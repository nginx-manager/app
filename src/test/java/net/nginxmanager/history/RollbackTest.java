package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RollbackTest extends ConfigRootTest {

    static final String PATH = "conf.d/sites/app.local.conf";

    @Autowired
    SiteService siteService;
    @Autowired
    ApplyService applyService;
    @Autowired
    RollbackService rollbackService;

    static SiteForm proxy(String target, Long version) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, target, version);
    }

    private Site site() {
        return siteRepository.findAll().getFirst();
    }

    private long applyProxy(String target) {
        if (siteRepository.count() == 0) {
            siteService.create(proxy(target, null), "editor");
        } else {
            siteService.update(site().getId(), proxy(target, site().getVersion()), "editor");
        }
        ApplyResult result = applyService.applyPending("editor", target);
        assertThat(result.succeeded()).isTrue();
        return result.versionId();
    }

    @Test
    void rollingBackRestoresEarlierFileThroughValidatedApply() {
        long first = applyProxy("http://first:1");
        applyProxy("http://second:2");
        int validationsBefore = nginx.validateCalls;

        ApplyResult result = rollbackService.rollback(first, "editor");

        assertThat(result.succeeded()).isTrue();
        assertThat(readFile(PATH)).contains("http://first:1").doesNotContain("second");
        assertThat(nginx.validateCalls).isEqualTo(validationsBefore + 1);
        ConfigVersion version = configVersionRepository.findById(result.versionId()).orElseThrow();
        assertThat(version.getSource()).isEqualTo(VersionSource.ROLLBACK);
        assertThat(version.getRolledBackToId()).isEqualTo(first);
        // 폼 값(second)이 되돌린 파일(first)과 맞지 않으므로 원문 사이트로 바뀐다
        assertThat(site().getType()).isEqualTo(SiteType.RAW);
        assertThat(driftDetector.refresh()).isEmpty();
    }

    @Test
    void rollingBackToBeforeSiteExistedRemovesSite() {
        writeFile("nginx.conf", "events {}\n");
        long base = applyRaw("nginx.conf", "events {}\nhttp {}\n");
        applyProxy("http://a:1");

        ApplyResult result = rollbackService.rollback(base, "editor");

        assertThat(result.succeeded()).isTrue();
        assertThat(readFile(PATH)).isNull();
        assertThat(siteRepository.count()).isZero();
        assertThat(configFileRepository.findByPath(PATH)).isEmpty();
    }

    @Test
    void rollingBackAcrossDisableReEnablesSite() {
        long enabled = applyProxy("http://a:1");
        siteService.setEnabled(site().getId(), false, site().getVersion(), "editor");
        assertThat(applyService.applyPending("editor", null).succeeded()).isTrue();
        assertThat(readFile("sites-disabled/app.local.conf")).isNotNull();

        assertThat(rollbackService.rollback(enabled, "editor").succeeded()).isTrue();

        assertThat(readFile(PATH)).contains("http://a:1");
        assertThat(readFile("sites-disabled/app.local.conf")).isNull();
        Site site = site();
        assertThat(site.isEnabled()).isTrue();
        assertThat(site.getType()).isEqualTo(SiteType.PROXY);
    }

    @Test
    void failedRollbackLeavesServerAndDraftsUntouched() {
        long first = applyProxy("http://first:1");
        applyProxy("http://second:2");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/" + PATH, 3, "host not found");

        ApplyResult result = rollbackService.rollback(first, "editor");

        assertThat(result.result()).isEqualTo(VersionResult.VALIDATION_FAILED);
        assertThat(readFile(PATH)).contains("http://second:2");
        assertThat(configFileRepository.countPending()).isZero();
    }

    @Test
    void rollbackIsRefusedWhilePendingDraftsExist() {
        long first = applyProxy("http://first:1");
        siteService.update(site().getId(), proxy("http://draft:3", site().getVersion()), "editor");

        assertThatThrownBy(() -> rollbackService.rollback(first, "editor"))
                .isInstanceOf(RollbackRejectedException.class)
                .extracting(e -> ((RollbackRejectedException) e).getReason())
                .isEqualTo(RollbackRejectedException.Reason.PENDING_CHANGES);
    }

    @Test
    void failedVersionCannotBeRollbackTarget() {
        applyProxy("http://first:1");
        siteService.update(site().getId(), proxy("http://bad:1", site().getVersion()), "editor");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/" + PATH, 1, "boom");
        long failed = applyService.applyPending("editor", null).versionId();
        siteService.discard(site().getConfigFile().getId(), "editor");
        nginx.reset();

        assertThatThrownBy(() -> rollbackService.rollback(failed, "editor"))
                .isInstanceOf(RollbackRejectedException.class);
    }

    @Test
    void rollbackToCurrentStateChangesNothing() {
        long latest = applyProxy("http://a:1");

        assertThat(rollbackService.rollback(latest, "editor").nothingChanged()).isTrue();
    }

    private long applyRaw(String path, String content) {
        var file = configFileRepository.findByPath(path).orElseGet(() -> configFileRepository.save(
                new net.nginxmanager.config.ConfigFile(path, false)));
        siteService.editRaw(file.getId(), content, configFileRepository.findById(file.getId()).orElseThrow().getVersion(),
                "editor");
        ApplyResult result = applyService.applyPending("editor", null);
        assertThat(result.succeeded()).isTrue();
        return result.versionId();
    }
}

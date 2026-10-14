package net.nginxmanager.apply;

import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionFile;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.runtime.HealthResult;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyServiceTest extends ConfigRootTest {

    static final String SITE_PATH = "conf.d/sites/app.local.conf";
    static final String ORIGINAL = "server { listen 80; server_name app.local; }\n";

    @Autowired
    ApplyService applyService;
    @Autowired
    SiteService siteService;
    @Autowired
    TransactionTemplate tx;

    static SiteForm proxy(String target, Long version) {
        return new SiteForm("app.local", "", SiteType.PROXY, 80, null, target, version);
    }

    /** 디스크에 이미 있는 파일을 관리 대상으로 등록하고, 바뀐 내용을 초안으로 올린다. */
    private ConfigFile stageChangeToExistingFile(String newContent) {
        writeFile(SITE_PATH, ORIGINAL);
        ConfigFile file = new ConfigFile(SITE_PATH, false);
        file.stageContent(newContent);
        return configFileRepository.save(file);
    }

    private ConfigVersion version(Long id) {
        return tx.execute(status -> {
            ConfigVersion version = configVersionRepository.findById(id).orElseThrow();
            version.getFiles().size();
            return version;
        });
    }

    @Test
    void successWritesFilesReloadsAndClearsDrafts() {
        siteService.create(proxy("http://127.0.0.1:8080", null), "editor");

        ApplyResult result = applyService.applyPending("editor", "first site");

        assertThat(result.result()).isEqualTo(VersionResult.SUCCESS);
        assertThat(readFile(SITE_PATH)).contains("proxy_pass http://127.0.0.1:8080;");
        assertThat(nginx.reloadCalls).isEqualTo(1);
        ConfigFile file = configFileRepository.findByPath(SITE_PATH).orElseThrow();
        assertThat(file.hasPendingChange()).isFalse();
        ConfigVersion version = version(result.versionId());
        assertThat(version.getMessage()).isEqualTo("first site");
        assertThat(version.getFiles()).extracting(ConfigVersionFile::getPath).contains(SITE_PATH);
    }

    @Test
    void validationFailureRestoresFilesAndKeepsDraft() {
        stageChangeToExistingFile("server { listen 80 broken; }\n");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/" + SITE_PATH, 1, "invalid parameter \"broken\"");

        ApplyResult result = applyService.applyPending("editor", null);

        assertThat(result.result()).isEqualTo(VersionResult.VALIDATION_FAILED);
        assertThat(result.messages()).singleElement()
                .satisfies(m -> assertThat(m.line()).isEqualTo(1));
        assertThat(readFile(SITE_PATH)).isEqualTo(ORIGINAL);
        assertThat(nginx.reloadCalls).isZero();
        assertThat(configFileRepository.findByPath(SITE_PATH).orElseThrow().getDraftContent())
                .contains("broken");
        assertThat(version(result.versionId()).getValidationOutput()).contains("test failed");
    }

    @Test
    void unhealthyReloadIsRolledBackAutomatically() {
        stageChangeToExistingFile("server { listen 80; server_name app.local; return 500; }\n");
        nginx.health = () -> HealthResult.failed("stub_status unreachable");

        ApplyResult result = applyService.applyPending("editor", null);

        assertThat(result.result()).isEqualTo(VersionResult.AUTO_RECOVERED);
        assertThat(readFile(SITE_PATH)).isEqualTo(ORIGINAL);
        assertThat(nginx.reloadCalls).isEqualTo(2);
        assertThat(configFileRepository.findByPath(SITE_PATH).orElseThrow().hasPendingChange()).isTrue();
    }

    @Test
    void newFileIsRemovedAgainWhenValidationFails() {
        siteService.create(proxy("http://127.0.0.1:8080", null), "editor");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/nginx.conf", 1, "boom");

        applyService.applyPending("editor", null);

        assertThat(readFile(SITE_PATH)).isNull();
    }

    @Test
    void deleteDraftRemovesFileAndRow() {
        writeFile(SITE_PATH, ORIGINAL);
        ConfigFile file = new ConfigFile(SITE_PATH, false);
        file.stageDelete();
        configFileRepository.save(file);

        ApplyResult result = applyService.applyPending("editor", null);

        assertThat(result.succeeded()).isTrue();
        assertThat(readFile(SITE_PATH)).isNull();
        assertThat(configFileRepository.findByPath(SITE_PATH)).isEmpty();
    }

    @Test
    void secondApplyWhileFirstRunsIsRejected() throws Exception {
        stageChangeToExistingFile("server { listen 81; }\n");
        nginx.validationStarted = new CountDownLatch(1);
        nginx.blockValidation = new CountDownLatch(1);

        CompletableFuture<ApplyResult> first = CompletableFuture.supplyAsync(() -> applyService.applyPending("a", null));
        assertThat(nginx.validationStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> applyService.applyPending("b", null)).isInstanceOf(ApplyInProgressException.class);
        nginx.blockValidation.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).succeeded()).isTrue();
    }

    @Test
    void nothingPendingDoesNothing() {
        ApplyResult result = applyService.applyPending("editor", null);

        assertThat(result.nothingChanged()).isTrue();
        assertThat(nginx.validateCalls).isZero();
        assertThat(configVersionRepository.count()).isZero();
        assertThat(result.changedPaths()).isEqualTo(List.of());
    }
}

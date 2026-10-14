package net.nginxmanager.apply;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 적용 도중 관리도구가 죽으면(edge case) 검증하지 않은 설정이 공유 볼륨에 남는다. 다음 시작 때 적어 둔 백업으로 되돌린다.
 */
class ApplyRecoveryTest extends ConfigRootTest {

    static final String CHANGED = "conf.d/sites/app.local.conf";
    static final String ADDED = "conf.d/sites/new.local.conf";

    @Autowired
    ApplyService applyService;
    @Autowired
    ApplyJournal journal;

    /** 쓰기까지 하고 검증 전에 프로세스가 죽은 상태를 만든다. */
    private void interruptedAfterWriting() {
        writeFile(CHANGED, "server { listen 80; server_name app.local; }\n");
        Map<String, String> backup = new HashMap<>();
        backup.put(CHANGED, "server { listen 80; server_name app.local; }\n");
        backup.put(ADDED, null);
        journal.begin("editor", backup);
        writeFile(CHANGED, "server { listen 80; server_name app.local; unvalidated }\n");
        writeFile(ADDED, "server { broken\n");
    }

    @Test
    void unfinishedApplyIsUndoneOnStartupAndNginxReloaded() {
        interruptedAfterWriting();

        assertThat(applyService.recoverInterruptedApply()).isTrue();

        assertThat(readFile(CHANGED)).isEqualTo("server { listen 80; server_name app.local; }\n");
        assertThat(CONFIG_ROOT.resolve(ADDED)).doesNotExist();
        assertThat(nginx.validateCalls).isEqualTo(1);
        assertThat(nginx.reloadCalls).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo(AuditAction.APPLY);
                    assertThat(log.getResult()).isEqualTo(AuditResult.FAILURE);
                    assertThat(log.getUsername()).isEqualTo("editor");
                    assertThat(log.getDetail()).startsWith("INTERRUPTED");
                }));
        // 한 번만 되돌린다
        assertThat(applyService.recoverInterruptedApply()).isFalse();
    }

    @Test
    void filesAreRestoredEvenWhenNginxIsNotReachable() {
        interruptedAfterWriting();
        nginx.unreachable = true;

        assertThat(applyService.recoverInterruptedApply()).isTrue();

        assertThat(readFile(CHANGED)).doesNotContain("unvalidated");
        assertThat(CONFIG_ROOT.resolve(ADDED)).doesNotExist();
        assertThat(nginx.reloadCalls).isZero();
    }

    @Test
    void finishedAppliesLeaveNothingToRecover() {
        writeFile("nginx.conf", "events {}\n");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/nginx.conf", 1, "x");
        applyService.applyChanges(() -> List.of(new FileChange(ADDED, "server {}\n")),
                new ApplyService.Origin("editor", "m", VersionSource.APPLY, null));
        nginx.validation = net.nginxmanager.support.FakeNginxRuntime::valid;
        applyService.applyChanges(() -> List.of(new FileChange(ADDED, "server {}\n")),
                new ApplyService.Origin("editor", "m", VersionSource.APPLY, null));

        assertThat(journal.pending()).isEmpty();
        assertThat(applyService.recoverInterruptedApply()).isFalse();
    }
}

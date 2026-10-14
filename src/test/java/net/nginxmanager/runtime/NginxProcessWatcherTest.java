package net.nginxmanager.runtime;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.dashboard.DashboardService;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class NginxProcessWatcherTest extends ConfigRootTest {

    @Autowired
    NginxProcessWatcher watcher;
    @Autowired
    DashboardService dashboardService;

    @Test
    void masterProcessReplacedByS6IsRecordedButReloadIsNot() {
        nginx.pid = 7L;
        watcher.check();
        auditLogRepository.deleteAll();
        watcher.check();
        assertThat(auditLogRepository.findAll()).noneMatch(l -> l.getAction() == AuditAction.NGINX_RESTART);

        nginx.running = false;
        watcher.check();
        nginx.running = true;
        nginx.pid = 42L;
        watcher.check();

        assertThat(auditLogRepository.findAll()).filteredOn(l -> l.getAction() == AuditAction.NGINX_RESTART)
                .extracting(l -> l.getResult())
                .containsExactly(AuditResult.FAILURE, AuditResult.SUCCESS);
        assertThat(watcher.lastEvent()).hasValueSatisfying(e -> {
            assertThat(e.down()).isFalse();
            assertThat(e.previousPid()).isEqualTo(7L);
            assertThat(e.currentPid()).isEqualTo(42L);
        });
        assertThat(dashboardService.load().lastProcessEvent()).isNotNull();
    }
}

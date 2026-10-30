package net.nginxmanager.dashboard;

import net.nginxmanager.admin.AdminDomainService;
import net.nginxmanager.backup.BackupStatus;
import net.nginxmanager.backup.BackupStatusReader;
import net.nginxmanager.site.SiteAccess;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    private final SiteAccess access;

    private final AdminDomainService adminDomain;

    private final BackupStatusReader backups;

    private final Clock clock;

    public DashboardController(DashboardService dashboardService, SiteAccess access, AdminDomainService adminDomain,
                               BackupStatusReader backups, Clock clock) {
        this.dashboardService = dashboardService;
        this.access = access;
        this.adminDomain = adminDomain;
        this.backups = backups;
        this.clock = clock;
    }

    @GetMapping("/")
    public String dashboard(Authentication authentication, Model model) {
        DashboardService.Dashboard dashboard = dashboardService.load(access.memberId(authentication).orElse(null));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("status", dashboard.status());
        model.addAttribute("adminDomain", adminDomain.status());
        if (access.memberId(authentication).isEmpty()) {
            BackupStatus backup = backups.read();
            model.addAttribute("backup", backup);
            model.addAttribute("backupWarning", backup.needsAttention(clock.instant()));
        }
        return "dashboard";
    }
}

package net.nginxmanager.dashboard;

import net.nginxmanager.admin.AdminDomainService;
import net.nginxmanager.site.SiteAccess;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    private final SiteAccess access;

    private final AdminDomainService adminDomain;

    public DashboardController(DashboardService dashboardService, SiteAccess access, AdminDomainService adminDomain) {
        this.dashboardService = dashboardService;
        this.access = access;
        this.adminDomain = adminDomain;
    }

    @GetMapping("/")
    public String dashboard(Authentication authentication, Model model) {
        DashboardService.Dashboard dashboard = dashboardService.load(access.memberId(authentication).orElse(null));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("status", dashboard.status());
        model.addAttribute("adminDomain", adminDomain.status());
        return "dashboard";
    }
}

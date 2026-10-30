package net.nginxmanager.dashboard;

import net.nginxmanager.site.SiteAccess;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    private final SiteAccess access;

    public DashboardController(DashboardService dashboardService, SiteAccess access) {
        this.dashboardService = dashboardService;
        this.access = access;
    }

    @GetMapping("/")
    public String dashboard(Authentication authentication, Model model) {
        DashboardService.Dashboard dashboard = dashboardService.load(access.memberId(authentication).orElse(null));
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("status", dashboard.status());
        return "dashboard";
    }
}

package net.nginxmanager.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        DashboardService.Dashboard dashboard = dashboardService.load();
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("status", dashboard.status());
        return "dashboard";
    }
}

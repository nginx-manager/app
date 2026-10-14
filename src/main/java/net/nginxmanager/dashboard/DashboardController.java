package net.nginxmanager.dashboard;

import net.nginxmanager.runtime.NginxRuntime;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final NginxRuntime runtime;

    public DashboardController(NginxRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("status", runtime.status());
        return "dashboard";
    }
}

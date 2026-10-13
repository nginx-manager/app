package net.nginxmanager.cert;

import net.nginxmanager.common.Messages;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Let's Encrypt 계정 설정(ADMIN). */
@Controller
@RequestMapping("/settings/acme")
public class AcmeSettingsController {

    private final AcmeSettingsRepository settingsRepository;
    private final AcmeProperties properties;
    private final ApplicationEventPublisher events;
    private final Messages messages;

    public AcmeSettingsController(AcmeSettingsRepository settingsRepository, AcmeProperties properties,
                                  ApplicationEventPublisher events, Messages messages) {
        this.messages = messages;
        this.settingsRepository = settingsRepository;
        this.properties = properties;
        this.events = events;
    }

    @GetMapping
    public String form(Model model) {
        model.addAttribute("settings", settingsRepository.findFirstByOrderByIdAsc().orElseGet(AcmeSettings::new));
        model.addAttribute("environments", AcmeEnvironment.values());
        model.addAttribute("overrideDirectory", properties.directoryUrl());
        return "settings/acme";
    }

    @PostMapping
    @Transactional
    public String save(@RequestParam AcmeEnvironment environment, @RequestParam(required = false) String contactEmail,
                       Authentication authentication, RedirectAttributes redirect) {
        if (contactEmail != null && !contactEmail.isBlank() && !contactEmail.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            redirect.addFlashAttribute("error", messages.get("msg.notify.invalidEmail"));
            return "redirect:/settings/acme";
        }
        AcmeSettings settings = settingsRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> settingsRepository.save(new AcmeSettings()));
        settings.update(environment, contactEmail);
        events.publishEvent(AuditEvent.success(authentication.getName(), AuditAction.USER_CHANGE,
                "settings:acme " + environment));
        redirect.addFlashAttribute("notice", messages.get("msg.saved"));
        return "redirect:/settings/acme";
    }
}

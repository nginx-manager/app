package net.nginxmanager.runtime;

import net.nginxmanager.common.Messages;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 내려간 nginx를 다시 띄운다(ADMIN). 띄우기 전에 지금 설정을 검증한다. */
@Controller
public class RuntimeController {

    private final NginxRuntime runtime;
    private final NginxRuntimeProperties properties;
    private final ApplyService applyService;
    private final ApplicationEventPublisher events;
    private final Messages messages;

    public RuntimeController(NginxRuntime runtime, NginxRuntimeProperties properties, ApplyService applyService,
                             ApplicationEventPublisher events, Messages messages) {
        this.messages = messages;
        this.runtime = runtime;
        this.properties = properties;
        this.applyService = applyService;
        this.events = events;
    }

    @PostMapping("/runtime/start")
    public String start(Authentication authentication, RedirectAttributes redirect) {
        String actor = authentication.getName();
        String error = applyService.withLock(() -> {
            RuntimeStatus.State state = runtime.status().state();
            if (state == RuntimeStatus.State.RUNNING) {
                return null;
            }
            if (state == RuntimeStatus.State.UNREACHABLE) {
                return messages.get("msg.runtime.unreachable");
            }
            ValidationResult validation = runtime.validate();
            if (!validation.ok()) {
                return messages.get("msg.runtime.validationFailed") + "\n" + validation.output();
            }
            try {
                runtime.start();
            } catch (NginxCommandException e) {
                return e.getMessage();
            }
            HealthResult health = runtime.healthCheck(properties.healthTimeout());
            return health.healthy() ? null : messages.get("msg.runtime.notStarted", health.detail());
        });
        if (error == null) {
            events.publishEvent(AuditEvent.success(actor, AuditAction.RUNTIME_START, "nginx"));
            redirect.addFlashAttribute("notice", messages.get("msg.runtime.running"));
        } else {
            events.publishEvent(AuditEvent.failure(actor, AuditAction.RUNTIME_START, "nginx", error));
            redirect.addFlashAttribute("error", error);
        }
        return "redirect:/";
    }
}

package net.nginxmanager.runtime;

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

    public RuntimeController(NginxRuntime runtime, NginxRuntimeProperties properties, ApplyService applyService,
                             ApplicationEventPublisher events) {
        this.runtime = runtime;
        this.properties = properties;
        this.applyService = applyService;
        this.events = events;
    }

    @PostMapping("/runtime/start")
    public String start(Authentication authentication, RedirectAttributes redirect) {
        String actor = authentication.getName();
        String error = applyService.withLock(() -> {
            if (runtime.status().state() == RuntimeStatus.State.RUNNING) {
                return null;
            }
            ValidationResult validation = runtime.validate();
            if (!validation.ok()) {
                return "설정 검증에 실패해 nginx를 띄우지 않았습니다. 설정을 고친 뒤 다시 시도하세요.\n" + validation.output();
            }
            try {
                runtime.start();
            } catch (NginxCommandException e) {
                return e.getMessage();
            }
            HealthResult health = runtime.healthCheck(properties.healthTimeout());
            return health.healthy() ? null : "nginx가 시작되지 않았습니다: " + health.detail();
        });
        if (error == null) {
            events.publishEvent(AuditEvent.success(actor, AuditAction.RUNTIME_START, "nginx"));
            redirect.addFlashAttribute("notice", "nginx가 실행 중입니다.");
        } else {
            events.publishEvent(AuditEvent.failure(actor, AuditAction.RUNTIME_START, "nginx", error));
            redirect.addFlashAttribute("error", error);
        }
        return "redirect:/";
    }
}

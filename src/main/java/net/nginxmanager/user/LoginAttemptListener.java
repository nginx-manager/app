package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/** 로그인 성공·실패를 기록하고, 5회 연속 실패하면 10분 동안 잠근다. */
@Component
public class LoginAttemptListener {

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public LoginAttemptListener(UserRepository userRepository, ApplicationEventPublisher events, Clock clock) {
        this.userRepository = userRepository;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        userRepository.findByUsername(username).ifPresent(User::recordLoginSuccess);
        events.publishEvent(AuditEvent.success(username, AuditAction.LOGIN, null));
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication().getName();
        userRepository.findByUsername(username).ifPresent(user -> {
            if (!user.isLocked(clock.instant())
                    && user.recordLoginFailure(MAX_ATTEMPTS, LOCK_DURATION, clock.instant())) {
                events.publishEvent(AuditEvent.failure(username, AuditAction.ACCOUNT_LOCKED, "user:" + username,
                        event.getException().getClass().getSimpleName()));
            }
        });
        events.publishEvent(AuditEvent.failure(username, AuditAction.LOGIN, null,
                event.getException().getClass().getSimpleName()));
    }
}

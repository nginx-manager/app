package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.common.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final UserSessions sessions;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
                       UserSessions sessions) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.sessions = sessions;
    }

    @Transactional
    public User createInitialAdmin(String username, String rawPassword) {
        if (userRepository.count() > 0) {
            throw new IllegalStateException("initial admin already exists");
        }
        User admin = userRepository.save(new User(username, passwordEncoder.encode(rawPassword), Role.ADMIN));
        events.publishEvent(AuditEvent.success(admin.getUsername(), AuditAction.USER_CREATE, "user:" + username));
        return admin;
    }

    /** 관리자가 초기 비밀번호와 역할을 정해 사용자를 만든다. */
    @Transactional
    public User invite(UserForms.Invite form, String actor) {
        if (userRepository.existsByUsername(form.username())) {
            throw new DuplicateUsernameException(form.username());
        }
        User user = userRepository.save(new User(form.username(), passwordEncoder.encode(form.password()), form.role()));
        events.publishEvent(AuditEvent.success(actor, AuditAction.USER_CREATE,
                "user:" + user.getUsername() + " role:" + user.getRole()));
        return user;
    }

    @Transactional
    public void changeRole(long userId, Role role, long expectedVersion, String actor) {
        User user = load(userId, expectedVersion);
        if (user.getRole() == role) {
            return;
        }
        if (user.getRole() == Role.ADMIN && user.isEnabled()) {
            requireAnotherAdmin();
        }
        Role before = user.getRole();
        user.changeRole(role);
        sessions.expire(user.getUsername());
        events.publishEvent(AuditEvent.success(actor, AuditAction.USER_CHANGE,
                "user:" + user.getUsername() + " role:" + before + "->" + role));
    }

    @Transactional
    public void setEnabled(long userId, boolean enabled, long expectedVersion, String actor) {
        User user = load(userId, expectedVersion);
        if (user.isEnabled() == enabled) {
            return;
        }
        if (!enabled && user.getRole() == Role.ADMIN) {
            requireAnotherAdmin();
        }
        user.setEnabled(enabled);
        if (!enabled) {
            sessions.expire(user.getUsername());
        }
        events.publishEvent(AuditEvent.success(actor, AuditAction.USER_CHANGE,
                "user:" + user.getUsername() + (enabled ? " enable" : " disable")));
    }

    /** 관리자가 다른 사용자의 비밀번호를 새로 정한다. 잠금도 풀린다. */
    @Transactional
    public void resetPassword(long userId, String rawPassword, long expectedVersion, String actor) {
        User user = load(userId, expectedVersion);
        user.changePassword(passwordEncoder.encode(rawPassword));
        sessions.expire(user.getUsername());
        events.publishEvent(AuditEvent.success(actor, AuditAction.USER_CHANGE,
                "user:" + user.getUsername() + " password reset"));
    }

    /** 본인 비밀번호 변경. 현재 비밀번호가 맞아야 한다. */
    @Transactional
    public boolean changeOwnPassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username).orElseThrow(() -> new NotFoundException("user " + username));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            events.publishEvent(AuditEvent.failure(username, AuditAction.USER_CHANGE, "user:" + username + " password",
                    "current password mismatch"));
            return false;
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        events.publishEvent(AuditEvent.success(username, AuditAction.USER_CHANGE, "user:" + username + " password"));
        return true;
    }

    private void requireAnotherAdmin() {
        if (userRepository.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new LastAdminException();
        }
    }

    private User load(long userId, long expectedVersion) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("user " + userId));
        if (user.getVersion() != expectedVersion) {
            throw new ConcurrentEditException("user " + user.getUsername());
        }
        return user;
    }
}

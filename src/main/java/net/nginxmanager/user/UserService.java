package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
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
}

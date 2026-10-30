package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.support.ConfigRootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserManagementTest extends ConfigRootTest {

    @Autowired
    UserService userService;
    @Autowired
    PasswordEncoder passwordEncoder;

    User admin;

    @BeforeEach
    void admin() {
        admin = userRepository.save(new User("admin", passwordEncoder.encode("admin-password"), Role.ADMIN));
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @Test
    void inviteCreatesUserWithRoleAndAudits() {
        User viewer = userService.invite(new UserForms.Invite("viewer", "viewer-password", Role.MEMBER), "admin");

        assertThat(viewer.getRole()).isEqualTo(Role.MEMBER);
        assertThat(passwordEncoder.matches("viewer-password", viewer.getPasswordHash())).isTrue();
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo(AuditAction.USER_CREATE);
            assertThat(log.getTarget()).contains("viewer");
        });
        assertThatThrownBy(() -> userService.invite(new UserForms.Invite("viewer", "other-password", Role.MEMBER), "admin"))
                .isInstanceOf(DuplicateUsernameException.class);
    }

    @Test
    void lastEnabledAdminCannotBeDemotedOrDisabled() {
        assertThatThrownBy(() -> userService.changeRole(admin.getId(), Role.MEMBER, admin.getVersion(), "admin"))
                .isInstanceOf(LastAdminException.class);
        assertThatThrownBy(() -> userService.setEnabled(admin.getId(), false, admin.getVersion(), "admin"))
                .isInstanceOf(LastAdminException.class);

        User second = userService.invite(new UserForms.Invite("second", "second-password", Role.ADMIN), "admin");
        userService.changeRole(admin.getId(), Role.MEMBER, admin.getVersion(), "second");

        assertThat(reload(admin).getRole()).isEqualTo(Role.MEMBER);
        User secondNow = reload(second);
        assertThatThrownBy(() -> userService.setEnabled(secondNow.getId(), false, secondNow.getVersion(), "second"))
                .isInstanceOf(LastAdminException.class);
    }

    @Test
    void disabledAdminDoesNotCountAsRemainingAdmin() {
        User second = userService.invite(new UserForms.Invite("second", "second-password", Role.ADMIN), "admin");
        userService.setEnabled(second.getId(), false, reload(second).getVersion(), "admin");

        User adminNow = reload(admin);
        assertThatThrownBy(() -> userService.changeRole(adminNow.getId(), Role.MEMBER, adminNow.getVersion(), "admin"))
                .isInstanceOf(LastAdminException.class);
    }

    @Test
    void passwordResetUnlocksAccount() {
        User editor = userService.invite(new UserForms.Invite("editor", "editor-password", Role.MEMBER), "admin");
        for (int i = 0; i < 5; i++) {
            editor = reload(editor);
            editor.recordLoginFailure(5, Duration.ofMinutes(10), Instant.now());
            editor = userRepository.save(editor);
        }
        assertThat(reload(editor).isLockedNow()).isTrue();

        userService.resetPassword(editor.getId(), "brand-new-password", reload(editor).getVersion(), "admin");

        User after = reload(editor);
        assertThat(after.isLockedNow()).isFalse();
        assertThat(passwordEncoder.matches("brand-new-password", after.getPasswordHash())).isTrue();
    }

    @Test
    void ownPasswordChangeRequiresCurrentPassword() {
        assertThat(userService.changeOwnPassword("admin", "wrong", "next-password-1")).isFalse();
        assertThat(userService.changeOwnPassword("admin", "admin-password", "next-password-1")).isTrue();
        assertThat(passwordEncoder.matches("next-password-1", reload(admin).getPasswordHash())).isTrue();
    }
}

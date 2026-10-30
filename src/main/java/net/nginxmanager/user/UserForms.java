package net.nginxmanager.user;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 사용자 관리 화면의 입력. */
public final class UserForms {

    private UserForms() {
    }

    public record Invite(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "{msg.validation.username}")
            String username,
            @NotBlank @Size(min = 10, max = 100, message = "{msg.validation.passwordLength}")
            String password,
            @NotNull Role role) {

        public static Invite empty() {
            return new Invite("", "", Role.MEMBER);
        }
    }

    public record PasswordChange(
            String currentPassword,
            @NotBlank @Size(min = 10, max = 100, message = "{msg.validation.passwordLength}")
            String newPassword,
            String newPasswordConfirm) {

        @AssertTrue(message = "{msg.validation.newPasswordMismatch}")
        public boolean isConfirmed() {
            return newPassword != null && newPassword.equals(newPasswordConfirm);
        }

        public static PasswordChange empty() {
            return new PasswordChange("", "", "");
        }
    }
}

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
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "영문, 숫자, . _ - 만 쓸 수 있습니다.")
            String username,
            @NotBlank @Size(min = 10, max = 100, message = "비밀번호는 10자 이상이어야 합니다.")
            String password,
            @NotNull Role role) {

        public static Invite empty() {
            return new Invite("", "", Role.VIEWER);
        }
    }

    public record PasswordChange(
            String currentPassword,
            @NotBlank @Size(min = 10, max = 100, message = "비밀번호는 10자 이상이어야 합니다.")
            String newPassword,
            String newPasswordConfirm) {

        @AssertTrue(message = "새 비밀번호가 서로 다릅니다.")
        public boolean isConfirmed() {
            return newPassword != null && newPassword.equals(newPasswordConfirm);
        }

        public static PasswordChange empty() {
            return new PasswordChange("", "", "");
        }
    }
}

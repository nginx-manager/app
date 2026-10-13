package net.nginxmanager.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SetupForm(
        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "영문, 숫자, . _ - 만 쓸 수 있습니다.")
        String username,
        @NotBlank @Size(min = 10, max = 100, message = "비밀번호는 10자 이상이어야 합니다.")
        String password,
        String passwordConfirm) {
}

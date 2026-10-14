package net.nginxmanager.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SetupForm(
        @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "{msg.validation.username}")
        String username,
        @NotBlank @Size(min = 10, max = 100, message = "{msg.validation.passwordLength}")
        String password,
        String passwordConfirm) {
}

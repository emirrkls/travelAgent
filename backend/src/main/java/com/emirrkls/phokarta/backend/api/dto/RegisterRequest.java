package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "^[a-zA-Z0-9_]+$",
                message = "Username may contain letters, digits, and underscores only")
        String username,
        @NotBlank @Size(min = 1, max = 100) String displayName,
        @NotBlank @Size(min = 8, max = 72) String password) {
}

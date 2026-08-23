package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 320) String identifier,
        @NotBlank @Size(min = 1, max = 72) String password) {
}

package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank @Size(min = 20, max = 512) String refreshToken) {
}

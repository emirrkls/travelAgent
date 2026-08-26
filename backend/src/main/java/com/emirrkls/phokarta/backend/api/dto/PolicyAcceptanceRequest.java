package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PolicyAcceptanceRequest(
        @NotBlank @Size(max = 64) String policyVersion) {
}

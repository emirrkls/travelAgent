package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateConversationEntryRequest(
        @NotBlank @Size(max = 1000) String body) {
}

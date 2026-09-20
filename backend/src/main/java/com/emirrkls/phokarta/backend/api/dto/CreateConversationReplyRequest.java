package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateConversationReplyRequest(
        @NotNull UUID clientMutationId,
        @NotBlank @Size(max = 1000) String body) {
}

package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ConversationEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateConversationEntryRequest(
        @NotNull UUID clientMutationId,
        @NotNull ConversationEntryType type,
        @NotBlank @Size(max = 1000) String body) {
}

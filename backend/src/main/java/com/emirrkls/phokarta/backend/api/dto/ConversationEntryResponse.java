package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ConversationEntryType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ConversationEntryResponse(
        UUID id,
        UUID experienceId,
        ConversationEntryType type,
        String body,
        Author author,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean edited,
        boolean experienceAuthor,
        boolean ownedByViewer,
        boolean reportableByViewer,
        List<ConversationEntryResponse> replies) {

    public record Author(UUID id, String username, String displayName, String avatarUrl) {
    }
}

package com.emirrkls.phokarta.backend.api.dto;

import java.util.UUID;

public record UserSummaryResponse(
        UUID id,
        String username,
        String displayName,
        String avatarUrl,
        RelationshipStateResponse relationship
) {
}

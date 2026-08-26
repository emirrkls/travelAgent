package com.emirrkls.phokarta.backend.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BlockedUserResponse(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        OffsetDateTime blockedAt
) {
}

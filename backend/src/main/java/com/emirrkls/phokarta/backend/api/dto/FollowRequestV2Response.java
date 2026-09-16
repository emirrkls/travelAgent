package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FollowRequestV2Response(
        UUID id,
        UserIdentity requester,
        FollowRequestStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt) {
    public record UserIdentity(UUID id, String username, String displayName, String avatarUrl) {}
}

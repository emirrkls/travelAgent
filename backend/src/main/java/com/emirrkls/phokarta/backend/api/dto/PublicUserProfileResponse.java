package com.emirrkls.phokarta.backend.api.dto;

import java.util.UUID;

public record PublicUserProfileResponse(
        UUID id,
        String username,
        String displayName,
        String avatarUrl,
        String bio,
        int cityCount,
        int countryCount,
        long followerCount,
        long followingCount,
        long friendCount,
        RelationshipStateResponse relationship
) {
}

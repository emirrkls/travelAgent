package com.emirrkls.phokarta.backend.api.dto;

import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        long followerCount,
        long followingCount,
        long friendCount) {
}

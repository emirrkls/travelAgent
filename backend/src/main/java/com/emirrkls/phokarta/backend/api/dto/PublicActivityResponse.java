package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Cross-place public visit event for the Activity feed.
 * Structurally excludes privateMemory, personal notes, and private user fields.
 */
public record PublicActivityResponse(
        UUID visitId,
        PublicAuthor author,
        ActivityPlace place,
        double overallScore,
        String publicReview,
        LocalDate visitedAt
) {
    public record PublicAuthor(
            UUID id,
            String username,
            String displayName,
            String avatarUrl
    ) {
    }

    public record ActivityPlace(
            UUID id,
            String name,
            PlaceCategory category,
            String city,
            String coverImage
    ) {
    }
}

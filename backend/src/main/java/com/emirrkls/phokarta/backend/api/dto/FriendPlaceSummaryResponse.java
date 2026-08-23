package com.emirrkls.phokarta.backend.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Viewer-relative friends lens for a place.
 * averageScore is the mean of per-friend averages (user-weighted), never visit-weighted.
 * Qualifying Visits are friend-readable (PUBLIC or FRIENDS); PRIVATE never contributes.
 * Structurally excludes privateMemory, email, and auth fields.
 * Null averageScore is omitted (never serialized as 0.0).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FriendPlaceSummaryResponse(
        Double averageScore,
        long friendsVisitedCount,
        List<FriendPreview> friends
) {
    /**
     * Unique mutual friend who visited with a friend-readable Visit.
     * latestScore is that friend's newest friend-readable Visit score (PRIVATE ignored).
     * Aggregate Friends score uses each friend's average across their friend-readable Visits.
     */
    public record FriendPreview(
            UUID userId,
            String displayName,
            String avatarUrl,
            double latestScore,
            LocalDate latestVisitedAt
    ) {
    }
}

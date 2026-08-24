package com.emirrkls.phokarta.backend.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Viewer-relative aggregates only: user-weighted friends score and distinct friends visited.
 * friendAverageScore is omitted when friendsVisitedCount is 0 (never serialized as 0.0).
 * No friend identities, review text, privateMemory, or PRIVATE Visit payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FriendPlaceMetricsResponse(
        UUID placeId,
        Double friendAverageScore,
        long friendsVisitedCount
) {
}

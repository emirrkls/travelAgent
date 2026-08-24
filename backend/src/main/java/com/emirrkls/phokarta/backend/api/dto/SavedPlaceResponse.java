package com.emirrkls.phokarta.backend.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * Owner saved place with optional viewer-relative friend overlap metrics.
 * friendAverageScore is omitted when friendsVisitedCount is 0 (never serialized as 0.0).
 * Community scores stay on {@link PlaceSummaryResponse} (PUBLIC Visits only).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedPlaceResponse(
        PlaceSummaryResponse place,
        OffsetDateTime savedAt,
        Double friendAverageScore,
        long friendsVisitedCount
) {
    public SavedPlaceResponse(PlaceSummaryResponse place, OffsetDateTime savedAt) {
        this(place, savedAt, null, 0L);
    }
}

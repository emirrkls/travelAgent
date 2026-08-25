package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VisitOwnerResponse(
        UUID id, PlaceSummaryResponse place, LocalDate visitedAt, double overallRating,
        List<DimensionScoreResponse> dimensions, String publicReview, String privateMemory,
        List<String> legacyPhotoUrls, List<VisitMediaResponse> media,
        Visibility visibility, VerificationStatus verificationStatus) {
    /** Deprecated source-compatible name for callers reading legacy Visit URLs. */
    @Deprecated
    @JsonProperty("photos")
    public List<String> photos() {
        return legacyPhotoUrls;
    }

    public record DimensionScoreResponse(String key, double score) {
    }
}

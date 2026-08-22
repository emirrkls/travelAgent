package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record VisitOwnerResponse(
        UUID id, PlaceSummaryResponse place, LocalDate visitedAt, double overallRating,
        List<DimensionScoreResponse> dimensions, String publicReview, String privateMemory,
        List<String> photos, Visibility visibility, VerificationStatus verificationStatus) {
    public record DimensionScoreResponse(String key, double score) {
    }
}

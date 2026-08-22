package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PublicVisitResponse(
        UUID id, UUID placeId, String placeName, UUID userId, String username,
        String displayName, String avatarUrl, LocalDate visitedAt, double overallRating,
        String publicReview, List<String> photos, VerificationStatus verificationStatus) {
}

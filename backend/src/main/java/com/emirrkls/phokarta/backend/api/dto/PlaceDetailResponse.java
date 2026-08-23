package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record PlaceDetailResponse(
        UUID id, String name, String description, PlaceCategory category,
        List<String> subcategories, double latitude, double longitude,
        String city, String region, String country, String address,
        String coverImage, List<String> photos, int priceLevel,
        @Schema(description = "Community average of PUBLIC Visit overall ratings; null when unrated",
                nullable = true)
        Double averageScore,
        @Schema(description = "Number of PUBLIC Visit ratings contributing to averageScore")
        long ratingCount,
        @Schema(description = "Per-dimension averages from PUBLIC Visits only")
        List<DimensionAggregateResponse> dimensionScores,
        List<PublicVisitResponse> recentPublicReviews) {
    public record DimensionAggregateResponse(String key, double average) {
    }
}

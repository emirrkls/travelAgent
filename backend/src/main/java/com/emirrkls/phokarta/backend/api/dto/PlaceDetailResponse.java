package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;

import java.util.List;
import java.util.UUID;

public record PlaceDetailResponse(
        UUID id, String name, String description, PlaceCategory category,
        List<String> subcategories, double latitude, double longitude,
        String city, String region, String country, String address,
        String coverImage, List<String> photos, int priceLevel,
        Double averageScore, long ratingCount,
        List<DimensionAggregateResponse> dimensionScores,
        List<PublicVisitResponse> recentPublicReviews) {
    public record DimensionAggregateResponse(String key, double average) {
    }
}

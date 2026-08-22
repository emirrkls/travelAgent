package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;

import java.util.UUID;

public record PlaceSummaryResponse(
        UUID id, String name, PlaceCategory category, String coverImage,
        String city, String region, String country, double latitude, double longitude,
        int priceLevel, Double averageScore, long ratingCount) {
}

package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record PlaceSummaryResponse(
        UUID id, String name, PlaceCategory category, String coverImage,
        String city, String region, String country, double latitude, double longitude,
        int priceLevel,
        @Schema(description = "Community average of PUBLIC Visit overall ratings; null when unrated",
                nullable = true)
        Double averageScore,
        @Schema(description = "Number of PUBLIC Visit ratings contributing to averageScore")
        long ratingCount) {
}

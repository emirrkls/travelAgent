package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.Visibility;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionSummaryResponse(
        UUID id, UUID userId, String title, String description, Visibility visibility,
        String coverImage, long placeCount, OffsetDateTime updatedAt) {
}

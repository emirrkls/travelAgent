package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.Visibility;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CollectionDetailResponse(
        UUID id, UUID userId, String title, String description, Visibility visibility,
        String coverImage, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        List<CollectionPlaceResponse> places) {
    public record CollectionPlaceResponse(
            PlaceSummaryResponse place, int displayOrder, OffsetDateTime addedAt) {
    }
}

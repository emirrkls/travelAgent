package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.Visibility;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CollectionV2DetailResponse(
        UUID id, UUID ownerUserId, String title, String description, Visibility visibility,
        String coverImage, OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Item> items) {
    public enum ItemType { PLACE, EXPERIENCE }
    public record Item(ItemType type, int displayOrder, OffsetDateTime addedAt,
                       PlaceSummaryResponse place, ExperienceV2Response experience) {}
}

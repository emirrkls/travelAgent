package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Batch lookup of viewer-relative friend metrics for map/discovery Place IDs.
 * Duplicate IDs are ignored after the first occurrence. Max 200 IDs (map bounds cap).
 */
public record FriendPlaceMetricsRequest(
        @NotNull
        @Size(max = 200)
        List<UUID> placeIds
) {
}

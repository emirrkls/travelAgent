package com.emirrkls.phokarta.backend.api.dto;

import java.time.OffsetDateTime;

public record SavedPlaceResponse(PlaceSummaryResponse place, OffsetDateTime savedAt) {
}

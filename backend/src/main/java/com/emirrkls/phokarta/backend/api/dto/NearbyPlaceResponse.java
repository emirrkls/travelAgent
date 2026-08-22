package com.emirrkls.phokarta.backend.api.dto;

public record NearbyPlaceResponse(PlaceSummaryResponse place, double distanceMeters) {
}

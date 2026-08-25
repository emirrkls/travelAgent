package com.emirrkls.phokarta.backend.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

public record VisitMediaResponse(
        UUID id,
        int sortOrder,
        @Schema(description = "Short-lived bearer URL; never persist or log it.")
        URI accessUrl,
        OffsetDateTime accessExpiresAt) {
}

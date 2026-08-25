package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.MediaStatus;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record MediaUploadIntentResponse(
        UUID mediaId,
        MediaStatus status,
        URI uploadUrl,
        Map<String, String> requiredHeaders,
        OffsetDateTime expiresAt) {
}

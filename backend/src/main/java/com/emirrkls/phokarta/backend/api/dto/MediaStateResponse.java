package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.MediaStatus;

import java.util.UUID;

public record MediaStateResponse(UUID mediaId, MediaStatus status) {
}

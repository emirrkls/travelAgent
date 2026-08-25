package com.emirrkls.phokarta.backend.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MediaUploadIntentRequest(
        @NotNull UUID clientMediaId,
        @NotBlank String contentType,
        @NotNull @Min(1) Long byteSize,
        @Min(1) Integer width,
        @Min(1) Integer height) {
}

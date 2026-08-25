package com.emirrkls.phokarta.backend.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.OffsetDateTime;

public record MediaAccessResponse(
        @Schema(description = "Short-lived bearer URL. Treat as sensitive and do not log, persist, "
                + "or share it; anyone holding it can read the object until expiry.")
        URI url,
        OffsetDateTime expiresAt) {
}

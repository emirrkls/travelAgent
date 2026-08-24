package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.Visibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateVisitRequest(
        @Schema(description = "Client-generated idempotency key. Retries with the same authenticated "
                + "user, key, and payload return the original Visit; a different payload returns 409. "
                + "Optional only for backward compatibility with legacy clients.")
        UUID clientMutationId,
        @NotNull UUID placeId,
        @NotNull @PastOrPresent LocalDate visitedAt,
        @NotNull @DecimalMin("0.0") @DecimalMax("10.0") Double overallRating,
        @Valid @Size(max = 20) List<DimensionScore> dimensions,
        @Size(max = 4000) String publicReview,
        @Size(max = 4000) String privateMemory,
        @Size(max = 20) List<@NotBlank @Size(max = 500) String> photos,
        @NotNull
        @Schema(description = "Visit audience. PUBLIC is community-readable and friend-readable "
                + "(community score/reviews/activity plus mutual-friend discovery). "
                + "FRIENDS is mutual-friend-readable only and is excluded from community "
                + "discovery. PRIVATE is owner-only and never appears in discovery. "
                + "Required; clients must send an explicit value.")
        Visibility visibility) {

    /** Source-compatible constructor for server tests and legacy in-process callers. */
    public CreateVisitRequest(UUID placeId, LocalDate visitedAt, Double overallRating,
                              List<DimensionScore> dimensions, String publicReview,
                              String privateMemory, List<String> photos, Visibility visibility) {
        this(null, placeId, visitedAt, overallRating, dimensions, publicReview,
                privateMemory, photos, visibility);
    }

    public record DimensionScore(
            @NotBlank @Size(max = 40) String key,
            @NotNull @DecimalMin("0.0") @DecimalMax("10.0") Double score) {
    }
}

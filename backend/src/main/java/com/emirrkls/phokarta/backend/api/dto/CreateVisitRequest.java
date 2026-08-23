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
        @NotNull UUID placeId,
        @NotNull @PastOrPresent LocalDate visitedAt,
        @NotNull @DecimalMin("0.0") @DecimalMax("10.0") Double overallRating,
        @Valid @Size(max = 20) List<DimensionScore> dimensions,
        @Size(max = 4000) String publicReview,
        @Size(max = 4000) String privateMemory,
        @Size(max = 20) List<@NotBlank @Size(max = 500) String> photos,
        @NotNull
        @Schema(description = "Visit audience. PUBLIC participates in community discovery, "
                + "community reviews, community activity, and friends discovery aggregates. "
                + "FRIENDS is stored for the owner but is currently excluded from community and "
                + "friends discovery surfaces. PRIVATE is owner-only and never appears in "
                + "discovery. Required; clients must send an explicit value.")
        Visibility visibility) {

    public record DimensionScore(
            @NotBlank @Size(max = 40) String key,
            @NotNull @DecimalMin("0.0") @DecimalMax("10.0") Double score) {
    }
}

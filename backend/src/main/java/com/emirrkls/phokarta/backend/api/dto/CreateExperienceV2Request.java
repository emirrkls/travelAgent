package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.CompanionCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.DimensionStateCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TimeOfDayCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TitleSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Native Experience V2 publication contract. Numeric compatibility values are server-owned. */
public record CreateExperienceV2Request(
        @NotNull UUID clientMutationId,
        @NotNull UUID placeId,
        @NotNull @PastOrPresent LocalDate visitDate,
        @NotNull PrimaryExperienceCode primaryExperienceCode,
        @Size(max = 120) String rawExperienceLabel,
        @NotNull OverallFeelingCode overallFeelingCode,
        CompanionCode companionCode,
        TimeOfDayCode timeOfDayCode,
        @Size(max = 2) List<@NotNull VibeCode> vibeCodes,
        @Size(max = 16) List<@NotNull PracticalSignalCode> practicalSignalCodes,
        @Valid @Size(max = 20) List<Dimension> dimensions,
        @Size(max = 240) String title,
        @NotNull TitleSource titleSource,
        @Size(max = 4000) String story,
        @Size(max = 1000) String tip,
        @Size(max = 4000) String privateMemory,
        @NotNull Visibility visibility,
        @Size(max = 6) List<@NotNull UUID> mediaIds,
        UUID originAcknowledgementId) {

    public record Dimension(
            @NotBlank @Size(max = 40) String key,
            @NotNull DimensionStateCode semanticStateCode,
            @NotNull @Min(1) Integer templateVersion) {}
}

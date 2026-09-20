package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ExperienceAcknowledgementV2Response(
        UUID id,
        UUID ownerUserId,
        UUID sourceExperienceId,
        boolean sourceAvailable,
        ExperienceV2Response sourceExperience,
        AnchorPlace place,
        PrimaryExperienceCode primaryExperienceCode,
        String rawExperienceLabel,
        OffsetDateTime acknowledgedAt,
        Status status,
        UUID convertedExperienceId) {
    public enum Status { UNCONVERTED, CONVERTED }
    public record AnchorPlace(UUID id, String name, String city, String region, String country) {}
}

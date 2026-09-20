package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.CompanionCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TimeOfDayCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TitleSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Compact feed representation. It deliberately has no private-memory or dimension fields. */
public record ExperienceSummaryV2Response(
        UUID id,
        ExperienceV2Response.Classification classification,
        Author author,
        Place place,
        LocalDate experiencedAt,
        String title,
        TitleSource titleSource,
        ExperienceV2Response.PrimaryExperience primaryExperience,
        OverallFeelingCode feeling,
        String storyPreview,
        String tipPreview,
        CompanionCode companion,
        TimeOfDayCode timeOfDay,
        List<VibeCode> vibes,
        List<PracticalSignalCode> practicalSignals,
        MediaPreview mediaPreview,
        int mediaCount,
        Visibility visibility,
        boolean plannedByViewer,
        boolean acknowledgedByViewer,
        long acknowledgementCount,
        long conversationCount) {

    public record Author(
            UUID id,
            String username,
            String displayName,
            String avatarUrl,
            RelationshipV2Response relationship) {
    }

    public record Place(
            UUID id,
            String name,
            PlaceCategory category,
            String city,
            String region,
            String country,
            String coverImage,
            Double distanceMeters) {
    }

    public record MediaPreview(
            ExperienceV2Response.MediaKind kind,
            UUID id,
            String url,
            OffsetDateTime accessExpiresAt) {
    }
}

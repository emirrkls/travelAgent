package com.emirrkls.phokarta.backend.api.dto;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.CompanionCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.DimensionStateCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.ExperienceFamily;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.FeelingSource;
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

/** Read-only V2 representation backed by the stable Visit aggregate identity. */
public record ExperienceV2Response(
        UUID id,
        Classification classification,
        Author author,
        Place place,
        LocalDate experiencedAt,
        String title,
        TitleSource titleSource,
        boolean titlePersisted,
        String story,
        String tip,
        Feeling feeling,
        PrimaryExperience primaryExperience,
        CompanionCode companion,
        TimeOfDayCode timeOfDay,
        List<VibeCode> vibes,
        List<PracticalSignalCode> practicalSignals,
        List<Dimension> dimensions,
        List<Media> media,
        Visibility visibility,
        Integer taxonomyVersion,
        boolean plannedByViewer,
        boolean acknowledgedByViewer,
        long acknowledgementCount,
        long conversationCount) {

    public enum Classification { LEGACY_COMPATIBILITY, NATIVE_V2 }
    public enum MediaKind { LEGACY_URL, MANAGED }

    public record Author(
            UUID id,
            String username,
            String displayName,
            String avatarUrl,
            RelationshipV2Response relationship) {}

    public record Place(
            UUID id,
            String name,
            PlaceCategory category,
            String city,
            String region,
            String country,
            String coverImage) {}

    public record Feeling(
            OverallFeelingCode code,
            FeelingSource source,
            double compatibilityNumericRating) {}

    public record PrimaryExperience(
            String code,
            boolean canonical,
            ExperienceFamily family,
            String rawLabel) {}

    public record Dimension(
            String key,
            double numericScore,
            DimensionStateCode semanticState,
            Integer templateVersion) {}

    public record Media(
            MediaKind kind,
            int position,
            UUID id,
            String url,
            OffsetDateTime accessExpiresAt) {}
}

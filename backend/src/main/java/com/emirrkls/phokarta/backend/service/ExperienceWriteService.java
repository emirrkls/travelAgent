package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateExperienceV2Request;
import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScore;
import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.ExperienceFamily;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.FeelingSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TitleSource;
import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.repository.ExperienceAcknowledgementRepository;
import com.emirrkls.phokarta.backend.domain.entity.ExperienceAcknowledgement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ExperienceWriteService {
    static final int TAXONOMY_VERSION = 1;
    static final int DIMENSION_TEMPLATE_VERSION = 1;
    static final int V2_MEDIA_LIMIT = 6;

    private final VisitRepository visits;
    private final VisitExperienceDetailRepository details;
    private final VisitDimensionScoreRepository dimensions;
    private final UserRepository users;
    private final PlaceRepository places;
    private final MediaService media;
    private final UgcPolicyService ugcPolicy;
    private final ExperienceReadService reader;
    private final ExperienceAcknowledgementRepository acknowledgements;

    public ExperienceWriteService(
            VisitRepository visits,
            VisitExperienceDetailRepository details,
            VisitDimensionScoreRepository dimensions,
            UserRepository users,
            PlaceRepository places,
            MediaService media,
            UgcPolicyService ugcPolicy,
            ExperienceReadService reader,
            ExperienceAcknowledgementRepository acknowledgements) {
        this.visits = visits;
        this.details = details;
        this.dimensions = dimensions;
        this.users = users;
        this.places = places;
        this.media = media;
        this.ugcPolicy = ugcPolicy;
        this.reader = reader;
        this.acknowledgements = acknowledgements;
    }

    @Transactional
    public ExperienceV2Response create(UUID userId, CreateExperienceV2Request request) {
        users.lockAccount(userId);
        ugcPolicy.requireAccepted(userId);
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        Place place = places.findById(request.placeId())
                .orElseThrow(() -> ApiException.notFound("Place", request.placeId()));

        ExperienceAcknowledgement origin = null;
        if (request.originAcknowledgementId() != null) {
            origin = acknowledgements.findByIdForUpdate(request.originAcknowledgementId())
                    .orElseThrow(() -> ApiException.notFound("Acknowledgement", request.originAcknowledgementId()));
            if (!origin.getUser().getId().equals(userId)) {
                throw ApiException.forbidden("You do not own this acknowledgement");
            }
            if (!origin.getPlace().getId().equals(request.placeId())
                    || origin.getPrimaryExperienceCode() != request.primaryExperienceCode()
                    || !java.util.Objects.equals(origin.getRawExperienceLabel(), normalized(request.rawExperienceLabel()))) {
                throw ApiException.validation("Publication does not match the acknowledgement anchor");
            }
        }

        Canonical canonical = canonicalize(request, place);
        visits.lockClientMutation(userId, request.clientMutationId());
        Visit existing = visits.findByUserIdAndClientMutationId(userId, request.clientMutationId())
                .orElse(null);
        if (existing != null) {
            if (!canonical.fingerprint().equals(existing.getClientPayloadFingerprint())
                    || !details.existsById(existing.getId())) {
                throw ApiException.conflict(
                        "clientMutationId was already used with a different Experience payload");
            }
            if (origin != null) origin.convertTo(existing, OffsetDateTime.now(ZoneOffset.UTC));
            return reader.getVisible(existing.getId(), userId);
        }
        if (origin != null && origin.isConverted()) {
            throw ApiException.conflict("Acknowledgement was already converted to another Experience");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Visit visit = visits.save(new Visit(
                UUID.randomUUID(), user, place, request.clientMutationId(), canonical.fingerprint(),
                request.visitDate(), request.overallFeelingCode().compatibilityScore(),
                canonical.story(), canonical.privateMemory(), List.of(), request.visibility(),
                VerificationStatus.UNVERIFIED, now));

        VisitExperienceDetail detail = details.save(new VisitExperienceDetail(
                visit, request.primaryExperienceCode(), canonical.rawLabel(),
                request.overallFeelingCode(), FeelingSource.EXPLICIT,
                request.companionCode(), request.timeOfDayCode(), canonical.title(),
                request.titleSource(), canonical.story(), canonical.tip(), TAXONOMY_VERSION,
                canonical.vibes(), canonical.practicalSignals(), now));

        List<VisitDimensionScore> dimensionEntities = canonical.dimensions().stream()
                .map(value -> new VisitDimensionScore(
                        visit, value.key(), value.semanticStateCode(), value.templateVersion()))
                .toList();
        dimensions.saveAll(dimensionEntities);
        List<VisitMediaResponse> attached = media.attach(visit, userId, canonical.mediaIds());
        if (origin != null) origin.convertTo(visit, now);
        return reader.map(visit, detail, dimensionEntities, attached);
    }

    Canonical canonicalize(CreateExperienceV2Request request, Place place) {
        String rawLabel = normalized(request.rawExperienceLabel());
        if (request.primaryExperienceCode() == PrimaryExperienceCode.OTHER) {
            if (rawLabel == null) throw ApiException.validation("rawExperienceLabel is required for OTHER");
        } else if (rawLabel != null) {
            throw ApiException.validation("rawExperienceLabel is only allowed for OTHER");
        }

        List<CreateExperienceV2Request.Dimension> dimensionValues = request.dimensions() == null
                ? List.of() : request.dimensions();
        Set<String> dimensionKeys = new HashSet<>();
        ExperienceFamily family = ExperienceTaxonomy.familyOf(request.primaryExperienceCode());
        Set<String> allowedDimensions = dimensionKeys(family);
        for (CreateExperienceV2Request.Dimension value : dimensionValues) {
            String key = value.key().trim().toUpperCase(Locale.ROOT);
            if (!dimensionKeys.add(key)) throw ApiException.validation("Duplicate dimension key: " + key);
            if (value.templateVersion() != DIMENSION_TEMPLATE_VERSION) {
                throw ApiException.validation("Unsupported dimension templateVersion");
            }
            if (!allowedDimensions.contains(key)) {
                throw ApiException.validation("Dimension '" + key + "' is not valid for " + family);
            }
        }

        List<com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode> vibes =
                sortedDistinct(request.vibeCodes(), "vibeCodes");
        if (vibes.size() > 2) throw ApiException.validation("vibeCodes must contain at most 2 items");
        List<com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode> practical =
                sortedDistinct(request.practicalSignalCodes(), "practicalSignalCodes");

        String story = value(request.story());
        String tip = normalized(request.tip());
        String privateMemory = value(request.privateMemory());
        List<UUID> mediaIds = request.mediaIds() == null ? List.of() : List.copyOf(request.mediaIds());
        if (mediaIds.size() > V2_MEDIA_LIMIT) {
            throw ApiException.validation("mediaIds must contain at most 6 items for V2 Experiences");
        }
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw ApiException.validation("mediaIds must not contain duplicates");
        }
        if (story.isBlank() && tip == null && mediaIds.isEmpty()) {
            throw ApiException.validation("An Experience requires media, Story, or Tip");
        }

        String title;
        if (request.titleSource() == TitleSource.CUSTOM) {
            title = normalized(request.title());
            if (title == null) throw ApiException.validation("title is required when titleSource is CUSTOM");
        } else {
            if (normalized(request.title()) != null) {
                throw ApiException.validation("title must be omitted when titleSource is GENERATED");
            }
            title = generatedTitle(place, request.primaryExperienceCode(), rawLabel);
        }

        List<CreateExperienceV2Request.Dimension> canonicalDimensions = dimensionValues.stream()
                .map(value -> new CreateExperienceV2Request.Dimension(
                        value.key().trim().toUpperCase(Locale.ROOT),
                        value.semanticStateCode(), value.templateVersion()))
                .sorted(Comparator.comparing(CreateExperienceV2Request.Dimension::key))
                .toList();
        String fingerprint = fingerprint(request, rawLabel, title, story, tip, privateMemory,
                vibes, practical, canonicalDimensions, mediaIds);
        return new Canonical(rawLabel, title, story, tip, privateMemory, vibes, practical,
                canonicalDimensions, mediaIds, fingerprint);
    }

    private String fingerprint(
            CreateExperienceV2Request request,
            String rawLabel,
            String title,
            String story,
            String tip,
            String privateMemory,
            List<? extends Enum<?>> vibes,
            List<? extends Enum<?>> practical,
            List<CreateExperienceV2Request.Dimension> dimensions,
            List<UUID> mediaIds) {
        List<String> fields = new ArrayList<>();
        fields.add("v2");
        fields.add(request.placeId().toString());
        fields.add(request.visitDate().toString());
        fields.add(request.primaryExperienceCode().name());
        fields.add(value(rawLabel));
        fields.add(request.overallFeelingCode().name());
        fields.add(Double.toString(request.overallFeelingCode().compatibilityScore()));
        fields.add(request.companionCode() == null ? "" : request.companionCode().name());
        fields.add(request.timeOfDayCode() == null ? "" : request.timeOfDayCode().name());
        fields.add(vibes.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""));
        fields.add(practical.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""));
        fields.add(dimensions.stream().map(value -> value.key() + "="
                        + value.semanticStateCode().name() + "="
                        + value.semanticStateCode().compatibilityScore() + "="
                        + value.templateVersion())
                .reduce((a, b) -> a + "," + b).orElse(""));
        fields.add(story);
        fields.add(value(tip));
        fields.add(privateMemory);
        fields.add(title);
        fields.add(request.titleSource().name());
        fields.add(request.visibility().name());
        fields.add(mediaIds.stream().map(UUID::toString)
                .reduce((a, b) -> a + "\u001f" + b).orElse(""));
        fields.add(request.originAcknowledgementId() == null ? "" : request.originAcknowledgementId().toString());
        return sha256(String.join("\u001e", fields));
    }

    private static String generatedTitle(Place place, PrimaryExperienceCode primary, String rawLabel) {
        String experience = primary == PrimaryExperienceCode.OTHER
                ? rawLabel
                : primary.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return place.getName().trim() + " · " + experience;
    }

    private static <T extends Enum<T>> List<T> sortedDistinct(List<T> input, String field) {
        if (input == null || input.isEmpty()) return List.of();
        Set<T> unique = new HashSet<>(input);
        if (unique.size() != input.size()) throw ApiException.validation(field + " must not contain duplicates");
        return unique.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }

    private static Set<String> dimensionKeys(ExperienceFamily family) {
        if (family == null) return Set.of();
        Map<ExperienceFamily, Set<String>> values = new EnumMap<>(ExperienceFamily.class);
        values.put(ExperienceFamily.FOOD_AND_DRINK, Set.of("FOOD", "SERVICE", "ATMOSPHERE", "VALUE"));
        values.put(ExperienceFamily.SCENERY_AND_MOMENT, Set.of("SCENERY", "ATMOSPHERE", "TRANQUILITY", "ACCESS"));
        values.put(ExperienceFamily.SEA_AND_WATER, Set.of("SEA", "CLEANLINESS", "COMFORT", "ACCESS"));
        values.put(ExperienceFamily.NATURE_AND_OUTDOOR, Set.of("SCENERY", "ROUTE", "TRANQUILITY", "ACCESS"));
        values.put(ExperienceFamily.TRAVEL_AND_DISCOVERY, Set.of("ATMOSPHERE", "WALKABILITY", "LOCALITY", "DISCOVERY_VALUE"));
        values.put(ExperienceFamily.CULTURE_AND_LOCAL_LIFE, Set.of("CONTENT_INTEREST", "ATMOSPHERE", "ACCESS", "VALUE"));
        values.put(ExperienceFamily.ENTERTAINMENT_AND_NIGHTLIFE, Set.of("ATMOSPHERE", "MUSIC_ENTERTAINMENT", "SERVICE", "VALUE"));
        values.put(ExperienceFamily.ACTIVITY_AND_ADVENTURE, Set.of("FUN", "ORGANIZATION", "COMFORT_DIFFICULTY", "VALUE"));
        values.put(ExperienceFamily.REST_AND_WELLNESS, Set.of("ATMOSPHERE", "COMFORT", "CLEANLINESS", "VALUE"));
        values.put(ExperienceFamily.ACCOMMODATION, Set.of("CLEANLINESS", "COMFORT", "LOCATION", "SERVICE"));
        return values.get(family);
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String value(String value) {
        String normalized = normalized(value);
        return normalized == null ? "" : normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record Canonical(
            String rawLabel,
            String title,
            String story,
            String tip,
            String privateMemory,
            List<com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode> vibes,
            List<com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode> practicalSignals,
            List<CreateExperienceV2Request.Dimension> dimensions,
            List<UUID> mediaIds,
            String fingerprint) {}
}

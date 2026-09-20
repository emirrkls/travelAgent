package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.ExperienceV2Response;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScore;
import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.FeelingSource;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.repository.PlannedExperienceRepository;
import com.emirrkls.phokarta.backend.repository.ExperienceAcknowledgementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ExperienceReadService {
    static final String UNKNOWN_LEGACY = "UNKNOWN_LEGACY";

    private final VisitRepository visits;
    private final VisitExperienceDetailRepository details;
    private final VisitDimensionScoreRepository dimensions;
    private final MediaService media;
    private final ViewerAccessPolicy access;
    private final FollowRequestService relationships;
    private final PlannedExperienceRepository plans;
    private final ExperienceAcknowledgementRepository acknowledgements;
    private final ExperienceConversationService conversations;

    public ExperienceReadService(
            VisitRepository visits,
            VisitExperienceDetailRepository details,
            VisitDimensionScoreRepository dimensions,
            MediaService media,
            ViewerAccessPolicy access,
            FollowRequestService relationships,
            PlannedExperienceRepository plans,
            ExperienceAcknowledgementRepository acknowledgements,
            ExperienceConversationService conversations) {
        this.visits = visits;
        this.details = details;
        this.dimensions = dimensions;
        this.media = media;
        this.access = access;
        this.relationships = relationships;
        this.plans = plans;
        this.acknowledgements = acknowledgements;
        this.conversations = conversations;
    }

    @Transactional(readOnly = true)
    public ExperienceV2Response getVisible(UUID experienceId, UUID viewerId) {
        Visit visit = visits.findDetailedById(experienceId)
                .orElseThrow(() -> ApiException.notFound("Experience", experienceId));
        if (!access.canViewVisit(visit, viewerId)) {
            throw ApiException.notFound("Experience", experienceId);
        }

        VisitExperienceDetail detail = details.findById(experienceId).orElse(null);
        List<VisitDimensionScore> scores = dimensions.findByIdVisitId(experienceId).stream()
                .sorted(Comparator.comparing(value -> value.getId().getDimensionKey()))
                .toList();
        List<VisitMediaResponse> managed = media.descriptorsForVisits(List.of(experienceId))
                .getOrDefault(experienceId, List.of());
        return map(visit, detail, scores, managed, viewerId);
    }

    ExperienceV2Response map(
            Visit visit,
            VisitExperienceDetail detail,
            List<VisitDimensionScore> scores,
            List<VisitMediaResponse> managedMedia) {
        return map(visit, detail, scores, managedMedia, visit.getUser().getId());
    }

    ExperienceV2Response map(
            Visit visit,
            VisitExperienceDetail detail,
            List<VisitDimensionScore> scores,
            List<VisitMediaResponse> managedMedia,
            UUID viewerId) {
        boolean nativeV2 = detail != null;
        var feelingCode = nativeV2
                ? detail.getOverallFeelingCode()
                : ExperienceTaxonomy.OverallFeelingCode.fromLegacyRating(visit.getOverallRating());
        var feelingSource = nativeV2 ? detail.getFeelingSource() : FeelingSource.DERIVED_LEGACY;

        var primary = nativeV2
                ? new ExperienceV2Response.PrimaryExperience(
                        detail.getPrimaryExperienceCode().name(),
                        true,
                        ExperienceTaxonomy.familyOf(detail.getPrimaryExperienceCode()),
                        detail.getRawExperienceLabel())
                : new ExperienceV2Response.PrimaryExperience(UNKNOWN_LEGACY, false, null, null);

        List<ExperienceV2Response.Dimension> dimensionResponses = scores.stream()
                .map(value -> new ExperienceV2Response.Dimension(
                        value.getId().getDimensionKey(),
                        value.getScore(),
                        value.getSemanticStateCode(),
                        value.getTemplateVersion()))
                .toList();

        return new ExperienceV2Response(
                visit.getId(),
                nativeV2
                        ? ExperienceV2Response.Classification.NATIVE_V2
                        : ExperienceV2Response.Classification.LEGACY_COMPATIBILITY,
                new ExperienceV2Response.Author(
                        visit.getUser().getId(), visit.getUser().getUsername(),
                        visit.getUser().getDisplayName(), visit.getUser().getAvatarUrl(),
                        relationships.relationship(viewerId, visit.getUser().getId())),
                new ExperienceV2Response.Place(
                        visit.getPlace().getId(), visit.getPlace().getName(),
                        visit.getPlace().getCategory(), visit.getPlace().getCity(),
                        visit.getPlace().getRegion(), visit.getPlace().getCountry(),
                        visit.getPlace().getCoverImage()),
                visit.getVisitedAt(),
                nativeV2 ? detail.getTitle() : visit.getPlace().getName(),
                nativeV2 ? detail.getTitleSource() : null,
                nativeV2,
                nativeV2 ? detail.getStory() : visit.getPublicReview(),
                nativeV2 ? detail.getTip() : null,
                new ExperienceV2Response.Feeling(feelingCode, feelingSource, visit.getOverallRating()),
                primary,
                nativeV2 ? detail.getCompanionCode() : null,
                nativeV2 ? detail.getTimeOfDayCode() : null,
                nativeV2 ? detail.getVibes() : List.of(),
                nativeV2 ? detail.getPracticalSignals() : List.of(),
                dimensionResponses,
                mapMedia(visit, managedMedia),
                visit.getVisibility(),
                nativeV2 ? detail.getTaxonomyVersion() : null,
                viewerId != null && plans.existsByIdUserIdAndIdExperienceId(viewerId, visit.getId()),
                viewerId != null && acknowledgements.existsByUserIdAndSourceExperienceId(viewerId, visit.getId()),
                acknowledgements.countBySourceExperienceId(visit.getId()),
                conversations.visibleRootCount(visit.getId(), viewerId));
    }

    private List<ExperienceV2Response.Media> mapMedia(
            Visit visit, List<VisitMediaResponse> managedMedia) {
        List<ExperienceV2Response.Media> result = new ArrayList<>();
        for (String legacyUrl : visit.getPhotos()) {
            result.add(new ExperienceV2Response.Media(
                    ExperienceV2Response.MediaKind.LEGACY_URL,
                    result.size(), null, legacyUrl, null));
        }
        managedMedia.stream()
                .sorted(Comparator.comparingInt(VisitMediaResponse::sortOrder))
                .forEach(value -> result.add(new ExperienceV2Response.Media(
                        ExperienceV2Response.MediaKind.MANAGED,
                        result.size(), value.id(), value.accessUrl().toString(),
                        value.accessExpiresAt())));
        return List.copyOf(result);
    }
}

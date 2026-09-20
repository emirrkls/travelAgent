package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.ExperienceAcknowledgementV2Response;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.ExperienceAcknowledgement;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.repository.ExperienceAcknowledgementRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitExperienceDetailRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

@Service
public class ExperienceAcknowledgementService {
    private final ExperienceAcknowledgementRepository acknowledgements;
    private final UserRepository users;
    private final VisitRepository visits;
    private final VisitExperienceDetailRepository details;
    private final ViewerAccessPolicy access;
    private final ExperienceReadService reader;
    private final UgcPolicyService ugcPolicy;
    private final PlaceRepository places;

    public ExperienceAcknowledgementService(ExperienceAcknowledgementRepository acknowledgements,
            UserRepository users, VisitRepository visits, VisitExperienceDetailRepository details,
            ViewerAccessPolicy access, ExperienceReadService reader, UgcPolicyService ugcPolicy,
            PlaceRepository places) {
        this.acknowledgements = acknowledgements; this.users = users; this.visits = visits;
        this.details = details; this.access = access; this.reader = reader; this.ugcPolicy = ugcPolicy;
        this.places = places;
    }

    @Transactional
    public ExperienceAcknowledgementV2Response acknowledge(UUID userId, UUID experienceId) {
        return acknowledge(userId, experienceId, null);
    }

    @Transactional
    public ExperienceAcknowledgementV2Response acknowledge(
            UUID userId, UUID experienceId, UUID clientAcknowledgementId) {
        return acknowledge(userId, experienceId, clientAcknowledgementId, null, null, null);
    }

    @Transactional
    public ExperienceAcknowledgementV2Response acknowledge(
            UUID userId, UUID experienceId, UUID clientAcknowledgementId,
            UUID anchorPlaceId, PrimaryExperienceCode anchorPrimaryExperienceCode,
            String anchorRawExperienceLabel) {
        users.lockAccount(userId);
        ugcPolicy.requireAccepted(userId);
        ExperienceAcknowledgement existing = acknowledgements
                .findByUserIdAndSourceExperienceId(userId, experienceId).orElse(null);
        if (existing != null) return response(existing, userId);
        if (clientAcknowledgementId != null) {
            ExperienceAcknowledgement byClientId = acknowledgements.findByIdForUpdate(clientAcknowledgementId)
                    .orElse(null);
            if (byClientId != null) {
                if (!byClientId.getUser().getId().equals(userId)) {
                    throw ApiException.conflict("Acknowledgement id is already in use");
                }
                return response(byClientId, userId);
            }
        }
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        Visit source = visits.findDetailedById(experienceId).orElse(null);
        if (source == null) {
            if (clientAcknowledgementId == null || anchorPlaceId == null || anchorPrimaryExperienceCode == null) {
                throw ApiException.notFound("Experience", experienceId);
            }
            validateAnchor(anchorPrimaryExperienceCode, anchorRawExperienceLabel);
            var place = places.findById(anchorPlaceId)
                    .orElseThrow(() -> ApiException.notFound("Place", anchorPlaceId));
            var value = acknowledgements.save(new ExperienceAcknowledgement(
                    clientAcknowledgementId == null ? UUID.randomUUID() : clientAcknowledgementId,
                    user, null, place, anchorPrimaryExperienceCode,
                    normalized(anchorRawExperienceLabel), OffsetDateTime.now(ZoneOffset.UTC)));
            return response(value, userId);
        }
        if (source.getUser().getId().equals(userId)) {
            throw ApiException.validation("You cannot acknowledge your own Experience");
        }
        if (!access.canViewVisit(source, userId)) throw ApiException.notFound("Experience", experienceId);
        var detail = details.findById(experienceId)
                .orElseThrow(() -> ApiException.validation("Legacy Experiences cannot be acknowledged yet"));
        if (anchorPlaceId != null && !source.getPlace().getId().equals(anchorPlaceId)
                || anchorPrimaryExperienceCode != null
                && detail.getPrimaryExperienceCode() != anchorPrimaryExperienceCode
                || anchorRawExperienceLabel != null
                && !java.util.Objects.equals(detail.getRawExperienceLabel(), normalized(anchorRawExperienceLabel))) {
            throw ApiException.validation("Acknowledgement anchor does not match the source Experience");
        }
        var value = acknowledgements.save(new ExperienceAcknowledgement(
                clientAcknowledgementId == null ? UUID.randomUUID() : clientAcknowledgementId,
                user, source, source.getPlace(), detail.getPrimaryExperienceCode(),
                detail.getRawExperienceLabel(), OffsetDateTime.now(ZoneOffset.UTC)));
        return response(value, userId);
    }

    private static void validateAnchor(PrimaryExperienceCode primary, String rawLabel) {
        String normalized = normalized(rawLabel);
        if (primary == PrimaryExperienceCode.OTHER && normalized == null) {
            throw ApiException.validation("anchorRawExperienceLabel is required for OTHER");
        }
        if (primary != PrimaryExperienceCode.OTHER && normalized != null) {
            throw ApiException.validation("anchorRawExperienceLabel is only allowed for OTHER");
        }
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional(readOnly = true)
    public PageResponse<ExperienceAcknowledgementV2Response> listUnconverted(
            UUID ownerId, UUID viewerId, int page, int size) {
        User owner = users.findById(ownerId).orElseThrow(() -> ApiException.notFound("User", ownerId));
        if (!access.canViewFullProfile(owner, viewerId)) throw ApiException.notFound("User", ownerId);
        var source = acknowledgements.findByUserIdAndConvertedAtIsNullOrderByAcknowledgedAtDescIdDesc(
                ownerId, PageRequest.of(page, size));
        var content = new ArrayList<ExperienceAcknowledgementV2Response>();
        for (var value : source.getContent()) {
            if (viewerId != null && viewerId.equals(ownerId)) {
                content.add(response(value, viewerId));
            } else if (value.getSourceExperience() != null
                    && access.canViewVisit(value.getSourceExperience(), viewerId)) {
                content.add(response(value, viewerId));
            }
        }
        boolean ownerView = viewerId != null && viewerId.equals(ownerId);
        if (ownerView) {
            return new PageResponse<>(content, source.getNumber(), source.getSize(),
                    source.getTotalElements(), source.getTotalPages(), source.hasNext());
        }
        // Never disclose the number or existence of acknowledgement rows whose source the viewer cannot access.
        return new PageResponse<>(content, page, size, content.size(), content.isEmpty() ? 0 : 1, false);
    }

    ExperienceAcknowledgementV2Response response(ExperienceAcknowledgement value, UUID viewerId) {
        Visit source = value.getSourceExperience();
        boolean available = source != null && access.canViewVisit(source, viewerId);
        var place = value.getPlace();
        return new ExperienceAcknowledgementV2Response(
                value.getId(), value.getUser().getId(), source == null ? null : source.getId(), available,
                available ? reader.getVisible(source.getId(), viewerId) : null,
                new ExperienceAcknowledgementV2Response.AnchorPlace(
                        place.getId(), place.getName(), place.getCity(), place.getRegion(), place.getCountry()),
                value.getPrimaryExperienceCode(), value.getRawExperienceLabel(), value.getAcknowledgedAt(),
                value.isConverted() ? ExperienceAcknowledgementV2Response.Status.CONVERTED
                        : ExperienceAcknowledgementV2Response.Status.UNCONVERTED,
                value.getConvertedExperience() == null ? null : value.getConvertedExperience().getId());
    }
}

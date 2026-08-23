package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.FriendPlaceSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicActivityResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.api.mapper.VisitMapper;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScore;
import com.emirrkls.phokarta.backend.domain.model.FeedScope;
import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.domain.service.RatingDimensionRegistry;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VisitService {
    static final int FRIEND_PREVIEW_LIMIT = 5;

    private final VisitRepository visits;
    private final VisitDimensionScoreRepository scores;
    private final UserRepository users;
    private final PlaceRepository places;
    private final RatingDimensionRegistry registry;
    private final VisitMapper mapper;

    public VisitService(VisitRepository visits, VisitDimensionScoreRepository scores,
                        UserRepository users, PlaceRepository places,
                        RatingDimensionRegistry registry, VisitMapper mapper) {
        this.visits = visits;
        this.scores = scores;
        this.users = users;
        this.places = places;
        this.registry = registry;
        this.mapper = mapper;
    }

    @Transactional
    public VisitOwnerResponse create(UUID userId, CreateVisitRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        Place place = places.findById(request.placeId())
                .orElseThrow(() -> ApiException.notFound("Place", request.placeId()));
        Map<String, Double> dimensions = new LinkedHashMap<>();
        List<CreateVisitRequest.DimensionScore> requestedDimensions =
                request.dimensions() == null ? List.of() : request.dimensions();
        requestedDimensions.forEach(item -> {
            if (dimensions.putIfAbsent(item.key(), item.score()) != null) {
                throw ApiException.validation("Duplicate dimension key: " + item.key());
            }
        });
        try {
            registry.validateScore(request.overallRating());
            registry.validateScores(place.getCategory(), dimensions);
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation(ex.getMessage());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Visit visit = visits.save(new Visit(UUID.randomUUID(), user, place, request.visitedAt(),
                request.overallRating(), value(request.publicReview()), value(request.privateMemory()),
                request.photos() == null ? List.of() : request.photos(), request.visibility(),
                VerificationStatus.UNVERIFIED, now));
        List<VisitDimensionScore> entities = dimensions.entrySet().stream()
                .map(entry -> new VisitDimensionScore(visit, entry.getKey(), entry.getValue()))
                .toList();
        scores.saveAll(entities);
        VisitRepository.ScoreAggregate rating = visits.aggregate(place.getId());
        return mapper.toOwner(visit, toDimensionResponses(entities),
                rating == null ? null : rating.getAverage(), rating == null ? 0 : rating.getCount());
    }

    @Transactional(readOnly = true)
    public PageResponse<VisitOwnerResponse> ownerVisits(UUID userId, int page, int size) {
        requireUser(userId);
        Page<Visit> result = visits.findByUserIdOrderByVisitedAtDescCreatedAtDescIdDesc(
                userId, PageRequest.of(page, size));
        List<UUID> ids = result.getContent().stream().map(Visit::getId).toList();
        Map<UUID, List<VisitOwnerResponse.DimensionScoreResponse>> byVisit = new HashMap<>();
        Map<UUID, PlaceRepository.RatingAggregate> byPlace = new HashMap<>();
        if (!ids.isEmpty()) {
            scores.findByIdVisitIdIn(ids).forEach(score -> byVisit
                    .computeIfAbsent(score.getId().getVisitId(), ignored -> new ArrayList<>())
                    .add(new VisitOwnerResponse.DimensionScoreResponse(
                            score.getId().getDimensionKey(), score.getScore())));
            List<UUID> placeIds = result.getContent().stream()
                    .map(v -> v.getPlace().getId()).distinct().toList();
            places.aggregateByIds(placeIds).forEach(row -> byPlace.put(row.getId(), row));
        }
        List<VisitOwnerResponse> content = result.getContent().stream()
                .map(v -> {
                    PlaceRepository.RatingAggregate rating = byPlace.get(v.getPlace().getId());
                    return mapper.toOwner(v, byVisit.getOrDefault(v.getId(), List.of()),
                            rating == null ? null : rating.getAverageScore(),
                            rating == null ? 0 : rating.getRatingCount());
                }).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicVisitResponse> publicReviews(UUID placeId, int page, int size) {
        return publicReviews(placeId, FeedScope.COMMUNITY, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicVisitResponse> publicReviews(
            UUID placeId, FeedScope scope, int page, int size) {
        if (!places.existsById(placeId)) throw ApiException.notFound("Place", placeId);
        FeedScope resolved = scope == null ? FeedScope.COMMUNITY : scope;
        if (resolved == FeedScope.COMMUNITY) {
            return PageResponse.from(visits
                    .findByPlaceIdAndVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
                            placeId, Visibility.PUBLIC, PageRequest.of(page, size)),
                    mapper::toPublic);
        }
        UUID viewerId = SecurityUtils.requireCurrentUserId();
        return PageResponse.from(
                visits.findFriendsReviews(placeId, viewerId, PageRequest.of(page, size)),
                mapper::toPublic);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicActivityResponse> publicActivity(int page, int size) {
        return publicActivity(FeedScope.COMMUNITY, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicActivityResponse> publicActivity(FeedScope scope, int page, int size) {
        FeedScope resolved = scope == null ? FeedScope.COMMUNITY : scope;
        if (resolved == FeedScope.COMMUNITY) {
            return PageResponse.from(
                    visits.findByVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
                            Visibility.PUBLIC, PageRequest.of(page, size)),
                    mapper::toActivity);
        }
        UUID viewerId = SecurityUtils.requireCurrentUserId();
        return PageResponse.from(
                visits.findFriendsActivity(viewerId, PageRequest.of(page, size)),
                mapper::toActivity);
    }

    /**
     * Viewer-relative friends summary for a place. Dedicated endpoint so the globally
     * cacheable public Place detail is not polluted with viewer-relative values.
     */
    @Transactional(readOnly = true)
    public FriendPlaceSummaryResponse friendsSummary(UUID placeId, UUID viewerId) {
        if (!places.existsById(placeId)) throw ApiException.notFound("Place", placeId);
        requireUser(viewerId);
        VisitRepository.FriendScoreAggregate aggregate =
                visits.aggregateFriendsScore(placeId, viewerId);
        long friendsVisitedCount = aggregate == null ? 0L : aggregate.getFriendsVisitedCount();
        Double averageScore = friendsVisitedCount == 0
                ? null
                : (aggregate == null ? null : aggregate.getAverageScore());
        List<FriendPlaceSummaryResponse.FriendPreview> friends = friendsVisitedCount == 0
                ? List.of()
                : visits.findFriendPreviews(placeId, viewerId, FRIEND_PREVIEW_LIMIT).stream()
                .map(row -> new FriendPlaceSummaryResponse.FriendPreview(
                        row.getUserId(),
                        row.getDisplayName(),
                        row.getAvatarUrl(),
                        row.getLatestScore(),
                        row.getLatestVisitedAt()))
                .toList();
        return new FriendPlaceSummaryResponse(averageScore, friendsVisitedCount, friends);
    }

    private List<VisitOwnerResponse.DimensionScoreResponse> toDimensionResponses(
            List<VisitDimensionScore> values) {
        return values.stream().map(score -> new VisitOwnerResponse.DimensionScoreResponse(
                score.getId().getDimensionKey(), score.getScore())).toList();
    }

    private void requireUser(UUID id) {
        if (!users.existsById(id)) throw ApiException.notFound("User", id);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}

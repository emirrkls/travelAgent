package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.FriendPlaceMetricsResponse;
import com.emirrkls.phokarta.backend.api.dto.FriendPlaceSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicActivityResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
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
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

@Service
public class VisitService {
    static final int FRIEND_PREVIEW_LIMIT = 5;
    static final int FRIEND_METRICS_MAX_PLACE_IDS = 200;

    private final VisitRepository visits;
    private final VisitDimensionScoreRepository scores;
    private final UserRepository users;
    private final PlaceRepository places;
    private final RatingDimensionRegistry registry;
    private final VisitMapper mapper;
    private final ApplicationMetrics metrics;
    private final MediaService media;

    @Autowired
    public VisitService(VisitRepository visits, VisitDimensionScoreRepository scores,
                        UserRepository users, PlaceRepository places,
                        RatingDimensionRegistry registry, VisitMapper mapper,
                        ApplicationMetrics metrics, MediaService media) {
        this.visits = visits;
        this.scores = scores;
        this.users = users;
        this.places = places;
        this.registry = registry;
        this.mapper = mapper;
        this.metrics = metrics;
        this.media = media;
    }

    /** Source-compatible constructor for focused unit tests predating managed media. */
    public VisitService(VisitRepository visits, VisitDimensionScoreRepository scores,
                        UserRepository users, PlaceRepository places,
                        RatingDimensionRegistry registry, VisitMapper mapper,
                        ApplicationMetrics metrics) {
        this(visits, scores, users, places, registry, mapper, metrics, null);
    }

    @Transactional
    public VisitOwnerResponse create(UUID userId, CreateVisitRequest request) {
        users.lockAccount(userId);
        String fingerprint = request.clientMutationId() == null ? null : fingerprint(request);
        if (request.clientMutationId() != null) {
            visits.lockClientMutation(userId, request.clientMutationId());
            Visit existing = visits.findByUserIdAndClientMutationId(userId, request.clientMutationId())
                    .orElse(null);
            if (existing != null) {
                if (!fingerprint.equals(existing.getClientPayloadFingerprint())) {
                    metrics.visitCreateConflict();
                    throw ApiException.conflict("clientMutationId was already used with a different Visit payload");
                }
                List<VisitDimensionScore> existingScores = scores.findByIdVisitIdIn(List.of(existing.getId()));
                VisitRepository.ScoreAggregate rating = visits.aggregate(existing.getPlace().getId());
                VisitOwnerResponse response = mapper.toOwner(existing, toDimensionResponses(existingScores),
                        rating == null ? null : rating.getAverage(), rating == null ? 0 : rating.getCount(),
                        descriptors(existing.getId()));
                metrics.visitCreateIdempotencyHit();
                return response;
            }
        }
        if (request.photos() != null && !request.photos().isEmpty()) {
            throw ApiException.validation("photos is legacy read-only; use mediaIds");
        }
        List<UUID> mediaIds = media == null
                ? legacyMediaIds(request.mediaIds()) : media.normalizeAndValidateIds(request.mediaIds());
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
        Visit visit = visits.save(new Visit(UUID.randomUUID(), user, place,
                request.clientMutationId(), fingerprint, request.visitedAt(),
                request.overallRating(), value(request.publicReview()), value(request.privateMemory()),
                List.of(), request.visibility(),
                VerificationStatus.UNVERIFIED, now));
        List<VisitMediaResponse> attachedMedia = media == null
                ? List.of() : media.attach(visit, userId, mediaIds);
        List<VisitDimensionScore> entities = dimensions.entrySet().stream()
                .map(entry -> new VisitDimensionScore(visit, entry.getKey(), entry.getValue()))
                .toList();
        scores.saveAll(entities);
        VisitRepository.ScoreAggregate rating = visits.aggregate(place.getId());
        VisitOwnerResponse response = mapper.toOwner(visit, toDimensionResponses(entities),
                rating == null ? null : rating.getAverage(), rating == null ? 0 : rating.getCount(),
                attachedMedia);
        metrics.visitCreateSuccess();
        return response;
    }

    private String fingerprint(CreateVisitRequest request) {
        String dimensions = (request.dimensions() == null ? List.<CreateVisitRequest.DimensionScore>of() : request.dimensions())
                .stream().sorted(Comparator.comparing(CreateVisitRequest.DimensionScore::key))
                .map(item -> item.key() + "=" + Double.toString(item.score()))
                .reduce((left, right) -> left + "," + right).orElse("");
        String photos = request.photos() == null ? "" : String.join("\u001f", request.photos());
        String mediaIds = request.mediaIds() == null ? "" : request.mediaIds().stream()
                .map(id -> id == null ? "<null>" : id.toString())
                .reduce((left, right) -> left + "\u001f" + right).orElse("");
        String canonical = request.placeId() + "\u001e" + request.visitedAt() + "\u001e"
                + Double.toString(request.overallRating()) + "\u001e" + dimensions + "\u001e"
                + value(request.publicReview()) + "\u001e" + value(request.privateMemory()) + "\u001e"
                + photos + "\u001e" + request.visibility();
        if (!mediaIds.isEmpty()) {
            canonical += "\u001e" + mediaIds;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
        Map<UUID, List<VisitMediaResponse>> mediaByVisit = mediaByVisit(ids);
        List<VisitOwnerResponse> content = result.getContent().stream()
                .map(v -> {
                    PlaceRepository.RatingAggregate rating = byPlace.get(v.getPlace().getId());
                    return mapper.toOwner(v, byVisit.getOrDefault(v.getId(), List.of()),
                            rating == null ? null : rating.getAverageScore(),
                            rating == null ? 0 : rating.getRatingCount(),
                            mediaByVisit.getOrDefault(v.getId(), List.of()));
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
        Page<Visit> result;
        if (resolved == FeedScope.COMMUNITY) {
            result = visits.findByPlaceIdAndVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
                    placeId, Visibility.PUBLIC, PageRequest.of(page, size));
        } else {
            UUID viewerId = SecurityUtils.requireCurrentUserId();
            result = visits.findFriendsReviews(placeId, viewerId, PageRequest.of(page, size));
        }
        List<UUID> ids = result.getContent().stream().map(Visit::getId).toList();
        Map<UUID, List<VisitMediaResponse>> mediaByVisit = mediaByVisit(ids);
        return PageResponse.from(result,
                visit -> mapper.toPublic(visit, mediaByVisit.getOrDefault(visit.getId(), List.of())));
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

    /**
     * Batch viewer-relative friend metrics for map enrichment.
     * Same formula as {@link #friendsSummary}: AVG(per-friend AVG) of PUBLIC+FRIENDS
     * Visits by mutual friends. Places with no qualifying friends return count 0
     * and omitted score. Input IDs are de-duplicated; order of first occurrence is kept.
     */
    @Transactional(readOnly = true)
    public List<FriendPlaceMetricsResponse> friendMetrics(UUID viewerId, List<UUID> placeIds) {
        requireUser(viewerId);
        List<UUID> unique = uniquePlaceIds(placeIds);
        if (unique.size() > FRIEND_METRICS_MAX_PLACE_IDS) {
            throw ApiException.validation(
                    "placeIds must contain at most " + FRIEND_METRICS_MAX_PLACE_IDS + " unique ids");
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        Map<UUID, VisitRepository.FriendScoreByPlace> byPlace = new HashMap<>();
        visits.aggregateFriendsScoreByPlaceIds(unique, viewerId)
                .forEach(row -> byPlace.put(row.getPlaceId(), row));
        return unique.stream().map(placeId -> {
            VisitRepository.FriendScoreByPlace row = byPlace.get(placeId);
            long count = row == null ? 0L : row.getFriendsVisitedCount();
            Double average = count == 0 ? null : row.getAverageScore();
            return new FriendPlaceMetricsResponse(placeId, average, count);
        }).toList();
    }

    private List<UUID> uniquePlaceIds(List<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID id : placeIds) {
            if (id != null) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }

    private List<VisitOwnerResponse.DimensionScoreResponse> toDimensionResponses(
            List<VisitDimensionScore> values) {
        return values.stream().map(score -> new VisitOwnerResponse.DimensionScoreResponse(
                score.getId().getDimensionKey(), score.getScore())).toList();
    }

    private List<VisitMediaResponse> descriptors(UUID visitId) {
        return mediaByVisit(List.of(visitId)).getOrDefault(visitId, List.of());
    }

    private Map<UUID, List<VisitMediaResponse>> mediaByVisit(List<UUID> visitIds) {
        return media == null ? Map.of() : media.descriptorsForVisits(visitIds);
    }

    private List<UUID> legacyMediaIds(List<UUID> ids) {
        if (ids != null && !ids.isEmpty()) {
            throw new IllegalStateException("Managed media is unavailable in this test configuration");
        }
        return List.of();
    }

    private void requireUser(UUID id) {
        if (!users.existsById(id)) throw ApiException.notFound("User", id);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}

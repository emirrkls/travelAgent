package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.api.mapper.VisitMapper;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitDimensionScore;
import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.domain.service.RatingDimensionRegistry;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitDimensionScoreRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
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
    public VisitOwnerResponse create(CreateVisitRequest request) {
        User user = users.findById(request.userId())
                .orElseThrow(() -> ApiException.notFound("User", request.userId()));
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
        if (!places.existsById(placeId)) throw ApiException.notFound("Place", placeId);
        return PageResponse.from(visits
                .findByPlaceIdAndVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
                placeId, Visibility.PUBLIC, PageRequest.of(page, size)), mapper::toPublic);
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

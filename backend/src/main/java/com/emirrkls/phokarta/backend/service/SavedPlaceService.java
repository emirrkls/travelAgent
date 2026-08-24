package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.SavedPlaceResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.api.mapper.PlaceMapper;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.SavedPlace;
import com.emirrkls.phokarta.backend.domain.entity.SavedPlaceId;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.SavedPlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SavedPlaceService {
    private final SavedPlaceRepository saved;
    private final UserRepository users;
    private final PlaceRepository places;
    private final VisitRepository visits;
    private final PlaceMapper mapper;

    public SavedPlaceService(SavedPlaceRepository saved, UserRepository users,
                             PlaceRepository places, VisitRepository visits,
                             PlaceMapper mapper) {
        this.saved = saved;
        this.users = users;
        this.places = places;
        this.visits = visits;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<SavedPlaceResponse> list(UUID userId, int page, int size) {
        requireUser(userId);
        var result = saved.findByUserIdOrderBySavedAtDesc(userId, PageRequest.of(page, size));
        List<UUID> placeIds = result.getContent().stream()
                .map(entity -> entity.getPlace().getId()).distinct().toList();
        Map<UUID, PlaceRepository.RatingAggregate> ratings = new HashMap<>();
        Map<UUID, VisitRepository.FriendScoreByPlace> friendScores = new HashMap<>();
        if (!placeIds.isEmpty()) {
            places.aggregateByIds(placeIds).forEach(row -> ratings.put(row.getId(), row));
            visits.aggregateFriendsScoreByPlaceIds(placeIds, userId)
                    .forEach(row -> friendScores.put(row.getPlaceId(), row));
        }
        List<SavedPlaceResponse> content = result.getContent().stream()
                .map(entity -> response(
                        entity,
                        ratings.get(entity.getPlace().getId()),
                        friendScores.get(entity.getPlace().getId())))
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional
    public SavedPlaceResponse save(UUID userId, UUID placeId) {
        SavedPlaceId id = new SavedPlaceId(userId, placeId);
        if (!users.existsById(userId)) throw ApiException.notFound("User", userId);
        if (!places.existsById(placeId)) throw ApiException.notFound("Place", placeId);
        saved.insertIfAbsent(userId, placeId, OffsetDateTime.now(ZoneOffset.UTC));
        return response(saved.findDetailedById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Saved place insert completed without a readable row")), userId);
    }

    @Transactional
    public void remove(UUID userId, UUID placeId) {
        requireUser(userId);
        saved.deleteById(new SavedPlaceId(userId, placeId));
    }

    private SavedPlaceResponse response(SavedPlace entity, UUID viewerId) {
        Place place = entity.getPlace();
        PlaceRepository.RatingAggregate rating = null;
        VisitRepository.FriendScoreByPlace friendScore = null;
        if (place != null) {
            UUID placeId = place.getId();
            rating = places.aggregateByIds(List.of(placeId)).stream().findFirst().orElse(null);
            friendScore = visits.aggregateFriendsScoreByPlaceIds(List.of(placeId), viewerId)
                    .stream().findFirst().orElse(null);
        }
        return response(entity, rating, friendScore);
    }

    private SavedPlaceResponse response(
            SavedPlace entity,
            PlaceRepository.RatingAggregate rating,
            VisitRepository.FriendScoreByPlace friendScore) {
        long friendsVisited = friendScore == null ? 0L : friendScore.getFriendsVisitedCount();
        Double friendAverage = friendsVisited == 0
                ? null
                : (friendScore == null ? null : friendScore.getAverageScore());
        return new SavedPlaceResponse(
                mapper.toSummary(entity.getPlace(),
                        rating == null ? null : rating.getAverageScore(),
                        rating == null ? 0 : rating.getRatingCount()),
                entity.getSavedAt(),
                friendAverage,
                friendsVisited);
    }

    private void requireUser(UUID id) {
        if (!users.existsById(id)) throw ApiException.notFound("User", id);
    }
}

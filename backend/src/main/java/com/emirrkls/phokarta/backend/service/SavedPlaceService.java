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
    private final PlaceMapper mapper;

    public SavedPlaceService(SavedPlaceRepository saved, UserRepository users,
                             PlaceRepository places, PlaceMapper mapper) {
        this.saved = saved;
        this.users = users;
        this.places = places;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<SavedPlaceResponse> list(UUID userId, int page, int size) {
        requireUser(userId);
        var result = saved.findByUserIdOrderBySavedAtDesc(userId, PageRequest.of(page, size));
        List<UUID> placeIds = result.getContent().stream()
                .map(entity -> entity.getPlace().getId()).distinct().toList();
        Map<UUID, PlaceRepository.RatingAggregate> ratings = new HashMap<>();
        if (!placeIds.isEmpty()) {
            places.aggregateByIds(placeIds).forEach(row -> ratings.put(row.getId(), row));
        }
        List<SavedPlaceResponse> content = result.getContent().stream()
                .map(entity -> response(entity, ratings.get(entity.getPlace().getId()))).toList();
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
                        "Saved place insert completed without a readable row")));
    }

    @Transactional
    public void remove(UUID userId, UUID placeId) {
        requireUser(userId);
        saved.deleteById(new SavedPlaceId(userId, placeId));
    }

    private SavedPlaceResponse response(SavedPlace entity) {
        Place place = entity.getPlace();
        PlaceRepository.RatingAggregate rating = null;
        if (place != null) {
            rating = places.aggregateByIds(List.of(place.getId())).stream().findFirst().orElse(null);
        }
        return response(entity, rating);
    }

    private SavedPlaceResponse response(
            SavedPlace entity, PlaceRepository.RatingAggregate rating) {
        return new SavedPlaceResponse(mapper.toSummary(entity.getPlace(),
                rating == null ? null : rating.getAverageScore(),
                rating == null ? 0 : rating.getRatingCount()), entity.getSavedAt());
    }

    private void requireUser(UUID id) {
        if (!users.existsById(id)) throw ApiException.notFound("User", id);
    }
}

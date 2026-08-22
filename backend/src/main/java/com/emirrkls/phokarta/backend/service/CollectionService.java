package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CollectionDetailResponse;
import com.emirrkls.phokarta.backend.api.dto.CollectionSummaryResponse;
import com.emirrkls.phokarta.backend.api.dto.CreateCollectionRequest;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.api.mapper.PlaceMapper;
import com.emirrkls.phokarta.backend.domain.entity.Collection;
import com.emirrkls.phokarta.backend.domain.entity.CollectionPlace;
import com.emirrkls.phokarta.backend.domain.entity.CollectionPlaceId;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.repository.CollectionPlaceRepository;
import com.emirrkls.phokarta.backend.repository.CollectionRepository;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
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
public class CollectionService {
    private final CollectionRepository collections;
    private final CollectionPlaceRepository memberships;
    private final PlaceRepository places;
    private final UserRepository users;
    private final PlaceMapper mapper;

    public CollectionService(CollectionRepository collections,
                             CollectionPlaceRepository memberships, PlaceRepository places,
                             UserRepository users, PlaceMapper mapper) {
        this.collections = collections;
        this.memberships = memberships;
        this.places = places;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<CollectionSummaryResponse> list(UUID userId, int page, int size) {
        if (!users.existsById(userId)) throw ApiException.notFound("User", userId);
        Page<Collection> result = collections.findByUserIdOrderByUpdatedAtDesc(
                userId, PageRequest.of(page, size));
        List<UUID> ids = result.getContent().stream().map(Collection::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        if (!ids.isEmpty()) memberships.countByCollectionIds(ids)
                .forEach(row -> counts.put(row.getCollectionId(), row.getPlaceCount()));
        List<CollectionSummaryResponse> content = result.getContent().stream()
                .map(c -> summary(c, counts.getOrDefault(c.getId(), 0L))).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional
    public CollectionDetailResponse create(UUID userId, CreateCollectionRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Collection collection = collections.save(new Collection(UUID.randomUUID(), user,
                request.title().trim(), value(request.description()), request.visibility(),
                request.coverImage().trim(), now));
        return response(collection, List.of());
    }

    @Transactional(readOnly = true)
    public CollectionDetailResponse detail(UUID collectionId) {
        Collection collection = require(collectionId);
        List<CollectionPlace> collectionPlaces =
                memberships.findByCollectionIdOrderByDisplayOrder(collectionId);
        List<UUID> placeIds = collectionPlaces.stream().map(cp -> cp.getPlace().getId()).toList();
        Map<UUID, PlaceRepository.RatingAggregate> ratings = new HashMap<>();
        if (!placeIds.isEmpty()) {
            places.aggregateByIds(placeIds).forEach(row -> ratings.put(row.getId(), row));
        }
        var placeResponses = collectionPlaces.stream().map(cp -> {
            PlaceRepository.RatingAggregate rating = ratings.get(cp.getPlace().getId());
            return new CollectionDetailResponse.CollectionPlaceResponse(
                    mapper.toSummary(cp.getPlace(), rating == null ? null : rating.getAverageScore(),
                            rating == null ? 0 : rating.getRatingCount()),
                    cp.getDisplayOrder(), cp.getAddedAt());
        }).toList();
        return response(collection, placeResponses);
    }

    private CollectionDetailResponse response(
            Collection collection,
            List<CollectionDetailResponse.CollectionPlaceResponse> placeResponses) {
        return new CollectionDetailResponse(collection.getId(), collection.getUser().getId(),
                collection.getTitle(), collection.getDescription(), collection.getVisibility(),
                collection.getCoverImage(), collection.getCreatedAt(), collection.getUpdatedAt(),
                placeResponses);
    }

    @Transactional
    public CollectionDetailResponse add(UUID collectionId, UUID userId, UUID placeId) {
        Collection collection = requireOwnedForUpdate(collectionId, userId);
        CollectionPlaceId id = new CollectionPlaceId(collectionId, placeId);
        if (memberships.existsById(id)) {
            throw ApiException.conflict("Place is already in this collection");
        }
        Place place = places.findById(placeId)
                .orElseThrow(() -> ApiException.notFound("Place", placeId));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int order = memberships.maxDisplayOrder(collectionId) + 1;
        memberships.save(new CollectionPlace(collection, place, order, now));
        collection.touch(now);
        return detail(collectionId);
    }

    @Transactional
    public void remove(UUID collectionId, UUID userId, UUID placeId) {
        Collection collection = requireOwnedForUpdate(collectionId, userId);
        CollectionPlaceId id = new CollectionPlaceId(collectionId, placeId);
        if (!memberships.existsById(id)) throw ApiException.notFound("Collection place", placeId);
        memberships.deleteById(id);
        collection.touch(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private Collection requireOwned(UUID id, UUID userId) {
        Collection collection = require(id);
        requireOwner(collection, userId, id);
        return collection;
    }

    private Collection requireOwnedForUpdate(UUID id, UUID userId) {
        Collection collection = collections.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Collection", id));
        requireOwner(collection, userId, id);
        return collection;
    }

    private void requireOwner(Collection collection, UUID userId, UUID id) {
        if (!collection.getUser().getId().equals(userId)) {
            throw ApiException.notFound("Collection", id);
        }
    }

    private Collection require(UUID id) {
        return collections.findDetailedById(id)
                .orElseThrow(() -> ApiException.notFound("Collection", id));
    }

    private CollectionSummaryResponse summary(Collection c, long count) {
        return new CollectionSummaryResponse(c.getId(), c.getUser().getId(), c.getTitle(),
                c.getDescription(), c.getVisibility(), c.getCoverImage(), count, c.getUpdatedAt());
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}

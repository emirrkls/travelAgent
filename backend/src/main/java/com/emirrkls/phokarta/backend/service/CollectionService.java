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
    private final ViewerAccessPolicy access;
    private final PlaceMapper mapper;

    public CollectionService(CollectionRepository collections,
                             CollectionPlaceRepository memberships, PlaceRepository places,
                             UserRepository users, ViewerAccessPolicy access,
                             PlaceMapper mapper) {
        this.collections = collections;
        this.memberships = memberships;
        this.places = places;
        this.users = users;
        this.access = access;
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
    public CollectionDetailResponse detail(UUID collectionId, UUID viewerUserId) {
        Collection collection = require(collectionId);
        assertReadable(collection, viewerUserId, collectionId);
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
        return detail(collectionId, userId);
    }

    @Transactional
    public void remove(UUID collectionId, UUID userId, UUID placeId) {
        Collection collection = requireOwnedForUpdate(collectionId, userId);
        CollectionPlaceId id = new CollectionPlaceId(collectionId, placeId);
        if (!memberships.existsById(id)) throw ApiException.notFound("Collection place", placeId);
        memberships.deleteById(id);
        collection.touch(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private Collection requireOwnedForUpdate(UUID id, UUID userId) {
        Collection collection = collections.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Collection", id));
        requireOwner(collection, userId, id);
        return collection;
    }

    private void requireOwner(Collection collection, UUID userId, UUID id) {
        if (!collection.getUser().getId().equals(userId)) {
            throw ApiException.forbidden("You do not own this collection");
        }
    }

    /**
     * Visibility: PUBLIC — anyone except a block-separated authenticated viewer;
     * PRIVATE — owner only; FRIENDS — owner or mutual-follow friend of owner.
     * Blocked authenticated viewers receive 404 so the collection is not confirmed.
     */
    private void assertReadable(Collection collection, UUID viewerUserId, UUID collectionId) {
        UUID ownerId = collection.getUser().getId();
        boolean isOwner = viewerUserId != null && ownerId.equals(viewerUserId);
        if (!isOwner && viewerUserId != null && access.isBlockSeparated(viewerUserId, ownerId)) {
            throw ApiException.notFound("Collection", collectionId);
        }
        if (!access.canViewCollection(collection, viewerUserId)) {
            throw ApiException.forbidden("Collection is not visible");
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

package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.MediaAccessResponse;
import com.emirrkls.phokarta.backend.api.dto.MediaStateResponse;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentRequest;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentResponse;
import com.emirrkls.phokarta.backend.api.dto.VisitMediaResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.config.MediaProperties;
import com.emirrkls.phokarta.backend.domain.entity.MediaAsset;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.entity.VisitMedia;
import com.emirrkls.phokarta.backend.domain.model.MediaStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import com.emirrkls.phokarta.backend.repository.MediaAssetRepository;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitMediaRepository;
import com.emirrkls.phokarta.backend.storage.ObjectStorageException;
import com.emirrkls.phokarta.backend.storage.ObjectStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {
    private final MediaAssetRepository assets;
    private final VisitMediaRepository visitMedia;
    private final UserRepository users;
    private final UserFollowRepository follows;
    private final BlockService blocks;
    private final ObjectStorageService storage;
    private final MediaProperties properties;
    private final ApplicationMetrics metrics;
    private final MediaCleanupClaims cleanupClaims;

    public MediaService(MediaAssetRepository assets, VisitMediaRepository visitMedia,
                        UserRepository users, UserFollowRepository follows,
                        BlockService blocks, ObjectStorageService storage,
                        MediaProperties properties, ApplicationMetrics metrics,
                        MediaCleanupClaims cleanupClaims) {
        this.assets = assets;
        this.visitMedia = visitMedia;
        this.users = users;
        this.follows = follows;
        this.blocks = blocks;
        this.storage = storage;
        this.properties = properties;
        this.metrics = metrics;
        this.cleanupClaims = cleanupClaims;
    }

    @Transactional
    public MediaUploadIntentResponse createUploadIntent(UUID ownerId, MediaUploadIntentRequest request) {
        requireEnabled();
        users.lockAccount(ownerId);
        validateMetadata(request);
        assets.lockClientMedia(ownerId, request.clientMediaId());
        MediaAsset asset = assets.findByOwnerIdAndClientMediaId(ownerId, request.clientMediaId())
                .orElse(null);
        if (asset != null) {
            if (!sameMetadata(asset, request)) {
                metrics.mediaUploadIntent("conflict");
                throw ApiException.conflict("clientMediaId was already used with different media metadata");
            }
            metrics.mediaUploadIntent("idempotency_hit");
            return uploadIntentResponse(asset);
        }
        User owner = users.findById(ownerId).orElseThrow(() -> ApiException.notFound("User", ownerId));
        OffsetDateTime now = now();
        UUID id = UUID.randomUUID();
        asset = assets.save(new MediaAsset(id, owner, request.clientMediaId(),
                "users/" + ownerId + "/media/" + id + "/original",
                request.contentType(), request.byteSize(), request.width(), request.height(),
                now, now.plus(properties.unattachedTtl())));
        metrics.mediaUploadIntent("created");
        return uploadIntentResponse(asset);
    }

    private MediaUploadIntentResponse uploadIntentResponse(MediaAsset asset) {
        if (asset.getStatus() != MediaStatus.PENDING_UPLOAD) {
            return new MediaUploadIntentResponse(asset.getId(), asset.getStatus(), null, Map.of(), null);
        }
        OffsetDateTime expiresAt = now().plus(properties.uploadTtl());
        try {
            ObjectStorageService.SignedRequest signed = storage.presignPut(asset.getStorageKey(),
                    asset.getContentType(), asset.getByteSize(), properties.uploadTtl());
            return new MediaUploadIntentResponse(asset.getId(), asset.getStatus(), signed.url(),
                    signed.requiredHeaders(), expiresAt);
        } catch (ObjectStorageException ex) {
            metrics.mediaUploadIntent("storage_error");
            throw storageUnavailable();
        }
    }

    @Transactional
    public MediaStateResponse confirm(UUID ownerId, UUID mediaId) {
        requireEnabled();
        users.lockAccount(ownerId);
        MediaAsset asset = assets.findByIdForUpdate(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media", mediaId));
        if (!asset.getOwner().getId().equals(ownerId)) {
            metrics.mediaConfirm("forbidden");
            throw ApiException.forbidden("Media belongs to another user");
        }
        if (asset.getStatus() != MediaStatus.PENDING_UPLOAD) {
            metrics.mediaConfirm("idempotent");
            return new MediaStateResponse(asset.getId(), asset.getStatus());
        }
        ObjectStorageService.StoredObject object;
        try {
            object = storage.head(asset.getStorageKey());
        } catch (ObjectStorageException ex) {
            metrics.mediaConfirm("storage_error");
            throw storageUnavailable();
        }
        if (object == null) {
            metrics.mediaConfirm("missing");
            throw ApiException.validation("Uploaded object was not found");
        }
        if (object.byteSize() != asset.getByteSize()) {
            metrics.mediaConfirm("size_mismatch");
            throw ApiException.validation("Uploaded object size does not match the upload intent");
        }
        if (object.contentType() != null && !object.contentType().equals(asset.getContentType())) {
            metrics.mediaConfirm("type_mismatch");
            throw ApiException.validation("Uploaded object content type does not match the upload intent");
        }
        OffsetDateTime now = now();
        asset.markReady(now, now.plus(properties.unattachedTtl()), object.etag());
        metrics.mediaConfirm("ready");
        return new MediaStateResponse(asset.getId(), asset.getStatus());
    }

    @Transactional(readOnly = true)
    public MediaAccessResponse access(UUID mediaId, UUID viewerId) {
        requireEnabled();
        MediaAsset asset = assets.findById(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media", mediaId));
        if (asset.getStatus() == MediaStatus.PENDING_UPLOAD
                || asset.getStatus() == MediaStatus.DELETING) {
            throw ApiException.notFound("Media", mediaId);
        }
        if (asset.getStatus() == MediaStatus.READY) {
            if (viewerId == null || !asset.getOwner().getId().equals(viewerId)) {
                throw ApiException.forbidden("Media is not accessible");
            }
        } else {
            VisitMedia relation = visitMedia.findByMediaId(mediaId)
                    .orElseThrow(() -> ApiException.notFound("Media", mediaId));
            authorizeAttached(relation, viewerId);
        }
        OffsetDateTime expiresAt = now().plus(properties.readTtl());
        return new MediaAccessResponse(signRead(asset), expiresAt);
    }

    @Transactional
    public List<VisitMediaResponse> attach(Visit visit, UUID ownerId, List<UUID> requestedIds) {
        List<UUID> ids = normalizeAndValidateIds(requestedIds);
        if (ids.isEmpty()) return List.of();
        List<UUID> lockOrder = ids.stream().sorted().toList();
        Map<UUID, MediaAsset> byId = new HashMap<>();
        assets.findAllByIdForUpdate(lockOrder).forEach(asset -> byId.put(asset.getId(), asset));
        List<VisitMedia> relations = new ArrayList<>(ids.size());
        OffsetDateTime now = now();
        for (int index = 0; index < ids.size(); index++) {
            MediaAsset asset = byId.get(ids.get(index));
            if (asset == null) throw ApiException.validation("One or more mediaIds are invalid");
            if (!asset.getOwner().getId().equals(ownerId)) {
                throw ApiException.forbidden("Media belongs to another user");
            }
            if (asset.getStatus() != MediaStatus.READY) {
                throw ApiException.validation("All mediaIds must be READY and unattached");
            }
            asset.markAttached(now);
            relations.add(new VisitMedia(visit, asset, index));
        }
        visitMedia.saveAll(relations);
        return signRelations(relations);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<VisitMediaResponse>> descriptorsForVisits(List<UUID> visitIds) {
        if (visitIds.isEmpty() || !properties.enabled()) return Map.of();
        Map<UUID, List<VisitMediaResponse>> result = new LinkedHashMap<>();
        for (VisitMedia relation : visitMedia.findByVisitIds(visitIds)) {
            result.computeIfAbsent(relation.getVisit().getId(), ignored -> new ArrayList<>())
                    .add(signRelation(relation));
        }
        return result;
    }

    public List<UUID> normalizeAndValidateIds(List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return List.of();
        if (requestedIds.size() > properties.maxPerVisit()) {
            throw ApiException.validation("mediaIds must contain at most " + properties.maxPerVisit() + " items");
        }
        Set<UUID> unique = new HashSet<>();
        for (UUID id : requestedIds) {
            if (id == null) throw ApiException.validation("mediaIds must not contain null");
            if (!unique.add(id)) throw ApiException.validation("mediaIds must not contain duplicates");
        }
        return List.copyOf(requestedIds);
    }

    /**
     * Claims are committed before storage is touched. DELETING rows are never attachable and are
     * leased for one cleanup interval so concurrent instances do not normally process the same key.
     */
    @Scheduled(fixedDelayString = "${phokarta.media.cleanup-interval:1h}")
    public void cleanupExpired() {
        if (!properties.enabled()) return;
        for (MediaCleanupClaims.Target target : cleanupClaims.claimExpired(now())) {
            try {
                storage.delete(target.storageKey());
                if (cleanupClaims.completeDeletion(target.id())) {
                    metrics.mediaCleanup("deleted");
                }
            } catch (RuntimeException ex) {
                metrics.mediaCleanup("failed");
            }
        }
    }

    private void authorizeAttached(VisitMedia relation, UUID viewerId) {
        UUID ownerId = relation.getMedia().getOwner().getId();
        Visibility visibility = relation.getVisit().getVisibility();
        if (ownerId.equals(viewerId)) return;
        if (viewerId != null && blocks.isBlockedEitherDirection(viewerId, ownerId)) {
            throw ApiException.notFound("Media", relation.getMedia().getId());
        }
        if (visibility == Visibility.PUBLIC) return;
        if (visibility == Visibility.FRIENDS && viewerId != null
                && follows.areFriends(viewerId, ownerId)) return;
        throw ApiException.forbidden("Media is not accessible");
    }

    private List<VisitMediaResponse> signRelations(List<VisitMedia> relations) {
        return relations.stream().map(this::signRelation).toList();
    }

    private VisitMediaResponse signRelation(VisitMedia relation) {
        OffsetDateTime expiresAt = now().plus(properties.readTtl());
        return new VisitMediaResponse(relation.getMedia().getId(), relation.getSortOrder(),
                signRead(relation.getMedia()), expiresAt);
    }

    private URI signRead(MediaAsset asset) {
        try {
            return storage.presignGet(asset.getStorageKey(), properties.readTtl());
        } catch (ObjectStorageException ex) {
            throw storageUnavailable();
        }
    }

    private void validateMetadata(MediaUploadIntentRequest request) {
        if (!properties.acceptedContentTypes().contains(request.contentType())) {
            throw ApiException.validation("Unsupported image content type");
        }
        if (request.byteSize() > properties.maxBytes()) {
            throw ApiException.validation("Image exceeds the configured maximum size");
        }
    }

    private boolean sameMetadata(MediaAsset asset, MediaUploadIntentRequest request) {
        return asset.getContentType().equals(request.contentType())
                && asset.getByteSize() == request.byteSize()
                && Objects.equals(asset.getWidth(), request.width())
                && Objects.equals(asset.getHeight(), request.height());
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE",
                    "Media storage is unavailable");
        }
    }

    private ApiException storageUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE",
                "Media storage is temporarily unavailable");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}

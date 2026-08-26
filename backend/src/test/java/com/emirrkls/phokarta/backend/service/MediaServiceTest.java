package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentRequest;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {
    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final VisitMediaRepository visitMedia = mock(VisitMediaRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserFollowRepository follows = mock(UserFollowRepository.class);
    private final BlockService blocks = mock(BlockService.class);
    private final FakeStorage storage = new FakeStorage();
    private final MediaCleanupClaims cleanupClaims = mock(MediaCleanupClaims.class);
    private MediaService service;
    private User owner;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        MediaProperties properties = new MediaProperties(true, "test", "us-east-1", null,
                true, "test", "test", 15L * 1024 * 1024, 20,
                Set.of("image/jpeg", "image/png", "image/webp"), Duration.ofMinutes(15),
                Duration.ofMinutes(10), Duration.ofHours(48), 100, Duration.ofHours(1),
                Duration.ofMinutes(2));
        service = new MediaService(assets, visitMedia, users, follows, blocks, storage, properties,
                new ApplicationMetrics(new SimpleMeterRegistry()), cleanupClaims, mock(UgcPolicyService.class));
        ownerId = UUID.randomUUID();
        owner = new User(ownerId, "owner@example.test", "owner", "Owner", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(users.findById(ownerId)).thenReturn(Optional.of(owner));
        when(assets.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uploadIntentIsIdempotentAndRejectsConflictingMetadata() {
        UUID clientId = UUID.randomUUID();
        MediaUploadIntentRequest request = new MediaUploadIntentRequest(
                clientId, "image/jpeg", 123L, 20, 10);

        var created = service.createUploadIntent(ownerId, request);
        MediaAsset existing = new MediaAsset(created.mediaId(), owner, clientId,
                "users/" + ownerId + "/media/" + created.mediaId() + "/original",
                "image/jpeg", 123, 20, 10, OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(48));
        when(assets.findByOwnerIdAndClientMediaId(ownerId, clientId))
                .thenReturn(Optional.of(existing));

        var retry = service.createUploadIntent(ownerId, request);
        assertThat(retry.mediaId()).isEqualTo(created.mediaId());
        assertThat(retry.uploadUrl()).isNotNull();

        assertThatThrownBy(() -> service.createUploadIntent(ownerId,
                new MediaUploadIntentRequest(clientId, "image/png", 123L, 20, 10)))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(409);
    }

    @Test
    void confirmVerifiesMetadataAndIsIdempotent() {
        MediaAsset asset = pending(ownerId, owner);
        when(assets.findByIdForUpdate(asset.getId())).thenReturn(Optional.of(asset));
        storage.head = new ObjectStorageService.StoredObject(
                asset.getByteSize(), asset.getContentType(), "etag");

        assertThat(service.confirm(ownerId, asset.getId()).status()).isEqualTo(MediaStatus.READY);
        assertThat(service.confirm(ownerId, asset.getId()).status()).isEqualTo(MediaStatus.READY);

        MediaAsset wrong = pending(ownerId, owner);
        when(assets.findByIdForUpdate(wrong.getId())).thenReturn(Optional.of(wrong));
        storage.head = new ObjectStorageService.StoredObject(wrong.getByteSize() + 1,
                wrong.getContentType(), null);
        assertThatThrownBy(() -> service.confirm(ownerId, wrong.getId()))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(400);

        MediaAsset missing = pending(ownerId, owner);
        when(assets.findByIdForUpdate(missing.getId())).thenReturn(Optional.of(missing));
        storage.head = null;
        assertThatThrownBy(() -> service.confirm(ownerId, missing.getId()))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(400);
    }

    @Test
    void crossUserCannotConfirm() {
        MediaAsset asset = pending(ownerId, owner);
        when(assets.findByIdForUpdate(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.confirm(UUID.randomUUID(), asset.getId()))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(403);
        assertThat(storage.headCalls).isZero();
    }

    @Test
    void accessEnforcesReadyOwnerAndMutualFriends() {
        MediaAsset ready = pending(ownerId, owner);
        ready.markReady(OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(48), null);
        when(assets.findById(ready.getId())).thenReturn(Optional.of(ready));

        assertThatThrownBy(() -> service.access(ready.getId(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(403);
        assertThat(service.access(ready.getId(), ownerId).url()).isNotNull();

        MediaAsset attached = mock(MediaAsset.class);
        VisitMedia relation = mock(VisitMedia.class);
        Visit visit = mock(Visit.class);
        UUID mediaId = UUID.randomUUID();
        UUID friendId = UUID.randomUUID();
        when(attached.getId()).thenReturn(mediaId);
        when(attached.getStatus()).thenReturn(MediaStatus.ATTACHED);
        when(attached.getOwner()).thenReturn(owner);
        when(attached.getStorageKey()).thenReturn("private-key");
        when(assets.findById(mediaId)).thenReturn(Optional.of(attached));
        when(relation.getMedia()).thenReturn(attached);
        when(relation.getVisit()).thenReturn(visit);
        when(visit.getVisibility()).thenReturn(Visibility.FRIENDS);
        when(visitMedia.findByMediaId(mediaId)).thenReturn(Optional.of(relation));
        when(follows.areFriends(friendId, ownerId)).thenReturn(true);

        assertThat(service.access(mediaId, friendId).url()).isNotNull();
        assertThatThrownBy(() -> service.access(mediaId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(403);

        when(visit.getVisibility()).thenReturn(Visibility.PUBLIC);
        assertThat(service.access(mediaId, null).url()).isNotNull();
        UUID blockedViewer = UUID.randomUUID();
        when(blocks.isBlockedEitherDirection(blockedViewer, ownerId)).thenReturn(true);
        assertThatThrownBy(() -> service.access(mediaId, blockedViewer))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(404);
        when(visit.getVisibility()).thenReturn(Visibility.PRIVATE);
        assertThat(service.access(mediaId, ownerId).url()).isNotNull();
        assertThatThrownBy(() -> service.access(mediaId, null))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(403);
    }

    @Test
    void attachValidatesAndOrdersReadyOwnerMedia() {
        MediaAsset first = pending(ownerId, owner);
        MediaAsset second = pending(ownerId, owner);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        first.markReady(now, now.plusHours(48), null);
        second.markReady(now, now.plusHours(48), null);
        when(assets.findAllByIdForUpdate(anyList())).thenReturn(List.of(first, second));
        Visit visit = mock(Visit.class);
        when(visit.getId()).thenReturn(UUID.randomUUID());

        var descriptors = service.attach(visit, ownerId, List.of(second.getId(), first.getId()));

        assertThat(descriptors).extracting("id")
                .containsExactly(second.getId(), first.getId());
        assertThat(descriptors).extracting("sortOrder").containsExactly(0, 1);
        assertThat(first.getStatus()).isEqualTo(MediaStatus.ATTACHED);
        assertThat(first.getExpiresAt()).isNull();

        assertThatThrownBy(() -> service.attach(visit, ownerId,
                List.of(first.getId(), first.getId())))
                .isInstanceOf(ApiException.class)
                .extracting("status.value").isEqualTo(400);
    }

    @Test
    void cleanupCompletesSuccessfulDeleteAndRetainsFailedClaim() {
        MediaAsset deleted = pending(ownerId, owner);
        MediaAsset failed = pending(ownerId, owner);
        when(cleanupClaims.claimExpired(any())).thenReturn(List.of(
                new MediaCleanupClaims.Target(deleted.getId(), deleted.getStorageKey()),
                new MediaCleanupClaims.Target(failed.getId(), failed.getStorageKey())));
        when(cleanupClaims.completeDeletion(deleted.getId())).thenReturn(true);
        storage.failDeleteKey = failed.getStorageKey();

        service.cleanupExpired();

        verify(cleanupClaims).completeDeletion(deleted.getId());
        verify(cleanupClaims, never()).completeDeletion(failed.getId());
        assertThat(storage.deletedKeys).containsExactly(deleted.getStorageKey());
    }

    @Test
    void cleanupLeavesDeletingClaimWhenDatabaseCompletionFailsAfterObjectDelete() {
        MediaAsset asset = pending(ownerId, owner);
        MediaCleanupClaims.Target target =
                new MediaCleanupClaims.Target(asset.getId(), asset.getStorageKey());
        when(cleanupClaims.claimExpired(any())).thenReturn(List.of(target));
        when(cleanupClaims.completeDeletion(asset.getId()))
                .thenThrow(new IllegalStateException("database unavailable"));

        service.cleanupExpired();

        assertThat(storage.deletedKeys).containsExactly(asset.getStorageKey());
        verify(cleanupClaims).completeDeletion(asset.getId());
    }

    private MediaAsset pending(UUID userId, User user) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new MediaAsset(id, user, UUID.randomUUID(),
                "users/" + userId + "/media/" + id + "/original",
                "image/jpeg", 123, null, null, now, now.plusHours(48));
    }

    private static final class FakeStorage implements ObjectStorageService {
        StoredObject head;
        int headCalls;
        String failDeleteKey;
        final java.util.ArrayList<String> deletedKeys = new java.util.ArrayList<>();

        @Override public SignedRequest presignPut(String key, String type, long size, Duration ttl) {
            return new SignedRequest(URI.create("https://storage.test/upload"), Map.of(
                    "Content-Type", type, "Content-Length", Long.toString(size)));
        }
        @Override public URI presignGet(String key, Duration ttl) {
            return URI.create("https://storage.test/read");
        }
        @Override public StoredObject head(String key) {
            headCalls++;
            return head;
        }
        @Override public void delete(String key) {
            if (key.equals(failDeleteKey)) throw new ObjectStorageException("failed", null);
            deletedKeys.add(key);
        }
    }
}

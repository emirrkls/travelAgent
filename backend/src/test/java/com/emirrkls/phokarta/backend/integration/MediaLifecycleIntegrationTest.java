package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentRequest;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.service.MediaCleanupClaims;
import com.emirrkls.phokarta.backend.service.MediaService;
import com.emirrkls.phokarta.backend.service.VisitService;
import com.emirrkls.phokarta.backend.storage.ObjectStorageService;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
@Import(MediaLifecycleIntegrationTest.StorageTestConfig.class)
@TestPropertySource(properties = "phokarta.media.cleanup-interval=24h")
class MediaLifecycleIntegrationTest {
    private static final UUID OWNER = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final UUID VIEWER = UUID.fromString("a1000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("a1000000-0000-0000-0000-000000000003");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Autowired MediaService media;
    @Autowired MediaCleanupClaims cleanupClaims;
    @Autowired VisitService visits;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestObjectStorage storage;
    @Autowired MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from user_follows where follower_user_id in (?, ?, ?) or followed_user_id in (?, ?, ?)",
                OWNER, VIEWER, OTHER, OWNER, VIEWER, OTHER);
        jdbc.update("delete from user_blocks where blocker_user_id in (?, ?, ?) or blocked_user_id in (?, ?, ?)",
                OWNER, VIEWER, OTHER, OWNER, VIEWER, OTHER);
        jdbc.update("delete from visits where user_id in (?, ?, ?)", OWNER, VIEWER, OTHER);
        jdbc.update("delete from media_assets where owner_user_id in (?, ?, ?)", OWNER, VIEWER, OTHER);
        ensureUser(OWNER, "media_owner");
        ensureUser(VIEWER, "media_viewer");
        ensureUser(OTHER, "media_other");
        PolicyAcceptanceSupport.acceptCurrent(jdbc, OWNER);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, VIEWER);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, OTHER);
        storage.reset();
    }

    @Test
    void sameClientMediaIdIsIndependentAcrossOwners() {
        UUID clientId = UUID.randomUUID();
        UUID first = intent(OWNER, clientId, "image/jpeg", 100).mediaId();
        UUID second = intent(VIEWER, clientId, "image/jpeg", 100).mediaId();

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select count(*) from media_assets where client_media_id = ?",
                Integer.class, clientId)).isEqualTo(2);
    }

    @Test
    void confirmationRejectsContentTypeMismatch() {
        var intent = intent(OWNER, UUID.randomUUID(), "image/jpeg", 100);
        storage.upload(intent.mediaId(), OWNER, "image/png", 100);

        assertStatus(400, () -> media.confirm(OWNER, intent.mediaId()));
        assertThat(assetStatus(intent.mediaId())).isEqualTo("PENDING_UPLOAD");
    }

    @Test
    void attachmentRejectsReuseCrossOwnerNonReadyDuplicatesAndTooMany() {
        UUID ready = ready(OWNER);
        VisitOwnerResponse visit = visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, List.of(ready)));
        assertThat(relationCount(visit.id())).isEqualTo(1);

        assertStatus(400, () -> visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, List.of(ready))));

        UUID otherReady = ready(VIEWER);
        assertStatus(403, () -> visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, List.of(otherReady))));

        UUID pending = intent(OWNER, UUID.randomUUID(), "image/jpeg", 100).mediaId();
        assertStatus(400, () -> visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, List.of(pending))));
        assertStatus(400, () -> visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, List.of(pending, pending))));

        List<UUID> tooMany = new ArrayList<>();
        for (int index = 0; index < 21; index++) tooMany.add(UUID.randomUUID());
        assertStatus(400, () -> visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, tooMany)));
    }

    @Test
    void readyUnattachedIsOwnerOnlyAndAttachedVisibilityUsesMutualFriendship() {
        UUID unattached = ready(OWNER);
        assertThat(media.access(unattached, OWNER).url()).isNotNull();
        assertStatus(403, () -> media.access(unattached, null));

        UUID publicMedia = attach(OWNER, Visibility.PUBLIC);
        assertThat(media.access(publicMedia, null).url()).isNotNull();

        UUID friendsMedia = attach(OWNER, Visibility.FRIENDS);
        follow(VIEWER, OWNER);
        assertStatus(403, () -> media.access(friendsMedia, VIEWER));
        assertStatus(403, () -> media.access(friendsMedia, null));
        follow(OWNER, VIEWER);
        assertThat(media.access(friendsMedia, VIEWER).url()).isNotNull();

        UUID privateMedia = attach(OWNER, Visibility.PRIVATE);
        assertThat(media.access(privateMedia, OWNER).url()).isNotNull();
        assertStatus(403, () -> media.access(privateMedia, VIEWER));
        assertStatus(403, () -> media.access(privateMedia, null));
    }

    @Test
    void authenticatedBlockedViewerDoesNotReceivePublicMediaUrl() {
        UUID publicMedia = attach(OWNER, Visibility.PUBLIC);
        assertThat(media.access(publicMedia, VIEWER).url()).isNotNull();
        jdbc.update("""
                insert into user_blocks (blocker_user_id, blocked_user_id, created_at)
                values (?, ?, now())
                """, VIEWER, OWNER);
        assertStatus(404, () -> media.access(publicMedia, VIEWER));
        assertThat(media.access(publicMedia, null).url()).isNotNull();
        assertThat(media.access(publicMedia, OWNER).url()).isNotNull();
    }

    @Test
    void anonymousControllerAllowsPublicAndBlocksPrivate() throws Exception {
        UUID publicMedia = attach(OWNER, Visibility.PUBLIC);
        UUID privateMedia = attach(OWNER, Visibility.PRIVATE);

        mvc.perform(get("/api/v1/media/{mediaId}/access", publicMedia))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/media/{mediaId}/access", privateMedia))
                .andExpect(status().isForbidden());
    }

    @Test
    void lostAckAndChangedOrderPreserveMediaIdempotency() {
        UUID firstMedia = ready(OWNER);
        UUID secondMedia = ready(OWNER);
        UUID mutation = UUID.randomUUID();
        CreateVisitRequest request = visitRequest(
                mutation, Visibility.PRIVATE, List.of(firstMedia, secondMedia));

        VisitOwnerResponse first = visits.create(OWNER, request);
        VisitOwnerResponse retry = visits.create(OWNER, request);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(relationCount(first.id())).isEqualTo(2);
        assertStatus(409, () -> visits.create(OWNER, visitRequest(
                mutation, Visibility.PRIVATE, List.of(secondMedia, firstMedia))));
        assertThat(relationCount(first.id())).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateVisitCreatesOneRelation() throws Exception {
        UUID mediaId = ready(OWNER);
        UUID mutation = UUID.randomUUID();
        CreateVisitRequest request = visitRequest(mutation, Visibility.PRIVATE, List.of(mediaId));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> visits.create(OWNER, request));
            var second = executor.submit(() -> visits.create(OWNER, request));
            assertThat(first.get().id()).isEqualTo(second.get().id());
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from visits where user_id = ? and client_mutation_id = ?",
                Integer.class, OWNER, mutation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from visit_media vm join visits v on v.id = vm.visit_id
                where v.user_id = ? and v.client_mutation_id = ?
                """, Integer.class, OWNER, mutation)).isEqualTo(1);
    }

    @Test
    void cleanupDeletesExpiredReadyButNeverAttached() {
        UUID expired = ready(OWNER);
        UUID attached = attach(OWNER, Visibility.PUBLIC);
        jdbc.update("update media_assets set expires_at = now() - interval '1 hour' where id = ?", expired);

        media.cleanupExpired();

        assertThat(jdbc.queryForObject(
                "select count(*) from media_assets where id = ?", Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from media_assets where id = ?", Integer.class, attached)).isOne();
        assertThat(storage.deletedMediaIds).contains(expired).doesNotContain(attached);
    }

    @Test
    void cleanupClaimSurvivesObjectDeletionInterruptionAndCannotBeAttached() {
        UUID expired = ready(OWNER);
        jdbc.update("update media_assets set expires_at = now() - interval '1 hour' where id = ?", expired);

        MediaCleanupClaims.Target target = cleanupClaims.claimExpired(
                        OffsetDateTime.now(ZoneOffset.UTC)).stream()
                .filter(candidate -> candidate.id().equals(expired))
                .findFirst()
                .orElseThrow();
        storage.delete(target.storageKey());

        assertThat(assetStatus(expired)).isEqualTo("DELETING");
        assertStatus(400, () -> visits.create(OWNER, visitRequest(
                UUID.randomUUID(), Visibility.PRIVATE, List.of(expired))));
        assertThat(jdbc.queryForObject(
                "select count(*) from media_assets where id = ?", Integer.class, expired)).isOne();
        assertThat(storage.head(target.storageKey())).isNull();
    }

    @Test
    void cleanupRetriesDeletingRowAfterProviderFailure() {
        UUID expired = ready(OWNER);
        jdbc.update("update media_assets set expires_at = now() - interval '1 hour' where id = ?", expired);
        storage.failDeleteKey = storage.keysByMediaId.get(expired);

        media.cleanupExpired();

        assertThat(assetStatus(expired)).isEqualTo("DELETING");
        storage.failDeleteKey = null;
        jdbc.update("update media_assets set updated_at = now() - interval '25 hours' where id = ?", expired);

        media.cleanupExpired();

        assertThat(jdbc.queryForObject(
                "select count(*) from media_assets where id = ?", Integer.class, expired)).isZero();
        assertThat(storage.deletedMediaIds).contains(expired);
    }

    private com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentResponse intent(
            UUID owner, UUID clientId, String contentType, long bytes) {
        var response = media.createUploadIntent(owner,
                new MediaUploadIntentRequest(clientId, contentType, bytes, null, null));
        storage.keysByMediaId.put(response.mediaId(),
                "users/" + owner + "/media/" + response.mediaId() + "/original");
        return response;
    }

    private UUID ready(UUID owner) {
        var intent = intent(owner, UUID.randomUUID(), "image/jpeg", 100);
        storage.upload(intent.mediaId(), owner, "image/jpeg", 100);
        media.confirm(owner, intent.mediaId());
        return intent.mediaId();
    }

    private UUID attach(UUID owner, Visibility visibility) {
        UUID mediaId = ready(owner);
        visits.create(owner, visitRequest(UUID.randomUUID(), visibility, List.of(mediaId)));
        return mediaId;
    }

    private CreateVisitRequest visitRequest(
            UUID mutation, Visibility visibility, List<UUID> mediaIds) {
        return new CreateVisitRequest(mutation, PLACE, LocalDate.of(2026, 8, 20), 8.5,
                List.of(), "media review", "private", List.of(), mediaIds, visibility);
    }

    private void follow(UUID follower, UUID followed) {
        jdbc.update("""
                insert into user_follows(follower_user_id, followed_user_id, created_at)
                values (?, ?, now()) on conflict do nothing
                """, follower, followed);
    }

    private void ensureUser(UUID id, String username) {
        if (!users.existsById(id)) {
            users.save(new User(id, username + "@example.test", username, username,
                    null, OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }

    private int relationCount(UUID visitId) {
        return jdbc.queryForObject(
                "select count(*) from visit_media where visit_id = ?", Integer.class, visitId);
    }

    private String assetStatus(UUID mediaId) {
        return jdbc.queryForObject(
                "select status from media_assets where id = ?", String.class, mediaId);
    }

    private void assertStatus(int expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.status().value()).isEqualTo(expected));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfig {
        @Bean
        @Primary
        TestObjectStorage testObjectStorage() {
            return new TestObjectStorage();
        }
    }

    static final class TestObjectStorage implements ObjectStorageService {
        private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        private final Map<UUID, String> keysByMediaId = new ConcurrentHashMap<>();
        private final List<UUID> deletedMediaIds = new ArrayList<>();
        private String failDeleteKey;

        void upload(UUID mediaId, UUID owner, String contentType, long byteSize) {
            String key = keysByMediaId.getOrDefault(mediaId,
                    "users/" + owner + "/media/" + mediaId + "/original");
            objects.put(key, new StoredObject(byteSize, contentType, "etag"));
        }

        void reset() {
            objects.clear();
            keysByMediaId.clear();
            deletedMediaIds.clear();
            failDeleteKey = null;
        }

        @Override
        public SignedRequest presignPut(String key, String contentType, long byteSize, Duration ttl) {
            return new SignedRequest(URI.create("https://storage.test/upload"),
                    Map.of("Content-Type", contentType, "Content-Length", Long.toString(byteSize)));
        }

        @Override
        public URI presignGet(String key, Duration ttl) {
            return URI.create("https://storage.test/read");
        }

        @Override
        public StoredObject head(String key) {
            return objects.get(key);
        }

        @Override
        public void delete(String key) {
            if (key.equals(failDeleteKey)) {
                throw new com.emirrkls.phokarta.backend.storage.ObjectStorageException(
                        "delete failed", null);
            }
            objects.remove(key);
            keysByMediaId.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(key))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(deletedMediaIds::add);
        }
    }
}

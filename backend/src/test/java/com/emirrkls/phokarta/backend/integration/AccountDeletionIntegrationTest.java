package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentRequest;
import com.emirrkls.phokarta.backend.config.MediaProperties;
import com.emirrkls.phokarta.backend.domain.entity.AccountDeletionMediaJob;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.AccountDeletionMediaCleanupService;
import com.emirrkls.phokarta.backend.service.MediaService;
import com.emirrkls.phokarta.backend.service.SavedPlaceService;
import com.emirrkls.phokarta.backend.service.VisitService;
import com.emirrkls.phokarta.backend.storage.ObjectStorageException;
import com.emirrkls.phokarta.backend.storage.ObjectStorageService;
import com.emirrkls.phokarta.backend.support.MutableClock;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
@Import(AccountDeletionIntegrationTest.StorageTestConfig.class)
@TestPropertySource(properties = "phokarta.media.cleanup-interval=24h")
class AccountDeletionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VisitService visits;
    @Autowired private MediaService media;
    @Autowired private AccountDeletionMediaCleanupService cleanup;
    @Autowired private TestObjectStorage storage;
    @Autowired private MediaProperties mediaProperties;
    @Autowired private MutableClock clock;
    @Autowired private SavedPlaceService savedPlaces;

    @BeforeEach
    void resetStorage() {
        storage.reset();
        clock.resetToNow();
    }

    @Test
    void populatedAccountIsHardDeletedAndOtherUsersRemain() throws Exception {
        Session a = register("delA");
        Session b = register("delB");
        UUID place = insertPlace("Delete Place", PlaceCategory.RESTAURANT, 28.1, 38.1);

        visits.create(a.id, visit(place, LocalDate.of(2026, 1, 10), 10.0, Visibility.PUBLIC,
                "A public review", "A private memory"));
        visits.create(a.id, visit(place, LocalDate.of(2026, 2, 10), 8.0, Visibility.FRIENDS,
                "A friends review", "A friends memory"));
        visits.create(a.id, visit(place, LocalDate.of(2026, 3, 10), 6.0, Visibility.PRIVATE,
                "A private review", "A secret"));
        visits.create(b.id, visit(place, LocalDate.of(2026, 1, 11), 6.0, Visibility.PUBLIC,
                "B public review", "B memory"));

        follow(a, b);
        follow(b, a);
        Session c = register("delC");
        follow(c, a);

        mockMvc.perform(post("/api/v1/me/saved-places/{placeId}", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/saved-places/{placeId}", place)
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk());

        MvcResult collection = mockMvc.perform(post("/api/v1/me/collections")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"A list","description":"mine","visibility":"PUBLIC",
                                "coverImage":"https://example.test/cover.jpg"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID collectionId = UUID.fromString(objectMapper.readTree(
                collection.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(post("/api/v1/collections/{id}/places/{placeId}", collectionId, place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk());

        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 100L, 10, 10));
        storage.upload(intent.mediaId(), a.id, "image/jpeg", 100);
        media.confirm(a.id, intent.mediaId());
        visits.create(a.id, visitWithMedia(place, LocalDate.of(2026, 4, 10), 7.0,
                Visibility.PRIVATE, List.of(intent.mediaId())));

        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
                .andExpect(status().isNoContent());

        assertThat(count("users", "id", a.id)).isZero();
        assertThat(count("visits", "user_id", a.id)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from visit_dimension_scores ds join visits v on v.id = ds.visit_id where v.user_id = ?",
                Integer.class, a.id)).isZero();
        assertThat(count("saved_places", "user_id", a.id)).isZero();
        assertThat(count("collections", "user_id", a.id)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from collection_places where collection_id = ?",
                Integer.class, collectionId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from user_follows
                where follower_user_id = ? or followed_user_id = ?
                """, Integer.class, a.id, a.id)).isZero();
        assertThat(count("auth_identities", "user_id", a.id)).isZero();
        assertThat(count("refresh_sessions", "user_id", a.id)).isZero();
        assertThat(count("media_assets", "owner_user_id", a.id)).isZero();
        String mediaKey = storage.keyFor(intent.mediaId());
        assertThat(storage.objects.containsKey(mediaKey)).isFalse();
        assertJobAwaitingFinal(mediaKey);
        completeFinalCleanup();
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, mediaKey)).isZero();

        assertThat(count("users", "id", b.id)).isEqualTo(1);
        assertThat(count("visits", "user_id", b.id)).isEqualTo(1);
        assertThat(count("saved_places", "user_id", b.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from places where id = ?",
                Integer.class, place)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + a.access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + a.refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","password":"SecurePass1"}
                                """.formatted(a.email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(get("/api/v1/users/{id}", a.id))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(6.0));
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("B public review"));
        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].publicReview").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("A public review"))));

        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult again = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","displayName":"A again",
                                "password":"SecurePass1"}
                                """.formatted(a.email, "n" + a.username.substring(1))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID newId = UUID.fromString(objectMapper.readTree(
                again.getResponse().getContentAsString()).get("user").get("id").asText());
        assertThat(newId).isNotEqualTo(a.id);
        assertThat(count("visits", "user_id", newId)).isZero();
        assertThat(count("saved_places", "user_id", newId)).isZero();
    }

    @Test
    void socialEdgesBothDirectionsAreRemoved() throws Exception {
        Session a = register("socA");
        Session b = register("socB");
        Session c = register("socC");
        follow(a, b);
        follow(b, a);
        follow(c, a);
        follow(b, c);

        deleteAccount(a);

        assertThat(jdbc.queryForObject("""
                select count(*) from user_follows
                where follower_user_id = ? or followed_user_id = ?
                """, Integer.class, a.id, a.id)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from user_follows
                where follower_user_id = ? and followed_user_id = ?
                """, Integer.class, b.id, c.id)).isEqualTo(1);
    }

    @Test
    void friendMetricsStopIncludingDeletedUser() throws Exception {
        Session a = register("frA");
        Session b = register("frB");
        follow(a, b);
        follow(b, a);
        UUID place = insertPlace("Friend Metric Place", PlaceCategory.RESTAURANT, 28.2, 38.2);
        visits.create(b.id, visit(place, LocalDate.of(2026, 5, 1), 9.0, Visibility.FRIENDS,
                "friend review", "mem"));

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[\"" + place + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(1));

        deleteAccount(b);

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[\"" + place + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(0));
        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(0));
    }

    @Test
    void wrongPasswordDoesNotDeleteAndDoesNotLogPassword() throws Exception {
        Session a = register("pwdA");
        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"WrongPass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        assertThat(count("users", "id", a.id)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk());
    }

    @Test
    void callerCannotDeleteAnotherAccountViaPathOrBody() throws Exception {
        Session a = register("ownA");
        Session b = register("ownB");
        mockMvc.perform(delete("/api/v1/users/{id}", a.id)
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + b.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\",\"userId\":\"" + a.id + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(count("users", "id", a.id)).isEqualTo(1);
        assertThat(count("users", "id", b.id)).isZero();
    }

    @Test
    void mediaCleanupJobSurvivesStorageFailureThenSucceeds() throws Exception {
        Session a = register("mediaA");
        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 80L, 8, 8));
        storage.upload(intent.mediaId(), a.id, "image/jpeg", 80);
        media.confirm(a.id, intent.mediaId());
        String key = storage.keyFor(intent.mediaId());
        storage.failDeleteKey = key;

        deleteAccount(a);

        assertThat(count("users", "id", a.id)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(storage.objects.containsKey(key)).isTrue();

        cleanup.processDueJobs();
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(count("users", "id", a.id)).isZero();

        storage.failDeleteKey = null;
        jdbc.update("""
                update account_deletion_media_jobs
                set next_attempt_at = now() - interval '1 minute'
                where storage_key = ?
                """, key);
        cleanup.processDueJobs();
        assertThat(storage.objects.containsKey(key)).isFalse();
        assertJobAwaitingFinal(key);
        completeFinalCleanup();
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isZero();
    }

    @Test
    void missingObjectCompletesCleanupJob() throws Exception {
        Session a = register("missA");
        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 50L, 4, 4));
        String key = "users/" + a.id + "/media/" + intent.mediaId() + "/original";
        storage.keysByMediaId.put(intent.mediaId(), key);

        deleteAccount(a);
        cleanup.processDueJobs();
        assertJobAwaitingFinal(key);
        completeFinalCleanup();

        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isZero();
    }

    @Test
    void latePresignedPutIsDeletedAfterUploadCapabilityExpires() throws Exception {
        Session a = register("latePut");
        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 64L, 4, 4));
        storage.upload(intent.mediaId(), a.id, "image/jpeg", 64);
        String key = storage.keyFor(intent.mediaId());

        deleteAccount(a);

        assertThat(count("users", "id", a.id)).isZero();
        assertThat(count("media_assets", "owner_user_id", a.id)).isZero();
        assertThat(storage.objects.containsKey(key)).isFalse();
        assertJobAwaitingFinal(key);

        storage.objects.put(key, new ObjectStorageService.StoredObject(64, "image/jpeg", "late"));
        assertThat(storage.objects.containsKey(key)).isTrue();

        cleanup.processDueJobs();
        assertThat(storage.objects.containsKey(key)).isTrue();
        assertJobAwaitingFinal(key);

        completeFinalCleanup();

        assertThat(storage.objects.containsKey(key)).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isZero();
    }

    @Test
    void cleanupJobSurvivesRestartUntilFinalVerify() throws Exception {
        Session a = register("restart");
        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 40L, 4, 4));
        storage.upload(intent.mediaId(), a.id, "image/jpeg", 40);
        String key = storage.keyFor(intent.mediaId());

        deleteAccount(a);
        assertJobAwaitingFinal(key);
        UUID deletionId = jdbc.queryForObject(
                "select deletion_id from account_deletion_media_jobs where storage_key = ?",
                UUID.class, key);

        cleanup.processDueJobs();
        assertThat(jdbc.queryForObject(
                "select deletion_id from account_deletion_media_jobs where storage_key = ?",
                UUID.class, key)).isEqualTo(deletionId);
        assertJobAwaitingFinal(key);

        completeFinalCleanup();
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isZero();
        assertThat(storage.objects.containsKey(key)).isFalse();
    }

    @Test
    void mediaAccessAfterDeletionDoesNotIssueSignedUrl() throws Exception {
        Session a = register("accessA");
        Session b = register("accessB");
        UUID place = insertPlace("Access Place", PlaceCategory.RESTAURANT, 28.4, 38.4);
        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 70L, 6, 6));
        storage.upload(intent.mediaId(), a.id, "image/jpeg", 70);
        media.confirm(a.id, intent.mediaId());
        visits.create(a.id, visitWithMedia(place, LocalDate.of(2026, 7, 1), 8.0,
                Visibility.PUBLIC, List.of(intent.mediaId())));

        mockMvc.perform(get("/api/v1/media/{mediaId}/access", intent.mediaId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString());

        deleteAccount(a);

        mockMvc.perform(get("/api/v1/media/{mediaId}/access", intent.mediaId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/media/{mediaId}/access", intent.mediaId())
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/media/{mediaId}/access", intent.mediaId())
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + a.access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + a.refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void concurrentVisitCreateDoesNotSurviveDeletedUser() throws Exception {
        Session a = register("raceA");
        UUID place = insertPlace("Race Place", PlaceCategory.RESTAURANT, 28.3, 38.3);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> deletion = executor.submit(() -> {
            start.await();
            deleteAccount(a);
            return null;
        });
        AtomicInteger visitOutcome = new AtomicInteger();
        Future<?> creation = executor.submit(() -> {
            start.await();
            try {
                visits.create(a.id, visit(place, LocalDate.of(2026, 6, 1), 8.0, Visibility.PRIVATE,
                        "race review", "race mem"));
                visitOutcome.set(1);
            } catch (RuntimeException ex) {
                visitOutcome.set(-1);
            }
            return null;
        });
        start.countDown();
        deletion.get(20, TimeUnit.SECONDS);
        creation.get(20, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(count("users", "id", a.id)).isZero();
        assertThat(count("visits", "user_id", a.id)).isZero();
        assertThat(visitOutcome.get()).isNotZero();
    }

    @Test
    void concurrentSavedPlaceDoesNotSurviveDeletedUser() throws Exception {
        Session a = register("savedRace");
        UUID place = insertPlace("Saved Race Place", PlaceCategory.CAFE, 28.5, 38.5);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> deletion = executor.submit(() -> {
            start.await();
            deleteAccount(a);
            return null;
        });
        AtomicInteger saveOutcome = new AtomicInteger();
        Future<?> save = executor.submit(() -> {
            start.await();
            try {
                savedPlaces.save(a.id, place);
                saveOutcome.set(1);
            } catch (RuntimeException ex) {
                saveOutcome.set(-1);
            }
            return null;
        });
        start.countDown();
        deletion.get(20, TimeUnit.SECONDS);
        save.get(20, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(count("users", "id", a.id)).isZero();
        assertThat(count("saved_places", "user_id", a.id)).isZero();
        assertThat(saveOutcome.get()).isNotZero();
    }

    @Test
    void concurrentMediaConfirmDoesNotSurviveDeletedUser() throws Exception {
        Session a = register("confirmRace");
        UUID clientMedia = UUID.randomUUID();
        var intent = media.createUploadIntent(a.id,
                new MediaUploadIntentRequest(clientMedia, "image/jpeg", 55L, 5, 5));
        storage.upload(intent.mediaId(), a.id, "image/jpeg", 55);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> deletion = executor.submit(() -> {
            start.await();
            deleteAccount(a);
            return null;
        });
        AtomicInteger confirmOutcome = new AtomicInteger();
        Future<?> confirmation = executor.submit(() -> {
            start.await();
            try {
                media.confirm(a.id, intent.mediaId());
                confirmOutcome.set(1);
            } catch (RuntimeException ex) {
                confirmOutcome.set(-1);
            }
            return null;
        });
        start.countDown();
        deletion.get(20, TimeUnit.SECONDS);
        confirmation.get(20, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(count("users", "id", a.id)).isZero();
        assertThat(count("media_assets", "owner_user_id", a.id)).isZero();
        assertThat(confirmOutcome.get()).isNotZero();
    }

    private void completeFinalCleanup() {
        clock.advance(mediaProperties.uploadTtl()
                .plus(mediaProperties.deletionVerifyGrace())
                .plusSeconds(1));
        cleanup.processDueJobs();
    }

    private void assertJobAwaitingFinal(String key) {
        assertThat(jdbc.queryForObject(
                "select last_error_category from account_deletion_media_jobs where storage_key = ?",
                String.class, key)).isEqualTo(AccountDeletionMediaJob.AWAITING_FINAL);
        assertThat(jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    private void deleteAccount(Session session) throws Exception {
        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + session.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
                .andExpect(status().isNoContent());
    }

    private void follow(Session follower, Session followed) throws Exception {
        mockMvc.perform(post("/api/v1/users/{id}/follow", followed.id)
                        .header("Authorization", "Bearer " + follower.access))
                .andExpect(status().isNoContent());
    }

    private Session register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = prefix + "_" + suffix;
        String email = username + "@example.com";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","displayName":"%s",
                                "password":"SecurePass1"}
                                """.formatted(email, username, prefix)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode session = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(session.get("user").get("id").asText());
        PolicyAcceptanceSupport.acceptCurrent(jdbc, id);
        return new Session(
                id,
                email,
                username,
                session.get("accessToken").asText(),
                session.get("refreshToken").asText());
    }

    private UUID insertPlace(String name, PlaceCategory category, double longitude,
                             double latitude) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'account deletion place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, id, name, category.name(), longitude, latitude);
        return id;
    }

    private CreateVisitRequest visit(UUID placeId, LocalDate visitedAt, double rating,
                                     Visibility visibility, String publicReview,
                                     String privateMemory) {
        return visitWithMedia(placeId, visitedAt, rating, visibility, publicReview, privateMemory,
                List.of());
    }

    private CreateVisitRequest visitWithMedia(UUID placeId, LocalDate visitedAt, double rating,
                                              Visibility visibility, List<UUID> mediaIds) {
        return visitWithMedia(placeId, visitedAt, rating, visibility, "media review", "media mem",
                mediaIds);
    }

    private CreateVisitRequest visitWithMedia(UUID placeId, LocalDate visitedAt, double rating,
                                              Visibility visibility, String publicReview,
                                              String privateMemory, List<UUID> mediaIds) {
        return new CreateVisitRequest(UUID.randomUUID(), placeId, visitedAt, rating,
                List.of(
                        new CreateVisitRequest.DimensionScore("FOOD", rating),
                        new CreateVisitRequest.DimensionScore("SERVICE", rating),
                        new CreateVisitRequest.DimensionScore("ATMOSPHERE", rating),
                        new CreateVisitRequest.DimensionScore("VALUE", rating),
                        new CreateVisitRequest.DimensionScore("PRESENTATION", rating)),
                publicReview, privateMemory, List.of(), mediaIds, visibility);
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, id);
    }

    private record Session(UUID id, String email, String username, String access, String refresh) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfig {
        @Bean
        @Primary
        TestObjectStorage testObjectStorage() {
            return new TestObjectStorage();
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static final class TestObjectStorage implements ObjectStorageService {
        final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        final Map<UUID, String> keysByMediaId = new ConcurrentHashMap<>();
        volatile String failDeleteKey;

        void upload(UUID mediaId, UUID owner, String contentType, long byteSize) {
            String key = keysByMediaId.getOrDefault(mediaId,
                    "users/" + owner + "/media/" + mediaId + "/original");
            keysByMediaId.put(mediaId, key);
            objects.put(key, new StoredObject(byteSize, contentType, "etag"));
        }

        String keyFor(UUID mediaId) {
            return keysByMediaId.get(mediaId);
        }

        void reset() {
            objects.clear();
            keysByMediaId.clear();
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
                throw new ObjectStorageException("delete failed", null);
            }
            objects.remove(key);
        }
    }
}

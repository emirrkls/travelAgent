package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.service.AccountDeletionMediaCleanupService;
import com.emirrkls.phokarta.backend.storage.ObjectStorageService;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MinIO + PostGIS account-deletion object cleanup. Does not use a fake
 * {@link ObjectStorageService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class AccountDeletionMinioIntegrationTest {
    private static final String ACCESS_KEY = "phokarta-minio";
    private static final String SECRET_KEY = "phokarta-minio-dev-only";
    private static final String BUCKET = "phokarta-account-deletion-e2e";
    private static final byte[] JPEG = jpegPayload();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200));

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("phokarta.media.enabled", () -> "true");
        registry.add("phokarta.media.bucket", () -> BUCKET);
        registry.add("phokarta.media.region", () -> "us-east-1");
        registry.add("phokarta.media.endpoint", AccountDeletionMinioIntegrationTest::minioEndpoint);
        registry.add("phokarta.media.path-style", () -> "true");
        registry.add("phokarta.media.access-key", () -> ACCESS_KEY);
        registry.add("phokarta.media.secret-key", () -> SECRET_KEY);
        registry.add("phokarta.media.upload-ttl", () -> "8s");
        registry.add("phokarta.media.deletion-verify-grace", () -> "2s");
        registry.add("phokarta.media.cleanup-interval", () -> "24h");
        registry.add("phokarta.media.cleanup-batch-size", () -> "100");
        ensureBucket();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountDeletionMediaCleanupService cleanup;
    @Autowired private ObjectStorageService storage;

    @BeforeAll
    static void createBucket() {
        ensureBucket();
    }

    private static void ensureBucket() {
        try (S3Client client = s3Client()) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
            } catch (NoSuchBucketException ex) {
                client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            }
        }
    }

    @Test
    void attachedObjectIsRemovedAfterAccountDeletion() throws Exception {
        Session user = register("minioDel");
        UUID place = insertPlace("Minio Delete Place");
        Upload upload = createUploadedReadyMedia(user);
        attachPublicVisit(user, place, upload.mediaId);

        assertThat(storage.head(upload.storageKey)).isNotNull();

        mockMvc.perform(get("/api/v1/media/{mediaId}/access", upload.mediaId)
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString());

        deleteAccount(user);

        assertThat(count("users", "id", user.id)).isZero();
        assertThat(count("media_assets", "owner_user_id", user.id)).isZero();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user.access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + user.refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(get("/api/v1/media/{mediaId}/access", upload.mediaId))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("""
                select last_error_category from account_deletion_media_jobs
                where storage_key = ?
                """, String.class, upload.storageKey)).isEqualTo("awaiting_final");
        assertThat(storage.head(upload.storageKey)).isNull();

        waitUntilFinalCleanup(upload.storageKey);

        assertThat(storage.head(upload.storageKey)).isNull();
        assertThat(jobCount(upload.storageKey)).isZero();
    }

    @Test
    void latePresignedPutIsRemovedByFinalCleanup() throws Exception {
        Session user = register("minioLate");
        Upload upload = createUploadedReadyMedia(user);
        assertThat(storage.head(upload.storageKey)).isNotNull();

        deleteAccount(user);
        assertThat(storage.head(upload.storageKey)).isNull();
        assertThat(jdbc.queryForObject("""
                select last_error_category from account_deletion_media_jobs
                where storage_key = ?
                """, String.class, upload.storageKey)).isEqualTo("awaiting_final");

        int lateStatus = putObject(upload.uploadUrl, upload.requiredHeaders);
        assertThat(lateStatus).isBetween(200, 299);
        assertThat(storage.head(upload.storageKey)).isNotNull();

        waitUntilFinalCleanup(upload.storageKey);

        assertThat(storage.head(upload.storageKey)).isNull();
        assertThat(jobCount(upload.storageKey)).isZero();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + user.access))
                .andExpect(status().isUnauthorized());
    }

    private Upload createUploadedReadyMedia(Session user) throws Exception {
        UUID clientMedia = UUID.randomUUID();
        MvcResult intentResult = mockMvc.perform(post("/api/v1/me/media/upload-intents")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMediaId":"%s","contentType":"image/jpeg","byteSize":%d,
                                "width":1,"height":1}
                                """.formatted(clientMedia, JPEG.length)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode intent = objectMapper.readTree(intentResult.getResponse().getContentAsString());
        UUID mediaId = UUID.fromString(intent.get("mediaId").asText());
        URI uploadUrl = URI.create(intent.get("uploadUrl").asText());
        var headers = new java.util.LinkedHashMap<String, String>();
        intent.get("requiredHeaders").fields()
                .forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        String storageKey = jdbc.queryForObject(
                "select storage_key from media_assets where id = ?", String.class, mediaId);

        int status = putObject(uploadUrl, headers);
        assertThat(status).isBetween(200, 299);

        mockMvc.perform(post("/api/v1/me/media/{mediaId}/confirm", mediaId)
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        return new Upload(mediaId, storageKey, uploadUrl, headers);
    }

    private void attachPublicVisit(Session user, UUID place, UUID mediaId) throws Exception {
        mockMvc.perform(post("/api/v1/visits")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMutationId":"%s","placeId":"%s","visitedAt":"2026-08-01",
                                "overallRating":8.0,
                                "dimensions":[{"key":"FOOD","score":8.0},{"key":"SERVICE","score":8.0},
                                {"key":"ATMOSPHERE","score":8.0},{"key":"VALUE","score":8.0},
                                {"key":"PRESENTATION","score":8.0}],
                                "publicReview":"minio review","privateMemory":"minio mem",
                                "photos":[],"mediaIds":["%s"],"visibility":"PUBLIC"}
                                """.formatted(UUID.randomUUID(), place, mediaId)))
                .andExpect(status().isCreated());
    }

    private void waitUntilFinalCleanup(String storageKey) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(25);
        while (Instant.now().isBefore(deadline)) {
            if (jobCount(storageKey) == 0) {
                return;
            }
            List<OffsetDateTime> due = jdbc.query("""
                    select next_attempt_at from account_deletion_media_jobs where storage_key = ?
                    """, (rs, row) -> rs.getObject(1, OffsetDateTime.class), storageKey);
            if (due.isEmpty()) {
                return;
            }
            if (!Instant.now().isBefore(due.getFirst().toInstant())) {
                cleanup.processDueJobs();
            }
            Thread.sleep(200);
        }
        cleanup.processDueJobs();
        assertThat(jobCount(storageKey)).isZero();
    }

    private int putObject(URI url, java.util.Map<String, String> requiredHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(15))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(JPEG));
        requiredHeaders.forEach((name, value) -> {
            if (!"content-length".equalsIgnoreCase(name) && !"host".equalsIgnoreCase(name)) {
                builder.header(name, value);
            }
        });
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private void deleteAccount(Session session) throws Exception {
        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + session.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
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
                session.get("accessToken").asText(),
                session.get("refreshToken").asText());
    }

    private UUID insertPlace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'minio deletion place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(28.6, 38.6), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, id, name, PlaceCategory.RESTAURANT.name());
        return id;
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, id);
    }

    private int jobCount(String storageKey) {
        Integer count = jdbc.queryForObject(
                "select count(*) from account_deletion_media_jobs where storage_key = ?",
                Integer.class, storageKey);
        return count == null ? 0 : count;
    }

    private static String minioEndpoint() {
        return "http://127.0.0.1:" + MINIO.getMappedPort(9000);
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(minioEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    private static byte[] jpegPayload() {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[bytes.length - 2] = (byte) 0xFF;
        bytes[bytes.length - 1] = (byte) 0xD9;
        return bytes;
    }

    private record Session(UUID id, String access, String refresh) {
    }

    private record Upload(UUID mediaId, String storageKey, URI uploadUrl,
                          java.util.Map<String, String> requiredHeaders) {
    }
}

package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import com.emirrkls.phokarta.backend.service.PlacePilotImportService;
import com.emirrkls.phokarta.backend.service.PlaceProviderStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaUpgradePlaceFoundationMigrationTest {
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000517");
    private static final UUID SECOND_PLACE = UUID.fromString("20000000-0000-0000-0000-000000000518");
    private static final UUID GRAPH_PLACE = UUID.fromString("20000000-0000-0000-0000-000000000519");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111517");

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void v17PreservesExistingPlacesAndSupportsSafeIdempotentPilotImport() {
        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("16")
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbc();
        insertLegacyPlace(jdbc, UUID.fromString("20000000-0000-0000-0000-000000000516"),
                "Pre-V17 Place");

        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load()
                .migrate();

        assertThat(jdbc.queryForObject(
                "select origin from places where name = 'Pre-V17 Place'", String.class))
                .isEqualTo("MANUAL_COMMUNITY");
        assertThat(jdbc.queryForObject(
                "select catalog_status from places where name = 'Pre-V17 Place'", String.class))
                .isEqualTo("ACTIVE");
        assertThat(count(jdbc, "select count(*) from information_schema.tables where table_name like 'place_%'"))
                .isGreaterThanOrEqualTo(5);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        PlacePilotImportService importer = new PlacePilotImportService(jdbc, mapper, transactions);
        ObjectNode envelope = approvedManifest(mapper, importer,
                UUID.fromString("60000000-0000-0000-0000-000000000517"), PLACE, false);

        PlacePilotImportService.ImportResult first = importer.importApproved(envelope);
        PlacePilotImportService.ImportResult retry = importer.importApproved(envelope);

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(first.sourceCount()).isEqualTo(3);
        assertThat(first.createdCount()).isEqualTo(1);
        assertThat(first.reviewCount()).isEqualTo(1);
        assertThat(retry.alreadyImported()).isTrue();
        assertThat(count(jdbc, "select count(*) from places where id = ?", PLACE)).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from place_source_records")).isEqualTo(3);
        assertThat(count(jdbc, "select count(*) from place_external_refs where place_id = ?", PLACE))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select name from places where id = ?", String.class, PLACE))
                .isEqualTo("Human Corrected Place");
        assertThat(jdbc.queryForObject("select price_level from places where id = ?", Integer.class, PLACE))
                .isZero();
        assertThat(count(jdbc, "select count(*) from place_canonical_overrides where place_id = ?", PLACE))
                .isEqualTo(1);

        // A process failure after this candidate committed must not supersede its own
        // human override when the exact approved manifest is resumed.
        jdbc.update("""
                update place_provider_sync_runs
                   set status = 'FAILED', failure_reason = 'simulated process interruption'
                 where id = ?
                """, first.runId());
        PlacePilotImportService.ImportResult resumed = importer.importApproved(envelope);
        assertThat(resumed.status()).isEqualTo("SUCCEEDED");
        assertThat(resumed.alreadyImported()).isFalse();
        assertThat(count(jdbc, """
                select count(*) from place_canonical_overrides
                 where place_id = ? and field_name = 'name' and superseded_at is null
                """, PLACE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from places where id = ?", String.class, PLACE))
                .isEqualTo("Human Corrected Place");

        assertThatThrownBy(() -> jdbc.update(
                "update place_source_records set normalized_name = 'changed' where provider = 'OVERTURE'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        ObjectNode failing = approvedManifest(mapper, importer,
                UUID.fromString("60000000-0000-0000-0000-000000000518"), SECOND_PLACE, true);
        assertThatThrownBy(() -> importer.importApproved(failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another canonical Place");
        assertThat(count(jdbc, "select count(*) from places where id = ?", SECOND_PLACE)).isZero();
        assertThat(count(jdbc, "select count(*) from place_external_refs where external_id = 'o-rollback'"))
                .isZero();

        PlaceGraphProtectionRepository graph = new PlaceGraphProtectionRepository(jdbc);
        PlaceProviderStateService providerState = new PlaceProviderStateService(jdbc, graph);
        UUID successfulRun = first.runId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        providerState.markMerged("OVERTURE", "o-2", "o-1", successfulRun, now);
        assertThat(jdbc.queryForObject("""
                select status from place_external_refs
                 where provider = 'OVERTURE' and external_id = 'o-2'
                """, String.class)).isEqualTo("MERGED");
        providerState.markRemoved("OVERTURE", "o-1", successfulRun, now.plusSeconds(1));
        PlaceProviderStateService.RemovalResult retired = providerState.markRemoved(
                "FSQ", "f-1", successfulRun, now.plusSeconds(2));
        assertThat(retired.retired()).isTrue();
        assertThat(jdbc.queryForObject("select catalog_status from places where id = ?",
                String.class, PLACE)).isEqualTo("RETIRED");

        insertGraphProtectedExternalPlace(jdbc, successfulRun, now);
        PlaceProviderStateService.RemovalResult preserved = providerState.markRemoved(
                "OVERTURE", "o-graph", successfulRun, now.plusSeconds(3));
        assertThat(preserved.graphProtected()).isTrue();
        assertThat(preserved.retired()).isFalse();
        assertThat(jdbc.queryForObject("select catalog_status from places where id = ?",
                String.class, GRAPH_PLACE)).isEqualTo("ACTIVE");
    }

    private ObjectNode approvedManifest(
            ObjectMapper mapper,
            PlacePilotImportService importer,
            UUID runId,
            UUID placeId,
            boolean conflictWithExistingRef
    ) {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("run_id", runId.toString());
        manifest.put("status", "APPROVED");
        manifest.put("method_version", "didim-canonicalization-v1");
        ObjectNode scope = manifest.putObject("scope");
        scope.put("name", "didim_core");
        scope.put("center_latitude", 37.3751);
        scope.put("center_longitude", 27.2678);
        scope.put("radius_meters", 6000);
        ObjectNode providers = manifest.putObject("providers");
        providers.putObject("overture").put("release", "2026-09-23.0");
        providers.putObject("fsq")
                .put("release", "2026-09-15 20:07:45.157000")
                .put("snapshot_id", "2325979374271449319");

        ArrayNode sources = manifest.putArray("source_records");
        if (conflictWithExistingRef) {
            source(sources, "70000000-0000-0000-0000-000000000521", "overture",
                    "o-rollback", "b".repeat(64));
            source(sources, "70000000-0000-0000-0000-000000000522", "fsq",
                    "f-1", "e".repeat(64));
        } else {
            source(sources, "70000000-0000-0000-0000-000000000517", "overture",
                    "o-1", "a".repeat(64));
            source(sources, "70000000-0000-0000-0000-000000000518", "overture",
                    "o-2", "b".repeat(64));
            source(sources, "70000000-0000-0000-0000-000000000519", "fsq",
                    "f-1", "c".repeat(64));
        }

        ArrayNode candidates = manifest.putArray("candidates");
        ObjectNode candidate = candidates.addObject();
        candidate.put("candidate_id", conflictWithExistingRef ? "failure" : "create");
        candidate.put("decision", "CREATE_NEW");
        candidate.put("approved", true);
        candidate.put("canonical_place_id", placeId.toString());
        ObjectNode canonical = candidate.putObject("canonical");
        canonical.put("name", "Provider Place");
        canonical.put("category", "CAFE");
        canonical.put("latitude", 37.3751);
        canonical.put("longitude", 27.2678);
        canonical.put("city", "Didim");
        canonical.put("region", "Aydın");
        canonical.put("country", "Türkiye");
        canonical.put("address", "Atatürk Bulvarı 1");
        ArrayNode sourceIds = candidate.putArray("source_record_ids");
        if (conflictWithExistingRef) {
            sourceIds.add("70000000-0000-0000-0000-000000000521");
            sourceIds.add("70000000-0000-0000-0000-000000000522");
        } else {
            sourceIds.add("70000000-0000-0000-0000-000000000517");
            sourceIds.add("70000000-0000-0000-0000-000000000518");
            sourceIds.add("70000000-0000-0000-0000-000000000519");
            ObjectNode override = candidate.putArray("overrides").addObject();
            override.put("override_id", "80000000-0000-0000-0000-000000000517");
            override.put("field", "name");
            override.put("value", "Human Corrected Place");
            override.put("actor_type", "HUMAN_REVIEW");
            override.put("actor_reference", "DIDIM-001");
            override.put("reason", "Physical review correction");
        }
        if (!conflictWithExistingRef) {
            ObjectNode review = candidates.addObject();
            review.put("candidate_id", "unresolved");
            review.put("decision", "REVIEW_REQUIRED");
            review.put("approved", false);
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("manifest", manifest);
        envelope.put("manifest_hash", importer.hashManifest(manifest));
        return envelope;
    }

    private void source(
            ArrayNode sources,
            String sourceId,
            String provider,
            String externalId,
            String hash
    ) {
        ObjectNode source = sources.addObject();
        source.put("source_record_id", sourceId);
        source.put("provider", provider);
        source.put("external_id", externalId);
        source.put("source_release", provider.equals("fsq")
                ? "2026-09-15 20:07:45.157000" : "2026-09-23.0");
        source.put("snapshot_id", provider.equals("fsq") ? "2325979374271449319" : "");
        source.put("normalized_name", "Provider Place");
        source.put("latitude", 37.3751);
        source.put("longitude", 27.2678);
        source.put("address", "Atatürk Bulvarı 1");
        source.put("locality", "Didim");
        source.put("region", "Aydın");
        source.put("country_code", "TR");
        source.putArray("provider_categories").add("cafe");
        source.put("proposed_place_category", "CAFE");
        source.put("source_hash", hash);
        source.put("license_identifier", provider.equals("fsq") ? "Apache-2.0" : "source-dependent");
        source.putObject("provenance").put("fixture", true);
        source.put("observed_at", "2026-09-25T12:00:00Z");
        source.put("retrieved_at", "2026-09-25T12:01:00Z");
        source.put("usable", true);
    }

    private void insertGraphProtectedExternalPlace(
            JdbcTemplate jdbc,
            UUID runId,
            OffsetDateTime now
    ) {
        insertPlace(jdbc, GRAPH_PLACE, "Graph Protected", "EXTERNAL_IMPORT");
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, created_at, updated_at
                ) values (?, 'graph@example.test', 'graph_user', 'Graph User', true,
                    0, 0, 0, 0, '{}', now(), now())
                """, USER);
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 8, '', '', '{}', 'PUBLIC', 'UNVERIFIED', now(), now())
                """, UUID.randomUUID(), USER, GRAPH_PLACE);
        UUID sourceId = UUID.fromString("70000000-0000-0000-0000-000000000523");
        jdbc.update("""
                insert into place_source_records (
                    id, sync_run_id, provider, external_id, source_release, method_version,
                    normalized_name, location, provider_categories, source_hash,
                    license_identifier, provenance, observed_at, retrieved_at
                ) values (?, ?, 'OVERTURE', 'o-graph', 'fixture', 'didim-canonicalization-v1',
                    'Graph Protected', ST_SetSRID(ST_MakePoint(27.2678, 37.3751), 4326),
                    '[]', ?, 'source-dependent', '{}', ?, ?)
                """, sourceId, runId, "d".repeat(64), now, now);
        jdbc.update("""
                insert into place_external_refs (
                    provider, external_id, place_id, current_source_record_id, source_release,
                    first_seen_at, last_seen_at, status, source_hash, last_sync_run_id
                ) values ('OVERTURE', 'o-graph', ?, ?, 'fixture', ?, ?, 'ACTIVE', ?, ?)
                """, GRAPH_PLACE, sourceId, now, now, "d".repeat(64), runId);
    }

    private void insertPlace(JdbcTemplate jdbc, UUID id, String name, String origin) {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, origin,
                    created_at, updated_at
                ) values (?, ?, '', 'CAFE', '{}', ST_SetSRID(ST_MakePoint(27.2, 37.3), 4326),
                    'Didim', 'Aydın', 'Türkiye', '', '', '{}', 1, ?, now(), now())
                """, id, name, origin);
    }

    private void insertLegacyPlace(JdbcTemplate jdbc, UUID id, String name) {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, ?, '', 'CAFE', '{}', ST_SetSRID(ST_MakePoint(27.2, 37.3), 4326),
                    'Didim', 'Aydın', 'Türkiye', '', '', '{}', 1, now(), now())
                """, id, name);
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword());
        return new JdbcTemplate(source);
    }

    private int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

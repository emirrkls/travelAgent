package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import com.emirrkls.phokarta.backend.operations.PlacePilotRollbackApplication;
import com.emirrkls.phokarta.backend.service.PlacePilotCanaryGateService;
import com.emirrkls.phokarta.backend.service.PlacePilotGateReconciliationService;
import com.emirrkls.phokarta.backend.service.PlacePilotImportService;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService.OperationalFailure;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService.Reason;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class PlacePilotRollbackOperationsIntegrationTest {
    private static final UUID SOURCE_UUID_NAMESPACE =
            UUID.fromString("c2d15a95-29f7-5c35-b852-d43aff1f3c81");
    private static final UUID PLACE_UUID_NAMESPACE =
            UUID.fromString("238147d6-fc89-5af3-8b78-559044fef2ad");
    private static final String SOURCE_METHOD_VERSION = "didim-canonicalization-v3";
    private static final String DECISION_METHOD_VERSION = "didim-autonomous-validation-v2";

    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    private static ObjectMapper mapper;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static PlacePilotImportService importer;
    private static PlacePilotCanaryGateService gates;
    private static PlacePilotRollbackOperationsService operations;

    @BeforeAll
    static void migrateAndCreateServices() {
        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load()
                .migrate();
        mapper = new ObjectMapper().findAndRegisterModules();
        jdbc = jdbc(POSTGIS.getJdbcUrl());
        transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        PlacePilotRollbackService rollback = new PlacePilotRollbackService(
                jdbc, new PlaceGraphProtectionRepository(jdbc));
        importer = new PlacePilotImportService(jdbc, mapper, transactions, rollback);
        gates = new PlacePilotCanaryGateService(jdbc, rollback, transactions);
        operations = new PlacePilotRollbackOperationsService(
                jdbc, transactions, rollback, mapper);
    }

    @Test
    void passedPilotCanBeInspectedThenContainedWithoutRewritingPassOrUserGraph() {
        UUID manualPlace = deterministicUuid("rollback-manual-place");
        insertPlace(manualPlace, "Pre-existing linked Place", "MANUAL_COMMUNITY");
        PilotFixture target = importPilotWithLinkedExistingPlace(
                "rollback-main", 6, true, manualPlace);
        PilotFixture other = importPilot("rollback-other", 1, false, true);
        GraphFixture graph = addUserGraph(target);

        UUID aliasProtectedPlace = target.placeIds().get(5);
        addLaterAlias(other.runId(), aliasProtectedPlace, "newer-alias");

        DatabaseSnapshot beforeDryRun = snapshot(target);
        var inspection = operations.inspect(target.runId(), target.manifestHash());

        assertThat(inspection.result()).isEqualTo("INSPECTED");
        assertThat(inspection.dryRun()).isTrue();
        assertThat(inspection.alreadyContained()).isFalse();
        assertThat(inspection.rollbackResult()).isNull();
        assertThat(inspection.inspection().createdPlaceCount()).isEqualTo(6);
        assertThat(inspection.inspection().sourceObservationCount()).isEqualTo(14);
        assertThat(inspection.inspection().affectedSourceObservationCount()).isEqualTo(12);
        assertThat(inspection.inspection().externalReferenceCount()).isEqualTo(13);
        assertThat(inspection.inspection().referencesToContainCount()).isEqualTo(12);
        assertThat(inspection.inspection().graphProtectedCount()).isEqualTo(4);
        assertThat(inspection.inspection().eligibleRetirementCount()).isEqualTo(1);
        assertThat(inspection.inspection().retainedGraphSafeCount()).isEqualTo(4);
        assertThat(inspection.inspection().containedByNewerReferencesCount()).isEqualTo(1);
        assertThat(inspection.inspection().pendingWriteCount()).isEqualTo(6);
        assertThat(inspection.inspection().affectedRunIds()).containsExactly(target.runId());
        assertThat(inspection.inspection().ownershipProvenanceValid()).isTrue();
        assertThat(snapshot(target)).isEqualTo(beforeDryRun);

        var executed = operations.execute(
                target.runId(), target.manifestHash(), Reason.PRODUCT_ACCEPTANCE_FAILURE);

        assertThat(executed.result()).isEqualTo("CONTAINED");
        assertThat(executed.dryRun()).isFalse();
        assertThat(executed.alreadyContained()).isFalse();
        assertThat(executed.rollbackResult().retiredCount()).isEqualTo(5);
        assertThat(executed.rollbackResult().graphProtectedCount()).isEqualTo(4);
        assertThat(executed.rollbackResult().containedByNewerReferencesCount()).isEqualTo(1);
        assertThat(executed.inspection().pendingWriteCount()).isZero();

        assertPlaceState(target.placeIds().get(0), "RETIRED", "RETIRED");
        for (int index = 1; index <= 4; index++) {
            assertPlaceState(target.placeIds().get(index), "RETIRED", "RETIRED_GRAPH_PROTECTED");
        }
        assertPlaceState(aliasProtectedPlace, "ACTIVE", "CONTAINED_NEWER_REFERENCES");
        assertThat(count("select count(*) from places where id = ?", manualPlace)).isOne();
        assertThat(jdbc.queryForObject(
                "select catalog_status from places where id = ?", String.class, manualPlace))
                .isEqualTo("ACTIVE");
        assertThat(count("""
                select count(*) from place_external_refs
                 where place_id = ? and last_sync_run_id = ? and status = 'ACTIVE'
                """, manualPlace, target.runId())).isOne();

        assertUserGraphPreserved(graph);
        assertThat(count("""
                select count(*) from place_source_records where sync_run_id = ?
                """, target.runId())).isEqualTo(14);
        assertThat(count("""
                select count(*) from place_external_refs ref
                join place_source_records source on source.id = ref.current_source_record_id
                 where source.sync_run_id = ? and ref.status = 'INACTIVE'
                """, target.runId())).isEqualTo(12);
        assertThat(count("""
                select count(*) from place_external_ref_events
                 where sync_run_id = ? and event_type = 'PILOT_ROLLBACK'
                """, target.runId())).isEqualTo(12);

        assertThat(jdbc.queryForObject("""
                select gate_status from place_pilot_canary_gates where sync_run_id = ?
                """, String.class, target.runId())).isEqualTo("PASSED");
        assertThat(count("""
                select count(*) from place_pilot_operational_events where sync_run_id = ?
                """, target.runId())).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                select event_type, details ->> 'reason_code' as reason
                  from place_pilot_operational_events where sync_run_id = ?
                 order by case event_type
                    when 'PILOT_CONTAINMENT_REQUESTED' then 1
                    when 'PILOT_ROLLBACK_STARTED' then 2 else 3 end
                """, target.runId()))
                .extracting(row -> row.get("event_type"))
                .containsExactly("PILOT_CONTAINMENT_REQUESTED", "PILOT_ROLLBACK_STARTED",
                        "PILOT_ROLLBACK_COMPLETED");
        assertThat(jdbc.queryForList("""
                select details ->> 'reason_code' as reason
                  from place_pilot_operational_events where sync_run_id = ?
                """, target.runId()))
                .allSatisfy(row -> assertThat(row.get("reason"))
                        .isEqualTo("PRODUCT_ACCEPTANCE_FAILURE"));

        assertThat(count("""
                select count(*) from place_validation_decisions
                 where sync_run_id = ? and decision_state = 'QUARANTINE'
                """, target.runId())).isOne();
        assertThat(count("""
                select count(*) from place_pilot_catalog_writes where sync_run_id = ?
                """, target.runId())).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                select catalog_status from places where id = ?
                """, String.class, other.placeIds().getFirst())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                select rollback_state from place_pilot_catalog_writes where sync_run_id = ?
                """, String.class, other.runId())).isEqualTo("NONE");

        List<Map<String, Object>> eventSnapshot = jdbc.queryForList("""
                select id::text, event_type, occurred_at::text, details::text
                  from place_pilot_operational_events where sync_run_id = ? order by event_type
                """, target.runId());
        DatabaseSnapshot afterFirstExecution = snapshot(target);
        var repeated = operations.execute(
                target.runId(), target.manifestHash(), Reason.PRODUCT_ACCEPTANCE_FAILURE);
        assertThat(repeated.result()).isEqualTo("ALREADY_CONTAINED");
        assertThat(repeated.alreadyContained()).isTrue();
        assertThat(repeated.rollbackResult().alreadyRolledBack()).isTrue();
        assertThat(snapshot(target)).isEqualTo(afterFirstExecution);
        assertThat(jdbc.queryForList("""
                select id::text, event_type, occurred_at::text, details::text
                  from place_pilot_operational_events where sync_run_id = ? order by event_type
                """, target.runId())).isEqualTo(eventSnapshot);
    }

    @Test
    void refusesWrongRunAndManifestIdentityWithoutMutation() {
        PilotFixture identity = importPilot("rollback-rejections", 1, false, true);
        DatabaseSnapshot pristine = snapshot(identity);

        assertOperationalFailure(() -> operations.inspect(
                deterministicUuid("missing-pilot-run"), identity.manifestHash()), "RUN_NOT_FOUND");
        assertOperationalFailure(() -> operations.execute(
                identity.runId(), "0".repeat(64), Reason.INTEGRITY_FAILURE), "MANIFEST_MISMATCH");
        assertOperationalFailure(() -> operations.execute(
                identity.runId(), "not-a-hash", Reason.INTEGRITY_FAILURE), "INVALID_INPUT");
        assertThat(snapshot(identity)).isEqualTo(pristine);
    }

    @Test
    void refusesInconsistentOwnershipAndReferenceProvenanceBeforeAuditWrites() {
        PilotFixture badOwnership = importPilot("rollback-bad-ownership", 1, false, true);
        jdbc.update("update places set origin = 'MANUAL_COMMUNITY' where id = ?",
                badOwnership.placeIds().getFirst());
        DatabaseSnapshot ownershipBefore = snapshot(badOwnership);
        assertOperationalFailure(() -> operations.execute(
                badOwnership.runId(), badOwnership.manifestHash(), Reason.INTEGRITY_FAILURE),
                "OWNERSHIP_INVALID");
        assertThat(snapshot(badOwnership)).isEqualTo(ownershipBefore);
        assertThat(count("select count(*) from place_pilot_operational_events where sync_run_id = ?",
                badOwnership.runId())).isZero();

        PilotFixture badProvenance = importPilot("rollback-bad-provenance", 1, false, true);
        jdbc.execute("alter table place_external_refs disable trigger trg_place_external_ref_source_snapshot");
        try {
            jdbc.update("""
                    update place_external_refs set source_hash = ?
                     where last_sync_run_id = ? and provider = 'OVERTURE'
                    """, "f".repeat(64), badProvenance.runId());
        } finally {
            jdbc.execute("alter table place_external_refs enable trigger trg_place_external_ref_source_snapshot");
        }
        DatabaseSnapshot provenanceBefore = snapshot(badProvenance);
        assertOperationalFailure(() -> operations.execute(
                badProvenance.runId(), badProvenance.manifestHash(), Reason.INTEGRITY_FAILURE),
                "OWNERSHIP_INVALID");
        assertThat(snapshot(badProvenance)).isEqualTo(provenanceBefore);
        assertThat(count("select count(*) from place_pilot_operational_events where sync_run_id = ?",
                badProvenance.runId())).isZero();
    }

    @Test
    void refusesPreV17DatabaseBeforeInspection() {
        String preV17Database = "rollback_pre_v17";
        jdbc.execute("create database " + preV17Database);
        String preV17Url = databaseUrl(preV17Database);
        Flyway.configure()
                .dataSource(preV17Url, POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .target("16")
                .load()
                .migrate();
        JdbcTemplate oldJdbc = jdbc(preV17Url);
        DataSourceTransactionManager oldTransactions =
                new DataSourceTransactionManager(oldJdbc.getDataSource());
        PlacePilotRollbackService oldRollback = new PlacePilotRollbackService(
                oldJdbc, new PlaceGraphProtectionRepository(oldJdbc));
        PlacePilotRollbackOperationsService oldOperations =
                new PlacePilotRollbackOperationsService(
                        oldJdbc, oldTransactions, oldRollback, mapper);
        assertOperationalFailure(() -> oldOperations.inspect(
                deterministicUuid("pre-v17-run"), "1".repeat(64)), "SCHEMA_INCOMPATIBLE");
    }

    @Test
    void executionFailureRollsBackContainmentAndAllOperationalEventsAtomically() {
        PilotFixture target = importPilot("rollback-atomic-failure", 1, false, true);
        DatabaseSnapshot before = snapshot(target);
        jdbc.execute("""
                create function reject_test_containment_completion() returns trigger
                language plpgsql as $$
                begin
                    if new.event_type = 'PILOT_ROLLBACK_COMPLETED' then
                        raise exception 'test completion rejection';
                    end if;
                    return new;
                end
                $$
                """);
        jdbc.execute("""
                create trigger trg_test_reject_containment_completion
                before insert on place_pilot_operational_events
                for each row execute function reject_test_containment_completion()
                """);
        try {
            assertOperationalFailure(() -> operations.execute(
                    target.runId(), target.manifestHash(), Reason.INTEGRITY_FAILURE),
                    "OPERATION_FAILED");
        } finally {
            jdbc.execute("drop trigger trg_test_reject_containment_completion "
                    + "on place_pilot_operational_events");
            jdbc.execute("drop function reject_test_containment_completion()");
        }
        assertThat(snapshot(target)).isEqualTo(before);
    }

    @Test
    void isolatedRunnerDryRunLoadsNoWebOrReconcilerAndLeavesExpiredRunUntouched() {
        PilotFixture target = importPilot("rollback-runner-dry-run", 1, false, true);
        PilotFixture expiredUngated = seedExpiredUngatedRun("rollback-expired-ungated");
        DatabaseSnapshot targetBefore = snapshot(target);
        DatabaseSnapshot expiredBefore = snapshot(expiredUngated);
        List<Map<String, Object>> expiredRunBefore = jdbc.queryForList("""
                select status, started_at, completed_at, gate_deadline, manifest_hash, plan_digest
                  from place_provider_sync_runs where id = ?
                """, expiredUngated.runId());
        AtomicBoolean isolatedContextInspected = new AtomicBoolean();

        SpringApplication application = rollbackApplication();
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "rollbackIsolationProbe",
                new IsolationProbe(context, isolatedContextInspected)));
        ConfigurableApplicationContext closed = application.run(
                rollbackArguments(target, true));

        assertThat(isolatedContextInspected).isTrue();
        assertThat(closed.isActive()).isFalse();
        assertThat(snapshot(target)).isEqualTo(targetBefore);
        assertThat(snapshot(expiredUngated)).isEqualTo(expiredBefore);
        assertThat(jdbc.queryForList("""
                select status, started_at, completed_at, gate_deadline, manifest_hash, plan_digest
                  from place_provider_sync_runs where id = ?
                """, expiredUngated.runId())).isEqualTo(expiredRunBefore);
        assertThat(count("select count(*) from place_pilot_canary_gates where sync_run_id = ?",
                expiredUngated.runId())).isZero();
        assertThat(count("""
                select count(*) from place_pilot_catalog_writes where sync_run_id = ?
                """, expiredUngated.runId())).isZero();
    }

    @Test
    void isolatedRunnerExecutesPostPassAcceptanceContainmentAndPreservesHistoricalPass() {
        PilotFixture target = importPilot("rollback-runner-execute", 1, false, true);
        AtomicBoolean isolatedContextInspected = new AtomicBoolean();
        SpringApplication application = rollbackApplication();
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "rollbackExecutionIsolationProbe",
                new IsolationProbe(context, isolatedContextInspected)));

        ConfigurableApplicationContext closed = application.run(
                rollbackArguments(target, false));

        assertThat(isolatedContextInspected).isTrue();
        assertThat(closed.isActive()).isFalse();
        assertPlaceState(target.placeIds().getFirst(), "RETIRED", "RETIRED");
        assertThat(jdbc.queryForObject("""
                select gate_status from place_pilot_canary_gates where sync_run_id = ?
                """, String.class, target.runId())).isEqualTo("PASSED");
        assertThat(jdbc.queryForList("""
                select event_type from place_pilot_operational_events
                 where sync_run_id = ? order by occurred_at, event_type
                """, String.class, target.runId()))
                .containsExactlyInAnyOrder("PILOT_CONTAINMENT_REQUESTED",
                        "PILOT_ROLLBACK_STARTED", "PILOT_ROLLBACK_COMPLETED");
        assertThat(jdbc.queryForList("""
                select details ->> 'reason_code' from place_pilot_operational_events
                 where sync_run_id = ?
                """, String.class, target.runId()))
                .containsOnly("PRODUCT_ACCEPTANCE_FAILURE");
    }

    @Test
    void publicControllerClasspathHasNoRollbackOrContainmentEndpoint() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> exposedMappings = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(
                "com.emirrkls.phokarta.backend")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            addMappings(exposedMappings,
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (Method method : controller.getMethods()) {
                addMappings(exposedMappings,
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class));
            }
        }
        assertThat(exposedMappings)
                .noneMatch(path -> path.toLowerCase(Locale.ROOT).matches(
                        ".*(rollback|containment|place[-_/]?pilot).*"));
    }

    private static PilotFixture importPilot(
            String seed, int createdPlaces, boolean includeQuarantine, boolean passGate) {
        ObjectNode envelope = manifest(seed, createdPlaces, includeQuarantine);
        return importEnvelope(envelope, passGate);
    }

    private static PilotFixture seedExpiredUngatedRun(String seed) {
        // Seed a historical terminal run in its original state, just like the gate
        // reconciliation integration fixture. Never rewrite a completed run's deadline.
        UUID runId = deterministicUuid("expired-run:" + seed);
        String manifestHash = sha256(seed + ":manifest");
        String authorization = "test-expired-authorization-" + seed;
        jdbc.update("""
                insert into place_provider_sync_runs (
                    id, pilot_run_key, canary_stage, authorization_reference,
                    provider, resolved_release, method_version, scope_name,
                    scope_center_latitude, scope_center_longitude, scope_radius_meters,
                    started_at, completed_at, gate_deadline, status, manifest_hash, plan_digest
                ) values (?, ?, 'STAGE_1', ?, 'MULTI_SOURCE', 'fixture',
                    'didim-autonomous-validation-v2', 'didim_core', 37.3751, 27.2678, 6000,
                    clock_timestamp() - interval '2 hours',
                    clock_timestamp() - interval '90 minutes',
                    clock_timestamp() - interval '1 minute', 'SUCCEEDED', ?, ?)
                """, runId, "didim-core-" + seed, authorization,
                manifestHash, sha256(seed + ":plan"));
        assertThat(jdbc.queryForObject("""
                select gate_deadline < clock_timestamp() from place_provider_sync_runs where id = ?
                """, Boolean.class, runId)).isTrue();
        return new PilotFixture(runId, manifestHash, authorization, List.of());
    }

    private static PilotFixture importPilotWithLinkedExistingPlace(
            String seed, int createdPlaces, boolean includeQuarantine, UUID existingPlaceId) {
        ObjectNode envelope = manifest(seed, createdPlaces, includeQuarantine);
        ObjectNode payload = (ObjectNode) envelope.path("manifest");
        String externalId = seed + "-existing-link";
        String sourceHash = sha256(seed + ":existing-link");
        UUID sourceId = sourceUuid("FSQ", externalId, sourceHash);
        source((ArrayNode) payload.path("source_records"), sourceId, "fsq", externalId,
                sourceHash, "Pre-existing linked Place", 37.3751, 27.2678);
        ObjectNode link = ((ArrayNode) payload.path("candidates")).addObject();
        link.put("candidate_id", seed + "-existing-link");
        link.put("decision", "AUTO_LINK");
        link.put("decision_reason", "VERIFIED_EXISTING_CANONICAL_MATCH");
        link.put("existence_assessment", "HIGH");
        link.put("candidate_hash", "0".repeat(64));
        link.put("decided_at", "2026-09-26T12:00:00Z");
        link.put("canary_eligible", false);
        link.put("selected_for_stage", false);
        link.put("canonical_fields_valid", true);
        link.putObject("evidence").put("provider_agreement", "DIRECT_IDENTITY_MATCH");
        link.putArray("hard_blockers");
        link.putObject("field_proposals");
        link.put("canonical_place_id", existingPlaceId.toString());
        link.putArray("source_record_ids").add(sourceId.toString());
        refreshAccountingAndHashes(payload);
        envelope.put("manifest_hash", importer.hashManifest(payload));

        PilotFixture fixture = importEnvelope(envelope, false);
        assertThat(gates.record(fixture.runId(), true,
                passingGateDiagnostics(fixture.runId()), null).status()).isEqualTo("PASSED");
        // Model the later product-acceptance anomaly after the immutable automated gate:
        // the reviewed AUTO_LINK provenance becomes externally visible without a CREATE journal.
        jdbc.update("""
                insert into place_external_refs (
                    provider, external_id, place_id, current_source_record_id, source_release,
                    snapshot_id, first_seen_at, last_seen_at, status, source_hash, last_sync_run_id
                ) values ('FSQ', ?, ?, ?, '2026-09-15 20:07:45.157000',
                    '2325979374271449319', now(), now(), 'ACTIVE', ?, ?)
                """, externalId, existingPlaceId, sourceId, sourceHash, fixture.runId());
        return fixture;
    }

    private static PilotFixture importEnvelope(ObjectNode envelope, boolean passGate) {
        UUID runId = UUID.fromString(envelope.path("manifest").path("run_id").asText());
        String manifestHash = envelope.path("manifest_hash").asText();
        String authorization = envelope.path("manifest")
                .path("authorization_reference").asText();
        PlacePilotImportService.ImportResult result = importer.importApproved(
                envelope, manifestHash, authorization);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        if (passGate) {
            assertThat(gates.record(runId, true, passingGateDiagnostics(runId), null).status())
                    .isEqualTo("PASSED");
        }
        List<UUID> placeIds = jdbc.query("""
                select canonical_place_id from place_validation_decisions
                 where sync_run_id = ? and selected_for_stage order by selection_rank
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), runId);
        return new PilotFixture(runId, manifestHash, authorization, placeIds);
    }

    private static SpringApplication rollbackApplication() {
        SpringApplication application = PlacePilotRollbackApplication.createApplication();
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> privateDatasource = new HashMap<>();
        privateDatasource.put("spring.datasource.url", POSTGIS.getJdbcUrl());
        privateDatasource.put("spring.datasource.username", POSTGIS.getUsername());
        privateDatasource.put("spring.datasource.password", POSTGIS.getPassword());
        privateDatasource.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        privateDatasource.put("spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'UTC'");
        privateDatasource.put("spring.datasource.hikari.connection-test-query", "");
        privateDatasource.put("spring.datasource.jndi-name", "");
        privateDatasource.put("spring.main.web-application-type", "none");
        privateDatasource.put("spring.flyway.enabled", "false");
        privateDatasource.put("spring.liquibase.enabled", "false");
        privateDatasource.put("spring.sql.init.mode", "never");
        privateDatasource.put("phokarta.place-import.enabled", "false");
        privateDatasource.put("phokarta.place-canary-gate.enabled", "false");
        privateDatasource.put("context.initializer.classes", "");
        privateDatasource.put("context.listener.classes", "");
        privateDatasource.put("logging.config", "");
        privateDatasource.put("logging.level.root", "OFF");
        environment.getPropertySources().addFirst(
                new MapPropertySource("rollbackIntegrationDatasource", privateDatasource));
        application.setEnvironment(environment);
        return application;
    }

    private static String[] rollbackArguments(PilotFixture target, boolean dryRun) {
        return new String[]{
                "--phokarta.place-rollback.enabled=true",
                "--phokarta.place-rollback.sync-run-id=" + target.runId(),
                "--phokarta.place-rollback.expected-manifest-hash=" + target.manifestHash(),
                "--phokarta.place-rollback.dry-run=" + dryRun,
                "--phokarta.place-rollback.execute=" + !dryRun,
                "--phokarta.place-rollback.reason-code=PRODUCT_ACCEPTANCE_FAILURE"
        };
    }

    private static ObjectNode manifest(String seed, int createdPlaces, boolean includeQuarantine) {
        UUID runId = deterministicUuid("pilot-run:" + seed);
        String pilotRunKey = "didim-core-" + seed;
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("run_id", runId.toString());
        manifest.put("pilot_run_key", pilotRunKey);
        manifest.put("canary_stage", "STAGE_1");
        manifest.put("authorization_reference", "test-authorization-" + seed);
        manifest.put("status", "AUTONOMOUS_CANARY_AUTHORIZED");
        manifest.put("method_version", DECISION_METHOD_VERSION);
        manifest.putObject("scope")
                .put("name", "didim_core")
                .put("center_latitude", 37.3751)
                .put("center_longitude", 27.2678)
                .put("radius_meters", 6000);
        manifest.putObject("providers")
                .putObject("overture").put("release", "2026-09-23.0");
        manifest.withObject("providers").putObject("fsq")
                .put("release", "2026-09-15 20:07:45.157000")
                .put("snapshot_id", "2325979374271449319");

        ArrayNode sources = manifest.putArray("source_records");
        ArrayNode candidates = manifest.putArray("candidates");
        for (int index = 0; index < createdPlaces; index++) {
            String candidateId = seed + "-candidate-" + index;
            String name = "Rollback fixture " + seed + " " + index;
            double latitude = 37.3700 + index * 0.0011;
            double longitude = 27.2600 + index * 0.0013;
            String overtureExternal = seed + "-overture-" + index;
            String fsqExternal = seed + "-fsq-" + index;
            String overtureHash = sha256(seed + ":overture:" + index);
            String fsqHash = sha256(seed + ":fsq:" + index);
            UUID overtureId = sourceUuid("OVERTURE", overtureExternal, overtureHash);
            UUID fsqId = sourceUuid("FSQ", fsqExternal, fsqHash);
            source(sources, overtureId, "overture", overtureExternal, overtureHash,
                    name, latitude, longitude);
            source(sources, fsqId, "fsq", fsqExternal, fsqHash,
                    name, latitude, longitude);

            ObjectNode candidate = candidates.addObject();
            candidate.put("candidate_id", candidateId);
            candidate.put("decision", "AUTO_CREATE");
            candidate.put("decision_reason", "HIGH_EXISTENCE_NO_CANONICAL_MATCH");
            candidate.put("existence_assessment", "HIGH");
            candidate.put("candidate_hash", "0".repeat(64));
            candidate.put("decided_at", "2026-09-26T12:00:00Z");
            candidate.put("canary_eligible", true);
            candidate.put("selected_for_stage", true);
            candidate.put("selection_rank", index + 1);
            candidate.put("canonical_fields_valid", true);
            candidate.putObject("evidence").put("provider_agreement", "STRONG");
            candidate.putArray("hard_blockers");
            candidate.putObject("field_proposals").put("name", name);
            candidate.put("canonical_place_id", placeUuid(candidateId).toString());
            candidate.putObject("canonical")
                    .put("name", name)
                    .put("category", "CAFE")
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("city", "Didim")
                    .put("region", "Aydın")
                    .put("country", "Türkiye")
                    .put("address", "Atatürk Bulvarı " + (index + 1));
            candidate.putArray("source_record_ids")
                    .add(overtureId.toString()).add(fsqId.toString());
        }
        if (includeQuarantine) {
            String externalId = seed + "-quarantine";
            String hash = sha256(seed + ":quarantine");
            UUID sourceId = sourceUuid("OVERTURE", externalId, hash);
            source(sources, sourceId, "overture", externalId, hash,
                    "Unresolved " + seed, 37.368, 27.259);
            ObjectNode quarantine = candidates.addObject();
            quarantine.put("candidate_id", seed + "-quarantine");
            quarantine.put("decision", "QUARANTINE");
            quarantine.put("decision_reason", "UNVERIFIED_IDENTITY");
            quarantine.put("existence_assessment", "MEDIUM");
            quarantine.put("candidate_hash", "0".repeat(64));
            quarantine.put("decided_at", "2026-09-26T12:00:00Z");
            quarantine.put("canary_eligible", false);
            quarantine.put("selected_for_stage", false);
            quarantine.putObject("evidence").put("provider_agreement", "WEAK");
            quarantine.putArray("hard_blockers").add("UNVERIFIED_IDENTITY");
            quarantine.putObject("field_proposals");
            quarantine.putArray("source_record_ids").add(sourceId.toString());
        }
        refreshAccountingAndHashes(manifest);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.set("manifest", manifest);
        envelope.put("manifest_hash", importer.hashManifest(manifest));
        return envelope;
    }

    private static void source(
            ArrayNode sources, UUID id, String provider, String externalId, String hash,
            String name, double latitude, double longitude) {
        ObjectNode source = sources.addObject();
        source.put("source_record_id", id.toString());
        source.put("provider", provider);
        source.put("external_id", externalId);
        source.put("source_release", provider.equals("fsq")
                ? "2026-09-15 20:07:45.157000" : "2026-09-23.0");
        source.put("snapshot_id", provider.equals("fsq") ? "2325979374271449319" : "");
        source.put("method_version", SOURCE_METHOD_VERSION);
        source.put("normalized_name", name);
        source.put("latitude", latitude);
        source.put("longitude", longitude);
        source.put("address", "Atatürk Bulvarı 1");
        source.put("locality", "Didim");
        source.put("region", "Aydın");
        source.put("country_code", "TR");
        source.putArray("provider_categories").add("cafe");
        source.put("proposed_place_category", "CAFE");
        source.put("source_hash", hash);
        source.put("license_identifier", provider.equals("fsq")
                ? "Apache-2.0" : "source-dependent");
        source.putObject("provenance").put("fixture", true);
        source.put("observed_at", "2026-09-23T12:00:00Z");
        source.put("retrieved_at", "2026-09-26T12:00:00Z");
        source.put("usable", true);
    }

    private static void refreshAccountingAndHashes(ObjectNode manifest) {
        manifest.put("reporting_schema_version", "didim-autonomy-accounting-v1");
        manifest.putObject("source_record_states")
                .put("total", manifest.path("source_records").size())
                .put("usable", manifest.path("source_records").size())
                .put("rejected_before_canonical_grouping", 0);
        manifest.put("candidate_group_count", manifest.path("candidates").size());
        ObjectNode decisions = manifest.putObject("candidate_decisions");
        for (String decision : List.of(
                "AUTO_LINK", "AUTO_CREATE", "AUTO_ENRICH", "AUTO_REJECT", "QUARANTINE")) {
            int count = 0;
            for (JsonNode candidate : manifest.path("candidates")) {
                if (decision.equals(candidate.path("decision").asText())) count++;
            }
            decisions.put(decision, count);
        }
        for (JsonNode value : manifest.path("candidates")) {
            ObjectNode candidate = (ObjectNode) value;
            ObjectNode payload = candidate.deepCopy();
            payload.remove("candidate_hash");
            payload.remove("selected_for_stage");
            candidate.put("candidate_hash", importer.hashManifest(payload));
        }
    }

    private static ObjectNode passingGateDiagnostics(UUID syncRunId) {
        ObjectNode diagnostics = mapper.createObjectNode();
        diagnostics.put("probe_mode", "AUTONOMOUS_HTTP_AND_DATABASE_V1");
        diagnostics.put("baseline_captured_before_import", true);
        diagnostics.put("measured_pass", true);
        ObjectNode http = diagnostics.putObject("http_probes");
        http.putObject("before").put("passed", true);
        ObjectNode after = http.putObject("after");
        after.put("passed", true);
        ObjectNode surfaces = after.putObject("surfaces");
        List<UUID> selected = jdbc.query("""
                select canonical_place_id from place_validation_decisions
                 where sync_run_id = ? and selected_for_stage order by selection_rank
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), syncRunId);
        diagnostics.put("selected_place_count", selected.size());
        ArrayNode checked = diagnostics.putArray("selected_place_ids_checked");
        selected.forEach(id -> checked.add(id.toString()));
        ObjectNode coverage = diagnostics.putObject("selected_place_coverage");
        for (String surface : List.of("search", "map_nearby", "map_bounds", "place_detail")) {
            coverage.put(surface, selected.size());
            surfaces.putObject(surface + "_coverage")
                    .put("sample_count", selected.size())
                    .put("error_count", 0)
                    .put("passed", true);
        }
        diagnostics.putObject("catalog_anomaly_report").put("passed", true);
        diagnostics.put("search_correctness", "PASS");
        diagnostics.put("map_bounded_behavior", "PASS");
        diagnostics.put("backend_health", "PASS");
        diagnostics.put("same_manifest_idempotency", "PASS");
        diagnostics.put("duplicate_canonical_diagnostics", "PASS");
        diagnostics.put("same_coordinate_diagnostics", "PASS");
        diagnostics.put("place_detail_correctness", "PASS");
        diagnostics.put("duplicate_canonical_violations", 0);
        diagnostics.put("same_coordinate_anomalies", 0);
        ObjectNode performance = diagnostics.putObject("performance");
        performanceMetric(performance.putObject("search"), 10.0, 10.1);
        performanceMetric(performance.putObject("map"), 11.0, 11.1);
        performanceMetric(performance.putObject("place_detail"), 8.0, 8.1);
        diagnostics.putObject("api_error_rate")
                .put("baseline_rate", 0.0)
                .put("after_rate", 0.0)
                .put("relative_change", 0.0)
                .put("material_regression", false);
        return diagnostics;
    }

    private static void performanceMetric(ObjectNode metric, double before, double after) {
        metric.put("baseline_ms", before)
                .put("after_ms", after)
                .put("relative_change", (after - before) / before)
                .put("material_regression", false);
    }

    private static GraphFixture addUserGraph(PilotFixture fixture) {
        UUID userId = deterministicUuid("rollback-graph-user:" + fixture.runId());
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, created_at, updated_at
                ) values (?, ?, ?, 'Rollback Graph User', true, 0, 0, 0, 0, '{}', now(), now())
                """, userId, "rollback-" + fixture.runId() + "@example.test",
                "rollback_" + fixture.runId().toString().substring(0, 12));

        UUID experienceId = deterministicUuid("rollback-experience:" + fixture.runId());
        UUID mutationId = deterministicUuid("rollback-mutation:" + fixture.runId());
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, client_mutation_id, client_payload_fingerprint,
                    visited_at, overall_rating, public_review, private_memory, photos,
                    visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, current_date, 8, 'acceptance', '', '{}',
                    'PUBLIC', 'UNVERIFIED', now(), now())
                """, experienceId, userId, fixture.placeIds().get(1), mutationId,
                sha256("rollback-experience-payload:" + fixture.runId()));
        jdbc.update("""
                insert into visit_experience_details (
                    visit_id, primary_experience_code, overall_feeling_code, feeling_source,
                    title, title_source, story, taxonomy_version, created_at, updated_at
                ) values (?, 'KAHVE', 'GUZELDI', 'EXPLICIT', 'Rollback acceptance',
                    'CUSTOM', 'Acceptance graph protection', 1, now(), now())
                """, experienceId);

        jdbc.update("insert into saved_places(user_id, place_id, saved_at) values (?, ?, now())",
                userId, fixture.placeIds().get(2));
        UUID collectionId = deterministicUuid("rollback-collection:" + fixture.runId());
        jdbc.update("""
                insert into collections (
                    id, user_id, title, description, visibility, cover_image,
                    created_at, updated_at
                ) values (?, ?, 'Rollback acceptance', '', 'PRIVATE', '', now(), now())
                """, collectionId, userId);
        jdbc.update("""
                insert into collection_places(collection_id, place_id, display_order, added_at)
                values (?, ?, 0, now())
                """, collectionId, fixture.placeIds().get(3));
        jdbc.update("""
                insert into collection_experiences(collection_id, experience_id, display_order, added_at)
                values (?, ?, 1, now())
                """, collectionId, experienceId);
        jdbc.update("""
                insert into planned_experiences(user_id, experience_id, planned_at)
                values (?, ?, now())
                """, userId, experienceId);
        UUID acknowledgementId = deterministicUuid("rollback-ack:" + fixture.runId());
        jdbc.update("""
                insert into experience_acknowledgements (
                    id, user_id, source_experience_id, place_id,
                    primary_experience_code, acknowledged_at
                ) values (?, ?, ?, ?, 'KAHVE', now())
                """, acknowledgementId, userId, experienceId, fixture.placeIds().get(4));
        return new GraphFixture(userId, experienceId, collectionId, acknowledgementId);
    }

    private static void assertUserGraphPreserved(GraphFixture graph) {
        assertThat(count("select count(*) from visits where id = ?", graph.experienceId())).isOne();
        assertThat(count("select count(*) from visit_experience_details where visit_id = ?",
                graph.experienceId())).isOne();
        assertThat(count("select count(*) from saved_places where user_id = ?", graph.userId()))
                .isOne();
        assertThat(count("select count(*) from collection_places where collection_id = ?",
                graph.collectionId())).isOne();
        assertThat(count("select count(*) from collection_experiences where collection_id = ?",
                graph.collectionId())).isOne();
        assertThat(count("select count(*) from planned_experiences where experience_id = ?",
                graph.experienceId())).isOne();
        assertThat(count("select count(*) from experience_acknowledgements where id = ?",
                graph.acknowledgementId())).isOne();
    }

    private static void addLaterAlias(UUID sourceRunId, UUID placeId, String seed) {
        String externalId = "rollback-" + seed;
        String hash = sha256(seed + ":source");
        UUID sourceId = sourceUuid("FSQ", externalId, hash);
        jdbc.update("""
                insert into place_source_records (
                    id, sync_run_id, provider, external_id, source_release, snapshot_id,
                    method_version, normalized_name, location, provider_categories,
                    source_hash, license_identifier, provenance, observed_at, retrieved_at
                ) values (?, ?, 'FSQ', ?, '2026-09-15 20:07:45.157000',
                    '2325979374271449319', ?, ?,
                    ST_SetSRID(ST_MakePoint(27.2678, 37.3751), 4326), '["cafe"]', ?,
                    'Apache-2.0', '{"fixture":true}', now(), now())
                """, sourceId, sourceRunId, externalId, SOURCE_METHOD_VERSION,
                "Later alias " + seed, hash);
        jdbc.update("""
                insert into place_external_refs (
                    provider, external_id, place_id, current_source_record_id, source_release,
                    snapshot_id, first_seen_at, last_seen_at, status, source_hash, last_sync_run_id
                ) values ('FSQ', ?, ?, ?, '2026-09-15 20:07:45.157000',
                    '2325979374271449319', now(), now(), 'ACTIVE', ?, ?)
                """, externalId, placeId, sourceId, hash, sourceRunId);
    }

    private static void insertPlace(UUID id, String name, String origin) {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, origin,
                    created_at, updated_at
                ) values (?, ?, '', 'CAFE', '{}',
                    ST_SetSRID(ST_MakePoint(27.25, 37.36), 4326),
                    'Didim', 'Aydın', 'Türkiye', '', '', '{}', 1, ?, now(), now())
                """, id, name, origin);
    }

    private static void assertPlaceState(UUID placeId, String catalog, String rollbackState) {
        assertThat(jdbc.queryForObject(
                "select catalog_status from places where id = ?", String.class, placeId))
                .isEqualTo(catalog);
        assertThat(jdbc.queryForObject("""
                select rollback_state from place_pilot_catalog_writes where place_id = ?
                """, String.class, placeId)).isEqualTo(rollbackState);
    }

    private static DatabaseSnapshot snapshot(PilotFixture fixture) {
        return new DatabaseSnapshot(
                jdbc.queryForList("""
                        select id::text, catalog_status, origin, updated_at::text
                          from places p
                         where id = any (?::uuid[])
                            or exists (select 1 from place_external_refs ref
                                       where ref.place_id = p.id and ref.last_sync_run_id = ?)
                         order by id
                        """, uuidArray(fixture.placeIds()), fixture.runId()),
                jdbc.queryForList("""
                        select id::text, source_hash, provenance::text
                          from place_source_records where sync_run_id = ? order by id
                        """, fixture.runId()),
                jdbc.queryForList("""
                        select provider, external_id, place_id::text, status,
                               current_source_record_id::text, last_sync_run_id::text
                          from place_external_refs
                         where place_id = any (?::uuid[]) or last_sync_run_id = ?
                         order by provider, external_id
                        """, uuidArray(fixture.placeIds()), fixture.runId()),
                jdbc.queryForList("""
                        select id::text, rollback_state, rolled_back_at::text
                          from place_pilot_catalog_writes where sync_run_id = ? order by id
                        """, fixture.runId()),
                jdbc.queryForList("""
                        select id::text, event_type, occurred_at::text, details::text
                          from place_pilot_operational_events where sync_run_id = ? order by event_type
                        """, fixture.runId()),
                jdbc.queryForList("""
                        select id::text, event_type, occurred_at::text, details::text
                          from place_external_ref_events where sync_run_id = ? order by id
                        """, fixture.runId()),
                jdbc.queryForList("""
                        select gate_status, checked_at::text, diagnostics::text
                          from place_pilot_canary_gates where sync_run_id = ?
                        """, fixture.runId()),
                jdbc.queryForList("""
                        select 'visit' as kind, id::text as id from visits
                         where place_id = any (?::uuid[])
                        union all
                        select 'saved', user_id::text || ':' || place_id::text from saved_places
                         where place_id = any (?::uuid[])
                        union all
                        select 'collection', collection_id::text || ':' || place_id::text
                          from collection_places where place_id = any (?::uuid[])
                        union all
                        select 'ack', id::text from experience_acknowledgements
                         where place_id = any (?::uuid[]) order by kind, id
                        """, uuidArray(fixture.placeIds()), uuidArray(fixture.placeIds()),
                        uuidArray(fixture.placeIds()), uuidArray(fixture.placeIds())));
    }

    private static String uuidArray(List<UUID> values) {
        return "{" + values.stream().map(UUID::toString)
                .reduce((left, right) -> left + "," + right).orElse("") + "}";
    }

    private static void assertOperationalFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String category) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(OperationalFailure.class,
                        failure -> assertThat(failure.category()).isEqualTo(category));
    }

    private static void addMappings(List<String> values, RequestMapping mapping) {
        if (mapping == null) return;
        values.addAll(Arrays.asList(mapping.value()));
        values.addAll(Arrays.asList(mapping.path()));
    }

    private static UUID sourceUuid(String provider, String externalId, String sourceHash) {
        return uuidV5(SOURCE_UUID_NAMESPACE,
                provider.toUpperCase(Locale.ROOT) + "\n" + externalId + "\n"
                        + SOURCE_METHOD_VERSION + "\n" + sourceHash);
    }

    private static UUID placeUuid(String candidateId) {
        return uuidV5(PLACE_UUID_NAMESPACE, DECISION_METHOD_VERSION + "\n" + candidateId);
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static UUID deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(
                url, POSTGIS.getUsername(), POSTGIS.getPassword()));
    }

    private static String databaseUrl(String databaseName) {
        URI uri = URI.create(POSTGIS.getJdbcUrl().replace("jdbc:", ""));
        String base = "jdbc:" + uri.getScheme() + "://" + uri.getAuthority();
        return base + "/" + databaseName
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static final class IsolationProbe implements ApplicationRunner, Ordered {
        private final ConfigurableApplicationContext context;
        private final AtomicBoolean inspected;

        private IsolationProbe(ConfigurableApplicationContext context, AtomicBoolean inspected) {
            this.context = context;
            this.inspected = inspected;
        }

        @Override
        public void run(ApplicationArguments args) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
            assertThat(context.getBeansOfType(WebServer.class)).isEmpty();
            assertThat(context.getBeansOfType(PlacePilotGateReconciliationService.class)).isEmpty();
            inspected.set(true);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    private record PilotFixture(
            UUID runId, String manifestHash, String authorizationReference,
            List<UUID> placeIds) { }

    private record GraphFixture(
            UUID userId, UUID experienceId, UUID collectionId, UUID acknowledgementId) { }

    private record DatabaseSnapshot(
            List<Map<String, Object>> places,
            List<Map<String, Object>> sourceRecords,
            List<Map<String, Object>> externalReferences,
            List<Map<String, Object>> journal,
            List<Map<String, Object>> operationalEvents,
            List<Map<String, Object>> referenceEvents,
            List<Map<String, Object>> gate,
            List<Map<String, Object>> userGraph) { }
}

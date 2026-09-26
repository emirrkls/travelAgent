package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Real V17 tables on disposable PostGIS only; no disabled integrity triggers. */
@Testcontainers
class PlacePilotSourceAccountingIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;

    @BeforeAll static void migrate() {
        Flyway flyway = Flyway.configure().dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(),
                POSTGIS.getPassword()).locations("classpath:db/migration/schema").load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("17");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGIS.getJdbcUrl(),
                POSTGIS.getUsername(), POSTGIS.getPassword()));
    }

    static List<String> failures() { return PlacePilotAccountingFixture.failures(); }

    @Test void actualRejectedObservationsOutsideCandidateGroupsPass() {
        var fixture = PlacePilotAccountingFixture.small();
        seed(fixture);
        assertThat(PlacePilotSourceAccounting.inspectPersisted(jdbc, fixture.approved)
                .path("passed").asBoolean()).isTrue();
    }

    @ParameterizedTest @MethodSource("failures")
    void corruptedPersistedAccountingFails(String scenario) {
        var fixture = PlacePilotAccountingFixture.small();
        fixture.corrupt(scenario);
        seed(fixture);
        assertThat(PlacePilotSourceAccounting.inspectPersisted(jdbc, fixture.approved)
                .path("passed").asBoolean()).as(scenario).isFalse();
    }

    @Test void exactSealedPopulationSizesPassAgainstPersistedRows() {
        var fixture = new PlacePilotAccountingFixture(18924, 17630, 16135, 71);
        seed(fixture);
        var result = PlacePilotSourceAccounting.inspectPersisted(jdbc, fixture.approved);
        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("source_total").asInt()).isEqualTo(18924);
        assertThat(result.path("source_usable").asInt()).isEqualTo(17630);
        assertThat(result.path("source_rejected_before_grouping").asInt()).isEqualTo(1294);
        assertThat(result.path("candidate_group_count").asInt()).isEqualTo(16135);
        assertThat(result.path("selected_count").asInt()).isEqualTo(71);
    }

    @Test void noRejectedObservationsPass() {
        var fixture = new PlacePilotAccountingFixture(2, 2, 2, 1);
        seed(fixture);
        assertThat(PlacePilotSourceAccounting.inspectPersisted(jdbc, fixture.approved)
                .path("passed").asBoolean()).isTrue();
    }

    @Test void exactSealedSelectionIsASubsetNotTheWholeEligiblePopulation() {
        var fixture = new PlacePilotAccountingFixture(3, 2, 2, 2, 1);
        seed(fixture);
        assertThat(PlacePilotSourceAccounting.inspectPersisted(jdbc, fixture.approved)
                .path("passed").asBoolean()).isTrue();
    }

    @Test void approvedObservationsReusedFromEarlierRunStillHaveExactCoverage() {
        var fixture = PlacePilotAccountingFixture.small();
        UUID prior = UUID.randomUUID();
        seedRun(prior, fixture.declared);
        seedRun(fixture.approvedRun(), fixture.declared);
        seedSources(fixture, prior);
        seedCandidates(fixture);
        assertThat(PlacePilotSourceAccounting.inspectPersisted(jdbc, fixture.approved)
                .path("passed").asBoolean()).isTrue();
    }

    private static void seed(PlacePilotAccountingFixture fixture) {
        seedRun(fixture.approvedRun(), fixture.declared);
        seedSources(fixture, fixture.approvedRun());
        seedCandidates(fixture);
    }

    private static void seedRun(UUID run, PlacePilotSourceAccounting.Declared counts) {
        // DRY_RUN permits synthetic audit fixtures without minting authorization or canonical writes.
        jdbc.update("""
                INSERT INTO place_provider_sync_runs (id, pilot_run_key, canary_stage, provider,
                    resolved_release, method_version, scope_name, scope_center_latitude,
                    scope_center_longitude, scope_radius_meters, started_at, completed_at, status,
                    source_count, usable_count, source_rejected_count, created_count, linked_count,
                    enriched_count, auto_rejected_count, quarantined_count, canary_eligible_count)
                VALUES (?, ?, 'DRY_RUN', 'MULTI_SOURCE', 'synthetic',
                    'didim-autonomous-validation-v2', 'didim_core', 37.3751, 27.2678, 6000,
                    now(), now(), 'SUCCEEDED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run, run.toString(), counts.total(), counts.usable(), counts.rejected(),
                counts.created(), counts.linked(), counts.enriched(), counts.autoRejected(),
                counts.quarantined(), counts.eligible());
    }

    private static void seedSources(PlacePilotAccountingFixture fixture, UUID owner) {
        jdbc.batchUpdate("""
                INSERT INTO place_source_records (id, sync_run_id, provider, external_id,
                    source_release, method_version, provider_categories, source_hash,
                    license_identifier, provenance, observed_at, retrieved_at)
                VALUES (?, ?, 'FSQ', ?, 'synthetic', 'didim-canonicalization-v3',
                    '[]'::jsonb, ?, 'synthetic', ?::jsonb, now(), now())
                """, fixture.sources, 500, (statement, source) -> {
            var provenance = JsonNodeFactory.instance.objectNode();
            if (!source.usable()) {
                provenance.put("source_record_state", "SOURCE_REJECTED");
                if (source.validState()) provenance.put("source_rejection_reason", "synthetic rejected observation");
            }
            statement.setObject(1, source.id());
            statement.setObject(2, owner);
            statement.setString(3, owner + ":" + source.id());
            statement.setString(4, "a".repeat(64));
            statement.setString(5, provenance.toString());
        });
    }

    private static void seedCandidates(PlacePilotAccountingFixture fixture) {
        jdbc.batchUpdate("""
                INSERT INTO place_validation_decisions (id, sync_run_id, pilot_run_key,
                    candidate_key, validation_method_version, decision_state, decision_reason,
                    existence_assessment, source_record_ids, canonical_place_id, candidate_hash,
                    canary_eligible, selected_for_stage, selection_rank, decided_at)
                VALUES (?, ?, ?, ?, 'didim-autonomous-validation-v2', ?, 'SYNTHETIC_ACCOUNTING',
                    'HIGH', ?, ?, ?, ?, ?, ?, now())
                """, fixture.candidates, 500, (statement, candidate) -> {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, fixture.approvedRun());
            statement.setString(3, fixture.approvedRun().toString());
            statement.setString(4, candidate.key());
            statement.setString(5, candidate.state());
            statement.setArray(6, statement.getConnection().createArrayOf("uuid",
                    candidate.sourceIds().toArray(UUID[]::new)));
            statement.setObject(7, candidate.canonicalId());
            statement.setString(8, candidate.candidateHash());
            statement.setBoolean(9, candidate.eligible());
            statement.setBoolean(10, candidate.selected());
            statement.setObject(11, candidate.rank());
        });
    }
}

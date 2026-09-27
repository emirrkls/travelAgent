package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService;
import com.emirrkls.phokarta.backend.service.PlacePilotImportService;
import com.emirrkls.phokarta.backend.service.PlacePilotSourceAccounting;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real normal orchestrator/HTTP/atomic filesystem/Flyway; importer is stopped at its boundary. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.flyway.locations=classpath:db/migration/schema", "management.server.port=0",
        "management.endpoint.health.probes.enabled=true", "phokarta.media.enabled=false",
        "phokarta.place-import.enabled=false"})
@Testcontainers
class PlacePilotBaselineArtifactIntegrationTest {
    static final UUID RUN=UUID.fromString("83000000-0000-4000-8000-000000000001");
    static final UUID STABLE=UUID.fromString("aa000000-0000-4000-8000-000000000001");
    static final String HASH="a".repeat(64);
    static final String AUTH="PRIVATE_SECRET_SENTINEL";
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGIS=new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcTemplate jdbc;
    @Autowired PlacePilotAutonomousCanaryService canary;
    @MockitoBean PlacePilotImportService importer;
    @LocalServerPort int serverPort;
    @Value("${local.management.port}") int managementPort;
    @TempDir Path directory;
    Path manifest;

    @BeforeEach void prepareDisposableFixture() throws Exception {
        jdbc.update("""
                INSERT INTO places(id,name,description,category,subcategories,location,city,region,country,address,
                    cover_image,photos,price_level,created_at,updated_at)
                VALUES (?,'Stable manual baseline','fixture','CAFE',array[]::text[],
                    ST_SetSRID(ST_MakePoint(28.9784,41.022),4326),'Istanbul','Istanbul','TR','fixture','',
                    array[]::text[],0,now(),now()) ON CONFLICT(id) DO NOTHING
                """,STABLE);
        manifest=directory.resolve("test-envelope.json");
        Files.writeString(manifest,"""
                {"manifest_hash":"%s","manifest":{"run_id":"%s","authorization_reference":"%s",
                "method_version":"didim-autonomous-validation-v2","candidates":[{
                "selected_for_stage":true,"selection_rank":1,"canonical_place_id":"e0f36cef-847d-5d96-b8f7-c7a72fef2447",
                "canonical":{"name":"sealed unimported Didim query","category":"BEACH","latitude":37.3751,"longitude":27.2678}}]}}
                """.formatted(HASH,RUN,AUTH));
        when(importer.hashManifest(any())).thenReturn(HASH);
        when(importer.validateApprovedAccounting(any(),eq(HASH),eq(AUTH)))
                .thenReturn(mock(PlacePilotSourceAccounting.Approved.class));
    }

    @Test void realNormalBaselineIsPersistedReopenedAndVerifiedBeforeImporterBoundaryWithNoDatabaseMutations() throws Exception {
        var before=snapshot();
        when(importer.importApproved(manifest,HASH,AUTH)).thenAnswer(call -> {
            Path finalFile;
            try(var files=Files.walk(directory.resolve(RUN.toString()))) {
                finalFile=files.filter(path -> path.getFileName().toString().equals("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json"))
                        .findFirst().orElseThrow();
            }
            var actual=new ObjectMapper().readTree(Files.readAllBytes(finalFile));
            assertThat(actual.path("overall").asText()).isEqualTo("PASS");
            assertThat(actual.path("run_id").asText()).isEqualTo(RUN.toString());
            assertThat(actual.path("request_count").asInt()).isEqualTo(25);
            assertThat(actual.path("surfaces").size()).isEqualTo(5);
            for(var surface:actual.path("surfaces")) {
                assertThat(surface.path("probes").size()).isEqualTo(5);
                assertThat(surface.path("status_distribution").path("200").asInt()).isEqualTo(5);
            }
            assertThat(Files.readString(finalFile)).doesNotContain(AUTH,"Authorization","jdbc:");
            assertThat(Files.getPosixFilePermissions(finalFile)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(snapshot()).isEqualTo(before);
            throw new IllegalStateException("TEST_IMPORT_BOUNDARY_REACHED");
        });
        assertThatThrownBy(() -> canary.run(configuration(directory))).hasMessage("TEST_IMPORT_BOUNDARY_REACHED");
        verify(importer).importApproved(manifest,HASH,AUTH);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT max(version::numeric) FROM flyway_schema_history",Integer.class)).isEqualTo(17);
    }

    @Test void realArtifactCreationFailureKeepsAllPilotAndCatalogTablesUnchangedAndNeverImports() throws Exception {
        var before=snapshot();
        assertThatThrownBy(() -> canary.run(configuration(directory.resolve("absent-private-directory"))))
                .hasMessage("PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED").hasNoCause();
        verify(importer,never()).importApproved(any(Path.class),anyString(),anyString());
        assertThat(snapshot()).isEqualTo(before);
    }

    private PlacePilotAutonomousCanaryService.Configuration configuration(Path privateDirectory) {
        return new PlacePilotAutonomousCanaryService.Configuration(manifest,HASH,AUTH,
                URI.create("http://127.0.0.1:"+serverPort),
                URI.create("http://127.0.0.1:"+managementPort+"/actuator"),STABLE,5,Duration.ofSeconds(5),privateDirectory);
    }
    private Map<String,Object> snapshot() {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:java.util.List.of("places","place_provider_sync_runs","place_source_records","place_external_refs",
                "place_validation_decisions","place_pilot_catalog_writes","place_pilot_canary_gates","place_pilot_operational_events")) {
            result.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));
        }
        result.put("catalog_fingerprint",jdbc.queryForObject(
                "SELECT md5(string_agg(to_jsonb(p)::text,'|' ORDER BY id)) FROM places p",String.class));
        return result;
    }
}

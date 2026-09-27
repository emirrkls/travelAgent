package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.operations.PlacePilotBaselineDiagnosisApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.flyway.locations=classpath:db/migration/schema", "management.server.port=0",
        "management.endpoint.health.probes.enabled=true", "management.endpoint.health.show-details=never",
        "phokarta.media.enabled=false", "phokarta.place-import.enabled=false"})
@Testcontainers
class PlacePilotBaselineDiagnosisIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGIS=new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int serverPort;
    @org.springframework.beans.factory.annotation.Value("${local.management.port}") int managementPort;
    @TempDir Path directory;

    @BeforeEach void seedTestDatabaseOnly() {
        for(int i=1;i<=2;i++) jdbc.update("""
                INSERT INTO places(id,name,description,category,subcategories,location,city,region,country,address,
                    cover_image,photos,price_level,created_at,updated_at)
                VALUES (?,?,'stable contract fixture','CAFE',array[]::text[],ST_SetSRID(ST_MakePoint(28.9784,41.022),4326),
                    'Istanbul','Istanbul','TR','fixture','',array[]::text[],0,now(),now()) ON CONFLICT(id) DO NOTHING
                """,UUID.fromString("aa000000-0000-4000-8000-00000000000"+i),"Staging Harbor Cafe "+i);
    }

    @Test void actualPublicPrivateContractsAndPostgisDiagnosisHaveZeroDatabaseMutations() throws Exception {
        Map<String,Object> before=snapshot();
        assertThat(diagnose("aa000000-0000-4000-8000-000000000001")).isZero();
        var artifact=new ObjectMapper().readTree(Files.readString(directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")));
        assertThat(artifact.path("overall").asText()).isEqualTo("PASS");
        assertThat(artifact.path("request_count").asInt()).isEqualTo(25);
        for(var surface:artifact.path("surfaces")) {
            assertThat(surface.path("sample_count").asInt()).isEqualTo(5);
            assertThat(surface.path("status_distribution").path("200").asInt()).isEqualTo(5);
            assertThat(surface.path("probes").size()).isEqualTo(5);
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT max(version::numeric) FROM flyway_schema_history",Integer.class)).isEqualTo(17);
    }

    @Test void stalePlaceTargetFails404WithoutReplacingItOrMutatingDatabase() throws Exception {
        Map<String,Object> before=snapshot();
        assertThat(diagnose("aa000000-0000-4000-8000-000000000099")).isEqualTo(1);
        var artifact=new ObjectMapper().readTree(Files.readString(directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")));
        assertThat(artifact.path("failing_surfaces").get(0).asText()).isEqualTo("place_detail");
        assertThat(artifact.path("surfaces").path("place_detail").path("first_failure_category").asText()).isEqualTo("HTTP_404");
        assertThat(snapshot()).isEqualTo(before);
    }

    private int diagnose(String stable) throws Exception {
        Path envelope=directory.resolve("envelope.json");
        Files.writeString(envelope,"""
                {"manifest":{"candidates":[{"selected_for_stage":true,"selection_rank":1,
                "canonical_place_id":"e0f36cef-847d-5d96-b8f7-c7a72fef2447",
                "canonical":{"name":"sealed unimported Didim query","category":"BEACH","latitude":37.3751,"longitude":27.2678}}]}}
                """);
        return PlacePilotBaselineDiagnosisApplication.execute(new String[]{
                "--base-url=http://127.0.0.1:"+serverPort,
                "--health-base-url=http://127.0.0.1:"+managementPort+"/actuator",
                "--manifest-path="+envelope,
                "--expected-envelope-sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(envelope))),
                "--baseline-place-id="+stable,"--output-path="+directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")});
    }

    private Map<String,Object> snapshot() {
        Map<String,Object> state=new LinkedHashMap<>();
        for(String table:java.util.List.of("places","users","visits","saved_places","collections","collection_places",
                "place_provider_sync_runs","place_source_records","place_external_refs","place_validation_decisions",
                "place_pilot_catalog_writes","place_pilot_canary_gates","place_pilot_operational_events")) {
            state.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));
        }
        state.put("catalog_fingerprint",jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(p)::text,'|' ORDER BY id)) FROM places p",String.class));
        return state;
    }
}

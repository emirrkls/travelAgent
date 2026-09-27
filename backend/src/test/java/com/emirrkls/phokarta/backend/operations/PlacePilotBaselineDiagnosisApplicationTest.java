package com.emirrkls.phokarta.backend.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlacePilotBaselineDiagnosisApplicationTest {
    @TempDir Path directory;
    @Test void diagnosisUsesOnlyTwentyFiveGetsAndPersistsAllSurfaces() throws Exception { diagnose(200,0); }
    @Test void failedDiagnosisStillWritesArtifactBeforeReturningFailure() throws Exception { diagnose(500,1); }

    private void diagnose(int healthStatus,int exit) throws Exception {
        AtomicInteger requests=new AtomicInteger();
        AtomicInteger mutations=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); if(!exchange.getRequestMethod().equals("GET")) mutations.incrementAndGet();
            String path=exchange.getRequestURI().getPath();
            String body=path.equals("/actuator/health") ? "{\"status\":\"UP\"}"
                    : path.equals("/api/v1/places") ? "{\"content\":[]}"
                    : path.endsWith("nearby")||path.endsWith("bounds") ? "[]"
                    : "{\"id\":\"aa000000-0000-4000-8000-000000000001\",\"name\":\"Staging Harbor Cafe\",\"category\":\"CAFE\",\"latitude\":41.022,\"longitude\":28.9784}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.equals("/actuator/health")?healthStatus:200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        try {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();
            var args=arguments(origin);
            assertThat(PlacePilotBaselineDiagnosisApplication.execute(args)).isEqualTo(exit);
            assertThat(requests).hasValue(25); assertThat(mutations).hasValue(0);
            var artifact=new ObjectMapper().readTree(Files.readString(directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")));
            assertThat(artifact.path("diagnosis_only").asBoolean()).isTrue();
            assertThat(artifact.path("database_mutations").asInt()).isZero();
            assertThat(artifact.path("timeout_ms").asLong()).isEqualTo(5000);
            assertThat(artifact.path("request_count").asInt()).isEqualTo(25);
            assertThat(artifact.path("surfaces").size()).isEqualTo(5);
            assertThat(artifact.path("overall").asText()).isEqualTo(exit==0?"PASS":"FAIL");
            for(String surface:List.of("health","search","map_nearby","map_bounds","place_detail"))
                assertThat(artifact.path("surfaces").path(surface).path("probes").size()).isEqualTo(5);
        } finally {server.stop(0);}
    }

    @Test void importRollbackTimeoutOrCredentialOptionsFailBeforeAnyNetwork() throws Exception {
        for(String invalid:List.of("--import=true","--timeout=30s","--authorization=Bearer-SECRET","--database-password=SECRET","--rollback=true")) {
            var args=new java.util.ArrayList<>(List.of(arguments("http://127.0.0.1:1")));args.add(invalid);
            assertThat(PlacePilotBaselineDiagnosisApplication.execute(args.toArray(String[]::new))).isEqualTo(1);
            assertThat(directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")).doesNotExist();
        }
    }

    @Test void wrongEnvelopeOrCredentialBearingOriginCannotProbe() throws Exception {
        var args=arguments("http://user:secret@127.0.0.1:1");
        assertThat(PlacePilotBaselineDiagnosisApplication.execute(args)).isEqualTo(1);
        args=arguments("http://127.0.0.1:1");
        for(int i=0;i<args.length;i++) if(args[i].startsWith("--expected-envelope-sha256=")) args[i]="--expected-envelope-sha256="+"0".repeat(64);
        assertThat(PlacePilotBaselineDiagnosisApplication.execute(args)).isEqualTo(1);
        assertThat(directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")).doesNotExist();
    }

    private String[] arguments(String origin) throws Exception {
        Path envelope=directory.resolve("sealed-fixture.json");
        Files.writeString(envelope,"""
                {"manifest":{"candidates":[{"selected_for_stage":true,"selection_rank":1,
                "canonical_place_id":"e0f36cef-847d-5d96-b8f7-c7a72fef2447",
                "canonical":{"name":"Didim existing frozen query","category":"BEACH","latitude":37.3751,"longitude":27.2678}}]}}
                """);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(envelope)));
        return new String[]{"--base-url="+origin,"--health-base-url="+origin+"/actuator",
                "--manifest-path="+envelope,"--expected-envelope-sha256="+hash,
                "--baseline-place-id=aa000000-0000-4000-8000-000000000001",
                "--output-path="+directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json")};
    }
}

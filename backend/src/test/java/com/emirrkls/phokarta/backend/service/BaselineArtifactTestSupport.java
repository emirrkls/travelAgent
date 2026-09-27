package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Faults affect only JUnit private temporary files, never an operational entry point. */
final class BaselineArtifactTestSupport {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final String METHOD = "didim-autonomous-validation-v2";
    static final UUID RUN = UUID.fromString("81000000-0000-4000-8000-000000000001");
    static final UUID EXECUTION = UUID.fromString("82000000-0000-4000-8000-000000000001");
    static PlacePilotBaselineArtifactService.Identity identity() {
        return new PlacePilotBaselineArtifactService.Identity(EXECUTION, RUN, "a".repeat(64), "b".repeat(64),
                METHOD, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
    }
    static PlacePilotHttpProbeService.ProbeSuite suite(boolean passed) {
        var surfaces = new LinkedHashMap<String, PlacePilotHttpProbeService.SurfaceResult>();
        for (String name : PlacePilotBaselineArtifactService.SURFACES) {
            boolean healthy = passed || !name.equals("health");
            var observations = java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
                    new PlacePilotHttpProbeService.ProbeDiagnostic(name, index, "GET",
                            PlacePilotHttpProbeService.pathTemplate(name), Instant.EPOCH.plusMillis(index),
                            index, healthy ? 200 : 503, false, "RESPONSE_RECEIVED",
                            healthy ? "VALID" : "NOT_EVALUATED", healthy ? null : "HTTP_503")).toList();
            surfaces.put(name, PlacePilotHttpProbeService.SurfaceResult.from(observations));
        }
        return PlacePilotHttpProbeService.ProbeSuite.from(surfaces);
    }
    static class FaultFiles extends PlacePilotBaselineArtifactService.FileAccess {
        final String fault;
        boolean finalized;
        Path finalPath;
        FaultFiles(String fault) { this.fault = fault; }
        @Override Path createTemporary(Path directory) throws IOException {
            if (fault.equals("CREATE")) throw new IOException("PRIVATE_SECRET_SENTINEL");
            return super.createTemporary(directory);
        }
        @Override PlacePilotBaselineArtifactService.WriteHandle openTemporary(Path path) throws IOException {
            if (fault.equals("OPEN")) throw new IOException("PRIVATE_SECRET_SENTINEL");
            var delegate = super.openTemporary(path);
            return new PlacePilotBaselineArtifactService.WriteHandle() {
                public void write(byte[] bytes) throws IOException {
                    if (fault.equals("WRITE")) { delegate.write(java.util.Arrays.copyOf(bytes, 7)); throw new IOException(); }
                    delegate.write(bytes);
                }
                public void force() throws IOException {
                    if (fault.equals("FORCE")) throw new IOException();
                    delegate.force();
                }
                public void close() throws IOException {
                    delegate.close();
                    if (fault.equals("CLOSE")) throw new IOException();
                }
            };
        }
        @Override void finalizeAtomic(Path temporary, Path target) throws IOException {
            if (fault.equals("FINALIZE")) throw new IOException();
            super.finalizeAtomic(temporary, target);
            finalized = true;
            finalPath = target;
            if (fault.equals("MALFORMED")) { Files.writeString(target, "{invalid"); return; }
            if (fault.equals("HASH_ONLY")) { Files.writeString(target, Files.readString(target) + " "); return; }
            if (fault.equals("OVERSIZE")) { Files.write(target, new byte[PlacePilotBaselineArtifactService.MAX_BYTES + 1]); return; }
            if (fault.equals("DUPLICATE_JSON_KEY")) {
                Files.writeString(target, Files.readString(target).replaceFirst("\\{", "{\"overall\":\"PASS\","));
                return;
            }
            ObjectNode value = (ObjectNode) new ObjectMapper().readTree(Files.readString(target));
            ObjectNode health = (ObjectNode) value.path("surfaces").path("health");
            ObjectNode probe = (ObjectNode) health.path("probes").get(0);
            switch (fault) {
                case "VERSION" -> value.put("version", "wrong");
                case "RUN" -> value.put("run_id", UUID.randomUUID().toString());
                case "EXECUTION" -> value.put("baseline_execution_id", UUID.randomUUID().toString());
                case "MANIFEST" -> value.put("manifest_hash", "c".repeat(64));
                case "ENVELOPE" -> value.put("envelope_sha256", "d".repeat(64));
                case "METHOD" -> value.put("validation_method", "wrong");
                case "OVERALL" -> value.put("overall", "FAIL");
                case "TIMESTAMP" -> value.put("started_at", Instant.EPOCH.toString());
                case "SURFACE_MISSING" -> ((ObjectNode)value.path("surfaces")).remove("search");
                case "PROBE_MISSING" -> ((com.fasterxml.jackson.databind.node.ArrayNode)health.path("probes")).remove(4);
                case "PROBE_DUPLICATE" -> ((ObjectNode)health.path("probes").get(4)).put("sample_index", 1);
                case "SUMMARY" -> health.put("median_ms", 999);
                case "STATUS" -> probe.put("http_status", 500);
                case "TIMEOUT" -> probe.put("timeout", true);
                case "DURATION" -> probe.put("duration_ms", -1);
                case "NONFINITE" -> probe.put("duration_ms", Double.NaN);
                case "UNSAFE_PATH" -> probe.put("path_template", "https://token:PRIVATE_SECRET_SENTINEL@host");
                case "EXTRA_FIELD" -> probe.put("Authorization", "PRIVATE_SECRET_SENTINEL");
                default -> { return; }
            }
            Files.writeString(target, value.toPrettyString());
        }
        @Override void forceDirectory(Path directory) throws IOException {
            if (finalized && fault.equals("DIRECTORY_FORCE")) throw new IOException();
            super.forceDirectory(directory);
        }
        @Override byte[] readFinal(Path path) throws IOException {
            if (fault.equals("REOPEN")) throw new IOException();
            return super.readFinal(path);
        }
    }
}

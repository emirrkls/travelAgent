package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static com.emirrkls.phokarta.backend.operations.PlacePilotPrivateArtifacts.require;

/** Private plain-Java main: GET and sanitized filesystem artifacts only; never starts Spring. */
public final class PlacePilotPersistentTelemetryApplication {
    private PlacePilotPersistentTelemetryApplication() {}
    public static int execute(String[] args) {
        try {
            Map<String,String> options = new HashMap<>();
            Set<String> expected = Set.of("plan-path", "plan-sha256", "output-path");
            for (String arg : args) {
                require(arg.startsWith("--") && arg.contains("="));
                String[] pair = arg.substring(2).split("=", 2);
                require(expected.contains(pair[0]) && !pair[1].isBlank() && options.putIfAbsent(pair[0], pair[1]) == null);
            }
            require(options.keySet().equals(expected));
            JsonNode plan = PlacePilotPrivateArtifacts.read(Path.of(options.get("plan-path")), options.get("plan-sha256")).document();
            PlacePilotPrivateArtifacts.fields(plan, Set.of("version", "run_id", "manifest_hash", "role", "observed_at", "target"));
            require("persistent-probe-plan-v1".equals(plan.path("version").asText()));
            Instant observed = Instant.parse(plan.path("observed_at").asText());
            require(!observed.isAfter(Instant.now()) && !observed.isBefore(Instant.now().minusSeconds(30)));
            require(PlacePilotPersistentArtifacts.validTarget(plan.path("target")));
            UUID run = UUID.fromString(plan.path("run_id").asText());
            String hash = plan.path("manifest_hash").asText(), role = plan.path("role").asText();
            // These flags belong ONLY to this external helper JVM, not beta/importer.
            // Java 21 otherwise transparently retries idempotent requests on connection expiry.
            require("true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
                    && "1".equals(System.getProperty("jdk.httpclient.redirects.retrylimit"))
                    && "false".equals(System.getProperty("jdk.httpclient.enableAllMethodRetry")));
            Path output = Path.of(options.get("output-path"));
            var probe = new PlacePilotPersistentProbe(() -> java.nio.file.Files.exists(output.getParent().resolve("STOP_PROBE.json")));
            var snapshot = "HEALTH".equals(role) ? probe.captureAttestationHealth(run,hash,plan.path("target"))
                    : probe.capture(run, hash, role, plan.path("target"));
            // Persist complete OR partial failure before validating it. Never drop a first slow/error request.
            var artifact = PlacePilotPrivateArtifacts.write(output, snapshot);
            if ("HEALTH".equals(role)) PlacePilotExecutionTarget.health(snapshot.path("health_checks"));
            else PlacePilotV3Policy.verifySnapshot(run, hash, role, artifact.document());
            require("COMPLETE".equals(snapshot.path("outcome").asText()));
            System.out.println("PERSISTENT_TELEMETRY_ARTIFACT_SHA256=" + artifact.sha256());
            return 0;
        } catch (Exception unsafe) {
            System.err.println("PERSISTENT_TELEMETRY_FAILED:INVALID_CONFIGURATION_OR_EVIDENCE");
            return 1;
        }
    }
    public static void main(String[] args) { int status = execute(args); if (status != 0) System.exit(status); }
}

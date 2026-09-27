package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotBaselineDiagnostics;
import com.emirrkls.phokarta.backend.service.PlacePilotHttpProbeService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure GET-only executable: no Spring context, datasource, Flyway, importer or worker. */
public final class PlacePilotBaselineDiagnosisApplication {
    private static final Set<String> OPTIONS = Set.of("base-url", "health-base-url", "manifest-path",
            "expected-envelope-sha256", "baseline-place-id", "output-path");
    private PlacePilotBaselineDiagnosisApplication() {}

    public static int execute(String[] args) {
        try {
            Map<String,String> options = new HashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException();
                String[] pair = arg.substring(2).split("=", 2);
                if (!OPTIONS.contains(pair[0]) || pair[1].isBlank()
                        || options.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalArgumentException();
            }
            if (!options.keySet().containsAll(Set.of("base-url", "health-base-url", "manifest-path",
                    "expected-envelope-sha256", "baseline-place-id"))) throw new IllegalArgumentException();
            Path manifestPath = Path.of(options.get("manifest-path"));
            if (!Files.isRegularFile(manifestPath) || Files.size(manifestPath) > 128L * 1024 * 1024)
                throw new IllegalArgumentException();
            byte[] bytes = Files.readAllBytes(manifestPath);
            String expectedHash = options.get("expected-envelope-sha256");
            if (!expectedHash.matches("[0-9a-f]{64}") || !expectedHash.equals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalArgumentException();
            var manifest = new ObjectMapper().readTree(bytes).path("manifest");
            var selected = manifest.path("candidates");
            var primary = java.util.stream.StreamSupport.stream(selected.spliterator(), false)
                    .filter(candidate -> candidate.path("selected_for_stage").asBoolean())
                    .min(java.util.Comparator.comparingInt(candidate -> candidate.path("selection_rank").asInt()))
                    .orElseThrow();
            var canonical = primary.path("canonical");
            var target = new PlacePilotHttpProbeService.ProbeTarget(
                    UUID.fromString(primary.path("canonical_place_id").asText()),
                    canonical.path("name").asText(), canonical.path("category").asText(),
                    canonical.path("latitude").asDouble(Double.NaN),
                    canonical.path("longitude").asDouble(Double.NaN));
            // Exact locked baseline, not a retrying/relaxed alternate probe plan.
            var config = new PlacePilotHttpProbeService.ProbeConfiguration(
                    URI.create(options.get("base-url")), URI.create(options.get("health-base-url")),
                    UUID.fromString(options.get("baseline-place-id")), 5, Duration.ofSeconds(5));
            var suite = new PlacePilotHttpProbeService(new ObjectMapper()).captureBaseline(config, target);
            var document = PlacePilotBaselineDiagnostics.document(suite, config.timeout());
            document.put("diagnosis_only", true);
            document.put("envelope_sha256_verified", true);
            PlacePilotBaselineDiagnostics.emit(document,
                    options.containsKey("output-path") ? Path.of(options.get("output-path")) : null);
            return suite.passed() ? 0 : 1;
        } catch (Exception unsafe) {
            // Never echo user input, framework exceptions, paths, URLs, payloads or secrets.
            System.err.println("PLACE_BASELINE_DIAGNOSIS_FAILED:INVALID_CONFIGURATION_OR_ARTIFACT");
            return 1;
        }
    }

    public static void main(String[] args) {
        int result = execute(args);
        if (result != 0) System.exit(result);
    }
}

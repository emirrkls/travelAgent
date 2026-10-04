package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Disabled-by-default autonomous canary entry point. Import success is never returned without
 * real HTTP probes, a catalog anomaly audit and an immutable pass/fail gate.
 */
@Component
@Profile("place-import")
@ConditionalOnProperty(prefix = "phokarta.place-import", name = "enabled", havingValue = "true")
public class PlacePilotImportRunner implements ApplicationRunner {
    private final PlacePilotAutonomousCanaryService canary;
    private final ConfigurableApplicationContext applicationContext;
    private final AtomicBoolean started = new AtomicBoolean();
    private PlacePilotAutonomousCanaryService.Configuration configuration;
    private PlacePilotAutonomousCanaryService.V3Observations persistentAdapter;

    public PlacePilotImportRunner(
            PlacePilotAutonomousCanaryService canary,
            ConfigurableApplicationContext applicationContext
    ) {
        this.canary = canary;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String manifestPath = applicationContext.getEnvironment()
                .getRequiredProperty("phokarta.place-import.manifest-path");
        String expectedManifestHash = applicationContext.getEnvironment()
                .getRequiredProperty("phokarta.place-import.expected-manifest-hash");
        String authorizationReference = applicationContext.getEnvironment()
                .getRequiredProperty("phokarta.place-import.authorization-reference");
        String diagnosticsDirectory = applicationContext.getEnvironment()
                .getRequiredProperty("phokarta.place-import.diagnostics-directory");
        if (diagnosticsDirectory.isBlank() || !Path.of(diagnosticsDirectory).isAbsolute()) {
            throw new IllegalArgumentException("PRIVATE_BASELINE_DIAGNOSTICS_DIRECTORY_REQUIRED");
        }
        String baseUrl = applicationContext.getEnvironment()
                .getRequiredProperty("phokarta.place-import.base-url");
        URI baseUri = URI.create(baseUrl);
        int runningServerPort = applicationContext.getEnvironment()
                .getRequiredProperty("local.server.port", Integer.class);
        int probePort = effectivePort(baseUri);
        if (probePort != runningServerPort) {
            throw new IllegalArgumentException(
                    "Place canary probes must target this process's local HTTP port");
        }
        String configuredContextPath = normalizeBasePath(baseUri.getRawPath());
        String serverContextPath = normalizeBasePath(applicationContext.getEnvironment()
                .getProperty("server.servlet.context-path", ""));
        if (!configuredContextPath.equals(serverContextPath)) {
            throw new IllegalArgumentException(
                    "Place canary base URL path must equal server.servlet.context-path");
        }
        int managementPort = applicationContext.getEnvironment()
                .getProperty("local.management.port", Integer.class, runningServerPort);
        String managementServerPath = managementPort == runningServerPort
                ? serverContextPath
                : normalizeBasePath(applicationContext.getEnvironment()
                        .getProperty("management.server.base-path", ""));
        String actuatorPath = normalizeBasePath(applicationContext.getEnvironment()
                .getProperty("management.endpoints.web.base-path", "/actuator"));
        URI healthBaseUri = endpointBaseUri(
                baseUri, managementPort, managementServerPath + actuatorPath);
        String baselinePlaceIdText = applicationContext.getEnvironment()
                .getProperty("phokarta.place-import.baseline-place-id", "").trim();
        UUID baselinePlaceId = baselinePlaceIdText.isEmpty()
                ? null : UUID.fromString(baselinePlaceIdText);
        int samples = applicationContext.getEnvironment()
                .getProperty("phokarta.place-import.probe-samples", Integer.class, 5);
        Duration timeout = DurationStyle.detectAndParse(
                applicationContext.getEnvironment()
                        .getProperty("phokarta.place-import.probe-timeout", "5s"));
        // ApplicationRunner precedes readiness. Probing root health here observes this
        // process's REFUSING_TRAFFIC indicator and reliably returns 503 before any import.
        // Prepare the exact same plan now, execute once only after the real readiness transition.
        configuration = new PlacePilotAutonomousCanaryService.Configuration(
                Path.of(manifestPath), expectedManifestHash, authorizationReference,
                baseUri, healthBaseUri, baselinePlaceId, samples, timeout, Path.of(diagnosticsDirectory));
        String evidenceDirectory = applicationContext.getEnvironment()
                .getProperty("phokarta.place-import.v3-evidence-directory", "");
        String planHash = applicationContext.getEnvironment()
                .getProperty("phokarta.place-import.v3-operations-plan-sha256", "");
        if (!evidenceDirectory.isBlank() || !planHash.isBlank()) {
            if (evidenceDirectory.isBlank() || !Path.of(evidenceDirectory).isAbsolute()
                    || !planHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("V3_PRIVATE_ADAPTER_CONFIGURATION_REQUIRED");
            persistentAdapter = new com.emirrkls.phokarta.backend.operations.PlacePilotPersistentOperationsAdapter(
                    Path.of(evidenceDirectory), planHash);
        }
    }

    /** Called by the operational main only after SpringApplication.run has finished readiness.
     * Even ApplicationReadyEvent listeners execute before the readiness indicator update;
     * doing this after run returns avoids event-listener ordering races and fake readiness.
     */
    public void executeAfterReady() {
        if (started.get()) return;
        if (configuration == null) throw new IllegalStateException("PLACE_CANARY_NOT_PREPARED");
        if (applicationContext.getBean(ApplicationAvailability.class).getReadinessState()
                != ReadinessState.ACCEPTING_TRAFFIC) {
            throw new IllegalStateException("PLACE_CANARY_LIFECYCLE_NOT_READY");
        }
        if (!started.compareAndSet(false,true)) return;
        PlacePilotAutonomousCanaryService.CanaryExecution result;
        try {
            result = persistentAdapter == null ? canary.run(configuration) : canary.run(configuration, persistentAdapter);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("PLACE_CANARY_EXECUTION_FAILED", failure);
        }
        System.out.printf(
                "Place autonomous canary %s: run=%s created=%d eligible=%d quarantined=%d%n",
                result.gateResult().status(), result.importResult().runId(),
                result.importResult().createdCount(),
                result.importResult().canaryEligibleCount(),
                result.importResult().quarantinedCount());
        if (!"PASSED".equals(result.gateResult().status())) {
            throw new IllegalStateException(
                    "autonomous Place canary gate FAILED and contained the imported batch");
        }
        applicationContext.close();
    }

    private int effectivePort(URI endpoint) {
        if (endpoint.getPort() >= 0) return endpoint.getPort();
        return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
    }

    private String normalizeBasePath(String value) {
        if (value == null || value.isBlank() || "/".equals(value.strip())) return "";
        String normalized = value.strip();
        if (!normalized.startsWith("/") || normalized.contains("?")
                || normalized.contains("#") || normalized.contains("//")
                || normalized.contains("/../") || normalized.endsWith("/..")
                || normalized.contains("/./") || normalized.endsWith("/.")) {
            throw new IllegalArgumentException("Place canary base path is invalid");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private URI endpointBaseUri(URI applicationBase, int port, String path) {
        try {
            return new URI(applicationBase.getScheme(), null, applicationBase.getHost(),
                    port, path.isEmpty() ? null : path, null, null);
        } catch (URISyntaxException invalidEndpoint) {
            throw new IllegalArgumentException(
                    "Place canary health endpoint could not be derived", invalidEndpoint);
        }
    }
}

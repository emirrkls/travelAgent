package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
        PlacePilotAutonomousCanaryService.CanaryExecution result = canary.run(
                new PlacePilotAutonomousCanaryService.Configuration(
                        Path.of(manifestPath), expectedManifestHash, authorizationReference,
                        baseUri, healthBaseUri, baselinePlaceId, samples, timeout));
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

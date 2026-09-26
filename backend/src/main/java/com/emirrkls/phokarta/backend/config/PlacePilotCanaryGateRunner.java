package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotCanaryGateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Disabled-by-default emergency containment entry point. It can record only FAILED; PASS is
 * reserved for the autonomous import/probe/audit orchestrator.
 */
@Component
@Profile("place-canary-gate")
@ConditionalOnProperty(prefix = "phokarta.place-canary-gate", name = "enabled",
        havingValue = "true")
public class PlacePilotCanaryGateRunner implements ApplicationRunner {
    static final long MAX_DIAGNOSTICS_BYTES = 48L * 1024L;

    private final PlacePilotCanaryGateService gates;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public PlacePilotCanaryGateRunner(
            PlacePilotCanaryGateService gates,
            ObjectMapper objectMapper,
            Environment environment
    ) {
        this.gates = gates;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        UUID syncRunId = UUID.fromString(environment.getRequiredProperty(
                "phokarta.place-canary-gate.sync-run-id"));
        String requestedPassText = environment.getRequiredProperty(
                "phokarta.place-canary-gate.requested-pass");
        if (!requestedPassText.equals("false")) {
            throw new IllegalArgumentException(
                    "manual place canary gate may only request false; PASS requires autonomous probes");
        }

        Path diagnosticsPath = Path.of(environment.getRequiredProperty(
                        "phokarta.place-canary-gate.diagnostics-path"))
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(diagnosticsPath)
                || !diagnosticsPath.getFileName().toString().endsWith(".json")) {
            throw new IllegalArgumentException(
                    "place canary gate diagnostics must be a regular JSON file");
        }
        if (Files.size(diagnosticsPath) > MAX_DIAGNOSTICS_BYTES) {
            throw new IllegalArgumentException(
                    "place canary gate diagnostics exceed the 48 KiB limit");
        }
        JsonNode diagnostics = objectMapper.readTree(Files.readAllBytes(diagnosticsPath));

        String checkedAtText = environment.getProperty(
                "phokarta.place-canary-gate.checked-at", "").trim();
        OffsetDateTime checkedAt = null;
        if (!checkedAtText.isEmpty()) {
            try {
                checkedAt = OffsetDateTime.parse(checkedAtText);
            } catch (DateTimeParseException invalidTimestamp) {
                throw new IllegalArgumentException(
                        "place canary gate checked-at must be an offset timestamp",
                        invalidTimestamp);
            }
        }

        PlacePilotCanaryGateService.GateResult result = gates.record(
                syncRunId, false, diagnostics, checkedAt);
        System.out.printf("Place pilot canary gate %s: run=%s alreadyRecorded=%s%n",
                result.status(), result.syncRunId(), result.alreadyRecorded());
        if (!"PASSED".equals(result.status())) {
            throw new IllegalStateException(
                    "place pilot canary gate recorded FAILED and contained the run");
        }
    }
}

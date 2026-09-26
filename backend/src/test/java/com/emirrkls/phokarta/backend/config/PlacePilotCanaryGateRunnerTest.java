package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotCanaryGateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacePilotCanaryGateRunnerTest {
    private static final UUID RUN_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000701");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsOperatorAuthoredPassBeforeCallingTheGate() throws Exception {
        Path diagnostics = temporaryDirectory.resolve("gate.json");
        Files.writeString(diagnostics, "{\"search_correctness\":\"PASS\"}");
        PlacePilotCanaryGateService gates = mock(PlacePilotCanaryGateService.class);
        MockEnvironment environment = environment(diagnostics)
                .withProperty("phokarta.place-canary-gate.requested-pass", "true");

        PlacePilotCanaryGateRunner runner =
                new PlacePilotCanaryGateRunner(gates, new ObjectMapper(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PASS requires autonomous probes");
        verify(gates, never()).record(any(), anyBoolean(), any(), any());
    }

    @Test
    void passesAnExplicitAuditedCheckedTime() throws Exception {
        Path diagnostics = temporaryDirectory.resolve("gate.json");
        Files.writeString(diagnostics, "{}");
        OffsetDateTime checkedAt = OffsetDateTime.parse("2026-09-26T12:00:00Z");
        PlacePilotCanaryGateService gates = mock(PlacePilotCanaryGateService.class);
        when(gates.record(eq(RUN_ID), eq(false), any(JsonNode.class), eq(checkedAt)))
                .thenReturn(new PlacePilotCanaryGateService.GateResult(
                        UUID.randomUUID(), RUN_ID, "didim-runner", "STAGE_1", "FAILED", false));
        MockEnvironment environment = environment(diagnostics)
                .withProperty("phokarta.place-canary-gate.requested-pass", "false")
                .withProperty("phokarta.place-canary-gate.checked-at", checkedAt.toString());

        PlacePilotCanaryGateRunner runner =
                new PlacePilotCanaryGateRunner(gates, new ObjectMapper(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recorded FAILED");
        verify(gates).record(eq(RUN_ID), eq(false), any(JsonNode.class), eq(checkedAt));
    }

    @Test
    void rejectsOversizedDiagnosticsBeforeCallingTheGate() throws Exception {
        Path diagnostics = temporaryDirectory.resolve("gate.json");
        Files.write(diagnostics, new byte[(int) PlacePilotCanaryGateRunner.MAX_DIAGNOSTICS_BYTES + 1]);
        PlacePilotCanaryGateService gates = mock(PlacePilotCanaryGateService.class);

        PlacePilotCanaryGateRunner runner = new PlacePilotCanaryGateRunner(
                gates, new ObjectMapper(), environment(diagnostics)
                        .withProperty("phokarta.place-canary-gate.requested-pass", "false"));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("48 KiB");
        verify(gates, never()).record(any(), eq(false), any(), any());
    }

    private MockEnvironment environment(Path diagnostics) {
        return new MockEnvironment()
                .withProperty("phokarta.place-canary-gate.sync-run-id", RUN_ID.toString())
                .withProperty("phokarta.place-canary-gate.diagnostics-path", diagnostics.toString());
    }
}

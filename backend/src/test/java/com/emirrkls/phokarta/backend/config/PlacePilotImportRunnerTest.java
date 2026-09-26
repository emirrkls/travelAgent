package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService;
import com.emirrkls.phokarta.backend.service.PlacePilotCanaryGateService;
import com.emirrkls.phokarta.backend.service.PlacePilotImportService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacePilotImportRunnerTest {
    private static final UUID RUN_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000701");
    private static final UUID BASELINE_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000702");
    private static final String HASH = "b".repeat(64);

    @Test
    void delegatesTheOnlySuccessCapableProfileToTheAutonomousOrchestrator() throws Exception {
        PlacePilotAutonomousCanaryService canary = mock(PlacePilotAutonomousCanaryService.class);
        ConfigurableApplicationContext context = context();
        when(canary.run(any())).thenReturn(execution("PASSED"));
        PlacePilotImportRunner runner = new PlacePilotImportRunner(canary, context);

        runner.run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<PlacePilotAutonomousCanaryService.Configuration> configuration =
                ArgumentCaptor.forClass(PlacePilotAutonomousCanaryService.Configuration.class);
        verify(canary).run(configuration.capture());
        assertThat(configuration.getValue().expectedManifestHash()).isEqualTo(HASH);
        assertThat(configuration.getValue().authorizationReference()).isEqualTo("AUTH-REF");
        assertThat(configuration.getValue().baseUrl().toString())
                .isEqualTo("http://127.0.0.1:8181");
        assertThat(configuration.getValue().healthBaseUrl().toString())
                .isEqualTo("http://127.0.0.1:8281/actuator");
        assertThat(configuration.getValue().baselinePlaceId()).isEqualTo(BASELINE_ID);
        assertThat(configuration.getValue().sampleCount()).isEqualTo(7);
        assertThat(configuration.getValue().timeout()).isEqualTo(Duration.ofSeconds(4));
        verify(context).close();
    }

    @Test
    void exitsAsFailureWhenTheAutonomousGateContainsTheBatch() throws Exception {
        PlacePilotAutonomousCanaryService canary = mock(PlacePilotAutonomousCanaryService.class);
        when(canary.run(any())).thenReturn(execution("FAILED"));
        ConfigurableApplicationContext context = context();
        PlacePilotImportRunner runner = new PlacePilotImportRunner(canary, context);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contained");
        verify(context, never()).close();
    }

    @Test
    void rejectsAProbePortThatDoesNotBelongToThisBackendProcess() {
        PlacePilotAutonomousCanaryService canary = mock(PlacePilotAutonomousCanaryService.class);
        ConfigurableApplicationContext context = context();
        ((MockEnvironment) context.getEnvironment())
                .setProperty("local.server.port", "8282");
        PlacePilotImportRunner runner = new PlacePilotImportRunner(canary, context);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("this process's local HTTP port");
    }

    @Test
    void requiresTheConfiguredBasePathToMatchTheRunningServletContext() throws Exception {
        PlacePilotAutonomousCanaryService canary = mock(PlacePilotAutonomousCanaryService.class);
        ConfigurableApplicationContext context = context();
        MockEnvironment environment = (MockEnvironment) context.getEnvironment();
        environment.setProperty("server.servlet.context-path", "/phokarta");
        PlacePilotImportRunner runner = new PlacePilotImportRunner(canary, context);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.servlet.context-path");
        verify(canary, never()).run(any());
        verify(context, never()).close();
    }

    @Test
    void preservesMatchingApplicationAndManagementBasePaths() throws Exception {
        PlacePilotAutonomousCanaryService canary = mock(PlacePilotAutonomousCanaryService.class);
        ConfigurableApplicationContext context = context();
        MockEnvironment environment = (MockEnvironment) context.getEnvironment();
        environment.setProperty("phokarta.place-import.base-url",
                "http://127.0.0.1:8181/phokarta");
        environment.setProperty("server.servlet.context-path", "/phokarta");
        environment.setProperty("management.server.base-path", "/management");
        environment.setProperty("management.endpoints.web.base-path", "/ops");
        when(canary.run(any())).thenReturn(execution("PASSED"));
        PlacePilotImportRunner runner = new PlacePilotImportRunner(canary, context);

        runner.run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<PlacePilotAutonomousCanaryService.Configuration> configuration =
                ArgumentCaptor.forClass(PlacePilotAutonomousCanaryService.Configuration.class);
        verify(canary).run(configuration.capture());
        assertThat(configuration.getValue().baseUrl().toString())
                .isEqualTo("http://127.0.0.1:8181/phokarta");
        assertThat(configuration.getValue().healthBaseUrl().toString())
                .isEqualTo("http://127.0.0.1:8281/management/ops");
        verify(context).close();
    }

    private ConfigurableApplicationContext context() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("phokarta.place-import.manifest-path", "canary.json")
                .withProperty("phokarta.place-import.expected-manifest-hash", HASH)
                .withProperty("phokarta.place-import.authorization-reference", "AUTH-REF")
                .withProperty("phokarta.place-import.base-url", "http://127.0.0.1:8181")
                .withProperty("local.server.port", "8181")
                .withProperty("local.management.port", "8281")
                .withProperty("phokarta.place-import.baseline-place-id", BASELINE_ID.toString())
                .withProperty("phokarta.place-import.probe-samples", "7")
                .withProperty("phokarta.place-import.probe-timeout", "4s");
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }

    private PlacePilotAutonomousCanaryService.CanaryExecution execution(String gateStatus) {
        PlacePilotImportService.ImportResult importResult =
                new PlacePilotImportService.ImportResult(
                        RUN_ID, false, "SUCCEEDED", 1, 1, 0,
                        1, 0, 0, 0, 0, 1);
        PlacePilotCanaryGateService.GateResult gate =
                new PlacePilotCanaryGateService.GateResult(
                        UUID.randomUUID(), RUN_ID, "didim-run", "STAGE_1",
                        gateStatus, false);
        return new PlacePilotAutonomousCanaryService.CanaryExecution(
                importResult, gate, JsonNodeFactory.instance.objectNode());
    }
}

package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService;
import com.emirrkls.phokarta.backend.service.PlacePilotCanaryGateService;
import com.emirrkls.phokarta.backend.service.PlacePilotImportService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
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
        runner.executeAfterReady();

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

        runner.run(new DefaultApplicationArguments(new String[0]));
        assertThatThrownBy(runner::executeAfterReady)
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
        runner.executeAfterReady();

        ArgumentCaptor<PlacePilotAutonomousCanaryService.Configuration> configuration =
                ArgumentCaptor.forClass(PlacePilotAutonomousCanaryService.Configuration.class);
        verify(canary).run(configuration.capture());
        assertThat(configuration.getValue().baseUrl().toString())
                .isEqualTo("http://127.0.0.1:8181/phokarta");
        assertThat(configuration.getValue().healthBaseUrl().toString())
                .isEqualTo("http://127.0.0.1:8281/management/ops");
        verify(context).close();
    }


    @Test
    void preparationNeverExecutesBeforeReadinessAndUnreadyInvocationFailsClosed() throws Exception {
        var canary = mock(PlacePilotAutonomousCanaryService.class);
        var context = context();
        when(context.getBean(ApplicationAvailability.class).getReadinessState())
                .thenReturn(ReadinessState.REFUSING_TRAFFIC);
        var runner = new PlacePilotImportRunner(canary, context);
        runner.run(new DefaultApplicationArguments(new String[0]));
        verify(canary, never()).run(any());
        assertThatThrownBy(runner::executeAfterReady).hasMessage("PLACE_CANARY_LIFECYCLE_NOT_READY");
        verify(canary, never()).run(any());
    }

    @Test
    void lockedDefaultsAndExecutionOnceOnlyArePreserved() throws Exception {
        var canary = mock(PlacePilotAutonomousCanaryService.class);
        var context = context();
        var environment = (MockEnvironment) context.getEnvironment();
        environment.setProperty("phokarta.place-import.probe-samples", "5");
        environment.setProperty("phokarta.place-import.probe-timeout", "5s");
        when(canary.run(any())).thenReturn(execution("PASSED"));
        var runner = new PlacePilotImportRunner(canary, context);
        runner.run(new DefaultApplicationArguments(new String[0]));
        runner.executeAfterReady();
        runner.executeAfterReady();
        var configuration = ArgumentCaptor.forClass(PlacePilotAutonomousCanaryService.Configuration.class);
        verify(canary).run(configuration.capture());
        assertThat(configuration.getValue().sampleCount()).isEqualTo(5);
        assertThat(configuration.getValue().timeout()).isEqualTo(Duration.ofSeconds(5));
        verify(context).close();
    }

    @Test
    void unpreparedAndFailedExecutionsCannotBeRetried() throws Exception {
        var canary = mock(PlacePilotAutonomousCanaryService.class);
        var runner = new PlacePilotImportRunner(canary, context());
        assertThatThrownBy(runner::executeAfterReady).hasMessage("PLACE_CANARY_NOT_PREPARED");
        runner.run(new DefaultApplicationArguments(new String[0]));
        when(canary.run(any())).thenThrow(new IllegalStateException("PREIMPORT_HTTP_BASELINE_UNHEALTHY"));
        assertThatThrownBy(runner::executeAfterReady).hasMessage("PREIMPORT_HTTP_BASELINE_UNHEALTHY");
        runner.executeAfterReady();
        verify(canary).run(any());
    }

    private ConfigurableApplicationContext context() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("phokarta.place-import.manifest-path", "canary.json")
                .withProperty("phokarta.place-import.diagnostics-directory",
                        java.nio.file.Path.of("private-test-diagnostics").toAbsolutePath().toString())
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
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(context.getBean(ApplicationAvailability.class)).thenReturn(availability);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        return context;
    }

    @Test
    void missingPrivateDiagnosticsDirectoryFailsBeforeCanaryExecution() throws Exception {
        var canary = mock(PlacePilotAutonomousCanaryService.class);
        var context = context();
        ((MockEnvironment) context.getEnvironment()).setProperty("phokarta.place-import.diagnostics-directory", "");
        var runner = new PlacePilotImportRunner(canary, context);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .hasMessage("PRIVATE_BASELINE_DIAGNOSTICS_DIRECTORY_REQUIRED");
        verify(canary, never()).run(any());
    }

    @Test void privateV3EvidenceConfigurationWiresTheConcreteAdapterNotAPolicyOverride() throws Exception {
        var canary = mock(PlacePilotAutonomousCanaryService.class); var context = context();
        var env = (MockEnvironment) context.getEnvironment();
        env.setProperty("phokarta.place-import.v3-evidence-directory", java.nio.file.Path.of("private-v3-evidence").toAbsolutePath().toString());
        env.setProperty("phokarta.place-import.v3-operations-plan-sha256", "1".repeat(64));
        when(canary.run(any(), any())).thenReturn(execution("PASSED"));
        var runner = new PlacePilotImportRunner(canary, context); runner.run(new DefaultApplicationArguments(new String[0])); runner.executeAfterReady();
        var adapter = ArgumentCaptor.forClass(PlacePilotAutonomousCanaryService.V3Observations.class);
        verify(canary).run(any(), adapter.capture());
        assertThat(adapter.getValue()).isInstanceOf(com.emirrkls.phokarta.backend.operations.PlacePilotPersistentOperationsAdapter.class);
        verify(canary, never()).run(any());
    }
    @Test void incompletePrivateV3ConfigurationFailsBeforeExecution() throws Exception {
        var canary = mock(PlacePilotAutonomousCanaryService.class); var context = context();
        ((MockEnvironment) context.getEnvironment()).setProperty("phokarta.place-import.v3-operations-plan-sha256", "1".repeat(64));
        var runner = new PlacePilotImportRunner(canary, context);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0]))).hasMessage("V3_PRIVATE_ADAPTER_CONFIGURATION_REQUIRED");
        verify(canary, never()).run(any());
        verify(canary, never()).run(any(), any());
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

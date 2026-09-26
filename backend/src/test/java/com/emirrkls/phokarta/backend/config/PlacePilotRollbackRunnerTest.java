package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacePilotRollbackRunnerTest {
    private static final UUID RUN = UUID.fromString("d70adea5-6e3f-4c32-92c0-49695eeeb9ce");
    private static final String HASH = "a".repeat(64);

    @Test
    void defaultModeInspectsAndClosesTheOneShotContext() throws Exception {
        var operations = mock(PlacePilotRollbackOperationsService.class);
        var context = context(environment());
        when(operations.inspect(RUN, HASH)).thenReturn(result());
        new PlacePilotRollbackRunner(operations, context, new ObjectMapper())
                .run(new DefaultApplicationArguments(new String[0]));
        verify(operations).inspect(RUN, HASH);
        verify(operations, never()).execute(any(), any(), any());
        verify(context).close();
    }

    @Test
    void invalidOrConflictingModesNeverCallTheOperation() {
        for (String[] modes : new String[][]{{"true", "true"}, {"false", "false"}, {"yes", "false"}}) {
            var operations = mock(PlacePilotRollbackOperationsService.class);
            var env = environment().withProperty("phokarta.place-rollback.dry-run", modes[0])
                    .withProperty("phokarta.place-rollback.execute", modes[1]);
            assertThatThrownBy(() -> new PlacePilotRollbackRunner(operations, context(env), new ObjectMapper())
                    .run(new DefaultApplicationArguments(new String[0])))
                    .hasMessage("PLACE_ROLLBACK_FAILED:INVALID_MODE");
            verifyNoInteractions(operations);
        }
    }

    @Test
    void refusesNoncanonicalUuidAndSanitizesRawFailure() {
        var operations = mock(PlacePilotRollbackOperationsService.class);
        var env = environment().withProperty("phokarta.place-rollback.sync-run-id", RUN.toString().toUpperCase());
        assertThatThrownBy(() -> new PlacePilotRollbackRunner(operations, context(env), new ObjectMapper())
                .run(new DefaultApplicationArguments(new String[0])))
                .hasMessage("PLACE_ROLLBACK_FAILED:INVALID_INPUT");
        verifyNoInteractions(operations);
        when(operations.inspect(RUN, HASH)).thenThrow(new IllegalStateException("jdbc:password=secret"));
        assertThatThrownBy(() -> new PlacePilotRollbackRunner(operations, context(environment()), new ObjectMapper())
                .run(new DefaultApplicationArguments(new String[0])))
                .hasMessage("PLACE_ROLLBACK_FAILED:INVALID_CONFIGURATION").hasNoCause();
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("phokarta.place-rollback.sync-run-id", RUN.toString())
                .withProperty("phokarta.place-rollback.expected-manifest-hash", HASH);
    }
    private static ConfigurableApplicationContext context(MockEnvironment env) {
        var context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(env);
        return context;
    }
    private static PlacePilotRollbackOperationsService.OperationResult result() {
        var plan = new PlacePilotRollbackService.RollbackInspection(RUN, "didim-core-unit-test",
                "STAGE_1", "SUCCEEDED", HASH, false, 1, 2, 2, 2, 2, 0,
                1, 0, 0, 1, Set.of(RUN), List.of(), true);
        return new PlacePilotRollbackOperationsService.OperationResult(RUN, "INSPECTED", true, false, plan, null);
    }
}

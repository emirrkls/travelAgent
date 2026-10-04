package com.emirrkls.phokarta.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlacePilotV3ContainmentServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PlacePilotRollbackService rollback=mock(PlacePilotRollbackService.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final UUID run=UUID.fromString("10000000-0000-4000-8000-000000000099");
    private final String hash="a".repeat(64);
    PlacePilotV3ContainmentService service() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new PlacePilotV3ContainmentService(jdbc,rollback,manager);
    }
    @Test void inspectionUsesGenuineReadOnlyRepeatableReadAndNeverWritesEvenForWrongIdentity() {
        var service=service();
        assertThatThrownBy(()->service.inspect(PlacePilotV3Policy.CONTAINED_RUN,hash))
                .hasMessage("V3_CONTAINMENT_OWNER_INTERVENTION_REQUIRED");
        var definition=ArgumentCaptor.forClass(TransactionDefinition.class); verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verifyNoInteractions(jdbc,rollback);
    }
    @Test void noFailedGateMeansNoContainmentRequestOrRetirement() {
        var service=service();
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(run))).thenReturn(0L);
        assertThatThrownBy(()->service.contain(run,hash)).hasMessage("V3_CONTAINMENT_OWNER_INTERVENTION_REQUIRED");
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verifyNoInteractions(rollback);
    }
    @Test void invalidInspectionLeavesRequestCommittedAndNeverFallsBackToRetireRun() {
        var service=service();
        when(jdbc.queryForObject(startsWith("SELECT count(*) FROM place_pilot_canary_gates"),eq(Long.class),eq(run))).thenReturn(1L);
        when(jdbc.queryForObject(startsWith("SELECT count(*) FROM place_provider_sync_runs"),eq(Long.class),eq(run),eq(hash),eq(PlacePilotV3Policy.V3))).thenReturn(1L);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(contains("current_schema()"),eq(Boolean.class))).thenReturn(false);
        assertThatThrownBy(()->service.contain(run,hash)).hasMessage("SCHEMA_INCOMPATIBLE");
        var definitions=ArgumentCaptor.forClass(TransactionDefinition.class); verify(manager,times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues().get(0).isReadOnly()).isFalse();
        assertThat(definitions.getAllValues().get(1).isReadOnly()).isTrue();
        verify(manager,times(1)).commit(any()); // request commits independently of rejected read-only inspection
        verify(jdbc,times(1)).update(anyString(),any(Object[].class)); verifyNoInteractions(rollback);
    }
}

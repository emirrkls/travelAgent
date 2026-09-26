package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Renews the bounded post-import probe lease and contains successful imports that never reach
 * an immutable gate result. Every decision is rechecked under the same pilot advisory lock used
 * by imports and gates, so a live renewal, a PASS, and timeout containment are serialized.
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlacePilotGateReconciliationService implements ApplicationRunner {
    private static final Logger log =
            LoggerFactory.getLogger(PlacePilotGateReconciliationService.class);
    private static final int RECONCILIATION_BATCH_SIZE = 50;
    private static final long PERFORMANCE_REQUESTS_PER_SAMPLE = 5L;
    private static final long CORRECTNESS_REQUESTS_PER_SELECTED_PLACE = 4L;
    private static final Duration PROBE_COMPLETION_MARGIN = Duration.ofMinutes(5);
    private static final Duration MAX_GATE_DEADLINE_LEASE = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final PlacePilotCanaryGateService gates;
    private final TransactionTemplate transactions;

    public PlacePilotGateReconciliationService(
            JdbcTemplate jdbc,
            PlacePilotCanaryGateService gates,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.gates = gates;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Extends an unexpired, ungated successful run immediately before its bounded HTTP probes.
     * The durable window is derived from all sequential performance and selected-Place
     * correctness requests at their configured timeout, plus a fixed completion margin.
     */
    public LeaseResult renewLeaseForProbes(
            UUID syncRunId,
            int selectedCount,
            int sampleCount,
            Duration requestTimeout
    ) {
        Duration requiredLease = requiredProbeLease(
                selectedCount, sampleCount, requestTimeout);
        LeaseResult result = transactions.execute(transaction -> {
            List<LeaseIdentity> rows = jdbc.query("""
                    SELECT pilot_run_key
                      FROM place_provider_sync_runs
                     WHERE id = ? AND status = 'SUCCEEDED'
                       AND canary_stage <> 'DRY_RUN'
                    """, (rs, rowNum) -> new LeaseIdentity(
                    rs.getString("pilot_run_key")), syncRunId);
            if (rows.size() != 1) {
                throw new IllegalArgumentException(
                        "gate lease renewal requires a successful canary run");
            }
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                    (rs, rowNum) -> rs.getObject(1), rows.getFirst().pilotRunKey());
            int updated = jdbc.update("""
                    UPDATE place_provider_sync_runs
                       SET gate_deadline = GREATEST(
                           gate_deadline,
                           clock_timestamp() + (? * interval '1 millisecond'))
                     WHERE id = ? AND status = 'SUCCEEDED'
                       AND canary_stage <> 'DRY_RUN'
                       AND gate_deadline > clock_timestamp()
                       AND NOT EXISTS (
                           SELECT 1 FROM place_pilot_canary_gates gate
                            WHERE gate.sync_run_id = place_provider_sync_runs.id
                       )
                    """, requiredLease.toMillis(), syncRunId);
            if (updated != 1) {
                throw new IllegalStateException(
                        "gate lease cannot be renewed after expiry or final gate recording");
            }
            OffsetDateTime deadline = jdbc.queryForObject("""
                    SELECT gate_deadline FROM place_provider_sync_runs WHERE id = ?
                    """, OffsetDateTime.class, syncRunId);
            if (deadline == null) {
                throw new IllegalStateException("renewed gate lease has no durable deadline");
            }
            return new LeaseResult(syncRunId, deadline);
        });
        if (result == null) {
            throw new IllegalStateException("gate lease renewal transaction returned no result");
        }
        return result;
    }

    /** Reconciles the full expired backlog in bounded, independently committed batches. */
    public ReconciliationResult reconcileExpired() {
        int candidateCount = 0;
        int contained = 0;
        while (true) {
            List<ExpiredRun> candidates = jdbc.query("""
                    SELECT run.id, run.pilot_run_key, run.gate_deadline
                      FROM place_provider_sync_runs run
                     WHERE run.status = 'SUCCEEDED'
                       AND run.canary_stage <> 'DRY_RUN'
                       AND run.gate_deadline <= clock_timestamp()
                       AND NOT EXISTS (
                           SELECT 1 FROM place_pilot_canary_gates gate
                            WHERE gate.sync_run_id = run.id
                       )
                     ORDER BY run.gate_deadline, run.id
                     LIMIT ?
                    """, (rs, rowNum) -> new ExpiredRun(
                    rs.getObject("id", UUID.class), rs.getString("pilot_run_key"),
                    rs.getObject("gate_deadline", OffsetDateTime.class)),
                    RECONCILIATION_BATCH_SIZE);
            candidateCount += candidates.size();
            for (ExpiredRun candidate : candidates) {
                Boolean reconciled = transactions.execute(
                        transaction -> reconcileLocked(candidate));
                if (Boolean.TRUE.equals(reconciled)) contained++;
            }
            if (candidates.size() < RECONCILIATION_BATCH_SIZE) break;
        }
        return new ReconciliationResult(candidateCount, contained);
    }

    private boolean reconcileLocked(ExpiredRun candidate) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                (rs, rowNum) -> rs.getObject(1), candidate.pilotRunKey());
        Integer stillExpired = jdbc.queryForObject("""
                SELECT count(*)
                  FROM place_provider_sync_runs run
                 WHERE run.id = ? AND run.status = 'SUCCEEDED'
                   AND run.canary_stage <> 'DRY_RUN'
                   AND run.gate_deadline <= clock_timestamp()
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_canary_gates gate
                        WHERE gate.sync_run_id = run.id
                   )
                """, Integer.class, candidate.syncRunId());
        if (!Integer.valueOf(1).equals(stillExpired)) return false;

        ObjectNode diagnostics = JsonNodeFactory.instance.objectNode();
        diagnostics.put("probe_mode", "AUTOMATED_GATE_DEADLINE_RECONCILIATION_V1");
        diagnostics.put("measured_pass", false);
        diagnostics.put("gate_failure_reason", "GATE_DEADLINE_EXPIRED_WITHOUT_RESULT");
        diagnostics.put("gate_deadline", candidate.gateDeadline().toString());
        PlacePilotCanaryGateService.GateResult gate =
                gates.record(candidate.syncRunId(), false, diagnostics, null);
        if (!"FAILED".equals(gate.status())) {
            throw new IllegalStateException(
                    "expired canary reconciliation did not record a failed gate");
        }
        return true;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        ReconciliationResult result = reconcileExpired();
        if (result.containedCount() > 0) {
            log.warn("Contained {} expired Place canary run(s) during startup reconciliation",
                    result.containedCount());
        }
    }

    @Scheduled(
            fixedDelayString = "${phokarta.place-import.gate-reconciliation-interval:1m}",
            initialDelayString = "${phokarta.place-import.gate-reconciliation-interval:1m}")
    public void reconcileScheduled() {
        ReconciliationResult result = reconcileExpired();
        if (result.containedCount() > 0) {
            log.warn("Contained {} expired Place canary run(s)", result.containedCount());
        }
    }

    static Duration requiredProbeLease(
            int selectedCount,
            int sampleCount,
            Duration requestTimeout
    ) {
        if (selectedCount < 1 || sampleCount < 1 || sampleCount > 20
                || requestTimeout == null
                || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "Place canary probe deadline inputs are outside their enforced bounds");
        }
        try {
            long requests = Math.addExact(
                    Math.multiplyExact(PERFORMANCE_REQUESTS_PER_SAMPLE, sampleCount),
                    Math.multiplyExact(CORRECTNESS_REQUESTS_PER_SELECTED_PLACE,
                            (long) selectedCount));
            Duration required = requestTimeout.multipliedBy(requests)
                    .plus(PROBE_COMPLETION_MARGIN);
            if (required.compareTo(MAX_GATE_DEADLINE_LEASE) > 0) {
                throw new IllegalArgumentException(
                        "bounded Place canary probes exceed the 24-hour gate deadline limit");
            }
            return required;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Place canary probe deadline calculation overflowed", overflow);
        }
    }

    private record LeaseIdentity(String pilotRunKey) {}

    private record ExpiredRun(
            UUID syncRunId,
            String pilotRunKey,
            OffsetDateTime gateDeadline
    ) {}

    public record LeaseResult(UUID syncRunId, OffsetDateTime gateDeadline) {}

    public record ReconciliationResult(int candidateCount, int containedCount) {}
}

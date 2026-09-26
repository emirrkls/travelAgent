package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.service.PlacePilotRollbackOperationsService;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Private one-shot runner. It prints only bounded, sanitized operational information. */
@Component
@Profile("place-rollback")
@ConditionalOnProperty(prefix = "phokarta.place-rollback", name = "enabled", havingValue = "true")
public class PlacePilotRollbackRunner implements ApplicationRunner {
    private final PlacePilotRollbackOperationsService operations;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper mapper;

    public PlacePilotRollbackRunner(PlacePilotRollbackOperationsService operations,
            ConfigurableApplicationContext context, ObjectMapper mapper) {
        this.operations = operations;
        this.context = context;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            Environment env = context.getEnvironment();
            boolean dryRun = strictBoolean(env, "dry-run", "true");
            boolean execute = strictBoolean(env, "execute", "false");
            if (dryRun == execute) throw new PlacePilotRollbackOperationsService.OperationalFailure("INVALID_MODE");
            String runText = env.getRequiredProperty("phokarta.place-rollback.sync-run-id");
            UUID runId = UUID.fromString(runText);
            if (!runId.toString().equals(runText)) {
                throw new PlacePilotRollbackOperationsService.OperationalFailure("INVALID_INPUT");
            }
            String hash = env.getRequiredProperty("phokarta.place-rollback.expected-manifest-hash");
            PlacePilotRollbackOperationsService.OperationResult result = dryRun
                    ? operations.inspect(runId, hash)
                    : operations.execute(runId, hash,
                    PlacePilotRollbackOperationsService.Reason.valueOf(env.getProperty(
                            "phokarta.place-rollback.reason-code", "")));
            ObjectNode output = mapper.createObjectNode();
            output.put("run_id", result.syncRunId().toString());
            output.put("result", result.result());
            output.put("dry_run", result.dryRun());
            output.put("already_contained", result.alreadyContained());
            PlacePilotRollbackService.RollbackInspection plan = result.inspection();
            output.put("canonical_places_created", plan.createdPlaceCount());
            output.put("source_observations", plan.sourceObservationCount());
            output.put("source_observations_affected", plan.affectedSourceObservationCount());
            output.put("external_references", plan.externalReferenceCount());
            output.put("external_references_affected", plan.referencesToContainCount());
            output.put("graph_protected_places", plan.graphProtectedCount());
            output.put("eligible_retirement_places", plan.eligibleRetirementCount());
            output.put("retained_graph_safe_places", plan.retainedGraphSafeCount());
            output.put("pending_writes", plan.pendingWriteCount());
            if (result.rollbackResult() != null) {
                output.put("retired_count", result.rollbackResult().retiredCount());
                output.put("graph_protected_count", result.rollbackResult().graphProtectedCount());
                output.put("contained_by_newer_references_count",
                        result.rollbackResult().containedByNewerReferencesCount());
            }
            var places = output.putArray("canonical_places");
            for (PlacePilotRollbackService.PlaceRollbackInspection place : plan.places()) {
                places.addObject().put("place_id", place.placeId().toString())
                        .put("expected_final_state", place.expectedFinalState())
                        .put("graph_protected", place.graphProtected());
            }
            com.emirrkls.phokarta.backend.operations.PlacePilotRollbackApplication
                    .emitOperationalResult(mapper.writeValueAsString(output));
            context.close();
        } catch (Exception unsafe) {
            String category = unsafe instanceof PlacePilotRollbackOperationsService.OperationalFailure safe
                    ? safe.category() : "INVALID_CONFIGURATION";
            // Never attach an SQL/configuration exception whose message can contain credentials.
            throw new IllegalStateException("PLACE_ROLLBACK_FAILED:" + category);
        }
    }

    private static boolean strictBoolean(Environment env, String name, String fallback) {
        String value = env.getProperty("phokarta.place-rollback." + name, fallback);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new PlacePilotRollbackOperationsService.OperationalFailure("INVALID_MODE");
        }
        return "true".equals(value);
    }
}

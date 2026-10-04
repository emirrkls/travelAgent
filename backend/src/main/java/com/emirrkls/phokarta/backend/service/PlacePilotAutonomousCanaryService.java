package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One-shot autonomous canary workflow. No successful operational import path bypasses this
 * service: it captures a before-state, imports, probes, audits and records the immutable gate.
 */
@Service
public class PlacePilotAutonomousCanaryService {
    private static final long MAX_MANIFEST_BYTES = 128L * 1024L * 1024L;
    private static final double MAX_RELATIVE_REGRESSION = 0.10;
    private static final int MAX_FAILURE_TEXT = 300;

    private final PlacePilotImportService importer;
    private final PlacePilotCanaryGateService gates;
    private final PlacePilotCatalogAnomalyService anomalies;
    private final PlacePilotHttpProbeService probes;
    private final PlacePilotGateReconciliationService gateReconciliation;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PlacePilotBaselineArtifactService baselineArtifacts;

    public PlacePilotAutonomousCanaryService(
            PlacePilotImportService importer,
            PlacePilotCanaryGateService gates,
            PlacePilotCatalogAnomalyService anomalies,
            PlacePilotHttpProbeService probes,
            PlacePilotGateReconciliationService gateReconciliation,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlacePilotBaselineArtifactService baselineArtifacts
    ) {
        this.importer = importer;
        this.gates = gates;
        this.anomalies = anomalies;
        this.probes = probes;
        this.gateReconciliation = gateReconciliation;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.baselineArtifacts = baselineArtifacts;
    }

    public CanaryExecution run(Configuration configuration) throws IOException {
        return run(configuration, null);
    }

    /** Private operations evidence boundary. Never exposed through a public API. */
    public interface V3Observations {
        default void bindAuthorization(String reference) throws IOException {}
        default void productSafety(UUID run, String hash, ObjectNode proof) throws IOException {}
        default void finalHealth(UUID run, String hash) throws IOException {}
        default void preflight(UUID runId, String manifestHash,
                               List<PlacePilotHttpProbeService.ProbeTarget> targets) throws IOException {}
        /** GET-only external process against the persistent backend; sanitized artifact independently read back. */
        JsonNode capturePersistent(String role, UUID runId, String manifestHash) throws IOException;
        /** Existing authorized product workflow, not an attestation that relative latency passed. */
        ObjectNode productAcceptance(UUID runId, String manifestHash,
                                     List<PlacePilotHttpProbeService.ProbeTarget> targets) throws IOException;
    }

    public CanaryExecution run(Configuration configuration, V3Observations v3Observations) throws IOException {
        configuration.validate();
        ReadEnvelope sealedEnvelope = readEnvelope(configuration.manifestPath());
        JsonNode envelope = sealedEnvelope.document();
        JsonNode manifest = requireObject(envelope, "manifest");
        boolean v3 = PlacePilotV3Policy.isV3(manifest);
        if (v3 && v3Observations == null) {
            throw new IllegalArgumentException("v3 requires an explicitly approved persistent/product operations adapter; no one-shot fallback");
        }
        String envelopeHash = requiredText(envelope, "manifest_hash");
        if (!configuration.expectedManifestHash().equals(envelopeHash)
                || !envelopeHash.equals(importer.hashManifest(manifest))) {
            throw new IllegalArgumentException(
                    "autonomous canary manifest does not match the authorized digest");
        }
        if (!configuration.authorizationReference().equals(
                requiredText(manifest, "authorization_reference"))) {
            throw new IllegalArgumentException(
                    "autonomous canary authorization reference does not match the manifest");
        }
        UUID runId = UUID.fromString(requiredText(manifest, "run_id"));
        PlacePilotSourceAccounting.Approved approved = importer.validateApprovedAccounting(
                envelope, configuration.expectedManifestHash(), configuration.authorizationReference());
        List<PlacePilotHttpProbeService.ProbeTarget> selectedTargets = selectedTargets(manifest);
        if (v3) {
            if (!Duration.ofSeconds(5).equals(configuration.timeout())) throw new IllegalArgumentException("v3 hard deadline is five seconds");
            v3Observations.bindAuthorization(configuration.authorizationReference());
            v3Observations.preflight(runId, envelopeHash, selectedTargets);
        }
        // Refuse an unfinishable probe plan before any canary write becomes public. The same
        // derived budget is persisted as a renewed deadline immediately before the probes.
        PlacePilotGateReconciliationService.requiredProbeLease(
                selectedTargets.size(), configuration.sampleCount(), configuration.timeout());
        UUID baselinePlaceId = resolveBaselinePlace(configuration.baselinePlaceId());
        PlacePilotHttpProbeService.ProbeConfiguration probeConfiguration =
                new PlacePilotHttpProbeService.ProbeConfiguration(
                        configuration.baseUrl(), configuration.healthBaseUrl(), baselinePlaceId,
                        configuration.sampleCount(), configuration.timeout());

        PlacePilotCatalogAnomalyService.CatalogSnapshot baselineCatalog =
                anomalies.captureDidimBaseline();
        UUID baselineExecutionId = UUID.randomUUID();
        Instant baselineStartedAt = Instant.now();
        PlacePilotHttpProbeService.ProbeSuite baselineHttp =
                probes.captureBaseline(probeConfiguration, selectedTargets.getFirst());
        var baselineIdentity = new PlacePilotBaselineArtifactService.Identity(
                baselineExecutionId, runId, envelopeHash, sealedEnvelope.sha256(),
                requiredText(manifest, "method_version"), baselineStartedAt, Instant.now());
        // Mandatory filesystem gate for both outcomes. It forces/closes/finalizes, independently
        // reopens and verifies all 25 persisted records. No importer call may precede its return.
        var verifiedBaseline = baselineArtifacts.persistAndVerify(
                configuration.diagnosticsDirectory(), baselineHttp, baselineIdentity, configuration.timeout());
        if (!baselineHttp.passed()) {
            ObjectNode diagnostics = PlacePilotBaselineArtifactService.document(
                    baselineHttp, baselineIdentity, configuration.timeout());
            throw new PlacePilotBaselineDiagnostics.BaselineFailure(diagnostics);
        }
        JsonNode persistentBefore = v3 ? v3Observations.capturePersistent("PRE", runId, envelopeHash) : null;
        if (v3) PlacePilotV3Policy.verifyFreshPre(runId, envelopeHash, persistentBefore, Instant.now());
        if (v3) return runV3(configuration, v3Observations, runId, envelopeHash, approved, selectedTargets,
                baselineHttp, baselineCatalog, baselinePlaceId, verifiedBaseline, persistentBefore, probeConfiguration);

        PlacePilotImportService.ImportResult imported = null;
        boolean importCommitted = false;
        try {
            imported = importer.importApproved(configuration.manifestPath(),
                    configuration.expectedManifestHash(),
                    configuration.authorizationReference());
            importCommitted = true;
            if (imported.alreadyImported()) {
                throw new IllegalStateException(
                        "an existing import cannot establish a trustworthy pre-import baseline");
            }
            boolean idempotent = false;
            String idempotencyFailure = null;
            try {
                PlacePilotImportService.ImportResult replay = importer.importApproved(
                        configuration.manifestPath(), configuration.expectedManifestHash(),
                        configuration.authorizationReference());
                idempotent = replay.alreadyImported()
                        && replay.runId().equals(imported.runId())
                        && "SUCCEEDED".equals(replay.status());
                if (!idempotent) idempotencyFailure = "REPLAY_RESULT_MISMATCH";
            } catch (RuntimeException | IOException replayFailure) {
                idempotencyFailure = compactFailure(replayFailure);
            }

            gateReconciliation.renewLeaseForProbes(
                    imported.runId(), selectedTargets.size(),
                    configuration.sampleCount(), configuration.timeout());
            PlacePilotHttpProbeService.ProbeSuite afterHttp =
                    probes.captureAfter(probeConfiguration, selectedTargets);
            PlacePilotCatalogAnomalyService.AuditResult audit =
                    anomalies.audit(imported.runId(), baselineCatalog);
            ObjectNode diagnostics = buildDiagnostics(
                    baselineHttp, afterHttp, audit, idempotent, idempotencyFailure,
                    baselinePlaceId, selectedTargets);
            diagnostics.set("preimport_baseline_artifact", verifiedBaseline.toJson());
            boolean measuredPass = diagnostics.path("measured_pass").asBoolean(false);
            PlacePilotCanaryGateService.GateResult gate = gates.record(imported.runId(), measuredPass, diagnostics, null, approved);
            return new CanaryExecution(imported, gate, diagnostics);
        } catch (RuntimeException | IOException failure) {
            if (importCommitted && imported != null) {
                ObjectNode diagnostics = failureDiagnostics(
                        baselineHttp, baselineCatalog, baselinePlaceId, selectedTargets, failure);
                diagnostics.set("preimport_baseline_artifact", verifiedBaseline.toJson());
                try {
                    gates.record(imported.runId(), false, diagnostics, null);
                } catch (RuntimeException containmentFailure) {
                    failure.addSuppressed(containmentFailure);
                }
            }
            throw failure;
        }
    }

    private CanaryExecution runV3(Configuration c,V3Observations observations,UUID run,String hash,
            PlacePilotSourceAccounting.Approved approved,List<PlacePilotHttpProbeService.ProbeTarget> targets,
            PlacePilotHttpProbeService.ProbeSuite baseline,PlacePilotCatalogAnomalyService.CatalogSnapshot catalog,
            UUID baselineId,PlacePilotBaselineArtifactService.VerifiedArtifact verifiedBaseline,JsonNode pre,
            PlacePilotHttpProbeService.ProbeConfiguration probeConfiguration) throws IOException {
        PlacePilotImportService.ImportResult imported=null;
        boolean failedRecorded=false;
        try {
            imported=importer.importApproved(c.manifestPath(),hash,c.authorizationReference());
            if(imported.alreadyImported()) throw new IllegalStateException("V3_FRESH_IMPORT_REQUIRED");
            if(!run.equals(imported.runId()) || !"SUCCEEDED".equals(imported.status())) throw new IllegalStateException("V3_IMPORT_RESULT_HARD_FAILURE");
            var replay=importer.importApproved(c.manifestPath(),hash,c.authorizationReference());
            if(!replay.alreadyImported() || !run.equals(replay.runId()) || !"SUCCEEDED".equals(replay.status()))
                throw new IllegalStateException("V3_IDEMPOTENCY_HARD_FAILURE");
            gateReconciliation.renewLeaseForProbes(run,targets.size(),c.sampleCount(),c.timeout());
            JsonNode post=observations.capturePersistent("POST",run,hash);
            var evidence=PlacePilotV3Policy.verify(run,hash,pre,post); // advisory computed first; valid DEGRADED/INCOMPARABLE never throws.
            gates.verifyV3Provenance(run);
            gates.verifyV3Accounting(run,approved,targets);
            var audit=anomalies.audit(run,catalog);
            if(!audit.passed()) throw new IllegalStateException("V3_ANOMALY_HARD_FAILURE");
            var after=probes.captureAfter(probeConfiguration,targets);
            if(!after.passed()) throw new IllegalStateException("V3_FUNCTIONAL_HTTP_HARD_FAILURE");
            ObjectNode inspection=gates.inspectV3Containment(run,hash);
            observations.productSafety(run,hash,inspection);
            ObjectNode product=observations.productAcceptance(run,hash,targets);
            if(product==null || PlacePilotV3Policy.PRODUCT_CHECKS.stream().anyMatch(k->!"PASS".equals(product.path(k).asText())))
                throw new IllegalStateException("V3_PRODUCT_HARD_FAILURE");
            observations.finalHealth(run,hash);
            ObjectNode d=buildDiagnostics(baseline,after,audit,true,null,baselineId,targets);
            d.set("preimport_baseline_artifact",verifiedBaseline.toJson());
            d.put("probe_mode","AUTONOMOUS_V3_PERSISTENT_ADVISORY"); d.remove("performance");
            d.set("persistent_performance",evidence.summary()); d.set("product_acceptance",product);
            d.put("measured_pass",true);
            var gate=gates.record(run,true,d,null,approved,evidence);
            if(!"PASSED".equals(gate.status())) { failedRecorded=true; gates.containV3(run,hash); }
            return new CanaryExecution(imported,gate,d);
        } catch(RuntimeException|IOException failure) {
            if(imported!=null && !imported.alreadyImported() && !failedRecorded) {
                ObjectNode d=failureDiagnostics(baseline,catalog,baselineId,targets,failure);
                d.set("preimport_baseline_artifact",verifiedBaseline.toJson());
                gates.record(run,false,d,null); // immutable FAILED, V3 never retires here.
                try { gates.containV3(run,hash); } catch(RuntimeException blocked) {
                    throw new IllegalStateException("V3_CONTAINMENT_OWNER_INTERVENTION_REQUIRED",blocked);
                }
            }
            throw failure;
        }
    }

    ObjectNode buildDiagnostics(
            PlacePilotHttpProbeService.ProbeSuite baseline,
            PlacePilotHttpProbeService.ProbeSuite after,
            PlacePilotCatalogAnomalyService.AuditResult audit,
            boolean idempotent,
            String idempotencyFailure,
            UUID baselinePlaceId,
            List<PlacePilotHttpProbeService.ProbeTarget> targets
    ) {
        ObjectNode diagnostics = JsonNodeFactory.instance.objectNode();
        diagnostics.put("probe_mode", "AUTONOMOUS_HTTP_AND_DATABASE_V1");
        diagnostics.put("baseline_captured_before_import", true);
        diagnostics.put("baseline_place_id", baselinePlaceId.toString());
        diagnostics.put("search_correctness", status(
                after.surface("search").passed()
                        && after.surface("search_coverage").passed()));
        boolean mapsPassed = after.surface("map_nearby").passed()
                && after.surface("map_bounds").passed()
                && after.surface("map_nearby_coverage").passed()
                && after.surface("map_bounds_coverage").passed();
        diagnostics.put("map_bounded_behavior", status(mapsPassed));
        diagnostics.put("backend_health", status(after.surface("health").passed()));
        diagnostics.put("same_manifest_idempotency", status(idempotent));
        diagnostics.put("duplicate_canonical_diagnostics", status(
                audit.nearDuplicatePairs() == 0
                        && audit.duplicateCanonicalAssignments() == 0));
        diagnostics.put("same_coordinate_diagnostics",
                status(audit.sameCoordinateAnomalies() == 0));
        diagnostics.put("place_detail_correctness",
                status(after.surface("place_detail").passed()
                        && after.surface("place_detail_coverage").passed()));
        diagnostics.put("duplicate_canonical_violations",
                audit.nearDuplicatePairs() + audit.duplicateCanonicalAssignments());
        diagnostics.put("same_coordinate_anomalies", audit.sameCoordinateAnomalies());
        if (idempotencyFailure != null) {
            diagnostics.put("idempotency_failure", truncate(idempotencyFailure));
        }

        ObjectNode performance = diagnostics.putObject("performance");
        metric(performance.putObject("search"), baseline.surface("search").p95Ms(),
                after.surface("search").p95Ms());
        metric(performance.putObject("map"),
                Math.max(baseline.surface("map_nearby").p95Ms(),
                        baseline.surface("map_bounds").p95Ms()),
                Math.max(after.surface("map_nearby").p95Ms(),
                        after.surface("map_bounds").p95Ms()));
        metric(performance.putObject("place_detail"),
                baseline.surface("place_detail").p95Ms(),
                after.surface("place_detail").p95Ms());
        rateMetric(diagnostics.putObject("api_error_rate"),
                baseline.errorRate(), after.errorRate());

        ObjectNode http = diagnostics.putObject("http_probes");
        http.set("before", baseline.toJson());
        http.set("after", after.toJson());
        diagnostics.set("catalog_anomaly_report", audit.report());
        ArrayNode selectedIds = diagnostics.putArray("selected_place_ids_checked");
        targets.stream().map(value -> value.placeId().toString())
                .forEach(selectedIds::add);
        diagnostics.put("selected_place_count", targets.size());
        ObjectNode coverage = diagnostics.putObject("selected_place_coverage");
        coverage.put("search", after.surface("search_coverage").sampleCount());
        coverage.put("map_nearby", after.surface("map_nearby_coverage").sampleCount());
        coverage.put("map_bounds", after.surface("map_bounds_coverage").sampleCount());
        coverage.put("place_detail", after.surface("place_detail_coverage").sampleCount());

        boolean performancePassed = performance.path("search")
                .path("material_regression").asBoolean(true) == false
                && performance.path("map").path("material_regression").asBoolean(true) == false
                && performance.path("place_detail")
                .path("material_regression").asBoolean(true) == false;
        boolean measuredPass = baseline.passed() && after.passed() && audit.passed()
                && idempotent && performancePassed
                && !diagnostics.path("api_error_rate")
                .path("material_regression").asBoolean(true);
        diagnostics.put("measured_pass", measuredPass);
        return diagnostics;
    }

    private ObjectNode failureDiagnostics(
            PlacePilotHttpProbeService.ProbeSuite baselineHttp,
            PlacePilotCatalogAnomalyService.CatalogSnapshot baselineCatalog,
            UUID baselinePlaceId,
            List<PlacePilotHttpProbeService.ProbeTarget> targets,
            Throwable failure
    ) {
        ObjectNode diagnostics = JsonNodeFactory.instance.objectNode();
        diagnostics.put("probe_mode", "AUTONOMOUS_HTTP_AND_DATABASE_V1");
        diagnostics.put("baseline_captured_before_import", true);
        diagnostics.put("baseline_place_id", baselinePlaceId.toString());
        diagnostics.put("measured_pass", false);
        diagnostics.put("orchestration_failure", compactFailure(failure));
        diagnostics.set("http_baseline", baselineHttp.toJson());
        diagnostics.set("catalog_baseline", baselineCatalog.toJson());
        ArrayNode selectedIds = diagnostics.putArray("selected_place_ids_checked");
        targets.stream().map(value -> value.placeId().toString())
                .forEach(selectedIds::add);
        diagnostics.put("selected_place_count", targets.size());
        return diagnostics;
    }

    private void metric(ObjectNode metric, double baseline, double after) {
        double relative = relativeChange(baseline, after);
        metric.put("baseline_ms", baseline);
        metric.put("after_ms", after);
        metric.put("relative_change", relative);
        metric.put("material_regression", relative > MAX_RELATIVE_REGRESSION);
    }

    private void rateMetric(ObjectNode metric, double baseline, double after) {
        double relative = relativeChange(baseline, after);
        metric.put("baseline_rate", baseline);
        metric.put("after_rate", after);
        metric.put("relative_change", relative);
        metric.put("material_regression", relative > MAX_RELATIVE_REGRESSION);
    }

    private double relativeChange(double baseline, double after) {
        if (baseline == 0.0) return after == 0.0 ? 0.0 : 1.0;
        return (after - baseline) / baseline;
    }

    private String status(boolean passed) {
        return passed ? "PASS" : "FAIL";
    }

    private UUID resolveBaselinePlace(UUID configured) {
        if (configured != null) {
            Integer active = jdbc.queryForObject("""
                    SELECT count(*) FROM places
                     WHERE id = ? AND catalog_status = 'ACTIVE'
                    """, Integer.class, configured);
            if (!Integer.valueOf(1).equals(active)) {
                throw new IllegalArgumentException(
                        "configured baseline Place is not active");
            }
            return configured;
        }
        List<UUID> values = jdbc.query("""
                SELECT id FROM places WHERE catalog_status = 'ACTIVE' ORDER BY id LIMIT 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (values.size() != 1) {
            throw new IllegalStateException(
                    "an active stable Place is required for the pre-import detail baseline");
        }
        return values.getFirst();
    }

    private List<PlacePilotHttpProbeService.ProbeTarget> selectedTargets(JsonNode manifest) {
        JsonNode candidates = manifest.path("candidates");
        if (!candidates.isArray()) {
            throw new IllegalArgumentException("manifest candidates must be an array");
        }
        List<RankedTarget> ranked = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            if (!candidate.path("selected_for_stage").asBoolean(false)) continue;
            JsonNode canonical = requireObject(candidate, "canonical");
            ranked.add(new RankedTarget(candidate.path("selection_rank").asInt(-1),
                    new PlacePilotHttpProbeService.ProbeTarget(
                            UUID.fromString(requiredText(candidate, "canonical_place_id")),
                            requiredText(canonical, "name"),
                            requiredText(canonical, "category"),
                            requiredFinite(canonical, "latitude"),
                            requiredFinite(canonical, "longitude"))));
        }
        ranked.sort(Comparator.comparingInt(RankedTarget::rank));
        if (ranked.isEmpty() || ranked.getFirst().rank() <= 0) {
            throw new IllegalArgumentException("manifest has no ranked selected Place targets");
        }
        return ranked.stream().map(RankedTarget::target).toList();
    }

    private ReadEnvelope readEnvelope(Path path) throws IOException {
        Path resolved = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)
                || !resolved.getFileName().toString().endsWith(".json")) {
            throw new IllegalArgumentException(
                    "authorized canary manifest must be a regular JSON file");
        }
        if (Files.size(resolved) > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                    "authorized canary manifest exceeds the 128 MiB limit");
        }
        byte[] bytes = Files.readAllBytes(resolved);
        return new ReadEnvelope(objectMapper.readTree(bytes), PlacePilotBaselineArtifactService.sha256(bytes));
    }

    private JsonNode requireObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private double requiredFinite(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " must be finite");
        }
        return value.doubleValue();
    }

    private String compactFailure(Throwable failure) {
        String message = failure.getMessage();
        return truncate(failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message));
    }

    private String truncate(String value) {
        return value.length() <= MAX_FAILURE_TEXT
                ? value : value.substring(0, MAX_FAILURE_TEXT);
    }

    private record RankedTarget(
            int rank,
            PlacePilotHttpProbeService.ProbeTarget target
    ) {}

    private record ReadEnvelope(JsonNode document, String sha256) {}

    public record Configuration(
            Path manifestPath,
            String expectedManifestHash,
            String authorizationReference,
            URI baseUrl,
            URI healthBaseUrl,
            UUID baselinePlaceId,
            int sampleCount,
            Duration timeout,
            Path diagnosticsDirectory
    ) {
        void validate() {
            if (manifestPath == null
                    || expectedManifestHash == null
                    || !expectedManifestHash.matches("^[0-9a-f]{64}$")
                    || authorizationReference == null || authorizationReference.isBlank()) {
                throw new IllegalArgumentException(
                        "authorized manifest path, digest and authorization are required");
            }
            if (diagnosticsDirectory == null || !diagnosticsDirectory.isAbsolute()) {
                throw new IllegalArgumentException("PRIVATE_BASELINE_DIAGNOSTICS_DIRECTORY_REQUIRED");
            }
            if (sampleCount != 5 || !Duration.ofSeconds(5).equals(timeout)) {
                throw new IllegalArgumentException("LOCKED_PREIMPORT_BASELINE_REQUIRES_FIVE_SAMPLES_AND_FIVE_SECONDS");
            }
            new PlacePilotHttpProbeService.ProbeConfiguration(
                    baseUrl, healthBaseUrl,
                    baselinePlaceId == null ? new UUID(0, 0) : baselinePlaceId,
                    sampleCount, timeout).validate();
        }
    }

    public record CanaryExecution(
            PlacePilotImportService.ImportResult importResult,
            PlacePilotCanaryGateService.GateResult gateResult,
            ObjectNode diagnostics
    ) {}
}

package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Private one-shot importer for an operationally authorized, non-secret M5.5B canary manifest.
 *
 * <p>This service intentionally has no controller. Provider credentials are not accepted and
 * provider IDs remain only in provenance tables. Only blocker-free, field-validated,
 * high-existence AUTO_CREATE decisions are writable. Each source observation and canonical
 * candidate is committed independently as non-public provisional state, so a failed candidate
 * cannot leave a partial Place/ref group and a retry can continue idempotently. Public activation
 * occurs only with the terminal successful run transition.</p>
 */
@Service
public class PlacePilotImportService {
    private static final String EXPECTED_SCOPE = "didim_core";
    private static final double EXPECTED_CENTER_LATITUDE = 37.3751;
    private static final double EXPECTED_CENTER_LONGITUDE = 27.2678;
    private static final double EXPECTED_RADIUS_METERS = 6000.0;
    private static final String EXPECTED_OVERTURE_RELEASE = "2026-09-23.0";
    private static final String EXPECTED_FSQ_RELEASE = "2026-09-15 20:07:45.157000";
    private static final String EXPECTED_FSQ_SNAPSHOT = "2325979374271449319";
    private static final String EXPECTED_METHOD_VERSION = "didim-autonomous-validation-v2";
    private static final String EXPECTED_ACCOUNTING_SCHEMA_VERSION = "didim-autonomy-accounting-v1";
    private static final Set<String> APPROVED_SOURCE_METHOD_VERSIONS = Set.of(
            "didim-canonicalization-v2", "didim-canonicalization-v3");
    private static final UUID SOURCE_UUID_NAMESPACE =
            UUID.fromString("c2d15a95-29f7-5c35-b852-d43aff1f3c81");
    private static final UUID PLACE_UUID_NAMESPACE =
            UUID.fromString("238147d6-fc89-5af3-8b78-559044fef2ad");
    private static final String AUTHORIZED_STATUS = "AUTONOMOUS_CANARY_AUTHORIZED";
    private static final LocalDate FRESHNESS_REFERENCE_DATE = LocalDate.of(2026, 9, 23);
    private static final long MAX_FRESHNESS_AGE_DAYS = 730;
    private static final Set<String> NEGATIVE_OPERATING_SIGNALS = Set.of(
            "closed", "delete", "does_not_exist", "doesnt_exist", "duplicate",
            "inappropriate", "moved", "not_a_place", "permanently_closed",
            "private_venue", "privatevenue", "relocated", "unresolved_closed",
            "unresolved_delete", "unresolved_does_not_exist", "unresolved_doesnt_exist",
            "unresolved_duplicate", "unresolved_inappropriate", "unresolved_not_a_place",
            "unresolved_private_venue", "unresolved_privatevenue");
    private static final long MAX_MANIFEST_BYTES = 128L * 1024L * 1024L;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    static final Duration MIN_GATE_DEADLINE_LEASE = Duration.ofMinutes(60);
    static final Duration DEFAULT_GATE_DEADLINE_LEASE = Duration.ofMinutes(65);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final PlacePilotRollbackService rollback;
    private final Duration gateDeadlineLease;

    @Autowired
    public PlacePilotImportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            PlacePilotRollbackService rollback,
            @Value("${phokarta.place-import.gate-deadline-lease:65m}")
            Duration gateDeadlineLease
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.rollback = rollback;
        this.gateDeadlineLease = requireSafeGateDeadlineLease(gateDeadlineLease);
    }

    public PlacePilotImportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            PlacePilotRollbackService rollback
    ) {
        this(jdbc, objectMapper, transactionManager, rollback,
                DEFAULT_GATE_DEADLINE_LEASE);
    }

    public ImportResult importApproved(
            Path manifestPath,
            String expectedManifestHash,
            String expectedAuthorizationReference
    ) throws IOException {
        Path resolved = manifestPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved) || !resolved.getFileName().toString().endsWith(".json")) {
            throw new IllegalArgumentException("approved import manifest must be a regular JSON file");
        }
        if (Files.size(resolved) > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("approved import manifest exceeds the 128 MiB limit");
        }
        return importApproved(objectMapper.readTree(Files.readAllBytes(resolved)),
                expectedManifestHash, expectedAuthorizationReference);
    }

    public ImportResult importApproved(
            JsonNode envelope,
            String expectedManifestHash,
            String expectedAuthorizationReference
    ) {
        JsonNode manifest = requiredObject(envelope, "manifest");
        String suppliedHash = requiredText(envelope, "manifest_hash");
        requireExpectedAuthorization(expectedManifestHash, expectedAuthorizationReference);
        if (!expectedManifestHash.equals(suppliedHash)) {
            throw new IllegalArgumentException(
                    "manifest hash does not match the out-of-band authorized digest");
        }
        if (!expectedAuthorizationReference.equals(
                requiredText(manifest, "authorization_reference"))) {
            throw new IllegalArgumentException(
                    "manifest authorization reference does not match the out-of-band authorization");
        }
        String actualHash = hashManifest(manifest);
        if (!actualHash.equals(suppliedHash)) {
            throw new IllegalArgumentException("approved import manifest hash does not match content");
        }
        validateManifest(manifest);
        String planDigest = hashPlan(manifest);

        ExistingRun existing = findRunByHash(actualHash);
        if (existing != null && "SUCCEEDED".equals(existing.status())) {
            return existing.toResult(true);
        }

        UUID runId = UUID.fromString(requiredText(manifest, "run_id"));
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (now == null) {
            throw new IllegalStateException("database clock is unavailable for pilot import");
        }
        UUID claimToken = UUID.randomUUID();
        ExistingRun completedWhileClaiming = claimRun(
                manifest, actualHash, planDigest, runId, now, claimToken);
        if (completedWhileClaiming != null) return completedWhileClaiming.toResult(true);
        Counters counters = new Counters();
        try {
            for (JsonNode source : requiredArray(manifest, "source_records")) {
                executeClaimed(runId, claimToken,
                        () -> insertSourceRecord(runId, manifest, source));
                counters.sourceCount++;
                if (source.path("usable").asBoolean(true)) counters.usableCount++;
                else counters.sourceRejectedCount++;
            }
            for (JsonNode candidate : requiredArray(manifest, "candidates")) {
                String decision = requiredText(candidate, "decision");
                boolean canaryEligible = candidate.path("canary_eligible").asBoolean(false);
                boolean selectedForStage = candidate.path("selected_for_stage").asBoolean(false);
                if (canaryEligible) counters.canaryEligibleCount++;
                switch (decision) {
                    case "AUTO_CREATE" -> executeClaimed(runId, claimToken, () -> {
                        UUID decisionId = insertValidationDecision(runId, manifest, candidate);
                        if (selectedForStage) {
                            importEligibleAutoCreate(runId, decisionId, manifest, candidate);
                        }
                    });
                    case "QUARANTINE" -> {
                        executeClaimed(runId, claimToken, () -> {
                            UUID decisionId = insertValidationDecision(runId, manifest, candidate);
                            queueQuarantineRecheck(decisionId, manifest, candidate, now);
                        });
                        counters.quarantinedCount++;
                    }
                    case "AUTO_LINK" -> {
                        executeClaimed(runId, claimToken, () ->
                                insertValidationDecision(runId, manifest, candidate));
                        counters.linkedCount++;
                    }
                    case "AUTO_ENRICH" -> {
                        executeClaimed(runId, claimToken, () ->
                                insertValidationDecision(runId, manifest, candidate));
                        counters.enrichedCount++;
                    }
                    case "AUTO_REJECT" -> {
                        executeClaimed(runId, claimToken, () ->
                                insertValidationDecision(runId, manifest, candidate));
                        counters.autoRejectedCount++;
                    }
                    default -> throw new IllegalArgumentException("unsupported candidate decision: " + decision);
                }
                if (selectedForStage) counters.createdCount++;
            }
            finishRun(runId, claimToken, "SUCCEEDED", counters, null);
            return new ImportResult(runId, false, "SUCCEEDED", counters.sourceCount,
                    counters.usableCount, counters.sourceRejectedCount, counters.createdCount,
                    counters.linkedCount, counters.enrichedCount, counters.autoRejectedCount,
                    counters.quarantinedCount, counters.canaryEligibleCount);
        } catch (RuntimeException failure) {
            if (ownsClaim(runId, claimToken)) {
                try {
                    failRunAndContain(runId, claimToken, counters, sanitizeFailure(failure));
                } catch (RuntimeException containmentFailure) {
                    failure.addSuppressed(containmentFailure);
                }
            }
            throw failure;
        }
    }

    private void requireExpectedAuthorization(
            String expectedManifestHash,
            String expectedAuthorizationReference
    ) {
        if (expectedManifestHash == null
                || !expectedManifestHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "an out-of-band authorized manifest SHA-256 is required");
        }
        if (expectedAuthorizationReference == null || expectedAuthorizationReference.isBlank()) {
            throw new IllegalArgumentException(
                    "an out-of-band authorization reference is required");
        }
    }

    public String hashManifest(JsonNode manifest) {
        return hashCanonical(manifest);
    }

    /**
     * Commits every canary stage to one frozen candidate/source plan. Operational run, pilot,
     * authorization and stage metadata plus the per-stage selection bit are excluded; source,
     * evidence and decision content remain part of the digest.
     */
    public String hashPlan(JsonNode manifest) {
        if (!manifest.isObject()) throw new IllegalArgumentException("manifest must be an object");
        ObjectNode plan = manifest.deepCopy();
        plan.remove(List.of(
                "run_id", "pilot_run_key", "canary_stage", "authorization_reference",
                "reauthorizes_run_id", "status"));
        JsonNode candidates = plan.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                if (candidate.isObject()) ((ObjectNode) candidate).remove("selected_for_stage");
            }
        }
        return hashCanonical(plan);
    }

    private String hashCanonical(JsonNode value) {
        try {
            StringBuilder canonical = new StringBuilder();
            appendPythonCanonicalJson(value, canonical);
            byte[] encoded = canonical.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to hash import manifest", exception);
        }
    }

    private void validateManifest(JsonNode manifest) {
        if (!AUTHORIZED_STATUS.equals(requiredText(manifest, "status"))) {
            throw new IllegalArgumentException(
                    "manifest requires the autonomous canary operational authorization");
        }
        if (!EXPECTED_METHOD_VERSION.equals(requiredText(manifest, "method_version"))) {
            throw new IllegalArgumentException("manifest validation method is not approved for this canary");
        }
        if (!requiredText(manifest, "pilot_run_key")
                .matches("^[a-z0-9][a-z0-9-]{7,159}$")) {
            throw new IllegalArgumentException(
                    "manifest pilot identity is invalid");
        }
        String canaryStage = requiredCanaryStage(manifest);
        requiredText(manifest, "authorization_reference");
        if (manifest.hasNonNull("reauthorizes_run_id")) {
            UUID.fromString(requiredText(manifest, "reauthorizes_run_id"));
        }
        JsonNode scope = requiredObject(manifest, "scope");
        if (!EXPECTED_SCOPE.equals(requiredText(scope, "name"))
                || Double.compare(requiredFiniteDouble(scope, "center_latitude"),
                        EXPECTED_CENTER_LATITUDE) != 0
                || Double.compare(requiredFiniteDouble(scope, "center_longitude"),
                        EXPECTED_CENTER_LONGITUDE) != 0
                || Double.compare(requiredFiniteDouble(scope, "radius_meters"),
                        EXPECTED_RADIUS_METERS) != 0) {
            throw new IllegalArgumentException(
                    "only the frozen Didim Core center and 6000 m scope may be imported");
        }
        JsonNode providers = requiredObject(manifest, "providers");
        JsonNode overture = requiredObject(providers, "overture");
        JsonNode fsq = requiredObject(providers, "fsq");
        if (!EXPECTED_OVERTURE_RELEASE.equals(requiredText(overture, "release"))
                || !EXPECTED_FSQ_RELEASE.equals(requiredText(fsq, "release"))
                || !EXPECTED_FSQ_SNAPSHOT.equals(requiredText(fsq, "snapshot_id"))) {
            throw new IllegalArgumentException("manifest provider versions do not match the reviewed dry run");
        }

        Set<UUID> sourceIds = new HashSet<>();
        Map<UUID, ManifestSource> manifestSources = new HashMap<>();
        Set<String> sourceIdentities = new HashSet<>();
        for (JsonNode source : requiredArray(manifest, "source_records")) {
            String sourceIdText = requiredText(source, "source_record_id");
            UUID sourceId = UUID.fromString(sourceIdText);
            if (!sourceIds.add(sourceId)) {
                throw new IllegalArgumentException("duplicate source record UUID in manifest");
            }
            String provider = requiredText(source, "provider").toUpperCase();
            if (!sourceIdentities.add(provider + "\n" + requiredText(source, "external_id"))) {
                throw new IllegalArgumentException("duplicate provider external identity in manifest");
            }
            String sourceHash = requiredText(source, "source_hash");
            if (!sourceHash.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("source hash must be a lowercase SHA-256 digest");
            }
            String sourceMethodVersion = requiredText(source, "method_version");
            if (!isApprovedSourceMethodVersion(sourceMethodVersion)) {
                throw new IllegalArgumentException(
                        "source transformation method is not approved for this canary");
            }
            UUID expectedSourceId = uuidV5(SOURCE_UUID_NAMESPACE,
                    provider + "\n" + requiredText(source, "external_id") + "\n"
                            + sourceMethodVersion + "\n" + sourceHash);
            if (!expectedSourceId.toString().equals(sourceIdText)) {
                throw new IllegalArgumentException("source record UUID is not deterministic");
            }
            JsonNode providerCategories = source.get("provider_categories");
            if (providerCategories == null || !providerCategories.isArray()) {
                throw new IllegalArgumentException("provider_categories must be an array");
            }
            JsonNode usable = source.get("usable");
            if (usable == null || !usable.isBoolean()) {
                throw new IllegalArgumentException("source usable must be a boolean");
            }
            OffsetDateTime observedAt = parseTime(source, "observed_at");
            parseTime(source, "retrieved_at");
            manifestSources.put(sourceId, new ManifestSource(
                    provider, usable.booleanValue(), sourceHash,
                    isFreshObservation(observedAt), hasNegativeOperatingSignal(source),
                    "OVERTURE".equals(provider) && hasFoursquareUpstreamLineage(source)));
            String expectedRelease = switch (provider) {
                case "OVERTURE" -> EXPECTED_OVERTURE_RELEASE;
                case "FSQ" -> EXPECTED_FSQ_RELEASE;
                default -> throw new IllegalArgumentException("unsupported source provider");
            };
            if (!expectedRelease.equals(requiredText(source, "source_release"))) {
                throw new IllegalArgumentException("source release does not match reviewed provider version");
            }
            if ("FSQ".equals(provider)
                    && !EXPECTED_FSQ_SNAPSHOT.equals(requiredText(source, "snapshot_id"))) {
                throw new IllegalArgumentException("FSQ source snapshot does not match reviewed dry run");
            }
            validateOptionalScopedPoint(source, "source record");
        }

        Set<UUID> usableSourceIds = new HashSet<>();
        manifestSources.forEach((sourceId, source) -> {
            if (source.usable()) usableSourceIds.add(sourceId);
        });
        Set<UUID> assignedSourceIds = new HashSet<>();
        Set<String> candidateIds = new HashSet<>();
        Set<UUID> selectedPlaceIds = new HashSet<>();
        Set<Integer> selectionRanks = new HashSet<>();
        Set<Integer> selectedRanks = new HashSet<>();
        for (JsonNode candidate : requiredArray(manifest, "candidates")) {
            if (!candidate.isObject()) {
                throw new IllegalArgumentException("candidate must be an object");
            }
            String decision = requiredText(candidate, "decision");
            if (!Set.of("AUTO_LINK", "AUTO_CREATE", "AUTO_ENRICH", "AUTO_REJECT", "QUARANTINE")
                    .contains(decision)) {
                throw new IllegalArgumentException("unsupported candidate decision: " + decision);
            }
            if (!candidateIds.add(requiredText(candidate, "candidate_id"))) {
                throw new IllegalArgumentException("duplicate canonical candidate identity");
            }
            requiredText(candidate, "decision_reason");
            requiredExistenceAssessment(candidate);
            String candidateHash = requiredText(candidate, "candidate_hash");
            if (!candidateHash.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException(
                        "candidate hash must be a lowercase SHA-256 digest");
            }
            ObjectNode candidatePayload = ((ObjectNode) candidate).deepCopy();
            candidatePayload.remove(List.of("candidate_hash", "selected_for_stage"));
            if (!candidateHash.equals(hashCanonical(candidatePayload))) {
                throw new IllegalArgumentException(
                        "candidate hash does not match canonical decision content");
            }
            parseTime(candidate, "decided_at");
            if (!candidate.path("evidence").isObject()
                    || !candidate.path("hard_blockers").isArray()
                    || !candidate.path("field_proposals").isObject()) {
                throw new IllegalArgumentException(
                        "candidate evidence, hard blockers and field proposals have invalid shapes");
            }
            JsonNode eligibleNode = candidate.get("canary_eligible");
            JsonNode selectedNode = candidate.get("selected_for_stage");
            if (eligibleNode == null || !eligibleNode.isBoolean()
                    || selectedNode == null || !selectedNode.isBoolean()) {
                throw new IllegalArgumentException(
                        "candidate canary eligibility and stage selection must be booleans");
            }
            boolean canaryEligible = eligibleNode.booleanValue();
            boolean selectedForStage = selectedNode.booleanValue();
            Integer selectionRank = null;
            if (canaryEligible) {
                if (!decision.equals("AUTO_CREATE")) {
                    throw new IllegalArgumentException("only AUTO_CREATE may be canary eligible");
                }
                if (!"HIGH".equals(requiredExistenceAssessment(candidate))) {
                    throw new IllegalArgumentException("canary candidate existence must be HIGH");
                }
                if (!candidate.path("hard_blockers").isEmpty()) {
                    throw new IllegalArgumentException("canary candidate has unresolved hard blockers");
                }
                if (!candidate.path("canonical_fields_valid").asBoolean(false)) {
                    throw new IllegalArgumentException("canary candidate fields were not validated");
                }
                String canonicalPlaceId = requiredText(candidate, "canonical_place_id");
                UUID.fromString(canonicalPlaceId);
                UUID expectedPlaceId = uuidV5(PLACE_UUID_NAMESPACE,
                        EXPECTED_METHOD_VERSION + "\n" + requiredText(candidate, "candidate_id"));
                if (!expectedPlaceId.toString().equals(canonicalPlaceId)) {
                    throw new IllegalArgumentException(
                            "eligible candidate Place UUID is not deterministic");
                }
                JsonNode canonical = requiredObject(candidate, "canonical");
                validateRequiredScopedPoint(canonical, "canonical candidate");
                requiredText(canonical, "name");
                requiredText(canonical, "category");
                requiredText(canonical, "city");
                requiredText(canonical, "region");
                requiredText(canonical, "country");
                JsonNode rankNode = candidate.get("selection_rank");
                if (rankNode == null || !rankNode.isIntegralNumber()
                        || !rankNode.canConvertToInt() || rankNode.intValue() <= 0) {
                    throw new IllegalArgumentException(
                            "canary-eligible candidate requires a positive integer selection rank");
                }
                selectionRank = rankNode.intValue();
                if (!selectionRanks.add(selectionRank)) {
                    throw new IllegalArgumentException("canary selection ranks must be unique");
                }
            } else if (candidate.hasNonNull("selection_rank")) {
                throw new IllegalArgumentException(
                        "noneligible candidate cannot carry a canary selection rank");
            }
            if (selectedForStage) {
                if (!canaryEligible) {
                    throw new IllegalArgumentException(
                            "stage selection must be a canary-eligible AUTO_CREATE");
                }
                UUID placeId = UUID.fromString(requiredText(candidate, "canonical_place_id"));
                if (!selectedPlaceIds.add(placeId)) {
                    throw new IllegalArgumentException("canonical Place UUID selected more than once");
                }
                selectedRanks.add(selectionRank);
            }
            if (candidate.path("overrides").isArray() && !candidate.path("overrides").isEmpty()) {
                throw new IllegalArgumentException(
                        "autonomous canary manifests cannot mutate trusted canonical overrides");
            }
            int candidateSourceCount = 0;
            Set<String> candidateProviders = new HashSet<>();
            Set<String> candidateSourceHashes = new HashSet<>();
            boolean allCandidateSourcesUsable = true;
            boolean allCandidateSourcesFresh = true;
            boolean hasNegativeOperatingSignal = false;
            boolean hasDependentSourceLineage = false;
            for (JsonNode sourceIdNode : requiredArray(candidate, "source_record_ids")) {
                if (!sourceIdNode.isTextual()) {
                    throw new IllegalArgumentException("candidate source record UUID must be text");
                }
                UUID sourceId = UUID.fromString(sourceIdNode.asText());
                if (!sourceIds.contains(sourceId)) {
                    throw new IllegalArgumentException("candidate references a source outside the manifest");
                }
                if (!assignedSourceIds.add(sourceId)) {
                    throw new IllegalArgumentException("source record is assigned to multiple candidates");
                }
                ManifestSource manifestSource = manifestSources.get(sourceId);
                if (!manifestSource.usable()) {
                    throw new IllegalArgumentException(
                            "rejected source cannot be assigned a candidate decision");
                }
                candidateProviders.add(manifestSource.provider());
                candidateSourceHashes.add(manifestSource.sourceHash());
                allCandidateSourcesUsable &= manifestSource.usable();
                allCandidateSourcesFresh &= manifestSource.fresh();
                hasNegativeOperatingSignal |= manifestSource.negativeOperatingSignal();
                hasDependentSourceLineage |= manifestSource.foursquareUpstreamLineage();
                candidateSourceCount++;
            }
            if (candidateSourceCount == 0) {
                throw new IllegalArgumentException("candidate must contain a source record");
            }
            if (canaryEligible && !hasSafeIndependentProviderEvidence(
                    candidateProviders, candidateSourceHashes,
                    allCandidateSourcesUsable, allCandidateSourcesFresh,
                    hasNegativeOperatingSignal, hasDependentSourceLineage)) {
                throw new IllegalArgumentException(
                        "canary eligibility requires fresh, nonnegative, usable independent "
                                + "Overture and FSQ evidence");
            }
        }
        if (!assignedSourceIds.equals(usableSourceIds)) {
            throw new IllegalArgumentException(
                    "every usable source record must be assigned exactly once to a candidate decision");
        }
        validateAccountingPopulations(manifest);
        for (int rank = 1; rank <= selectionRanks.size(); rank++) {
            if (!selectionRanks.contains(rank)) {
                throw new IllegalArgumentException(
                        "canary selection ranks must be contiguous from one");
            }
        }
        requireExactStageSelection(canaryStage, selectionRanks.size(), selectedRanks);
    }

    static void validateAccountingPopulations(JsonNode manifest) {
        if (!EXPECTED_ACCOUNTING_SCHEMA_VERSION.equals(
                manifest.path("reporting_schema_version").asText())) {
            throw new IllegalArgumentException("manifest accounting schema is invalid");
        }
        JsonNode sources = manifest.path("source_records");
        JsonNode candidates = manifest.path("candidates");
        if (!sources.isArray() || !candidates.isArray()) {
            throw new IllegalArgumentException("manifest accounting populations must be arrays");
        }
        int usable = 0;
        for (JsonNode source : sources) {
            if (source.path("usable").asBoolean(false)) {
                usable++;
            } else if (!"SOURCE_REJECTED".equals(
                    source.path("provenance").path("source_record_state").asText())
                    || source.path("provenance").path("source_rejection_reason")
                    .asText().isBlank()) {
                throw new IllegalArgumentException("rejected source lacks source-state provenance");
            }
        }
        JsonNode sourceStates = manifest.path("source_record_states");
        requireAccountingCount(sourceStates, "total", sources.size());
        requireAccountingCount(sourceStates, "usable", usable);
        requireAccountingCount(sourceStates, "rejected_before_canonical_grouping",
                sources.size() - usable);
        if (!sourceStates.isObject() || sourceStates.size() != 3) {
            throw new IllegalArgumentException("source record accounting must contain only source states");
        }
        requireAccountingCount(manifest, "candidate_group_count", candidates.size());
        Map<String, Integer> decisionCounts = new HashMap<>();
        Set<String> decisionStates = Set.of(
                "AUTO_LINK", "AUTO_CREATE", "AUTO_ENRICH", "AUTO_REJECT", "QUARANTINE");
        Set<String> identities = new HashSet<>();
        for (JsonNode candidate : candidates) {
            String identity = candidate.path("candidate_id").asText();
            String decision = candidate.path("decision").asText();
            if (identity.isBlank() || !identities.add(identity)
                    || !decisionStates.contains(decision)) {
                throw new IllegalArgumentException("candidate decisions must have one unique final state");
            }
            decisionCounts.merge(decision, 1, Integer::sum);
            if (candidate.path("canary_eligible").asBoolean(false)
                    && !"AUTO_CREATE".equals(decision)) {
                throw new IllegalArgumentException("only AUTO_CREATE may be canary eligible");
            }
        }
        JsonNode declaredDecisions = manifest.path("candidate_decisions");
        for (String decision : decisionStates) {
            requireAccountingCount(declaredDecisions, decision,
                    decisionCounts.getOrDefault(decision, 0));
        }
        if (!declaredDecisions.isObject() || declaredDecisions.size() != decisionStates.size()) {
            throw new IllegalArgumentException("candidate accounting must contain exactly five final states");
        }
    }

    private static void requireAccountingCount(JsonNode node, String field, int expected) {
        JsonNode count = node.get(field);
        if (count == null || !count.isIntegralNumber() || !count.canConvertToInt()
                || count.intValue() != expected) {
            throw new IllegalArgumentException("manifest accounting count is inconsistent: " + field);
        }
    }

    static boolean isApprovedSourceMethodVersion(String methodVersion) {
        return APPROVED_SOURCE_METHOD_VERSIONS.contains(methodVersion);
    }

    private void requireExactStageSelection(
            String canaryStage,
            int eligibleCount,
            Set<Integer> selectedRanks
    ) {
        int firstRank = switch (canaryStage) {
            case "STAGE_1" -> 1;
            case "STAGE_2" -> 101;
            case "STAGE_3" -> 501;
            default -> throw new IllegalArgumentException("invalid canary stage");
        };
        int lastRank = switch (canaryStage) {
            case "STAGE_1" -> Math.min(100, eligibleCount);
            case "STAGE_2" -> Math.min(500, eligibleCount);
            case "STAGE_3" -> eligibleCount;
            default -> throw new IllegalArgumentException("invalid canary stage");
        };
        if (lastRank < firstRank) {
            throw new IllegalArgumentException(
                    canaryStage + " has no remaining eligible candidates to select");
        }
        Set<Integer> expectedRanks = new HashSet<>();
        for (int rank = firstRank; rank <= lastRank; rank++) expectedRanks.add(rank);
        if (!selectedRanks.equals(expectedRanks)) {
            throw new IllegalArgumentException(
                    canaryStage + " must select exactly its frozen deterministic rank range");
        }
    }

    private boolean isFreshObservation(OffsetDateTime observedAt) {
        LocalDate observedDate = observedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        return !observedDate.isAfter(FRESHNESS_REFERENCE_DATE)
                && !observedDate.isBefore(
                        FRESHNESS_REFERENCE_DATE.minusDays(MAX_FRESHNESS_AGE_DAYS));
    }

    private boolean hasNegativeOperatingSignal(JsonNode source) {
        if (isNegativeOperatingSignal(textOrNull(source, "operating_status"))) {
            return true;
        }
        JsonNode provenance = source.get("provenance");
        if (provenance == null || !provenance.isObject()) {
            throw new IllegalArgumentException("source provenance must be an object");
        }
        JsonNode unresolvedFlags = provenance.get("unresolved_flags");
        if (unresolvedFlags == null || unresolvedFlags.isNull()) return false;
        if (unresolvedFlags.isTextual()) {
            return isNegativeOperatingSignal(unresolvedFlags.asText());
        }
        if (!unresolvedFlags.isArray()) {
            throw new IllegalArgumentException(
                    "source provenance unresolved flags must be text or an array");
        }
        for (JsonNode flag : unresolvedFlags) {
            if (!flag.isTextual()) {
                throw new IllegalArgumentException(
                        "source provenance unresolved flags must contain only text");
            }
            if (isNegativeOperatingSignal(flag.asText())) {
                return true;
            }
        }
        return false;
    }

    static boolean hasFoursquareUpstreamLineage(JsonNode source) {
        JsonNode provenance = source.get("provenance");
        if (provenance == null || !provenance.isObject()) {
            throw new IllegalArgumentException("source provenance must be an object");
        }
        JsonNode upstreamSources = provenance.get("sources");
        if (upstreamSources == null || upstreamSources.isNull()) return false;
        if (upstreamSources.isObject()) {
            return isFoursquareUpstreamSource(upstreamSources);
        }
        if (!upstreamSources.isArray()) {
            throw new IllegalArgumentException(
                    "source provenance sources must be an object or an array");
        }
        for (JsonNode upstream : upstreamSources) {
            if (!upstream.isObject()) {
                throw new IllegalArgumentException(
                        "source provenance sources must contain only objects");
            }
            if (isFoursquareUpstreamSource(upstream)) return true;
        }
        return false;
    }

    static boolean hasSafeIndependentProviderEvidence(
            Set<String> providers,
            Set<String> sourceHashes,
            boolean allUsable,
            boolean allFresh,
            boolean hasNegativeOperatingSignal,
            boolean hasDependentSourceLineage
    ) {
        return allUsable
                && providers.equals(Set.of("OVERTURE", "FSQ"))
                && sourceHashes.size() >= 2
                && allFresh
                && !hasNegativeOperatingSignal
                && !hasDependentSourceLineage;
    }

    private static boolean isFoursquareUpstreamSource(JsonNode upstream) {
        for (String field : List.of("provider", "dataset", "resource")) {
            JsonNode valueNode = upstream.get(field);
            if (valueNode == null || valueNode.isNull()) continue;
            if (!valueNode.isTextual()) {
                throw new IllegalArgumentException(
                        "source provenance lineage identities must be text");
            }
            String value = normalizeLineageIdentity(valueNode.textValue());
            if ("fsq".equals(value) || value.contains("foursquare")) return true;
        }
        return false;
    }

    private static String normalizeLineageIdentity(String value) {
        String decomposed = Normalizer.normalize(
                value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String normalizeOperatingSignal(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(
                value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}_\\s]", " ")
                .strip().replaceAll("\\s+", "_");
    }

    private boolean isNegativeOperatingSignal(String value) {
        String normalized = normalizeOperatingSignal(value);
        return NEGATIVE_OPERATING_SIGNALS.contains(normalized)
                || normalized.startsWith("closed_")
                || normalized.endsWith("_closed");
    }

    private ExistingRun claimRun(
            JsonNode manifest,
            String manifestHash,
            String planDigest,
            UUID runId,
            OffsetDateTime now,
            UUID claimToken
    ) {
        JsonNode scope = requiredObject(manifest, "scope");
        JsonNode providers = requiredObject(manifest, "providers");
        String pilotRunKey = requiredText(manifest, "pilot_run_key");
        String resolvedRelease = "overture="
                + requiredText(requiredObject(providers, "overture"), "release")
                + ";fsq=" + requiredText(requiredObject(providers, "fsq"), "release");
        String snapshot = requiredText(requiredObject(providers, "fsq"), "snapshot_id");
        ExistingRun[] completed = new ExistingRun[1];
        transactions.executeWithoutResult(status -> {
            // Serializes the state checks and STARTED claim for one logical pilot. The partial
            // unique index remains the database backstop after this transaction releases.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                    (rs, rowNum) -> rs.getObject(1), pilotRunKey);
            ExistingRun existingByHash = findRunByHash(manifestHash);
            if (existingByHash != null) {
                if (!existingByHash.runId().equals(runId)) {
                    throw new IllegalArgumentException(
                            "manifest hash is already bound to another run UUID");
                }
                if ("SUCCEEDED".equals(existingByHash.status())) {
                    completed[0] = existingByHash;
                    return;
                }
                if ("FAILED".equals(existingByHash.status())) {
                    throw new IllegalStateException(
                            "failed contained pilot runs are terminal and require a new authorization");
                }
                RunLease lease = jdbc.queryForObject("""
                        SELECT claim_token, claim_expires_at
                          FROM place_provider_sync_runs WHERE id = ? FOR UPDATE
                        """, (rs, rowNum) -> new RunLease(
                        rs.getObject("claim_token", UUID.class),
                        rs.getObject("claim_expires_at", OffsetDateTime.class)), runId);
                if (lease == null || lease.token() == null || lease.expiresAt() == null
                        || !lease.expiresAt().isBefore(now)) {
                    throw new IllegalStateException(
                            "another importer still owns this pilot's active claim");
                }
                validateClaimedRunState(manifest, planDigest, runId);
                int reclaimed = jdbc.update("""
                        UPDATE place_provider_sync_runs
                           SET claim_token = ?, claim_expires_at = ?
                         WHERE id = ? AND status = 'STARTED' AND claim_expires_at < ?
                        """, claimToken, now.plus(CLAIM_LEASE), runId, now);
                if (reclaimed != 1) {
                    throw new IllegalStateException("pilot claim could not be recovered safely");
                }
                return;
            }
            List<UUID> activeRuns = jdbc.query("""
                    SELECT id FROM place_provider_sync_runs
                     WHERE pilot_run_key = ? AND status = 'STARTED'
                     FOR UPDATE
                    """, (rs, rowNum) -> rs.getObject("id", UUID.class), pilotRunKey);
            if (!activeRuns.isEmpty()) {
                throw new IllegalStateException(
                        "another import run already owns this pilot's active claim");
            }
            validateClaimedRunState(manifest, planDigest, runId);
            int inserted = jdbc.update("""
                    INSERT INTO place_provider_sync_runs (
                        id, pilot_run_key, canary_stage, authorization_reference,
                        reauthorizes_run_id,
                        provider, requested_release, resolved_release, snapshot_id,
                        method_version, scope_name, scope_center_latitude,
                        scope_center_longitude, scope_radius_meters, started_at, status,
                        claim_token, claim_expires_at, manifest_hash, plan_digest
                    ) VALUES (?, ?, ?, ?, ?, 'MULTI_SOURCE', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED',
                        ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, runId, requiredText(manifest, "pilot_run_key"),
                    requiredCanaryStage(manifest), requiredText(manifest, "authorization_reference"),
                    optionalUuid(manifest, "reauthorizes_run_id"),
                    textOrNull(manifest, "requested_release"), resolvedRelease,
                    snapshot, requiredText(manifest, "method_version"), requiredText(scope, "name"),
                    scope.path("center_latitude").asDouble(), scope.path("center_longitude").asDouble(),
                    scope.path("radius_meters").asDouble(), now, claimToken,
                    now.plus(CLAIM_LEASE), manifestHash, planDigest);
            if (inserted == 0) {
                throw new IllegalArgumentException("run UUID is already bound to another manifest");
            }
        });
        return completed[0];
    }

    private void validateClaimedRunState(JsonNode manifest, String planDigest, UUID runId) {
        String pilotRunKey = requiredText(manifest, "pilot_run_key");
        String canaryStage = requiredCanaryStage(manifest);
        String authorizationReference = requiredText(manifest, "authorization_reference");
        UUID reauthorizesRunId = optionalUuid(manifest, "reauthorizes_run_id");
        Integer authorizationRebound = jdbc.queryForObject("""
                SELECT count(*) FROM place_pilot_authorization_bindings
                 WHERE authorization_reference = ? AND pilot_run_key <> ?
                """, Integer.class, authorizationReference, pilotRunKey);
        if (authorizationRebound != null && authorizationRebound > 0) {
            throw new IllegalArgumentException(
                    "operational authorization is already bound to another pilot");
        }
        Integer exactBinding = jdbc.queryForObject("""
                SELECT count(*) FROM place_pilot_authorization_bindings binding
                 WHERE binding.pilot_run_key = ?
                   AND binding.authorization_reference = ? AND binding.plan_digest = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_authorization_bindings successor
                        WHERE successor.supersedes_authorization_reference =
                              binding.authorization_reference
                   )
                """, Integer.class, pilotRunKey, authorizationReference, planDigest);
        Integer pilotBindings = jdbc.queryForObject("""
                SELECT count(*) FROM place_pilot_authorization_bindings
                 WHERE pilot_run_key = ?
                """, Integer.class, pilotRunKey);
        if (reauthorizesRunId == null) {
            if (pilotBindings != null && pilotBindings > 0
                    && !Integer.valueOf(1).equals(exactBinding)) {
                throw new IllegalArgumentException(
                        "pilot stages must use the current frozen authorized plan");
            }
        } else {
            validateReauthorization(pilotRunKey, canaryStage, authorizationReference,
                    planDigest, runId, reauthorizesRunId, exactBinding);
        }
        int selectedCount = 0;
        for (JsonNode candidate : requiredArray(manifest, "candidates")) {
            if (!candidate.path("selected_for_stage").asBoolean(false)) continue;
            selectedCount++;
        }
        requireMonotonicStageHistory(pilotRunKey, canaryStage, runId, reauthorizesRunId);
        long priorActiveWrites = priorActiveWrites(pilotRunKey, runId);
        long cumulativeLimit = switch (canaryStage) {
            case "STAGE_1" -> 100;
            case "STAGE_2" -> 500;
            default -> Long.MAX_VALUE;
        };
        if (priorActiveWrites + selectedCount > cumulativeLimit) {
            throw new IllegalArgumentException(canaryStage + " exceeds its cumulative write limit");
        }
        for (JsonNode candidate : requiredArray(manifest, "candidates")) {
            if (candidate.path("selected_for_stage").asBoolean(false)) {
                validateSelectedTarget(pilotRunKey, runId, canaryStage, manifest, candidate);
            }
        }
    }

    private void validateReauthorization(
            String pilotRunKey,
            String canaryStage,
            String authorizationReference,
            String planDigest,
            UUID currentRunId,
            UUID reauthorizesRunId,
            Integer exactBinding
    ) {
        if (Integer.valueOf(1).equals(exactBinding)) {
            Integer sameStartedReplacement = jdbc.queryForObject("""
                    SELECT count(*) FROM place_provider_sync_runs
                     WHERE id = ? AND pilot_run_key = ? AND canary_stage = ?
                       AND authorization_reference = ? AND plan_digest = ?
                       AND reauthorizes_run_id = ? AND status = 'STARTED'
                    """, Integer.class, currentRunId, pilotRunKey, canaryStage,
                    authorizationReference, planDigest, reauthorizesRunId);
            if (!Integer.valueOf(1).equals(sameStartedReplacement)) {
                throw new IllegalArgumentException(
                        "reauthorization reference is already bound to another attempt");
            }
        }
        Integer validPredecessor = jdbc.queryForObject("""
                SELECT count(*)
                  FROM place_provider_sync_runs prior
                 WHERE prior.id = ? AND prior.pilot_run_key = ? AND prior.canary_stage = ?
                   AND (prior.status = 'FAILED' OR EXISTS (
                       SELECT 1 FROM place_pilot_canary_gates gate
                        WHERE gate.sync_run_id = prior.id AND gate.gate_status = 'FAILED'
                   ))
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_catalog_writes write
                        WHERE write.sync_run_id = prior.id
                          AND write.rollback_state NOT IN
                              ('RETIRED', 'RETIRED_GRAPH_PROTECTED')
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM place_provider_sync_runs successor
                        WHERE successor.reauthorizes_run_id = prior.id
                          AND successor.id <> ?
                   )
                """, Integer.class, reauthorizesRunId, pilotRunKey, canaryStage, currentRunId);
        if (!Integer.valueOf(1).equals(validPredecessor)) {
            throw new IllegalArgumentException(
                    "reauthorization requires the contained leaf failed attempt for this pilot stage");
        }
    }

    private void insertSourceRecord(UUID runId, JsonNode manifest, JsonNode source) {
        UUID sourceId = UUID.fromString(requiredText(source, "source_record_id"));
        String provider = requiredText(source, "provider").toUpperCase();
        if (!provider.equals("OVERTURE") && !provider.equals("FSQ")) {
            throw new IllegalArgumentException("unsupported source provider");
        }
        Double latitude = optionalFiniteDouble(source, "latitude");
        Double longitude = optionalFiniteDouble(source, "longitude");
        String location = latitude == null || longitude == null
                ? "NULL"
                : "ST_SetSRID(ST_MakePoint(" + longitude + "," + latitude + "),4326)";
        String sql = """
                INSERT INTO place_source_records (
                    id, sync_run_id, provider, external_id, source_release, snapshot_id,
                    method_version, normalized_name, location, address, locality, region,
                    country_code, provider_categories, proposed_place_category, phone, website,
                    operating_status, source_hash, license_identifier, provenance,
                    observed_at, retrieved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?, ?)
                ON CONFLICT DO NOTHING
                """.formatted(location);
        OffsetDateTime observed = parseTime(source, "observed_at");
        OffsetDateTime retrieved = parseTime(source, "retrieved_at");
        int inserted = jdbc.update(sql, sourceId, runId, provider, requiredText(source, "external_id"),
                requiredText(source, "source_release"), textOrNull(source, "snapshot_id"),
                requiredText(source, "method_version"), textOrNull(source, "normalized_name"),
                textOrNull(source, "address"), textOrNull(source, "locality"),
                textOrNull(source, "region"), textOrNull(source, "country_code"),
                requiredArrayNode(source, "provider_categories").toString(),
                textOrNull(source, "proposed_place_category"), textOrNull(source, "phone"),
                textOrNull(source, "website"), textOrNull(source, "operating_status"),
                requiredText(source, "source_hash"), requiredText(source, "license_identifier"),
                source.path("provenance").isObject() ? source.path("provenance").toString() : "{}",
                observed, retrieved);
        if (inserted == 0) {
            Integer same = jdbc.queryForObject("""
                    SELECT count(*) FROM place_source_records
                     WHERE id = ? AND provider = ? AND external_id = ?
                       AND source_release = ? AND snapshot_id IS NOT DISTINCT FROM ?
                       AND method_version = ? AND normalized_name IS NOT DISTINCT FROM ?
                       AND ((location IS NULL AND %s IS NULL) OR ST_Equals(location, %s))
                       AND address IS NOT DISTINCT FROM ? AND locality IS NOT DISTINCT FROM ?
                       AND region IS NOT DISTINCT FROM ? AND country_code IS NOT DISTINCT FROM ?
                       AND provider_categories = ?::jsonb
                       AND proposed_place_category IS NOT DISTINCT FROM ?
                       AND phone IS NOT DISTINCT FROM ? AND website IS NOT DISTINCT FROM ?
                       AND operating_status IS NOT DISTINCT FROM ? AND source_hash = ?
                       AND license_identifier = ? AND provenance = ?::jsonb
                       AND observed_at = ? AND retrieved_at = ?
                    """.formatted(location, location), Integer.class,
                    sourceId, provider, requiredText(source, "external_id"),
                    requiredText(source, "source_release"), textOrNull(source, "snapshot_id"),
                    requiredText(source, "method_version"), textOrNull(source, "normalized_name"),
                    textOrNull(source, "address"), textOrNull(source, "locality"),
                    textOrNull(source, "region"), textOrNull(source, "country_code"),
                    requiredArrayNode(source, "provider_categories").toString(),
                    textOrNull(source, "proposed_place_category"), textOrNull(source, "phone"),
                    textOrNull(source, "website"), textOrNull(source, "operating_status"),
                    requiredText(source, "source_hash"), requiredText(source, "license_identifier"),
                    source.path("provenance").isObject() ? source.path("provenance").toString() : "{}",
                    observed, retrieved);
            if (!Integer.valueOf(1).equals(same)) {
                throw new IllegalArgumentException("source record UUID or identity collision");
            }
        }
    }

    private UUID insertValidationDecision(UUID runId, JsonNode manifest, JsonNode candidate) {
        String candidateKey = requiredText(candidate, "candidate_id");
        UUID decisionId = UUID.nameUUIDFromBytes((runId + "\n" + candidateKey + "\n"
                + requiredText(manifest, "method_version"))
                .getBytes(StandardCharsets.UTF_8));
        List<String> sourceIds = new ArrayList<>();
        for (JsonNode sourceId : requiredArray(candidate, "source_record_ids")) {
            sourceIds.add(UUID.fromString(sourceId.asText()).toString());
        }
        String postgresSourceIds = "{" + String.join(",", sourceIds) + "}";
        UUID canonicalPlaceId = candidate.path("canonical_place_id").isTextual()
                ? UUID.fromString(candidate.path("canonical_place_id").asText()) : null;
        UUID supersedes = resolveSupersededDecision(
                runId, candidateKey, canonicalPlaceId, candidate);
        Integer selectionRank = candidate.path("selection_rank").isIntegralNumber()
                ? candidate.path("selection_rank").intValue() : null;
        OffsetDateTime decidedAt = parseTime(candidate, "decided_at");
        int inserted = jdbc.update("""
                INSERT INTO place_validation_decisions (
                    id, sync_run_id, pilot_run_key, candidate_key,
                    validation_method_version, decision_state, decision_reason,
                    existence_assessment, evidence, hard_blockers, field_proposals,
                    source_record_ids, canonical_place_id, candidate_hash, canary_eligible,
                    selected_for_stage, selection_rank, supersedes_decision_id, decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                    ?::uuid[], ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, decisionId, runId, requiredText(manifest, "pilot_run_key"), candidateKey,
                requiredText(manifest, "method_version"), requiredText(candidate, "decision"),
                requiredText(candidate, "decision_reason"),
                requiredExistenceAssessment(candidate), candidate.path("evidence").toString(),
                candidate.path("hard_blockers").toString(),
                candidate.path("field_proposals").toString(), postgresSourceIds, canonicalPlaceId,
                requiredText(candidate, "candidate_hash"),
                candidate.path("canary_eligible").asBoolean(false),
                candidate.path("selected_for_stage").asBoolean(false), selectionRank, supersedes,
                decidedAt);
        if (inserted == 0) {
            Integer same = jdbc.queryForObject("""
                    SELECT count(*) FROM place_validation_decisions
                     WHERE id = ? AND sync_run_id = ? AND pilot_run_key = ?
                       AND candidate_key = ? AND validation_method_version = ?
                       AND decision_state = ? AND decision_reason = ?
                       AND existence_assessment = ? AND evidence = ?::jsonb
                       AND hard_blockers = ?::jsonb AND field_proposals = ?::jsonb
                       AND source_record_ids = ?::uuid[] AND candidate_hash = ?
                       AND canonical_place_id IS NOT DISTINCT FROM ?
                       AND canary_eligible = ? AND selected_for_stage = ?
                       AND selection_rank IS NOT DISTINCT FROM ?
                       AND supersedes_decision_id IS NOT DISTINCT FROM ?
                       AND decided_at = ?
                    """, Integer.class, decisionId, runId,
                    requiredText(manifest, "pilot_run_key"), candidateKey,
                    requiredText(manifest, "method_version"), requiredText(candidate, "decision"),
                    requiredText(candidate, "decision_reason"),
                    requiredExistenceAssessment(candidate), candidate.path("evidence").toString(),
                    candidate.path("hard_blockers").toString(),
                    candidate.path("field_proposals").toString(), postgresSourceIds,
                    requiredText(candidate, "candidate_hash"), canonicalPlaceId,
                    candidate.path("canary_eligible").asBoolean(false),
                    candidate.path("selected_for_stage").asBoolean(false), selectionRank,
                    supersedes, decidedAt);
            if (!Integer.valueOf(1).equals(same)) {
                throw new IllegalArgumentException("validation decision UUID collision");
            }
        }
        return decisionId;
    }

    private UUID resolveSupersededDecision(
            UUID runId,
            String candidateKey,
            UUID canonicalPlaceId,
            JsonNode candidate
    ) {
        List<UUID> derived = jdbc.query("""
                SELECT prior_decision.id
                  FROM place_provider_sync_runs current_run
                  JOIN place_validation_decisions prior_decision
                    ON prior_decision.sync_run_id = current_run.reauthorizes_run_id
                 WHERE current_run.id = ?
                   AND prior_decision.candidate_key = ?
                   AND prior_decision.canonical_place_id IS NOT DISTINCT FROM ?
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                runId, candidateKey, canonicalPlaceId);
        if (derived.size() > 1) {
            throw new IllegalStateException("reauthorized candidate has ambiguous decision lineage");
        }
        UUID derivedId = derived.isEmpty() ? null : derived.getFirst();
        UUID supplied = candidate.path("supersedes_decision_id").isTextual()
                ? UUID.fromString(candidate.path("supersedes_decision_id").asText()) : null;
        if (supplied != null && !supplied.equals(derivedId)) {
            throw new IllegalArgumentException(
                    "superseded decision must match the contained predecessor attempt");
        }
        return derivedId;
    }

    private void queueQuarantineRecheck(
            UUID decisionId,
            JsonNode manifest,
            JsonNode candidate,
            OffsetDateTime now
    ) {
        JsonNode triggers = candidate.path("reevaluation_triggers");
        String triggerJson = triggers.isArray() && !triggers.isEmpty()
                ? triggers.toString() : "[\"PROVIDER_SNAPSHOT_OR_RULE_CHANGE\"]";
        jdbc.update("""
                INSERT INTO place_validation_recheck_queue (
                    pilot_run_key, candidate_key, current_decision_id, status,
                    trigger_reasons, next_attempt_at, updated_at
                ) VALUES (?, ?, ?, 'PENDING', ?::jsonb, ?, ?)
                ON CONFLICT (pilot_run_key, candidate_key) DO UPDATE SET
                    current_decision_id = EXCLUDED.current_decision_id,
                    status = 'PENDING', trigger_reasons = EXCLUDED.trigger_reasons,
                    next_attempt_at = EXCLUDED.next_attempt_at,
                    lease_expires_at = NULL, updated_at = EXCLUDED.updated_at
                """, requiredText(manifest, "pilot_run_key"),
                requiredText(candidate, "candidate_id"), decisionId, triggerJson, now, now);
    }

    private void importEligibleAutoCreate(
            UUID runId,
            UUID decisionId,
            JsonNode manifest,
            JsonNode candidate
    ) {
        lockRedirectGraph();
        UUID placeId = UUID.fromString(requiredText(candidate, "canonical_place_id"));
        JsonNode canonical = requiredObject(candidate, "canonical");
        ReuseLineage reuse = insertCanonicalPlace(runId, placeId, decisionId, canonical);

        for (JsonNode sourceIdNode : requiredArray(candidate, "source_record_ids")) {
            UUID sourceId = UUID.fromString(sourceIdNode.asText());
            SourceRef source = requireSource(sourceId);
            int linked = jdbc.update("""
                    INSERT INTO place_external_refs (
                        provider, external_id, place_id, current_source_record_id,
                        source_release, snapshot_id, first_seen_at, last_seen_at, status,
                        source_hash, last_sync_run_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    ON CONFLICT (provider, external_id) DO UPDATE SET
                        current_source_record_id = EXCLUDED.current_source_record_id,
                        source_release = EXCLUDED.source_release,
                        snapshot_id = EXCLUDED.snapshot_id,
                        last_seen_at = EXCLUDED.last_seen_at,
                        status = 'ACTIVE', redirected_provider = NULL,
                        redirected_external_id = NULL, source_hash = EXCLUDED.source_hash,
                        last_sync_run_id = EXCLUDED.last_sync_run_id
                    WHERE place_external_refs.place_id = EXCLUDED.place_id
                      AND (
                          place_external_refs.last_sync_run_id = ?
                          OR (?::uuid IS NOT NULL
                              AND place_external_refs.status = 'INACTIVE'
                              AND place_external_refs.last_sync_run_id = ?)
                      )
                    """, source.provider(), source.externalId(), placeId, sourceId,
                    source.release(), source.snapshotId(), source.observedAt(), source.retrievedAt(),
                    source.sourceHash(), runId, runId, reuse.predecessorRunId(),
                    reuse.predecessorRunId());
            if (linked != 1) {
                throw new IllegalStateException("provider external ID is linked to another canonical Place");
            }
            UUID eventId = UUID.nameUUIDFromBytes((runId + "\n" + source.provider() + "\n"
                    + source.externalId() + "\nLINKED").getBytes(StandardCharsets.UTF_8));
            String eventDetails = reuse.predecessorRunId() == null
                    ? "{}"
                    : "{\"reauthorizes_run_id\":\"" + reuse.predecessorRunId()
                            + "\",\"supersedes_write_id\":\""
                            + reuse.predecessorWriteId() + "\"}";
            int eventInserted = jdbc.update("""
                    INSERT INTO place_external_ref_events (
                        id, provider, external_id, place_id, event_type, source_record_id,
                        sync_run_id, occurred_at, details
                    ) VALUES (?, ?, ?, ?, 'LINKED', ?, ?, ?, ?::jsonb)
                    ON CONFLICT (id) DO NOTHING
                    """, eventId, source.provider(), source.externalId(), placeId, sourceId,
                    runId, source.retrievedAt(), eventDetails);
            if (eventInserted == 0) {
                Integer sameEvent = jdbc.queryForObject("""
                        SELECT count(*) FROM place_external_ref_events
                         WHERE id = ? AND provider = ? AND external_id = ? AND place_id = ?
                           AND event_type = 'LINKED' AND source_record_id = ?
                           AND sync_run_id = ? AND redirected_provider IS NULL
                           AND redirected_external_id IS NULL AND occurred_at = ?
                           AND details = ?::jsonb
                        """, Integer.class, eventId, source.provider(), source.externalId(),
                        placeId, sourceId, runId, source.retrievedAt(), eventDetails);
                if (!Integer.valueOf(1).equals(sameEvent)) {
                    throw new IllegalArgumentException("provider event UUID payload collision");
                }
            }
        }
        UUID writeId = UUID.nameUUIDFromBytes((runId + "\n" + placeId + "\nAUTO_CREATE")
                .getBytes(StandardCharsets.UTF_8));
        int journaled = jdbc.update("""
                INSERT INTO place_pilot_catalog_writes (
                    id, sync_run_id, validation_decision_id, place_id, canary_stage,
                    write_action, supersedes_write_id, imported_at
                ) VALUES (?, ?, ?, ?, ?, 'AUTO_CREATE', ?, now())
                ON CONFLICT (id) DO NOTHING
                """, writeId, runId, decisionId, placeId, requiredCanaryStage(manifest),
                reuse.predecessorWriteId());
        if (journaled == 0) {
            Integer same = jdbc.queryForObject("""
                    SELECT count(*) FROM place_pilot_catalog_writes
                     WHERE id = ? AND sync_run_id = ? AND validation_decision_id = ?
                       AND place_id = ? AND rollback_state = 'NONE'
                       AND supersedes_write_id IS NOT DISTINCT FROM ?
                    """, Integer.class, writeId, runId, decisionId, placeId,
                    reuse.predecessorWriteId());
            if (!Integer.valueOf(1).equals(same)) {
                throw new IllegalArgumentException("canary catalog write identity collision");
            }
        }
    }

    private ReuseLineage insertCanonicalPlace(
            UUID runId,
            UUID placeId,
            UUID decisionId,
            JsonNode canonical
    ) {
        List<ReuseLineage> current = jdbc.query("""
                SELECT write.supersedes_write_id, predecessor.sync_run_id AS predecessor_run_id
                  FROM place_pilot_catalog_writes write
                  JOIN places place ON place.id = write.place_id
                  LEFT JOIN place_pilot_catalog_writes predecessor
                    ON predecessor.id = write.supersedes_write_id
                 WHERE write.sync_run_id = ? AND write.place_id = ?
                   AND write.validation_decision_id = ? AND write.rollback_state = 'NONE'
                   AND place.origin = 'EXTERNAL_IMPORT'
                   AND place.catalog_status = 'PROVISIONAL'
                """, (rs, rowNum) -> new ReuseLineage(
                rs.getObject("supersedes_write_id", UUID.class),
                rs.getObject("predecessor_run_id", UUID.class)), runId, placeId, decisionId);
        if (current.size() == 1) return current.getFirst();
        if (current.size() > 1) {
            throw new IllegalStateException("current canary write has ambiguous lineage");
        }
        UUID reauthorizesRunId = jdbc.queryForObject("""
                SELECT reauthorizes_run_id FROM place_provider_sync_runs WHERE id = ?
                """, UUID.class, runId);
        if (reauthorizesRunId == null) {
            int inserted = jdbc.update("""
                    INSERT INTO places (
                        id, name, description, category, subcategories, location, city, region,
                        country, address, cover_image, photos, price_level, origin, catalog_status,
                        created_at, updated_at
                    ) VALUES (?, ?, '', ?, '{}', ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, ?, ?,
                        '', '{}', 0, 'EXTERNAL_IMPORT', 'PROVISIONAL', now(), now())
                    ON CONFLICT (id) DO NOTHING
                    """, placeId, requiredText(canonical, "name"),
                    requiredText(canonical, "category"),
                    requiredFiniteDouble(canonical, "longitude"),
                    requiredFiniteDouble(canonical, "latitude"),
                    requiredText(canonical, "city"), requiredText(canonical, "region"),
                    requiredText(canonical, "country"), canonical.path("address").asText(""));
            if (inserted == 1) return new ReuseLineage(null, null);
        }

        List<ReuseLineage> predecessors = jdbc.query("""
                SELECT prior_write.id AS predecessor_write_id,
                       prior_write.sync_run_id AS predecessor_run_id
                  FROM place_provider_sync_runs current_run
                  JOIN place_provider_sync_runs prior_run
                    ON prior_run.id = current_run.reauthorizes_run_id
                   JOIN place_pilot_catalog_writes prior_write
                     ON prior_write.sync_run_id = prior_run.id
                    AND prior_write.place_id = ?
                  JOIN place_validation_decisions prior_decision
                    ON prior_decision.id = prior_write.validation_decision_id
                   JOIN place_validation_decisions current_decision
                     ON current_decision.id = ?
                    AND current_decision.supersedes_decision_id =
                        prior_write.validation_decision_id
                    AND current_decision.candidate_hash = prior_decision.candidate_hash
                   JOIN places place ON place.id = prior_write.place_id
                 WHERE current_run.id = ?
                   AND prior_write.rollback_state IN
                       ('RETIRED', 'RETIRED_GRAPH_PROTECTED')
                   AND place.origin = 'EXTERNAL_IMPORT'
                   AND place.catalog_status = 'RETIRED'
                   AND NOT EXISTS (
                       SELECT 1 FROM place_external_refs ref
                        WHERE ref.place_id = place.id
                          AND ref.status IN ('ACTIVE', 'MERGED')
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_catalog_writes live_write
                        WHERE live_write.place_id = place.id
                          AND live_write.rollback_state = 'NONE'
                   )
                 FOR UPDATE OF prior_write, place
                """, (rs, rowNum) -> new ReuseLineage(
                rs.getObject("predecessor_write_id", UUID.class),
                rs.getObject("predecessor_run_id", UUID.class)), placeId, decisionId, runId);
        if (predecessors.size() != 1) {
            throw new IllegalArgumentException(
                    "canonical UUID or candidate hash differs from the safely contained "
                            + "predecessor write");
        }
        requireContainedCanonicalPayload(placeId, canonical);
        int reactivated = jdbc.update("""
                UPDATE places SET catalog_status = 'PROVISIONAL', updated_at = now()
                 WHERE id = ? AND origin = 'EXTERNAL_IMPORT' AND catalog_status = 'RETIRED'
                """, placeId);
        if (reactivated != 1) {
            throw new IllegalStateException("contained canonical Place could not be reauthorized");
        }
        return predecessors.getFirst();
    }

    /**
     * A reauthorization is an operational retry, not a canonical-field update.  The immutable
     * decision hash above binds the candidate payload; this database comparison also prevents
     * drift in the actual Place row from being hidden by a successful replacement run.  Trusted
     * or user-managed fields are never overwritten here.
     */
    private void requireContainedCanonicalPayload(UUID placeId, JsonNode canonical) {
        Integer matching = jdbc.queryForObject("""
                SELECT count(*)
                  FROM places place
                 WHERE place.id = ?
                   AND place.origin = 'EXTERNAL_IMPORT'
                   AND place.catalog_status = 'RETIRED'
                   AND place.name = ?
                   AND place.category = ?
                   AND ST_Equals(
                       place.location,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)
                   )
                   AND place.city = ?
                   AND place.region = ?
                   AND place.country = ?
                   AND place.address = ?
                """, Integer.class, placeId,
                requiredText(canonical, "name"), requiredText(canonical, "category"),
                requiredFiniteDouble(canonical, "longitude"),
                requiredFiniteDouble(canonical, "latitude"),
                requiredText(canonical, "city"), requiredText(canonical, "region"),
                requiredText(canonical, "country"), canonical.path("address").asText(""));
        if (!Integer.valueOf(1).equals(matching)) {
            throw new IllegalArgumentException(
                    "reauthorized canonical payload differs from the contained predecessor Place");
        }
    }

    private SourceRef requireSource(UUID sourceId) {
        List<SourceRef> rows = jdbc.query("""
                SELECT provider, external_id, source_release, snapshot_id, source_hash,
                       observed_at, retrieved_at
                  FROM place_source_records WHERE id = ?
                """, (rs, rowNum) -> new SourceRef(
                rs.getString("provider"), rs.getString("external_id"),
                rs.getString("source_release"), rs.getString("snapshot_id"),
                rs.getString("source_hash"), rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("retrieved_at", OffsetDateTime.class)), sourceId);
        if (rows.size() != 1) throw new IllegalArgumentException("unknown source record UUID");
        return rows.getFirst();
    }

    private void executeClaimed(UUID runId, UUID claimToken, Runnable work) {
        transactions.executeWithoutResult(transaction -> {
            ClaimedRunIdentity identity = lockAndRequireClaim(runId, claimToken);
            requireMonotonicStageHistory(
                    identity.pilotRunKey(), identity.canaryStage(), runId,
                    identity.reauthorizesRunId());
            int renewed = jdbc.update("""
                    UPDATE place_provider_sync_runs
                       SET claim_expires_at = clock_timestamp() + interval '5 minutes'
                     WHERE id = ? AND status = 'STARTED' AND claim_token = ?
                    """, runId, claimToken);
            if (renewed != 1) {
                throw new IllegalStateException("pilot import claim was lost");
            }
            work.run();
        });
    }

    private ClaimedRunIdentity lockAndRequireClaim(UUID runId, UUID claimToken) {
        ClaimedRunIdentity identity = jdbc.queryForObject("""
                SELECT pilot_run_key, canary_stage, reauthorizes_run_id
                  FROM place_provider_sync_runs
                 WHERE id = ? AND status = 'STARTED' AND claim_token = ?
                """, (rs, rowNum) -> new ClaimedRunIdentity(
                rs.getString("pilot_run_key"), rs.getString("canary_stage"),
                rs.getObject("reauthorizes_run_id", UUID.class)),
                runId, claimToken);
        if (identity == null) {
            throw new IllegalStateException("pilot import claim was lost");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                (rs, rowNum) -> rs.getObject(1), identity.pilotRunKey());
        Integer stillOwned = jdbc.queryForObject("""
                SELECT count(*) FROM place_provider_sync_runs
                 WHERE id = ? AND status = 'STARTED' AND claim_token = ?
                """, Integer.class, runId, claimToken);
        if (!Integer.valueOf(1).equals(stillOwned)) {
            throw new IllegalStateException("pilot import claim was lost");
        }
        return identity;
    }

    private boolean ownsClaim(UUID runId, UUID claimToken) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM place_provider_sync_runs
                 WHERE id = ? AND status = 'STARTED' AND claim_token = ?
                """, Integer.class, runId, claimToken);
        return Integer.valueOf(1).equals(count);
    }

    private void finishRun(
            UUID runId,
            UUID claimToken,
            String status,
            Counters counters,
            String failure
    ) {
        Integer updated = transactions.execute(transaction -> {
            ClaimedRunIdentity identity = lockAndRequireClaim(runId, claimToken);
            if ("SUCCEEDED".equals(status)) {
                requireMonotonicStageHistory(
                        identity.pilotRunKey(), identity.canaryStage(), runId,
                        identity.reauthorizesRunId());
                lockRedirectGraph();
                jdbc.update("""
                        UPDATE places place
                           SET catalog_status = 'ACTIVE', updated_at = now()
                          FROM place_pilot_catalog_writes write
                         WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                           AND write.place_id = place.id
                           AND place.origin = 'EXTERNAL_IMPORT'
                           AND place.catalog_status = 'PROVISIONAL'
                        """, runId);
                Integer unsafePlaces = jdbc.queryForObject("""
                        SELECT count(*)
                          FROM place_pilot_catalog_writes write
                          JOIN places place ON place.id = write.place_id
                         WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                           AND (place.origin <> 'EXTERNAL_IMPORT'
                                OR place.catalog_status <> 'ACTIVE')
                        """, Integer.class, runId);
                if (!Integer.valueOf(0).equals(unsafePlaces)) {
                    throw new IllegalStateException(
                            "pilot Places could not be activated atomically");
                }
            }
            return jdbc.update("""
                WITH completion AS (SELECT clock_timestamp() AS completed_at)
                UPDATE place_provider_sync_runs run
                   SET completed_at = completion.completed_at,
                       gate_deadline = CASE WHEN ? = 'SUCCEEDED'
                           THEN completion.completed_at + (? * interval '1 millisecond')
                           ELSE NULL END,
                       status = ?, source_count = ?, usable_count = ?,
                       source_rejected_count = ?, created_count = ?, linked_count = ?,
                       enriched_count = ?, auto_rejected_count = ?, quarantined_count = ?,
                       canary_eligible_count = ?, failure_reason = ?, claim_token = NULL,
                       claim_expires_at = NULL
                  FROM completion
                 WHERE id = ? AND status = 'STARTED' AND claim_token = ?
                """, status, gateDeadlineLease.toMillis(), status,
                counters.sourceCount, counters.usableCount,
                counters.sourceRejectedCount, counters.createdCount, counters.linkedCount,
                counters.enrichedCount, counters.autoRejectedCount, counters.quarantinedCount,
                counters.canaryEligibleCount, failure, runId, claimToken);
        });
        if (!Integer.valueOf(1).equals(updated)) {
            throw new IllegalStateException("pilot import claim was lost before completion");
        }
    }

    private static Duration requireSafeGateDeadlineLease(Duration value) {
        if (value == null || value.compareTo(MIN_GATE_DEADLINE_LEASE) < 0
                || value.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "Place canary gate deadline lease must be between 60 minutes and 24 hours");
        }
        return value;
    }

    private void failRunAndContain(
            UUID runId,
            UUID claimToken,
            Counters counters,
            String failure
    ) {
        transactions.executeWithoutResult(transaction -> {
            lockAndRequireClaim(runId, claimToken);
            OffsetDateTime occurredAt = jdbc.queryForObject(
                    "SELECT clock_timestamp()", OffsetDateTime.class);
            if (occurredAt == null) {
                throw new IllegalStateException("database clock is unavailable for containment");
            }
            int updated = jdbc.update("""
                    UPDATE place_provider_sync_runs
                       SET completed_at = ?, status = 'FAILED', source_count = ?,
                           usable_count = ?, source_rejected_count = ?, created_count = ?,
                           linked_count = ?, enriched_count = ?, auto_rejected_count = ?,
                           quarantined_count = ?, canary_eligible_count = ?, failure_reason = ?,
                           claim_token = NULL, claim_expires_at = NULL
                     WHERE id = ? AND status = 'STARTED' AND claim_token = ?
                    """, occurredAt, counters.sourceCount, counters.usableCount,
                    counters.sourceRejectedCount, counters.createdCount, counters.linkedCount,
                    counters.enrichedCount, counters.autoRejectedCount, counters.quarantinedCount,
                    counters.canaryEligibleCount, failure, runId, claimToken);
            if (updated != 1) {
                throw new IllegalStateException("pilot import claim was lost before containment");
            }
            rollback.retireRun(runId, occurredAt);
        });
    }

    private ExistingRun findRunByHash(String hash) {
        List<ExistingRun> rows = jdbc.query("""
                SELECT id, status, source_count, usable_count, source_rejected_count,
                       created_count, linked_count, enriched_count, auto_rejected_count,
                       quarantined_count, canary_eligible_count
                  FROM place_provider_sync_runs WHERE manifest_hash = ?
                """, (rs, rowNum) -> new ExistingRun(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getLong("source_count"), rs.getLong("usable_count"),
                rs.getLong("source_rejected_count"),
                rs.getLong("created_count"), rs.getLong("linked_count"),
                rs.getLong("enriched_count"), rs.getLong("auto_rejected_count"),
                rs.getLong("quarantined_count"), rs.getLong("canary_eligible_count")), hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Mirrors Python json.dumps(..., ensure_ascii=False, sort_keys=True, separators=(",", ":")). */
    private void appendPythonCanonicalJson(JsonNode node, StringBuilder output) {
        if (node.isObject()) {
            output.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(this::compareUnicodeCodePointOrder);
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) output.append(',');
                String name = names.get(index);
                appendPythonJsonString(name, output);
                output.append(':');
                appendPythonCanonicalJson(node.get(name), output);
            }
            output.append('}');
            return;
        }
        if (node.isArray()) {
            output.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) output.append(',');
                appendPythonCanonicalJson(node.get(index), output);
            }
            output.append(']');
            return;
        }
        if (node.isTextual()) {
            appendPythonJsonString(node.textValue(), output);
            return;
        }
        if (node.isIntegralNumber()) {
            output.append(node.bigIntegerValue());
            return;
        }
        if (node.isFloatingPointNumber()) {
            output.append(formatPythonJsonFloat(node.doubleValue()));
            return;
        }
        if (node.isBoolean()) {
            output.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNull()) {
            output.append("null");
            return;
        }
        throw new IllegalArgumentException("manifest contains a non-JSON value");
    }

    private UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits())
                    .array());
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable for deterministic UUIDv5", exception);
        }
    }

    private void lockRedirectGraph() {
        jdbc.query("SELECT pg_advisory_xact_lock(5517, 917)",
                (rs, rowNum) -> rs.getObject(1));
    }

    private int compareUnicodeCodePointOrder(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private void appendPythonJsonString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length();) {
            char first = value.charAt(index);
            if (Character.isSurrogate(first)) {
                if (!Character.isHighSurrogate(first) || index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "manifest contains an unpaired Unicode surrogate");
                }
            }
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append("\\u00");
                        output.append(Character.forDigit((codePoint >>> 4) & 0xf, 16));
                        output.append(Character.forDigit(codePoint & 0xf, 16));
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
        }
        output.append('"');
    }

    private String formatPythonJsonFloat(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("manifest numbers must be finite");
        }
        if (value == 0.0) {
            return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        BigDecimal absolute = decimal.abs();
        if (absolute.compareTo(new BigDecimal("0.0001")) < 0
                || absolute.compareTo(new BigDecimal("1e16")) >= 0) {
            int exponent = decimal.precision() - decimal.scale() - 1;
            String mantissa = decimal.movePointLeft(exponent)
                    .stripTrailingZeros().toPlainString();
            int magnitude = Math.abs(exponent);
            return mantissa + "e" + (exponent >= 0 ? "+" : "-")
                    + (magnitude < 10 ? "0" : "") + magnitude;
        }
        String plain = decimal.toPlainString();
        return plain.indexOf('.') >= 0 ? plain : plain + ".0";
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private Iterable<JsonNode> requiredArray(JsonNode parent, String field) {
        return requiredArrayNode(parent, field);
    }

    private ArrayNode requiredArrayNode(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return (ArrayNode) value;
    }

    private String requiredText(JsonNode parent, String field) {
        String value = textOrNull(parent, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private String textOrNull(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private UUID optionalUuid(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a UUID string");
        }
        try {
            return UUID.fromString(value.textValue());
        } catch (IllegalArgumentException invalidUuid) {
            throw new IllegalArgumentException(field + " must be a UUID string", invalidUuid);
        }
    }

    private String requiredCanaryStage(JsonNode manifest) {
        String stage = requiredText(manifest, "canary_stage");
        if (!Set.of("STAGE_1", "STAGE_2", "STAGE_3").contains(stage)) {
            throw new IllegalArgumentException("authorized manifest has an invalid canary stage");
        }
        return stage;
    }

    private void requireMonotonicStageHistory(
            String pilotRunKey,
            String canaryStage,
            UUID currentRunId,
            UUID reauthorizesRunId
    ) {
        int requestedStage = stageNumber(canaryStage);
        List<StageHistory> history = jdbc.query("""
                SELECT r.id, r.canary_stage, r.status,
                       COALESCE(g.gate_status, 'MISSING') AS gate_status,
                       count(w.id) FILTER (
                           WHERE w.rollback_state = 'NONE' AND p.catalog_status = 'ACTIVE'
                       ) AS active_writes
                  FROM place_provider_sync_runs r
                  LEFT JOIN place_pilot_canary_gates g ON g.sync_run_id = r.id
                  LEFT JOIN place_pilot_catalog_writes w ON w.sync_run_id = r.id
                  LEFT JOIN places p ON p.id = w.place_id
                 WHERE r.pilot_run_key = ? AND r.canary_stage <> 'DRY_RUN' AND r.id <> ?
                   AND (?::uuid IS NULL OR r.id <> ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM place_provider_sync_runs successor
                        WHERE successor.reauthorizes_run_id = r.id
                          AND successor.id <> ?
                   )
                 GROUP BY r.id, r.canary_stage, r.status, g.gate_status
                 ORDER BY r.started_at, r.id
                """, (rs, rowNum) -> new StageHistory(
                rs.getObject("id", UUID.class), rs.getString("canary_stage"),
                rs.getString("status"), rs.getString("gate_status"),
                rs.getLong("active_writes")), pilotRunKey, currentRunId,
                reauthorizesRunId, reauthorizesRunId, currentRunId);

        for (StageHistory prior : history) {
            int priorStage = stageNumber(prior.canaryStage());
            if (priorStage >= requestedStage) {
                throw new IllegalArgumentException(
                        "canary stages are single-use and must progress monotonically");
            }
            if (!"SUCCEEDED".equals(prior.status())
                    || !"PASSED".equals(prior.gateStatus())
                    || prior.activeWrites() == 0) {
                throw new IllegalArgumentException(
                        canaryStage + " is blocked by an incomplete or failed prior stage");
            }
        }
        for (int required = 1; required < requestedStage; required++) {
            int expectedStage = required;
            long count = history.stream()
                    .filter(row -> stageNumber(row.canaryStage()) == expectedStage)
                    .count();
            if (count != 1) {
                throw new IllegalArgumentException(
                        canaryStage + " requires exactly one passed STAGE_" + required);
            }
        }
    }

    private int stageNumber(String canaryStage) {
        return switch (canaryStage) {
            case "STAGE_1" -> 1;
            case "STAGE_2" -> 2;
            case "STAGE_3" -> 3;
            default -> throw new IllegalArgumentException("invalid canary stage");
        };
    }

    private long priorActiveWrites(String pilotRunKey, UUID currentRunId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM place_pilot_catalog_writes w
                JOIN place_provider_sync_runs r ON r.id = w.sync_run_id
                 WHERE r.pilot_run_key = ? AND r.id <> ? AND w.rollback_state = 'NONE'
                """, Long.class, pilotRunKey, currentRunId);
        return count == null ? 0 : count;
    }

    private void validateSelectedTarget(
            String pilotRunKey,
            UUID currentRunId,
            String canaryStage,
            JsonNode manifest,
            JsonNode candidate
    ) {
        String candidateKey = requiredText(candidate, "candidate_id");
        UUID placeId = UUID.fromString(requiredText(candidate, "canonical_place_id"));
        Integer priorCandidateWrite = jdbc.queryForObject("""
                SELECT count(*) FROM place_pilot_catalog_writes w
                JOIN place_validation_decisions d ON d.id = w.validation_decision_id
                 WHERE d.pilot_run_key = ? AND d.candidate_key = ?
                   AND w.sync_run_id <> ? AND w.rollback_state = 'NONE'
                """, Integer.class, pilotRunKey, candidateKey, currentRunId);
        if (priorCandidateWrite != null && priorCandidateWrite > 0) {
            throw new IllegalArgumentException("candidate was already imported by an earlier canary stage");
        }
        Integer unsafeCollision = jdbc.queryForObject("""
                SELECT count(*) FROM places p
                 WHERE p.id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_catalog_writes w
                        WHERE w.place_id = p.id AND w.sync_run_id = ?
                          AND w.rollback_state = 'NONE'
                   )
                """, Integer.class, placeId, currentRunId);
        if (unsafeCollision != null && unsafeCollision > 0) {
            UUID reauthorizesRunId = optionalUuid(manifest, "reauthorizes_run_id");
            Integer safePredecessor = reauthorizesRunId == null ? 0 : jdbc.queryForObject("""
                    SELECT count(*)
                      FROM place_provider_sync_runs prior_run
                      JOIN place_pilot_catalog_writes prior_write
                        ON prior_write.sync_run_id = prior_run.id
                      JOIN place_validation_decisions prior_decision
                        ON prior_decision.id = prior_write.validation_decision_id
                      JOIN places place ON place.id = prior_write.place_id
                     WHERE prior_run.id = ? AND prior_run.pilot_run_key = ?
                       AND prior_run.canary_stage = ?
                       AND prior_write.place_id = ?
                       AND prior_write.rollback_state IN
                           ('RETIRED', 'RETIRED_GRAPH_PROTECTED')
                       AND prior_decision.candidate_key = ?
                       AND place.origin = 'EXTERNAL_IMPORT'
                       AND place.catalog_status = 'RETIRED'
                       AND NOT EXISTS (
                           SELECT 1 FROM place_external_refs ref
                            WHERE ref.place_id = place.id
                              AND ref.status IN ('ACTIVE', 'MERGED')
                       )
                       AND NOT EXISTS (
                           SELECT 1 FROM place_pilot_catalog_writes live_write
                            WHERE live_write.place_id = place.id
                              AND live_write.rollback_state = 'NONE'
                       )
                    """, Integer.class, reauthorizesRunId, pilotRunKey, canaryStage,
                    placeId, candidateKey);
            if (!Integer.valueOf(1).equals(safePredecessor)) {
                throw new IllegalArgumentException(
                        "selected canonical UUID collides with an existing canonical Place");
            }
        }
        if (stageNumber(canaryStage) > 1) {
            Integer matchingPriorDecision = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM place_validation_decisions d
                      JOIN place_provider_sync_runs r ON r.id = d.sync_run_id
                     WHERE d.pilot_run_key = ? AND d.candidate_key = ?
                       AND d.validation_method_version = ?
                       AND d.decision_state = 'AUTO_CREATE' AND d.canary_eligible
                       AND d.candidate_hash = ? AND d.canonical_place_id = ?
                       AND r.id <> ? AND r.status = 'SUCCEEDED'
                       AND CASE r.canary_stage
                             WHEN 'STAGE_1' THEN 1 WHEN 'STAGE_2' THEN 2 ELSE 3
                           END < CASE ?
                             WHEN 'STAGE_1' THEN 1 WHEN 'STAGE_2' THEN 2 ELSE 3
                           END
                    """, Integer.class, pilotRunKey, candidateKey,
                    requiredText(manifest, "method_version"),
                    requiredText(candidate, "candidate_hash"), placeId, currentRunId, canaryStage);
            if (matchingPriorDecision == null || matchingPriorDecision == 0) {
                throw new IllegalArgumentException(
                        "later-stage selection must match a prior canary-eligible decision");
            }
        }
    }

    private String requiredExistenceAssessment(JsonNode candidate) {
        String assessment = requiredText(candidate, "existence_assessment");
        if (!Set.of("HIGH", "MEDIUM", "LOW", "UNKNOWN").contains(assessment)) {
            throw new IllegalArgumentException("invalid existence assessment");
        }
        return assessment;
    }

    private Double optionalFiniteDouble(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return value.doubleValue();
    }

    private double requiredFiniteDouble(JsonNode parent, String field) {
        Double value = optionalFiniteDouble(parent, field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private void validateOptionalScopedPoint(JsonNode node, String label) {
        Double latitude = optionalFiniteDouble(node, "latitude");
        Double longitude = optionalFiniteDouble(node, "longitude");
        if (latitude == null && longitude == null) return;
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException(label + " must provide both coordinates or neither");
        }
        validateScopedPoint(latitude, longitude, label);
    }

    private void validateRequiredScopedPoint(JsonNode node, String label) {
        validateScopedPoint(requiredFiniteDouble(node, "latitude"),
                requiredFiniteDouble(node, "longitude"), label);
    }

    private void validateScopedPoint(double latitude, double longitude, String label) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(label + " coordinates are out of range");
        }
        double latitudeDelta = Math.toRadians(latitude - EXPECTED_CENTER_LATITUDE);
        double longitudeDelta = Math.toRadians(longitude - EXPECTED_CENTER_LONGITUDE);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(EXPECTED_CENTER_LATITUDE))
                * Math.cos(Math.toRadians(latitude))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        double clamped = Math.max(0, Math.min(1, a));
        double distanceMeters = 6_371_008.8 * 2
                * Math.atan2(Math.sqrt(clamped), Math.sqrt(1 - clamped));
        if (distanceMeters > EXPECTED_RADIUS_METERS + 0.01) {
            throw new IllegalArgumentException(label + " lies outside Didim Core");
        }
    }

    private OffsetDateTime parseTime(JsonNode parent, String field) {
        String value = requiredText(parent, field);
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException notOffsetDateTime) {
            try {
                return LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException notDate) {
                throw new IllegalArgumentException(field + " must be an ISO date or offset timestamp");
            }
        }
    }

    private String sanitizeFailure(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return name;
        return (name + ": " + message).substring(0, Math.min(500, name.length() + 2 + message.length()));
    }

    private static final class Counters {
        long sourceCount;
        long usableCount;
        long sourceRejectedCount;
        long createdCount;
        long linkedCount;
        long enrichedCount;
        long autoRejectedCount;
        long quarantinedCount;
        long canaryEligibleCount;
    }

    private record SourceRef(
            String provider,
            String externalId,
            String release,
            String snapshotId,
            String sourceHash,
            OffsetDateTime observedAt,
            OffsetDateTime retrievedAt
    ) {}

    private record ManifestSource(
            String provider,
            boolean usable,
            String sourceHash,
            boolean fresh,
            boolean negativeOperatingSignal,
            boolean foursquareUpstreamLineage
    ) {}

    private record RunLease(UUID token, OffsetDateTime expiresAt) {}

    private record ClaimedRunIdentity(
            String pilotRunKey,
            String canaryStage,
            UUID reauthorizesRunId
    ) {}

    private record ReuseLineage(UUID predecessorWriteId, UUID predecessorRunId) {}

    private record StageHistory(
            UUID runId,
            String canaryStage,
            String status,
            String gateStatus,
            long activeWrites
    ) {}

    private record ExistingRun(
            UUID runId,
            String status,
            long sourceCount,
            long usableCount,
            long sourceRejectedCount,
            long createdCount,
            long linkedCount,
            long enrichedCount,
            long autoRejectedCount,
            long quarantinedCount,
            long canaryEligibleCount
    ) {
        ImportResult toResult(boolean alreadyImported) {
            return new ImportResult(runId, alreadyImported, status, sourceCount, usableCount,
                    sourceRejectedCount, createdCount, linkedCount, enrichedCount,
                    autoRejectedCount, quarantinedCount, canaryEligibleCount);
        }
    }

    public record ImportResult(
            UUID runId,
            boolean alreadyImported,
            String status,
            long sourceCount,
            long usableCount,
            long sourceRejectedCount,
            long createdCount,
            long linkedCount,
            long enrichedCount,
            long autoRejectedCount,
            long quarantinedCount,
            long canaryEligibleCount
    ) {}
}

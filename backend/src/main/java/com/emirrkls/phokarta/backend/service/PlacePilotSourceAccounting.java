package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate source, candidate and selection populations; no classification rules or writes. */
public final class PlacePilotSourceAccounting {
    private static final Set<String> STATES = Set.of(
            "AUTO_LINK", "AUTO_CREATE", "AUTO_ENRICH", "AUTO_REJECT", "QUARANTINE");

    private PlacePilotSourceAccounting() {}

    /** Only the importer mints this receipt, after its unchanged hash/authorization validation. */
    public static final class Approved {
        private final UUID runId;
        private final String manifestHash;
        private final Set<UUID> sources;
        private final Set<UUID> usable;
        private final Set<UUID> rejected;
        private final Map<String, Candidate> candidates;
        private final Map<String, Long> decisions;
        private final Set<UUID> selected;

        private Approved(UUID runId, String manifestHash, Set<UUID> sources,
                         Set<UUID> usable, Set<UUID> rejected,
                         Map<String, Candidate> candidates, Map<String, Long> decisions,
                         Set<UUID> selected) {
            this.runId = runId;
            this.manifestHash = manifestHash;
            this.sources = Set.copyOf(sources);
            this.usable = Set.copyOf(usable);
            this.rejected = Set.copyOf(rejected);
            this.candidates = Map.copyOf(candidates);
            this.decisions = Map.copyOf(decisions);
            this.selected = Set.copyOf(selected);
        }

        public UUID runId() { return runId; }
        public String manifestHash() { return manifestHash; }
        public Set<UUID> sourceIds() { return sources; }
    }

    record Source(UUID id, boolean usable, boolean validState) {
        static Source persisted(UUID id, JsonNode provenance) {
            JsonNode state = provenance.path("source_record_state");
            JsonNode reason = provenance.path("source_rejection_reason");
            boolean absent = state.isMissingNode() || state.isNull();
            boolean rejected = state.isTextual() && "SOURCE_REJECTED".equals(state.textValue());
            boolean hasReason = reason.isTextual() && !reason.textValue().isBlank();
            boolean valid = provenance.isObject()
                    && (rejected ? hasReason : absent && (reason.isMissingNode() || reason.isNull()));
            return new Source(id, !rejected, valid);
        }
    }

    record Candidate(String key, String state, List<UUID> sourceIds,
                     UUID canonicalId, boolean eligible, boolean selected,
                     Integer rank, String candidateHash) {
        Candidate {
            List<UUID> sorted = new ArrayList<>(sourceIds);
            sorted.sort(Comparator.nullsFirst(Comparator.comparing(UUID::toString)));
            sourceIds = Collections.unmodifiableList(sorted);
        }

        static Candidate manifest(JsonNode candidate) {
            List<UUID> sources = new ArrayList<>();
            candidate.path("source_record_ids").forEach(id -> sources.add(UUID.fromString(id.asText())));
            return new Candidate(candidate.path("candidate_id").asText(),
                    candidate.path("decision").asText(), sources,
                    candidate.hasNonNull("canonical_place_id")
                            ? UUID.fromString(candidate.path("canonical_place_id").asText()) : null,
                    candidate.path("canary_eligible").asBoolean(),
                    candidate.path("selected_for_stage").asBoolean(),
                    candidate.hasNonNull("selection_rank") ? candidate.path("selection_rank").intValue() : null,
                    candidate.path("candidate_hash").asText());
        }
    }

    record Declared(long total, long usable, long rejected, long created,
                    long linked, long enriched, long autoRejected, long quarantined,
                    long eligible) {}

    /** Reads approved observations, including immutable rows reused by a later stage.
     * Also includes unexpected observations owned by this run so extras cannot be hidden. */
    static ObjectNode inspectPersisted(JdbcTemplate jdbc, Approved approved) {
        Declared declared = jdbc.queryForObject("""
                SELECT source_count, usable_count, source_rejected_count, created_count,
                       linked_count, enriched_count, auto_rejected_count, quarantined_count,
                       canary_eligible_count
                  FROM place_provider_sync_runs WHERE id = ?
                """, (rs, row) -> new Declared(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                rs.getLong(9)), approved.runId());
        ObjectMapper mapper = new ObjectMapper();
        List<Source> sources = jdbc.query("""
                SELECT id, provenance FROM place_source_records
                 WHERE id = ANY(?) OR sync_run_id = ?
                """, statement -> {
            statement.setArray(1, statement.getConnection().createArrayOf("uuid",
                    approved.sourceIds().toArray(UUID[]::new)));
            statement.setObject(2, approved.runId());
        }, (rs, row) -> {
            try {
                return Source.persisted(rs.getObject("id", UUID.class),
                        mapper.readTree(rs.getString("provenance")));
            } catch (java.io.IOException invalid) {
                throw new SQLException("invalid source-state provenance", invalid);
            }
        });
        List<Candidate> candidates = jdbc.query("""
                SELECT candidate_key, decision_state, source_record_ids, canonical_place_id,
                       canary_eligible, selected_for_stage, selection_rank, candidate_hash
                  FROM place_validation_decisions WHERE sync_run_id = ?
                """, (rs, row) -> new Candidate(rs.getString(1), rs.getString(2),
                Arrays.stream((Object[]) rs.getArray(3).getArray())
                        .map(id -> UUID.fromString(id.toString())).toList(),
                rs.getObject(4, UUID.class), rs.getBoolean(5), rs.getBoolean(6),
                rs.getObject(7, Integer.class), rs.getString(8)), approved.runId());
        return inspect(approved, declared, sources, candidates);
    }

    // Package-private: production callers must use the importer's verified receipt factory.
    static Approved approved(JsonNode manifest, String verifiedHash) {
        PlacePilotImportService.validateAccountingPopulations(manifest);
        Set<UUID> all = new HashSet<>();
        Set<UUID> usable = new HashSet<>();
        Set<UUID> rejected = new HashSet<>();
        for (JsonNode source : manifest.path("source_records")) {
            UUID id = UUID.fromString(source.path("source_record_id").asText());
            Source state = Source.persisted(id, source.path("provenance"));
            if (!all.add(id) || !state.validState()
                    || !source.path("usable").isBoolean()
                    || source.path("usable").booleanValue() != state.usable()) {
                throw new IllegalArgumentException("source accounting identity/state is inconsistent");
            }
            (state.usable() ? usable : rejected).add(id);
        }
        Map<String, Candidate> candidates = new HashMap<>();
        Map<String, Long> decisions = new HashMap<>();
        Set<UUID> coverage = new HashSet<>();
        Set<UUID> selected = new HashSet<>();
        for (JsonNode node : manifest.path("candidates")) {
            Candidate candidate = Candidate.manifest(node);
            if (candidates.put(candidate.key(), candidate) != null || !STATES.contains(candidate.state())) {
                throw new IllegalArgumentException("candidate accounting must have one final decision");
            }
            decisions.merge(candidate.state(), 1L, Long::sum);
            for (UUID id : candidate.sourceIds()) {
                if (!usable.contains(id) || !coverage.add(id)) {
                    throw new IllegalArgumentException("candidate source accounting membership is invalid");
                }
            }
            if (candidate.selected() && (!candidate.eligible()
                    || !"AUTO_CREATE".equals(candidate.state()) || candidate.canonicalId() == null
                    || !selected.add(candidate.canonicalId()))) {
                throw new IllegalArgumentException("selected accounting identity is not approved AUTO_CREATE");
            }
        }
        if (!coverage.equals(usable)) {
            throw new IllegalArgumentException("candidate source coverage must equal the usable source set");
        }
        return new Approved(UUID.fromString(manifest.path("run_id").asText()), verifiedHash,
                all, usable, rejected, candidates, decisions, selected);
    }

    static ObjectNode inspect(Approved approved, Declared declared,
                              List<Source> observations, List<Candidate> decisions) {
        Set<String> failures = new LinkedHashSet<>();
        Set<UUID> actual = new HashSet<>();
        Set<UUID> usable = new HashSet<>();
        Set<UUID> rejected = new HashSet<>();
        for (Source source : observations) {
            if (source.id() == null || !actual.add(source.id()) || !source.validState()) {
                failures.add("SOURCE_IDENTITY_OR_STATE");
            }
            (source.usable() ? usable : rejected).add(source.id());
        }
        if (declared.total() < 0 || declared.usable() < 0 || declared.rejected() < 0
                || declared.usable() > declared.total()
                || declared.rejected() != declared.total() - declared.usable()) {
            failures.add("SOURCE_TOTAL_PARTITION");
        }
        if (declared.total() != observations.size() || declared.total() != approved.sources.size()
                || !actual.equals(approved.sources)) failures.add("ACTUAL_SOURCE_TOTAL_OR_SET");
        if (declared.usable() != usable.size() || !usable.equals(approved.usable)) {
            failures.add("ACTUAL_USABLE_COUNT_OR_SET");
        }
        if (declared.rejected() != rejected.size() || !rejected.equals(approved.rejected)) {
            failures.add("ACTUAL_REJECTED_COUNT_OR_SET");
        }
        Set<UUID> coverage = new HashSet<>();
        Set<UUID> selected = new HashSet<>();
        Map<String, Candidate> actualCandidates = new HashMap<>();
        Map<String, Long> distribution = new HashMap<>();
        long eligible = 0;
        for (Candidate candidate : decisions) {
            if (candidate.key() == null || candidate.key().isBlank()
                    || actualCandidates.put(candidate.key(), candidate) != null
                    || !STATES.contains(candidate.state())) failures.add("ONE_FINAL_DECISION_PER_GROUP");
            distribution.merge(candidate.state(), 1L, Long::sum);
            if (candidate.sourceIds().isEmpty()) failures.add("EMPTY_CANDIDATE_MEMBERSHIP");
            for (UUID id : candidate.sourceIds()) {
                if (id == null || !actual.contains(id)) failures.add("UNKNOWN_CANDIDATE_SOURCE");
                if (rejected.contains(id)) failures.add("REJECTED_SOURCE_IN_CANDIDATE");
                if (!coverage.add(id)) failures.add("DUPLICATE_SOURCE_MEMBERSHIP");
            }
            if (candidate.eligible()) {
                eligible++;
                if (!"AUTO_CREATE".equals(candidate.state())) failures.add("ELIGIBLE_NOT_AUTO_CREATE");
            }
            if (candidate.selected()) {
                if (!candidate.eligible() || !"AUTO_CREATE".equals(candidate.state())
                        || candidate.canonicalId() == null || !selected.add(candidate.canonicalId())) {
                    failures.add("SELECTED_NOT_ELIGIBLE_AUTO_CREATE");
                }
            }
        }
        if (!coverage.equals(usable)) failures.add("USABLE_COVERAGE_SET_MISMATCH");
        if (!actualCandidates.equals(approved.candidates)) failures.add("SEALED_CANDIDATE_IDENTITY_MISMATCH");
        if (decisions.size() != approved.candidates.size()
                || distribution.values().stream().mapToLong(Long::longValue).sum() != approved.candidates.size()
                || !distribution.equals(approved.decisions)) failures.add("CANDIDATE_DECISION_TOTALS");
        if (!selected.equals(approved.selected)) failures.add("SEALED_SELECTED_IDENTITY_MISMATCH");
        if (declared.created() != selected.size() || declared.eligible() != eligible
                || declared.linked() != distribution.getOrDefault("AUTO_LINK", 0L)
                || declared.enriched() != distribution.getOrDefault("AUTO_ENRICH", 0L)
                || declared.autoRejected() != distribution.getOrDefault("AUTO_REJECT", 0L)
                || declared.quarantined() != distribution.getOrDefault("QUARANTINE", 0L)) {
            failures.add("DECLARED_CANDIDATE_COUNTERS");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("version", "source-candidate-accounting-v1");
        result.put("source_total", actual.size());
        result.put("source_usable", usable.size());
        result.put("source_rejected_before_grouping", rejected.size());
        result.put("distinct_candidate_source_coverage", coverage.size());
        result.put("candidate_group_count", actualCandidates.size());
        ObjectNode counts = result.putObject("candidate_decisions");
        STATES.stream().sorted().forEach(state -> counts.put(state, distribution.getOrDefault(state, 0L)));
        result.put("selected_count", selected.size());
        result.put("usable_source_set_equal", coverage.equals(usable));
        result.put("sealed_source_sets_equal", actual.equals(approved.sources)
                && usable.equals(approved.usable) && rejected.equals(approved.rejected));
        result.put("sealed_candidate_identities_equal", actualCandidates.equals(approved.candidates));
        result.put("sealed_selected_identities_equal", selected.equals(approved.selected));
        result.put("violations", failures.size());
        var reasons = result.putArray("failure_categories");
        failures.forEach(reasons::add);
        result.put("passed", failures.isEmpty());
        return result;
    }
}

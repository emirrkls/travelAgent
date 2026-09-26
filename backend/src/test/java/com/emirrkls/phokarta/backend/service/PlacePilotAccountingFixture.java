package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.emirrkls.phokarta.backend.service.PlacePilotSourceAccounting.*;

/** Synthetic identities, never the live sealed payload. Shared unit/PostGIS adversarial cases. */
final class PlacePilotAccountingFixture {
    final ObjectNode manifest;
    final Approved approved;
    Declared declared;
    final List<Source> sources = new ArrayList<>();
    final List<Candidate> candidates = new ArrayList<>();

    PlacePilotAccountingFixture(int total, int usable, int groups, int creates) {
        this(total, usable, groups, creates, creates);
    }

    PlacePilotAccountingFixture(int total, int usable, int groups, int creates, int selectedLimit) {
        manifest = JsonNodeFactory.instance.objectNode();
        manifest.put("run_id", UUID.randomUUID().toString());
        manifest.put("reporting_schema_version", "didim-autonomy-accounting-v1");
        manifest.putObject("source_record_states").put("total", total).put("usable", usable)
                .put("rejected_before_canonical_grouping", total - usable);
        manifest.put("candidate_group_count", groups);
        manifest.putObject("candidate_decisions").put("AUTO_CREATE", creates)
                .put("QUARANTINE", groups - creates).put("AUTO_LINK", 0)
                .put("AUTO_ENRICH", 0).put("AUTO_REJECT", 0);
        var sourceNodes = manifest.putArray("source_records");
        for (int index = 0; index < total; index++) {
            UUID id = new UUID(approvedRun().getMostSignificantBits(), index + 1);
            ObjectNode node = sourceNodes.addObject();
            node.put("source_record_id", id.toString()).put("usable", index < usable);
            var provenance = node.putObject("provenance");
            if (index >= usable) provenance.put("source_record_state", "SOURCE_REJECTED")
                    .put("source_rejection_reason", "synthetic rejected observation");
            sources.add(Source.persisted(id, provenance));
        }
        var candidateNodes = manifest.putArray("candidates");
        int sourceIndex = 0;
        for (int index = 0; index < groups; index++) {
            ObjectNode node = candidateNodes.addObject();
            boolean eligible = index < creates;
            boolean selected = index < selectedLimit;
            node.put("candidate_id", "group-" + index)
                    .put("decision", eligible ? "AUTO_CREATE" : "QUARANTINE")
                    .put("canary_eligible", eligible).put("selected_for_stage", selected)
                    .put("candidate_hash", "a".repeat(64));
            if (eligible) node.put("canonical_place_id", new UUID(2, index + 1).toString())
                    .put("selection_rank", index + 1);
            var ids = node.putArray("source_record_ids");
            ids.add(sources.get(sourceIndex++).id().toString());
            if (index < usable - groups) ids.add(sources.get(sourceIndex++).id().toString());
            candidates.add(Candidate.manifest(node));
        }
        declared = new Declared(total, usable, total - usable, selectedLimit, 0, 0, 0,
                groups - creates, creates);
        approved = PlacePilotSourceAccounting.approved(manifest, "b".repeat(64));
    }

    UUID approvedRun() { return UUID.fromString(manifest.path("run_id").asText()); }
    static PlacePilotAccountingFixture small() { return new PlacePilotAccountingFixture(3, 2, 2, 1); }
    static List<String> failures() {
        return List.of("rejected-in-selected", "missing-usable", "forged-source",
                "wrong-total", "wrong-usable", "wrong-rejected", "duplicate-masks-missing",
                "missing-decision", "changed-decision", "unapproved-selected-id",
                "selected-not-eligible", "same-count-rejected-swap", "missing-observation",
                "extra-observation", "invalid-rejected-provenance");
    }

    void corrupt(String scenario) {
        Candidate first = candidates.getFirst();
        List<UUID> membership = first.sourceIds();
        UUID canonical = first.canonicalId();
        boolean eligible = first.eligible(), selected = first.selected();
        String state = first.state();
        switch (scenario) {
            case "rejected-in-selected" -> membership = List.of(sources.get(2).id());
            case "missing-usable" -> { candidates.removeLast(); return; }
            case "forged-source" -> membership = List.of(new UUID(99, 99));
            case "wrong-total" -> declared = new Declared(4, 2, 1, 1, 0, 0, 0, 1, 1);
            case "wrong-usable" -> declared = new Declared(3, 1, 1, 1, 0, 0, 0, 1, 1);
            case "wrong-rejected" -> declared = new Declared(3, 2, 0, 1, 0, 0, 0, 1, 1);
            case "duplicate-masks-missing" -> {
                membership = List.of(sources.getFirst().id(), sources.getFirst().id());
                candidates.removeLast();
            }
            case "missing-decision" -> { candidates.removeLast(); return; }
            case "changed-decision" -> {
                Candidate q = candidates.getLast();
                candidates.set(1, new Candidate(q.key(), "AUTO_REJECT", q.sourceIds(),
                        null, false, false, null, q.candidateHash())); return;
            }
            case "unapproved-selected-id" -> canonical = new UUID(99, 98);
            case "selected-not-eligible" -> { eligible = false; selected = false; }
            case "same-count-rejected-swap" -> {
                sources.set(0, new Source(sources.getFirst().id(), false, true));
                sources.set(2, new Source(sources.get(2).id(), true, true)); return;
            }
            case "missing-observation" -> { sources.removeLast(); return; }
            case "extra-observation" -> { sources.add(new Source(new UUID(99, 97), true, true)); return; }
            case "invalid-rejected-provenance" -> {
                sources.set(2, new Source(sources.get(2).id(), false, false)); return;
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        candidates.set(0, new Candidate(first.key(), state, membership, canonical,
                eligible, selected, eligible ? first.rank() : null, first.candidateHash()));
    }
}

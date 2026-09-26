package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PlacePilotImportManifestHashTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private PlacePilotImportService importer;

    @BeforeEach
    void setUp() {
        importer = new PlacePilotImportService(
                mock(JdbcTemplate.class), mapper,
                mock(PlatformTransactionManager.class),
                mock(PlacePilotRollbackService.class));
    }

    @Test
    void canonicalHashMatchesPythonCompactUtf8JsonContract() throws Exception {
        JsonNode value = mapper.readTree("""
                {
                  "z": "İzmir",
                  "array": [{"b": 2, "a": 1}, "é", 3],
                  "a": {"β": 2, "a": 1}
                }
                """);

        assertThat(importer.hashManifest(value))
                .isEqualTo("1ce2b329a84f1240e567cc715e4479c8e777fa3e88aee094bcbc003513a43229");

        ObjectNode arrayReordered = value.deepCopy();
        ArrayNode original = (ArrayNode) arrayReordered.path("array");
        JsonNode first = original.remove(0);
        original.add(first);
        assertThat(importer.hashManifest(arrayReordered))
                .isNotEqualTo(importer.hashManifest(value));

        JsonNode numericValue = mapper.readTree("""
                {"tiny": 1e-07, "integer": 6000, "negative_zero": -0.0,
                 "decimal": 37.3751}
                """);
        assertThat(importer.hashManifest(numericValue))
                .isEqualTo("9b3b07f2fa079fcaf6b556340591e1184c03a113852e533f5b9447fe6b307720");

        JsonNode numericEdges = mapper.readTree("""
                {"floats":[0.0,-0.0,1.0,-1.0,1e-8,1e-7,1e-6,1e-5,1e-4,1e-3,
                  1e15,1e16,1e17,1e20,1e21,1.2345678901234567,
                  2.2250738585072014e-308,1.7976931348623157e308]}
                """);
        assertThat(importer.hashManifest(numericEdges))
                .isEqualTo("80197b6946feb7ce1423f17274e47393ee967c55ee19fe867f456cf41db8f7d9");

        ObjectNode unicodeEdges = mapper.createObjectNode();
        unicodeEdges.put(new String(Character.toChars(0x10000)), "supplementary");
        unicodeEdges.put(String.valueOf((char) 0xe000), "bmp");
        unicodeEdges.put("control", "x" + (char) 0x1f + "y");
        unicodeEdges.put("line", "a" + (char) 0x2028 + "b");
        assertThat(importer.hashManifest(unicodeEdges))
                .isEqualTo("9857dc93d37ec9458efdd3926274ec762e2442e278347049eab41ef084bf51a5");
    }

    @Test
    void planDigestIgnoresOnlyRunStageAuthorizationAndSelectionMetadata() {
        ObjectNode stageOne = planManifest("STAGE_1", true, 1);
        ObjectNode stageTwo = stageOne.deepCopy();
        stageTwo.put("run_id", UUID.randomUUID().toString());
        stageTwo.put("pilot_run_key", "didim-frozen-plan-successor");
        stageTwo.put("canary_stage", "STAGE_2");
        stageTwo.put("authorization_reference", "approval-successor");
        stageTwo.put("reauthorizes_run_id", UUID.randomUUID().toString());
        stageTwo.put("status", "AUTONOMOUS_CANARY_AUTHORIZED");
        ((ObjectNode) stageTwo.path("candidates").get(0)).put("selected_for_stage", false);

        assertThat(importer.hashPlan(stageTwo)).isEqualTo(importer.hashPlan(stageOne));

        ((ObjectNode) stageTwo.path("candidates").get(0)).put("selection_rank", 2);
        assertThat(importer.hashPlan(stageTwo)).isNotEqualTo(importer.hashPlan(stageOne));
    }

    @Test
    void sourceMethodAllowlistPreservesFrozenAndCurrentCanonicalizationProvenance() {
        assertThat(PlacePilotImportService.isApprovedSourceMethodVersion(
                "didim-canonicalization-v2")).isTrue();
        assertThat(PlacePilotImportService.isApprovedSourceMethodVersion(
                "didim-canonicalization-v3")).isTrue();
        assertThat(PlacePilotImportService.isApprovedSourceMethodVersion(
                "didim-canonicalization-v1")).isFalse();
    }

    @Test
    void overtureFoursquareUpstreamLineageCannotMasqueradeAsIndependentEvidence()
            throws Exception {
        JsonNode byProvider = mapper.readTree("""
                {"provenance":{"sources":[
                  {"provider":"Foursquare","dataset":"places","resource":"poi"}
                ]}}
                """);
        JsonNode byDataset = mapper.readTree("""
                {"provenance":{"sources":{
                  "provider":"wrapper","dataset":"fsq","resource":"places"
                }}}
                """);
        JsonNode unrelated = mapper.readTree("""
                {"provenance":{"sources":[
                  {"provider":"meta","dataset":"Overture","resource":"confidence_calculation"}
                ]}}
                """);
        JsonNode malformed = mapper.readTree("""
                {"provenance":{"sources":"foursquare"}}
                """);

        assertThat(PlacePilotImportService.hasFoursquareUpstreamLineage(byProvider)).isTrue();
        assertThat(PlacePilotImportService.hasFoursquareUpstreamLineage(byDataset)).isTrue();
        assertThat(PlacePilotImportService.hasFoursquareUpstreamLineage(unrelated)).isFalse();
        assertThatThrownBy(() ->
                PlacePilotImportService.hasFoursquareUpstreamLineage(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sources must be an object or an array");

        assertThat(PlacePilotImportService.hasSafeIndependentProviderEvidence(
                Set.of("OVERTURE", "FSQ"), Set.of("a".repeat(64), "b".repeat(64)),
                true, true, false, false)).isTrue();
        assertThat(PlacePilotImportService.hasSafeIndependentProviderEvidence(
                Set.of("OVERTURE", "FSQ"), Set.of("a".repeat(64), "b".repeat(64)),
                true, true, false, true)).isFalse();
    }

    private ObjectNode planManifest(String stage, boolean selected, int rank) {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("status", "AUTONOMOUS_CANARY_AUTHORIZED");
        manifest.put("authorization_reference", "approval-initial");
        manifest.put("canary_stage", stage);
        manifest.put("pilot_run_key", "didim-frozen-plan-initial");
        manifest.put("run_id", UUID.randomUUID().toString());
        ArrayNode candidates = manifest.putArray("candidates");
        ObjectNode candidate = candidates.addObject();
        candidate.put("candidate_key", "candidate-1");
        candidate.put("selected_for_stage", selected);
        candidate.put("selection_rank", rank);
        candidate.putArray("source_record_ids").add("source-b").add("source-a");
        return manifest;
    }
}

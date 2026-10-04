package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** V3 only: durable failure/request, independently read-only inspection, locked revalidation, retirement. */
public final class PlacePilotV3ContainmentService {
    private final JdbcTemplate jdbc;
    private final PlacePilotRollbackService rollback;
    private final TransactionTemplate reads, writes;
    public PlacePilotV3ContainmentService(JdbcTemplate jdbc, PlacePilotRollbackService rollback, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.rollback=rollback;
        reads=new TransactionTemplate(manager); reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes=new TransactionTemplate(manager); writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public ObjectNode inspect(UUID run, String hash) {
        return reads.execute(t -> inspectLocked(run,hash));
    }
    private ObjectNode inspectLocked(UUID run, String hash) {
        require(run!=null && !run.equals(PlacePilotV3Policy.CONTAINED_RUN) && hash!=null && hash.matches("[0-9a-f]{64}"));
        PlacePilotRollbackOperationsService.validateSchema(jdbc);
        Long valid=jdbc.queryForObject("""
            SELECT count(*) FROM place_provider_sync_runs b JOIN place_provider_sync_runs a ON a.id=b.reauthorizes_run_id
            WHERE b.id=? AND b.manifest_hash=? AND b.method_version=? AND b.canary_stage='STAGE_1'
              AND b.status='SUCCEEDED' AND a.id=? AND a.method_version=? AND a.status='SUCCEEDED'
              AND a.pilot_run_key=b.pilot_run_key AND a.canary_stage=b.canary_stage
              AND b.scope_name='didim_core' AND b.scope_center_latitude=37.3751 AND b.scope_center_longitude=27.2678 AND b.scope_radius_meters=6000
              AND a.scope_name=b.scope_name AND a.scope_center_latitude=b.scope_center_latitude
              AND a.scope_center_longitude=b.scope_center_longitude AND a.scope_radius_meters=b.scope_radius_meters
              AND a.resolved_release=b.resolved_release AND a.snapshot_id IS NOT DISTINCT FROM b.snapshot_id
              AND b.checkpoint_state->>'catalog_operation'='READOPT_RETIRED'
              AND b.checkpoint_state->>'predecessor_manifest_hash'=a.manifest_hash
              AND b.checkpoint_state->>'source_observation_owner_run_id'=a.id::text
              AND EXISTS(SELECT 1 FROM place_pilot_canary_gates g WHERE g.sync_run_id=a.id AND g.gate_status='FAILED')
              AND (SELECT count(*) FROM place_pilot_catalog_writes w WHERE w.sync_run_id=a.id)=71
              AND NOT EXISTS(SELECT 1 FROM place_pilot_catalog_writes w WHERE w.sync_run_id=a.id AND w.rollback_state NOT IN ('RETIRED','RETIRED_GRAPH_PROTECTED'))
            """,Long.class,run,hash,PlacePilotV3Policy.V3,PlacePilotV3Policy.CONTAINED_RUN,PlacePilotV3Policy.V2);
        require(Long.valueOf(1).equals(valid));
        List<String> expected=jdbc.query("""
            SELECT canonical_place_id::text FROM place_validation_decisions WHERE sync_run_id=? AND selected_for_stage ORDER BY canonical_place_id
            """,(rs,n)->rs.getString(1),PlacePilotV3Policy.CONTAINED_RUN);
        List<String> actual=jdbc.query("""
            SELECT w.place_id::text FROM place_pilot_catalog_writes w JOIN places p ON p.id=w.place_id
             WHERE w.sync_run_id=? AND p.origin='EXTERNAL_IMPORT' ORDER BY w.place_id
            """,(rs,n)->rs.getString(1),run);
        require(expected.size()==71 && new HashSet<>(expected).size()==71 && expected.equals(actual));
        Long changed=jdbc.queryForObject("""
            SELECT count(*) FROM place_validation_decisions b LEFT JOIN place_validation_decisions a
              ON a.sync_run_id=? AND a.candidate_key=b.candidate_key AND a.candidate_hash=b.candidate_hash
             AND a.decision_state=b.decision_state AND a.canonical_place_id IS NOT DISTINCT FROM b.canonical_place_id
             AND a.source_record_ids=b.source_record_ids AND a.canary_eligible=b.canary_eligible
             AND a.selected_for_stage=b.selected_for_stage AND a.selection_rank IS NOT DISTINCT FROM b.selection_rank
            WHERE b.sync_run_id=? AND a.id IS NULL
            """,Long.class,PlacePilotV3Policy.CONTAINED_RUN,run);
        require(Long.valueOf(0).equals(changed));
        Long missing=jdbc.queryForObject("""
            SELECT count(*) FROM place_validation_decisions a WHERE a.sync_run_id=? AND NOT EXISTS
              (SELECT 1 FROM place_validation_decisions b WHERE b.sync_run_id=? AND b.candidate_key=a.candidate_key)
            """,Long.class,PlacePilotV3Policy.CONTAINED_RUN,run);
        require(Long.valueOf(0).equals(missing));
        List<String> expectedRefs=jdbc.query("""
            SELECT s.provider||'|'||s.external_id||'|'||d.canonical_place_id||'|'||s.id
              FROM place_validation_decisions d CROSS JOIN LATERAL unnest(d.source_record_ids) sid(id)
              JOIN place_source_records s ON s.id=sid.id WHERE d.sync_run_id=? AND d.selected_for_stage ORDER BY 1
            """,(rs,n)->rs.getString(1),PlacePilotV3Policy.CONTAINED_RUN);
        var plan=rollback.inspectRun(run);
        require(plan.ownershipProvenanceValid() && plan.affectedRunIds().equals(Set.of(run)) && !plan.superseded());
        boolean contained=plan.pendingWriteCount()==0;
        require(contained || plan.pendingWriteCount()==71);
        List<String> refs=jdbc.query("""
            SELECT provider||'|'||external_id||'|'||place_id||'|'||current_source_record_id
              FROM place_external_refs WHERE last_sync_run_id=? AND status=? ORDER BY 1
            """,(rs,n)->rs.getString(1),run,contained?"INACTIVE":"ACTIVE");
        require(expectedRefs.size()==142 && new HashSet<>(expectedRefs).size()==142 && expectedRefs.equals(refs));
        require(jdbc.queryForObject("SELECT count(*) FROM place_external_refs WHERE last_sync_run_id=?",Long.class,run)==expectedRefs.size());
        require(jdbc.queryForObject("SELECT count(*) FROM place_source_records WHERE sync_run_id=?",Long.class,run)==0L);
        for(var p:plan.places()) {
            require(p.ownershipProvenanceValid());
            require(contained ? p.rollbackState().startsWith("RETIRED") || p.rollbackState().equals("CONTAINED_NEWER_REFERENCES")
                    : p.rollbackState().equals("NONE") && p.catalogStatus().equals("ACTIVE"));
        }
        ObjectNode proof=JsonNodeFactory.instance.objectNode();
        proof.put("version","v3-containment-inspection-v1").put("run_id",run.toString()).put("manifest_hash",hash)
                .put("validation_method",PlacePilotV3Policy.V3).put("stage","STAGE_1")
                .put("predecessor_run_id",PlacePilotV3Policy.CONTAINED_RUN.toString())
                .put("canonical_uuid_digest",PlacePilotV3Policy.sha256(String.join("\n",expected)))
                .put("provider_ref_digest",PlacePilotV3Policy.sha256(String.join("\n",expectedRefs)))
                .put("exposure_count",expected.size()).put("ref_count",expectedRefs.size()).put("already_contained",contained)
                .put("graph_protected",plan.graphProtectedCount()).put("newer_owner_protected",plan.containedByNewerReferencesCount());
        List<String> actions=plan.places().stream().map(p->p.placeId()+"|"+p.expectedFinalState()+"|"+p.referencesToContainCount()+"|"+p.protectedReferenceCount()).sorted().toList();
        proof.put("action_set_digest",PlacePilotV3Policy.sha256(String.join("\n",actions)));
        proof.put("ownership_hash",PlacePilotV3Policy.sha256(proof.toString()));
        return proof;
    }
    public ObjectNode contain(UUID run,String hash) {
        // Commit the request separately so inspection failure leaves durable owner-intervention evidence.
        writes.execute(t->{
            require(jdbc.queryForObject("SELECT count(*) FROM place_pilot_canary_gates WHERE sync_run_id=? AND gate_status='FAILED'",Long.class,run)==1L);
            require(jdbc.queryForObject("SELECT count(*) FROM place_provider_sync_runs WHERE id=? AND manifest_hash=? AND method_version=?",Long.class,run,hash,PlacePilotV3Policy.V3)==1L);
            ObjectNode requested=JsonNodeFactory.instance.objectNode();
            requested.put("reason_code","INTEGRITY_FAILURE").put("phase","HARD_FAILURE_DETECTED_CONTAINMENT_REQUESTED")
                    .put("inspection_required",true).put("on_inspection_failure","OWNER_INTERVENTION_REQUIRED");
            event(run,hash,"PILOT_CONTAINMENT_REQUESTED",requested); return null;
        });
        ObjectNode inspection=inspect(run,hash); // genuinely read-only transaction; no blind fallback.
        return writes.execute(t->{
            String pilot=jdbc.queryForObject("SELECT pilot_run_key FROM place_provider_sync_runs WHERE id=? AND manifest_hash=?",String.class,run,hash);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,5517))",(rs,n)->rs.getObject(1),pilot);
            jdbc.query("SELECT pg_advisory_xact_lock(5517,917)",(rs,n)->rs.getObject(1));
            ObjectNode current=inspectLocked(run,hash);
            require(current.equals(inspection)); // graph/ownership drift requires owner review, never silent replan.
            if(current.path("already_contained").asBoolean()) {
                require(jdbc.queryForObject("SELECT count(*) FROM place_pilot_operational_events WHERE sync_run_id=? AND manifest_hash=? AND event_type IN ('PILOT_CONTAINMENT_REQUESTED','PILOT_ROLLBACK_STARTED','PILOT_ROLLBACK_COMPLETED')",Long.class,run,hash)==3L);
                return current;
            }
            ObjectNode started=current.deepCopy(); started.put("phase","CONTAINMENT_INSPECTION_COMPLETED_THEN_CONTAINMENT_STARTED");
            event(run,hash,"PILOT_ROLLBACK_STARTED",started);
            var result=rollback.retireRun(run,OffsetDateTime.now());
            ObjectNode after=inspectLocked(run,hash); require(after.path("already_contained").asBoolean());
            after.put("phase","CONTAINMENT_COMPLETED").put("retired_count",result.retiredCount());
            event(run,hash,"PILOT_ROLLBACK_COMPLETED",after);
            return after;
        });
    }
    private void event(UUID run,String hash,String type,ObjectNode details) {
        UUID id=UUID.nameUUIDFromBytes((run+"\n"+type).getBytes(StandardCharsets.UTF_8));
        int added=jdbc.update("""
            INSERT INTO place_pilot_operational_events(id,sync_run_id,manifest_hash,event_type,occurred_at,details)
            VALUES(?,?,?,?,clock_timestamp(),?::jsonb) ON CONFLICT(id) DO NOTHING
            """,id,run,hash,type,details.toString());
        if(added==0) require(jdbc.queryForObject("SELECT count(*) FROM place_pilot_operational_events WHERE id=? AND sync_run_id=? AND manifest_hash=? AND event_type=? AND details=?::jsonb",Long.class,id,run,hash,type,details.toString())==1L);
    }
    private static void require(boolean ok) { if(!ok) throw new IllegalStateException("V3_CONTAINMENT_OWNER_INTERVENTION_REQUIRED"); }
}

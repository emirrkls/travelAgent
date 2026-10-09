import com.emirrkls.phokarta.backend.PhokartaBackendApplication;
import com.emirrkls.phokarta.backend.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Isolated fixture/audit only. Never packaged into the product JAR or used on beta. */
public class RehearsalSeed {
    static final UUID A = UUID.fromString("d70adea5-6e3f-4c32-92c0-49695eeeb9ce");
    static final UUID B = UUID.fromString("19994388-464c-4f0c-83db-44c41d8b67fa");
    static final UUID U = UUID.fromString("bb000000-0000-4000-8000-000000000001");
    static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
    public static void main(String[] args) throws Exception {
        if (!"M55B_ISOLATED".equals(System.getenv("APP_ENVIRONMENT")) ||
                !System.getenv("PHOKARTA_DB_URL").startsWith("jdbc:postgresql://m55b-memory-db:5432/"))
            throw new IllegalStateException("ISOLATED_TARGET_REQUIRED");
        try (var c = SpringApplication.run(PhokartaBackendApplication.class,
                "--server.port=0", "--management.server.port=0", "--phokarta.place-import.enabled=false")) {
            var jdbc = c.getBean(JdbcTemplate.class);
            String mode=System.getenv("REHEARSAL_MODE");
            if ("seed".equals(mode) || "seed-import".equals(mode)) {
                if ("seed".equals(mode) && jdbc.queryForObject("select count(*) from places", Long.class) != 0)
                    throw new IllegalStateException("EMPTY_ISOLATED_FIXTURE_REQUIRED");
                if ("seed-import".equals(mode) && (jdbc.queryForObject("select count(*) from places where origin='MANUAL_COMMUNITY'",Long.class)!=2
                        || jdbc.queryForObject("select count(*) from places",Long.class)!=2
                        || jdbc.queryForObject("select count(*) from users",Long.class)!=0
                        || jdbc.queryForObject("select count(*) from place_provider_sync_runs",Long.class)!=0))
                    throw new IllegalStateException("EXACT_PARTIAL_MANUAL_FIXTURE_REQUIRED");
                for (int n=1;"seed".equals(mode) && n<=2;n++) {
                    UUID id=UUID.fromString("aa000000-0000-4000-8000-00000000000"+n);
                    jdbc.update("""
                        INSERT INTO places(id,name,description,category,subcategories,location,city,region,country,address,
                            cover_image,photos,price_level,created_at,updated_at)
                        VALUES (?,?,'Isolated manual fixture','CAFE',array[]::text[],
                            ST_SetSRID(ST_MakePoint(?,?),4326),'Istanbul','Istanbul','TR','Isolated fixture','',
                            array[]::text[],0,now(),now())
                        """, id,n==1?"Staging Harbor Cafe":"Staging Courtyard Kitchen",28.9784+(n-1)*0.001,41.022+(n-1)*0.001);
                }
                jdbc.update("insert into users(id,username,display_name,email,created_at,updated_at) values (?,'isolated_existing_owner','Isolated existing owner','existing_owner@example.invalid',now(),now())", U);
                jdbc.update("insert into saved_places(user_id,place_id,saved_at) values (?,?::uuid,now())",U,"aa000000-0000-4000-8000-000000000002");
                var imported=c.getBean(PlacePilotImportService.class).importApproved(
                        Path.of("/sealed/predecessor/stage_1_import_manifest.json"),
                        System.getenv("REHEARSAL_A_HASH"),System.getenv("REHEARSAL_A_AUTH"));
                if (!A.equals(imported.runId()) || imported.createdCount()!=71 || imported.alreadyImported())
                    throw new IllegalStateException("EXACT_PREDECESSOR_IMPORT_REQUIRED");
                c.getBean(PlacePilotCanaryGateService.class).record(A,false,
                        M.createObjectNode().put("isolated_fixture_failure",true),null);
                c.getBean(PlacePilotRollbackOperationsService.class).execute(A,System.getenv("REHEARSAL_A_HASH"),
                        PlacePilotRollbackOperationsService.Reason.PRODUCT_ACCEPTANCE_FAILURE);
            }
            ObjectNode state=snapshot(jdbc);
            Path output=Path.of(System.getenv("REHEARSAL_OUTPUT"));
            if (Files.exists(output)) throw new IllegalStateException("AUDIT_OVERWRITE_REFUSED");
            Files.write(output,M.writerWithDefaultPrettyPrinter().writeValueAsBytes(state),StandardOpenOption.CREATE_NEW);
            System.out.println("ISOLATED_FIXTURE_AUDIT:"+state.toString());
        }
    }
    static ObjectNode snapshot(JdbcTemplate jdbc) throws Exception {
        ObjectNode o=M.createObjectNode();
        o.put("isolated",true).put("at",java.time.Instant.now().toString());
        for (var e:Map.ofEntries(
                Map.entry("places","select count(*) from places"),
                Map.entry("manual","select count(*) from places where origin='MANUAL_COMMUNITY'"),
                Map.entry("retired","select count(*) from places where origin='EXTERNAL_IMPORT' and catalog_status='RETIRED'"),
                Map.entry("active_imported","select count(*) from places where origin='EXTERNAL_IMPORT' and catalog_status='ACTIVE'"),
                Map.entry("sources","select count(*) from place_source_records"),
                Map.entry("refs","select count(*) from place_external_refs"),
                Map.entry("active_refs","select count(*) from place_external_refs where status='ACTIVE'"),
                Map.entry("inactive_refs","select count(*) from place_external_refs where status='INACTIVE'"),
                Map.entry("quarantine","select count(*) from place_validation_recheck_queue"),
                Map.entry("v17","select count(*) from flyway_schema_history where version='17' and checksum=61925746 and success")).entrySet())
            o.put(e.getKey(),jdbc.queryForObject(e.getValue(),Long.class));
        var ids=jdbc.queryForList("select id::text from places where origin='EXTERNAL_IMPORT' order by id",String.class);
        o.put("canonical_uuid_digest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",ids).getBytes(StandardCharsets.UTF_8))));
        ObjectNode h=o.putObject("historical_a");
        h.put("run",fingerprint(jdbc,"place_provider_sync_runs","id",A));
        for (String table:List.of("place_source_records","place_validation_decisions","place_pilot_catalog_writes","place_pilot_canary_gates","place_pilot_operational_events","place_external_ref_events"))
            h.put(table,fingerprint(jdbc,table,"sync_run_id",A));
        o.put("manual_fingerprint",jdbc.queryForObject("select md5(string_agg(to_jsonb(p)::text,'' order by id)) from places p where origin='MANUAL_COMMUNITY'",String.class));
        o.put("existing_user_fingerprint",fingerprint(jdbc,"users","id",U));
        o.put("existing_saved_fingerprint",fingerprint(jdbc,"saved_places","user_id",U));
        o.put("b_writes",jdbc.queryForObject("select count(*) from place_pilot_catalog_writes where sync_run_id=?",Long.class,B));
        o.put("b_decisions",jdbc.queryForObject("select count(*) from place_validation_decisions where sync_run_id=?",Long.class,B));
        o.put("b_sources",jdbc.queryForObject("select count(*) from place_source_records where sync_run_id=?",Long.class,B));
        o.put("b_gate",jdbc.query("select gate_status from place_pilot_canary_gates where sync_run_id=?",(rs,n)->rs.getString(1),B).stream().findFirst().orElse("NONE"));
        return o;
    }
    static String fingerprint(JdbcTemplate jdbc,String table,String column,UUID id) {
        return jdbc.queryForObject("select md5(coalesce(string_agg(to_jsonb(t)::text,'' order by to_jsonb(t)::text),'')) from "+table+" t where "+column+"=?",String.class,id);
    }
}

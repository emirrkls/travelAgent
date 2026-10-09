"""Real isolated PostgreSQL regression tests; mandatory in the full Linux suite.

No beta route or private fixture. V17 SQL schema is loaded unchanged. The full
operational/Flyway rehearsal is separate and cannot be replaced by these tests.
"""
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import unittest
from unittest.mock import MagicMock, patch
import uuid

spec = importlib.util.spec_from_file_location("historical_audit_postgres", Path(__file__).parents[1] / "operations/historical_database_audit.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class HistoricalAuditPostgresTests(unittest.TestCase):
    @classmethod
    def command(cls, *args, input=None, timeout=60):
        result = subprocess.run(["docker", *args], input=input, capture_output=True, timeout=timeout)
        if result.returncode:
            raise AssertionError("ISOLATED_POSTGRES_COMMAND_FAILED:" + args[0])
        return result.stdout

    @classmethod
    def sql(cls, text, *, readonly=True):
        options = "-c statement_timeout=5000" + (" -c default_transaction_read_only=on" if readonly else "")
        shell = "PGOPTIONS='" + options + "' psql -X -qAt -v ON_ERROR_STOP=1 -U phokarta -d phokarta"
        return cls.command("exec", "-i", cls.container, "sh", "-c", shell, input=text.encode())

    @classmethod
    def cleanup(cls):
        if not getattr(cls, "container", None): return
        result = subprocess.run(["docker", "inspect", cls.container], capture_output=True)
        if result.returncode: return
        value = json.loads(result.stdout)[0]
        if value["Config"]["Labels"].get("phokarta.audit_regression") != cls.marker:
            raise AssertionError("ISOLATED_CLEANUP_SCOPE_MISMATCH")
        cls.command("rm", "-f", "-v", cls.container)

    @classmethod
    def setUpClass(cls):
        cls.marker = uuid.uuid4().hex
        cls.name = "m55b-audit-regression-" + cls.marker[:12]
        cls.container = None
        cls.addClassCleanup(cls.cleanup)
        cls.container = cls.command("run", "-d", "--name", cls.name, "--network", "none", "--memory", "768m",
                                    "--memory-swap", "768m", "--cpus", "1.5", "--label", "phokarta.audit_regression=" + cls.marker,
                                    "-e", "POSTGRES_USER=phokarta", "-e", "POSTGRES_DB=phokarta",
                                    "-e", "POSTGRES_PASSWORD=" + uuid.uuid4().hex, "postgis/postgis:16-3.5").decode().strip()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            # pg_isready can see the temporary init server. Require completed init
            # and the final postmaster's message; never restore during init shutdown.
            all_logs = subprocess.run(["docker", "logs", cls.container], capture_output=True, timeout=10)
            logs = (all_logs.stdout + all_logs.stderr).decode(errors="replace")
            if "PostgreSQL init process complete" in logs and "database system is ready to accept connections" in logs.split("PostgreSQL init process complete", 1)[1]:
                break
            time.sleep(0.2)
        else: raise AssertionError("ISOLATED_POSTGRES_STARTUP_DEADLINE")
        repo = Path(__file__).resolve().parents[3]
        migration = repo / "backend/src/main/resources/db/migration/schema"
        files = sorted(migration.glob("V*__*.sql"), key=lambda p: int(p.name.split("__")[0][1:]))
        for path in files:
            cls.sql(path.read_text(encoding="utf-8"), readonly=False)
        cls.sql("""
CREATE TABLE flyway_schema_history(version varchar, success boolean, checksum integer);
INSERT INTO flyway_schema_history VALUES ('17',true,61925746);
INSERT INTO places(id,name,description,category,location,city,region,country,address,cover_image,price_level,created_at,updated_at)
VALUES ('aa000000-0000-4000-8000-000000000001','İzmir ''quote''','line one
line two \\ slash','CAFE',ST_SetSRID(ST_MakePoint(27,37),4326),'Didim','Aydin','TR','','',0,now(),now());
INSERT INTO place_provider_sync_runs(id,pilot_run_key,canary_stage,provider,resolved_release,method_version,scope_name,
scope_center_latitude,scope_center_longitude,scope_radius_meters,started_at,completed_at,status)
VALUES ('d70adea5-6e3f-4c32-92c0-49695eeeb9ce','audit-test','DRY_RUN','MULTI_SOURCE','synthetic','synthetic','synthetic',37,27,6000,now()-interval '1 hour',now(),'FAILED');
INSERT INTO place_source_records(id,sync_run_id,provider,external_id,source_release,method_version,provider_categories,
source_hash,license_identifier,provenance,observed_at,retrieved_at)
VALUES ('bb000000-0000-4000-8000-000000000001','d70adea5-6e3f-4c32-92c0-49695eeeb9ce','OVERTURE','synthetic-1','synthetic','synthetic','[]',repeat('a',64),'synthetic','{"text":"apostrophe '' and unicode İzmir"}',now(),now());
""", readonly=False)

    def setUp(self):
        self.original = audit.strict_json(self.sql(audit.SQL_PATH.read_text()))
        self.command_args = audit.docker_psql_command(self.container)

    def delayed_script(self, seconds, every=False):
        script = audit.build_script()
        for index, (_, expression) in enumerate(audit.expressions()):
            if every or index == 0:
                script = script.replace("'value'," + expression + ",", "'value',(SELECT " + expression + " FROM pg_sleep(" + str(seconds) + ")),", 1)
        return script

    def test_original_decomposed_equality_all_38_real_sql_expressions(self):
        evidence = audit.run_checked_audit(self.command_args, self.original)
        self.assertEqual(evidence["audit"], self.original)
        self.assertEqual(evidence["diagnostics"]["completed_expressions"], 38)
        self.assertEqual(evidence["diagnostics"]["context"]["isolation"], "repeatable read")
        self.assertLess(evidence["diagnostics"]["aggregate_elapsed_ms"], 30000)

    def test_real_snapshot_consistency_during_concurrent_write(self):
        script = self.delayed_script(1.0)
        with patch.object(audit, "build_script", return_value=script), concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(audit.run_checked_audit, self.command_args, self.original)
            deadline = time.monotonic() + 4
            while time.monotonic() < deadline:
                sleeping = self.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event='PgSleep';").decode().strip()
                if sleeping == "1": break
                time.sleep(0.02)
            else: self.fail("SNAPSHOT_BARRIER_NOT_OBSERVED")
            self.sql("UPDATE places SET description='concurrent-new-version' WHERE id='aa000000-0000-4000-8000-000000000001';", readonly=False)
            evidence = future.result(timeout=10)
        self.assertEqual(evidence["audit"], self.original)
        after = audit.strict_json(self.sql(audit.SQL_PATH.read_text()))
        self.assertNotEqual(after["places"]["manual_fingerprint"], self.original["places"]["manual_fingerprint"])

    def test_historical_source_append_changes_fingerprint_and_blocks_preflight(self):
        self.sql("""INSERT INTO place_source_records(id,sync_run_id,provider,external_id,source_release,method_version,provider_categories,source_hash,license_identifier,observed_at,retrieved_at)
VALUES ('bb000000-0000-4000-8000-000000000002','d70adea5-6e3f-4c32-92c0-49695eeeb9ce','OVERTURE','synthetic-2','synthetic','synthetic','[]',repeat('b',64),'synthetic',now(),now());""", readonly=False)
        downstream = MagicMock()
        with self.assertRaisesRegex(audit.AuditFailure, "HISTORICAL_FINGERPRINT_MISMATCH"):
            audit.preflight(self.command_args, self.original, downstream)
        downstream.assert_not_called()

    def test_historical_decision_append_changes_full_row_fingerprint(self):
        self.sql("""INSERT INTO place_validation_decisions(id,sync_run_id,pilot_run_key,candidate_key,
validation_method_version,decision_state,decision_reason,existence_assessment,evidence,source_record_ids,candidate_hash,decided_at)
VALUES ('cc000000-0000-4000-8000-000000000001','d70adea5-6e3f-4c32-92c0-49695eeeb9ce',
'audit-test','synthetic-candidate','synthetic','QUARANTINE','AUDIT_DIAGNOSTIC','UNKNOWN',
'{"text":"NULL versus empty, apostrophe '' and Unicode İzmir"}',
ARRAY['bb000000-0000-4000-8000-000000000001'::uuid],repeat('c',64),now());""", readonly=False)
        after = audit.strict_json(self.sql(audit.SQL_PATH.read_text()))
        self.assertNotEqual(after["historical_a"]["decisions_fingerprint"], self.original["historical_a"]["decisions_fingerprint"])
        downstream = MagicMock()
        with self.assertRaisesRegex(audit.AuditFailure, "HISTORICAL_FINGERPRINT_MISMATCH"):
            audit.preflight(self.command_args, self.original, downstream)
        downstream.assert_not_called()

    def test_real_statement_timeout_keeps_5000ms_and_no_partial_pass(self):
        with patch.object(audit, "build_script", return_value=self.delayed_script(5.5)):
            start = time.monotonic()
            with self.assertRaisesRegex(audit.AuditFailure, "STATEMENT_TIMEOUT") as raised:
                audit.run_checked_audit(self.command_args, self.original)
        self.assertGreaterEqual(time.monotonic() - start, 5)
        self.assertLess(time.monotonic() - start, 9)
        self.assertEqual(raised.exception.diagnostics["status"], "FAIL")
        self.assertNotIn("audit", raised.exception.diagnostics)

    def test_real_aggregate_timeout_is_30_seconds_not_38_times_30(self):
        with patch.object(audit, "build_script", return_value=self.delayed_script(1.0, every=True)):
            start = time.monotonic()
            with self.assertRaisesRegex(audit.AuditFailure, "AGGREGATE_DEADLINE_EXCEEDED"):
                audit.run_checked_audit(self.command_args, self.original)
        self.assertGreaterEqual(time.monotonic() - start, 29.5)
        self.assertLess(time.monotonic() - start, 34)

    def test_sql_error_after_completed_expression_still_no_partial_pass(self):
        path, expression = audit.expressions()[1]
        script = audit.build_script().replace("'value'," + expression + ",", "'value',(SELECT 1/0),", 1)
        with patch.object(audit, "build_script", return_value=script):
            with self.assertRaisesRegex(audit.AuditFailure, "AUDIT_SQL_FAILED") as raised:
                audit.run_checked_audit(self.command_args, self.original)
        self.assertEqual(raised.exception.diagnostics["status"], "FAIL")
        self.assertNotIn("audit", raised.exception.diagnostics)


if __name__ == "__main__": unittest.main()

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("historical_audit", Path(__file__).parents[1] / "operations/historical_database_audit.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class HistoricalAuditTests(unittest.TestCase):
    def setUp(self):
        self.inventory = audit.expressions()
        self.paths = [path for path, _ in self.inventory]
        self.expected = audit.reconstruct({path: None for path in self.paths}, self.paths)
        self.expected["transaction_read_only"] = "on"
        self.context = dict(backend_pid=42, snapshot="100:100:", isolation="repeatable read", read_only="on", statement_timeout="5s")

    def lines(self):
        values = audit.flatten_expected(self.expected, self.paths)
        rows = [json.dumps(dict(kind="HEADER", **self.context))]
        for index, path in enumerate(self.paths):
            rows.extend([json.dumps(dict(kind="EXPRESSION", index=index, path=list(path), value=values[path], **self.context)), "Time: 1.234 ms"])
        rows += [json.dumps(dict(kind="FOOTER", **self.context)), audit.COMMIT_MARKER]
        return rows

    def decode(self, lines):
        return audit.decode_output("\n".join(lines).encode(), self.expected)

    def child(self, lines=None, code=0, stderr=b""):
        child = MagicMock()
        child.returncode = code
        child.poll.return_value = code
        child.communicate.return_value = ("\n".join(lines or self.lines()).encode(), stderr)
        return child

    def test_exact_original_hash_and_38_unique_unmodified_expressions(self):
        self.assertEqual(hashlib.sha256(audit.SQL_PATH.read_bytes()).hexdigest(), audit.SQL_SHA256)
        self.assertEqual(len(self.paths), 38)
        self.assertEqual(len(set(self.paths)), 38)
        script = audit.build_script()
        for _, expression in self.inventory:
            self.assertIn("'value'," + expression + ",", script)
        self.assertEqual(script.count("BEGIN TRANSACTION"), 1)
        self.assertEqual(script.count("COMMIT;"), 1)
        self.assertNotIn("SET statement_timeout", script)

    def test_complete_equality_and_per_expression_timings(self):
        result, context, timings = self.decode(self.lines())
        self.assertEqual(result, self.expected)
        self.assertEqual(context, self.context)
        self.assertEqual(len(timings), 38)
        self.assertEqual([t["index"] for t in timings], list(range(38)))

    def test_null_empty_ordering_and_escaping_are_preserved(self):
        self.expected["places"]["canonical_ids"] = ["first", "second"]
        self.expected["b_events"] = [{"details": {"text": "İzmir 'quoted' \"double\" \\ slash\nline\tend"}}]
        self.expected["b_decisions"] = {}
        self.expected["b_writes"] = []
        self.assertEqual(self.decode(self.lines())[0], self.expected)
        changed = self.lines()
        index = self.paths.index(("places", "canonical_ids"))
        record = json.loads(changed[1 + index * 2])
        record["value"].reverse()
        changed[1 + index * 2] = json.dumps(record)
        with self.assertRaisesRegex(audit.AuditFailure, "HISTORICAL_FINGERPRINT_MISMATCH"):
            self.decode(changed)

    def test_each_of_38_required_values_is_compared(self):
        for index, path in enumerate(self.paths):
            with self.subTest(path=path):
                rows = self.lines()
                record = json.loads(rows[1 + index * 2])
                record["value"] = "changed-full-row-evidence"
                rows[1 + index * 2] = json.dumps(record)
                with self.assertRaisesRegex(audit.AuditFailure, "HISTORICAL_FINGERPRINT_MISMATCH"):
                    self.decode(rows)

    def test_missing_expression_fails(self):
        rows = self.lines()
        del rows[17:19]
        with self.assertRaises(audit.AuditFailure): self.decode(rows)

    def test_duplicate_expression_fails(self):
        rows = self.lines()
        rows[3] = rows[1]
        with self.assertRaisesRegex(audit.AuditFailure, "EXPRESSION_IDENTITY_MISMATCH"): self.decode(rows)

    def test_duplicate_json_key_fails(self):
        rows = self.lines()
        rows[1] = rows[1].replace('"index": 0', '"index": 0, "index": 0')
        with self.assertRaisesRegex(audit.AuditFailure, "DUPLICATE_JSON_KEY"): self.decode(rows)

    def test_extra_output_fails(self):
        with self.assertRaises(audit.AuditFailure): self.decode(self.lines() + ['{"status":"PASS"}'])

    def test_missing_timing_fails(self):
        rows = self.lines(); rows[2] = ""
        with self.assertRaisesRegex(audit.AuditFailure, "MISSING_OR_INVALID_TIMING"): self.decode(rows)

    def test_statement_duration_above_5000_fails(self):
        rows = self.lines(); rows[2] = "Time: 5000.001 ms (00:05.000)"
        with self.assertRaisesRegex(audit.AuditFailure, "STATEMENT_DEADLINE_EXCEEDED"): self.decode(rows)

    def test_snapshot_and_connection_change_fail(self):
        for key, value in (("snapshot", "100:101:"), ("backend_pid", 43)):
            with self.subTest(key=key):
                rows = self.lines(); record = json.loads(rows[3]); record[key] = value; rows[3] = json.dumps(record)
                with self.assertRaisesRegex(audit.AuditFailure, "SNAPSHOT_OR_CONNECTION_CHANGED"): self.decode(rows)

    def test_invalid_transaction_and_timeout_fail(self):
        for key, value in (("isolation", "read committed"), ("read_only", "off"), ("statement_timeout", "0")):
            with self.subTest(key=key):
                rows = self.lines(); record = json.loads(rows[0]); record[key] = value; rows[0] = json.dumps(record)
                with self.assertRaisesRegex(audit.AuditFailure, "INVALID_TRANSACTION"): self.decode(rows)

    def test_invalid_snapshot_fails(self):
        for snapshot in ("unknown", "200:100:", "100:200:110,110", "100:200:200", "100:200:120,110"):
            with self.subTest(snapshot=snapshot):
                rows = self.lines(); record = json.loads(rows[0]); record["snapshot"] = snapshot; rows[0] = json.dumps(record)
                with self.assertRaisesRegex(audit.AuditFailure, "INVALID_SNAPSHOT"): self.decode(rows)

    def test_uncommitted_output_fails(self):
        rows = self.lines(); rows[-1] = "ROLLBACK"
        with self.assertRaisesRegex(audit.AuditFailure, "TRANSACTION_NOT_COMPLETED"): self.decode(rows)

    def test_malformed_output_fails(self):
        rows = self.lines(); rows[1] = "broken"
        with self.assertRaises(audit.AuditFailure): self.decode(rows)

    def test_nonfinite_json_fails(self):
        with self.assertRaisesRegex(audit.AuditFailure, "NONFINITE_JSON"): audit.strict_json('{"latency":NaN}')

    def test_invalid_reference_is_rejected_before_launch(self):
        with patch.object(audit.subprocess, "Popen") as popen:
            with self.assertRaisesRegex(audit.AuditFailure, "INVALID_EXPECTED_AUDIT"):
                audit.run_checked_audit(["unused"], {})
            popen.assert_not_called()

    def test_one_process_one_aggregate_budget_not_reset_per_expression(self):
        child = self.child()
        with patch.object(audit.subprocess, "Popen", return_value=child) as popen, patch.object(audit.time, "monotonic", side_effect=[10, 10.1, 11]):
            result = audit.run_checked_audit(["psql-test"], self.expected)
        popen.assert_called_once()
        child.communicate.assert_called_once()
        self.assertAlmostEqual(child.communicate.call_args.kwargs["timeout"], 29.9)
        self.assertEqual(result["diagnostics"]["status"], "PASS")

    def test_statement_timeout_no_partial_pass_and_no_downstream_mutation(self):
        child = self.child(code=3, stderr=b"ERROR: 57014\nprivate-row-must-not-be-disclosed")
        downstream = MagicMock()
        with patch.object(audit.subprocess, "Popen", return_value=child):
            with self.assertRaisesRegex(audit.AuditFailure, "STATEMENT_TIMEOUT") as raised:
                audit.preflight(["psql-test"], self.expected, downstream)
        downstream.assert_not_called()
        self.assertEqual(raised.exception.diagnostics["status"], "FAIL")
        self.assertNotIn("private-row", json.dumps(raised.exception.diagnostics))
        self.assertNotIn("audit", raised.exception.diagnostics)

    def test_aggregate_timeout_no_partial_pass(self):
        child = self.child(); child.communicate.side_effect = [subprocess.TimeoutExpired("test", 30), (b"", b"")]
        child.poll.return_value = None
        with patch.object(audit.subprocess, "Popen", return_value=child), patch.object(audit, "terminate_owned") as terminate:
            with self.assertRaisesRegex(audit.AuditFailure, "AGGREGATE_DEADLINE_EXCEEDED"):
                audit.run_checked_audit(["psql-test"], self.expected)
        terminate.assert_called_once_with(child)

    def test_completed_process_over_total_budget_still_fails(self):
        with patch.object(audit.subprocess, "Popen", return_value=self.child()), patch.object(audit.time, "monotonic", side_effect=[10, 10, 40.001]):
            with self.assertRaisesRegex(audit.AuditFailure, "AGGREGATE_DEADLINE_EXCEEDED"):
                audit.run_checked_audit(["psql-test"], self.expected)

    def test_sql_failure_never_returns_partial_result(self):
        with patch.object(audit.subprocess, "Popen", return_value=self.child(code=1, stderr=b"ERROR: 42P01")):
            with self.assertRaisesRegex(audit.AuditFailure, "AUDIT_SQL_FAILED"):
                audit.run_checked_audit(["psql-test"], self.expected)

    def test_failure_retains_only_completed_prefix_timings_not_values(self):
        child = self.child(lines=self.lines()[:3], code=3, stderr=b"ERROR: 22012")
        with patch.object(audit.subprocess, "Popen", return_value=child):
            with self.assertRaises(audit.AuditFailure) as raised:
                audit.run_checked_audit(["psql-test"], self.expected)
        evidence = raised.exception.diagnostics
        self.assertEqual(evidence["status"], "FAIL")
        self.assertEqual(evidence["completed_expressions"], 1)
        self.assertNotIn("value", evidence["expressions"][0])
        self.assertNotIn("audit", evidence)
        self.assertEqual(evidence["expressions"][0]["validation"], "COMPLETED_PREFIX_ONLY_NOT_AUDIT_PASS")

    def test_malformed_partial_output_never_makes_diagnostics_fail_open(self):
        self.assertEqual(audit.safe_partial_timings(b"bad\xff\n"), {"completed_expressions": 0, "expressions": []})

    def test_successful_preflight_allows_downstream_once(self):
        downstream = MagicMock(return_value="next-step")
        with patch.object(audit.subprocess, "Popen", return_value=self.child()):
            self.assertEqual(audit.preflight(["psql-test"], self.expected, downstream), "next-step")
        downstream.assert_called_once()

    def test_pinned_sql_modification_rejected(self):
        with patch.object(Path, "read_bytes", return_value=b"SELECT 1;"):
            with self.assertRaisesRegex(audit.AuditFailure, "ORIGINAL_SQL_HASH_MISMATCH"): audit.expressions()

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"; path.write_text("original")
            with self.assertRaisesRegex(audit.AuditFailure, "OUTPUT_ALREADY_EXISTS"): audit.publish(path, {"status": "PASS"})
            self.assertEqual(path.read_text(), "original")

    def test_docker_command_keeps_fixed_timeouts_and_no_secrets(self):
        command = audit.docker_psql_command("phokarta-db-1", sudo=True)
        self.assertEqual(command[:3], ["sudo", "-n", "docker"])
        self.assertIn("timeout --signal=TERM 30s", command[-1])
        self.assertIn("statement_timeout=5000", command[-1])
        self.assertIn("default_transaction_read_only=on", command[-1])
        with self.assertRaises(audit.AuditFailure): audit.docker_psql_command("unsafe;command")


if __name__ == "__main__": unittest.main()

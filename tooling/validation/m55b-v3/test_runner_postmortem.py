"""Failure evidence tests; never execute a product/import/rollback worker."""
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import runner_postmortem as p
import linux_rehearsal as h
from fixture import B

HERE = Path(__file__).resolve().parent
APP = HERE.parents[2]


class PostmortemTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.plan = {'run_id': B, 'manifest_hash': 'a' * 64, 'selected_canonical_ids': []}
        self.state = {'ExitCode': 1, 'OOMKilled': False, 'Running': False}

    def write(self, name, value):
        path = self.directory / (name + '.json')
        path.write_text(json.dumps(value), encoding='utf-8')
        return path

    def trial(self):
        return SimpleNamespace(evidence=self.directory, plan=self.plan, extra_before={}, app=APP,
            sql=mock.Mock(return_value=self.raw_db()), target='target', db='db', caddy='caddy', product_cid='product')

    def raw_db(self):
        return {'readonly': 'on', 'isolation': 'repeatable read', 'run': None, 'gate': None,
                'events': [], 'places': {}, 'refs': {}, 'counts': {}}

    def inspect(self, *args):
        return {'Id': 'b' * 64, 'RestartCount': 0, 'State': {'Running': True, 'OOMKilled': False,
                'ExitCode': 0, 'Health': {'Status': 'healthy'}}}

    def evidence(self):
        return {'PRE': {'status': 'VERIFIED_COMPLETE'}, 'POST': {'status': 'NOT_VERIFIED'},
                'cold': {'status': 'VERIFIED_PASS'}, 'product_status': 'NOT_VERIFIED'}

    def test_nonzero_exit_preserves_known_fatal_reason(self):
        log = b'Exception in thread "main" java.lang.IllegalStateException: V3_IDEMPOTENCY_HARD_FAILURE\n'
        result = p.collect(self.trial(), self.state, lambda: log, self.inspect)
        self.assertEqual(result['runner_exit_code'], 1)
        self.assertEqual(result['attribution']['fatal_reason_code'], 'V3_IDEMPOTENCY_HARD_FAILURE')
        self.assertEqual(result['attribution']['first_failed_stage'], 'REPLAY')
        self.assertTrue(result['runner_result_authoritative'])

    def test_last_completed_is_not_first_failed(self):
        log = {'exceptions': [{'reason_code': 'V3_IDEMPOTENCY_HARD_FAILURE'}]}
        result = p.attribution(log, {'run_status': 'SUCCEEDED'}, self.evidence())
        self.assertEqual(result['last_completed_stage'], 'RE_ADOPTION_COMMITTED')
        self.assertEqual(result['first_failed_stage'], 'REPLAY')

    def test_baseline_marker_alone_cannot_classify(self):
        log = p.log_projection('PREIMPORT_HTTP_BASELINE_ARTIFACT_VERIFIED=secret', APP)
        result = p.attribution(log, {}, self.evidence())
        self.assertEqual(result['first_failed_stage'], 'UNKNOWN')
        self.assertEqual(result['fatal_reason_code'], 'UNKNOWN')

    def test_exact_approved_frame_can_attribute_generic_error(self):
        source = APP / 'backend/src/main/java/com/emirrkls/phokarta/backend/service/PlacePilotAutonomousCanaryService.java'
        lines = source.read_text(encoding='utf-8').splitlines()
        line = next(i + 1 for i, s in enumerate(lines) if 'JsonNode post=observations.capturePersistent' in s)
        raw = ('java.io.IOException: PRIVATE_OPERATIONS_EVIDENCE_INVALID\n'
               ' at com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService.runV3('
               f'PlacePilotAutonomousCanaryService.java:{line})')
        result = p.attribution(p.log_projection(raw, APP), {}, self.evidence())
        self.assertEqual(result['first_failed_stage'], 'PERSISTENT_POST')

    def test_missing_receipt_never_pass(self):
        docs = {'PRE': {'run_id': B, 'manifest_hash': 'a' * 64, 'role': 'PRE', 'outcome': 'COMPLETE', 'requests': []}}
        result = p.telemetry('PRE', docs, {'PRE': {}}, self.plan)
        self.assertFalse(result['receipt_verified'])
        self.assertNotEqual(result['status'], 'VERIFIED_COMPLETE')

    def test_aggregate_sql_read_only_no_mutation(self):
        sql = p.database_sql().upper()
        self.assertTrue(sql.startswith('SELECT '))
        for forbidden in ['INSERT ', 'UPDATE ', 'DELETE ', 'CALL ', 'DROP ', 'ALTER ', 'PG_TERMINATE', 'PG_CANCEL']:
            self.assertNotIn(forbidden, sql)
        self.assertIn("CURRENT_SETTING('TRANSACTION_READ_ONLY')", sql)
        source = (HERE / 'linux_rehearsal.py').read_text()
        self.assertIn('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;', source)
        self.assertIn('statement_timeout=5000', source)
        self.assertIn('exec timeout 30s', source)

    def test_writable_database_snapshot_rejected(self):
        for key, value in [('readonly', 'off'), ('isolation', 'read committed')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.database_projection(dict(self.raw_db(), **{key: value}), {}, [])

    def test_postmortem_query_failure_does_not_hide_exit(self):
        trial = self.trial()
        trial.sql.side_effect = RuntimeError('PRIVATE_SECRET')
        result = p.collect(trial, self.state, lambda: b'', self.inspect)
        self.assertEqual(result['runner_exit_code'], 1)
        self.assertEqual(result['database']['status'], 'NOT_VERIFIED')
        self.assertIn('database', result['gaps'])
        self.assertNotIn('PRIVATE_SECRET', json.dumps(result))

    def test_collector_failure_cannot_mask_original_runner_error(self):
        trial = SimpleNamespace(operational_cid='cid', result={})
        with mock.patch.object(h, 'collect_postmortem', side_effect=RuntimeError('PRIVATE_SECRET')):
            h.Rehearsal.capture_runner_postmortem(trial, self.state)
        self.assertEqual(trial.result, {'postmortem_status': 'UNAVAILABLE'})
        with self.assertRaisesRegex(h.Failure, 'OPERATIONAL_RUNNER_FAILED'):
            h.require(self.state['ExitCode'] == 0, 'OPERATIONAL_RUNNER_FAILED')

    def test_success_exit_is_not_turned_to_failure_by_diagnostics(self):
        trial = self.trial()
        trial.sql.side_effect = RuntimeError()
        result = p.collect(trial, dict(self.state, ExitCode=0), lambda: b'', self.inspect)
        self.assertEqual(result['runner_exit_code'], 0)
        self.assertEqual(result['collection_status'], 'PARTIAL')
        self.assertNotIn('assessment', result)

    def test_containment_events_reported_no_second_rollback(self):
        raw = self.raw_db()
        raw.update(places={'active_imported': 0}, counts={'retired_writes': 71}, events=[
            {'type': e, 'at': '2026-10-12T00:00:00Z', 'retired_count': 71} for e in
            ['PILOT_CONTAINMENT_REQUESTED', 'PILOT_ROLLBACK_STARTED', 'PILOT_ROLLBACK_COMPLETED']])
        result = p.database_projection(raw, {}, [])
        self.assertEqual(result['containment'], 'COMPLETED')
        trial = self.trial()
        trial.sql.return_value = raw
        result = p.collect(trial, self.state, lambda: b'', self.inspect)
        self.assertEqual(result['additional_rollback_invocations'], 0)
        trial.sql.assert_called_once_with(p.database_sql())

    def test_partial_containment_not_complete(self):
        raw = dict(self.raw_db(), events=[{'type': 'PILOT_CONTAINMENT_REQUESTED'}])
        self.assertEqual(p.database_projection(raw, {}, [])['containment'], 'REQUESTED_OR_INCOMPLETE')

    def test_secret_fields_raw_logs_and_payloads_not_published(self):
        secret = 'TEST_SECRET_DO_NOT_PUBLISH_654321'
        self.write('PRODUCT_FAILED', {'failure_code': secret, 'cleanup': secret, 'observations': [{'body': secret}],
                                      'private_key': secret, 'synthetic_user_id': secret})
        trial = self.trial()
        trial.sql.return_value.update(gate={'status': 'FAILED', 'reason': secret, 'product': {'secret': secret}})
        result = p.collect(trial, self.state, lambda: ('java.lang.IllegalStateException: '+secret+'\n'+secret), self.inspect)
        self.assertNotIn(secret, json.dumps(result))
        self.assertNotIn('private_key', json.dumps(result))
        self.assertEqual(result['evidence']['product_failure_observation_count'], 1)

    def test_duplicate_json_rejected(self):
        path = self.directory / 'PRE.json'
        path.write_bytes(b'{"outcome":"COMPLETE","outcome":"HARD_FAILURE"}')
        info, value = p.artifact(path)
        self.assertEqual(info['validity'], 'INVALID_OR_UNREADABLE')
        self.assertIsNone(value)

    def test_arbitrary_method_string_is_not_approved_frame(self):
        raw = (' at com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService.PRIVATE_SECRET('
               'PlacePilotAutonomousCanaryService.java:203)')
        result = p.log_projection(raw, APP)
        self.assertEqual(result['frames'], [])
        self.assertNotIn('PRIVATE_SECRET', json.dumps(result))

    def test_nan_negative_or_boolean_measurements_not_numbers(self):
        for value in [True, False, -1, float('nan'), float('inf'), '123secret']:
            self.assertIsNone(p.number(value))

    def test_insert_delta_requires_same_statistics_epoch_relation(self):
        raw = dict(self.raw_db(), physical_inserts=73, stats_reset='epoch1', place_relid=16384)
        before = {'physical_inserts': 73, 'stats_reset': 'epoch1', 'place_relid': 16384}
        self.assertEqual(p.database_projection(raw, before, [])['physical_place_insert_delta'], 0)
        for key, value in [('stats_reset', 'epoch2'), ('place_relid', 16385)]:
            self.assertIsNone(p.database_projection(dict(raw, **{key: value}), before, [])['physical_place_insert_delta'])

    def test_planned_identity_never_substitutes_for_http_coverage(self):
        raw = dict(self.raw_db(), gate={'status': 'FAILED', 'accounting_coverage': ['planned'] * 71})
        result = p.database_projection(raw, {}, [])
        self.assertEqual(result['accounting_identity_count'], 71)
        self.assertEqual(result['http_coverage'], {})
        self.assertEqual(result['replay'], 'UNKNOWN')

    def test_running_runner_not_inspected_or_interrupted(self):
        trial = SimpleNamespace(operational_cid='cid', result={}, publish=mock.Mock())
        with mock.patch.object(h, 'collect_postmortem') as collector:
            h.Rehearsal.capture_runner_postmortem(trial, dict(self.state, Running=True))
        collector.assert_not_called()
        self.assertEqual(trial.publish.call_args.args[1]['reason'], 'RUNNER_STILL_RUNNING')

    def test_capture_precedes_exit_propagation_and_teardown(self):
        code = (HERE / 'linux_rehearsal.py').read_text()
        body = code.split('    def operational(self):')[1].split('    def capture_runner_postmortem')[0]
        self.assertLess(body.index('self.capture_runner_postmortem(state)'), body.index("'OPERATIONAL_RUNNER_FAILED'"))
        self.assertLess(body.index("'operational_exit.json'"), body.index('self.capture_runner_postmortem(state)'))
        self.assertNotIn('retireRun', (HERE / 'runner_postmortem.py').read_text())
        self.assertNotIn('helper(', (HERE / 'runner_postmortem.py').read_text())

    def test_postmortem_no_callbacks_after_aggregate_diagnostic_budget(self):
        trial = self.trial()
        result = p.collect(trial, self.state, mock.Mock(), mock.Mock(), clock=mock.Mock(side_effect=[0]+[100]*30))
        self.assertEqual(result['runner_exit_code'], 1)
        trial.sql.assert_not_called()


if __name__ == '__main__':
    unittest.main()

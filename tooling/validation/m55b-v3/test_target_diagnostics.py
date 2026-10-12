"""No network/Docker. Exact substep evidence and fail-closed infrastructure tests."""
import contextlib
import copy
import io
import json
from pathlib import Path
import socket
import ssl
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixture
import linux_rehearsal as h
import target_diagnostics as d
import worker


class RequestFailure(ValueError):
    def __init__(self):
        super().__init__('PRIVATE_TEST_SECRET')
        self.observation = {'validation': 'PRODUCT_TIMEOUT', 'body': 'PRIVATE_TEST_SECRET'}


class TargetTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = io.StringIO()
        self.ctx = mock.Mock(verify_mode=ssl.CERT_REQUIRED, check_hostname=True)
        self.tls = mock.MagicMock()
        self.tls.__enter__.return_value = self.tls
        self.tls.version.return_value = 'TLSv1.3'
        self.ctx.wrap_socket.side_effect = [self.tls, ssl.SSLCertVerificationError('PRIVATE_TEST_SECRET')]
        self.sock = mock.MagicMock()
        self.sock.__enter__.return_value = self.sock
        self.transport = mock.Mock()
        self.responses = [dict(status=200, latency_ms=20, request_id='safe-request-' + str(n), body=body)
                          for n, body in enumerate([{'status': 'UP'}, {'status': 'UP'},
                              {'content': [{'id': 'aa000000-0000-4000-8000-000000000001'}]}])]
        self.transport.request.side_effect = self.responses
        self.b1 = SimpleNamespace(Transport=mock.Mock(return_value=self.transport))
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(contextlib.redirect_stdout(self.output))
        self.load = self.stack.enter_context(mock.patch.object(worker, 'load_b1', return_value=self.b1))
        self.dns = self.stack.enter_context(mock.patch.object(worker.socket, 'getaddrinfo',
            return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('172.18.0.2', 8443))]))
        self.tcp = self.stack.enter_context(mock.patch.object(worker.socket, 'create_connection', return_value=self.sock))
        self.context = self.stack.enter_context(mock.patch.object(worker.ssl, 'create_default_context', return_value=self.ctx))

    def run_probe(self):
        result = worker.tls_probe(self.root / 'application', self.root)
        raw = self.output.getvalue().encode()
        self.assertNotIn(b'PRIVATE_TEST_SECRET', raw)
        return result, d.from_logs(raw)

    def assert_failure(self, phase, category):
        code, evidence = self.run_probe()
        self.assertEqual(code, 1)
        self.assertEqual(evidence['status'], 'FAIL')
        self.assertEqual(evidence['phase'], phase)
        self.assertEqual(evidence['events'][-1]['failure_category'], category)
        self.assertFalse((self.root / 'tls.json').exists())
        return evidence

    def test_complete_probe_uses_unchanged_three_get_contracts_and_no_detail_warmup(self):
        code, evidence = self.run_probe()
        self.assertEqual(code, 0)
        self.assertEqual(evidence['status'], 'PASS')
        self.assertEqual([x['phase'] for x in evidence['events']], list(d.PHASES))
        self.b1.Transport.assert_called_once_with(worker.ORIGIN)
        self.assertEqual(self.transport.request.call_args_list, [mock.call('GET', path) for path in
            ['/health/live', '/health/ready', '/api/v1/places?search=Staging&size=20']])
        value = json.loads((self.root / 'tls.json').read_bytes())
        self.assertEqual(value['status'], 'PASS')
        self.assertTrue(value['chain_verified'] and value['hostname_verified'] and value['hostname_negative_control'])
        self.assertEqual(len(value['requests']), 3)
        self.assertTrue(all(call.kwargs['timeout'] == 5 for call in self.tcp.call_args_list))
        self.assertTrue(all(0 < call.args[0] <= 5 for call in self.sock.settimeout.call_args_list))

    def test_import_failure_is_distinct_before_dns_or_http(self):
        self.load.side_effect = ModuleNotFoundError('PRIVATE_TEST_SECRET')
        self.assert_failure('APPLICATION_IMPORT', 'APPLICATION_IMPORT')
        self.dns.assert_not_called()
        self.transport.request.assert_not_called()

    def test_dns_failure_is_distinct(self):
        self.dns.side_effect = socket.gaierror('PRIVATE_TEST_SECRET')
        self.assert_failure('DNS', 'DNS_RESOLUTION')
        self.tcp.assert_not_called()

    def test_external_dns_address_stops_before_connect(self):
        self.dns.return_value = [(2, 1, 6, '', ('8.8.8.8', 8443))]
        self.assert_failure('DNS', 'ISOLATED_DNS_REQUIRED')
        self.tcp.assert_not_called()

    def test_unverified_context_is_not_used(self):
        self.ctx.check_hostname = False
        self.assert_failure('TLS_CONTEXT', 'VERIFIED_TLS_REQUIRED')
        self.tcp.assert_not_called()

    def test_tcp_refusal_is_not_misclassified_as_certificate_failure(self):
        self.tcp.side_effect = ConnectionRefusedError('PRIVATE_TEST_SECRET')
        self.assert_failure('TCP', 'TCP_REFUSED')
        self.ctx.wrap_socket.assert_not_called()

    def test_certificate_failure_preserves_positive_phase(self):
        self.ctx.wrap_socket.side_effect = ssl.SSLCertVerificationError('PRIVATE_TEST_SECRET')
        self.assert_failure('TLS_CHAIN_HOSTNAME', 'TLS_CERTIFICATE_VALIDATION')
        self.transport.request.assert_not_called()

    def test_negative_sni_alert_is_not_a_positive_tls_failure_or_a_pass(self):
        self.ctx.wrap_socket.side_effect = [self.tls, ssl.SSLError('PRIVATE_TEST_SECRET')]
        evidence = self.assert_failure('TLS_NEGATIVE_HOSTNAME', 'TLS_PROTOCOL')
        self.assertEqual(evidence['events'][-2]['status'], 'PASS')
        self.transport.request.assert_not_called()

    def test_missing_negative_rejection_stops_before_b1(self):
        self.ctx.wrap_socket.side_effect = [self.tls, self.tls]
        self.assert_failure('TLS_NEGATIVE_HOSTNAME', 'HOSTNAME_MISMATCH_NOT_REJECTED')
        self.transport.request.assert_not_called()

    def test_http_status_retained_without_error_body_or_retry(self):
        self.responses[0].update(status=403, body={'private': 'PRIVATE_TEST_SECRET'})
        evidence = self.assert_failure('B1_HEALTH_LIVE', 'B1_HTTP_STATUS')
        self.assertEqual(evidence['events'][-1]['details']['status'], 403)
        self.assertEqual(self.transport.request.call_count, 1)

    def test_invalid_health_or_search_is_never_pass(self):
        self.responses[2]['body'] = {'content': [{'id': 'wrong-canonical-id'}]}
        self.assert_failure('B1_SEARCH', 'B1_SEMANTICS')
        self.assertEqual(self.transport.request.call_count, 3)

    def test_b1_complete_response_timeout_stops_without_retry(self):
        self.transport.request.side_effect = RequestFailure()
        self.assert_failure('B1_HEALTH_LIVE', 'B1_TIMEOUT')
        self.assertEqual(self.transport.request.call_count, 1)

    def test_artifact_permission_failure_survives_on_safe_stdout(self):
        with mock.patch.object(fixture, 'write', side_effect=PermissionError('PRIVATE_TEST_SECRET')):
            self.assert_failure('ARTIFACT_WRITE', 'FILESYSTEM_ACCESS')

    def test_malformed_response_does_not_leak_or_pass(self):
        self.responses[0]['request_id'] = 'PRIVATE_TEST_SECRET\nAuthorization: invalid'
        result = worker.tls_probe(self.root, self.root)
        self.assertEqual(result, 1)
        self.assertNotIn('PRIVATE_TEST_SECRET', self.output.getvalue())
        with self.assertRaises(ValueError):
            # Last emitted record is RUNNING, not enough to authorize downstream.
            record = d.from_logs(self.output.getvalue().encode())
            record['status'] = 'PASS'
            d.validate(record)


class DiagnosticContractTest(unittest.TestCase):
    def record(self, failure=False):
        lines = []
        recorder = d.Recorder(sink=lambda value, **kw: lines.append(value))
        if failure:
            try:
                with recorder.step(d.PHASES[0]):
                    raise ImportError('PRIVATE_TEST_SECRET')
            except ImportError:
                pass
        else:
            for phase in d.PHASES:
                with recorder.step(phase):
                    pass
            recorder.finish()
        return '\n'.join(lines).encode(), copy.deepcopy(recorder.record)

    def test_raw_stderr_or_unrelated_stdout_is_never_published(self):
        raw, expected = self.record()
        actual = d.from_logs(b'PRIVATE_TEST_SECRET\n' + raw + b'\nAuthorization: PRIVATE_TEST_SECRET')
        self.assertEqual(actual, expected)
        self.assertNotIn('PRIVATE_TEST_SECRET', json.dumps(actual))

    def test_missing_malformed_duplicate_or_oversized_evidence_fails_closed(self):
        for raw in [b'PRIVATE_TEST_SECRET', (d.PREFIX + '{}').encode(), b'x' * (256 * 1024 + 1),
                    (d.PREFIX + '{"status":"PASS","status":"FAIL"}').encode()]:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                d.from_logs(raw)

    def test_partial_duplicate_missing_or_nonfinite_event_cannot_pass(self):
        _, record = self.record()
        for change in ['partial', 'duplicate', 'missing', 'nan', 'extra', 'bad-details']:
            value = copy.deepcopy(record)
            if change == 'partial': value['events'] = value['events'][:1]; value['phase'] = d.PHASES[0]
            if change == 'duplicate': value['events'][1]['phase'] = d.PHASES[0]
            if change == 'missing': value['events'].pop(1)
            if change == 'nan': value['events'][0]['elapsed_ms'] = float('nan')
            if change == 'extra': value['private'] = 'PRIVATE_TEST_SECRET'
            if change == 'bad-details': value['events'][0]['details']['body'] = 'PRIVATE_TEST_SECRET'
            with self.subTest(change=change), self.assertRaises(ValueError):
                d.validate(value)

    def test_failure_cannot_be_erased_by_later_pass_record(self):
        failed, _ = self.record(failure=True)
        passed, _ = self.record()
        with self.assertRaisesRegex(ValueError, 'DIAGNOSTIC_HISTORY_INVALID'):
            d.from_logs(failed + b'\n' + passed.splitlines()[-1])

    def test_parent_publishes_failure_evidence_before_propagating_worker_exit(self):
        raw, evidence = self.record(failure=True)
        with tempfile.TemporaryDirectory() as temporary:
            trial = mock.Mock()
            trial.root = Path(temporary)
            trial.image_id = 'sha256:' + 'a' * 64
            trial.common.return_value = []
            trial.trust.return_value = []
            trial.run.side_effect = ['a' * 64, 'b' * 64]
            trial.inspect.return_value = {'Image': trial.image_id, 'RestartCount': 0,
                'State': {'Running': True, 'OOMKilled': False, 'Pid': 123, 'StartedAt': '2026-10-12T00:00:00Z',
                          'Health': {'Status': 'healthy'}}}
            trial.wait.side_effect = h.Failure('ISOLATED_PROCESS_FAILED')
            trial.target_worker_evidence.side_effect = lambda cid: h.Rehearsal.target_worker_evidence(trial, cid)
            with mock.patch.object(h.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=raw)):
                with self.assertRaisesRegex(h.Failure, 'PERSISTENT_APPLICATION_IMPORT_APPLICATION_IMPORT'):
                    h.Rehearsal.persistent(trial)
            records = {call.args[0]: call.args[1] for call in trial.publish.call_args_list}
            self.assertEqual(records['tls_worker_diagnostics.json'], evidence)
            self.assertEqual(records['persistent_target.json']['health'], 'healthy')
            self.assertEqual(records['persistent_target.json']['status'], 'FAIL')
            self.assertNotIn('tls.json', records)

    def test_parent_cannot_pass_from_exit_zero_with_missing_evidence(self):
        trial = mock.Mock()
        trial.root = HERE
        trial.image_id = 'sha256:' + 'a' * 64
        trial.common.return_value = []
        trial.trust.return_value = []
        trial.run.return_value = 'a' * 64
        trial.inspect.return_value = {'Image': trial.image_id, 'RestartCount': 0,
            'State': {'Running': True, 'OOMKilled': False, 'Pid': 123, 'StartedAt': '2026-10-12T00:00:00Z',
                      'Health': {'Status': 'healthy'}}}
        trial.target_worker_evidence.return_value = None
        with self.assertRaisesRegex(h.Failure, 'TLS_DIAGNOSTICS_INCOMPLETE'):
            h.Rehearsal.persistent(trial)

    def test_bad_process_identity_stops_before_readiness_or_worker(self):
        trial = mock.Mock()
        trial.image_id = 'sha256:' + 'a' * 64
        trial.common.return_value = []
        trial.trust.return_value = []
        trial.inspect.return_value = {'Image': 'sha256:' + 'b' * 64, 'RestartCount': 0}
        with self.assertRaisesRegex(h.Failure, 'PERSISTENT_IDENTITY_FAILED'):
            h.Rehearsal.persistent(trial)
        self.assertEqual(trial.run.call_count, 1)
        trial.worker_args.assert_not_called()


if __name__ == '__main__':
    unittest.main()

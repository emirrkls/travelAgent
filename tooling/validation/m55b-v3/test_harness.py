"""Small infrastructure checks. NOT substitutes for full-volume rehearsal."""
import ast
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import subprocess

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixture
import dependencies as dep


class HarnessTest(unittest.TestCase):
    def test_exact_identity_inventory(self):
        entries = json.loads((HERE / 'canonical_ids.json').read_bytes())
        self.assertEqual(len(entries), 71)
        self.assertEqual(len({x['candidate_id'] for x in entries}), 71)
        ids = sorted(x['canonical_place_id'] for x in entries)
        self.assertEqual(hashlib.sha256('\n'.join(ids).encode()).hexdigest(), fixture.IDENTITY_DIGEST)

    def test_small_synthetic_generation_real_contract(self):
        app = Path(os.environ.get('GITHUB_WORKSPACE', HERE.parents[2])) / 'application'
        if not app.is_dir():
            app = HERE.parents[2]
        with tempfile.TemporaryDirectory(dir=HERE.parent) as temporary:
            info = fixture.generate(app, Path(temporary) / 'sealed', quarantine=2, rejected=2, dual_quarantine=1, padding=0)
            self.assertEqual(info['counts'], {'source_records': 147, 'candidates': 73, 'eligible': 71, 'selected': 71})
            self.assertEqual(info['source_states']['usable'], 145)
            self.assertEqual(info['candidate_decisions']['QUARANTINE'], 2)
            self.assertFalse(info['production_fingerprint_equivalence_claimed'])

    def test_no_live_connection_or_credential_inputs(self):
        code = (HERE / 'linux_rehearsal.py').read_text()
        for forbidden in ['api.phokarta.com', 'ssh ', 'credentials.json', 'authorized_keys', 'curl ', ' --insecure']:
            self.assertNotIn(forbidden, code)
        self.assertIn("'--internal'", code)
        self.assertNotIn('beta', ast.dump(ast.parse(code)).lower())

    def test_unchanged_client_and_bounded_resources(self):
        code = (HERE / 'worker.py').read_text()
        self.assertIn('b1.Transport(ORIGIN)', code)
        self.assertIn('ssl.CERT_REQUIRED', code)
        self.assertNotIn('SENDER =', code)
        self.assertNotIn('CERT_NONE', code)
        self.assertNotIn('check_hostname = False', code)
        orchestration = (HERE / 'linux_rehearsal.py').read_text()
        self.assertIn("'3072m'", orchestration)
        self.assertIn("'5376m'", orchestration)
        self.assertIn("'-Xmx2048m'", orchestration)
        self.assertIn('audit.preflight(', orchestration)
        self.assertNotIn('build_script =', orchestration)


class DependencyTest(unittest.TestCase):
    REF = dep.SERVICES[0][1]
    IMAGE = {'id': 'sha256:' + '1' * 64, 'digests': ['postgis/postgis@sha256:' + '2' * 64],
             'os': 'linux', 'architecture': 'amd64'}
    REMOTE = [{'Descriptor': {'platform': {'os': 'linux', 'architecture': 'amd64'}, 'digest': 'sha256:' + '3' * 64}}]

    def setUp(self):
        self.records = []
        self.runner = mock.Mock()
        self.d = dep.Dependencies(lambda name, value: self.records.append(value), runner=self.runner)

    @staticmethod
    def result(code=0, value=None, error=b''):
        return subprocess.CompletedProcess([], code, json.dumps(value).encode() if value is not None else b'', error)

    def test_missing_image_is_acquired_once_and_independently_inspected(self):
        self.runner.side_effect = [self.result(1, error=b'Error: No such image: test'), self.result(value=self.REMOTE),
                                   self.result(), self.result(value=self.IMAGE)]
        self.d.acquire('postgis', self.REF)
        pulls = [c for c in self.runner.call_args_list if c.args[0][1] == 'pull']
        self.assertEqual(len(pulls), 1)
        self.assertEqual(self.records[-1]['verified_image_id'], self.IMAGE['id'])
        self.assertEqual(self.records[-1]['status'], 'PASS')

    def test_failed_pull_is_not_retried_or_inspected_after_failure(self):
        self.runner.side_effect = [self.result(1, error=b'No such image: test'), self.result(value=self.REMOTE),
                                   self.result(1, error=b'toomanyrequests: pull rate limit')]
        with self.assertRaises(dep.DependencyFailure) as failure:
            self.d.acquire('postgis', self.REF)
        self.assertEqual(failure.exception.observation['operation_type'], 'PULL')
        self.assertEqual(failure.exception.observation['error_category'], 'REGISTRY_RATE_LIMIT')
        self.assertEqual(self.runner.call_count, 3)

    def test_successful_pull_failed_inspect_is_never_pass(self):
        self.runner.side_effect = [self.result(1, error=b'No such image: test'), self.result(value=self.REMOTE),
                                   self.result(), self.result(1, error=b'Cannot connect to the Docker daemon')]
        with self.assertRaises(dep.DependencyFailure) as failure:
            self.d.acquire('postgis', self.REF)
        self.assertEqual(failure.exception.observation['operation_type'], 'INSPECT')
        self.assertEqual(failure.exception.observation['error_category'], 'DOCKER_DAEMON')
        self.assertNotIn(self.REF, self.d.verified)

    def test_incorrect_reference_is_rejected_without_docker(self):
        with self.assertRaisesRegex(ValueError, 'UNAPPROVED_EXTERNAL_DEPENDENCY'):
            self.d.acquire('postgis', 'unapproved/image:other-version')
        self.runner.assert_not_called()

    def test_remote_missing_manifest_stops_before_pull(self):
        self.runner.side_effect = [self.result(1, error=b'No such image: test'),
                                   self.result(1, error=b'manifest unknown')]
        with self.assertRaises(dep.DependencyFailure) as failure:
            self.d.acquire('postgis', self.REF)
        self.assertEqual(failure.exception.observation['error_category'], 'IMAGE_NOT_FOUND')
        self.assertEqual(self.runner.call_count, 2)

    def test_unsupported_local_platform_is_fail_closed(self):
        self.runner.return_value = self.result(value=dict(self.IMAGE, architecture='arm64'))
        with self.assertRaises(dep.DependencyFailure) as failure:
            self.d.local('unsupported', self.REF)
        self.assertEqual(failure.exception.observation['error_category'], 'PLATFORM_MISMATCH')

    def test_unsupported_remote_platform_stops_before_pull(self):
        remote = [{'Descriptor': {'platform': {'os': 'linux', 'architecture': 'arm64'}}}]
        self.runner.side_effect = [self.result(1, error=b'No such image: test'), self.result(value=remote)]
        with self.assertRaises(dep.DependencyFailure) as failure:
            self.d.acquire('postgis', self.REF)
        self.assertEqual(failure.exception.observation['error_category'], 'PLATFORM_MISMATCH')
        self.assertEqual(self.runner.call_count, 2)

    def test_daemon_failure_does_not_run_downstream(self):
        self.runner.return_value = self.result(1, error=b'Cannot connect to the Docker daemon')
        continuation = mock.Mock()
        with self.assertRaises(dep.DependencyFailure):
            self.d.before_fixture(continuation)
        continuation.assert_not_called()
        self.assertEqual(self.runner.call_count, 1)

    def test_registry_failure_prevents_fixture_initialization(self):
        self.runner.side_effect = [self.result(value={'version': '28.0.4', 'os': 'linux', 'architecture': 'x86_64'}),
            self.result(value=self.IMAGE), self.result(1, error=b'No such image: test'),
            self.result(1, error=b'pull access denied; authentication required')]
        db_init = mock.Mock()
        with self.assertRaises(dep.DependencyFailure):
            self.d.before_fixture(db_init)
        db_init.assert_not_called()
        self.assertEqual(self.records[-1]['error_category'], 'REGISTRY_AUTH')

    def test_secrets_and_private_urls_never_enter_records(self):
        sentinel = 'TEST_ONLY_DO_NOT_PUBLISH_918273'
        private_ref = 'private.example.invalid/restricted/image:' + sentinel
        self.runner.return_value = self.result(1, error=(
            'unauthorized: Authorization: Bearer ' + sentinel + '\n'
            'https://user:' + sentinel + '@private.example.invalid/x?token=' + sentinel + '\n'
            'Set-Cookie: ' + sentinel + '\nPRIVATE KEY ' + sentinel).encode())
        with self.assertRaises(dep.DependencyFailure):
            self.d.operation('private-reference', 'INSPECT', private_ref, ['image', 'inspect', private_ref])
        published = json.dumps(self.records)
        for secret in [sentinel, 'private.example.invalid', 'Authorization', 'Set-Cookie', 'PRIVATE KEY']:
            self.assertNotIn(secret, published)
        self.assertTrue(self.records[-1]['image_reference'].startswith('private-sha256:'))
        self.assertEqual(self.records[-1]['error_category'], 'REGISTRY_AUTH')

    def test_error_categories_and_unknown(self):
        cases = {b'manifest unknown': 'IMAGE_NOT_FOUND', b'authentication required': 'REGISTRY_AUTH',
            b'429 Too Many Requests': 'REGISTRY_RATE_LIMIT', b'lookup registry: no such host': 'NETWORK_DNS',
            b'TLS handshake timeout': 'NETWORK_TIMEOUT', b'x509: certificate signed by unknown authority': 'TLS_CERTIFICATE',
            b'no matching manifest for linux/amd64': 'PLATFORM_MISMATCH',
            b'Cannot connect to the Docker daemon': 'DOCKER_DAEMON', b'no space left on device': 'DISK_OR_STORAGE',
            b'unrecognized safe test failure': 'UNKNOWN'}
        for value, expected in cases.items():
            with self.subTest(expected=expected):
                self.assertEqual(dep.classify(value), expected)

    def test_bounded_timeout_has_no_retry(self):
        self.runner.side_effect = subprocess.TimeoutExpired('docker', 60, stderr=b'PRIVATE_TEST_DATA')
        with self.assertRaises(dep.DependencyFailure):
            self.d.operation('postgis-remote', 'INSPECT', self.REF, ['manifest', 'inspect', self.REF], timeout=60)
        self.assertEqual(self.runner.call_count, 1)
        self.assertTrue(self.records[-1]['deadline_exceeded'])
        self.assertNotIn('PRIVATE_TEST_DATA', json.dumps(self.records))

    def test_malformed_inspect_cannot_pass(self):
        self.runner.return_value = subprocess.CompletedProcess([], 0, b'not-json', b'')
        with self.assertRaises(dep.DependencyFailure):
            self.d.local('malformed', self.REF)
        self.assertEqual(self.records[-1]['status'], 'FAIL')

    def test_all_required_operation_fields(self):
        self.runner.return_value = self.result(value=self.IMAGE)
        self.d.local('valid', self.REF)
        o = self.records[-1]
        required = {'operation_id', 'operation_type', 'image_reference', 'expected_platform', 'started_at',
                    'completed_at', 'elapsed_ms', 'exit_code', 'status', 'error_category', 'verified_image_id', 'verified_digests'}
        self.assertTrue(required <= set(o))
        self.assertEqual(o['exit_code'], 0)
        self.assertEqual(o['expected_platform'], 'linux/amd64')

    def test_actual_harness_does_not_initialize_db_after_dependency_failure(self):
        import linux_rehearsal as h
        trial = mock.Mock()
        trial.root = HERE
        trial.dependencies.engine.return_value = None
        trial.dependency_preflight.side_effect = dep.DependencyFailure({'operation_id': 'test', 'error_category': 'UNKNOWN'})
        with mock.patch.object(h, 'gate', return_value={'status': 'PASS'}):
            with self.assertRaises(dep.DependencyFailure):
                h.Rehearsal.execute(trial)
        trial.command.assert_not_called()  # full fixture generator is downstream too
        trial.services.assert_not_called()
        trial.helper.assert_not_called()

    def test_no_unapproved_image_or_gate_changes(self):
        self.assertEqual(dep.SERVICES, (('postgis', 'postgis/postgis:16-3.5'),
            ('minio', 'minio/minio:RELEASE.2025-07-23T15-54-02Z'), ('mc', 'minio/mc:RELEASE.2025-07-21T05-28-08Z'),
            ('caddy', 'caddy:2.10-alpine'), ('python', 'python:3.12-slim')))
        self.assertEqual(dep.APPLICATION, 'phokarta-backend:' + fixture.APP_SHA)
        self.assertEqual(dep.PLATFORM, 'linux/amd64')


if __name__ == '__main__':
    unittest.main()

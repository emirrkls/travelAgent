"""Small infrastructure checks. NOT substitutes for full-volume rehearsal."""
import ast
import copy
from collections import Counter
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


def category_regression_evidence():
    """Mock readback for infrastructure unit tests; never operational evidence."""
    def report(passed, categories, spikes):
        return {'version': 'didim-catalog-anomaly-v1', 'passed': passed,
                'before': {'active_places': 0, 'category_counts': {}},
                'after': {'active_places': 71, 'category_counts': categories},
                'deltas': {'category_growth': categories, 'category_spikes': spikes,
                           'same_coordinate_anomalies': 0, 'same_name_category_near_duplicate_pairs': 0,
                           'dense_marker_excess': 0},
                **{key: 0 for key in ['invalid_or_out_of_scope_coordinates', 'invalid_categories',
                     'provider_reference_collisions', 'source_orphans', 'canonical_without_provenance',
                     'duplicate_canonical_assignments']}}
    return {'classification': 'ISOLATED_CATEGORY_REGRESSION_ONLY',
            'balanced': report(True, {'CAFE': 36, 'RESTAURANT': 35}, 0),
            'homogeneous': report(False, {'CAFE': 71}, 1),
            'negative_transaction_rolled_back': True, 'restored_snapshot_equal': True,
            'balanced_recheck_passed': True}


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
            self.assertEqual(info['selected_category_counts'], {'CAFE': 36, 'RESTAURANT': 35})
            manifest = json.loads((Path(temporary) / 'sealed/stage_1_import_manifest.json').read_bytes())['manifest']
            sources = {row['source_record_id']: row for row in manifest['source_records']}
            selected = [row for row in manifest['candidates'] if row['selected_for_stage']]
            self.assertEqual(Counter(row['canonical']['category'] for row in selected), {'CAFE': 36, 'RESTAURANT': 35})
            self.assertEqual(sorted(row['canonical_place_id'] for row in selected), info['selected_canonical_ids'])
            from phokarta_place_ingestion.production_categories import ProductionCategoryMapper
            mapper = ProductionCategoryMapper()
            for candidate in selected:
                category = candidate['canonical']['category']
                self.assertEqual(candidate['decision'], 'AUTO_CREATE')
                self.assertTrue(candidate['canary_eligible'])
                self.assertEqual(candidate['hard_blockers'], [])
                self.assertEqual(len(candidate['source_record_ids']), 2)
                for source_id in candidate['source_record_ids']:
                    source = sources[source_id]
                    self.assertEqual(source['proposed_place_category'], category)
                    self.assertEqual(source['normalized_name'], candidate['canonical']['name'])
                    self.assertEqual(mapper.mapped_categories(source['provider'].lower(), source['provider_categories']), (category,))
                    self.assertEqual(mapper.map(source['provider'].lower(), source['provider_categories']).rule, 'exact')

    def test_profile_is_deterministic_and_index_bounded(self):
        self.assertEqual(Counter(fixture.selected_profile(i)[0] for i in range(71)), {'CAFE': 36, 'RESTAURANT': 35})
        self.assertEqual(fixture.selected_profile(1), ('RESTAURANT', 'İnci Restoran 1',
            {'overture': 'restaurant', 'fsq': '4bf58dd8d48988d1c4941735'}))
        for invalid in [-1, 71, True, 1.0]:
            with self.subTest(index=invalid), self.assertRaises(ValueError):
                fixture.selected_profile(invalid)

    def test_taxonomy_mismatch_fails_before_manifest_write(self):
        app = Path(os.environ.get('GITHUB_WORKSPACE', HERE.parents[2])) / 'application'
        if not app.is_dir():
            app = HERE.parents[2]
        taxonomy = dict(fixture.SELECTED_TAXONOMY, RESTAURANT=('Restoran', 'restaurant', '4bf58dd8d48988d16d941735'))
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(fixture, 'SELECTED_TAXONOMY', taxonomy):
            root = Path(temporary) / 'sealed'
            with self.assertRaisesRegex(ValueError, 'SYNTHETIC_CATEGORY_TAXONOMY_MISMATCH'):
                fixture.generate(app, root, quarantine=2, rejected=2, dual_quarantine=1, padding=0)
            self.assertFalse(root.exists())

    def test_real_category_regression_contract_requires_homogeneous_failure_and_restoration(self):
        evidence = category_regression_evidence()
        fixture.validate_category_regression(evidence)
        for path, value in [
            (('homogeneous', 'passed'), True), (('homogeneous', 'deltas', 'category_spikes'), 0),
            (('balanced', 'passed'), False), (('negative_transaction_rolled_back',), False),
            (('restored_snapshot_equal',), False), (('balanced_recheck_passed',), False),
            (('balanced', 'source_orphans'), 1),
        ]:
            broken = copy.deepcopy(evidence)
            target = broken
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'CATEGORY_REGRESSION_NOT_VERIFIED'):
                fixture.validate_category_regression(broken)
        for broken in [None, {}, {'balanced': evidence['balanced']}]:
            with self.subTest(missing=broken), self.assertRaises(ValueError):
                fixture.validate_category_regression(broken)

    def test_category_negative_control_uses_unchanged_service_and_transaction_rollback(self):
        code = (HERE / 'RehearsalSeed.java').read_text()
        self.assertIn('var result=anomaly.audit(A,before);', code)
        self.assertIn('status.setRollbackOnly();', code)
        self.assertIn('!anomaly.captureDidimBaseline().equals(restored)', code)
        self.assertNotIn('extends PlacePilotCatalogAnomalyService', code)

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
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.anonymous = Path(self.temporary.name)
        (self.anonymous / 'config.json').write_text('{"auths":{}}')
        self.d = dep.Dependencies(lambda name, value: self.records.append(value), runner=self.runner,
                                  anonymous_config=self.anonymous)

    @staticmethod
    def result(code=0, value=None, error=b''):
        return subprocess.CompletedProcess([], code, json.dumps(value).encode() if value is not None else b'', error)

    def test_missing_image_is_acquired_once_and_independently_inspected(self):
        self.runner.side_effect = [self.result(1, error=b'Error: No such image: test'), self.result(value=self.REMOTE),
                                   self.result(), self.result(value=self.IMAGE)]
        self.d.acquire('postgis', self.REF)
        pulls = [c for c in self.runner.call_args_list if 'pull' in c.args[0]]
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
            ('minio', 'phokarta-test-minio:RELEASE.2025-07-23T15-54-02Z'), ('mc', 'phokarta-test-mc:RELEASE.2025-07-21T05-28-08Z'),
            ('caddy', 'caddy:2.10-alpine'), ('python', 'python:3.12-slim')))
        self.assertEqual(dep.APPLICATION, 'phokarta-backend:' + fixture.APP_SHA)
        self.assertEqual(dep.PLATFORM, 'linux/amd64')

    def test_no_minio_registry_acquisition_is_allowed(self):
        for identity in ['minio', 'mc']:
            for ref in [dict(dep.SERVICES)[identity], 'minio/' + identity + ':' + dep.RELEASES[identity],
                        'quay.io/minio/' + identity + ':' + dep.RELEASES[identity]]:
                with self.subTest(ref=ref), self.assertRaisesRegex(ValueError, 'UNAPPROVED_EXTERNAL_DEPENDENCY'):
                    self.d.acquire(identity, ref)
        self.runner.assert_not_called()

    def test_external_pulls_never_inherit_populated_registry_config(self):
        ref = self.REF
        (self.anonymous / 'config.json').write_text('{"auths":{"quay.io":{"auth":"SECRET_TEST"}}}')
        with self.assertRaisesRegex(ValueError, 'ANONYMOUS_CONFIG_REQUIRED'):
            self.d.operation('postgis-pull', 'PULL', ref, ['pull', ref], anonymous=True)
        self.runner.assert_not_called()
        self.assertNotIn('SECRET_TEST', json.dumps(self.records))

    def test_external_image_requires_actual_registry_digest(self):
        self.runner.side_effect = [self.result(value=self.IMAGE), self.result(value=self.REMOTE),
                                   self.result(value=dict(self.IMAGE, digests=[]))]
        with self.assertRaises(dep.DependencyFailure):
            self.d.acquire('postgis', self.REF)

    def test_release_and_executable_contracts_are_exact_and_sanitized(self):
        for identity, commands in [('minio', ['server']), ('mc', ['alias', 'mb', 'stat', 'ready'])]:
            raw = (identity + ' version ' + dep.RELEASES[identity] + ' (commit-id=test)\n' +
                   '\n'.join('M55B_CONTRACT_' + c for c in commands) + '\nPRIVATE_TEST_DATA\n').encode()
            observed = dep.Dependencies.release_projection(identity, raw)
            self.assertEqual(observed['embedded_release'], dep.RELEASES[identity])
            self.assertEqual(observed['invocation_contracts'], commands)
            self.assertNotIn('PRIVATE_TEST_DATA', json.dumps(observed))
            for invalid in [raw.replace(dep.RELEASES[identity].encode(), b'RELEASE.2025-01-01T00-00-00Z'),
                            raw.replace(b'M55B_CONTRACT_' + commands[0].encode(), b'MISSING')]:
                with self.assertRaises(ValueError):
                    dep.Dependencies.release_projection(identity, invalid)

    def test_all_dependencies_precede_network_or_fixture(self):
        events = []
        with mock.patch.object(self.d, 'engine', side_effect=lambda: events.append('engine')), \
             mock.patch.object(self.d, 'local', side_effect=lambda *a: events.append('app')), \
             mock.patch.object(self.d, 'acquire', side_effect=lambda identity, ref: events.append(identity)):
            self.d.before_fixture(lambda: events.append('network'))
        self.assertEqual(events, ['engine', 'app', *[name for name, _ in dep.EXTERNAL], 'network'])

    def trial(self):
        trial = mock.Mock()
        trial.root = self.anonymous
        (trial.root / 'fixture.json').write_text('{}')
        manual = {'places': 2, 'manual': 2, 'sources': 0, 'refs': 0, 'v17': 1, 'b_writes': 0, 'b_sources': 0}
        seeded = {'places': 73, 'manual': 2, 'retired': 71, 'sources': 18924, 'refs': 142, 'inactive_refs': 142,
                  'quarantine': 16064, 'b_writes': 0, 'b_sources': 0, 'b_decisions': 0, 'v17': 1,
                  'canonical_uuid_digest': fixture.IDENTITY_DIGEST, 'b_gate': 'NONE',
                  'category_policy_regression': category_regression_evidence()}
        trial.helper.side_effect = [manual, seeded]
        trial.sql.return_value = {'b_runs': 0}
        trial.historical_preflight.side_effect = lambda callback: callback()
        return trial

    def test_services_v17_and_unchanged_https_precede_full_fixture(self):
        import linux_rehearsal as h
        trial = self.trial()
        with mock.patch.object(h, 'gate', return_value={'status': 'PASS'}):
            h.Rehearsal.execute(trial)
        names = [(c[0], c.args) for c in trial.mock_calls]
        keys = [c[0] for c in names]
        self.assertLess(keys.index('dependency_preflight'), keys.index('services'))
        self.assertLess(keys.index('services'), keys.index('helper'))
        self.assertLess(keys.index('persistent'), keys.index('command'))
        self.assertEqual([c.args[0] for c in trial.helper.call_args_list], ['manual', 'seed-import'])
        trial.persistent.assert_called_once()
        trial.historical_preflight.assert_called_once()
        trial.operational.assert_called_once()
        trial.validate.assert_called_once()

    def test_failed_https_stops_before_full_fixture_or_product(self):
        import linux_rehearsal as h
        trial = self.trial()
        trial.persistent.side_effect = h.Failure('TEST_TLS_FAILURE')
        with mock.patch.object(h, 'gate', return_value={'status': 'PASS'}), self.assertRaises(h.Failure):
            h.Rehearsal.execute(trial)
        trial.command.assert_not_called()
        trial.operational.assert_not_called()
        trial.historical_preflight.assert_not_called()

    def test_missing_category_negative_evidence_blocks_before_audit_or_runner(self):
        import linux_rehearsal as h
        trial = self.trial()
        states = trial.helper.side_effect
        manual, seeded = list(states)
        seeded.pop('category_policy_regression')
        trial.helper.side_effect = [manual, seeded]
        with mock.patch.object(h, 'gate', return_value={'status': 'PASS'}), self.assertRaisesRegex(ValueError, 'CATEGORY_REGRESSION_NOT_VERIFIED'):
            h.Rehearsal.execute(trial)
        trial.historical_preflight.assert_not_called()
        trial.operational.assert_not_called()

    def test_contract_and_bucket_readback_are_not_skipped(self):
        import linux_rehearsal as h
        code = (HERE / 'linux_rehearsal.py').read_text()
        self.assertIn('mc stat --json fixture/m55b-isolated-media', code)
        self.assertIn("self.publish('s3_smoke.json', verify_smoke(smoke))", code)
        self.assertIn('self.verify_minio_contracts()', code)
        self.assertIn('Dependencies.release_projection(identity, raw)', code)
        self.assertIn("*self.common(sealed=False)", code)


if __name__ == '__main__':
    unittest.main()

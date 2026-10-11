"""Deterministic assembly/smoke failure tests. No network, fixtures or Docker."""
import base64
import hashlib
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import release_assets as r
import dependencies as d


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.records = []
        self.opener = mock.Mock()
        self.client = r.OfficialReleases(self.root / 'download', lambda n, v: self.records.append(v), opener=self.opener)

    def response(self, payload=b'test'):
        response = io.BytesIO(payload)
        response.status = 200
        return response

    def test_download_success_and_metadata_accept_contract(self):
        self.opener.open.return_value = self.response()
        path, observation = self.client.download('https://api.github.com/repos/minio/minio/releases/tags/test', 'test.json')
        self.assertEqual(path.read_bytes(), b'test')
        self.assertEqual(observation['status'], 'PASS')
        self.assertEqual(self.opener.open.call_args.args[0].get_header('Accept'), 'application/vnd.github+json')

    def test_download_failure_is_terminal_secret_safe_and_not_retried(self):
        sentinel = 'PRIVATE_TEST_ACCESS_MATERIAL'
        self.opener.open.side_effect = urllib.error.HTTPError('https://user:' + sentinel + '@example.invalid',
                                                            403, sentinel, {}, io.BytesIO(sentinel.encode()))
        with self.assertRaisesRegex(r.ReleaseFailure, 'ASSET_DOWNLOAD_FAILED'):
            self.client.download('https://github.com/minio/minio/approved', 'test.bin')
        self.assertEqual(self.opener.open.call_count, 1)
        self.assertEqual(self.records[0]['error_category'], 'HTTP_403')
        self.assertNotIn(sentinel, json.dumps(self.records))
        self.assertNotIn('example.invalid', json.dumps(self.records))

    def test_download_empty_and_size_limit_fail_closed(self):
        for name, data, limit in [('empty', b'', 10), ('oversize', b'xx', 1)]:
            self.opener.open.return_value = self.response(data)
            with self.subTest(name=name), self.assertRaises(r.ReleaseFailure):
                self.client.download('https://github.com/minio/minio/approved', name, maximum=limit)
            self.assertEqual(self.records[-1]['status'], 'FAIL')

    def test_download_total_deadline_does_not_reset(self):
        self.opener.open.return_value = self.response()
        with mock.patch.object(r.time, 'monotonic', side_effect=[0, 2, 3]), self.assertRaisesRegex(r.ReleaseFailure, 'DOWNLOAD_DEADLINE'):
            self.client.download('https://github.com/minio/minio/approved', 'deadline', deadline=1)
        self.assertEqual(self.records[-1]['status'], 'FAIL')

    def test_nonofficial_or_insecure_redirect_is_refused(self):
        for url in ['http://github.com/minio/minio/x', 'https://mirror.example.invalid/x',
                    'https://user:secret@github.com/minio/minio/x', 'https://github.com:444/minio/minio/x']:
            with self.subTest(url=url), self.assertRaises(r.ReleaseFailure):
                r.safe_url(url)

    def test_exact_asset_is_required_and_no_fallback_or_other_release_used(self):
        s = r.SPECS['minio']
        release = {'tag_name': s['tag'], 'draft': False, 'assets': []}
        ref = {'object': {'type': 'tag', 'sha': s['tag_object']}}
        tag = {'tag': s['tag'], 'object': {'type': 'commit', 'sha': s['commit']},
               'verification': {'verified': True, 'reason': 'valid'}}
        with mock.patch.object(self.client, 'api', side_effect=[release, ref, tag]), \
             mock.patch.object(self.client, 'download') as download, self.assertRaisesRegex(r.ReleaseFailure, 'EXACT_ASSET_UNAVAILABLE'):
            self.client.acquire('minio')
        download.assert_not_called()

    def test_changed_release_or_tag_identity_is_rejected(self):
        with mock.patch.object(self.client, 'api', side_effect=[{'tag_name': 'latest', 'draft': False}, {}]), \
             self.assertRaisesRegex(r.ReleaseFailure, 'RELEASE_IDENTITY_MISMATCH'):
            self.client.acquire('minio')

    def test_checksum_and_checksum_filename_must_match(self):
        binary, checksum = self.root / 'binary', self.root / 'checksum'
        binary.write_bytes(b'verified-test-only-binary')
        s = dict(r.SPECS['minio'], sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
        with mock.patch.dict(r.SPECS, {'minio': s}):
            checksum.write_text(s['sha256'] + ' minio.' + s['tag'])
            r.verify_checksum(binary, checksum, 'minio')
            for content in ['0' * 64 + ' minio.' + s['tag'], s['sha256'] + ' other-release']:
                checksum.write_text(content)
                with self.assertRaisesRegex(r.ReleaseFailure, 'CHECKSUM_MISMATCH'):
                    r.verify_checksum(binary, checksum, 'minio')

    def test_elf_architecture_is_verified_not_filename_inferred(self):
        binary = self.root / 'binary'
        data = bytearray(64)
        data[:7] = b'\x7fELF\x02\x01\x01'
        struct.pack_into('<HH', data, 16, 2, 62)
        binary.write_bytes(data)
        r.elf(binary)
        for offset, value in [(18, 183), (4, 1), (5, 2)]:
            wrong = bytearray(data)
            wrong[offset] = value
            binary.write_bytes(wrong)
            with self.subTest(offset=offset), self.assertRaisesRegex(r.ReleaseFailure, 'BINARY_ARCHITECTURE_MISMATCH'):
                r.elf(binary)

    def test_assembled_identity_base_entrypoint_and_labels_are_independent_gates(self):
        s, digest, image = r.SPECS['minio'], 'sha256:' + 'a' * 64, 'sha256:' + 'b' * 64
        actual = {'Id': image, 'Os': 'linux', 'Architecture': 'amd64', 'RootFS': {'Layers': ['base', 'binary']},
                  'Config': {'Entrypoint': ['minio'], 'Labels': {'m55b.release': s['tag'], 'm55b.binary.sha256': s['sha256'],
                    'm55b.base.digest': digest, 'm55b.upstream.commit': s['commit']}}}
        kwargs = dict(identity='minio', expected_id=image, base_digest=digest, base_layers=['base'], binary_sha=s['sha256'])
        self.assertEqual(r.verify_image(actual, **kwargs)['status'], 'PASS')
        for changed in [dict(actual, Id='sha256:' + 'c' * 64), dict(actual, Architecture='arm64'),
                        dict(actual, RootFS={'Layers': ['different']}), dict(actual, Config={'Entrypoint': ['other'], 'Labels': {}})]:
            with self.subTest(changed=changed), self.assertRaises(r.ReleaseFailure):
                r.verify_image(changed, **kwargs)

    def test_s3_bytes_digest_and_cleanup_are_all_required(self):
        (self.root / 'input.bin').write_bytes(b'isolated-smoke\x00')
        (self.root / 'readback.bin').write_bytes(b'isolated-smoke\x00')
        (self.root / 'bucket.json').write_text('{"status":"success"}')
        (self.root / 'remaining.json').write_text('')
        self.assertEqual(r.verify_smoke(self.root)['status'], 'PASS')
        (self.root / 'readback.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(r.ReleaseFailure, 'S3_SMOKE_BYTE_OR_DIGEST_MISMATCH'):
            r.verify_smoke(self.root)
        (self.root / 'readback.bin').write_bytes(b'isolated-smoke\x00')
        (self.root / 'remaining.json').write_text('{"key":"leftover"}')
        with self.assertRaisesRegex(r.ReleaseFailure, 'S3_SMOKE_BUCKET_OR_CLEANUP_FAILED'):
            r.verify_smoke(self.root)

    def test_missing_smoke_readback_cannot_pass(self):
        with self.assertRaisesRegex(r.ReleaseFailure, 'S3_SMOKE_MISSING_OR_MALFORMED'):
            r.verify_smoke(self.root)

    def test_acquisition_failure_prevents_network_and_heavy_fixture(self):
        import linux_rehearsal as h
        trial = mock.Mock()
        trial.dependencies.before_fixture.side_effect = lambda callback: callback()
        trial.assemble_minio.side_effect = r.ReleaseFailure('TEST_ASSET_FAILURE')
        with self.assertRaises(r.ReleaseFailure):
            h.Rehearsal.dependency_preflight(trial)
        trial.verify_minio_contracts.assert_not_called()
        trial.prepare_network.assert_not_called()
        trial.services.assert_not_called()
        trial.command.assert_not_called()

    def test_no_binary_registry_publication_or_transport_weakening(self):
        code = (HERE / 'linux_rehearsal.py').read_text()
        self.assertIn("'--pull=never'", code)
        self.assertIn('BASE=python@', code)
        self.assertIn('verify_image(self.inspect(ref)', code)
        self.assertNotIn("'push'", code)
        for fragment in ['mc cp /smoke/input.bin', 'mc cp fixture/m55b-isolated-media/m55b-dependency-smoke.bin',
                         'mc rm fixture/m55b-isolated-media/m55b-dependency-smoke.bin']:
            self.assertIn(fragment, code)
        self.assertLess(code.index('cmp /smoke/input.bin'), code.index('mc rm fixture/'))
        self.assertLess(code.index('test "$input_hash" = "$readback_hash"'), code.index('mc rm fixture/'))

    def test_real_minisign_protocol_and_tamper_detection_with_ephemeral_test_key(self):
        executable = shutil.which('openssl')
        self.assertIsNotNone(executable)  # Required verifier, not a silently skipped test.
        private, public, binary, signature = [self.root / n for n in ['ephemeral-test-key.pem', 'public.der', 'binary', 'signature']]
        def command(*args):
            subprocess.run([executable, *map(str, args)], capture_output=True, check=True, timeout=10)
        command('genpkey', '-algorithm', 'ED25519', '-out', private)
        command('pkey', '-in', private, '-pubout', '-outform', 'DER', '-out', public)
        key_id = b'TESTKEY1'
        key = base64.b64encode(b'Ed' + key_id + public.read_bytes()[-32:]).decode()
        binary.write_bytes(b'isolated deterministic signed content')
        comment = 'filename:isolated-test-only'
        signatures = []
        for n in range(2):
            message = self.root / f'message-{n}'
            output = self.root / f'detached-{n}'
            message.write_bytes(hashlib.blake2b(binary.read_bytes()).digest() if n == 0 else signatures[0] + comment.encode())
            command('pkeyutl', '-sign', '-inkey', private, '-rawin', '-in', message, '-out', output)
            signatures.append(output.read_bytes())
        signature.write_text('untrusted comment: fixture\n' + base64.b64encode(b'ED' + key_id + signatures[0]).decode() +
                             '\ntrusted comment: ' + comment + '\n' + base64.b64encode(signatures[1]).decode() + '\n')
        with mock.patch.object(r, 'PUBLIC_KEY', key):
            r.verify_minisign(binary, signature, self.root)
            binary.write_bytes(b'tampered')
            with self.assertRaisesRegex(r.ReleaseFailure, 'AUTHENTICATED_SIGNATURE_MISMATCH'):
                r.verify_minisign(binary, signature, self.root)
            binary.write_bytes(b'isolated deterministic signed content')
            signature.write_text(signature.read_text().replace(comment, 'filename:changed-without-signing'))
            with self.assertRaisesRegex(r.ReleaseFailure, 'AUTHENTICATED_SIGNATURE_MISMATCH'):
                r.verify_minisign(binary, signature, self.root)


if __name__ == '__main__':
    unittest.main()

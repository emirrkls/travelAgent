"""Strict separate SNI/name controls, including real ephemeral loopback TLS."""
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import hostname_control as h
from target_diagnostics import DiagnosticFailure

POSITIVE = subprocess.CompletedProcess([], 0, b'', b'CONNECTION ESTABLISHED\nVerification: OK\n')
NEGATIVE = subprocess.CompletedProcess([], 1, b'', b'verify error:num=62:hostname mismatch\n')


class HostnameControlTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.ca = Path(self.temporary.name) / 'public-ca.crt'
        self.ca.write_bytes(b'test-public-certificate')
        self.which = mock.patch.object(h.shutil, 'which', return_value='/trusted/openssl')
        self.which.start()
        self.addCleanup(self.which.stop)
        self.runner = mock.patch.object(h.subprocess, 'run', side_effect=[POSITIVE, NEGATIVE]).start()
        self.addCleanup(mock.patch.stopall)

    def run_control(self):
        return h.verify_hostname_control(ca_file=self.ca)

    def test_sni_stays_valid_only_expected_name_changes_and_chain_errors_are_fatal(self):
        result = self.run_control()
        self.assertTrue(result['negative_control_rejected'] and result['sni_preserved'] and result['positive_reference_verified'])
        self.assertEqual(result['mismatch_verify_code'], 62)
        commands = [call.args[0] for call in self.runner.call_args_list]
        for command in commands:
            self.assertEqual(command[command.index('-servername') + 1], 'm55b-api')
            self.assertEqual(command[command.index('-connect') + 1], 'm55b-api:8443')
            self.assertIn('-verify_return_error', command)
            self.assertEqual(command[command.index('-CAfile') + 1], str(self.ca))
            self.assertIn('-no-CApath', command)
            self.assertIn('-no-CAstore', command)
        self.assertEqual(commands[0][commands[0].index('-verify_hostname') + 1], 'm55b-api')
        self.assertEqual(commands[1][commands[1].index('-verify_hostname') + 1], 'wrong-host.example.invalid')
        for call in self.runner.call_args_list:
            self.assertEqual(call.kwargs['input'], b'')
            self.assertTrue(0 < call.kwargs['timeout'] <= 5)
            self.assertEqual(call.kwargs['env']['LC_ALL'], 'C')

    def test_positive_reference_must_succeed_before_negative(self):
        self.runner.side_effect = [subprocess.CompletedProcess([], 1, b'', b'PRIVATE_TEST_SECRET')]
        with self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_REFERENCE_FAILED'):
            self.run_control()
        self.assertEqual(self.runner.call_count, 1)

    def test_generic_tls_alert_connection_error_and_other_verify_codes_never_pass(self):
        failures = [b'TLS alert internal error PRIVATE_TEST_SECRET', b'Connection refused',
            b'verify error:num=20:unable to get local issuer certificate\n',
            b'verify error:num=10:certificate has expired\n',
            b'verify error:num=62:hostname mismatch\nverify error:num=20:unknown issuer\n',
            b'PRIVATE_TEST_SECRET hostname mismatch']
        for stderr in failures:
            self.runner.reset_mock(side_effect=True)
            self.runner.side_effect = [POSITIVE, subprocess.CompletedProcess([], 1, b'', stderr)]
            with self.subTest(stderr=stderr), self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_CONTROL_UNCLASSIFIED'):
                self.run_control()
            self.assertEqual(self.runner.call_count, 2)

    def test_unexpected_negative_success_or_wrong_diagnostic_is_not_pass(self):
        for returned in [POSITIVE, subprocess.CompletedProcess([], 0, b'', NEGATIVE.stderr),
                         subprocess.CompletedProcess([], 1, b'', b'verify error:num=62:other error\n')]:
            self.runner.side_effect = [POSITIVE, returned]
            with self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_CONTROL_UNCLASSIFIED'):
                self.run_control()

    def test_missing_tool_or_ca_fails_before_connect(self):
        with mock.patch.object(h.shutil, 'which', return_value=None):
            with self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_CONTROL_TOOL_UNAVAILABLE'):
                self.run_control()
        self.runner.assert_not_called()
        with self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_REFERENCE_FAILED'):
            h.verify_hostname_control(ca_file=self.ca.parent / 'missing.crt')
        self.runner.assert_not_called()

    def test_timeout_is_terminal_no_retry(self):
        self.runner.side_effect = subprocess.TimeoutExpired('openssl', 5, stderr=b'PRIVATE_TEST_SECRET')
        with self.assertRaises(TimeoutError):
            self.run_control()
        self.assertEqual(self.runner.call_count, 1)

    def test_five_second_budget_does_not_reset_for_second_control(self):
        with mock.patch.object(h.time, 'monotonic', side_effect=[0, 1, 4, 4.5, 4.7, 4.8]):
            self.run_control()
        deadlines = [call.kwargs['timeout'] for call in self.runner.call_args_list]
        self.assertEqual(deadlines, [4, .5])

    def test_elapsed_deadline_even_after_completed_subprocess_fails(self):
        with mock.patch.object(h.time, 'monotonic', side_effect=[0, 0, 5.01]), self.assertRaises(TimeoutError):
            self.run_control()
        self.assertEqual(self.runner.call_count, 1)

    def test_output_is_bounded_and_never_in_exception(self):
        self.runner.side_effect = [POSITIVE, subprocess.CompletedProcess([], 1, b'', b'PRIVATE_TEST_SECRET' * 5000)]
        with self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_CONTROL_OUTPUT_LIMIT') as result:
            self.run_control()
        self.assertNotIn('PRIVATE_TEST_SECRET', str(result.exception))


class RealLoopbackTLSTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='m55b-test-tls-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        executable = shutil.which('openssl')
        if executable is None:
            raise RuntimeError('OPENSSL_REQUIRED_NO_SKIPPED_TLS_TEST')
        cls.ca, cls.cert, cls.key = (cls.root / name for name in ('ca.crt', 'leaf.crt', 'leaf.key'))
        ca_key, csr, extension = (cls.root / name for name in ('ca.key', 'leaf.csr', 'leaf.cnf'))
        extension.write_text('subjectAltName=DNS:m55b-api\nbasicConstraints=critical,CA:FALSE\n'
                             'keyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
        commands = [
            ['req', '-x509', '-newkey', 'rsa:2048', '-noenc', '-days', '1', '-subj', '/CN=M55B ephemeral test CA',
             '-keyout', ca_key, '-out', cls.ca, '-addext', 'basicConstraints=critical,CA:TRUE'],
            ['req', '-newkey', 'rsa:2048', '-noenc', '-subj', '/CN=m55b-api', '-keyout', cls.key, '-out', csr],
            ['x509', '-req', '-in', csr, '-CA', cls.ca, '-CAkey', ca_key, '-CAcreateserial', '-days', '1',
             '-extfile', extension, '-out', cls.cert]]
        for command in commands:
            result = subprocess.run([executable, *map(str, command)], capture_output=True, timeout=15)
            if result.returncode != 0:
                raise RuntimeError('EPHEMERAL_TEST_CERTIFICATE_GENERATION_FAILED')
        for path in (ca_key, cls.key):
            path.chmod(0o600)

    @contextmanager
    def server(self, connections):
        observed, failures = [], []
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert, self.key)
        def sni(sock, name, ctx):
            observed.append(name)
            if name != 'm55b-api':
                return ssl.ALERT_DESCRIPTION_UNRECOGNIZED_NAME
        context.set_servername_callback(sni)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(4)
            listener.settimeout(3)
            address = '127.0.0.1:' + str(listener.getsockname()[1])
            def serve():
                for _ in range(connections):
                    try:
                        raw, _ = listener.accept()
                        with raw:
                            raw.settimeout(2)
                            try:
                                with context.wrap_socket(raw, server_side=True) as tls:
                                    tls.recv(1)
                            except ssl.SSLError:
                                pass  # Expected peer verification rejection; no raw logs.
                    except Exception as error:
                        failures.append(type(error).__name__)
                        return
            thread = threading.Thread(target=serve, daemon=True)
            thread.start()
            try:
                yield address, observed
            finally:
                thread.join(timeout=4)
                self.assertFalse(thread.is_alive(), 'BOUNDED_TLS_THREAD_REQUIRED')
                self.assertEqual(failures, [])

    def test_real_sni_rejecting_server_still_proves_exact_client_hostname_mismatch(self):
        with self.server(3) as (address, observed):
            host, port = address.split(':')
            context = ssl.create_default_context(cafile=str(self.ca))
            self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
            self.assertTrue(context.check_hostname)
            with socket.create_connection((host, int(port)), timeout=2) as raw:
                with self.assertRaises(ssl.SSLError) as old_failure:
                    context.wrap_socket(raw, server_hostname='wrong-host.example.invalid')
            self.assertNotIsInstance(old_failure.exception, ssl.SSLCertVerificationError)
            result = h.verify_hostname_control(connect=address, ca_file=self.ca)
            self.assertEqual(result['mismatch_verify_code'], 62)
            self.assertTrue(result['sni_preserved'] and result['positive_reference_verified'])
            self.assertEqual(observed, ['wrong-host.example.invalid', 'm55b-api', 'm55b-api'])

    def test_real_untrusted_ca_cannot_be_accepted_as_hostname_rejection(self):
        # A non-CA certificate cannot trust this server's valid chain.
        with self.server(1) as (address, observed):
            with self.assertRaisesRegex(DiagnosticFailure, 'HOSTNAME_REFERENCE_FAILED'):
                h.verify_hostname_control(connect=address, ca_file=self.cert)
            self.assertEqual(observed, ['m55b-api'])


if __name__ == '__main__':
    unittest.main()

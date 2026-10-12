"""Test-only independent SNI / certificate hostname validation, never HTTP."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from target_diagnostics import DiagnosticFailure

TRUST_CA = Path('/usr/local/share/ca-certificates/m55b-isolated.crt')


def verify_hostname_control(*, connect='m55b-api:8443', server_name='m55b-api', ca_file=TRUST_CA):
    """Positive + negative references share one non-resetting five-second budget.

    Both connections send the SAME valid SNI. Only -verify_hostname changes.
    Explicit -verify_return_error makes certificate verification fatal. A generic
    alert/exit, missing command, timeout or other verification error is NOT PASS.
    Keyword overrides exist solely for deterministic loopback TLS regression tests.
    """
    started = time.monotonic()
    executable = shutil.which('openssl')
    if executable is None:
        raise DiagnosticFailure('HOSTNAME_CONTROL_TOOL_UNAVAILABLE')
    if not ca_file.is_file():
        raise DiagnosticFailure('HOSTNAME_REFERENCE_FAILED')
    environment = {'LC_ALL': 'C'}
    if os.name == 'nt':
        environment['SystemRoot'] = os.environ['SystemRoot']
    for expected in (server_name, 'wrong-host.example.invalid'):
        remaining = 5 - (time.monotonic() - started)
        if remaining <= 0:
            raise TimeoutError()
        args = [executable, 's_client', '-connect', connect, '-servername', server_name,
                '-verify_hostname', expected, '-verify_return_error', '-CAfile', str(ca_file),
                '-no-CApath', '-no-CAstore', '-brief', '-no_ign_eof']
        try:
            result = subprocess.run(args, input=b'', capture_output=True, timeout=remaining,
                                    env=environment, close_fds=True)
        except subprocess.TimeoutExpired:
            raise TimeoutError() from None
        if time.monotonic() - started >= 5:
            raise TimeoutError()
        raw = result.stdout + result.stderr
        if len(raw) > 64 * 1024:
            raise DiagnosticFailure('HOSTNAME_CONTROL_OUTPUT_LIMIT')
        errors = re.findall(rb'(?m)^verify error:num=(\d+):([^\r\n]+)\r?$', raw)
        if expected == server_name:
            if result.returncode != 0 or errors or not re.search(rb'(?m)^Verification: OK\r?$', raw):
                raise DiagnosticFailure('HOSTNAME_REFERENCE_FAILED')
        else:
            # X509_V_ERR_HOSTNAME_MISMATCH = 62. No broad SSLError acceptance.
            if result.returncode == 0 or errors != [(b'62', b'hostname mismatch')]:
                raise DiagnosticFailure('HOSTNAME_CONTROL_UNCLASSIFIED')
    return {'negative_control_rejected': True, 'sni_preserved': True,
            'positive_reference_verified': True, 'mismatch_verify_code': 62,
            'hostname_control_elapsed_ms': (time.monotonic() - started) * 1000}

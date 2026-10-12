"""Launch unchanged approved B1 implementation inside isolated TLS trust image."""
import argparse
import json
from pathlib import Path
import socket
import ssl
import sys
import time

from target_diagnostics import DiagnosticFailure, Recorder

ORIGIN = 'https://m55b-api:8443'


def load_b1(application):
    sys.path[:0] = [str(application / 'tools/place-ingestion/operations'),
                    str(application / 'tools/place-ingestion/src')]
    import product_evidence_worker as b1
    return b1


def tls_probe(application, root):
    """The approved three GETs; stdout carries only structured allowlisted phases."""
    record = Recorder()
    try:
        with record.step('APPLICATION_IMPORT'):
            b1 = load_b1(application)
            from fixture import write
        with record.step('DNS') as details:
            addresses = sorted({x[4][0] for x in socket.getaddrinfo('m55b-api', 8443)})
            import ipaddress
            private = bool(addresses) and all(ipaddress.ip_address(x).is_private for x in addresses)
            details.update(address_count=len(addresses), all_private=private)
            if not private:
                raise DiagnosticFailure('ISOLATED_DNS_REQUIRED')
        with record.step('TLS_CONTEXT') as details:
            ctx = ssl.create_default_context()
            details.update(cert_required=ctx.verify_mode == ssl.CERT_REQUIRED, check_hostname=ctx.check_hostname)
            if ctx.verify_mode != ssl.CERT_REQUIRED or not ctx.check_hostname:
                raise DiagnosticFailure('VERIFIED_TLS_REQUIRED')
        sock = None
        try:
            connection_start = time.monotonic()
            with record.step('TCP'):
                sock = socket.create_connection(('m55b-api', 8443), timeout=5)
            with record.step('TLS_CHAIN_HOSTNAME') as details:
                remaining = 5 - (time.monotonic() - connection_start)
                if remaining <= 0:
                    raise TimeoutError()
                sock.settimeout(remaining)
                with ctx.wrap_socket(sock, server_hostname='m55b-api') as tls:
                    tls_version = tls.version()
                    details['tls_version'] = tls_version
        finally:
            if sock is not None:
                sock.close()
        with record.step('TLS_NEGATIVE_HOSTNAME') as details:
            refused = False
            connection_start = time.monotonic()
            try:
                with socket.create_connection(('m55b-api', 8443), timeout=5) as sock:
                    remaining = 5 - (time.monotonic() - connection_start)
                    if remaining <= 0:
                        raise TimeoutError()
                    sock.settimeout(remaining)
                    with ctx.wrap_socket(sock, server_hostname='wrong-host.example.invalid'):
                        pass
            except ssl.SSLCertVerificationError:
                refused = True
            details['negative_control_rejected'] = refused
            if not refused:
                raise DiagnosticFailure('HOSTNAME_MISMATCH_NOT_REJECTED')
        results = []
        transport = b1.Transport(ORIGIN)
        for phase, path in [('B1_HEALTH_LIVE', '/health/live'), ('B1_HEALTH_READY', '/health/ready'),
                            ('B1_SEARCH', '/api/v1/places?search=Staging&size=20')]:
            with record.step(phase) as details:
                r = transport.request('GET', path)
                # Bodies remain in memory. No diagnostic substitution for B1.
                details.update(status=r['status'], latency_ms=r['latency_ms'], request_id=r['request_id'])
                if r['status'] != 200:
                    raise DiagnosticFailure('B1_HTTP_STATUS')
                body = r['body']
                valid = isinstance(body, dict) and (body.get('status') == 'UP' if path.startswith('/health')
                    else isinstance(body.get('content'), list) and
                    all(isinstance(x, dict) and 'id' in x for x in body['content']) and
                    'aa000000-0000-4000-8000-000000000001' in {x['id'] for x in body['content']})
                details['semantic_valid'] = valid
                if not valid:
                    raise DiagnosticFailure('B1_SEMANTICS')
                results.append({'path': path, 'status': r['status'], 'latency_ms': r['latency_ms'],
                                'request_id': r['request_id'], 'validation': 'VALID'})
        with record.step('ARTIFACT_WRITE'):
            write(root / 'tls.json', {'status': 'PASS', 'origin': ORIGIN, 'private_addresses': addresses,
                'chain_verified': True, 'hostname_verified': True, 'hostname_negative_control': True,
                'tls_version': tls_version, 'requests': results, 'production_connections': 0})
        record.finish()
        return 0
    except Exception:
        # step() already emitted exact safe phase/category. No traceback/body/env.
        return 1


def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=['tls', 'product'])
    p.add_argument('--application', type=Path, required=True)
    p.add_argument('--root', type=Path, required=True)
    a = p.parse_args()
    root, directory = a.root, a.root / 'evidence'
    if a.mode == 'tls':
        return tls_probe(a.application, root)
    else:
        b1 = load_b1(a.application)
        from fixture import sha
        config = json.loads((root / 'fixture.json').read_bytes())
        b1.Worker(directory, sha(directory / 'V3_OPERATIONS_PLAN.json'),
                  root / 'sealed/stage_1_import_manifest.json', config['envelope_sha256'], config['authorization'],
                  root / 'sealed/predecessor/stage_1_import_manifest.json', config['predecessor_sha256'],
                  b1.Transport(ORIGIN)).run()
        return 0


if __name__ == '__main__':
    raise SystemExit(main())

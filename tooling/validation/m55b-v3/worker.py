"""Launch unchanged approved B1 implementation inside isolated TLS trust image."""
import argparse
import json
from pathlib import Path
import socket
import ssl
import sys

ORIGIN = 'https://m55b-api:8443'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=['tls', 'product'])
    p.add_argument('--application', type=Path, required=True)
    p.add_argument('--root', type=Path, required=True)
    a = p.parse_args()
    sys.path[:0] = [str(a.application / 'tools/place-ingestion/operations'),
                    str(a.application / 'tools/place-ingestion/src')]
    import product_evidence_worker as b1
    from fixture import sha, write
    root, directory = a.root, a.root / 'evidence'
    if a.mode == 'tls':
        addresses = sorted({x[4][0] for x in socket.getaddrinfo('m55b-api', 8443)})
        import ipaddress
        if not addresses or not all(ipaddress.ip_address(x).is_private for x in addresses):
            raise ValueError('ISOLATED_DNS_REQUIRED')
        ctx = ssl.create_default_context()
        if ctx.verify_mode != ssl.CERT_REQUIRED or not ctx.check_hostname:
            raise ValueError('VERIFIED_TLS_REQUIRED')
        with socket.create_connection(('m55b-api', 8443), timeout=5) as sock:
            with ctx.wrap_socket(sock, server_hostname='m55b-api') as tls:
                tls_version = tls.version()
        # Negative hostname control uses the same trusted CA, not an insecure path.
        refused = False
        try:
            with socket.create_connection(('m55b-api', 8443), timeout=5) as sock:
                with ctx.wrap_socket(sock, server_hostname='wrong-host.example.invalid'):
                    pass
        except ssl.SSLCertVerificationError:
            refused = True
        if not refused:
            raise ValueError('HOSTNAME_MISMATCH_NOT_REJECTED')
        results = []
        transport = b1.Transport(ORIGIN)
        for path in ['/health/live', '/health/ready', '/api/v1/places?search=Staging&size=20']:
            r = transport.request('GET', path)
            valid = (r['status'] == 200 and (r['body'].get('status') == 'UP' if path.startswith('/health')
                      else 'aa000000-0000-4000-8000-000000000001' in {x['id'] for x in r['body'].get('content', [])}))
            results.append({'path': path, 'status': r['status'], 'latency_ms': r['latency_ms'],
                            'request_id': r['request_id'], 'validation': 'VALID' if valid else 'INVALID'})
            if not valid:
                write(root / 'tls.json', {'status': 'FAIL', 'requests': results})
                raise ValueError('UNCHANGED_B1_TLS_CONTRACT_FAILED')
        write(root / 'tls.json', {'status': 'PASS', 'origin': ORIGIN, 'private_addresses': addresses,
                                'chain_verified': True, 'hostname_verified': True, 'hostname_negative_control': True,
                                'tls_version': tls_version, 'requests': results, 'production_connections': 0})
    else:
        config = json.loads((root / 'fixture.json').read_bytes())
        b1.Worker(directory, sha(directory / 'V3_OPERATIONS_PLAN.json'),
                  root / 'sealed/stage_1_import_manifest.json', config['envelope_sha256'], config['authorization'],
                  root / 'sealed/predecessor/stage_1_import_manifest.json', config['predecessor_sha256'],
                  b1.Transport(ORIGIN)).run()


if __name__ == '__main__':
    main()

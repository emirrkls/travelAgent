"""Allowlisted Docker dependency evidence. No raw stderr, credentials or retries."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

PLATFORM = 'linux/amd64'
SERVICES = (
    ('postgis', 'postgis/postgis:16-3.5'),
    ('minio', 'quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z'),
    ('mc', 'quay.io/minio/mc:RELEASE.2025-07-21T05-28-08Z'),
    ('caddy', 'caddy:2.10-alpine'),
    ('python', 'python:3.12-slim'),
)
APPLICATION = 'phokarta-backend:e93cae3028b44ba47f407ce19f7001b8b2983011'
WORKER = 'm55b-isolated-b1:validation'
PUBLIC = {ref for _, ref in SERVICES} | {APPLICATION, WORKER}
QUAY = {ref for name, ref in SERVICES if name in {'minio', 'mc'}}
RELEASES = {name: ref.rsplit(':', 1)[1] for name, ref in SERVICES if ref in QUAY}
IMAGE_FORMAT = '{"id":{{json .Id}},"digests":{{json .RepoDigests}},"os":{{json .Os}},"architecture":{{json .Architecture}}}'
CATEGORIES = {'IMAGE_NOT_FOUND', 'REGISTRY_AUTH', 'REGISTRY_RATE_LIMIT', 'NETWORK_DNS',
              'NETWORK_TIMEOUT', 'TLS_CERTIFICATE', 'PLATFORM_MISMATCH', 'DOCKER_DAEMON',
              'DISK_OR_STORAGE', 'UNKNOWN'}


def utc():
    return datetime.now(timezone.utc).isoformat()


def safe_reference(ref):
    return ref if ref in PUBLIC else 'private-sha256:' + hashlib.sha256(ref.encode()).hexdigest()


def classify(stderr, *, timeout=False):
    if timeout:
        return 'NETWORK_TIMEOUT'
    text = stderr.decode(errors='replace').lower() if isinstance(stderr, bytes) else str(stderr).lower()
    rules = [
        ('DISK_OR_STORAGE', ('no space left on device', 'input/output error', 'read-only file system', 'disk quota exceeded')),
        ('DOCKER_DAEMON', ('cannot connect to the docker daemon', 'is the docker daemon running',
                           'error during connect', 'permission denied while trying to connect to the docker daemon socket')),
        ('REGISTRY_RATE_LIMIT', ('toomanyrequests', 'too many requests', 'pull rate limit', '429 too many')),
        ('TLS_CERTIFICATE', ('x509:', 'certificate verify failed', 'tls: failed to verify certificate', 'certificate has expired')),
        ('NETWORK_DNS', ('no such host', 'temporary failure in name resolution', 'server misbehaving', 'name or service not known')),
        ('NETWORK_TIMEOUT', ('i/o timeout', 'context deadline exceeded', 'tls handshake timeout', 'connection timed out')),
        ('PLATFORM_MISMATCH', ('no matching manifest for', 'does not match the specified platform', 'unsupported platform')),
        ('IMAGE_NOT_FOUND', ('manifest unknown', 'no such manifest:', 'no such image:', 'name unknown')),
        ('REGISTRY_AUTH', ('unauthorized:', 'authentication required', 'pull access denied', 'requested access to the resource is denied', '403 forbidden')),
    ]
    for category, needles in rules:
        if any(n in text for n in needles):
            return category
    if re.search(r'manifest for [^\r\n]+ not found', text):
        return 'IMAGE_NOT_FOUND'
    return 'UNKNOWN'


def error_signals(stderr):
    """Presence flags only. Never copy even a truncated error line or URL."""
    text = stderr.decode(errors='replace').lower()
    return {name: any(n in text for n in needles) for name, needles in {
        'manifest_unknown': ('manifest unknown', 'no such manifest:'),
        'local_image_missing': ('no such image:',),
        'access_denied': ('pull access denied', 'requested access to the resource is denied'),
        'authentication_required': ('authentication required', 'unauthorized:'),
        'rate_limited': ('toomanyrequests', 'too many requests', 'pull rate limit'),
        'dns_failure': ('no such host', 'name or service not known', 'temporary failure in name resolution'),
        'certificate_failure': ('x509:', 'certificate verify failed'),
        'daemon_unavailable': ('cannot connect to the docker daemon', 'is the docker daemon running'),
        'storage_failure': ('no space left on device', 'input/output error', 'read-only file system'),
        'platform_mismatch': ('no matching manifest for', 'unsupported platform'),
    }.items()}


class DependencyFailure(RuntimeError):
    def __init__(self, observation):
        self.observation = observation
        super().__init__('DEPENDENCY_' + observation.get('operation_id', 'projection') + '_' + observation['error_category'])


class Dependencies:
    def __init__(self, publish, *, runner=subprocess.run, anonymous_config=None):
        self.publish, self.runner = publish, runner
        self.anonymous_config = Path(anonymous_config) if anonymous_config is not None else None
        self.sequence = 0
        self.verified = {}

    def operation(self, identity, kind, ref, args, *, timeout=30, project=None, allow_missing=False, anonymous=False):
        if kind not in {'PULL', 'INSPECT', 'LOAD', 'CREATE'} or not re.fullmatch(r'[a-z0-9_-]{1,80}', identity):
            raise ValueError('DEPENDENCY_OPERATION_SCHEMA_INVALID')
        self.sequence += 1
        o = {'schema': 'm55b-docker-dependency-v1', 'operation_id': identity, 'operation_type': kind,
             'image_reference': safe_reference(ref), 'expected_platform': PLATFORM, 'started_at': utc(),
             'deadline_seconds': timeout, 'exit_code': None, 'status': 'FAIL', 'error_category': 'UNKNOWN',
             'verified_image_id': None, 'verified_digests': []}
        tick = time.monotonic()
        result = None
        try:
            prefix = ['docker']
            if anonymous:
                # Explicit empty CLI config; never inherit registry auth/credential helpers.
                if (self.anonymous_config is None or
                        json.loads((self.anonymous_config / 'config.json').read_bytes()) != {'auths': {}}):
                    raise ValueError('ANONYMOUS_CONFIG_REQUIRED')
                prefix += ['--config', str(self.anonymous_config)]
                o['registry_authentication'] = 'ANONYMOUS_EXPLICIT_EMPTY_CONFIG'
            result = self.runner([*prefix, *args], stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
            o['exit_code'] = result.returncode
            if result.returncode:
                o['error_category'] = classify(result.stderr)
                o['error_signals'] = error_signals(result.stderr)
            else:
                try:
                    if project:
                        o.update(project(result.stdout))
                    o.update(status='PASS', error_category=None)
                except (ValueError, KeyError, TypeError, IndexError):
                    # Parse/type/platform failures are never successful inspections.
                    o['error_category'] = 'UNKNOWN'
                except DependencyFailure as e:
                    o['error_category'] = e.observation['error_category']
        except subprocess.TimeoutExpired:
            o['error_category'] = 'NETWORK_TIMEOUT' if kind == 'PULL' or identity.endswith('-remote') else 'UNKNOWN'
            o['deadline_exceeded'] = True
        except OSError:
            o['error_category'] = 'DOCKER_DAEMON'
        finally:
            o.update(completed_at=utc(), elapsed_ms=(time.monotonic() - tick) * 1000)
            self.publish(f'dependency-{self.sequence:03d}-{identity}.json', o)
        if o['status'] != 'PASS':
            if allow_missing and o['error_category'] == 'IMAGE_NOT_FOUND':
                return None
            raise DependencyFailure(o)
        return result, o

    @staticmethod
    def image_projection(raw):
        v = json.loads(raw)
        if v['os'] != 'linux' or v['architecture'] != 'amd64':
            raise DependencyFailure({'error_category': 'PLATFORM_MISMATCH'})
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', v['id']):
            raise ValueError('IMAGE_ID_INVALID')
        digests = []
        for d in v['digests'] or []:
            digest = d.rsplit('@', 1)[-1]
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
                raise ValueError('IMAGE_DIGEST_INVALID')
            digests.append(digest)  # Registry/private repository names are deliberately excluded.
        return {'verified_image_id': v['id'], 'verified_digests': sorted(set(digests)),
                'observed_platform': 'linux/amd64'}

    @staticmethod
    def remote_projection(raw):
        v = json.loads(raw)
        rows = v if isinstance(v, list) else [v]
        platforms, digests = set(), set()
        for row in rows:
            descriptor = row.get('Descriptor', {})
            p = descriptor.get('platform', {})
            if (isinstance(p.get('os'), str) and isinstance(p.get('architecture'), str)
                    and re.fullmatch(r'[a-z0-9_]{1,20}', p['os'])
                    and re.fullmatch(r'[a-z0-9_]{1,20}', p['architecture'])):
                platforms.add(p['os'] + '/' + p['architecture'])
            digest = descriptor.get('digest')
            if digest and re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
                digests.add(digest)
        if PLATFORM not in platforms:
            if not platforms:
                raise ValueError('REMOTE_PLATFORM_EVIDENCE_MISSING')
            raise DependencyFailure({'error_category': 'PLATFORM_MISMATCH'})
        return {'observed_platforms': sorted(platforms), 'verified_digests': sorted(digests)}

    def local(self, identity, ref, *, allow_missing=False):
        def project(raw):
            result = self.image_projection(raw)
            if ref in QUAY:
                prefix = ref.rsplit(':', 1)[0] + '@sha256:'
                if not any(d.startswith(prefix) for d in json.loads(raw)['digests'] or []):
                    raise ValueError('QUAY_REPOSITORY_DIGEST_REQUIRED')
            return result
        observed = self.operation(identity + '-local', 'INSPECT', ref,
            ['image', 'inspect', '--format', IMAGE_FORMAT, ref], project=project,
            allow_missing=allow_missing)
        if observed:
            self.verified[ref] = observed[1]
        return observed

    def acquire(self, identity, ref):
        if (identity, ref) not in SERVICES:
            raise ValueError('UNAPPROVED_EXTERNAL_DEPENDENCY')
        existing = self.local(identity, ref, allow_missing=True)
        quay = ref in QUAY
        try:
            _, remote = self.operation(identity + '-remote', 'INSPECT', ref, ['manifest', 'inspect', '--verbose', ref],
                                      timeout=60, project=self.remote_projection, anonymous=quay)
        except DependencyFailure as e:
            # Owner-authorized Quay policy only. Failed inspection stays FAIL; actual
            # anonymous acquisition + independent local identity are still mandatory.
            if not quay or e.observation['error_category'] in {'DOCKER_DAEMON', 'DISK_OR_STORAGE', 'PLATFORM_MISMATCH'}:
                raise
            remote = e.observation
        if existing is None or quay:
            self.operation(identity + '-pull', 'PULL', ref, ['pull', '--platform', PLATFORM, ref],
                           timeout=300, anonymous=quay)
        # Independently inspect after remote verification/acquisition; never infer loaded identity from exit=0.
        verified = self.local(identity + '-verified', ref)[1]
        if quay and not verified['verified_digests']:
            raise DependencyFailure({'operation_id': identity + '-digest', 'error_category': 'UNKNOWN'})
        self.publish(identity + '-acquisition.json', {'image_reference': ref, 'status': 'PASS',
            'remote_inspection_status': remote['status'], 'remote_inspection_error': remote['error_category'],
            'actual_pull_required': quay or existing is None, 'anonymous': quay,
            'verified_image_id': verified['verified_image_id'], 'verified_digests': verified['verified_digests'],
            'cross_registry_byte_equivalence_claimed': False})

    @staticmethod
    def release_projection(identity, raw):
        if identity not in RELEASES or len(raw) > 16384:
            raise ValueError('RELEASE_CONTRACT_INVALID')
        text = raw.decode('utf-8')
        releases = re.findall(r'(?m)^' + identity + r' version (RELEASE\.[0-9TZ-]+)(?:\s|$)', text)
        if releases != [RELEASES[identity]]:
            raise ValueError('EMBEDDED_RELEASE_MISMATCH')
        contracts = ['server'] if identity == 'minio' else ['alias', 'mb', 'stat', 'ready']
        if any(text.splitlines().count('M55B_CONTRACT_' + x) != 1 for x in contracts):
            raise ValueError('EXECUTABLE_CONTRACT_MISSING')
        return {'embedded_release': releases[0], 'executable': identity,
                'invocation_contracts': contracts, 'shell_available': True}

    def engine(self):
        def projection(raw):
            v = json.loads(raw)
            if v['os'] != 'linux' or v['architecture'] not in {'x86_64', 'amd64'}:
                raise DependencyFailure({'error_category': 'PLATFORM_MISMATCH'})
            version = v['version']
            if not re.fullmatch(r'[0-9.]+', version):
                raise ValueError('ENGINE_VERSION_INVALID')
            return {'engine_version': version, 'observed_platform': PLATFORM}
        self.operation('docker-engine', 'INSPECT', 'DOCKER_ENGINE', ['info', '--format',
            '{"version":{{json .ServerVersion}},"os":{{json .OSType}},"architecture":{{json .Architecture}}}'], project=projection)

    def before_fixture(self, continuation):
        self.engine()
        self.local('application', APPLICATION)
        for identity, ref in SERVICES:
            self.acquire(identity, ref)
        return continuation()

"""Validation infrastructure ONLY. No SSH, production routes, secrets or package inputs."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

from capacity import gate, snapshot
from fixture import APP_SHA, A, B, IDENTITY_DIGEST, sha, write
from dependencies import Dependencies, DependencyFailure, SERVICES, WORKER, ASSEMBLED
from release_assets import OfficialReleases, ReleaseFailure, verify_image, verify_smoke
from target_diagnostics import from_logs
from runner_postmortem import collect as collect_postmortem

HERE = Path(__file__).resolve().parent
IMAGE = 'phokarta-backend:' + APP_SHA
NETWORK = 'm55b-isolated-validation'
DBNAME = 'm55b_fixture'


class Failure(RuntimeError):
    pass


def require(ok, code):
    if not ok:
        raise Failure(code)


def now():
    return datetime.now(timezone.utc).isoformat()


class Rehearsal:
    def __init__(self, app, public):
        require(os.name == 'posix' and os.getuid() != 0, 'NONROOT_LINUX_REQUIRED')
        self.app, self.public = app.resolve(), public.resolve()
        self.public.mkdir(exist_ok=True, parents=True)
        self.root = Path(tempfile.mkdtemp(prefix='m55b-private-', dir=os.environ['RUNNER_TEMP']))
        self.root.chmod(0o700)
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir(mode=0o700)
        self.containers, self.children, self.logs = [], [], []
        self.phase = 'INITIAL_CAPACITY'
        self.uid = f'{os.getuid()}:{os.getgid()}'
        self.result = {'assessment': 'BLOCKED', 'application_sha': APP_SHA,
                       'harness_sha': os.environ['GITHUB_SHA'], 'started_at': now(),
                       'production_connections': 0, 'existing_operational_package_changed': False}
        anonymous = self.root / 'anonymous-docker'
        anonymous.mkdir(mode=0o700)
        write(anonymous / 'config.json', {'auths': {}})
        self.dependencies = Dependencies(self.publish, anonymous_config=anonymous)
        self.capability_volume = None
        self.network_id = None

    def publish(self, name, data):
        write(self.public / name, data)

    def command(self, args, *, timeout=60, data=None):
        r = subprocess.run([str(x) for x in args], input=data, capture_output=True, timeout=timeout)
        if r.returncode:
            # Raw output stays in ephemeral private storage. No arbitrary stderr in artifacts.
            with (self.root / f'command-{len(list(self.root.glob("command-*")))}.private').open('xb') as f:
                os.chmod(f.name, 0o600)
                f.write(r.stdout + r.stderr)
            raise Failure('COMMAND_FAILED_' + Path(str(args[0])).name.upper())
        return r.stdout.decode().strip()

    def docker(self, *args, **kwargs):
        return self.command(['docker', *args], **kwargs)

    def inspect(self, cid):
        # Captured privately only; the complete env is never printed or published.
        return json.loads(self.docker('inspect', cid))[0]

    def run(self, name, args):
        refs = {IMAGE, WORKER} | {ref for _, ref in SERVICES}
        matching = [str(x) for x in args if str(x) in refs]
        require(len(matching) == 1, 'APPROVED_CONTAINER_IMAGE_REQUIRED')
        def project(raw):
            value = raw.decode().strip()
            if not re.fullmatch(r'[0-9a-f]{64}', value):
                raise ValueError('CONTAINER_ID_INVALID')
            return {'container_id': value}
        result, observation = self.dependencies.operation(name + '-create', 'CREATE', matching[0],
            ['run', '-d', '--pull=never', '--name', name, '--label', 'm55b.isolated=true', *map(str, args)],
            timeout=60, project=project)
        cid = observation['container_id']
        self.containers.append(cid)
        return cid

    def wait(self, cid, seconds=240):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            s = self.inspect(cid)['State']
            if not s['Running']:
                if s['ExitCode'] != 0 or s['OOMKilled']:
                    r = subprocess.run(['docker', 'logs', cid], capture_output=True, timeout=10)
                    markers = sorted(set(re.findall(rb'M55B_SMOKE_[A-Z_]{1,40}', r.stdout)))
                    self.publish('isolated_process_failure.json', {'phase': self.phase, 'container_id': cid,
                        'exit_code': s['ExitCode'], 'oom_killed': s['OOMKilled'],
                        'completed_smoke_phases': [x.decode() for x in markers], 'raw_logs_not_published': True})
                require(s['ExitCode'] == 0 and not s['OOMKilled'], 'ISOLATED_PROCESS_FAILED')
                return s
            time.sleep(1)  # IPC/readiness polling, never request warmup or retries.
        raise Failure('ISOLATED_PROCESS_DEADLINE')

    def common(self, *, sealed=True):
        args = ['--user', self.uid, '--env-file', self.root / 'app.env', '--env', 'JAVA_TOOL_OPTIONS=',
                '--mount', f'type=bind,src={self.root},dst=/fixture',
                '--mount', f'type=bind,src={self.root / "classes"},dst=/harness,readonly',
                '--mount', f'type=bind,src={self.root / "cacerts"},dst=/trust,readonly']
        if sealed:
            args += ['--mount', f'type=bind,src={self.root / "sealed"},dst=/sealed,readonly']
        return args

    @staticmethod
    def trust():
        return ['-Djavax.net.ssl.trustStore=/trust', '-Djavax.net.ssl.trustStorePassword=changeit']

    def envfile(self, name, values):
        with (self.root / name).open('x') as f:
            os.chmod(f.name, 0o600)
            for k, v in values.items():
                require('\n' not in v and '\r' not in v, 'ENV_VALUE_INVALID')
                f.write(k + '=' + v + '\n')

    def build(self):
        self.phase = 'BUILD_PINNED_APPLICATION'
        require(self.command(['git', '-C', self.app, 'rev-parse', 'HEAD']) == APP_SHA, 'APP_SHA_NOT_PINNED')
        require(not self.command(['git', '-C', self.app, 'status', '--porcelain']), 'APP_CHECKOUT_DIRTY')
        self.dependencies.operation('application-build', 'CREATE', IMAGE,
            ['build', '--label', 'org.opencontainers.image.revision=' + APP_SHA,
             '-t', IMAGE, str(self.app / 'backend')], timeout=1500)
        self.image_id = self.dependencies.local('application-built', IMAGE)[1]['verified_image_id']
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', self.image_id), 'IMAGE_ID_INVALID')
        # Extract, compile and run the separate fixture helper; never edit/package it into app.jar.
        result, _ = self.dependencies.operation('application-extract-create', 'CREATE', IMAGE, ['create', IMAGE])
        cid = result.stdout.decode().strip()
        require(re.fullmatch(r'[0-9a-f]{64}', cid), 'EXTRACT_CONTAINER_ID_INVALID')
        self.containers.append(cid)
        self.docker('cp', cid + ':/app/app.jar', self.root / 'app.jar')
        self.docker('cp', cid + ':/opt/java/openjdk/lib/security/cacerts', self.root / 'cacerts')
        classes = self.root / 'classes'
        classes.mkdir(mode=0o700)
        jars = self.root / 'jar'
        with zipfile.ZipFile(self.root / 'app.jar') as z:
            for n in z.namelist():
                if n.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')) and not n.endswith('/'):
                    require('..' not in Path(n).parts, 'JAR_PATH_INVALID')
                    z.extract(n, jars)
        self.command(['javac', '-cp', str(jars / 'BOOT-INF/classes') + ':' + str(jars / 'BOOT-INF/lib/*'),
                      '-d', classes, HERE / 'RehearsalSeed.java'], timeout=120)
        self.publish('image_identity.json', {'application_sha': APP_SHA, 'image_sha': self.image_id,
                                           'jar_sha256': sha(self.root / 'app.jar')})

    def dependency_preflight(self):
        self.phase = 'DOCKER_DEPENDENCY_PREFLIGHT'
        self.publish('dependency_inventory.json', {'expected_platform': 'linux/amd64', 'application': IMAGE,
            'worker_built_after_ephemeral_ca': WORKER, 'external_services': dict(SERVICES),
            'image_references_changed': True, 'changed_images_only': ['minio', 'mc'],
            'minio_acquisition': 'EXACT_OFFICIAL_GITHUB_RELEASE_BINARY_ASSEMBLY',
            'anonymous_empty_cli_config': True, 'automatic_retries': 0})
        def ready():
            self.assemble_minio()
            self.verify_minio_contracts()
            self.prepare_network()
        self.dependencies.before_fixture(ready)
        self.publish('fixture_image_digests.json', {ref: {k: v[k] for k in ['verified_image_id', 'verified_digests', 'observed_platform']}
                                                   for ref, v in self.dependencies.verified.items()})

    def assemble_minio(self):
        self.phase = 'OFFICIAL_RELEASE_ASSEMBLY'
        releases = OfficialReleases(self.root / 'official-releases', self.publish)
        base_ref = dict(SERVICES)['python']
        base = self.dependencies.verified[base_ref]
        require(len(base['verified_digests']) == 1, 'EXACT_BASE_DIGEST_REQUIRED')
        digest = base['verified_digests'][0]
        base_layers = self.inspect(base_ref)['RootFS']['Layers']
        self.release_provenance = {}
        for identity, ref in SERVICES:
            if ref not in ASSEMBLED:
                continue
            binary, provenance = releases.acquire(identity)
            context = self.root / ('assembly-' + identity)
            context.mkdir(mode=0o700)
            shutil.copyfile(binary, context / 'binary')
            recipe = (HERE / 'Dockerfile.release-binary').read_text() + '\nENTRYPOINT ["' + identity + '"]\n'
            (context / 'Dockerfile').write_text(recipe)
            iid = context / 'image-id'
            self.dependencies.operation(identity + '-assembly-build', 'CREATE', ref,
                ['build', '--platform', 'linux/amd64', '--iidfile', str(iid), '-t', ref,
                 '--build-arg', 'BASE=python@' + digest, '--build-arg', 'EXECUTABLE=' + identity,
                 '--build-arg', 'RELEASE=' + provenance['release_tag'],
                 '--build-arg', 'BINARY_SHA=' + provenance['sha256'],
                 '--build-arg', 'SOURCE_COMMIT=' + provenance['resolved_release_commit'],
                 '--build-arg', 'BASE_DIGEST=' + digest, str(context)], timeout=300, anonymous=True)
            expected_id = iid.read_text().strip()
            independent = verify_image(self.inspect(ref), identity=identity, expected_id=expected_id,
                base_digest=digest, base_layers=base_layers, binary_sha=provenance['sha256'])
            self.dependencies.local(identity + '-assembled', ref)
            independent['image_reference'] = ref
            self.publish(identity + '-assembled-image.json', independent)
            self.release_provenance[identity] = provenance

    def verify_minio_contracts(self):
        for identity, ref in SERVICES:
            if ref not in ASSEMBLED:
                continue
            contracts = ['server'] if identity == 'minio' else ['alias', 'mb', 'stat', 'ready']
            script = identity + ' --version || exit 1\nsha256sum /usr/local/bin/' + identity + '\n'
            for command in contracts:
                invocation = 'alias set' if command == 'alias' else command
                script += f'{identity} {invocation} --help >/dev/null || exit 1\nprintf "M55B_CONTRACT_{command}\\n"\n'
            cid = self.run('m55b-' + identity + '-contract', ['--network', 'none', '--memory', '128m',
                '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m', '--env', 'MC_CONFIG_DIR=/tmp/.mc',
                '--entrypoint', '/bin/sh', ref, '-c', script])
            self.wait(cid, 30)
            def verified(raw):
                result = Dependencies.release_projection(identity, raw)
                hashes = re.findall(rb'(?m)^([0-9a-f]{64})  /usr/local/bin/' + identity.encode() + rb'$', raw)
                if hashes != [self.release_provenance[identity]['sha256'].encode()]:
                    raise ValueError('IMAGE_BINARY_DIGEST_MISMATCH')
                result['in_image_binary_sha256'] = hashes[0].decode()
                return result
            self.dependencies.operation(identity + '-release-contract', 'INSPECT', ref, ['logs', cid], project=verified)

    def prepare_network(self):
        self.dependencies.operation('isolated-network-create', 'CREATE', NETWORK,
            ['network', 'create', '--internal', '--label', 'm55b.isolated=true', NETWORK])
        network = json.loads(self.docker('network', 'inspect', NETWORK))[0]
        require(network['Internal'] is True and network['Driver'] == 'bridge', 'INTERNAL_NETWORK_REQUIRED')
        require(network['Labels'].get('m55b.isolated') == 'true', 'OWNED_NETWORK_REQUIRED')
        self.network_id = network['Id']
        self.publish('isolated_network.json', {'id': network['Id'], 'internal': network['Internal'],
                                             'driver': network['Driver'], 'published_ports': 0})
        volume = NETWORK + '-capability'
        self.dependencies.operation('volume-capability-create', 'CREATE', volume,
            ['volume', 'create', '--label', 'm55b.isolated=true', volume])
        self.capability_volume = volume
        def verify_volume(raw):
            v = json.loads(raw)
            if v != {'name': volume, 'driver': 'local', 'scope': 'local', 'label': 'true'}:
                raise ValueError('VOLUME_CAPABILITY_INVALID')
            return {'volume_driver': 'local', 'volume_scope': 'local', 'owned_test_volume': True}
        self.dependencies.operation('volume-capability-inspect', 'INSPECT', volume,
            ['volume', 'inspect', '--format', '{"name":{{json .Name}},"driver":{{json .Driver}},"scope":{{json .Scope}},"label":{{json (index .Labels "m55b.isolated")}}}', volume],
            project=verify_volume)

    def services(self):
        self.phase = 'ISOLATED_SERVICES'
        images = [ref for _, ref in SERVICES]
        pw, media_pw = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        self.envfile('db.env', {'POSTGRES_USER': 'phokarta', 'POSTGRES_PASSWORD': pw, 'POSTGRES_DB': DBNAME})
        self.envfile('media.env', {'MINIO_ROOT_USER': 'isolatedtest', 'MINIO_ROOT_PASSWORD': media_pw})
        self.envfile('app.env', {
            'APP_ENVIRONMENT': 'M55B_ISOLATED', 'SPRING_PROFILES_ACTIVE': 'prod',
            'PHOKARTA_RELEASE': APP_SHA, 'PHOKARTA_DB_URL': 'jdbc:postgresql://m55b-memory-db:5432/' + DBNAME,
            'PHOKARTA_DB_USER': 'phokarta', 'PHOKARTA_DB_PASSWORD': pw, 'PHOKARTA_JWT_SECRET': secrets.token_urlsafe(64),
            'PHOKARTA_CORS_ALLOWED_ORIGINS': 'https://m55b-api:8443', 'PHOKARTA_MEDIA_BUCKET': 'm55b-isolated-media',
            'PHOKARTA_MEDIA_REGION': 'us-east-1', 'PHOKARTA_MEDIA_ENDPOINT': 'https://m55b-minio:9443',
            'PHOKARTA_MEDIA_PATH_STYLE': 'true', 'PHOKARTA_MEDIA_ACCESS_KEY': 'isolatedtest',
            'PHOKARTA_MEDIA_SECRET_KEY': media_pw, 'PHOKARTA_PLACE_DETAIL_OBSERVABILITY_ENABLED': 'true',
            'PHOKARTA_PLACE_DETAIL_SLOW_THRESHOLD_MS': '350', 'PHOKARTA_PLACE_IMPORT_ENABLED': 'false'})
        self.db = self.run('m55b-memory-db', ['--network', NETWORK, '--memory', '768m', '--env-file', self.root / 'db.env',
                          '--health-cmd', 'pg_isready -U phokarta -d ' + DBNAME, '--health-interval', '2s',
                          '--health-timeout', '5s', '--health-retries', '60', images[0]])
        self.media = self.run('m55b-minio-store', ['--network', NETWORK, '--memory', '512m',
                             '--env-file', self.root / 'media.env', images[1], 'server', '/data'])
        self.caddy = self.run('m55b-memory-caddy', ['--network', NETWORK, '--network-alias', 'm55b-api',
                             '--network-alias', 'm55b-minio', '--memory', '128m',
                             '--mount', f'type=bind,src={HERE / "Caddyfile"},dst=/etc/caddy/Caddyfile,readonly', images[3]])
        end = time.monotonic() + 120
        while time.monotonic() < end:
            if self.inspect(self.db)['State'].get('Health', {}).get('Status') == 'healthy':
                break
            time.sleep(1)
        else:
            raise Failure('DB_READINESS_FAILED')
        # CA generation is local Caddy startup; no live certificates/private keys copied.
        self.docker('cp', self.caddy + ':/data/caddy/pki/authorities/local/root.crt', self.root / 'root.crt')
        (self.root / 'root.crt').chmod(0o644)
        (self.root / 'cacerts').chmod(0o600)
        self.command(['keytool', '-importcert', '-noprompt', '-alias', 'm55b-isolated',
                      '-file', self.root / 'root.crt', '-keystore', self.root / 'cacerts', '-storepass', 'changeit'])
        context = self.root / 'worker-image'
        context.mkdir(mode=0o700)
        shutil.copyfile(HERE / 'Dockerfile.worker', context / 'Dockerfile')
        shutil.copyfile(self.root / 'root.crt', context / 'root.crt')
        self.dependencies.operation('b1-worker-build', 'CREATE', WORKER,
                                    ['build', '-t', WORKER, str(context)], timeout=300)
        self.dependencies.local('b1-worker-built', WORKER)
        self.envfile('mc.env', {'MC_USER': 'isolatedtest', 'MC_PASSWORD': media_pw})
        self.phase = 'S3_DEPENDENCY_SMOKE'
        smoke = self.root / 's3-smoke'
        smoke.mkdir(mode=0o700)
        (smoke / 'input.bin').write_bytes(b'M55B_ISOLATED_STORAGE_SMOKE\x00' * 8)
        mc = self.run('m55b-isolated-mc', ['--network', NETWORK, '--env-file', self.root / 'mc.env',
            '--mount', f'type=bind,src={self.root / "root.crt"},dst=/root/.mc/certs/CAs/m55b.crt,readonly',
            '--mount', f'type=bind,src={smoke},dst=/smoke',
            '--entrypoint', '/bin/sh', images[2], '-c',
            'set -e\nmc alias set fixture https://m55b-minio:9443 "$MC_USER" "$MC_PASSWORD" >/dev/null\n'
            'mc ready fixture >/dev/null\nprintf "M55B_SMOKE_READY\\n"\n'
            'mc mb fixture/m55b-isolated-media >/dev/null\n'
            'mc stat --json fixture/m55b-isolated-media > /smoke/bucket.json\nprintf "M55B_SMOKE_BUCKET\\n"\n'
            'mc cp /smoke/input.bin fixture/m55b-isolated-media/m55b-dependency-smoke.bin >/dev/null\nprintf "M55B_SMOKE_UPLOAD\\n"\n'
            'mc cp fixture/m55b-isolated-media/m55b-dependency-smoke.bin /smoke/readback.bin >/dev/null\nprintf "M55B_SMOKE_READBACK\\n"\n'
            'cmp /smoke/input.bin /smoke/readback.bin\n'
            'sha256sum /smoke/input.bin > /smoke/input.sha256\nsha256sum /smoke/readback.bin > /smoke/readback.sha256\n'
            'read input_hash rest < /smoke/input.sha256\nread readback_hash rest < /smoke/readback.sha256\n'
            'test "$input_hash" = "$readback_hash"\nprintf "M55B_SMOKE_VERIFY\\n"\n'
            'mc rm fixture/m55b-isolated-media/m55b-dependency-smoke.bin >/dev/null\nprintf "M55B_SMOKE_REMOVE\\n"\n'
            'mc ls --json fixture/m55b-isolated-media > /smoke/remaining.json'])
        self.wait(mc, 30)
        self.publish('s3_smoke.json', verify_smoke(smoke))
        for cid in [self.db, self.media, self.caddy]:
            x = self.inspect(cid)
            require(x['State']['Running'] and not x['State']['OOMKilled'] and x['RestartCount'] == 0, 'INITIAL_SERVICE_HEALTH_FAILURE')
            require(not any((x['NetworkSettings']['Ports'] or {}).values()), 'INITIAL_PORT_EXPOSURE')
        self.publish('isolated_services.json', {'status': 'PASS', 'postgres_health': 'healthy',
            'minio_ready': True, 'bucket_created': True, 'bucket_stat_readback': True,
            'bucket': 'm55b-isolated-media', 'client_image': images[2], 'caddy_running': True,
            'internal_network': True, 'published_ports': 0})

    def helper(self, mode):
        manual = mode == 'manual'
        cid = self.run('m55b-helper-' + mode, ['--network', NETWORK, '--memory', '3072m', '--memory-swap', '5376m',
                       '--cpus', '1.5', *self.common(sealed=not manual), '--env', 'REHEARSAL_MODE=' + mode,
                       '--env', 'REHEARSAL_OUTPUT=/fixture/state-' + mode + '.json',
                       '--env', 'REHEARSAL_A_HASH=' + ('' if manual else self.config['predecessor_manifest_hash']),
                       '--env', 'REHEARSAL_A_AUTH=' + ('' if manual else self.config['predecessor_authorization']),
                       '--entrypoint', 'java', IMAGE, '-Xms256m', '-Xmx2048m', '-XX:+ExitOnOutOfMemoryError',
                       *self.trust(), '-Dloader.path=/harness', '-Dloader.main=RehearsalSeed', '-cp', '/app/app.jar',
                       'org.springframework.boot.loader.launch.PropertiesLauncher'])
        self.wait(cid, 600)
        return json.loads((self.root / ('state-' + mode + '.json')).read_bytes())

    def sql(self, query):
        out = self.command(['docker', 'exec', '-i', self.db, 'sh', '-c',
            'exec timeout 30s env LC_ALL=C PGOPTIONS="-c statement_timeout=5000 -c default_transaction_read_only=on" '
            'psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
            data=('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + query + ';\nCOMMIT;\n').encode(), timeout=30)
        return json.loads(out)

    def historical_preflight(self, downstream):
        self.phase = 'HISTORICAL_DATABASE_AUDIT'
        ops = self.app / 'tools/place-ingestion/operations'
        spec = importlib.util.spec_from_file_location('approved_audit', ops / 'historical_database_audit.py')
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        require(len(audit.expressions()) == 38, 'EXACT_38_REQUIRED')
        # Independent oracle ONLY before mutation, never a fallback preflight.
        reference_start = time.monotonic()
        reference = self.sql((ops / 'historical_database_audit.sql').read_text().rstrip().rstrip(';'))
        reference_elapsed = (time.monotonic() - reference_start) * 1000
        write(self.root / 'original_reference.json', reference)
        reference_sha = sha(self.root / 'original_reference.json')
        def verified(evidence):
            audit.publish(self.root / 'integrated_audit.json', evidence)
            readback = audit.strict_json((self.root / 'integrated_audit.json').read_bytes())
            require(readback == evidence and audit.canonical(readback['audit']) == audit.canonical(reference), 'AUDIT_READBACK_MISMATCH')
            require(sha(self.root / 'original_reference.json') == reference_sha, 'REFERENCE_CHANGED')
            d = readback['diagnostics']
            require(d['status'] == 'PASS' and d['completed_expressions'] == 38, 'INCOMPLETE_AUDIT')
            self.publish('historical_audit.json', {'diagnostics': d, 'independent_readback': True,
                         'original_decomposed_equality': True, 'reference_sha256': reference_sha,
                         'original_reference_orchestration_ms': reference_elapsed,
                         'artifact_sha256': sha(self.root / 'integrated_audit.json'),
                         'producer_sha256': sha(ops / 'historical_database_audit.py'),
                         'fallback_used': False, 'synthetic_history_not_production': True})
            return downstream()
        try:
            return audit.preflight(audit.docker_psql_command(self.db), reference, verified)
        except audit.AuditFailure as e:
            self.publish('historical_audit_failure.json', e.diagnostics)
            raise Failure('HISTORICAL_AUDIT_' + e.code) from None

    def persistent(self):
        self.phase = 'PERSISTENT_TARGET_AND_TLS'
        diagnostic = {'version': 'm55b-persistent-startup-v1', 'status': 'RUNNING',
                      'phase': 'BACKEND_STARTUP', 'started_at': now(), 'production_connections': 0}
        started = time.monotonic()
        try:
            self.target = self.run('phokarta-backend-1', ['--network', NETWORK, '--memory', '1536m', '--cpus', '1.5',
                *self.common(sealed=False), '--health-cmd', 'wget -q -T 5 -O /dev/null http://127.0.0.1:8081/actuator/health/readiness',
                '--health-interval', '2s', '--health-timeout', '5s', '--health-retries', '60',
                '--entrypoint', 'java', IMAGE, '-Xms256m', '-Xmx1024m', '-XX:+ExitOnOutOfMemoryError', *self.trust(), '-jar', '/app/app.jar'])
            diagnostic.update(phase='CONTAINER_IDENTITY', container_id=self.target)
            identity = self.inspect(self.target)
            diagnostic.update(image_id=identity['Image'], expected_image_id=self.image_id,
                              restart_count=identity['RestartCount'])
            require(identity['Image'] == self.image_id and identity['RestartCount'] == 0, 'PERSISTENT_IDENTITY_FAILED')
            diagnostic['phase'] = 'BACKEND_READINESS'
            end = time.monotonic() + 180
            while time.monotonic() < end:
                observed = self.inspect(self.target)
                s = observed['State']
                health = s.get('Health', {}).get('Status', 'none')
                require(health in {'healthy', 'unhealthy', 'starting', 'none'}, 'PERSISTENT_HEALTH_STATE_INVALID')
                diagnostic.update(health=health, running=s['Running'], oom_killed=s['OOMKilled'],
                                  restart_count=observed['RestartCount'], process_pid=s['Pid'],
                                  container_started_at=s['StartedAt'])
                require(s['Running'] and not s['OOMKilled'] and observed['RestartCount'] == 0, 'PERSISTENT_TARGET_FAILED')
                if health == 'healthy':
                    break
                time.sleep(2)
            else:
                raise Failure('PERSISTENT_READINESS_DEADLINE')
            diagnostic.update(phase='TLS_WORKER', readiness_elapsed_ms=(time.monotonic() - started) * 1000)
            tls = self.run('m55b-tls-verification', self.worker_args('tls'))
            wait_error = None
            try:
                self.wait(tls, 30)
            except Failure as error:
                wait_error = error
            # Read the strict safe stdout contract BEFORE propagating exit failure.
            # This also works when the worker cannot write its mounted artifact.
            evidence = self.target_worker_evidence(tls)
            if wait_error is not None:
                if evidence and evidence['status'] == 'FAIL':
                    failed = evidence['events'][-1]
                    raise Failure('PERSISTENT_' + failed['phase'] + '_' + failed['failure_category']) from None
                raise wait_error
            require(evidence is not None and evidence['status'] == 'PASS', 'TLS_DIAGNOSTICS_INCOMPLETE')
            verified = json.loads((self.root / 'tls-output/tls.json').read_bytes())
            require(verified.get('status') == 'PASS' and verified.get('chain_verified') is True
                    and verified.get('hostname_verified') is True and verified.get('hostname_negative_control') is True
                    and verified.get('negative_control_sni_preserved') is True
                    and verified.get('negative_control_verify_code') == 62
                    and len(verified.get('requests', [])) == 3
                    and all(r['status'] == 200 and r['validation'] == 'VALID' for r in verified['requests']),
                    'TLS_ARTIFACT_INCOMPLETE')
            self.publish('tls.json', verified)
            diagnostic['status'] = 'PASS'
        finally:
            if diagnostic['status'] != 'PASS':
                diagnostic['status'] = 'FAIL'
            diagnostic.update(completed_at=now(), elapsed_ms=(time.monotonic() - started) * 1000)
            self.publish('persistent_target.json', diagnostic)

    def target_worker_evidence(self, cid):
        try:
            r = subprocess.run(['docker', 'logs', cid], capture_output=True, timeout=10)
            require(r.returncode == 0, 'TLS_DIAGNOSTIC_LOG_READ_FAILED')
            evidence = from_logs(r.stdout)  # stderr and arbitrary lines NEVER published.
        except Exception:
            self.publish('tls_diagnostic_gap.json', {'status': 'UNAVAILABLE_OR_INVALID',
                'raw_logs_not_published': True, 'worker_container_id': cid})
            return None
        self.publish('tls_worker_diagnostics.json', evidence)
        return evidence

    def worker_args(self, mode):
        args = ['--network', NETWORK, '--memory', '1536m', '--memory-swap', '1536m',
                '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--user', self.uid,
                '--mount', f'type=bind,src={HERE},dst=/tools,readonly',
                '--mount', f'type=bind,src={self.app},dst=/application,readonly']
        worker_root = self.root
        if mode == 'tls':
            worker_root = self.root / 'tls-output'
            worker_root.mkdir(mode=0o700)
            args += ['--mount', f'type=bind,src={worker_root},dst={worker_root}']
        else:
            # NO env files, datasource credentials, CA private keys or Docker socket.
            args += ['--mount', f'type=bind,src={self.evidence},dst={self.evidence}',
                     '--mount', f'type=bind,src={self.root / "sealed"},dst={self.root / "sealed"},readonly',
                     '--mount', f'type=bind,src={self.root / "fixture.json"},dst={self.root / "fixture.json"},readonly']
        return args + ['m55b-isolated-b1:validation', '/tools/worker.py', mode,
                       '--application', '/application', '--root', worker_root]

    def launch_coordinator(self):
        f = (self.root / 'coordinator.private').open('xb')
        os.chmod(f.name, 0o600)
        self.logs.append(f)
        args = [sys.executable, self.app / 'tools/place-ingestion/operations/persistent_telemetry_worker.py',
                '--directory', self.evidence, '--plan-sha256', sha(self.evidence / 'V3_OPERATIONS_PLAN.json'),
                '--probe-image-sha256', self.image_id, '--db-container-id', self.db, '--caddy-container-id', self.caddy]
        p = subprocess.Popen([str(x) for x in args], stdin=subprocess.DEVNULL, stdout=f, stderr=f)
        self.children.append(p)

    def await_files(self, names):
        end = time.monotonic() + 180
        while time.monotonic() < end:
            require(not any(p.poll() is not None for p in self.children), 'COORDINATOR_FAILED')
            require(not any((self.evidence / p).exists() for p in ['WORKER_FAILED.json', 'PRODUCT_FAILED.json']), 'REAL_WORKER_FAILED')
            if all((self.evidence / n).exists() for n in names):
                return
            time.sleep(.2)
        raise Failure('WORKER_READINESS_DEADLINE')

    def metrics(self, cid):
        script = ('for f in memory.current memory.peak memory.max memory.events memory.swap.current memory.swap.peak '
                  'memory.swap.max memory.pressure cpu.stat; do if [ -r /sys/fs/cgroup/$f ]; then echo FIELD:$f; '
                  'cat /sys/fs/cgroup/$f; fi; done')
        r = subprocess.run(['docker', 'exec', cid, '/bin/sh', '-c', script], capture_output=True, timeout=10)
        if r.returncode:
            return None
        values, key = {}, None
        scalar = {'memory.current', 'memory.peak', 'memory.max', 'memory.swap.current', 'memory.swap.peak', 'memory.swap.max'}
        for line in r.stdout.decode().splitlines():
            if line.startswith('FIELD:'):
                key = line[6:]
                values[key] = {}
            elif key in scalar:
                values[key] = int(line) if line != 'max' else line
            elif key == 'memory.pressure':
                values[key][line.split()[0]] = {k: float(v) for k, v in (p.split('=') for p in line.split()[1:])}
            else:
                k, v = line.split()
                values[key][k] = int(v)
        r = subprocess.run(['docker', 'exec', cid, 'wget', '-q', '-T', '1', '-O', '-',
                            'http://127.0.0.1:10081/actuator/prometheus'], capture_output=True, timeout=4)
        heap = {}
        if r.returncode == 0:
            lines = r.stdout.decode().splitlines()
            for metric in ['used', 'committed', 'max']:
                heap[metric] = sum(float(s.rsplit(' ', 1)[1]) for s in lines
                                   if s.startswith('jvm_memory_' + metric + '_bytes{') and 'area="heap"' in s)
        mem = {k: int(v.split()[0]) * 1024 for k, v in
               (s.split(':', 1) for s in Path('/proc/meminfo').read_text().splitlines())
               if k in {'MemAvailable', 'SwapFree', 'SwapTotal'}}
        return {'at': now(), 'cgroup': values, 'heap': heap, 'host': mem,
                'disk_free': shutil.disk_usage(self.root).free}

    def operational(self):
        self.phase = 'FRESH_PRE_AND_WORKERS'
        sys.path.insert(0, str(self.app / 'tools/place-ingestion/operations'))
        import product_evidence_worker as product
        plan = {'version': 'v3-private-operations-plan-v2', 'validation_method': 'didim-autonomous-validation-v3',
                'run_id': B, 'manifest_hash': self.config['manifest_hash'], 'authorization_reference': self.config['authorization'],
                'selected_canonical_ids': self.config['selected_canonical_ids'], 'product_checks': sorted(product.CHECKS),
                'target_policy': {'source_sha': APP_SHA, 'image_sha': self.image_id, 'image_ref': IMAGE,
                   'origin': 'http://127.0.0.1:8080', 'management_origin': 'http://127.0.0.1:8081',
                   'route_id': 'PRIVATE_PERSISTENT_CONTAINER_NETNS', 'sentinel': 'aa000000-0000-4000-8000-000000000001',
                   'samples': 20, 'preconditioning': 1, 'deadline_ms': 5000, 'detail_slow_threshold_ms': 350, 'roles': ['PRE', 'POST']}}
        write(self.evidence / 'V3_OPERATIONS_PLAN.json', plan)
        self.plan = plan  # Needed for independent failure readback too, never a PASS receipt.
        self.launch_coordinator()
        self.await_files(['EXECUTION_TARGET_RECEIPT.json', 'CURRENT_TARGET.json'])
        self.product_cid = self.run('m55b-real-product-worker', self.worker_args('product'))
        self.await_files(['PRE_RECEIPT.json', 'PRODUCT_WORKER_READY.json'])
        c = gate(self.root, operational=True)
        self.publish('capacity_pre_operational.json', c)
        require(c['status'] == 'PASS', 'PRE_OPERATIONAL_CAPACITY_BLOCKED')
        self.phase = 'REAL_V3_OPERATIONAL_RUNNER'
        cid = self.run('m55b-operational', ['--network', 'container:' + self.target, '--memory', '3072m',
            '--memory-swap', '5376m', '--cpus', '1.5', *self.common(),
            '--env', 'SPRING_PROFILES_ACTIVE=prod,place-import', '--env', 'PHOKARTA_PLACE_IMPORT_ENABLED=true',
            '--env', 'SERVER_PORT=10080', '--env', 'MANAGEMENT_SERVER_PORT=10081',
            '--env', 'PHOKARTA_PLACE_IMPORT_MANIFEST_PATH=/sealed/stage_1_import_manifest.json',
            '--env', 'PHOKARTA_PLACE_IMPORT_EXPECTED_MANIFEST_HASH=' + self.config['manifest_hash'],
            '--env', 'PHOKARTA_PLACE_IMPORT_AUTHORIZATION_REFERENCE=' + self.config['authorization'],
            '--env', 'PHOKARTA_PLACE_IMPORT_BASE_URL=http://127.0.0.1:10080',
            '--env', 'PHOKARTA_PLACE_IMPORT_BASELINE_PLACE_ID=aa000000-0000-4000-8000-000000000001',
            '--env', 'PHOKARTA_PLACE_IMPORT_DIAGNOSTICS_DIRECTORY=/fixture/evidence',
            '--env', 'PHOKARTA_PLACE_IMPORT_V3_EVIDENCE_DIRECTORY=/fixture/evidence',
            '--env', 'PHOKARTA_PLACE_IMPORT_V3_OPERATIONS_PLAN_SHA256=' + sha(self.evidence / 'V3_OPERATIONS_PLAN.json'),
            '--entrypoint', 'java', IMAGE, '-Xms256m', '-Xmx2048m', '-XX:+ExitOnOutOfMemoryError',
            '-Dfile.encoding=UTF-8', '-Duser.timezone=UTC', *self.trust(), '-jar', '/app/app.jar'])
        self.operational_cid = cid
        config = self.inspect(cid)['HostConfig']
        require(config['Memory'] == 3072 * 1024 ** 2 and config['MemorySwap'] == 5376 * 1024 ** 2
                and config['NanoCpus'] == 1500000000, 'RESOURCE_LIMITS_CHANGED')
        self.publish('operational_limits.json', {k: config[k] for k in ['Memory', 'MemorySwap', 'MemorySwappiness', 'NanoCpus']})
        samples = []
        end = time.monotonic() + 900
        while time.monotonic() < end:
            state = self.inspect(cid)['State']
            if not state['Running']:
                break
            sample = self.metrics(cid)
            if sample:
                samples.append(sample)
                require(sample['cgroup'].get('memory.max') == 3072 * 1024 ** 2
                        and sample['cgroup'].get('memory.swap.max') == 2304 * 1024 ** 2, 'CGROUP_SWAP_SEMANTICS_INVALID')
            time.sleep(1)
        else:
            raise Failure('OPERATIONAL_REHEARSAL_DEADLINE')
        self.publish('resource_samples.json', samples)
        self.publish('operational_exit.json', {k: state[k] for k in ['ExitCode', 'OOMKilled', 'StartedAt', 'FinishedAt']})
        self.capture_runner_postmortem(state)  # Runner already exited; its containment has returned.
        require(state['ExitCode'] == 0 and not state['OOMKilled'], 'OPERATIONAL_RUNNER_FAILED')
        require(samples and not any(s['cgroup'].get('memory.events', {}).get('oom_kill', 0) for s in samples), 'OOM_EVIDENCE')
        for p in self.children:
            p.wait(timeout=15)
            require(p.returncode == 0, 'COORDINATOR_TERMINAL_FAILURE')
        self.wait(self.product_cid, 15)
        self.samples = samples
        self.plan = plan

    def capture_runner_postmortem(self, state=None):
        if getattr(self, 'postmortem_attempted', False) or not hasattr(self, 'operational_cid'):
            return
        self.postmortem_attempted = True
        try:
            def inspect(cid, timeout):
                # Full inspect stays in memory; collector projects only explicit safe state.
                return json.loads(self.command(['docker', 'inspect', cid], timeout=timeout))[0]
            if state is None:
                state = inspect(self.operational_cid, 5)['State']
            if state['Running']:
                self.publish('runner_postmortem.json', {'collection_status': 'NOT_VERIFIED',
                    'reason': 'RUNNER_STILL_RUNNING', 'runner_result_authoritative': True})
                return  # Never interrupt the runner or replace its containment mechanism.
            # docker logs may emit the uncaught Java exception on stderr too. Keep both
            # privately in memory; only source-allowlisted codes/methods can leave it.
            def combined_logs():
                r = subprocess.run(['docker', 'logs', '--tail', '600', self.operational_cid],
                                   capture_output=True, timeout=10)
                require(r.returncode == 0, 'POSTMORTEM_LOG_UNAVAILABLE')
                return r.stdout + r.stderr
            evidence = collect_postmortem(self, state, combined_logs, inspect)
            self.publish('runner_postmortem.json', evidence)
        except Exception:
            # Best effort only: diagnostic problems must never replace the operational error.
            self.result['postmortem_status'] = 'UNAVAILABLE'

    def extra(self):
        return self.sql("SELECT jsonb_build_object('physical_inserts',(SELECT n_tup_ins FROM pg_stat_all_tables WHERE relname='places'),"
            "'stats_reset',(SELECT stats_reset::text FROM pg_stat_database WHERE datname=current_database()),"
            "'place_relid',(SELECT relid::bigint FROM pg_stat_all_tables WHERE relname='places'),"
            "'readoption_writes',(SELECT count(*) FROM place_pilot_catalog_writes WHERE sync_run_id='" + B + "' AND supersedes_write_id IS NOT NULL),"
            "'users',(SELECT count(*) FROM users),'saved',(SELECT count(*) FROM saved_places),'experiences',(SELECT count(*) FROM visits),"
            "'gate',(SELECT jsonb_build_object('status',gate_status,'diagnostics',diagnostics) FROM place_pilot_canary_gates WHERE sync_run_id='" + B + "'))")

    def validate(self):
        self.phase = 'FINAL_INDEPENDENT_READBACK'
        after = self.helper('audit')
        extra = self.extra()
        preserved = {k: self.before[k] == after[k] for k in ['places', 'sources', 'refs', 'quarantine',
                     'canonical_uuid_digest', 'historical_a', 'manual_fingerprint', 'existing_user_fingerprint', 'existing_saved_fingerprint']}
        require(all(preserved.values()), 'HISTORICAL_OR_IDENTITY_MUTATION')
        require(after['active_imported'] == 71 and after['active_refs'] == 142 and after['retired'] == 0
                and after['b_writes'] == 71 and after['b_sources'] == 0 and after['b_gate'] == 'PASSED', 'EXPECTED_RE_ADOPTION_FAILED')
        require(extra['physical_inserts'] == self.extra_before['physical_inserts'] and extra['readoption_writes'] == 71, 'PHYSICAL_PLACE_INSERT_OR_LINEAGE_FAILURE')
        require(all(extra[k] == self.extra_before[k] for k in ['users', 'saved', 'experiences']), 'SYNTHETIC_GRAPH_CLEANUP_FAILED')
        diagnostics = extra['gate']['diagnostics']
        require(extra['gate']['status'] == 'PASSED', 'TERMINAL_GATE_NOT_PASSED')
        coverage = diagnostics['selected_place_http_coverage']
        require(len(coverage) == 4 and all(len(v) == len(set(v)) == 71 and sorted(v) == self.plan['selected_canonical_ids']
                                        for v in coverage.values()), 'EXACT_FOUR_SURFACE_HTTP_COVERAGE_FAILED')
        product = json.loads((self.evidence / 'PRODUCT.json').read_bytes())
        receipt = json.loads((self.evidence / 'PRODUCT_RECEIPT.json').read_bytes())
        require(receipt['artifact_sha256'] == sha(self.evidence / 'PRODUCT.json')
                and receipt['artifact_bytes'] == (self.evidence / 'PRODUCT.json').stat().st_size, 'PRODUCT_READBACK_FAILED')
        from product_evidence_worker import validate_evidence
        validate_evidence(product, self.plan)
        for role in ['PRE', 'POST']:
            data = json.loads((self.evidence / (role + '.json')).read_bytes())
            receipt = json.loads((self.evidence / (role + '_RECEIPT.json')).read_bytes())
            require(receipt['artifact_sha256'] == sha(self.evidence / (role + '.json')) and len(data['requests']) == 84
                    and data['outcome'] == 'COMPLETE', 'TELEMETRY_READBACK_FAILED')
            self.publish(role + '.json', data)  # GET-only producer's sanitized diagnostics, never response bodies.
        self.publish('product_checks.json', product)  # Approved schema contains only IDs/status/latency/request IDs.
        self.publish('final_fixture_state.json', {'before': self.before, 'after': after, 'preserved': preserved,
                    'physical_place_insert_delta': extra['physical_inserts'] - self.extra_before['physical_inserts'],
                    'readoption_lineage_count': extra['readoption_writes'],
                    'graph_counts_before': {k: self.extra_before[k] for k in ['users', 'saved', 'experiences']},
                    'graph_counts_after': {k: extra[k] for k in ['users', 'saved', 'experiences']}})
        allowed = ['persistent_performance', 'selected_place_http_coverage', 'preimport_baseline_artifact',
                   'source_accounting', 'same_manifest_idempotency', 'search_correctness', 'turkish_search_correctness',
                   'map_correctness', 'place_detail_correctness', 'provenance_linkage', 'quarantine_imported',
                   'hard_blockers_imported', 'product_acceptance', 'selected_place_probe_coverage']
        self.publish('terminal_gate.json', {'status': 'PASSED', 'diagnostics': {k: diagnostics[k] for k in allowed if k in diagnostics}})
        for cid in [self.target, self.db, self.caddy]:
            x = self.inspect(cid)
            require(x['State']['Running'] and not x['State']['OOMKilled'] and x['RestartCount'] == 0, 'FINAL_SERVICE_HEALTH_FAILURE')
            require(not x['NetworkSettings']['Ports'] or not any(x['NetworkSettings']['Ports'].values()), 'PORT_EXPOSURE')
            if cid != self.caddy:
                require(x['State']['Health']['Status'] == 'healthy', 'FINAL_HEALTH_FAILURE')
        self.result.update(assessment='FULL_V3_REHEARSAL_PASS', completed_at=now(), gate='PASSED',
                          re_adoptions=71, physical_place_inserts=0, active_refs=142, new_source_observations=0,
                          canonical_uuid_changes=0, quarantine_promotions=0, product_checks=14,
                          cleanup='COMPLETED', preserved=preserved, fixture_manifest_sha256=self.config['envelope_sha256'])

    def execute(self):
        c = gate(self.root)
        self.publish('capacity_harness.json', c)
        require(c['status'] == 'PASS', 'INITIAL_CAPACITY_BLOCKED')
        self.phase = 'DOCKER_ENGINE_PREFLIGHT'
        self.dependencies.engine()
        self.build()
        self.dependency_preflight()
        self.services()
        self.phase = 'REAL_V17_AND_MANUAL_SENTINELS'
        manual = self.helper('manual')
        require(all(manual[k] == v for k, v in {'places': 2, 'manual': 2, 'sources': 0,
                'refs': 0, 'v17': 1, 'b_writes': 0, 'b_sources': 0}.items()), 'MANUAL_V17_BOOTSTRAP_INVALID')
        self.publish('manual_v17_bootstrap.json', manual)
        # Same long-lived target for TLS, later PRE and POST. No Detail warmup.
        self.persistent()
        c = gate(self.root, operational=True)
        self.publish('capacity_pre_fixture.json', c)
        require(c['status'] == 'PASS', 'PRE_FIXTURE_CAPACITY_BLOCKED')
        self.phase = 'DETERMINISTIC_FULL_VOLUME_FIXTURE'
        self.command([sys.executable, HERE / 'fixture.py', '--application', self.app, '--output', self.root / 'sealed'], timeout=180)
        self.config = json.loads((self.root / 'fixture.json').read_bytes())
        self.publish('fixture.json', self.config)
        self.phase = 'REAL_V17_AND_PREDECESSOR_LINEAGE'
        self.before = self.helper('seed-import')
        from fixture import validate_category_regression
        validate_category_regression(self.before.get('category_policy_regression'))
        self.publish('category_policy_regression.json', self.before['category_policy_regression'])
        require(all(self.before[k] == v for k, v in {'places': 73, 'manual': 2, 'retired': 71, 'sources': 18924,
               'refs': 142, 'inactive_refs': 142, 'quarantine': 16064, 'b_writes': 0, 'b_sources': 0, 'b_decisions': 0, 'v17': 1}.items()), 'FULL_VOLUME_SEED_INVALID')
        require(self.before['canonical_uuid_digest'] == IDENTITY_DIGEST and self.before['b_gate'] == 'NONE', 'FROZEN_IDENTITY_FAILED')
        require(self.sql("SELECT jsonb_build_object('b_runs',(SELECT count(*) FROM place_provider_sync_runs WHERE id='" + B + "'))")['b_runs'] == 0, 'INITIAL_B_RUN_EXISTS')
        self.publish('seed_state.json', self.before)
        self.extra_before = self.extra()
        def continuation():
            self.operational()
            self.validate()
        self.historical_preflight(continuation)

    def cleanup(self):
        for p in self.children:
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    p.kill()
                    p.wait()
        for f in self.logs:
            f.close()
        # Stop/remove ONLY exact IDs created by this harness on the ephemeral CI runner.
        for cid in reversed(self.containers):
            subprocess.run(['docker', 'rm', '-f', '-v', cid], capture_output=True, timeout=35)
        if self.network_id:
            subprocess.run(['docker', 'network', 'rm', self.network_id], capture_output=True, timeout=10)
        if self.capability_volume:
            subprocess.run(['docker', 'volume', 'rm', self.capability_volume], capture_output=True, timeout=10)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--application', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    trial = Rehearsal(args.application, args.output)
    try:
        trial.execute()
    except Exception as e:
        code = str(e) if isinstance(e, (Failure, DependencyFailure, ReleaseFailure)) else 'HARNESS_' + type(e).__name__.upper()
        trial.result.update(assessment='FAILED' if hasattr(trial, 'operational_cid') else 'BLOCKED',
                            failure_phase=trial.phase, failure_category=code, completed_at=now())
        if isinstance(e, DependencyFailure):
            trial.result['dependency_failure'] = e.observation
        trial.capture_runner_postmortem()
    finally:
        trial.publish('result.json', trial.result)
        trial.cleanup()
    print(json.dumps(trial.result))
    return 0 if trial.result['assessment'] == 'FULL_V3_REHEARSAL_PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())

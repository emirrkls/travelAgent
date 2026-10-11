"""Exact official GitHub release acquisition. No registry, mirrors or credentials.

Minisign ED format: Ed25519(Blake2b-512(file)), plus signature of the
64-byte detached signature concatenated with the trusted comment. Verification
uses the existing OpenSSL implementation, not a new cryptographic primitive.
"""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

SPECS = {
    'minio': {'repository': 'minio/minio', 'tag': 'RELEASE.2025-07-23T15-54-02Z',
              'tag_object': 'ee211f1b5dbd8f4c4d4e3b8be1d4aa37eef9977e',
              'commit': '7ced9663e6a791fef9dc6be798ff24cda9c730ac',
              'sha256': 'eef6581f6509f43ece007a6f2eb4c5e3ce41498c8956e919a7ac7b4b170fa431'},
    'mc': {'repository': 'minio/mc', 'tag': 'RELEASE.2025-07-21T05-28-08Z',
           'tag_object': '82c5d2530428bea3897490e7030004ee3b011e3a',
           'commit': 'ee72571936f15b0e65dc8b4a231a4dd445e5ccb6',
           'sha256': 'ea4a453be116071ab1ccbd24eb8755bf0579649f41a7b94ab9e68571bb9f4a1e'},
}
# Independently selected from the exact signed upstream source, NOT the asset or
# its checksum/signature response. HTTPS/GitHub remain trust anchors; no claim of
# an owner-supplied, out-of-band key or historical Docker byte equivalence.
PUBLIC_KEY = 'RWTx5Zr1tiHQLwG9keckT0c45M3AGeHD6IvimQHpyRywVWGbP1aVSGav'
KEY_SOURCE = 'https://raw.githubusercontent.com/minio/minio/' + SPECS['minio']['commit'] + '/Dockerfile.release'
KEY_SOURCE_SHA256 = '749dd8f1b5eaa04408999c3d0c2fa148b70de5b33239b604c4a9e9cd86f2cdee'
HOSTS = {'api.github.com', 'github.com', 'release-assets.githubusercontent.com',
         'objects.githubusercontent.com', 'raw.githubusercontent.com'}


class ReleaseFailure(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__('RELEASE_' + code)


def require(ok, code):
    if not ok:
        raise ReleaseFailure(code)


def safe_url(url):
    u = urllib.parse.urlsplit(url)
    require(u.scheme == 'https' and u.hostname in HOSTS and not u.username and not u.password
            and u.port in {None, 443}, 'DOWNLOAD_DESTINATION_REFUSED')


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    max_redirections = 3

    def redirect_request(self, request, fp, code, msg, headers, newurl):
        safe_url(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


class OfficialReleases:
    def __init__(self, root, publish, *, opener=None):
        self.root, self.publish = Path(root), publish
        self.root.mkdir(mode=0o700)
        self.opener = opener or urllib.request.build_opener(SafeRedirect())
        self.sequence = 0

    def download(self, url, name, *, maximum=2_000_000, deadline=30):
        safe_url(url)
        require(re.fullmatch(r'[A-Za-z0-9_.-]{1,160}', name), 'ASSET_NAME_INVALID')
        self.sequence += 1
        o = {'operation': 'HTTPS_DOWNLOAD', 'asset': name, 'status': 'FAIL', 'http_status': None,
             'started_at': datetime.now(timezone.utc).isoformat(), 'bytes': 0,
             'deadline_seconds': deadline, 'error_category': 'DOWNLOAD_FAILED'}
        start = time.monotonic()
        destination = self.root / name
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'Phokarta-Release-Provenance',
                'Accept': 'application/vnd.github+json' if urllib.parse.urlsplit(url).hostname == 'api.github.com'
                          else 'application/octet-stream'})
            with self.opener.open(request, timeout=min(10, deadline)) as response:
                o['http_status'] = response.status
                require(response.status == 200, 'DOWNLOAD_HTTP_STATUS')
                digest = hashlib.sha256()
                with destination.open('xb') as f:
                    os.chmod(destination, 0o600)
                    while True:
                        require(time.monotonic() - start < deadline, 'DOWNLOAD_DEADLINE')
                        chunk = response.read(min(1024 * 1024, maximum - o['bytes'] + 1))
                        if not chunk:
                            break
                        o['bytes'] += len(chunk)
                        require(o['bytes'] <= maximum, 'DOWNLOAD_SIZE_LIMIT')
                        f.write(chunk)
                        digest.update(chunk)
            require(o['bytes'] > 0, 'DOWNLOAD_EMPTY')
            o.update(status='PASS', sha256=digest.hexdigest(), error_category=None)
            return destination, o
        except urllib.error.HTTPError as e:
            o.update(http_status=e.code, error_category='HTTP_' + str(e.code))
            raise ReleaseFailure('ASSET_DOWNLOAD_FAILED') from None
        except ReleaseFailure as e:
            o['error_category'] = e.code
            raise
        except (OSError, urllib.error.URLError, TimeoutError):
            raise ReleaseFailure('ASSET_DOWNLOAD_FAILED') from None
        finally:
            o['elapsed_ms'] = (time.monotonic() - start) * 1000
            self.publish(f'release-download-{self.sequence:03d}.json', o)

    def api(self, repository, path, name):
        p, _ = self.download('https://api.github.com/repos/' + repository + path, name)
        try:
            return json.loads(p.read_bytes())
        except (ValueError, TypeError):
            raise ReleaseFailure('METADATA_MALFORMED') from None

    def acquire(self, identity):
        require(identity in SPECS, 'UNAPPROVED_IDENTITY')
        s = SPECS[identity]
        release = self.api(s['repository'], '/releases/tags/' + s['tag'], identity + '-release.json')
        ref = self.api(s['repository'], '/git/ref/tags/' + s['tag'], identity + '-ref.json')
        require(release.get('tag_name') == s['tag'] and release.get('draft') is False,
                'RELEASE_IDENTITY_MISMATCH')
        require(ref.get('object', {}).get('sha') == s['tag_object'] and ref['object']['type'] == 'tag',
                'TAG_IDENTITY_MISMATCH')
        tag = self.api(s['repository'], '/git/tags/' + s['tag_object'], identity + '-tag.json')
        require(tag.get('object', {}).get('sha') == s['commit'] and tag['object']['type'] == 'commit'
                and tag.get('tag') == s['tag'], 'COMMIT_IDENTITY_MISMATCH')
        require(tag.get('verification', {}).get('verified') is True
                and tag['verification'].get('reason') == 'valid', 'UPSTREAM_TAG_SIGNATURE_NOT_VERIFIED')
        name = identity + '.linux-amd64.' + s['tag']
        assets = {}
        for filename in [name, name + '.sha256sum', name + '.minisig']:
            selected = [a for a in release.get('assets', []) if a.get('name') == filename]
            require(len(selected) == 1, 'EXACT_ASSET_UNAVAILABLE')
            a = selected[0]
            expected = 'https://github.com/' + s['repository'] + '/releases/download/' + s['tag'] + '/' + filename
            require(a.get('browser_download_url') == expected and a.get('state') == 'uploaded', 'ASSET_IDENTITY_MISMATCH')
            p, d = self.download(expected, filename, maximum=160 * 1024 * 1024 if filename == name else 16384,
                                 deadline=120 if filename == name else 30)
            require(d['bytes'] == a['size'], 'ASSET_SIZE_MISMATCH')
            if a.get('digest'):
                require(a['digest'] == 'sha256:' + d['sha256'], 'ASSET_METADATA_DIGEST_MISMATCH')
            assets[filename] = (p, d)
        binary, observed = assets[name]
        verify_checksum(binary, assets[name + '.sha256sum'][0], identity)
        elf(binary)
        key_file, key_observation = self.download(KEY_SOURCE, identity + '-key-source.txt')
        require(key_observation['sha256'] == KEY_SOURCE_SHA256 and PUBLIC_KEY.encode() in key_file.read_bytes(),
                'PINNED_KEY_SOURCE_MISMATCH')
        verify_minisign(binary, assets[name + '.minisig'][0], self.root)
        binary.chmod(0o755)
        result = {'status': 'PASS', 'repository': s['repository'], 'release_tag': s['tag'],
                  'resolved_release_commit': s['commit'], 'tag_object': s['tag_object'],
                  'github_tag_signature': 'VERIFIED_VALID', 'asset_name': name,
                  'acquisition_route': 'OFFICIAL_GITHUB_RELEASE_BINARY', 'sha256': observed['sha256'],
                  'published_checksum_match': True, 'architecture': 'ELF64_LITTLE_ENDIAN_EM_X86_64',
                  'signature': 'MINISIGN_ED25519_BLAKE2B_AND_TRUSTED_COMMENT_VERIFIED',
                  'public_key_source': KEY_SOURCE, 'public_key_source_sha256': key_observation['sha256'],
                  'trust_limitation': 'HTTPS_AND_GITHUB_PINNED_UPSTREAM_SOURCE; NOT_OWNER_OUT_OF_BAND_KEY',
                  'same_origin_checksum_is_independent_authenticity_proof': False,
                  'historical_binary_or_docker_byte_equivalence_claimed': False}
        self.publish(identity + '-release-provenance.json', result)
        return binary, result


def verify_checksum(binary, checksum_path, identity):
    s = SPECS[identity]
    h = hashlib.sha256()
    with Path(binary).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    checksum = Path(checksum_path).read_text().strip().split()
    require(len(checksum) == 2 and checksum[0] == h.hexdigest() == s['sha256']
            and checksum[1].lstrip('*') == identity + '.' + s['tag'], 'CHECKSUM_MISMATCH')


def elf(path):
    with Path(path).open('rb') as f:
        header = f.read(64)
    require(len(header) == 64 and header[:7] == b'\x7fELF\x02\x01\x01'
            and struct.unpack_from('<H', header, 18)[0] == 62
            and struct.unpack_from('<H', header, 16)[0] in {2, 3}, 'BINARY_ARCHITECTURE_MISMATCH')


def verify_minisign(binary, signature_path, root, *, runner=subprocess.run):
    executable = shutil.which('openssl')
    require(executable is not None, 'OPENSSL_VERIFIER_UNAVAILABLE')
    try:
        lines = Path(signature_path).read_text().splitlines()
        key = base64.b64decode(PUBLIC_KEY, validate=True)
        packet = base64.b64decode(lines[1], validate=True)
        global_signature = base64.b64decode(lines[3], validate=True)
        require(len(lines) == 4 and lines[0].startswith('untrusted comment: ')
                and lines[2].startswith('trusted comment: ') and len(key) == 42 and key[:2] == b'Ed'
                and len(packet) == 74 and packet[:2] == b'ED' and packet[2:10] == key[2:10]
                and len(global_signature) == 64, 'SIGNATURE_FORMAT_OR_KEY_MISMATCH')
        h = hashlib.blake2b(digest_size=64)
        with Path(binary).open('rb') as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b''):
                h.update(chunk)
        messages = [(h.digest(), packet[10:]),
                    (packet[10:] + lines[2][len('trusted comment: '):].encode(), global_signature)]
        # A unique private verifier directory; neither signature payload nor output is published.
        import tempfile
        with tempfile.TemporaryDirectory(prefix='verify-', dir=root) as temporary:
            directory = Path(temporary)
            public = directory / 'public.der'
            public.write_bytes(bytes.fromhex('302a300506032b6570032100') + key[10:])
            for n, (message, signature) in enumerate(messages):
                data, sig = directory / f'message-{n}', directory / f'signature-{n}'
                data.write_bytes(message)
                sig.write_bytes(signature)
                r = runner([executable, 'pkeyutl', '-verify', '-pubin', '-inkey', str(public), '-keyform', 'DER',
                            '-rawin', '-in', str(data), '-sigfile', str(sig)], capture_output=True, timeout=10)
                require(r.returncode == 0, 'AUTHENTICATED_SIGNATURE_MISMATCH')
    except ReleaseFailure:
        raise
    except (ValueError, IndexError, OSError, subprocess.TimeoutExpired):
        raise ReleaseFailure('SIGNATURE_VERIFICATION_FAILED') from None


def verify_image(actual, *, identity, expected_id, base_digest, base_layers, binary_sha):
    s = SPECS[identity]
    require(actual.get('Id') == expected_id and re.fullmatch(r'sha256:[0-9a-f]{64}', expected_id)
            and actual.get('Os') == 'linux' and actual.get('Architecture') == 'amd64', 'ASSEMBLED_IMAGE_IDENTITY_MISMATCH')
    labels = actual.get('Config', {}).get('Labels', {}) or {}
    require(all(labels.get(k) == v for k, v in {'m55b.release': s['tag'], 'm55b.binary.sha256': binary_sha,
                'm55b.base.digest': base_digest, 'm55b.upstream.commit': s['commit']}.items()), 'ASSEMBLED_IMAGE_LABEL_MISMATCH')
    require(actual['Config'].get('Entrypoint') == [identity] and base_layers
            and actual.get('RootFS', {}).get('Layers', [])[:len(base_layers)] == base_layers, 'ASSEMBLED_BASE_OR_ENTRYPOINT_MISMATCH')
    return {'status': 'PASS', 'image_id': expected_id, 'base_image_digest': base_digest,
            'binary_sha256': binary_sha, 'platform': 'linux/amd64', 'release_tag': s['tag'],
            'test_only': True, 'public_registry_push': False, 'upstream_docker_byte_equivalence_claimed': False}


def verify_smoke(directory):
    p = Path(directory)
    try:
        before, after = (p / 'input.bin').read_bytes(), (p / 'readback.bin').read_bytes()
        bucket = json.loads((p / 'bucket.json').read_bytes())
        remaining = (p / 'remaining.json').read_text()
    except (OSError, ValueError):
        raise ReleaseFailure('S3_SMOKE_MISSING_OR_MALFORMED') from None
    require(before and before == after and hashlib.sha256(before).digest() == hashlib.sha256(after).digest(),
            'S3_SMOKE_BYTE_OR_DIGEST_MISMATCH')
    require(bucket.get('status') == 'success' and not remaining.strip(), 'S3_SMOKE_BUCKET_OR_CLEANUP_FAILED')
    return {'status': 'PASS', 'uploaded_bytes': len(before), 'readback_bytes': len(after),
            'sha256': hashlib.sha256(before).hexdigest(), 'byte_equality': True, 'digest_equality': True,
            'disposable_object_removed': True, 'remaining_objects': 0, 'production_connections': 0}

"""Deterministic TEST-ONLY fixture; no provider downloads or production inputs."""
import argparse
import copy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import sys

APP_SHA = 'e93cae3028b44ba47f407ce19f7001b8b2983011'
A = 'd70adea5-6e3f-4c32-92c0-49695eeeb9ce'
B = '19994388-464c-4f0c-83db-44c41d8b67fa'
IDENTITY_DIGEST = 'fd5c67b0ab2a80b994e0c574a0d49d9fff241f3991c17bef67cda9e958c02321'


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def write(path, value):
    with Path(path).open('x', encoding='utf-8') as f:
        __import__('os').chmod(path, 0o600)
        json.dump(value, f, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
        f.write('\n')


def generate(app, root, *, quarantine=16064, rejected=1294, dual_quarantine=1424, padding=350):
    sys.path[:0] = [str(app / 'tools/place-ingestion/src'), str(app / 'tools/place-ingestion/tests')]
    from test_canary_manifest import _fixture
    from phokarta_place_ingestion.autonomous_validation import decide_candidate
    from phokarta_place_ingestion.canary_manifest import (
        _assemble_manifest, _sha256_json, _place_uuid, validate_canary_manifest_contract,
    )
    bindings = json.loads(Path(__file__).with_name('canonical_ids.json').read_bytes())
    ids = sorted(x['canonical_place_id'] for x in bindings)
    if len(ids) != 71 or len(set(ids)) != 71 or hashlib.sha256('\n'.join(ids).encode()).hexdigest() != IDENTITY_DIGEST:
        raise ValueError('EXACT_CANONICAL_IDENTITIES_REQUIRED')
    v, _, _ = _fixture(71)
    context = v['reproducible']['source_context']
    candidates, decisions = [], []
    # An explicit synthetic byte-volume dimension, not copied provider metadata.
    pad = 'SYNTHETIC_TEST_ONLY_' + 'x' * padding
    def synthetic(row):
        row = dict(row, license_identifier='ISOLATED_SYNTHETIC_TEST_ONLY')
        row['provenance'] = {'classification': 'SYNTHETIC_TEST_ONLY', 'synthetic_padding': pad}
        row.pop('source_hash', None)
        row['source_hash'] = _sha256_json(row)
        return row
    for index, base in enumerate(v['candidates']):
        c = replace(base, candidate_id=bindings[index]['candidate_id'],
                    latitude=37.36 + (index // 9) * 0.003,
                    longitude=27.25 + (index % 9) * 0.003)
        if _place_uuid(c.candidate_id) != bindings[index]['canonical_place_id']:
            raise ValueError('CANONICAL_UUID_BASIS_MISMATCH')
        rows = []
        for provider, ext in [('overture', c.overture_id), ('fsq', c.fsq_id)]:
            row = synthetic(dict(context[provider, ext], latitude=c.latitude, longitude=c.longitude))
            context[provider, ext] = row
            rows.append(row)
        c = replace(c, source_hashes=tuple(r['source_hash'] for r in rows))
        d = decide_candidate(c, rows)
        if d.action.value != 'AUTO_CREATE' or not d.canary_eligible or d.hard_blockers:
            raise ValueError('SYNTHETIC_ELIGIBLE_POLICY_FAILED')
        candidates.append(c)
        decisions.append(d)
    template = v['candidates'][0]
    source_template = context['overture', template.overture_id]
    for index in range(quarantine):
        oid = 'synthetic-q-o-' + str(index)
        fid = 'synthetic-q-f-' + str(index) if index < dual_quarantine else None
        c = replace(template, candidate_id=f'synthetic-q-{index:08d}',
                    overture_id=oid, fsq_id=fid, proposed_name=f'Synthetic unresolved {index}',
                    overture_name=f'Synthetic unresolved {index}', fsq_name=None,
                    classification='REVIEW_REQUIRED', proposed_category=None,
                    provider_categories=('overture:synthetic_unknown',), risk_flags=('CATEGORY_UNMAPPED',),
                    cross_provider_classification=None, matching_reasons=(), source_hashes=())
        rows = []
        for provider, ext in [('overture', oid), ('fsq', fid)]:
            if ext is None:
                continue
            provider_template = context[provider, 'o-0' if provider == 'overture' else 'f-0']
            row = synthetic(dict(provider_template, external_id=ext, name=c.proposed_name,
                                 proposed_place_category=None, provider_categories=['synthetic_unknown']))
            context[provider, ext] = row
            rows.append(row)
        c = replace(c, source_hashes=tuple(r['source_hash'] for r in rows))
        d = decide_candidate(c, rows)
        if d.action.value != 'QUARANTINE' or d.canary_eligible:
            raise ValueError('SYNTHETIC_QUARANTINE_POLICY_FAILED')
        candidates.append(c)
        decisions.append(d)
    rejects = []
    for index in range(rejected):
        ext = f'synthetic-rejected-{index:08d}'
        context['overture', ext] = synthetic(dict(source_template, external_id=ext,
                                                name=f'Synthetic rejected {index}', operating_status='CLOSED'))
        rejects.append({'provider': 'overture', 'external_id': ext, 'reasons': 'SOURCE_QUALITY_REJECTED'})
    v.update(candidates=tuple(candidates), rejected=tuple(rejects))
    order = tuple(c.candidate_id for c in candidates[:71])
    old = _assemble_manifest(v, decisions, order, stage='STAGE_1', run_id=A,
                            pilot_run_key='didim-ci-synthetic-v3', authorization_reference='ISOLATED_TEST_ONLY_A')
    old_envelope = {'manifest': old, 'manifest_hash': _sha256_json(old)}
    new = copy.deepcopy(old)
    new.update(run_id=B, method_version='didim-autonomous-validation-v3',
               authorization_reference='ISOLATED_TEST_ONLY_B', reauthorizes_run_id=A,
               predecessor_manifest_hash=old_envelope['manifest_hash'],
               canonical_identity_method_version='didim-autonomous-validation-v2', performance_policy='ADVISORY_ONLY')
    envelope = {'manifest': new, 'manifest_hash': _sha256_json(new)}
    result = validate_canary_manifest_contract(envelope, predecessor=old_envelope)
    root.mkdir(mode=0o700, parents=True)
    (root / 'predecessor').mkdir(mode=0o700)
    old_path = root / 'predecessor/stage_1_import_manifest.json'
    new_path = root / 'stage_1_import_manifest.json'
    write(old_path, old_envelope)
    write(new_path, envelope)
    if max(p.stat().st_size for p in (old_path, new_path)) > 128 * 1024 * 1024:
        raise ValueError('MANIFEST_SIZE_CONTRACT_FAILED')
    if quarantine == 16064 and result != {'source_records': 18924, 'candidates': 16135, 'eligible': 71, 'selected': 71}:
        raise ValueError('FULL_VOLUME_ACCOUNTING_REQUIRED')
    info = {'classification': 'EXCLUSIVELY_SYNTHETIC', 'method': 'PINNED_APPROVED_TEST_GENERATOR_AND_REAL_POLICY',
            'production_fingerprint_equivalence_claimed': False, 'counts': result,
            'source_states': new['source_record_states'], 'candidate_decisions': new['candidate_decisions'],
            'canonical_uuid_digest': IDENTITY_DIGEST, 'selected_canonical_ids': ids,
            'manifest_hash': envelope['manifest_hash'], 'envelope_sha256': sha(new_path),
            'predecessor_manifest_hash': old_envelope['manifest_hash'], 'predecessor_sha256': sha(old_path),
            'manifest_bytes': new_path.stat().st_size, 'predecessor_bytes': old_path.stat().st_size,
            'authorization': 'ISOLATED_TEST_ONLY_B', 'predecessor_authorization': 'ISOLATED_TEST_ONLY_A'}
    write(root.parent / 'fixture.json', info)
    return info


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--application', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    info = generate(args.application.resolve(), args.output.resolve())
    print(json.dumps({k: v for k, v in info.items() if k != 'selected_canonical_ids'}))

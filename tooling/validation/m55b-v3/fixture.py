"""Deterministic TEST-ONLY fixture; no provider downloads or production inputs."""
import argparse
import copy
from collections import Counter
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import sys

APP_SHA = 'e93cae3028b44ba47f407ce19f7001b8b2983011'
A = 'd70adea5-6e3f-4c32-92c0-49695eeeb9ce'
B = '19994388-464c-4f0c-83db-44c41d8b67fa'
IDENTITY_DIGEST = 'fd5c67b0ab2a80b994e0c574a0d49d9fff241f3991c17bef67cda9e958c02321'
SELECTED_TAXONOMY = {
    'CAFE': ('Kafe', 'cafe', '4bf58dd8d48988d16d941735'),
    'RESTAURANT': ('Restoran', 'restaurant', '4bf58dd8d48988d1c4941735'),
}
SELECTED_CATEGORY_COUNTS = {'CAFE': 36, 'RESTAURANT': 35}


def validate_category_regression(evidence):
    """Require real Java service evidence, not a copied Python anomaly formula."""
    try:
        if (evidence['classification'] != 'ISOLATED_CATEGORY_REGRESSION_ONLY'
                or evidence['negative_transaction_rolled_back'] is not True
                or evidence['restored_snapshot_equal'] is not True
                or evidence['balanced_recheck_passed'] is not True):
            raise ValueError
        for label, passed, growth, spikes in [
            ('balanced', True, SELECTED_CATEGORY_COUNTS, 0),
            ('homogeneous', False, {'CAFE': 71}, 1),
        ]:
            report = evidence[label]
            if (report['version'] != 'didim-catalog-anomaly-v1'
                    or report['passed'] is not passed
                    or report['before']['active_places'] != 0
                    or report['before']['category_counts'] != {}
                    or report['after']['active_places'] != 71
                    or report['after']['category_counts'] != growth
                    or report['deltas']['category_growth'] != growth
                    or report['deltas']['category_spikes'] != spikes):
                raise ValueError
            if any(report['deltas'][key] != 0 for key in [
                'same_coordinate_anomalies', 'same_name_category_near_duplicate_pairs', 'dense_marker_excess',
            ]) or any(report[key] != 0 for key in [
                'invalid_or_out_of_scope_coordinates', 'invalid_categories', 'provider_reference_collisions',
                'source_orphans', 'canonical_without_provenance', 'duplicate_canonical_assignments',
            ]):
                raise ValueError
    except (KeyError, TypeError, ValueError):
        raise ValueError('SYNTHETIC_CATEGORY_REGRESSION_NOT_VERIFIED') from None


def selected_profile(index):
    if type(index) is not int or not 0 <= index < 71:
        raise ValueError('EXACT_SELECTED_INDEX_REQUIRED')
    category = 'CAFE' if index % 2 == 0 else 'RESTAURANT'
    label, overture, fsq = SELECTED_TAXONOMY[category]
    return category, f'İnci {label} {index}', {'overture': overture, 'fsq': fsq}


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
    from phokarta_place_ingestion.production_categories import ProductionCategoryMapper
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
    mapper = ProductionCategoryMapper()
    # An explicit synthetic byte-volume dimension, not copied provider metadata.
    pad = 'SYNTHETIC_TEST_ONLY_' + 'x' * padding
    def synthetic(row):
        row = dict(row, license_identifier='ISOLATED_SYNTHETIC_TEST_ONLY')
        row['provenance'] = {'classification': 'SYNTHETIC_TEST_ONLY', 'synthetic_padding': pad}
        row.pop('source_hash', None)
        row['source_hash'] = _sha256_json(row)
        return row
    for index, base in enumerate(v['candidates']):
        category, name, taxonomy = selected_profile(index)
        for provider, value in taxonomy.items():
            mapped = mapper.map(provider, (value,))
            if (mapped.category != category or mapped.rule != 'exact'
                    or mapper.mapped_categories(provider, (value,)) != (category,)
                    or mapper.unmapped_non_ignored_values(provider, (value,))):
                raise ValueError('SYNTHETIC_CATEGORY_TAXONOMY_MISMATCH')
        c = replace(base, candidate_id=bindings[index]['candidate_id'],
                    proposed_category=category, proposed_name=name, overture_name=name, fsq_name=name,
                    provider_categories=tuple(f'{provider}:{value}' for provider, value in taxonomy.items()),
                    latitude=37.36 + (index // 9) * 0.003,
                    longitude=27.25 + (index % 9) * 0.003)
        if _place_uuid(c.candidate_id) != bindings[index]['canonical_place_id']:
            raise ValueError('CANONICAL_UUID_BASIS_MISMATCH')
        rows = []
        for provider, ext in [('overture', c.overture_id), ('fsq', c.fsq_id)]:
            row = synthetic(dict(context[provider, ext], latitude=c.latitude, longitude=c.longitude,
                                 name=name, proposed_place_category=category, provider_categories=[taxonomy[provider]]))
            context[provider, ext] = row
            rows.append(row)
        c = replace(c, source_hashes=tuple(r['source_hash'] for r in rows))
        d = decide_candidate(c, rows)
        if d.action.value != 'AUTO_CREATE' or not d.canary_eligible or d.hard_blockers:
            raise ValueError('SYNTHETIC_ELIGIBLE_POLICY_FAILED')
        candidates.append(c)
        decisions.append(d)
    category_counts = dict(Counter(c.proposed_category for c in candidates))
    if category_counts != SELECTED_CATEGORY_COUNTS:
        raise ValueError('SYNTHETIC_SELECTED_CATEGORY_DISTRIBUTION_FAILED')
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
            'selected_category_counts': category_counts,
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

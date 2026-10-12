"""Read-only, best-effort evidence projection AFTER runner exit, BEFORE teardown.

Not an acceptance gate, worker, retry, cleanup or containment implementation.
Unknown/partial evidence can never manufacture a successful operational result.
"""
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import time

from fixture import A, B

UNKNOWN = 'UNKNOWN'
SURFACES = {'search', 'nearby', 'bounds', 'detail'}
COLD_SURFACES = {'health', 'search', 'map_nearby', 'map_bounds', 'place_detail'}
EVENTS = {'PILOT_CONTAINMENT_REQUESTED', 'PILOT_ROLLBACK_STARTED', 'PILOT_ROLLBACK_COMPLETED'}
FILES = ['EXECUTION_TARGET_RECEIPT', 'CURRENT_TARGET', 'TELEMETRY_WORKER_READY', 'PRODUCT_WORKER_READY',
         'PRE', 'PRE_RECEIPT', 'POST_REQUEST', 'POST', 'POST_RECEIPT', 'PRODUCT_GRAPH_SAFETY',
         'PRODUCT_REQUEST', 'PRODUCT', 'PRODUCT_RECEIPT', 'PRODUCT_FAILED', 'WORKER_FAILED']
CHECKS = {'search', 'turkish_search', 'nearby', 'bounds', 'selected_place_coverage', 'public_provider_id_isolation',
          'zero_experience_detail', 'want_to_go', 'collections', 'synthetic_v2_experience', 'detail_after_publication',
          'experience_first_explore', 'rollback_safety', 'graph_safety'}


def enum(value, choices):
    return value if isinstance(value, str) and value in choices else UNKNOWN


def number(value):
    return value if type(value) in (int, float) and math.isfinite(value) and value >= 0 else None


def count(value):
    return value if type(value) is int and value >= 0 else None


def timestamp(value):
    try:
        if not isinstance(value, str) or len(value) > 40:
            return None
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        return parsed.isoformat() if parsed.tzinfo is not None else None
    except ValueError:
        return None


def strict(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('DUPLICATE_FIELD')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))


def artifact(path):
    """Do not publish the file contents or arbitrary names/errors."""
    if not path.exists():
        return {'presence': 'MISSING', 'validity': 'NOT_VERIFIED'}, None
    try:
        if path.is_symlink() or path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError()
        raw = path.read_bytes()
        value = strict(raw)
        if not isinstance(value, dict):
            raise ValueError()
        return {'presence': 'PRESENT', 'validity': 'JSON_ONLY', 'bytes': len(raw),
                'sha256': hashlib.sha256(raw).hexdigest()}, value
    except Exception:
        return {'presence': 'PRESENT', 'validity': 'INVALID_OR_UNREADABLE'}, None


def bound(value, plan):
    return (isinstance(value, dict) and value.get('run_id') == B
            and value.get('manifest_hash') == plan.get('manifest_hash'))


def receipt_ok(info, receipt, plan):
    return (bound(receipt, plan) and receipt.get('artifact_sha256') == info.get('sha256')
            and type(receipt.get('artifact_bytes')) is int and receipt['artifact_bytes'] == info.get('bytes'))


def observation(value, cold=False):
    if not isinstance(value, dict):
        raise ValueError()
    result = {'surface': enum(value.get('surface'), COLD_SURFACES if cold else SURFACES),
              'latency_ms': number(value.get('duration_ms' if cold else 'latency_ms')),
              'status': count(value.get('http_status' if cold else 'status')),
              'validation': enum(value.get('response_validation' if cold else 'validation'),
                                 {'VALID', 'INVALID', 'TIMEOUT', 'INTERRUPTED', 'NOT_EVALUATED'})}
    if cold:
        result.update(sample_index=count(value.get('sample_index')),
                      timeout=value.get('timeout') if type(value.get('timeout')) is bool else None)
    else:
        result.update(preconditioning=value.get('preconditioning') if type(value.get('preconditioning')) is bool else None,
                      headers_ms=number(value.get('headers_ms')), body_ms=number(value.get('body_ms')),
                      response_bytes=count(value.get('response_bytes')), result_count=count(value.get('result_count')),
                      utc_start=timestamp(value.get('utc_start')))
        digest = value.get('id_digest')
        result['id_digest'] = digest if isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest) else None
    return result


def valid_request(value):
    return (value['surface'] != UNKNOWN and value['validation'] == 'VALID'
            and value['status'] == 200 and value['latency_ms'] is not None and 0 < value['latency_ms'] < 5000)


def telemetry(role, docs, inventory, plan):
    value = docs.get(role)
    if not isinstance(value, dict):
        return {'status': 'NOT_VERIFIED', 'requests': []}
    requests = value.get('requests', [])
    if not isinstance(requests, list) or len(requests) > 100:
        return {'status': 'INVALID', 'requests': []}
    records = [observation(r) for r in requests]
    complete = (bound(value, plan) and value.get('role') == role and value.get('outcome') == 'COMPLETE'
                and receipt_ok(inventory[role], docs.get(role + '_RECEIPT'), plan)
                and len(records) == 84 and all(valid_request(r) for r in records)
                and all(sum(r['surface'] == s and r['preconditioning'] is True for r in records) == 1
                        and sum(r['surface'] == s and r['preconditioning'] is False for r in records) == 20
                        for s in SURFACES))
    first = next((r for r in records if r['surface'] == 'detail' and r['preconditioning'] is True), None)
    return {'status': 'VERIFIED_COMPLETE' if complete else 'PARTIAL_OR_INVALID',
            'validation_scope': 'DIAGNOSTIC_READBACK_REQUEST_COUNTS_HTTP_NOT_FULL_JAVA_CONTRACT',
            'receipt_verified': receipt_ok(inventory[role], docs.get(role + '_RECEIPT'), plan),
            'request_count': len(records), 'first_detail_preconditioning': first, 'requests': records}


def cold_baseline(directory, plan):
    paths = list((directory / B).glob('*/PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json'))[:3]
    if len(paths) != 1:
        return {'status': 'MISSING' if not paths else 'AMBIGUOUS', 'requests': []}
    info, value = artifact(paths[0])
    if value is None:
        return {'status': 'NOT_VERIFIED', 'artifact': info, 'requests': []}
    records = []
    surfaces = value.get('surfaces', {})
    if not isinstance(surfaces, dict) or set(surfaces) != COLD_SURFACES:
        return {'status': 'INVALID', 'artifact': info, 'requests': []}
    for surface in sorted(surfaces):
        probes = surfaces[surface].get('probes', [])
        if not isinstance(probes, list) or len(probes) > 20:
            raise ValueError()
        records.extend(observation(r, cold=True) for r in probes)
    valid = (bound(value, plan) and value.get('timeout_ms') == 5000 and len(records) == 25
             and all(sum(r['surface'] == s for r in records) == 5 for s in COLD_SURFACES)
             and all(valid_request(r) and r['timeout'] is False for r in records))
    return {'status': 'VERIFIED_PASS' if valid and value.get('overall') == 'PASS' else 'FAIL_OR_INVALID',
            'overall': enum(value.get('overall'), {'PASS', 'FAIL'}), 'artifact': info,
            'request_count': len(records), 'requests': records}


def log_projection(raw, app):
    """Exact static exception literals + actual approved Java frame identifiers only.

    No substring-marker attribution. Unknown messages stay UNKNOWN, never echoed.
    Non-code literal messages are represented by a digest, not arbitrary text.
    """
    text = raw.decode(errors='replace') if isinstance(raw, bytes) else raw
    if len(text) > 2 * 1024 * 1024:
        raise ValueError('LOG_BOUND')
    sources, literals = {}, set()
    for path in (app / 'backend/src/main/java/com/emirrkls/phokarta/backend').rglob('PlacePilot*.java'):
        code = path.read_text(encoding='utf-8')
        sources[path.stem] = (len(code.splitlines()), set(re.findall(r'\b(\w+)\s*\(', code)))
        literals.update(re.findall(r'throw new \w+(?:Exception|Error)\("([^"\n\\]+)"', code))
        literals.update(re.findall(r',\s*"([^"\n\\]+)"\s*\);', code))
    literals.add('PRIVATE_OPERATIONS_EVIDENCE_INVALID')
    exceptions = []
    for line in text.splitlines():
        match = re.match(r'^(?:Exception in thread "[^"]{1,40}" |Caused by: )?'
                         r'((?:java\.(?:lang|io)\.)\w+(?:Exception|Error))(?:: (.*))?$', line.strip())
        if match:
            message = match[2] or ''
            known = message in literals
            exceptions.append({'exception_class': match[1],
                'reason_code': message if known and re.fullmatch('[A-Z][A-Z0-9_]{2,100}', message) else UNKNOWN,
                'static_message_sha256': hashlib.sha256(message.encode()).hexdigest() if known else None})
    frames = []
    for cls, method, filename, line in re.findall(
            r'\bat (com\.emirrkls\.phokarta\.backend\.[\w.]+)\.([\w$]+)\((\w+)\.java:(\d+)\)', text):
        if (filename in sources and cls.rsplit('.', 1)[-1] == filename and method in sources[filename][1]
                and 0 < int(line) <= sources[filename][0]):
            item = {'class': cls, 'method': method, 'line': int(line)}
            # Fixed pinned-source call-site interpretation, not log marker matching.
            if filename == 'PlacePilotAutonomousCanaryService' and method == 'runV3':
                source = app / 'backend/src/main/java/com/emirrkls/phokarta/backend/service' / (filename + '.java')
                statement = source.read_text(encoding='utf-8').splitlines()[int(line) - 1].strip()
                for literal, stage in [('imported=importer.importApproved', 'RE_ADOPTION'),
                        ('var replay=importer.importApproved', 'REPLAY'), ('capturePersistent("POST"', 'PERSISTENT_POST'),
                        ('PlacePilotV3Policy.verify(', 'POST_VALIDATION'), ('gates.verifyV3Provenance', 'PROVENANCE'),
                        ('gates.verifyV3Accounting', 'SOURCE_ACCOUNTING'), ('anomalies.audit', 'ANOMALY'),
                        ('probes.captureAfter', 'FUNCTIONAL_HTTP'), ('verifiedV3Coverage', 'HTTP_COVERAGE'),
                        ('inspectV3Containment', 'GRAPH_SAFETY'), ('observations.productSafety', 'PRODUCT_SAFETY'),
                        ('observations.productAcceptance', 'PRODUCT_ACCEPTANCE'), ('observations.finalHealth', 'FINAL_HEALTH'),
                        ('gates.record(run,true', 'TERMINAL_GATE')]:
                    if literal in statement:
                        item['call_site_stage'] = stage
                        break
            if item not in frames:
                frames.append(item)
    return {'exceptions': exceptions[:16], 'frames': frames[:32], 'raw_logs_not_published': True}


def database_sql():
    """One existing 5s/statement, 30s total read-only SQL envelope; aggregate-only."""
    return f"""SELECT jsonb_build_object(
      'readonly',current_setting('transaction_read_only'),'isolation',current_setting('transaction_isolation'),
      'run',(SELECT jsonb_build_object('status',status,'created',created_count,'quarantined',quarantined_count)
             FROM place_provider_sync_runs WHERE id='{B}'),
      'gate',(SELECT jsonb_build_object('status',gate_status,'reason',diagnostics->>'orchestration_failure',
          'idempotency',diagnostics->>'same_manifest_idempotency','advisory',diagnostics#>>'{{persistent_performance,PERFORMANCE_ADVISORY}}',
          'coverage',diagnostics->'selected_place_http_coverage','accounting_coverage',diagnostics->'selected_place_ids_checked',
          'product',diagnostics->'product_acceptance') FROM place_pilot_canary_gates WHERE sync_run_id='{B}'),
      'places',(SELECT jsonb_build_object('total',count(*),'manual',count(*) FILTER(WHERE origin='MANUAL_COMMUNITY'),
          'active_imported',count(*) FILTER(WHERE origin='EXTERNAL_IMPORT' AND catalog_status='ACTIVE'),
          'retired',count(*) FILTER(WHERE origin='EXTERNAL_IMPORT' AND catalog_status='RETIRED'),
          'provisional',count(*) FILTER(WHERE origin='EXTERNAL_IMPORT' AND catalog_status='PROVISIONAL')) FROM places),
      'refs',(SELECT jsonb_build_object('total',count(*),'active',count(*) FILTER(WHERE status='ACTIVE'),
          'inactive',count(*) FILTER(WHERE status='INACTIVE'),'b_owned',count(*) FILTER(WHERE last_sync_run_id='{B}'),
          'b_active',count(*) FILTER(WHERE last_sync_run_id='{B}' AND status='ACTIVE'),
          'b_inactive',count(*) FILTER(WHERE last_sync_run_id='{B}' AND status='INACTIVE')) FROM place_external_refs),
      'counts',jsonb_build_object('sources',(SELECT count(*) FROM place_source_records),
          'b_sources',(SELECT count(*) FROM place_source_records WHERE sync_run_id='{B}'),
          'a_candidates',(SELECT count(*) FROM place_validation_decisions WHERE sync_run_id='{A}'),
          'b_candidates',(SELECT count(*) FROM place_validation_decisions WHERE sync_run_id='{B}'),
          'quarantine',(SELECT count(*) FROM place_validation_recheck_queue),
          'b_writes',(SELECT count(*) FROM place_pilot_catalog_writes WHERE sync_run_id='{B}'),
          'readoptions',(SELECT count(*) FROM place_pilot_catalog_writes WHERE sync_run_id='{B}' AND supersedes_write_id IS NOT NULL),
          'retired_writes',(SELECT count(*) FROM place_pilot_catalog_writes WHERE sync_run_id='{B}' AND rollback_state LIKE 'RETIRED%'),
          'users',(SELECT count(*) FROM users),'saved',(SELECT count(*) FROM saved_places),
          'experiences',(SELECT count(*) FROM visits),'collections',(SELECT count(*) FROM collections),
          'synthetic_users',(SELECT count(*) FROM users WHERE id<>'bb000000-0000-4000-8000-000000000001'),
          'synthetic_experiences',(SELECT count(*) FROM visits WHERE user_id<>'bb000000-0000-4000-8000-000000000001'),
          'synthetic_collections',(SELECT count(*) FROM collections WHERE user_id<>'bb000000-0000-4000-8000-000000000001')),
      'physical_inserts',(SELECT n_tup_ins FROM pg_stat_all_tables WHERE relname='places'),
      'stats_reset',(SELECT stats_reset::text FROM pg_stat_database WHERE datname=current_database()),
      'place_relid',(SELECT relid::bigint FROM pg_stat_all_tables WHERE relname='places'),
      'events',(SELECT coalesce(jsonb_agg(jsonb_build_object('type',event_type,'at',occurred_at,
          'retired_count',details->'retired_count') ORDER BY occurred_at,event_type),'[]'::jsonb)
          FROM place_pilot_operational_events WHERE sync_run_id='{B}'))"""


def database_projection(raw, before, selected):
    if raw.get('readonly') != 'on' or raw.get('isolation') != 'repeatable read':
        raise ValueError('READ_ONLY_SNAPSHOT_REQUIRED')
    result = {'status': 'OBSERVED', 'readonly': True, 'isolation': 'repeatable read'}
    for group in ['places', 'refs', 'counts']:
        keys = {'places': ['total', 'manual', 'active_imported', 'retired', 'provisional'],
                'refs': ['total', 'active', 'inactive', 'b_owned', 'b_active', 'b_inactive'],
                'counts': ['sources', 'b_sources', 'a_candidates', 'b_candidates', 'quarantine', 'b_writes',
                           'readoptions', 'retired_writes', 'users', 'saved', 'experiences', 'collections',
                           'synthetic_users', 'synthetic_experiences', 'synthetic_collections']}[group]
        result[group] = {k: count((raw.get(group) or {}).get(k)) for k in keys}
    run, gate = raw.get('run'), raw.get('gate')
    result['run_status'] = 'ABSENT' if run is None else enum(run.get('status'), {'STARTED', 'SUCCEEDED', 'FAILED'})
    result['gate_status'] = 'ABSENT' if gate is None else enum(gate.get('status'), {'PASSED', 'FAILED'})
    gate = gate or {}
    result['replay'] = enum(gate.get('idempotency'), {'PASS', 'FAIL'})
    result['advisory'] = enum(gate.get('advisory'), {'NORMAL', 'DEGRADED', 'INCOMPARABLE'})
    # The accounting list is explicitly NEVER HTTP evidence.
    accounting = gate.get('accounting_coverage')
    result['accounting_identity_count'] = len(accounting) if isinstance(accounting, list) else None
    coverage = gate.get('coverage')
    result['http_coverage'] = {k: {'count': len(v), 'exact_selected_ids': sorted(v) == selected and len(set(v)) == 71}
                             for k, v in (coverage or {}).items() if k in {'search', 'map_nearby', 'map_bounds', 'place_detail'}
                             and isinstance(v, list) and all(isinstance(x, str) for x in v)}
    result['product_checks'] = {k: enum(v, {'PASS', 'FAIL'}) for k, v in (gate.get('product') or {}).items() if k in CHECKS}
    same_stats = (raw.get('stats_reset') == before.get('stats_reset') and raw.get('place_relid') == before.get('place_relid')
                  and count(raw.get('place_relid')) is not None and count(raw.get('physical_inserts')) is not None
                  and count(before.get('physical_inserts')) is not None)
    delta = raw['physical_inserts'] - before['physical_inserts'] if same_stats else None
    result['physical_place_insert_delta'] = delta if delta is not None and delta >= 0 else None
    result['insert_delta_evidence'] = 'SAME_RELATION_AND_STATS_EPOCH' if same_stats else 'NOT_VERIFIED'
    result['graph_count_deltas'] = {k: result['counts'][k] - before[k] if result['counts'][k] is not None
                                   and count(before.get(k)) is not None else None for k in ['users', 'saved', 'experiences']}
    result['events'] = [{'type': enum(e.get('type'), EVENTS), 'at': timestamp(e.get('at')),
                         'retired_count': count(e.get('retired_count'))} for e in raw.get('events', [])[:10]]
    result['containment'] = ('COMPLETED' if [e['type'] for e in result['events']] ==
        ['PILOT_CONTAINMENT_REQUESTED', 'PILOT_ROLLBACK_STARTED', 'PILOT_ROLLBACK_COMPLETED']
        and result['places']['active_imported'] == 0 and result['counts']['retired_writes'] == 71
        else 'REQUESTED_OR_INCOMPLETE' if result['events'] else 'NOT_OBSERVED')
    return result


def attribution(logs, db, evidence):
    last, first = UNKNOWN, UNKNOWN
    if evidence['PRE']['status'] == 'VERIFIED_COMPLETE':
        last = 'PERSISTENT_PRE_ARTIFACT'
    if evidence['cold']['status'] == 'VERIFIED_PASS':
        last = 'COLD_BASELINE'
    if db.get('run_status') == 'SUCCEEDED':
        last = 'RE_ADOPTION_COMMITTED'
    if evidence.get('post_requested') is True:
        last = 'REPLAY'  # Emitted only after exact replay validation in pinned runV3.
    if evidence['POST']['status'] == 'VERIFIED_COMPLETE':
        last = 'PERSISTENT_POST_ARTIFACT'
    if evidence.get('product_requested') is True:
        last = 'PRODUCT_SAFETY_AND_HTTP_COVERAGE'
    if evidence['product_status'] == 'VERIFIED_COMPLETE':
        last = 'PRODUCT_ACCEPTANCE_AND_CLEANUP'
    if db.get('gate_status') == 'PASSED':
        last = 'TERMINAL_GATE_PASSED'
    codes = [e['reason_code'] for e in logs.get('exceptions', []) if e['reason_code'] != UNKNOWN]
    mapping = {'PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED': 'COLD_BASELINE_ARTIFACT',
        'V3_IMPORT_RESULT_HARD_FAILURE': 'RE_ADOPTION', 'V3_IDEMPOTENCY_HARD_FAILURE': 'REPLAY',
        'V3_ANOMALY_HARD_FAILURE': 'ANOMALY', 'V3_FUNCTIONAL_HTTP_HARD_FAILURE': 'FUNCTIONAL_HTTP',
        'V3_PRODUCT_HARD_FAILURE': 'PRODUCT_ACCEPTANCE'}
    reason = next((c for c in reversed(codes) if c != 'PLACE_CANARY_EXECUTION_FAILED'), UNKNOWN)
    first = mapping.get(reason, UNKNOWN)
    if first == UNKNOWN:
        first = next((f['call_site_stage'] for f in logs.get('frames', []) if 'call_site_stage' in f), UNKNOWN)
    # A generic adapter IOException alone cannot identify its failed require(). Actual
    # approved call-site frames are retained for exact source-level investigation.
    return {'last_completed_stage': last, 'first_failed_stage': first, 'fatal_reason_code': reason,
            'containment_failure_observed': 'V3_CONTAINMENT_OWNER_INTERVENTION_REQUIRED' in codes,
            'marker_presence_is_not_gate_attribution': True}


def collect(trial, state, logs, inspect, *, clock=time.monotonic):
    """Callbacks are bounded read-only SQL/log/inspect, never helper Spring or rollback."""
    started = clock()
    result = {'version': 'isolated-runner-postmortem-v1', 'scope': 'EXCLUSIVELY_SYNTHETIC_ISOLATED_FIXTURE',
              'runner_exit_code': state['ExitCode'],
              'runner_oom_killed': state['OOMKilled'], 'collection_status': 'COMPLETE',
              'runner_result_authoritative': True, 'additional_rollback_invocations': 0,
              'raw_data_not_published': True, 'gaps': []}
    def attempt(name, function):
        try:
            if clock() - started >= 90:
                raise TimeoutError()
            return function()
        except Exception:
            result['gaps'].append(name)
            result['collection_status'] = 'PARTIAL'
            return None
    inventory, docs = {}, {}
    for name in FILES:
        info, value = artifact(trial.evidence / (name + '.json'))
        inventory[name], docs[name] = info, value
    result['artifacts'] = inventory
    plan = trial.plan
    attested = (docs.get('EXECUTION_TARGET_RECEIPT') or {}).get('target')
    current = (docs.get('CURRENT_TARGET') or {}).get('target')
    result['execution_target'] = {'attestation_present': attested is not None,
        'current_present': current is not None,
        'same_target': attested == current if attested is not None and current is not None else None}
    evidence = {'PRE': attempt('PRE', lambda: telemetry('PRE', docs, inventory, plan)),
                'POST': attempt('POST', lambda: telemetry('POST', docs, inventory, plan)),
                'cold': attempt('cold', lambda: cold_baseline(trial.evidence, plan))}
    for k in ['PRE', 'POST', 'cold']:
        evidence[k] = evidence[k] or {'status': 'NOT_VERIFIED', 'requests': []}
    evidence['post_requested'] = bound(docs['POST_REQUEST'], plan)
    evidence['product_requested'] = bound(docs['PRODUCT_REQUEST'], plan)
    product = docs['PRODUCT']
    def product_validation():
        if not receipt_ok(inventory['PRODUCT'], docs['PRODUCT_RECEIPT'], plan):
            return 'NOT_VERIFIED'
        import product_evidence_worker
        product_evidence_worker.validate_evidence(product, plan)
        return 'VERIFIED_COMPLETE'
    evidence['product_status'] = attempt('product_contract', product_validation) or 'NOT_VERIFIED'
    evidence['product_check_count'] = len(product['checks']) if evidence['product_status'] == 'VERIFIED_COMPLETE' else None
    failure = docs['PRODUCT_FAILED'] or {}
    evidence['synthetic_cleanup'] = enum(failure.get('cleanup'),
        {'COMPLETED', 'PENDING', 'NOT_NEEDED', 'OWNER_INTERVENTION_REQUIRED'})
    if evidence['product_status'] == 'VERIFIED_COMPLETE':
        evidence['synthetic_cleanup'] = 'COMPLETED'
    evidence['product_failure_observation_count'] = len(failure['observations']) if isinstance(failure.get('observations'), list) else None
    product_codes = {'PRODUCT_TIMEOUT', 'PRODUCT_HTTP_FAILURE', 'PRODUCT_READBACK_FAILED', 'SYNTHETIC_CLEANUP_FAILED',
        'HTTP_SEMANTICS_OR_DEADLINE', 'UNSAFE_REQUEST_ID', 'TARGET_CHANGED_OR_STALE', 'TARGET_ATTESTATION_CHANGED',
        'WORKER_STOPPED', 'PRODUCT_MUTATION_NOT_AUTHORIZED', 'GRAPH_SAFETY_EVIDENCE_INVALID', 'SEARCH_COVERAGE',
        'TURKISH_COVERAGE', 'BOUNDS_COVERAGE', 'BOUNDS_GEOMETRY', 'DENSE_MARKERS', 'BOUNDS_LIMIT', 'NEARBY_COVERAGE',
        'NEARBY_GEOMETRY_ORDER', 'ZERO_DETAIL', 'ZERO_AGGREGATE', 'PROVIDER_FIELD_LEAK', 'PROVIDER_VALUE_LEAK',
        'PRODUCT_HARD_FAILURE', 'SYNTHETIC_IDENTITY_MISMATCH', 'HARD_FAILURE'}
    evidence['product_failure_code'] = enum(failure.get('failure_code'), product_codes)
    evidence['telemetry_worker_failure'] = enum((docs['WORKER_FAILED'] or {}).get('reason'), {'HARD_FAILURE'})
    def db_read():
        # Reserve the unchanged single 30s SQL subprocess envelope inside 90s postmortem.
        if clock() - started > 60:
            raise TimeoutError()
        raw = trial.sql(database_sql())
        projected = database_projection(raw, trial.extra_before, plan['selected_canonical_ids'])
        reason = str((raw.get('gate') or {}).get('reason', ''))
        reason = re.sub(r'^(?:IllegalStateException|IllegalArgumentException|IOException): ', '', reason)
        result['gate_failure'] = log_projection('java.lang.IllegalStateException: ' + reason, trial.app)
        return projected
    result['database'] = attempt('database', db_read) or {'status': 'NOT_VERIFIED'}
    result['runner_log'] = attempt('runner_log', lambda: log_projection(logs(), trial.app)) or {}
    result['evidence'] = evidence
    combined = {'exceptions': result['runner_log'].get('exceptions', []) + result.get('gate_failure', {}).get('exceptions', []),
                'frames': result['runner_log'].get('frames', [])}
    result['attribution'] = attribution(combined, result['database'], evidence)
    result['services'] = {}
    for name in ['target', 'db', 'caddy', 'product_cid']:
        def read_service(name=name):
            value = inspect(getattr(trial, name), max(.1, min(5, 90 - (clock() - started))))
            s = value['State']
            return {'running': s['Running'] is True, 'oom_killed': s['OOMKilled'] is True,
                    'exit_code': count(s['ExitCode']), 'restart_count': count(value['RestartCount']),
                    'health': enum(s.get('Health', {}).get('Status'), {'healthy', 'unhealthy', 'starting'}),
                    'container_id': value['Id'] if re.fullmatch('[0-9a-f]{64}', value.get('Id', '')) else None}
        result['services'][name] = attempt('service_' + name, read_service) or {'health': 'NOT_VERIFIED'}
    result['elapsed_ms'] = (clock() - started) * 1000
    return result

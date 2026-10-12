"""Bounded allowlisted worker evidence; never exception messages, bodies or env."""
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import math
import re
import socket
import ssl
import time

PREFIX = 'M55B_TARGET_DIAGNOSTIC='
VERSION = 'm55b-persistent-target-diagnostics-v1'
PHASES = ('APPLICATION_IMPORT', 'DNS', 'TLS_CONTEXT', 'TCP', 'TLS_CHAIN_HOSTNAME',
          'TLS_NEGATIVE_HOSTNAME', 'B1_HEALTH_LIVE', 'B1_HEALTH_READY', 'B1_SEARCH', 'ARTIFACT_WRITE')
CATEGORIES = {'DNS_RESOLUTION', 'ISOLATED_DNS_REQUIRED', 'TLS_CERTIFICATE_VALIDATION', 'TLS_PROTOCOL',
    'VERIFIED_TLS_REQUIRED', 'HOSTNAME_MISMATCH_NOT_REJECTED', 'TCP_REFUSED', 'NETWORK_DEADLINE',
    'FILESYSTEM_ACCESS', 'APPLICATION_IMPORT', 'B1_TIMEOUT', 'B1_TRANSPORT_FAILURE',
    'B1_HTTP_STATUS', 'B1_SEMANTICS', 'VALIDATION_ERROR', 'OS_ERROR', 'UNCLASSIFIED',
    'HOSTNAME_REFERENCE_FAILED', 'HOSTNAME_CONTROL_UNCLASSIFIED',
    'HOSTNAME_CONTROL_TOOL_UNAVAILABLE', 'HOSTNAME_CONTROL_OUTPUT_LIMIT'}
CLASSES = {'DiagnosticFailure', 'gaierror', 'SSLCertVerificationError', 'SSLError', 'ConnectionRefusedError',
           'TimeoutError', 'PermissionError', 'ModuleNotFoundError', 'ImportError', 'RequestFailure',
           'ValueError', 'OSError', 'FileNotFoundError', 'TypeError', 'KeyError', 'AttributeError'}
FIELDS = {'address_count', 'all_private', 'cert_required', 'check_hostname', 'tls_version',
          'negative_control_rejected', 'status', 'latency_ms', 'request_id', 'semantic_valid',
          'sni_preserved', 'positive_reference_verified', 'mismatch_verify_code', 'hostname_control_elapsed_ms'}


class DiagnosticFailure(ValueError):
    def __init__(self, category):
        if category not in CATEGORIES:
            raise ValueError('UNAPPROVED_DIAGNOSTIC_CATEGORY')
        self.category = category
        super().__init__(category)


def classify(error):
    if isinstance(error, DiagnosticFailure):
        return error.category
    if isinstance(error, ssl.SSLCertVerificationError):
        return 'TLS_CERTIFICATE_VALIDATION'
    if isinstance(error, ssl.SSLError):
        return 'TLS_PROTOCOL'
    if isinstance(error, socket.gaierror):
        return 'DNS_RESOLUTION'
    if isinstance(error, ConnectionRefusedError):
        return 'TCP_REFUSED'
    if isinstance(error, TimeoutError):
        return 'NETWORK_DEADLINE'
    if isinstance(error, PermissionError):
        return 'FILESYSTEM_ACCESS'
    if isinstance(error, ImportError):
        return 'APPLICATION_IMPORT'
    # Read ONLY the approved client's fixed failure enum; never stringify errors.
    if type(error).__name__ == 'RequestFailure':
        observation = getattr(error, 'observation', {})
        return 'B1_TIMEOUT' if observation.get('validation') == 'PRODUCT_TIMEOUT' else 'B1_TRANSPORT_FAILURE'
    return 'OS_ERROR' if isinstance(error, OSError) else 'VALIDATION_ERROR' if isinstance(error, ValueError) else 'UNCLASSIFIED'


def utc():
    return datetime.now(timezone.utc).isoformat()


def finite(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def validate(record):
    """Reject rather than publish unexpected keys/strings from subprocess output."""
    if not isinstance(record, dict) or set(record) != {'version', 'status', 'phase', 'events'}:
        raise ValueError('DIAGNOSTIC_SCHEMA')
    if record['version'] != VERSION or record['status'] not in {'RUNNING', 'PASS', 'FAIL'}:
        raise ValueError('DIAGNOSTIC_STATUS')
    events = record['events']
    if not isinstance(events, list) or not 1 <= len(events) <= len(PHASES):
        raise ValueError('DIAGNOSTIC_EVENTS')
    if [e.get('phase') for e in events] != list(PHASES[:len(events)]) or record['phase'] != events[-1]['phase']:
        raise ValueError('DIAGNOSTIC_ORDER')
    for index, event in enumerate(events):
        required = {'phase', 'status', 'started_at', 'completed_at', 'elapsed_ms', 'details', 'failure_category', 'exception_class'}
        if set(event) != required or event['status'] not in {'RUNNING', 'PASS', 'FAIL'}:
            raise ValueError('DIAGNOSTIC_EVENT_SCHEMA')
        if index < len(events) - 1 and event['status'] != 'PASS':
            raise ValueError('DIAGNOSTIC_PARTIAL_CONTINUATION')
        for key in ('started_at', 'completed_at'):
            if key == 'completed_at' and event[key] is None and event['status'] == 'RUNNING':
                continue
            value = event[key]
            if not isinstance(value, str) or len(value) > 40 or not re.fullmatch(r'[0-9T:+.Z-]+', value):
                raise ValueError('DIAGNOSTIC_TIMESTAMP')
            stamp = datetime.fromisoformat(value.replace('Z', '+00:00'))
            if stamp.utcoffset() is None or stamp.utcoffset().total_seconds() != 0:
                raise ValueError('DIAGNOSTIC_UTC')
        if not finite(event['elapsed_ms']):
            raise ValueError('DIAGNOSTIC_DURATION')
        if event['status'] == 'FAIL':
            if event['failure_category'] not in CATEGORIES or event['exception_class'] not in CLASSES | {'OTHER'}:
                raise ValueError('DIAGNOSTIC_FAILURE')
        elif event['failure_category'] is not None or event['exception_class'] is not None:
            raise ValueError('DIAGNOSTIC_FALSE_FAILURE')
        details = event['details']
        if not isinstance(details, dict) or not set(details) <= FIELDS:
            raise ValueError('DIAGNOSTIC_DETAILS')
        for key, value in details.items():
            if key in {'all_private', 'cert_required', 'check_hostname', 'negative_control_rejected', 'semantic_valid',
                       'sni_preserved', 'positive_reference_verified'}:
                valid = type(value) is bool
            elif key == 'request_id':
                valid = isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,100}', value)
            elif key == 'tls_version':
                valid = value in {'TLSv1.2', 'TLSv1.3'}
            elif key == 'status':
                valid = type(value) is int and 100 <= value <= 599
            elif key == 'address_count':
                valid = type(value) is int and 0 <= value <= 32
            elif key == 'mismatch_verify_code':
                valid = type(value) is int and value == 62
            else:
                valid = finite(value)
            if not valid:
                raise ValueError('DIAGNOSTIC_VALUE')
    last = events[-1]['status']
    if record['status'] == 'PASS' and (len(events) != len(PHASES) or last != 'PASS'):
        raise ValueError('DIAGNOSTIC_PARTIAL_PASS')
    if record['status'] == 'FAIL' and last != 'FAIL':
        raise ValueError('DIAGNOSTIC_FAILURE_MISSING')
    return record


class Recorder:
    def __init__(self, sink=print):
        self.sink = sink
        self.record = {'version': VERSION, 'status': 'RUNNING', 'phase': None, 'events': []}

    def emit(self):
        validate(self.record)
        self.sink(PREFIX + json.dumps(self.record, sort_keys=True, separators=(',', ':'), allow_nan=False), flush=True)

    @contextmanager
    def step(self, phase):
        index = len(self.record['events'])
        if index >= len(PHASES) or phase != PHASES[index] or self.record['status'] != 'RUNNING':
            raise ValueError('DIAGNOSTIC_PHASE_ORDER')
        event = {'phase': phase, 'status': 'RUNNING', 'started_at': utc(), 'completed_at': None,
                 'elapsed_ms': 0, 'details': {}, 'failure_category': None, 'exception_class': None}
        self.record['events'].append(event)
        self.record['phase'] = phase
        started = time.monotonic()
        self.emit()
        try:
            yield event['details']
        except Exception as error:
            event.update(status='FAIL', failure_category=classify(error),
                         exception_class=type(error).__name__ if type(error).__name__ in CLASSES else 'OTHER')
            self.record['status'] = 'FAIL'
            raise
        else:
            event['status'] = 'PASS'
        finally:
            event.update(completed_at=utc(), elapsed_ms=(time.monotonic() - started) * 1000)
            self.emit()

    def finish(self):
        self.record['status'] = 'PASS'
        self.emit()


def from_logs(raw):
    """In-memory logs -> strictly validated projection. No raw log persistence."""
    if len(raw) > 256 * 1024:
        raise ValueError('DIAGNOSTIC_LOG_LIMIT')
    found = []
    for line in raw.splitlines():
        if line.startswith(PREFIX.encode()):
            if len(line) > 32 * 1024 or len(found) >= 2 * len(PHASES) + 1:
                raise ValueError('DIAGNOSTIC_RECORD_LIMIT')
            def pairs(items):
                if len(dict(items)) != len(items):
                    raise ValueError('DIAGNOSTIC_DUPLICATE_KEY')
                return dict(items)
            record = json.loads(line[len(PREFIX):], object_pairs_hook=pairs)
            found.append(validate(record))
    if not found:
        raise ValueError('DIAGNOSTIC_NOT_EMITTED')
    # A later record may extend progress, never erase an observed failure.
    for before, after in zip(found, found[1:]):
        if before['status'] != 'RUNNING' or len(after['events']) < len(before['events']):
            raise ValueError('DIAGNOSTIC_HISTORY_INVALID')
        if before['events'][:-1] != after['events'][:len(before['events']) - 1]:
            raise ValueError('DIAGNOSTIC_HISTORY_CHANGED')
    return found[-1]

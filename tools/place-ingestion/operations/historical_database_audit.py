"""Complete historical audit: one connection/snapshot, 5s statements, 30s total.

Operational resilience only. This is NOT a confirmed fix for the historical
production timeout and never authorizes a downstream operation or live retry.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import time

SQL_SHA256 = "afa3d485de1c28d5293fbc71cfe44b8e2a5aeb0ef4ba57d479cf7a9ccfd91866"
EXPRESSION_COUNT = 38
STATEMENT_TIMEOUT_MS = 5000
TOTAL_BUDGET_SECONDS = 30
MAX_OUTPUT_BYTES = 1024 * 1024
COMMIT_MARKER = "HISTORICAL_AUDIT_COMMITTED"
SQL_PATH = Path(__file__).with_suffix(".sql")


class AuditFailure(RuntimeError):
    def __init__(self, code, diagnostics=None):
        super().__init__(code)
        self.code = code
        self.diagnostics = diagnostics or {}


def require(condition, code):
    if not condition:
        raise AuditFailure(code)


def strict_json(raw):
    def pairs(items):
        obj = {}
        for key, value in items:
            require(key not in obj, "DUPLICATE_JSON_KEY")
            obj[key] = value
        return obj
    def invalid_constant(value):
        raise AuditFailure("NONFINITE_JSON")
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, TypeError) as error:
        raise AuditFailure("MALFORMED_JSON") from error


def split_arguments(text):
    """Split only the pinned SQL's nested arguments; never evaluate user SQL."""
    result, depth, quoted, start, index = [], 0, False, 0, 0
    while index < len(text):
        char = text[index]
        if char == "'":
            if quoted and index + 1 < len(text) and text[index + 1] == "'":
                index += 2
                continue
            quoted = not quoted
        elif not quoted:
            if char == "(": depth += 1
            elif char == ")": depth -= 1
            elif char == "," and depth == 0:
                result.append(text[start:index].strip())
                start = index + 1
        require(depth >= 0, "INVALID_SQL_STRUCTURE")
        index += 1
    require(depth == 0 and not quoted, "INVALID_SQL_STRUCTURE")
    result.append(text[start:].strip())
    return result


def expressions():
    raw = SQL_PATH.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == SQL_SHA256, "ORIGINAL_SQL_HASH_MISMATCH")
    sql = raw.decode("utf-8").strip()
    require(sql.startswith("SELECT jsonb_build_object(") and sql.endswith(");"), "INVALID_SQL_STRUCTURE")
    def walk(expression, path):
        if expression.startswith("jsonb_build_object("):
            arguments = split_arguments(expression[len("jsonb_build_object("):-1])
            require(len(arguments) % 2 == 0, "INVALID_SQL_STRUCTURE")
            found = []
            for index in range(0, len(arguments), 2):
                key = arguments[index]
                require(re.fullmatch(r"'[a-z_]+'", key) is not None, "INVALID_SQL_KEY")
                found.extend(walk(arguments[index + 1], path + (key[1:-1],)))
            return found
        return [(path, expression)]
    result = walk(sql[len("SELECT "):-1], ())
    require(len(result) == EXPRESSION_COUNT and len({path for path, _ in result}) == EXPRESSION_COUNT,
            "EXPRESSION_INVENTORY_MISMATCH")
    return result


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def flatten_expected(value, paths):
    require(isinstance(value, dict), "INVALID_EXPECTED_AUDIT")
    # Only expand original SQL jsonb_build_object nodes, not arbitrary value objects.
    expected = {}
    for path in paths:
        cursor = value
        for key in path:
            require(isinstance(cursor, dict) and key in cursor, "INVALID_EXPECTED_AUDIT")
            cursor = cursor[key]
        expected[path] = cursor
    rebuilt = reconstruct(expected, paths)
    require(canonical(rebuilt) == canonical(value), "INVALID_EXPECTED_AUDIT")
    return expected


def reconstruct(values, paths):
    require(set(values) == set(paths) and len(values) == EXPRESSION_COUNT, "INCOMPLETE_AUDIT")
    result = {}
    for path in paths:
        cursor = result
        for key in path[:-1]:
            cursor = cursor.setdefault(key, {})
        require(path[-1] not in cursor, "DUPLICATE_EXPRESSION")
        cursor[path[-1]] = values[path]
    return result


CONTEXT = ("'backend_pid',pg_backend_pid(),'snapshot',pg_current_snapshot()::text,"
           "'isolation',current_setting('transaction_isolation'),'read_only',current_setting('transaction_read_only'),"
           "'statement_timeout',current_setting('statement_timeout')")


def build_script():
    inventory = expressions()
    script = ["\\timing off", "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;",
              "SELECT jsonb_build_object('kind','HEADER'," + CONTEXT + ");", "\\timing on"]
    for index, (path, expression) in enumerate(inventory):
        # Pinned expressions are unchanged; psql's own timing measures each statement.
        path_literal = json.dumps(path).replace("'", "''")
        script.append("SELECT jsonb_build_object('kind','EXPRESSION','index'," + str(index)
                      + ",'path','" + path_literal + "'::jsonb,'value'," + expression + "," + CONTEXT + ");")
    script += ["\\timing off", "SELECT jsonb_build_object('kind','FOOTER'," + CONTEXT + ");",
               "COMMIT;", "\\echo " + COMMIT_MARKER]
    return "\n".join(script) + "\n"


def validate_context(record, expected=None):
    require(record.get("isolation") == "repeatable read" and record.get("read_only") == "on"
            and record.get("statement_timeout") in ("5s", "5000ms"), "INVALID_TRANSACTION")
    require(type(record.get("backend_pid")) is int and record["backend_pid"] > 0
            and isinstance(record.get("snapshot"), str)
            and re.fullmatch(r"\d+:\d+:(?:\d+(?:,\d+)*)?", record["snapshot"]) is not None,
            "INVALID_SNAPSHOT")
    xmin, xmax, active = record["snapshot"].split(":")
    active_ids = [int(value) for value in active.split(",")] if active else []
    require(int(xmin) <= int(xmax) and active_ids == sorted(set(active_ids))
            and all(int(xmin) <= value < int(xmax) for value in active_ids), "INVALID_SNAPSHOT")
    context = {key: record[key] for key in ("backend_pid", "snapshot", "isolation", "read_only", "statement_timeout")}
    if expected is not None:
        require(context == expected, "SNAPSHOT_OR_CONNECTION_CHANGED")
    return context


def safe_partial_timings(stdout):
    """Completed-prefix timings only; never publish partial fingerprints or values."""
    timings = []
    try:
        require(isinstance(stdout, bytes) and len(stdout) <= MAX_OUTPUT_BYTES, "INVALID_PARTIAL_OUTPUT")
        lines = stdout.decode("utf-8").splitlines()
        header = strict_json(lines[0])
        require(isinstance(header, dict) and header.get("kind") == "HEADER", "INVALID_HEADER")
        context = validate_context(header)
        for index, (path, expression) in enumerate(expressions()):
            if 2 + index * 2 >= len(lines): break
            record = strict_json(lines[1 + index * 2])
            require(isinstance(record, dict) and record.get("kind") == "EXPRESSION"
                    and type(record.get("index")) is int and record["index"] == index
                    and record.get("path") == list(path), "EXPRESSION_IDENTITY_MISMATCH")
            validate_context(record, context)
            match = re.fullmatch(r"Time: ([0-9]+(?:\.[0-9]+)?) ms(?: \([^\r\n]+\))?", lines[2 + index * 2])
            require(match is not None, "MISSING_OR_INVALID_TIMING")
            duration = float(match.group(1))
            require(math.isfinite(duration) and 0 <= duration <= STATEMENT_TIMEOUT_MS, "STATEMENT_DEADLINE_EXCEEDED")
            timings.append({"index": index, "path": list(path), "duration_ms": duration,
                            "expression_sha256": hashlib.sha256(expression.encode()).hexdigest(),
                            "validation": "COMPLETED_PREFIX_ONLY_NOT_AUDIT_PASS"})
    except (AuditFailure, UnicodeError, IndexError):
        pass
    return {"completed_expressions": len(timings), "expressions": timings}


def decode_output(stdout, expected):
    require(len(stdout) <= MAX_OUTPUT_BYTES, "OUTPUT_LIMIT_EXCEEDED")
    try: lines = stdout.decode("utf-8").splitlines()
    except UnicodeError as error: raise AuditFailure("MALFORMED_OUTPUT") from error
    require(len(lines) == 2 * EXPRESSION_COUNT + 3, "INCOMPLETE_OR_EXTRA_OUTPUT")
    inventory = expressions()
    paths = [path for path, _ in inventory]
    flatten_expected(expected, paths)
    header = strict_json(lines[0])
    require(isinstance(header, dict) and header.get("kind") == "HEADER", "INVALID_HEADER")
    context = validate_context(header)
    values, timings = {}, []
    for index, (path, expression) in enumerate(inventory):
        record = strict_json(lines[1 + index * 2])
        require(isinstance(record, dict) and record.get("kind") == "EXPRESSION"
                and type(record.get("index")) is int and record["index"] == index
                and record.get("path") == list(path) and "value" in record, "EXPRESSION_IDENTITY_MISMATCH")
        validate_context(record, context)
        require(path not in values, "DUPLICATE_EXPRESSION")
        match = re.fullmatch(r"Time: ([0-9]+(?:\.[0-9]+)?) ms(?: \([^\r\n]+\))?", lines[2 + index * 2])
        require(match is not None, "MISSING_OR_INVALID_TIMING")
        duration = float(match.group(1))
        require(math.isfinite(duration) and 0 <= duration <= STATEMENT_TIMEOUT_MS, "STATEMENT_DEADLINE_EXCEEDED")
        values[path] = record["value"]
        timings.append({"index": index, "path": list(path), "duration_ms": duration,
                        "expression_sha256": hashlib.sha256(expression.encode()).hexdigest(), "validation": "VALID"})
    footer = strict_json(lines[-2])
    require(isinstance(footer, dict) and footer.get("kind") == "FOOTER", "INVALID_FOOTER")
    validate_context(footer, context)
    require(lines[-1] == COMMIT_MARKER, "TRANSACTION_NOT_COMPLETED")
    result = reconstruct(values, paths)
    require(canonical(result) == canonical(expected), "HISTORICAL_FINGERPRINT_MISMATCH")
    return result, context, timings


def terminate_owned(child):
    # Kill only the subprocess/session created here. Never kill a DB backend/session.
    if child.poll() is None:
        if os.name == "posix":
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass
        else: child.kill()
    child.communicate()


def run_checked_audit(command, expected):
    """No result is returned until complete snapshot/commit/equality validation."""
    inventory = expressions()
    flatten_expected(expected, [path for path, _ in inventory])
    started_utc = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    script = build_script().encode()
    start = time.monotonic()
    child = None
    diagnostics = {"stage": "HISTORICAL_DATABASE_AUDIT", "status": "FAIL", "started_utc": started_utc,
                   "sql_sha256": SQL_SHA256, "statement_timeout_ms": STATEMENT_TIMEOUT_MS,
                   "aggregate_budget_ms": TOTAL_BUDGET_SECONDS * 1000, "required_expressions": EXPRESSION_COUNT}
    try:
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 start_new_session=(os.name == "posix"))
        remaining = TOTAL_BUDGET_SECONDS - (time.monotonic() - start)
        require(remaining > 0, "AGGREGATE_DEADLINE_EXCEEDED")
        stdout, stderr = child.communicate(input=script, timeout=remaining)
        elapsed = (time.monotonic() - start) * 1000
        diagnostics["aggregate_elapsed_ms"] = elapsed
        diagnostics.update(safe_partial_timings(stdout))
        require(elapsed <= TOTAL_BUDGET_SECONDS * 1000, "AGGREGATE_DEADLINE_EXCEEDED")
        if child.returncode:
            diagnostics["sqlstates"] = re.findall(rb"(?:ERROR|FATAL):\s*([0-9A-Z]{5})", stderr)
            diagnostics["sqlstates"] = [value.decode("ascii") for value in diagnostics["sqlstates"]]
            code = ("AGGREGATE_DEADLINE_EXCEEDED" if child.returncode == 124 else
                    "STATEMENT_TIMEOUT" if "57014" in diagnostics["sqlstates"] else "AUDIT_SQL_FAILED")
            raise AuditFailure(code)
        result, context, timings = decode_output(stdout, expected)
        diagnostics.update(status="PASS", completed_expressions=len(timings), context=context, expressions=timings,
                           reconstructed_sha256=hashlib.sha256(canonical(result).encode()).hexdigest())
        return {"audit": result, "diagnostics": diagnostics}
    except subprocess.TimeoutExpired as error:
        diagnostics["aggregate_elapsed_ms"] = (time.monotonic() - start) * 1000
        diagnostics.update(safe_partial_timings(error.output or b""))
        diagnostics["failure"] = "AGGREGATE_DEADLINE_EXCEEDED"
        if child is not None: terminate_owned(child)
        raise AuditFailure("AGGREGATE_DEADLINE_EXCEEDED", diagnostics) from error
    except AuditFailure as error:
        if child is not None and child.poll() is None: terminate_owned(child)
        diagnostics.update(error.diagnostics)
        diagnostics["failure"] = error.code
        raise AuditFailure(error.code, diagnostics) from error
    except (OSError, ValueError) as error:
        if child is not None: terminate_owned(child)
        raise AuditFailure("AUDIT_PROCESS_FAILED", diagnostics) from error


def docker_psql_command(container, *, sudo=False, database=None):
    require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}", container) is not None, "INVALID_CONTAINER")
    require(database is None or re.fullmatch(r"[a-zA-Z0-9_]{1,63}", database) is not None, "INVALID_DATABASE")
    db = '"$POSTGRES_DB"' if database is None else database
    shell = ("exec timeout --signal=TERM 30s env LC_ALL=C "
             "PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000' "
             "psql -X -qAt -v ON_ERROR_STOP=1 -v VERBOSITY=sqlstate -P pager=off "
             '-U "$POSTGRES_USER" -d ' + db)
    return (["sudo", "-n"] if sudo else []) + ["docker", "exec", "-i", container, "sh", "-c", shell]


def preflight(command, expected, downstream):
    """Explicit fail-closed seam for operational orchestration; exceptions propagate."""
    evidence = run_checked_audit(command, expected)
    return downstream(evidence)


def publish(path, value):
    require(not path.exists() and not path.is_symlink(), "OUTPUT_ALREADY_EXISTS")
    data = (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-container", required=True)
    parser.add_argument("--database")
    parser.add_argument("--sudo", action="store_true")
    parser.add_argument("--expected-json", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        require(not args.output.exists() and not args.output.is_symlink(), "OUTPUT_ALREADY_EXISTS")
        raw = args.expected_json.read_bytes()
        require(re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256) is not None
                and hashlib.sha256(raw).hexdigest() == args.expected_sha256, "EXPECTED_ARTIFACT_HASH_MISMATCH")
        expected = strict_json(raw)
        result = run_checked_audit(docker_psql_command(args.db_container, sudo=args.sudo, database=args.database), expected)
        publish(args.output, result)
        print(json.dumps({"stage": "HISTORICAL_DATABASE_AUDIT", "status": "PASS",
                          "completed_expressions": EXPRESSION_COUNT,
                          "aggregate_elapsed_ms": result["diagnostics"]["aggregate_elapsed_ms"]}))
        return 0
    except (AuditFailure, OSError) as error:
        code = error.code if isinstance(error, AuditFailure) else "LOCAL_ARTIFACT_FAILED"
        diagnostic = error.diagnostics if isinstance(error, AuditFailure) else {}
        print(json.dumps({**diagnostic, "stage": "HISTORICAL_DATABASE_AUDIT", "status": "FAIL", "failure": code}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

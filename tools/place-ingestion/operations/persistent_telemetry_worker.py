#!/usr/bin/env python3
"""Private operational coordinator, NOT an importer/product worker.

Docker access stays here. The separately launched probe receives only sanitized
files + the persistent container's network namespace: no Docker socket, DB env,
Spring startup, tokens or product mutation capability. Never run on import alone.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from datetime import datetime, timezone

MAIN = "com.emirrkls.phokarta.backend.operations.PlacePilotPersistentTelemetryApplication"
ROUTE = "PRIVATE_PERSISTENT_CONTAINER_NETNS"
OLD_RUN = "d70adea5-6e3f-4c32-92c0-49695eeeb9ce"
MAX_BYTES = 2 * 1024 * 1024
MAX_SECONDS = 65 * 60


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def pairs(values):
    result = {}
    for key, value in values:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def read(path: Path, expected: str | None = None):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
        raise ValueError("private artifact invalid")
    data = path.read_bytes()
    if expected is not None and hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("private artifact digest invalid")
    return json.loads(data, object_pairs_hook=pairs)


def private(directory: Path):
    if not directory.is_absolute() or not directory.is_dir():
        raise ValueError("private directory required")
    if any(p.is_symlink() for p in (directory, *directory.parents)):
        raise ValueError("symlink refused")
    if os.name == "posix" and directory.stat().st_mode & 0o077:
        raise ValueError("private directory mode required")


def publish(path: Path, value, *, heartbeat=False):
    private(path.parent)
    if path.is_symlink() or (path.exists() and not heartbeat):
        raise ValueError("artifact overwrite refused")
    data = (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + "\n").encode()
    temporary = path.with_name("." + path.name + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    if heartbeat:
        os.replace(temporary, path)
    else:
        os.link(temporary, path)  # no replacing a final artifact, including concurrent creators
        temporary.unlink()
    if os.name == "posix":
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    if path.read_bytes() != data:
        raise ValueError("independent read-back mismatch")


def require_host():
    if os.name != "posix" or os.getuid() == 0:
        raise ValueError("non-root Linux coordinator required")


class Coordinator:
    def __init__(self, directory: Path, plan_hash: str, probe_image: str, db_id: str, caddy_id: str, sudo=False):
        private(directory)
        require_host()
        if not re.fullmatch(r"[0-9a-f]{64}", plan_hash) or not re.fullmatch(r"sha256:[0-9a-f]{64}", probe_image):
            raise ValueError("pinned hashes required")
        for identity in (db_id, caddy_id):
            if not re.fullmatch(r"[0-9a-f]{64}", identity):
                raise ValueError("full pinned container IDs required")
        self.directory, self.image = directory, probe_image
        self.plan = read(directory / "V3_OPERATIONS_PLAN.json", plan_hash)
        if self.plan.get("version") != "v3-private-operations-plan-v1" or self.plan.get("validation_method") != "didim-autonomous-validation-v3":
            raise ValueError("v3 plan required")
        if self.plan.get("run_id") == OLD_RUN:
            raise ValueError("historical run refused")
        self.prefix = ["sudo", "-n", "docker"] if sudo else ["docker"]
        self.db_id, self.caddy_id = db_id, caddy_id
        self.deadline = time.monotonic() + MAX_SECONDS

    def command(self, args):
        # All callers below use fixed Docker read-only commands, no shell interpolation.
        result = subprocess.run(self.prefix + args, stdin=subprocess.DEVNULL, capture_output=True, timeout=10, check=True)
        if len(result.stdout) > MAX_BYTES:
            raise ValueError("bounded command output required")
        return result.stdout.decode()

    def observe(self):
        expected = self.plan["target"]
        identity = expected["container_id"]
        if not re.fullmatch(r"[0-9a-f]{64}", identity):
            raise ValueError("backend full ID required")
        # Only these safe fields are projected; full inspect/config/env is NEVER captured.
        fmt = '{"id":{{json .Id}},"image":{{json .Image}},"start":{{json .State.StartedAt}},"restarts":{{.RestartCount}},"oom":{{.State.OOMKilled}},"running":{{.State.Running}},"healthy":{{json .State.Health.Status}},"enabled":{{range .Config.Env}}{{if eq . "PHOKARTA_PLACE_DETAIL_OBSERVABILITY_ENABLED=true"}}true{{end}}{{end}},"threshold":{{range .Config.Env}}{{if eq . "PHOKARTA_PLACE_DETAIL_SLOW_THRESHOLD_MS=350"}}350{{end}}{{end}}}'
        state = json.loads(self.command(["inspect", "--format", fmt, identity]))
        # Static script reads only comm/stat, not cmdline/environ/request payloads.
        script = 'for p in /proc/[0-9]*; do if [ "$(cat "$p/comm" 2>/dev/null)" = java ]; then awk \'{print $1 ":" $22}\' "$p/stat"; fi; done'
        java = self.command(["exec", identity, "/bin/sh", "-c", script]).strip()
        if not re.fullmatch(r"[1-9][0-9]*:[1-9][0-9]*", java):
            raise ValueError("exact single persistent Java identity required")
        db = self.command(["inspect", "--format", '{{.Id}} {{.State.Running}} {{.State.OOMKilled}} {{.State.Health.Status}}', self.db_id]).strip().split()
        caddy = self.command(["inspect", "--format", '{{.Id}} {{.State.Running}} {{.State.OOMKilled}}', self.caddy_id]).strip().split()
        target = {"kind": "LONG_LIVED_PERSISTENT", "origin": "http://127.0.0.1:8080", "route_id": ROUTE,
                  "container_id": state["id"], "image_sha": state["image"], "java_identity": java,
                  "container_started_at": state["start"], "restart_count": state["restarts"], "oom": state["oom"],
                  "backend_healthy": state["running"] and state["healthy"] == "healthy",
                  "database_healthy": db == [self.db_id, "true", "false", "healthy"],
                  "caddy_running": caddy == [self.caddy_id, "true", "false"],
                  "detail_observability_enabled": state["enabled"], "detail_slow_threshold_ms": state["threshold"]}
        if target != expected or state["restarts"] != 0 or state["oom"] or not all(target[k] for k in ("backend_healthy", "database_healthy", "caddy_running", "detail_observability_enabled")) or target["detail_slow_threshold_ms"] != 350:
            raise ValueError("actual target identity/config/health differs from approved pin")
        return {"observed_at": now(), "target": target}

    def heartbeat(self):
        observation = self.observe()
        publish(self.directory / "CURRENT_TARGET.json", observation, heartbeat=True)
        publish(self.directory / "TELEMETRY_WORKER_READY.json",
                {"version": "persistent-telemetry-worker-v1", "run_id": self.plan["run_id"],
                 "manifest_hash": self.plan["manifest_hash"], "observed_at": now()}, heartbeat=True)
        return observation

    def snapshot(self, role):
        before = self.heartbeat()
        plan = {"version": "persistent-probe-plan-v1", "run_id": self.plan["run_id"], "manifest_hash": self.plan["manifest_hash"],
                "role": role, **before}
        plan_path = self.directory / (role + "_PLAN.json")
        publish(plan_path, plan)
        sha = hashlib.sha256(plan_path.read_bytes()).hexdigest()
        # This starts only a plain Java GET helper, never the backend application.
        # Separate UID, read-only rootfs, no environment file, no Docker socket, no datasource secrets.
        args = self.prefix + ["run", "--rm", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--user", f"{os.getuid()}:{os.getgid()}", "--network", "container:" + self.plan["target"]["container_id"],
                "--mount", f"type=bind,src={self.directory},dst=/evidence",
                "--entrypoint", "java", "--env", "JAVA_TOOL_OPTIONS=", self.image,
                "-Dloader.main=" + MAIN, "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher",
                "--plan-path=/evidence/" + plan_path.name, "--plan-sha256=" + sha,
                "--output-path=/evidence/" + role + ".json"]
        with subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) as child:
            try:
                while child.poll() is None:
                    if time.monotonic() >= self.deadline:
                        raise ValueError("bounded worker deadline")
                    self.heartbeat()
                    time.sleep(2)  # Coordinator IPC/health polling, NOT measured-stream spacing/warmup.
            except Exception:
                # Stop ONLY probe scheduling through private IPC. The current GET keeps its 5 s deadline.
                # Never stop/kill/restart a beta container. Partial diagnostic artifact is preserved.
                publish(self.directory / "STOP_PROBE.json", {"version": "persistent-probe-stop-v1", "observed_at": now()})
                child.wait(timeout=10)
                raise
            if child.returncode != 0:
                raise ValueError("GET-only producer hard failure; partial artifact retained")
        after = self.heartbeat()
        artifact = self.directory / (role + ".json")
        document = read(artifact)
        if document.get("outcome") != "COMPLETE" or len(document.get("requests", [])) != 84:
            raise ValueError("incomplete snapshot")
        publish(self.directory / (role + "_RECEIPT.json"),
                {"version": "persistent-telemetry-receipt-v1", "run_id": self.plan["run_id"], "manifest_hash": self.plan["manifest_hash"],
                 "role": role, "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                 "artifact_bytes": artifact.stat().st_size, "before": before, "after": after})

    def run(self):
        self.snapshot("PRE")  # Exactly once; no automatic rebaseline.
        while not (self.directory / "POST_REQUEST.json").exists():
            if time.monotonic() >= self.deadline:
                raise ValueError("bounded worker deadline")
            self.heartbeat()
            time.sleep(2)
        request = read(self.directory / "POST_REQUEST.json")
        if set(request) != {"version", "run_id", "manifest_hash", "requested_at"} or request["version"] != "persistent-post-request-v1" or request["run_id"] != self.plan["run_id"] or request["manifest_hash"] != self.plan["manifest_hash"]:
            raise ValueError("POST request binding invalid")
        self.snapshot("POST")
        # Keep SAME target attestation fresh for the separately authorized product workflow.
        # This coordinator never performs that workflow and never manufactures its PASS receipt.
        while not (self.directory / "PRODUCT_RECEIPT.json").exists():
            if time.monotonic() >= self.deadline:
                raise ValueError("bounded worker deadline")
            self.heartbeat()
            time.sleep(2)
        self.heartbeat()


def main():
    parser = argparse.ArgumentParser(description="Private V3 telemetry coordinator; no importer/product writes")
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--plan-sha256", required=True)
    parser.add_argument("--probe-image-sha256", required=True)
    parser.add_argument("--db-container-id", required=True)
    parser.add_argument("--caddy-container-id", required=True)
    parser.add_argument("--sudo-docker", action="store_true")
    args = parser.parse_args()
    try:
        Coordinator(args.directory, args.plan_sha256, args.probe_image_sha256,
                    args.db_container_id, args.caddy_container_id, args.sudo_docker).run()
        return 0
    except Exception:
        try:
            publish(args.directory / "WORKER_FAILED.json", {"version": "persistent-telemetry-worker-failure-v1", "observed_at": now(), "reason": "HARD_FAILURE"})
        except Exception:
            pass
        print("PERSISTENT_TELEMETRY_WORKER_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

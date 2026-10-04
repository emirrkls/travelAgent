import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location("v3_worker", Path(__file__).parents[1] / "operations" / "persistent_telemetry_worker.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class PersistentTelemetryWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        if os.name == "posix": self.directory.chmod(0o700)
        self.addCleanup(self.temp.cleanup)

    def target(self):
        return {"kind": "LONG_LIVED_PERSISTENT", "origin": "http://127.0.0.1:8080", "route_id": worker.ROUTE,
                "container_id": "1" * 64, "image_sha": "sha256:" + "2" * 64, "java_identity": "7:12345",
                "container_started_at": "2026-09-29T12:00:00Z", "restart_count": 0, "oom": False,
                "backend_healthy": True, "database_healthy": True, "caddy_running": True,
                "detail_observability_enabled": True, "detail_slow_threshold_ms": 350,
                "network_identity":hashlib.sha256(("6"*64).encode()).hexdigest()}

    def policy(self):
        return {"source_sha":"c"*40,"image_sha":"sha256:"+"2"*64,"image_ref":"phokarta-backend:"+"c"*40,
                "origin":"http://127.0.0.1:8080","management_origin":"http://127.0.0.1:8081","route_id":worker.ROUTE,
                "sentinel":"aa000000-0000-4000-8000-000000000001","samples":20,"preconditioning":1,"deadline_ms":5000,
                "detail_slow_threshold_ms":350,"roles":["PRE","POST"]}

    def coordinator(self):
        plan = {"version": "v3-private-operations-plan-v2", "validation_method": "didim-autonomous-validation-v3",
                "run_id": "10000000-0000-4000-8000-000000000099", "manifest_hash": "a" * 64, "target_policy": self.policy()}
        worker.publish(self.directory / "V3_OPERATIONS_PLAN.json", plan)
        digest = hashlib.sha256((self.directory / "V3_OPERATIONS_PLAN.json").read_bytes()).hexdigest()
        health=[{'path':p,'status':200,'validation':'VALID','latency_ms':10} for p in ('/actuator/health/liveness','/actuator/health/readiness')]
        with patch.object(worker, "require_host"),patch.object(worker.Coordinator,"command",side_effect=self.responses()*2),patch.object(worker.Coordinator,'pre_attestation_health',return_value=health):
            return worker.Coordinator(self.directory, digest, "sha256:" + "3" * 64, "4" * 64, "5" * 64, True)

    def responses(self, *, restarts=0):
        return [json.dumps({"id": "1" * 64, "image": "sha256:" + "2" * 64, "start": "2026-09-29T12:00:00Z",
                            "restarts": restarts, "oom": False, "running": True, "healthy": "healthy", "enabled": True, "threshold": 350}),
                "c"*40,"phokarta-backend:"+"c"*40,"6"*64,
                "7:12345\n", "4" * 64 + " true false healthy\n", "5" * 64 + " true false\n"]

    def test_actual_read_only_identity_matches_pin_without_secrets(self):
        coordinator = self.coordinator()
        with patch.object(coordinator, "command", side_effect=self.responses()) as command:
            observed = coordinator.observe()
        self.assertEqual(observed["target"], self.target())
        commands = [call.args[0] for call in command.call_args_list]
        self.assertEqual([cmd[0] for cmd in commands], ["inspect", "exec", "inspect", "inspect", "exec", "inspect", "inspect"])
        self.assertNotIn("{{json .Config.Env}}", repr(commands))  # no complete environment dump; guarded equality projection is safe
        self.assertNotIn("environ", repr(commands))
        self.assertNotIn("cmdline", repr(commands))

    def test_restart_is_not_rebaselined(self):
        coordinator = self.coordinator()
        with patch.object(coordinator, "command", side_effect=self.responses(restarts=1)):
            with self.assertRaises(ValueError): coordinator.observe()

    def test_sealed_policy_has_no_imaginary_container_pid_or_start(self):
        for key in ('container_id','java_identity','container_started_at','network_identity'):
            p=self.policy(); p[key]='imaginary'
            with self.subTest(key=key),self.assertRaises(ValueError): worker.policy(p)

    def test_wrong_release_image_network_or_process_is_hard_failure(self):
        c=self.coordinator()
        for index,value in ((1,'d'*40),(2,'other:image'),(3,'7'*64),(4,'9:99999')):
            responses=self.responses(); responses[index]=value
            with self.subTest(index=index),patch.object(c,'command',side_effect=responses),self.assertRaises(ValueError): c.observe()

    def test_execution_attestation_run_hash_plan_health_receipt_binding(self):
        c=self.coordinator(); good=worker.execution_target(self.directory,c.plan,c.plan_hash)
        self.assertEqual(good['target'],self.target()); worker.health_checks(good['health_checks'])
        for field in ('run_id','manifest_hash','plan_sha256','source_sha','image_ref'):
            a=dict(good); a[field]='wrong'
            (self.directory/'EXECUTION_TARGET.json').unlink(); worker.publish(self.directory/'EXECUTION_TARGET.json',a)
            receipt=worker.read(self.directory/'EXECUTION_TARGET_RECEIPT.json'); receipt['artifact_sha256']=hashlib.sha256((self.directory/'EXECUTION_TARGET.json').read_bytes()).hexdigest(); receipt['artifact_bytes']=(self.directory/'EXECUTION_TARGET.json').stat().st_size
            (self.directory/'EXECUTION_TARGET_RECEIPT.json').unlink(); worker.publish(self.directory/'EXECUTION_TARGET_RECEIPT.json',receipt)
            with self.subTest(field=field),self.assertRaises(ValueError): worker.execution_target(self.directory,c.plan,c.plan_hash)

    def test_no_reattestation_after_pre_or_mutation_to_hide_restart(self):
        c=self.coordinator()
        (self.directory/'EXECUTION_TARGET.json').unlink(); (self.directory/'EXECUTION_TARGET_RECEIPT.json').unlink()
        worker.publish(self.directory/'POST_REQUEST.json',{'fixture':True})
        with patch.object(worker,'require_host'),patch.object(worker.Coordinator,'command') as command,self.assertRaises(ValueError):
            worker.Coordinator(self.directory,c.plan_hash,c.image,c.db_id,c.caddy_id,True)
        command.assert_not_called()

    def test_liveness_readiness_and_full_body_deadline_required(self):
        valid=[{'path':p,'status':200,'validation':'VALID','latency_ms':10} for p in ('/actuator/health/liveness','/actuator/health/readiness')]
        for bad in (valid[:1],[],[dict(valid[0],latency_ms=5000),valid[1]],[dict(valid[0],status=503),valid[1]]):
            with self.subTest(bad=bad),self.assertRaises(ValueError): worker.health_checks(bad)

    def test_unhealthy_db_stops(self):
        coordinator = self.coordinator()
        responses = self.responses(); responses[5] = "4" * 64 + " true false unhealthy"
        with patch.object(coordinator, "command", side_effect=responses):
            with self.assertRaises(ValueError): coordinator.observe()

    def test_wrong_java_identity_stops(self):
        coordinator = self.coordinator()
        responses = self.responses(); responses[4] = "7:98765"
        with patch.object(coordinator, "command", side_effect=responses):
            with self.assertRaises(ValueError): coordinator.observe()

    def test_private_digest_and_duplicate_json_rejected(self):
        path = self.directory / "artifact.json"
        worker.publish(path, {"safe": True})
        with self.assertRaises(ValueError): worker.read(path, "0" * 64)
        with self.assertRaises(ValueError): worker.pairs([("role", "PRE"), ("role", "POST")])

    def test_final_receipt_cannot_be_overwritten(self):
        path = self.directory / "PRE_RECEIPT.json"
        worker.publish(path, {"safe": True})
        with self.assertRaises(ValueError): worker.publish(path, {"safe": False})
        self.assertEqual(worker.read(path), {"safe": True})

    def test_only_heartbeat_files_are_explicitly_replaceable(self):
        path = self.directory / "CURRENT_TARGET.json"
        worker.publish(path, {"count": 1}, heartbeat=True)
        worker.publish(path, {"count": 2}, heartbeat=True)
        self.assertEqual(worker.read(path), {"count": 2})

    def test_helper_has_no_database_credentials_docker_socket_or_spring_startup(self):
        coordinator = self.coordinator()
        child = MagicMock(); child.poll.return_value = 0; child.returncode = 0
        child.__enter__.return_value = child
        def launch(args, **kwargs):
            records = [{}] * 84
            worker.publish(self.directory / "PRE.json", {"outcome": "COMPLETE", "requests": records})
            return child
        observation = {"observed_at": worker.now(), "target": self.target()}
        with patch.object(coordinator, "heartbeat", return_value=observation), patch.object(worker.subprocess, "Popen", side_effect=launch) as popen, patch.object(worker.os, "getuid", return_value=10001, create=True), patch.object(worker.os, "getgid", return_value=10001, create=True):
            coordinator.snapshot("PRE")
        args = popen.call_args.args[0]
        self.assertIn("container:" + "1" * 64, args)
        self.assertIn("--read-only", args); self.assertIn("--cap-drop=ALL", args)
        self.assertIn("-Dloader.main=" + worker.MAIN, args)
        self.assertIn("-Djdk.httpclient.disableRetryConnect=true", args)
        self.assertIn("-Djdk.httpclient.redirects.retrylimit=1", args)
        self.assertIn("-Djdk.httpclient.enableAllMethodRetry=false", args)
        self.assertNotIn("--env-file", args)
        self.assertNotIn("docker.sock", repr(args))
        self.assertNotIn("PHOKARTA_DB", repr(args))
        self.assertNotIn("PlacePilotImportApplication", repr(args))

    def test_failed_producer_never_generates_success_receipt(self):
        coordinator = self.coordinator()
        child = MagicMock(); child.poll.return_value = 1; child.returncode = 1; child.__enter__.return_value = child
        observation = {"observed_at": worker.now(), "target": self.target()}
        with patch.object(coordinator, "heartbeat", return_value=observation), patch.object(worker.subprocess, "Popen", return_value=child), patch.object(worker.os, "getuid", return_value=10001, create=True), patch.object(worker.os, "getgid", return_value=10001, create=True):
            with self.assertRaises(ValueError): coordinator.snapshot("PRE")
        self.assertFalse((self.directory / "PRE_RECEIPT.json").exists())

    def test_no_importer_or_rollback_in_command_helper(self):
        coordinator = self.coordinator()
        self.assertEqual(coordinator.prefix, ["sudo", "-n", "docker"])
        self.assertNotEqual(worker.MAIN, "com.emirrkls.phokarta.backend.operations.PlacePilotRollbackApplication")


if __name__ == "__main__":
    unittest.main()

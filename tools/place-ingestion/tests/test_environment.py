from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from phokarta_place_ingestion.cli import main
from phokarta_place_ingestion.environment import (
    FSQ_TOKEN_VARIABLE,
    fsq_credential_available,
    load_local_environment,
)


class EnvironmentTest(unittest.TestCase):
    def _env_file(self, directory: str, value: str) -> Path:
        path = Path(directory) / ".env.local"
        path.write_text(f"FSQ_PLACES_TOKEN={value}\n", encoding="utf-8")
        return path

    def test_local_file_loads_when_process_value_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            environment: dict[str, str] = {}
            load_local_environment(self._env_file(directory, "local-value"), environment)
            self.assertEqual(environment[FSQ_TOKEN_VARIABLE], "local-value")

    def test_explicit_process_value_wins(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = {FSQ_TOKEN_VARIABLE: "process-value"}
            load_local_environment(self._env_file(directory, "local-value"), environment)
            self.assertEqual(environment[FSQ_TOKEN_VARIABLE], "process-value")

    def test_explicit_blank_process_value_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = {FSQ_TOKEN_VARIABLE: ""}
            load_local_environment(self._env_file(directory, "local-value"), environment)
            self.assertFalse(fsq_credential_available(self._env_file(directory, "local-value"), environment))

    def test_doctor_reports_presence_only(self):
        output = io.StringIO()
        with patch("phokarta_place_ingestion.cli.fsq_credential_available", return_value=True):
            with redirect_stdout(output):
                self.assertEqual(main(["doctor"]), 0)
        self.assertEqual(output.getvalue(), "FSQ credential configured: YES\n")


if __name__ == "__main__":
    unittest.main()

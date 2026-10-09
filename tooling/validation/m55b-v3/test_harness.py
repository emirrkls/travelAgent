"""Small infrastructure checks. NOT substitutes for full-volume rehearsal."""
import ast
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixture


class HarnessTest(unittest.TestCase):
    def test_exact_identity_inventory(self):
        entries = json.loads((HERE / 'canonical_ids.json').read_bytes())
        self.assertEqual(len(entries), 71)
        self.assertEqual(len({x['candidate_id'] for x in entries}), 71)
        ids = sorted(x['canonical_place_id'] for x in entries)
        self.assertEqual(hashlib.sha256('\n'.join(ids).encode()).hexdigest(), fixture.IDENTITY_DIGEST)

    def test_small_synthetic_generation_real_contract(self):
        app = Path(os.environ.get('GITHUB_WORKSPACE', HERE.parents[2])) / 'application'
        if not app.is_dir():
            app = HERE.parents[2]
        with tempfile.TemporaryDirectory(dir=HERE.parent) as temporary:
            info = fixture.generate(app, Path(temporary) / 'sealed', quarantine=2, rejected=2, dual_quarantine=1, padding=0)
            self.assertEqual(info['counts'], {'source_records': 147, 'candidates': 73, 'eligible': 71, 'selected': 71})
            self.assertEqual(info['source_states']['usable'], 145)
            self.assertEqual(info['candidate_decisions']['QUARANTINE'], 2)
            self.assertFalse(info['production_fingerprint_equivalence_claimed'])

    def test_no_live_connection_or_credential_inputs(self):
        code = (HERE / 'linux_rehearsal.py').read_text()
        for forbidden in ['api.phokarta.com', 'ssh ', 'credentials.json', 'authorized_keys', 'curl ', ' --insecure']:
            self.assertNotIn(forbidden, code)
        self.assertIn("'--internal'", code)
        self.assertNotIn('beta', ast.dump(ast.parse(code)).lower())

    def test_unchanged_client_and_bounded_resources(self):
        code = (HERE / 'worker.py').read_text()
        self.assertIn('b1.Transport(ORIGIN)', code)
        self.assertIn('ssl.CERT_REQUIRED', code)
        self.assertNotIn('SENDER =', code)
        self.assertNotIn('CERT_NONE', code)
        self.assertNotIn('check_hostname = False', code)
        orchestration = (HERE / 'linux_rehearsal.py').read_text()
        self.assertIn("'3072m'", orchestration)
        self.assertIn("'5376m'", orchestration)
        self.assertIn("'-Xmx2048m'", orchestration)
        self.assertIn('audit.preflight(', orchestration)
        self.assertNotIn('build_script =', orchestration)


if __name__ == '__main__':
    unittest.main()

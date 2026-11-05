"""Pin-guard behavior when the oldest-platform gate cannot read the channel."""

import lzma
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
PIN = "27.0.2.v202609041212"
CORE = "com._1c.g5.v8.dt.core"
BASH = shutil.which("bash")

# Keep the real fetch/decode/guard path; replace only the network transport.
PROBE = r'''
curl() {
  local index output
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -o) output="$2"; shift ;;
      *content.xml.xz) index=content ;;
      *artifacts.xml.xz) index=artifacts ;;
    esac
    shift
  done
  [ -f "$EDT_PIN_FIXTURES/$index.xml.xz" ] || return 22
  cp "$EDT_PIN_FIXTURES/$index.xml.xz" "$output"
}
export -f curl
bash .github/scripts/edt-pin.sh 2026.1 https://edt.invalid/ruby/2026.1/
'''


@unittest.skipUnless(BASH, "The pin guard requires bash")
class EdtPinTest(unittest.TestCase):
    def probe(self, *, strict=True, content=True, artifacts=True, version=PIN,
              stored_version=PIN):
        scratch = ROOT / "tmp"
        scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            fixtures = Path(directory)
            indexes = {
                "content": (content, '<repository><units><unit id="' + CORE
                            + '" version="' + version + '"><artifacts><artifact '
                            + 'classifier="osgi.bundle" id="' + CORE + '" version="'
                            + version + '"/></artifacts></unit></units></repository>'),
                "artifacts": (artifacts, '<repository><artifacts><artifact '
                              + 'classifier="osgi.bundle" id="' + CORE + '" version="'
                              + stored_version + '"/></artifacts></repository>'),
            }
            for name, (present, xml) in indexes.items():
                if present:
                    (fixtures / (name + ".xml.xz")).write_bytes(lzma.compress(xml.encode()))
            env = dict(os.environ, EDT_PIN_FIXTURES=fixtures.relative_to(ROOT).as_posix())
            env.pop("EDT_PIN_REQUIRE_READABLE", None)
            if strict:
                env["EDT_PIN_REQUIRE_READABLE"] = "1"
            return subprocess.run([BASH, "-c", PROBE], cwd=ROOT, env=env,
                                  capture_output=True, text=True, timeout=30)

    def test_strict_mode_accepts_matching_readable_indexes(self):
        result = self.probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, PIN + "\n")
        self.assertIn("OK: channel 2026.1 matches", result.stderr)

    def test_strict_mode_refuses_unreadable_indexes_without_a_fallback_pin(self):
        for content, artifacts in ((False, False), (False, True), (True, False)):
            with self.subTest(content=content, artifacts=artifacts):
                result = self.probe(content=content, artifacts=artifacts)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertIn("channel indexes could not be read", result.stderr)
                self.assertIn("refusing to validate a cached installation", result.stderr)

    def test_default_mode_keeps_the_existing_network_fallback(self):
        result = self.probe(strict=False, content=False, artifacts=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, PIN + "\n")
        self.assertIn("skipping the drift check", result.stderr)

    def test_strict_mode_keeps_the_drift_and_artifact_guards(self):
        drift = self.probe(version="27.0.2.v999999999999", stored_version="27.0.2.v999999999999")
        self.assertEqual(drift.returncode, 1, drift.stderr)
        self.assertIn("but CI is pinned to", drift.stderr)
        inconsistent = self.probe(stored_version="27.0.2.v000000000000")
        self.assertEqual(inconsistent.returncode, 1, inconsistent.stderr)
        self.assertIn("channel is INCONSISTENT", inconsistent.stderr)

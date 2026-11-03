"""CI contracts for installing and caching the route-map serializer feature.

Runtime behavior and guide/docs contracts are covered by the JUnit ratchets.
Run with: python -m unittest discover -s tests/e2e/runner_tests -p test_route_map_contract.py
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]
IU = "com._1c.g5.v8.dt.bp.scheme.feature.feature.group"


class RouteMapIntegrationContractTest(unittest.TestCase):
    def require(self, needle, text):
        self.assertTrue(needle in text, "Missing contract: " + needle)

    def test_e2e_installs_and_keys_the_serializer_feature(self):
        text = (ROOT / ".github/workflows/e2e-tests.yml").read_text(encoding="utf-8")
        self.require('BP_SCHEME_IU: "' + IU + '"', text)
        cache = text.split("H=$(printf", 1)[1].split("| sha256sum", 1)[0]
        self.require('"$BP_SCHEME_IU"', cache)
        self.require('IUS="$IUS,$BP_SCHEME_IU"', text)
        self.assertEqual(len(re.findall(r"%s", cache)), len(re.findall(r'"\$[A-Z][A-Z0-9_]*"', cache)))

    def test_shared_setup_installs_and_keys_the_serializer_feature(self):
        text = (ROOT / ".github/actions/setup-edt/action.yml").read_text(encoding="utf-8")
        self.assertRegex(text, r'bp-scheme-iu:\s+description:[^\n]+\s+required: false\s+default: "' + re.escape(IU) + '"')
        cache = text.split("H=$(printf", 1)[1].split("| sha256sum", 1)[0]
        self.require('"${{ inputs.bp-scheme-iu }}"', cache)
        self.require('IUS="$IUS,${{ inputs.bp-scheme-iu }}"', text)
        self.assertEqual(len(re.findall(r"%s", cache)), len(re.findall(r'"(?:\$\{\{[^\n]+|\$EDT_BUILD)"', cache)))

if __name__ == "__main__":
    unittest.main()

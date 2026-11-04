"""Contracts of the Linux headless acceptance driver; no EDT is launched here."""

import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stdio_headless as S


class HeadlessDriverTest(unittest.TestCase):
    def test_launch_selects_our_application_and_suppresses_http_without_xvfb(self):
        with tempfile.TemporaryDirectory() as directory:
            install = Path(directory)
            (install / "plugins").mkdir()
            (install / "plugins/org.eclipse.equinox.launcher_1.jar").touch()
            command = S.launch_command(Path("java"), install, install / "workspace")
            self.assertEqual("com.ditrix.edt.mcp.server.stdioHost", command[command.index("-application") + 1])
            self.assertIn("-Dedt.mcp.stdioOnly=true", command)
            self.assertIn("-Dedt.mcp.headlessIdleMinutes=1", command)
            self.assertNotIn("xvfb-run", command)
            self.assertNotIn("org.eclipse.ui.ide.workbench", command)

    def test_host_has_no_display_or_consent_bypass_and_uses_an_isolated_project(self):
        with patch.dict(os.environ, {"DISPLAY": ":99", "WAYLAND_DISPLAY": "wayland-0",
                                    "EDT_MCP_DESTRUCTIVE_CONSENT": "allow"}):
            environment = S.host_environment(Path("isolated-project"))
            self.assertNotIn("DISPLAY", environment)
            self.assertNotIn("WAYLAND_DISPLAY", environment)
            self.assertNotIn("EDT_MCP_DESTRUCTIVE_CONSENT", environment)
            self.assertEqual("isolated-project", environment["EDT_MCP_IMPORT_PROJECTS"])
            self.assertEqual("true", environment["EDT_MCP_AUTO_START"])


if __name__ == "__main__":
    unittest.main()

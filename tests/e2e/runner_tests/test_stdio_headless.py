"""Contracts of the Linux headless acceptance driver; no EDT is launched here."""

import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import json
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stdio_headless as S
import read_host_contracts as C


class HeadlessDriverTest(unittest.TestCase):
    def test_driver_calls_all_migrated_contracts_through_the_bootstrap(self):
        calls = []
        cases = C.baseline_cases()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            install = root / "install"
            (install / "plugins").mkdir(parents=True)
            (install / "plugins/org.eclipse.equinox.launcher_1.jar").touch()

            class Client:
                def __init__(self, command):
                    if "-headless" not in command:
                        raise AssertionError("Missing explicit headless client selection")

                def initialize(self):
                    pass

                def close(self):
                    pass

                def request(self, method, params=None):
                    if method == "tools/list":
                        return {"result": {"tools": [{"name": name} for name in C.MOVED_TOOLS]}}
                    name, arguments = params["name"], params["arguments"]
                    calls.append((name, arguments))
                    if name == "get_server_status":
                        result = {"structuredContent": {"hostKind": "HEADLESS", "transports": {"http": False, "ipc": True}}}
                    elif name == "list_projects":
                        result = {"structuredContent": {"projects": [{"name": project, "state": "ready"}
                                  for project in ("SupportedConfiguration", "TestConfiguration")]}}
                    elif name == "list_common_pictures":
                        result = {"content": [{"text": "No common pictures found."}]}
                    elif name == "delete_project":
                        result = {"isError": True, "content": [{"text": "Remove -headless"}]}
                    elif name == "list_modules" and arguments == {"projectName": "TestConfiguration"}:
                        result = {"content": [{"text": "CommonModules/Calc/Module.bsl\n"
                                  "CommonModules/Вычисление/Module.bsl\nConfiguration/ManagedApplicationModule.bsl"}]}
                    else:
                        matches = [case for case in cases.values() if case["tool"] == name and case["arguments"] == arguments]
                        if len(matches) != 1:
                            raise AssertionError("Unexpected read request")
                        text = matches[0]["response"]["text"]
                        if name == "read_module_source":
                            text = text.partition("\n## Extension interception\n")[0]
                        result = {"content": [{"text": text}], "isError": False}
                    return {"result": result}

            class Host:
                def poll(self):
                    return None

                def wait(self, timeout):
                    metadata = root / "stdio-headless/workspace/.metadata"
                    metadata.mkdir()
                    (metadata / ".log").write_text("EDT MCP headless workspace saved", encoding="utf-8")
                    return 0

            def copy_fixture(source, destination):
                destination.mkdir()

            with patch.dict(os.environ, {"EDT_MCP_EDT_INSTALL": str(install), "JAVA_HOME": str(root),
                                         "RUNNER_TEMP": str(root)}), \
                 patch.object(S, "os", SimpleNamespace(name="posix", environ=os.environ, pathsep=os.pathsep)), \
                 patch.object(S.shutil, "copytree", side_effect=copy_fixture), \
                 patch.object(S, "discover", return_value=(root / "bootstrap.jar", "identity")), \
                 patch.object(S, "StdioClient", Client), \
                 patch.object(S.subprocess, "Popen", return_value=Host()) as popen, \
                 patch("builtins.print"):
                S.main()
            environment = popen.call_args.kwargs["env"]
            self.assertEqual(2, len(environment["EDT_MCP_IMPORT_PROJECTS"].split(os.pathsep)))
            reads = [(name, arguments) for name, arguments in calls if name in (*C.MOVED_TOOLS, "read_module_source")]
            self.assertEqual(17, len(reads))
            self.assertIn("Вычисление", json.dumps(reads, ensure_ascii=False))

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

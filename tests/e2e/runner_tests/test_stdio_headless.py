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
import headless_development_contracts as D
import harness as H


class HeadlessDriverTest(unittest.TestCase):
    def test_auxiliary_driver_waits_for_native_effects_and_checks_deleted_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project, workspace = root / "project", root / "workspace"
            settings = project / ".settings"
            settings.mkdir(parents=True)
            workspace.mkdir()
            pending = []
            ready = {"TestConfiguration": True}
            deleted = []
            def invoke(tool, arguments):
                if tool == "project_checks":
                    name = arguments["profile"]
                    if arguments["action"] == "createProfile":
                        (settings / (name + ".cset")).write_text("{}", encoding="utf-8")
                    else:
                        pending.append(name)
                elif tool == "merge_rules":
                    Path(arguments["filePath"]).write_text("GetFromOther", encoding="utf-8")
                elif tool == "create_project":
                    name = arguments["name"]
                    (workspace / name).mkdir()
                    ready[name] = False
                elif tool == "list_projects":
                    rows = [{"name": name, "state": "ready" if done else "building"}
                            for name, done in ready.items()]
                    return H.Result({"result": {"structuredContent": {"projects": rows}}})
                elif tool == "delete_project":
                    name = arguments["projectName"]
                    self.assertTrue(ready[name], "deletion must wait for the build")
                    if arguments.get("confirm"):
                        self.assertTrue(arguments["deleteContent"])
                        (workspace / name).rmdir()
                        deleted.append(name)
                elif tool != "git":
                    self.fail("Unexpected tool " + tool)
                return H.Result({"result": {"structuredContent": {"success": True}}})
            def finish_background_work(_):
                if pending:
                    encoded = "".join(c if ord(c) < 128 else "\\u%04X" % ord(c) for c in pending.pop())
                    (settings / "com.e1c.g5.v8.dt.check.prefs").write_text(
                        "activeProfile=" + encoded + "\n", encoding="utf-8")
                ready.update({name: True for name in ready})
            with patch.object(H.time, "sleep", side_effect=finish_background_work) as wait, \
                 patch.object(D.subprocess, "run"):
                D.run_auxiliary_writes(invoke, project, workspace, root)
            self.assertEqual(4, wait.call_count)
            self.assertEqual(["HeadlessDeleteProject", "УдаляемыйПроект"], deleted)
            self.assertEqual([], list(workspace.iterdir()))

    def test_writes_wait_for_observed_ready_without_retrying_the_mutation(self):
        for name in D.READY_WRITES:
            calls = []
            def invoke(tool, arguments):
                calls.append(tool)
                if tool == "list_projects":
                    state = "building" if len(calls) == 1 else "ready"
                    return H.Result({"result": {"structuredContent": {"projects": [
                        {"name": "Disposable", "state": state}]}}})
                return H.Result({"result": {"structuredContent": {"success": True}}})
            with patch.object(H.time, "sleep") as wait:
                D._ok(invoke, name, {"projectName": "Disposable"})
            self.assertEqual(["list_projects", "list_projects", name], calls)
            self.assertEqual(1, wait.call_count)

    def test_unready_or_missing_project_never_reaches_the_mutation(self):
        for state in ("building", None):
            calls = []
            def invoke(tool, arguments):
                calls.append(tool)
                rows = [] if state is None else [{"name": "Disposable", "state": state}]
                return H.Result({"result": {"structuredContent": {"projects": rows}}})
            with patch.object(H.time, "time", side_effect=[0, 181]), patch.object(H.time, "sleep"):
                with self.assertRaisesRegex(AssertionError, "requires a ready project"):
                    D._ok(invoke, "delete_project", {"projectName": "Disposable", "confirm": True})
            self.assertEqual(["list_projects"], calls)

    def test_real_mutation_failure_is_never_retried(self):
        calls = []
        def invoke(tool, arguments):
            calls.append(tool)
            if tool == "list_projects":
                return H.Result({"result": {"structuredContent": {"projects": [
                    {"name": "Disposable", "state": "ready"}]}}})
            return H.Result({"result": {"isError": True, "structuredContent": {"error": "disk failure"}}})
        with self.assertRaisesRegex(H.E2EAssertion, "disk failure"):
            D._ok(invoke, "create_metadata", {"projectName": "Disposable"})
        self.assertEqual(["list_projects", "create_metadata"], calls)

    def test_failed_call_keeps_structured_error_and_all_content_text(self):
        response = {"result": {"isError": True, "structuredContent": {"error": "model is building"},
            "content": [{"text": "Wait for project readiness"}, {"text": "Additional platform detail"}]}}
        client = SimpleNamespace(request=lambda *args: response)
        for invoke in (lambda: S.call(client, "create_metadata", {}),
                       lambda: D._ok(lambda *args: H.Result(response), "create_metadata", {})):
            with self.assertRaises((AssertionError, H.E2EAssertion)) as raised:
                invoke()
            for text in ("create_metadata", "model is building", "Wait for project readiness",
                         "Additional platform detail"):
                self.assertIn(text, str(raised.exception))

    def test_rpc_error_is_visible_and_success_is_unchanged(self):
        client = SimpleNamespace(request=lambda *args: {"error": {"code": -32602, "message": "bad argument"}})
        with self.assertRaisesRegex(AssertionError, "bad argument"):
            S.call(client, "delete_project", {})
        success = {"isError": False, "structuredContent": {"success": True}}
        client.request = lambda *args: {"result": success}
        self.assertIs(success, S.call(client, "delete_project", {}))

    def test_profile_wait_observes_delayed_disk_flush_in_both_languages(self):
        for name in ("HeadlessProfile", "ПрофильБезОкна"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                prefs = Path(directory) / "checks.prefs"
                def flush(_):
                    encoded = "".join(c if ord(c) < 128 else "\\u%04X" % ord(c) for c in name)
                    prefs.write_text("activeProfile=" + encoded + "\n", encoding="utf-8")
                with patch.object(H.time, "sleep", side_effect=flush) as wait:
                    D._wait_for_active_profile(prefs, name)
                self.assertEqual(1, wait.call_count)
                self.assertTrue(prefs.is_file())

    def test_profile_wait_rejects_missing_stale_or_prefix_only_disk_values(self):
        for value in (None, "OldProfile", "HeadlessProfileOther"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                prefs = Path(directory) / "checks.prefs"
                if value is not None:
                    prefs.write_text("activeProfile=" + value + "\n", encoding="utf-8")
                with patch.object(H.time, "time", side_effect=[0, 0, 100]), patch.object(H.time, "sleep"):
                    with self.assertRaisesRegex(H.E2EAssertion, "activeProfile must persist as HeadlessProfile"):
                        D._wait_for_active_profile(prefs, "HeadlessProfile", timeout=1)

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
                    self.status_requests = 0

                def initialize(self):
                    pass

                def close(self):
                    pass

                def request(self, method, params=None):
                    if method == "tools/list":
                        return {"result": {"tools": [{"name": name} for name in (*C.MOVED_TOOLS, *S.READ_TOOLS, *S.WRITE_TOOLS)]}}
                    name, arguments = params["name"], params["arguments"]
                    calls.append((name, arguments))
                    if name == "get_server_status":
                        self.status_requests += 1
                        if self.status_requests == 1:
                            return {"result": {"isError": True, "content": [{"type": "text", "text": json.dumps({
                                "success": False, "error": "EDT is connecting. Retry the same call in about 2 seconds. No tool ran."})}]}}
                        result = {"structuredContent": {"hostKind": "HEADLESS", "transports": {"http": False, "ipc": True}}}
                    elif name == "list_projects":
                        result = {"structuredContent": {"projects": [{"name": project, "state": "ready"}
                                  for project in ("SupportedConfiguration", "TestConfiguration", "TestConfiguration.tests")]}}
                    elif name == "list_common_pictures":
                        result = {"content": [{"text": "No common pictures found."}]}
                    elif name == "open_editor":
                        result = {"isError": True, "content": [{"text": "Remove -headless"}]}
                    elif name == "list_modules" and arguments == {"projectName": "TestConfiguration"}:
                        result = {"content": [{"text": "CommonModules/Calc/Module.bsl\n"
                                  "CommonModules/Вычисление/Module.bsl\nConfiguration/ManagedApplicationModule.bsl"}]}
                    else:
                        matches = [case for case in cases.values() if case["tool"] == name and case["arguments"] == arguments]
                        if len(matches) != 1:
                            raise AssertionError("Unexpected read request")
                        if name == "get_symbol_info":
                            matches = [case for case in json.loads(C.HEADLESS_BASELINE.read_text(encoding="utf-8"))["cases"].values() if case["arguments"] == arguments]
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
                 patch.object(S, "run_metadata_writes"), \
                 patch.object(S, "prepare_quick_fix_fixture"), \
                 patch.object(S, "run_quick_fix"), \
                 patch.object(S, "run_adoption"), \
                 patch.object(S, "run_auxiliary_writes"), \
                 patch.object(S, "run_registration_writes") as registrations, \
                 patch.object(S, "run_runtime_preconditions") as runtime, \
                 patch.object(S, "seed_and_read_modules"), \
                 patch.object(S, "run_designer_writes"), \
                 patch.object(S, "run_additional_reads"), \
                 patch("builtins.print"):
                S.main()
            environment = popen.call_args.kwargs["env"]
            self.assertEqual(3, len(environment["EDT_MCP_IMPORT_PROJECTS"].split(os.pathsep)))
            self.assertEqual(2, sum(name == "get_server_status" for name, _ in calls))
            registrations.assert_called_once()
            runtime.assert_called_once()
            reads = [(name, arguments) for name, arguments in calls if name in (*C.MOVED_TOOLS, "read_module_source")]
            self.assertEqual(23, len(reads))
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

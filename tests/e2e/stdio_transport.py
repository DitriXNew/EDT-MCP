"""Live attach-only stdio acceptance against the same EDT used by the HTTP suite."""

from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path

import harness as H
from stdio_client import StdioClient, discover
from read_host_contracts import run_contracts


def checked(client, name, arguments, identity):
    raw = client.request("tools/call", {"name": name, "arguments": arguments}, identity=identity)
    result = H.Result(raw)
    if result.rpc_error:
        raise AssertionError("Stdio tools/call returned a JSON-RPC error: " + name)
    H.assert_ok(result, "stdio " + name)
    H._validate_tool_output(name, result)
    return result


def main():
    workspace = Path(os.environ["EDT_MCP_EDT_WORKSPACE"]).resolve(strict=True)
    install = Path(os.environ["EDT_MCP_EDT_INSTALL"]).resolve(strict=True)
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file():
        raise AssertionError("JAVA_HOME must select Java 17 or newer")
    H.initialize()
    if not H.wait_for_project_ready():
        raise AssertionError("EDT projects are not ready for stdio acceptance")
    http_tools = H._post("tools/list", {}).get("result", {}).get("tools", [])
    jar, original = discover(workspace)
    command = [str(java), "-jar", str(jar), "-install", str(install), "-data", str(workspace)]
    first, second = StdioClient(command), StdioClient(command)
    try:
        first.initialize()
        second.initialize({"experimental": {"structuredContent": False}})
        offline = first.request("tools/list", identity="offline")["result"]["tools"]
        assert offline, "Bundled catalog is empty"
        projects = checked(first, "list_projects", {"format": "json"}, "first-call")
        assert workspace == Path(projects.structured["workspacePath"]).resolve(strict=True), "Attached to the wrong workspace"
        assert H.PROJECT in [p["name"] for p in projects.structured["projects"]], "Fixture project missing"
        live = first.request("tools/list", identity="live")["result"]["tools"]
        assert sorted(t["name"] for t in live) == sorted(t["name"] for t in http_tools), "HTTP/stdio catalog mismatch"
        assert "notifications/tools/list_changed" in first.notifications, "No live catalog notification"
        modules = checked(first, "list_modules", {"projectName": H.PROJECT, "metadataType": "commonModules", "nameFilter": "Вычисление"}, "cyrillic")
        assert "Вычисление" in (modules.text + str(modules.structured)), "Fixture Cyrillic module was corrupted or missing"
        run_contracts(lambda name, arguments: checked(first, name, arguments, "formatter"), "WINDOWED")
        with ThreadPoolExecutor(max_workers=2) as executor:
            a = executor.submit(checked, first, "get_server_status", {}, "overlap-Ё")
            b = executor.submit(checked, second, "get_server_status", {}, "overlap-Ё")
            structured, text_only = a.result(), b.result()
        assert structured.structured["transports"] == {"http": True, "ipc": True}
        assert "structuredContent" not in text_only.result, "Capabilities leaked between connections"
        assert json.loads(text_only.text)["transports"] == {"http": True, "ipc": True}, "Text-only client lost its response"
        second.close()
        second = None
        checked(first, "list_projects", {"format": "json"}, "after-peer-exit")
    finally:
        try:
            if second is not None:
                second.close()
        finally:
            first.close()
    H.assert_ok(H.call("list_projects", {"format": "json"}), "HTTP after both stdio clients exit")
    assert discover(workspace)[1] == original, "EDT identity changed after stdin closed"
    H.assert_no_diff("stdio transport acceptance must not change fixture projects")
    print("stdio: initialize, live catalog (%d tools), first-call attach, Cyrillic, concurrent isolation, pure stdout and EOF lifetime PASS" % len(live))


if __name__ == "__main__":
    main()

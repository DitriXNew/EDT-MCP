"""Display-free host acceptance. Run only on a provisioned Linux EDT runner."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from stdio_client import StdioClient, discover


APPLICATION = "com.ditrix.edt.mcp.server.stdioHost"


def launch_command(java, install, workspace):
    launchers = list((install / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    if len(launchers) != 1:
        raise AssertionError("Expected one installed Equinox launcher")
    return [str(java), "-Xmx4g", "-Djava.awt.headless=true", "-Dedt.mcp.stdioOnly=true",
            "-Dedt.mcp.headlessIdleMinutes=1", "-Dorg.osgi.framework.bundle.parent=ext",
            "-Dosgi.module.lock.timeout=240", "--add-modules=ALL-SYSTEM",
            *["--add-opens=java.base/" + package + "=ALL-UNNAMED" for package in
              ("java.lang", "java.lang.constant", "java.lang.ref", "java.nio", "java.time", "sun.nio.ch", "java.net")],
            "-jar", str(launchers[0]), "-application", APPLICATION, "-data", str(workspace),
            "-install", str(install), "-nosplash", "-consoleLog"]


def host_environment(project):
    environment = os.environ.copy()
    for name in ("DISPLAY", "WAYLAND_DISPLAY", "EDT_MCP_DESTRUCTIVE_CONSENT"):
        environment.pop(name, None)
    environment["EDT_MCP_IMPORT_PROJECTS"] = str(project)
    # Prove stdioOnly wins even over the explicit HTTP override.
    environment["EDT_MCP_AUTO_START"] = "true"
    return environment


def call(client, name, arguments):
    response = client.request("tools/call", {"name": name, "arguments": arguments})
    if "error" in response or response.get("result", {}).get("isError"):
        raise AssertionError("Headless call failed: " + name)
    return response["result"]


def main():
    if os.name != "posix":
        raise AssertionError("This acceptance starts a Linux host directly; Windows cold start is a separate live check")
    install = Path(os.environ["EDT_MCP_EDT_INSTALL"]).resolve(strict=True)
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    root = Path(os.environ["RUNNER_TEMP"]) / "stdio-headless"
    root.mkdir(exist_ok=False)
    workspace = root / "workspace"
    workspace.mkdir()
    project = root / "project"
    shutil.copytree(Path(__file__).resolve().parents[1] / "SupportedConfiguration", project)
    log = root / "host.log"
    with log.open("wb") as output:
        host = subprocess.Popen(launch_command(java, install, workspace), env=host_environment(project),
                                stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
        client = None
        try:
            deadline = time.monotonic() + int(os.environ.get("E2E_PROJECT_READY_TIMEOUT", "1200"))
            while True:
                if host.poll() is not None:
                    raise AssertionError("Headless host exited before IPC readiness; inspect " + str(log))
                try:
                    jar, identity = discover(workspace)
                    break
                except (AssertionError, OSError):
                    if time.monotonic() >= deadline:
                        raise AssertionError("Headless host did not publish IPC; inspect " + str(log)) from None
                    time.sleep(1)
            client = StdioClient([str(java), "-jar", str(jar), "-install", str(install),
                                  "-data", str(workspace), "-headless"])
            client.initialize()
            offline = client.request("tools/list")["result"]["tools"]
            assert "delete_project" not in {tool["name"] for tool in offline}
            status = call(client, "get_server_status", {})["structuredContent"]
            assert status["hostKind"] == "HEADLESS"
            assert status["transports"] == {"http": False, "ipc": True}
            live = client.request("tools/list")["result"]["tools"]
            assert "get_metadata_objects" not in {tool["name"] for tool in live}
            while True:
                projects = call(client, "list_projects", {"format": "json"})["structuredContent"]["projects"]
                if any(p["name"] == "SupportedConfiguration" and p["state"] == "ready" for p in projects):
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError("Headless project context did not become ready")
                time.sleep(2)
            read = call(client, "list_common_pictures", {"projectName": "SupportedConfiguration"})
            content = read.get("content", [])
            rendered = "\n".join(part.get("text", part.get("resource", {}).get("text", "")) for part in content)
            assert "No common pictures found." in rendered, "Unexpected BM result for the small fixture"
            refused = client.request("tools/call", {"name": "delete_project", "arguments": {}})["result"]
            assert refused["isError"] is True and "Remove -headless" in json.dumps(refused)
            client.close()
            client = None
            assert host.poll() is None, "EOF must leave the shared host running until idle expiry"
            assert discover(workspace)[1] == identity
            assert host.wait(timeout=150) == 0, "Idle shutdown must be clean"
            workspace_log = (workspace / ".metadata/.log").read_text(encoding="utf-8", errors="replace")
            assert "EDT MCP headless workspace saved" in workspace_log
            assert "EDT MCP headless host failed" not in workspace_log
            assert "Failed to auto-start MCP Server" not in workspace_log
            assert "MCP Server auto-started on port" not in workspace_log
            print("stdio headless: no display, imported project, real BM read through bootstrap, filtered catalog, refusal, pure stdout, idle save PASS")
        finally:
            if client is not None:
                client.close()
            # Idle expiry owns host shutdown, including on a failed acceptance run.


if __name__ == "__main__":
    main()

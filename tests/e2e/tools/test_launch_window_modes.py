"""Live client window modes; each test owns and terminates its throwaway launch."""

import ctypes
import os
import platform
import shutil
import subprocess
import time

from harness import (
    PROJECT, E2ESkip, assert_error, assert_error_quality, assert_no_diff, assert_ok,
    call, calls_aborted, e2e_test, requires_live_infobase, wait_until_no_running_launch,
)


@e2e_test(tool="launch", kind="read")
def test_window_mode_validation_before_launch():
    error = assert_error(call("launch", {"windowMode": "hidden"}))
    assert_error_quality(error, names=["hidden"], suggests=["isolated"])
    error = assert_error(call("launch", {"windowMode": "otherScreen", "screen": 1.5}))
    assert_error_quality(error, names=["screen"], suggests=["integer"])
    error = assert_error(call("launch", {"windowMode": "isolated", "screen": 2}))
    assert_error_quality(error, names=["screen"], suggests=["otherScreen"])
    assert_no_diff("window validation must precede application preparation")


def _platform_ready(mode):
    requires_live_infobase("launches a real 1C client to verify window modes")
    system = platform.system()
    if system not in ("Windows", "Linux", "Darwin"):
        raise E2ESkip("window modes are unsupported on " + system)
    if system == "Linux":
        tools = ("Xvfb", "setsid", "kill") if mode == "isolated" else ("xdotool", "wmctrl", "xprop")
        missing = [tool for tool in tools if shutil.which(tool) is None]
        if missing:
            raise E2ESkip("missing window-mode packages: " + ", ".join(missing))
        if mode != "isolated" and not os.environ.get("DISPLAY"):
            raise E2ESkip("an X11 DISPLAY is required for window adjustment")
    if mode != "otherScreen":
        return
    if system == "Windows":
        count = ctypes.windll.user32.GetSystemMetrics(80)
    elif system == "Linux":
        if shutil.which("xrandr") is None:
            raise E2ESkip("otherScreen requires the xrandr package")
        result = subprocess.run(["xrandr", "--listmonitors"], capture_output=True, text=True, timeout=5, check=True)
        count = int(result.stdout.splitlines()[0].split(":")[1].strip())
    else:
        result = subprocess.run(["osascript", "-l", "JavaScript", "-e",
                                 "ObjC.import('AppKit'); $.NSScreen.screens.count"],
                                capture_output=True, text=True, timeout=5, check=True)
        count = int(result.stdout.strip())
    if count < 2:
        raise E2ESkip("otherScreen requires two connected monitors; found " + str(count))


def _round_trip(mode, launch_mode="run"):
    _platform_ready(mode)
    name = "e2e_window_mode_" + mode + ("_debug" if launch_mode == "debug" else "")
    assert_ok(call("create_launch_config", {"projectName": PROJECT, "name": name}), "create window-mode fixture")
    try:
        try:
            launched = call("launch", {"launchConfigurationName": name, "mode": launch_mode,
                                       "windowMode": mode, "restartIfRunning": True})
            assert_ok(launched, "launch " + mode)
            result = launched.structured
            assert result.get("windowMode") == mode, result
            assert result.get("windowModeApplied") is True, result
            assert isinstance(result.get("pid"), int) and result["pid"] > 0, result
            status = call("debug_status", {"applicationId": result["applicationId"]})
            assert_ok(status, "inspect window-mode launch")
            matches = [launch for launch in status.structured.get("launches", [])
                       if launch.get("launchConfiguration") == name]
            assert len(matches) == 1, status.structured
            assert matches[0].get("windowMode") == mode, matches[0]
            assert matches[0].get("windowModeApplied") is True, matches[0]
            assert matches[0].get("pid") == result["pid"], matches[0]
            assert matches[0].get("state") == "running", matches[0]
            if mode == "isolated":
                assert "windowListingAvailable" in matches[0], matches[0]
                assert "asynchronously" not in result.get("message", ""), result
            if launch_mode == "debug":
                # The client registers its threads with the debug server it was pointed at.
                deadline = time.time() + 60
                threads = 0
                while time.time() < deadline and threads == 0:
                    status = call("debug_status", {"applicationId": result["applicationId"]})
                    assert_ok(status, "inspect the isolated debug session")
                    threads = max([launch.get("threadCount", 0) for launch in status.structured.get("launches", [])
                                   if launch.get("launchConfiguration") == name] or [0])
                    if threads == 0:
                        time.sleep(2)
                assert threads > 0, "the isolated client never attached to the debug server: %r" % status.structured
                if platform.system() == "Darwin":
                    assert "hidden" in result.get("windowModeReason", ""), result
        finally:
            if not calls_aborted():
                assert_ok(call("terminate_launch", {"launchConfigurationName": name, "force": True}),
                          "terminate window-mode fixture")
                assert wait_until_no_running_launch(name), "the client survived terminate_launch"
                status = call("debug_status", {})
                assert_ok(status, "inspect after termination")
                assert not any(launch.get("launchConfiguration") == name and launch.get("state") == "running"
                               for launch in status.structured.get("launches", [])), status.structured
    finally:
        if not calls_aborted():
            assert_ok(call("delete_launch_config", {"name": name, "confirm": True}), "delete window-mode fixture")
    assert_no_diff("window modes must not edit project sources")


@e2e_test(tool="launch", kind="action")
def test_live_minimized_window_mode():
    _round_trip("minimized")


@e2e_test(tool="launch", kind="action")
def test_live_isolated_window_mode():
    _round_trip("isolated")


@e2e_test(tool="launch", kind="action")
def test_live_isolated_debug_window_mode():
    _round_trip("isolated", "debug")


@e2e_test(tool="launch", kind="action")
def test_live_other_screen_window_mode():
    _round_trip("otherScreen")

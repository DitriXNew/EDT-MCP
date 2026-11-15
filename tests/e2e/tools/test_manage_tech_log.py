"""Preview/refusals are CI-safe; real user-profile capture is explicitly opt-in."""
import os
from pathlib import Path
from tempfile import TemporaryDirectory

from harness import (REPO_ROOT, E2ESkip, call, assert_ok, assert_error,
                     assert_error_quality, assert_contains, assert_no_diff, e2e_test)


@e2e_test(tool="manage_tech_log", kind="action")
def test_preview_and_read_only_write_refusals():
    with TemporaryDirectory(prefix="edt-mcp-tech-log-e2e-") as directory:
        capture = Path(directory) / "capture"
        preview = call("manage_tech_log", {"mode": "preview", "profile": "db",
                                          "location": str(capture), "thresholdMs": 1234,
                                          "plans": True, "dbmsLocks": True})
        assert_ok(preview, "capture preview")
        assert_contains(preview.text, 'format="json"')
        assert_contains(preview.text, 'property="durationus" value="1234000"')
        assert_contains(preview.text, "plansql")
        assert_contains(preview.text, "service-account")
        assert not capture.exists(), "preview created capture files"
        for params, names, suggests in (
            ({"mode": "bad"}, ["bad"], ["preview", "status"]),
            ({"mode": "preview", "thresholdMs": -1}, ["thresholdMs"], ["preview"]),
            ({"mode": "export"}, ["path", "outputPath"], ["absolute"]),
        ):
            error = assert_error(call("manage_tech_log", params), "invalid capture input")
            assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()


@e2e_test(tool="manage_tech_log", kind="action")
def test_export_new_csv_and_refuse_overwrite():
    # This writes only a new temporary file; it still exercises the shared consent policy.
    if os.environ.get("EDT_MCP_TECH_LOG_EXPORT") != "1":
        raise E2ESkip("Set EDT_MCP_TECH_LOG_EXPORT=1 with a consent-enabled stand for CSV export")
    with TemporaryDirectory(prefix="edt-mcp-tech-log-export-") as directory:
        output = Path(directory) / "events.csv"
        params = {"mode": "export", "path": str(Path(REPO_ROOT) / "tests/fixtures/tech-log"),
                  "outputPath": str(output)}
        result = call("manage_tech_log", params)
        assert_ok(result, "CSV export")
        before = output.read_bytes()
        assert before.startswith(b"\xef\xbb\xbf"), before[:3]
        assert b"MemoryPeak" in before
        error = assert_error(call("manage_tech_log", params), "never overwrite CSV")
        assert_error_quality(error, names=[str(output)], suggests=["new absolute file"])
        assert output.read_bytes() == before
    assert_no_diff()


@e2e_test(tool="manage_tech_log", kind="action")
def test_user_profile_start_stop_roundtrip_byte_exact():
    if os.environ.get("EDT_MCP_TECH_LOG_LIVE_CAPTURE") != "1":
        raise E2ESkip("Set EDT_MCP_TECH_LOG_LIVE_CAPTURE=1 to authorize writing the real user-profile logcfg.xml")
    target = Path(os.environ["LOCALAPPDATA"]) / "1C/1cv8/conf/logcfg.xml"
    state = target.with_name("edt-mcp-active-capture.json")
    if state.exists() or target.exists() and b"EDT-MCP-TECH-LOG" in target.read_bytes():
        raise E2ESkip("An existing capture owns user config; refusing to interfere")
    before = target.read_bytes() if target.exists() else None
    with TemporaryDirectory(prefix="edt-mcp-tech-log-live-") as directory:
        capture = Path(directory) / "capture"
        started = False
        try:
            result = call("manage_tech_log", {"mode": "start", "profile": "errors", "location": str(capture)})
            # A post-write error still owns a recovery receipt and must reach stop in finally.
            started = (capture / "edt-mcp-capture.json").exists()
            assert_ok(result, "real user-profile capture start")
            assert b"EDT-MCP-TECH-LOG" in target.read_bytes()
            assert (capture / "edt-mcp-capture.json").exists()
            status = call("manage_tech_log", {"mode": "status"})
            assert_ok(status, "observed capture status")
            assert_contains(status.text, "Config written (observed exact contents): true")
            assert_contains(status.text, "Log files appeared:")
        finally:
            if started:
                stopped = call("manage_tech_log", {"mode": "stop"})
                assert_ok(stopped, "restore original user configuration")
                assert_contains(stopped.text, "byte-exactly")
            assert (target.read_bytes() if target.exists() else None) == before
            assert not state.exists()
    assert_no_diff()

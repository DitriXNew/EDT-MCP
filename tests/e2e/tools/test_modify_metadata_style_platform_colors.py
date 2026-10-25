"""StyleItem platform ColorRefs must survive .mdo export and a project re-import.

Each case checks the bytes written by modify_metadata, forces EDT to read them, and checks
get_metadata_details. reset_model restores the fixture in finally, including a failed case.
"""

from pathlib import Path
import time

from harness import (
    PROJECT, PROJECT_DIR, assert_contains, assert_ok, call, e2e_test,
    reset_model, wait_for_project_ready,
)


def _poll_style_mdo(name, qualified_name):
    path = Path(PROJECT_DIR, "src", "StyleItems", name, name + ".mdo")
    marker = qualified_name.encode("utf-8")
    deadline = time.monotonic() + 30
    payload = b""
    while True:
        try:
            payload = path.read_bytes()
            if b"ColorRef" in payload and marker in payload:
                return payload
        except OSError:
            pass
        if time.monotonic() >= deadline:
            raise AssertionError("StyleItem .mdo did not persist %s at %s: %r" %
                                 (qualified_name, path, payload[:1000]))
        time.sleep(0.2)


def _assert_platform_color_survives_reload(name, source, color_name, qualified_name):
    fqn = "StyleItem." + name
    try:
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
                  "create a fresh color StyleItem")
        wait_for_project_ready()
        result = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "properties": [{"name": "value", "value": {"color": {source: color_name}}}],
        })
        assert_ok(result, "set " + qualified_name)
        assert "value" in (result.structured.get("applied") or []), result.structured
        before = _poll_style_mdo(name, qualified_name)
        assert qualified_name.encode("utf-8") in before
        wait_for_project_ready()
        assert_ok(call("clean_project", {"projectName": PROJECT}),
                  "re-import the persisted platform ColorRef from its .mdo")
        wait_for_project_ready()
        details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
        assert_ok(details, "read the reloaded StyleItem")
        assert_contains(details.text, "Value", "the StyleItem must keep its value after re-import")
        assert_contains(details.text, "| Color | " + qualified_name + " |",
                        "metadata details must render the same platform color after re-import")
        after = _poll_style_mdo(name, qualified_name)
        assert qualified_name.encode("utf-8") in after, "the re-import must keep the stored name"
    finally:
        reset_model()


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_style_item_web_red_survives_disk_and_reload():
    _assert_platform_color_survives_reload("E2E682StyleWeb", "web", "Red", "Web.Red")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_style_item_windows_window_text_survives_disk_and_reload():
    _assert_platform_color_survives_reload("E2E682StyleWindows", "windows", "WindowText", "Windows.WindowText")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_style_item_platform_style_color_survives_disk_and_reload():
    _assert_platform_color_survives_reload("E2E682StylePlatform", "style", "FieldBackColor", "Style.FieldBackColor")

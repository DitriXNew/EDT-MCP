"""Opt-in EDT navigation. UI focus itself is checked on the live acceptance stand.

Run normally with the shipped navigation preference OFF. After manually enabling
Allow UI navigation, set EDT_MCP_UI_NAVIGATION_E2E=1 to exercise successful calls
and the target-resolution error matrix. The environment variable only selects
tests; it does not enable the server preference.
"""

import os

from harness import (
    E2ESkip, PROJECT, call, e2e_test, assert_ok, assert_error,
    assert_error_quality, assert_contains, assert_no_diff,
    read_disk,
)


OPTED_IN = os.environ.get("EDT_MCP_UI_NAVIGATION_E2E", "").strip() == "1"
MODULE = "CommonModules/Calc/Module.bsl"


def _require_opt_in():
    if not OPTED_IN:
        raise E2ESkip("Enable Allow UI navigation in EDT and set EDT_MCP_UI_NAVIGATION_E2E=1")


@e2e_test(tool="open_editor", kind="action")
def test_open_editor_refuses_by_default():
    if OPTED_IN:
        raise E2ESkip("This case requires the shipped navigation preference OFF")
    result = call("open_editor", {"projectName": PROJECT, "fqn": "Catalog.Catalog"})
    error = assert_error(result, "UI navigation must be off by default")
    assert_error_quality(error, names=["UI navigation", "disabled"], suggests=["Allow UI navigation", "Preferences"])
    assert_no_diff()


@e2e_test(tool="open_editor", kind="action")
def test_open_editor_validates_required_and_exclusive_arguments():
    cases = [
        ({"fqn": "Catalog.Catalog"}, ["projectName"], ["required"]),
        ({"projectName": PROJECT}, ["fqn", "modulePath"], ["exactly one"]),
        ({"projectName": PROJECT, "fqn": "Catalog.Catalog", "modulePath": MODULE},
         ["fqn", "modulePath"], ["exactly one"]),
        ({"projectName": PROJECT, "fqn": "Catalog.Catalog", "line": 1},
         ["line", "modulePath"], ["omit fqn"]),
        ({"projectName": PROJECT, "fqn": "Catalog.Catalog", "methodName": "M"},
         ["methodName", "modulePath"], ["omit fqn"]),
        ({"projectName": PROJECT, "modulePath": MODULE, "line": 1, "methodName": "M"},
         ["line", "methodName"], ["pass only one"]),
    ]
    for arguments, names, suggests in cases:
        error = assert_error(call("open_editor", arguments), "invalid open_editor arguments")
        assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()


@e2e_test(tool="open_editor", kind="action")
def test_open_editor_rejects_nonpositive_lines():
    for line in (0, -1):
        error = assert_error(call("open_editor", {
            "projectName": PROJECT, "modulePath": MODULE, "line": line,
        }), "invalid line")
        assert_error_quality(error, names=[str(line), "line"], suggests=["positive", "1-based"])
    assert_no_diff()


@e2e_test(tool="open_editor", kind="action")
def test_open_editor_opted_in_opens_supported_targets():
    _require_opt_in()
    for target in (
        {"fqn": "Catalog.Catalog"},
        {"fqn": "Catalog.Catalog.Attribute.Attribute"},
        {"fqn": "CommonForm.Form"},
        {"modulePath": MODULE, "line": 1},
        {"modulePath": MODULE, "methodName": "Add"},
    ):
        result = call("open_editor", {"projectName": PROJECT, **target})
        assert_ok(result, "open EDT editor")
        assert_contains(result.text, "status: opened", "completed native editor opening")
        assert_contains(result.text, target.get("fqn") or target["modulePath"], "requested target")
    assert_no_diff()


@e2e_test(tool="open_editor", kind="action")
def test_open_editor_opted_in_accepts_trailing_empty_line():
    _require_opt_in()
    source = read_disk("src/" + MODULE)
    assert source.endswith("\n"), "Calc fixture must end with a newline"
    final_line = source.count("\n") + 1
    result = call("open_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "line": final_line,
    })
    assert_ok(result, "navigate to the final empty editor line")
    assert_contains(result.text, "status: opened", "completed native editor opening")
    assert_contains(result.text, MODULE, "requested module")
    error = assert_error(call("open_editor", {
        "projectName": PROJECT, "modulePath": MODULE, "line": final_line + 1,
    }), "line beyond the editor document")
    assert_error_quality(error, names=[str(final_line + 1)], suggests=["read_module_source"])
    assert_no_diff()


@e2e_test(tool="open_editor", kind="action")
def test_open_editor_opted_in_rejects_missing_targets():
    _require_opt_in()
    cases = [
        ({"projectName": "Missing792Project", "fqn": "Catalog.Catalog"},
         ["Missing792Project"], ["list_projects"]),
        ({"projectName": PROJECT, "fqn": "Catalog.Missing792"},
         ["Catalog.Missing792"], ["get_metadata_objects"]),
        ({"projectName": PROJECT, "modulePath": "CommonModules/Missing792/Module.bsl"},
         ["CommonModules/Missing792/Module.bsl"], ["list_modules"]),
        ({"projectName": PROJECT, "modulePath": MODULE, "methodName": "Missing792Method"},
         ["Missing792Method"], ["get_module_structure"]),
        ({"projectName": PROJECT, "modulePath": MODULE, "line": 2147483647},
         ["2147483647"], ["read_module_source"]),
    ]
    for arguments, names, suggests in cases:
        error = assert_error(call("open_editor", arguments), "unresolved open_editor target")
        assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()

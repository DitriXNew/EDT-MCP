"""Exact windowed formatter pins, shared by windowed and display-free stdio acceptance."""

import json
import re
from pathlib import Path

from harness import assert_ok, assert_contains, assert_error


BASELINE = Path(__file__).with_name("resources") / "stdio-read-windowed.json"
HEADLESS_BASELINE = BASELINE.with_name("stdio-read-headless.json")
# The windowed get_symbol_info answer is EDT's editor hover, which EDT renders in its UI language
# (the stand's -nl / OS locale). The main baseline holds the English UI; this one the Russian UI.
RU_HOVER_BASELINE = BASELINE.with_name("stdio-read-windowed-ru.json")
MOVED_TOOLS = ("get_metadata_objects", "get_metadata_details", "list_modules",
               "read_method_source", "get_module_structure", "get_symbol_info")

# A type link in the hover: the label is the type name in EDT's UI language, the target names the
# syntax-helper page by the type's ENGLISH name (def_Number) in every UI language.
_V8HELP_TYPE_LINK = re.compile(r"\[([^\]]*)\]\(v8help:[^)\s]*/def_([A-Za-z0-9_]+)\)")
_CYRILLIC = re.compile("[Ѐ-ӿ]")


def baseline_cases():
    return json.loads(BASELINE.read_text(encoding="utf-8"))["cases"]


def hover_language(text):
    """The UI language EDT rendered a hover in, read from the hover's own type links.

    "en" when every type link's label is the English name its target carries, "ru" when every
    label is Cyrillic, None otherwise (no type link, or a mix) - None selects the English pin,
    so an unrecognised answer is still compared byte for byte and fails there."""
    links = _V8HELP_TYPE_LINK.findall(text or "")
    if not links:
        return None
    if all(label == english for label, english in links):
        return "en"
    if all(_CYRILLIC.search(label) for label, _ in links):
        return "ru"
    return None


def expected_response(label, case, host_kind, actual_text=None):
    if host_kind == "HEADLESS" and case["tool"] == "get_symbol_info":
        return json.loads(HEADLESS_BASELINE.read_text(encoding="utf-8"))["cases"][label]["response"]
    if case["tool"] == "get_symbol_info" and hover_language(actual_text) == "ru":
        return json.loads(RU_HOVER_BASELINE.read_text(encoding="utf-8"))["cases"][label]["response"]
    return case["response"]


def _contract(tool, invoke, host_kind):
    count = 0
    for label, case in baseline_cases().items():
        if case["tool"] != tool:
            continue
        result = invoke(tool, dict(case["arguments"]))
        assert_ok(result, host_kind + " " + label)
        expected = expected_response(label, case, host_kind, result.text)
        text = expected["text"]
        if host_kind == "HEADLESS" and tool == "read_module_source":
            text = text.partition("\n## Extension interception\n")[0]
        pin = ""
        if host_kind == "WINDOWED" and tool == "get_symbol_info":
            pin = " (hover language %s; pins exist for en and ru)" % (hover_language(result.text),)
        assert result.text.encode("utf-8") == text.encode("utf-8"), (
            host_kind + " formatter contract changed: " + label + pin + "\n"
            + repr(result.text) + "\nexpected: " + repr(text))
        assert result.structured == expected["structuredContent"], label
        assert result.is_error == expected["isError"], label
        count += 1
    assert count, "No pinned requests for " + tool
    return count


def contract_get_metadata_objects(invoke, host_kind):
    return _contract("get_metadata_objects", invoke, host_kind)


def contract_get_metadata_details(invoke, host_kind):
    return _contract("get_metadata_details", invoke, host_kind)


def contract_list_modules(invoke, host_kind):
    count = _contract("list_modules", invoke, host_kind)
    result = invoke("list_modules", {"projectName": "TestConfiguration"})
    assert_ok(result, host_kind + " list_modules filesystem mode")
    for path in ("CommonModules/Calc/Module.bsl", "CommonModules/Вычисление/Module.bsl",
                 "Configuration/ManagedApplicationModule.bsl"):
        assert_contains(result.text, path, host_kind + " filesystem enumeration must return real modules")
    return count + 1


def contract_read_method_source(invoke, host_kind):
    return _contract("read_method_source", invoke, host_kind)


def contract_get_module_structure(invoke, host_kind):
    return _contract("get_module_structure", invoke, host_kind)


def contract_get_symbol_info(invoke, host_kind):
    return _contract("get_symbol_info", invoke, host_kind)


def contract_read_module_source(invoke, host_kind):
    return _contract("read_module_source", invoke, host_kind)


CONTRACTS = {name: globals()["contract_" + name] for name in (*MOVED_TOOLS, "read_module_source")}


def run_contracts(invoke, host_kind):
    return sum(contract(invoke, host_kind) for contract in CONTRACTS.values())


def run_runtime_preconditions(invoke):
    """Unlicensed CI proves native admission/preconditions, not runtime mutation effects."""
    for expression in ("Calc.Add(1, 2)", "Вычисление.Маркер()"):
        error = assert_error(invoke("evaluate_expression", {"frameRef": 999999, "expression": expression}))
        assert_contains(error, "stale frameRef")
        assert_contains(error, "wait_for_break")
    missing = "headless_acceptance_missing_application"
    for tool, extra in (
        ("update_database", {"confirm": True, "terminateRunningClients": False}),
        ("infobase_sessions", {"action": "terminate", "sessionId": "1", "confirm": True}),
        ("infobase_extensions", {"action": "uninstall", "extensionName": "HeadlessAcceptance"}),
    ):
        error = assert_error(invoke(tool, {"projectName": "TestConfiguration", "applicationId": missing, **extra}))
        assert_contains(error, missing, tool + " must reach native application lookup")
        assert_contains(error, "get_applications", tool + " must give a recovery step")
    result = invoke("ask_workmate", {"workmateTool": "JShellManual", "workmateArgs": "{}",
                                     "timeoutSeconds": 15, "waitSeconds": 20})
    assert_ok(result, "Workmate direct manual job must be admitted even when native authentication is absent")
    assert_contains(result.text, "# Background job:")
    assert "disabled by the user" not in result.text and "needs a windowed EDT" not in result.text

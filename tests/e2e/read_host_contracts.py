"""Exact windowed formatter pins, shared by windowed and display-free stdio acceptance."""

import json
from pathlib import Path

from harness import assert_ok, assert_contains, assert_error


BASELINE = Path(__file__).with_name("resources") / "stdio-read-windowed.json"
HEADLESS_BASELINE = BASELINE.with_name("stdio-read-headless.json")
MOVED_TOOLS = ("get_metadata_objects", "get_metadata_details", "list_modules",
               "read_method_source", "get_module_structure", "get_symbol_info")


def baseline_cases():
    return json.loads(BASELINE.read_text(encoding="utf-8"))["cases"]


def expected_response(label, case, host_kind):
    if host_kind == "HEADLESS" and case["tool"] == "get_symbol_info":
        return json.loads(HEADLESS_BASELINE.read_text(encoding="utf-8"))["cases"][label]["response"]
    return case["response"]


def _contract(tool, invoke, host_kind):
    count = 0
    for label, case in baseline_cases().items():
        if case["tool"] != tool:
            continue
        result = invoke(tool, dict(case["arguments"]))
        assert_ok(result, host_kind + " " + label)
        expected = expected_response(label, case, host_kind)
        text = expected["text"]
        if host_kind == "HEADLESS" and tool == "read_module_source":
            text = text.partition("\n## Extension interception\n")[0]
        assert result.text.encode("utf-8") == text.encode("utf-8"), (
            host_kind + " formatter contract changed: " + label + "\n"
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

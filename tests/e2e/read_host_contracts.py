"""Exact windowed formatter pins, shared by windowed and display-free stdio acceptance."""

import json
from pathlib import Path

from harness import assert_ok, assert_contains


BASELINE = Path(__file__).with_name("resources") / "stdio-read-windowed.json"
MOVED_TOOLS = ("get_metadata_objects", "get_metadata_details", "list_modules")


def baseline_cases():
    return json.loads(BASELINE.read_text(encoding="utf-8"))["cases"]


def _contract(tool, invoke, host_kind):
    count = 0
    for label, case in baseline_cases().items():
        if case["tool"] != tool:
            continue
        result = invoke(tool, dict(case["arguments"]))
        assert_ok(result, host_kind + " " + label)
        expected = case["response"]
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


CONTRACTS = {name: globals()["contract_" + name] for name in (*MOVED_TOOLS,
             "read_method_source", "get_module_structure", "get_symbol_info", "read_module_source")}


def run_contracts(invoke, host_kind):
    names = CONTRACTS if host_kind == "WINDOWED" else (*MOVED_TOOLS, "read_module_source")
    return sum(CONTRACTS[name](invoke, host_kind) for name in names)

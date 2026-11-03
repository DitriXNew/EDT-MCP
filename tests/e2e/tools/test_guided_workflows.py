"""Opt-in prompt/resource wire checks; run with EDT_MCP_GUIDED_WORKFLOWS=true on EDT.

Also exercises the common request shapes used by Claude Code, Copilot and Cursor.
Actual client UI acceptance is described in docs/guided-workflows.md.
"""

import os
from urllib.parse import quote

from harness import (
    E2ESkip, PROJECT, _post, assert_no_diff, assert_ok, call, e2e_test,
    initialize, wait_for_server,
)

PROBLEMS = "edt://projects/{projectName}/problems"
GUIDES = "edt://projects/{projectName}/guides/{guideName}"


def _result(method, params):
    response = _post(method, params)
    assert "error" not in response, "%s failed: %r" % (method, response)
    return response["result"]


def _enabled():
    templates = _result("resources/templates/list", {})["resourceTemplates"]
    if not templates and os.environ.get("EDT_MCP_GUIDED_WORKFLOWS", "").lower() != "true":
        raise E2ESkip("Launch EDT with EDT_MCP_GUIDED_WORKFLOWS=true to accept #339")
    assert [t["uriTemplate"] for t in templates] == [PROBLEMS, GUIDES], templates


def _uri(suffix):
    return "edt://projects/%s/%s" % (quote(PROJECT, safe=""), suffix)


def _completion(reference, name, value, arguments=None):
    params = {"ref": reference, "argument": {"name": name, "value": value}}
    if arguments is not None:
        params["context"] = {"arguments": arguments}
    return _result("completion/complete", params)["completion"]


@e2e_test(tool="_guided_workflows", kind="read")
def test_guided_workflows_health_prompt_and_resource_reuse_live_report():
    _enabled()
    prompts = _result("prompts/list", {})["prompts"]
    assert [p["name"] for p in prompts] == ["edt_project_health"], prompts
    assert [(a["name"], a["required"]) for a in prompts[0]["arguments"]] == [("projectName", True)]
    contents = _result("resources/read", {"uri": _uri("problems")})["contents"]
    assert len(contents) == 1 and contents[0]["uri"] == _uri("problems"), contents
    assert contents[0]["mimeType"] == "text/markdown", contents
    control = call("get_project_errors", {"projectName": PROJECT, "limit": 100})
    assert_ok(control, ctx="problem producer control")
    assert contents[0]["text"] == control.text, (contents[0], control.text)
    prompt = _result("prompts/get", {"name": "edt_project_health", "arguments": {"projectName": PROJECT}})
    messages = prompt["messages"]
    assert [m["role"] for m in messages] == ["user", "user", "user"], messages
    assert messages[0]["content"]["type"] == "text", messages
    assert "prioritized repair plan" in messages[0]["content"]["text"]
    assert "Do not modify, clean, rebuild, or update a database" in messages[0]["content"]["text"]
    assert messages[1]["content"] == {"type": "resource", "resource": contents[0]}, messages[1]
    guide = _result("resources/read", {"uri": "guide://get_project_errors"})["contents"][0]
    assert messages[2]["content"] == {"type": "resource", "resource": guide}, messages[2]
    assert_no_diff("guided health review must be read-only")


@e2e_test(tool="_guided_workflows", kind="read")
def test_guided_workflows_completion_supports_prompt_and_contextual_template_shapes():
    _enabled()
    # The prompt form also works without context, as sent by older clients.
    prompt = _completion({"type": "ref/prompt", "name": "edt_project_health"}, "projectName", PROJECT)
    resource = _completion({"type": "ref/resource", "uri": PROBLEMS}, "projectName", PROJECT, {})
    assert PROJECT in prompt["values"], prompt
    assert prompt == resource, (prompt, resource)
    assert len(prompt["values"]) <= 50 and prompt["total"] >= len(prompt["values"]), prompt
    assert prompt["hasMore"] == (prompt["total"] > len(prompt["values"])), prompt
    guide = _completion({"type": "ref/resource", "uri": GUIDES}, "guideName", "get_project_errors",
                        {"projectName": PROJECT})
    assert guide == {"values": ["get_project_errors"], "total": 1, "hasMore": False}, guide
    unavailable = _completion({"type": "ref/resource", "uri": GUIDES}, "guideName", "",
                              {"projectName": "__MISSING_GUIDED_PROJECT__"})
    assert unavailable == {"values": [], "total": 0, "hasMore": False}, unavailable
    document = _result("resources/read", {"uri": _uri("guides/get_project_errors")})["contents"][0]
    control = _result("resources/read", {"uri": "guide://get_project_errors"})["contents"][0]
    assert document["text"] == control["text"] and document["mimeType"] == control["mimeType"], document
    assert_no_diff("completion and guide reads must be read-only")


@e2e_test(tool="_guided_workflows", kind="read")
def test_guided_workflows_invalid_arguments_are_jsonrpc_errors():
    _enabled()
    cases = [
        ("resources/read", {"uri": "edt://projects/%2e%2e/problems"}, -32602, "projectName"),
        ("resources/read", {"uri": _uri("problems?limit=1")}, -32602, "queries and fragments"),
        ("resources/read", {"uri": "edt://projects/__MISSING_GUIDED_PROJECT__/problems"}, -32002, "completion"),
        ("prompts/get", {"name": "edt_project_health", "arguments": {}}, -32602, "projectName"),
        ("prompts/get", {"name": "__missing_prompt__"}, -32602, "prompts/list"),
        ("completion/complete", {"ref": {"type": "ref/tool", "name": "get_project_errors"},
                                 "argument": {"name": "projectName", "value": ""}}, -32602, "ref/prompt or ref/resource"),
    ]
    for method, params, code, guidance in cases:
        response = _post(method, params)
        assert "result" not in response and response.get("error", {}).get("code") == code, response
        assert guidance in response["error"]["message"], response
    assert_no_diff("invalid workflow requests must not modify the fixture")


if __name__ == "__main__":
    wait_for_server()
    initialize()
    for test in (test_guided_workflows_health_prompt_and_resource_reuse_live_report,
                 test_guided_workflows_completion_supports_prompt_and_contextual_template_shapes,
                 test_guided_workflows_invalid_arguments_are_jsonrpc_errors):
        test()
        print("PASS", test.__name__)

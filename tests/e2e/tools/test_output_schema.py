"""
Wire-level contract test for the per-tool ``outputSchema`` (kind: read).

The Java ratchet (BuiltInToolOutputSchemaTest) checks the in-process contract; this
test pins the SERIALIZED ``tools/list`` surface AND the runtime payload a real client
sees, which only a live server can prove:

  A. structural — every tool that advertises an ``outputSchema`` advertises a permissive
     success envelope (object, ``properties`` includes ``success``, no
     ``additionalProperties:false``);
  B. presence    — a known JSON tool (``get_server_status``) DOES carry one;
  C. absence     — a known non-JSON tool (``get_module_structure`` is MARKDOWN) does NOT;
  D. conformance — the real ``structuredContent`` of a call only contains keys the
     declared schema knows about (catches schema-vs-reality drift at runtime).

These are read-only protocol calls; none mutate the fixture.
"""

from harness import _post, call, assert_ok, assert_no_diff, e2e_test, _fail


def _tools_by_name():
    raw = _post("tools/list", {})
    tools = (raw.get("result", {}) or {}).get("tools", []) or []
    if len(tools) < 50:
        _fail("tools/list returned only %d tools — server not fully up?" % len(tools))
    return {t.get("name"): t for t in tools}


def _assert_permissive_success_envelope(name, schema):
    if not isinstance(schema, dict):
        _fail("%s: outputSchema must be a JSON object, got %r" % (name, type(schema)))
    if schema.get("type") != "object":
        _fail("%s: outputSchema must have type 'object', got %r" % (name, schema.get("type")))
    props = schema.get("properties")
    if not isinstance(props, dict) or not props:
        _fail("%s: outputSchema must declare a non-empty 'properties'" % name)
    if "success" not in props:
        _fail("%s: outputSchema 'properties' must include 'success'" % name)
    if schema.get("additionalProperties") is False:
        _fail("%s: outputSchema must not set additionalProperties:false (stay permissive)" % name)
    required = schema.get("required") or []
    # 'success' is always present; conditional fields must NOT be required.
    if "success" not in required:
        _fail("%s: outputSchema should mark 'success' as required" % name)
    stray = [r for r in required if r != "success"]
    if stray:
        _fail("%s: only 'success' may be required, found extra required: %s" % (name, stray))


@e2e_test(tool="_output_schema", kind="read")
def test_every_advertised_output_schema_is_a_permissive_envelope():
    tools = _tools_by_name()
    advertised = {n: t["outputSchema"] for n, t in tools.items()
                  if t.get("outputSchema") is not None}
    # The JSON-tool count is ~24; guard against the feature silently regressing to zero
    # (e.g. serialization dropped the field) which would make this test vacuous.
    if len(advertised) < 20:
        _fail("only %d tools advertise an outputSchema — expected ~24 JSON tools "
              "(serialization regressed?)" % len(advertised))
    for name, schema in sorted(advertised.items()):
        _assert_permissive_success_envelope(name, schema)
    assert_no_diff("reading tools/list must not modify the project")


@e2e_test(tool="_output_schema", kind="read")
def test_json_tool_advertises_output_schema_and_non_json_omits_it():
    tools = _tools_by_name()

    # Presence: get_server_status is a JSON tool -> must carry an outputSchema.
    status = tools.get("get_server_status")
    if status is None:
        _fail("get_server_status missing from tools/list")
    _assert_permissive_success_envelope("get_server_status", status.get("outputSchema"))

    # Absence: get_module_structure returns MARKDOWN, not structuredContent -> no schema.
    struct = tools.get("get_module_structure")
    if struct is None:
        _fail("get_module_structure missing from tools/list")
    if struct.get("outputSchema") is not None:
        _fail("get_module_structure is a MARKDOWN tool and must NOT advertise an outputSchema")
    assert_no_diff("reading tools/list must not modify the project")


@e2e_test(tool="_output_schema", kind="read")
def test_runtime_structured_content_conforms_to_declared_schema():
    tools = _tools_by_name()
    schema = tools.get("get_server_status", {}).get("outputSchema") or {}
    declared = set((schema.get("properties") or {}).keys())
    if not declared:
        _fail("get_server_status has no declared outputSchema properties to check against")

    # get_server_status needs no arguments and returns a deterministic rich payload.
    r = call("get_server_status", {})
    assert_ok(r, "get_server_status for schema-conformance check")
    if not isinstance(r.structured, dict):
        _fail("get_server_status returned no structuredContent dict")

    undeclared = [k for k in r.structured.keys() if k not in declared]
    if undeclared:
        _fail("get_server_status structuredContent has keys NOT in its outputSchema: %s "
              "(schema drifted from the real payload)" % undeclared)
    # The schema must at least describe the always-present 'success' field.
    if "success" not in r.structured:
        _fail("get_server_status structuredContent unexpectedly lacks 'success'")
    assert_no_diff("a read call must not modify the project")

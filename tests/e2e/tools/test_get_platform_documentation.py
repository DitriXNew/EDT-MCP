"""
e2e tests for get_platform_documentation (kind: read).

The tool renders platform documentation for 1C:Enterprise types / built-in
functions as MARKDOWN, so the payload is in r.text (NOT r.structured). It is a
pure read tool: it never touches the project tree, so every test ends with
assert_no_diff().

REAL params (GetPlatformDocumentationTool.getInputSchema / execute):
  typeName    (string, REQUIRED) - type or symbol name, e.g. 'Array', 'ValueTable'
  category    (enum: type|builtin, default 'type')
  memberName  (string, partial match filter on member name)
  memberType  (enum: method|property|constructor|event|all, default 'all')
  projectName (string, optional - only picks the platform version)
  limit       (int, default 50, clamped to 200)
  language    (enum: en|ru, default 'en')
  responseFormat (enum: concise|detailed, default 'concise') - concise keeps the
                  header + Type Info block + every section/member heading but omits the
                  verbose per-member body (parameters, overloads, return/property types,
                  access flags); detailed returns the full rendering. An unrecognized
                  value falls back to concise.

Happy paths assert on real rendered content that MUST be present:
  - type lookup -> "# Array" header + "**Type Info:**" block + a member section
  - builtin lookup -> "Built-in function" header line
  - memberName filter narrows the rendered members (mutation guard).

Negative matrix targets the tool's REAL execute() / service paths. Two CLASSES
of failure exist and they behave DIFFERENTLY on the wire:

  (A) machine-detectable errors via ToolResult.error(...).toJson() -> is_error=true:
        - missing required typeName  -> "typeName is required"
        - invalid memberType enum    -> "memberType must be one of: method, ..."
        - unknown category           -> "Unknown category '<cat>'. Supported: 'type', 'builtin'"

  (B) SOFT errors: PlatformDocumentationService returns a PLAIN markdown string
      beginning "Error: Type not found: <name>" / "Error: Built-in function not
      found: <name>". These are NOT wrapped in ToolResult.error, do NOT start
      with '{', so the protocol's isJsonErrorPayload diversion does not fire and
      the result is delivered as a normal markdown resource with is_error=FALSE.
      => assert_error() would WRONGLY fail here, so these are asserted as a
         successful response whose text carries the bad value + the actionable
         "Available types/methods" list, and flagged with # AUDIT (they SHOULD be
         a real is_error so a machine client can detect the not-found).

'Array' / 'Message' are universal platform symbols present for every platform
version, so the happy paths do not depend on the (minimal) fixture content.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_platform_documentation", kind="read")
def test_type_array_renders_doc_and_does_not_mutate():
    # Default category 'type'. 'Array' is a universal platform type -> the service
    # must resolve it and render the type header + the "Type Info" block. If lookup
    # were broken it would fall through to the "Error: Type not found" branch and
    # neither marker below would be present.
    r = call("get_platform_documentation", {"projectName": PROJECT, "typeName": "Array"})
    assert_ok(r, "get_platform_documentation Array")
    # The H1 header is built only from a RESOLVED Type (buildTypeDocumentation).
    assert_contains(r.text, "# Array", "rendered doc must carry the resolved type header")
    # This block is emitted for every resolved type -> proves the type body rendered.
    assert_contains(r.text, "**Type Info:**",
                    "resolved type doc must include the Type Info block")
    # A resolved Array exposes members; with memberType=all at least one section
    # heading must appear (Methods / Properties / Constructors).
    assert ("## Methods" in r.text or "## Properties" in r.text
            or "## Constructors" in r.text), \
        "resolved Array doc must render at least one member section"
    # A 'not found' soft-error would start with this literal -> must be absent.
    assert_not_contains(r.text, "Error: Type not found",
                        "a successful type lookup must not emit the not-found banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_membername_filter_narrows_members():
    # memberName is a case-insensitive partial filter. On 'Array', "Add" matches the
    # Add method but must NOT pull in the unrelated "Count" property. If the filter
    # were ignored (broken), the full member set (incl. Count) would render.
    # responseFormat=detailed so the full member body (parameters / return type) is
    # rendered alongside the H3 heading; the filter logic itself is format-independent.
    r = call("get_platform_documentation",
             {"projectName": PROJECT, "typeName": "Array",
              "memberName": "Add", "memberType": "method",
              "responseFormat": "detailed"})
    assert_ok(r, "get_platform_documentation Array memberName=Add")
    assert_contains(r.text, "# Array", "filtered doc still carries the type header")
    # The matching member must be rendered as its own H3 entry.
    assert_contains(r.text, "### Add", "memberName 'Add' must keep the Add method")
    # Mutation guard: 'Count' is a property of Array; with memberType=method +
    # memberName=Add it must be filtered out. Its presence would mean the filter
    # (or the memberType narrowing) did nothing.
    assert_not_contains(r.text, "### Count",
                        "memberName=Add / memberType=method must EXCLUDE the Count member")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_builtin_function_message_renders_doc():
    # category=builtin routes to getBuiltinFunctionDocumentation. 'Message' /
    # 'Сообщить' is a universal global procedure -> must resolve and render the
    # built-in header line. A broken lookup falls to "Built-in function not found".
    r = call("get_platform_documentation",
             {"projectName": PROJECT, "typeName": "Message", "category": "builtin"})
    assert_ok(r, "get_platform_documentation builtin Message")
    # This line is emitted only by buildBuiltinMethodDocumentation for a RESOLVED
    # global method -> proves the builtin branch resolved the function.
    assert_contains(r.text, "Built-in function",
                    "resolved builtin doc must carry the 'Built-in function' category line")
    assert_not_contains(r.text, "Built-in function not found",
                        "a successful builtin lookup must not emit the not-found banner")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# responseFormat contract — concise (default) is leaner than detailed
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_platform_documentation", kind="read")
def test_concise_default_is_leaner_than_detailed():
    # The DEFAULT (no responseFormat) must be the concise rendering: it keeps the
    # structural skeleton — type header, Type Info block and every member H3 heading
    # (the inventory the caller drills into) — but omits the verbose per-member body.
    # detailed must return strictly MORE text (the full signatures/parameters/types).
    args = {"projectName": PROJECT, "typeName": "Array"}
    concise = call("get_platform_documentation", args)
    detailed = call("get_platform_documentation",
                    {**args, "responseFormat": "detailed"})
    assert_ok(concise, "default (concise) Array")
    assert_ok(detailed, "detailed Array")

    # Essential structure survives the default concise rendering.
    assert_contains(concise.text, "# Array", "concise keeps the type header")
    assert_contains(concise.text, "**Type Info:**", "concise keeps the Type Info block")
    assert_contains(concise.text, "### Add",
                    "concise keeps every member heading (the inventory)")

    # detailed renders the verbose Add-method body (a Parameters block); concise drops
    # it. This marker is the litmus test that concise actually shed the per-member detail.
    assert_contains(detailed.text, "**Parameters:**",
                    "detailed renders the verbose per-member Parameters body")
    assert_not_contains(concise.text, "**Parameters:**",
                        "concise must omit the verbose per-member Parameters body")

    # The whole point: fewer tokens. concise must be strictly shorter than detailed.
    assert len(concise.text) < len(detailed.text), (
        "concise must be leaner than detailed (got concise=%d, detailed=%d chars)"
        % (len(concise.text), len(detailed.text)))

    # An unrecognized responseFormat value falls back to concise (no error), so it
    # matches the default rendering rather than erroring or returning detailed.
    bogus = call("get_platform_documentation",
                 {**args, "responseFormat": "bogus_fmt_e2e"})
    assert_ok(bogus, "unrecognized responseFormat falls back to concise")
    assert_not_contains(bogus.text, "**Parameters:**",
                        "an unrecognized responseFormat must default to concise, not detailed")

    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — CLASS (A): real, machine-detectable is_error
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_platform_documentation", kind="read")
def test_missing_typename_errors_clearly():
    # Required param omitted -> JsonUtils.requireArgument -> ToolResult.error(
    # "typeName is required").toJson() -> is_error=true.
    r = call("get_platform_documentation", {"projectName": PROJECT})
    err = assert_error(r, "missing required typeName")
    # AUDIT: the message names the missing param but offers NO next step (it does
    # not hint at the 'category'/'typeName' usage or an example). Keep suggests=[]
    # and flag it as a fix-card to add an actionable hint.
    assert_error_quality(err, names=["typeName"], suggests=[],
                         ctx="missing typeName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_invalid_membertype_enum_errors_actionably():
    # memberType is validated in execute(): an out-of-set value ->
    # ToolResult.error("memberType must be one of: method, property, constructor,
    # event, all") -> is_error=true. This error is actionable (it lists the valid
    # values), so suggests= one of them.
    bad = "bogusMember_e2e"
    r = call("get_platform_documentation",
             {"projectName": PROJECT, "typeName": "Array", "memberType": bad})
    err = assert_error(r, "invalid memberType enum")
    # AUDIT: the message lists the valid set but does NOT echo the rejected value
    # (`bad`), so a caller cannot see WHAT it sent that was wrong. names=["memberType"]
    # asserts the param is named; the rejected literal is intentionally NOT required.
    assert_error_quality(err, names=["memberType"], suggests=["property"],
                         ctx="invalid memberType names the param and lists valid values")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_unknown_category_errors_actionably():
    # category default branch in execute() -> ToolResult.error("Unknown category
    # '<cat>'. Supported: 'type', 'builtin'") -> is_error=true. Names the bad value
    # AND lists the valid alternatives -> genuinely actionable.
    bad = "bogusCategory_e2e"
    r = call("get_platform_documentation",
             {"projectName": PROJECT, "typeName": "Array", "category": bad})
    err = assert_error(r, "unknown category")
    assert_error_quality(err, names=[bad], suggests=["builtin"],
                         ctx="unknown category names the bad value and lists valid ones")
    assert_no_diff("an invalid call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — CLASS (B): SOFT errors (is_error=FALSE on the wire)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_platform_documentation", kind="read")
def test_nonexistent_type_reports_not_found_with_suggestions():
    # PlatformDocumentationService returns a PLAIN string "Error: Type not found:
    # <name>\n\nAvailable types (...)". It is NOT a ToolResult.error JSON, so
    # is_error stays FALSE -> assert the SUCCESSFUL response's text content instead.
    bad = "NoSuchType_ZZZ_e2e"
    r = call("get_platform_documentation", {"projectName": PROJECT, "typeName": bad})
    # AUDIT: a not-found is delivered as is_error=FALSE (a "successful" markdown
    # whose body merely starts with "Error:"). A machine MCP client cannot detect
    # the failure. This SHOULD be a ToolResult.error so is_error=true. Fix-card.
    assert_ok(r, "type not found is (wrongly) a non-error response")
    # The banner must name the bad value (so a human sees WHAT was not found)...
    assert_contains(r.text, "Type not found", "soft error must say the type was not found")
    assert_contains(r.text, bad, "soft error must name the bad type value")
    # ...and be actionable: the service lists the available type names to choose from.
    assert_contains(r.text, "Available types",
                    "soft error must list available types as the actionable next step")
    assert_no_diff("an invalid lookup must not touch the project on disk")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_nonexistent_builtin_reports_not_found_with_suggestions():
    # Same soft-error shape on the builtin branch: "Error: Built-in function not
    # found: <name>\n\nAvailable global methods (...)". is_error stays FALSE.
    bad = "NoSuchBuiltin_ZZZ_e2e"
    r = call("get_platform_documentation",
             {"projectName": PROJECT, "typeName": bad, "category": "builtin"})
    # AUDIT: as above, this not-found is is_error=FALSE -> not machine-detectable.
    # Should be a ToolResult.error. Fix-card.
    assert_ok(r, "builtin not found is (wrongly) a non-error response")
    assert_contains(r.text, "Built-in function not found",
                    "soft error must say the builtin was not found")
    assert_contains(r.text, bad, "soft error must name the bad function value")
    assert_contains(r.text, "Available global methods",
                    "soft error must list available global methods as the next step")
    assert_no_diff("an invalid lookup must not touch the project on disk")

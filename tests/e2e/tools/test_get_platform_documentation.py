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

Negative matrix targets the tool's REAL execute() / service paths. Every failure
is a machine-detectable is_error via ToolResult.error(...).toJson():
        - missing required typeName  -> "typeName is required"
        - invalid memberType enum    -> "Invalid memberType: '<bad>'. Must be one of: ..."
        - unknown category           -> "Unknown category '<cat>'. Supported: 'type', 'builtin'"
        - type not found             -> "Type not found: <name>\n\nAvailable types (...)"
        - builtin not found          -> "Built-in function not found: <name>\n\nAvailable global methods (...)"

The not-found cases are NOT-FOUND banners that PlatformDocumentationService builds
as plain markdown ("Error: Type not found: <name>\n\n<available list>"); execute()
detects that soft banner and surfaces it through ToolResult.error(...) so the miss
is is_error=TRUE on the wire (a machine MCP client can detect it), while the
actionable available-types/functions list is preserved as the error body.

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
    # The message echoes the rejected value AND lists the valid set, so a caller sees
    # both WHAT it sent that was wrong and the actionable alternatives.
    assert_error_quality(err, names=[bad], suggests=["property"],
                         ctx="invalid memberType names the bad value and lists valid values")
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
    # PlatformDocumentationService builds a "Type not found: <name>\n\nAvailable
    # types (...)" banner. execute() now detects the soft banner and surfaces it via
    # ToolResult.error(...) -> is_error=TRUE, so a machine MCP client can detect the
    # miss. The actionable available-types list is preserved as the error body.
    bad = "NoSuchType_ZZZ_e2e"
    r = call("get_platform_documentation", {"projectName": PROJECT, "typeName": bad})
    err = assert_error(r, "nonexistent type is a real is_error")
    # The error must name the bad value AND list the available types as the next step.
    assert_error_quality(err, names=[bad], suggests=["Available types"],
                         ctx="type not found names the bad value and lists available types")
    assert_no_diff("an invalid lookup must not touch the project on disk")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_nonexistent_builtin_reports_not_found_with_suggestions():
    # Same shape on the builtin branch: "Built-in function not found: <name>\n\n
    # Available global methods (...)". execute() now surfaces the soft banner via
    # ToolResult.error(...) -> is_error=TRUE, preserving the available-methods list.
    bad = "NoSuchBuiltin_ZZZ_e2e"
    r = call("get_platform_documentation",
             {"projectName": PROJECT, "typeName": bad, "category": "builtin"})
    err = assert_error(r, "nonexistent builtin is a real is_error")
    # The error must name the bad value AND list the available global methods.
    assert_error_quality(err, names=[bad], suggests=["Available global methods"],
                         ctx="builtin not found names the bad value and lists available methods")
    assert_no_diff("an invalid lookup must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# System enumerations, and the documentation the model does not carry — issue #299.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_platform_documentation", kind="read")
def test_system_enumeration_lists_its_values():
    """Issue #299: a system enumeration rendered as a bare, self-contradicting "Constructor 1 /
    No parameters" under "Created by New: No" and NOT ONE of its values - so the only thing such a
    type actually has could not be learned from this tool at all.

    The values live on a companion type reached through the global-context property named after the
    enumeration, which is what BSL itself resolves for `DateFractions.Date`."""
    r = call("get_platform_documentation", {
        "projectName": PROJECT, "typeName": "DateFractions", "responseFormat": "detailed"})
    assert_ok(r, "document a system enumeration")
    assert_contains(r.text, "## Values", "a system enumeration must render its values")
    for value in ("DateFractions.Date", "DateFractions.DateTime", "DateFractions.Time"):
        assert_contains(r.text, value, "the enumeration value %s must be listed" % value)
    # The Russian names come along, the way every other member renders bilingually.
    assert_contains(r.text, "ЧастиДаты.Дата", "the value's Russian name must be listed too")
    # ... and the constructor that never applied is gone: this type is not created by New.
    assert_not_contains(r.text, "## Constructors",
                        "a non-constructible enumeration must not render a Constructors section")
    assert_no_diff("a read tool must not touch the project")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_russian_rendering_names_the_enumeration_in_both_languages():
    # The alternate identifier must be wholly English: the first cut prefixed the ENGLISH value with
    # the RUSSIAN enumeration name, producing 'ЧастиДаты.Date' - an identifier that exists in
    # neither language (issue #299 review).
    r = call("get_platform_documentation", {
        "projectName": PROJECT, "typeName": "DateFractions", "responseFormat": "detailed",
        "language": "ru"})
    assert_ok(r, "document a system enumeration in Russian")
    assert_contains(r.text, "`ЧастиДаты.Дата` / `DateFractions.Date`",
                    "the alternate identifier must name the enumeration in the other language too")
    assert_not_contains(r.text, "`ЧастиДаты.Date`",
                        "a half-Russian, half-English identifier must never be rendered")
    assert_no_diff("a read tool must not touch the project")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_enumeration_values_survive_the_member_name_filter():
    # The values are members like any other, so memberName narrows them instead of being ignored.
    r = call("get_platform_documentation", {
        "projectName": PROJECT, "typeName": "DateFractions", "responseFormat": "detailed",
        "memberName": "DateTime"})
    assert_ok(r, "filter the enumeration values by name")
    assert_contains(r.text, "DateFractions.DateTime", "the matching value must survive the filter")
    # The needle must be one the renderer really emits: values are rendered inside backticks,
    # so "DateFractions.Time\n" never appeared and this could not fail whatever the filter did.
    assert_not_contains(r.text, "`DateFractions.Time`",
                        "a non-matching value must be filtered out")
    assert_no_diff("a read tool must not touch the project")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_system_enumeration_values_survive_the_default_concise_rendering():
    # 'concise' is the DEFAULT, and it drops per-member bodies - but an enumeration's values ARE its
    # inventory, not a body. They are bullets rather than '###' headings, so the concise filter
    # silently removed them and left an empty "## Values" section (issue #299 review).
    r = call("get_platform_documentation", {"projectName": PROJECT, "typeName": "DateFractions"})
    assert_ok(r, "document a system enumeration in the default format")
    assert_contains(r.text, "## Values", "the concise rendering must keep the Values section")
    assert_contains(r.text, "DateFractions.Date",
                    "the concise rendering must keep the values themselves, not just the heading")
    assert_no_diff("a read tool must not touch the project")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_type_and_members_carry_the_platform_documentation():
    """Issue #299: the model carries names, parameters and flags but no PROSE, so a caller could not
    learn what a type or a member is for. The syntax helper has it; this pulls it in."""
    r = call("get_platform_documentation", {
        "projectName": PROJECT, "typeName": "AccessToken", "responseFormat": "detailed"})
    assert_ok(r, "document a type that the platform help describes")
    # The type's own description...
    assert_contains(r.text, "JSON Web Token", "the type description must come from the platform help")
    # ... a method's ...
    assert_contains(r.text, "Adds a signature to the access token",
                    "a method's description must come from the platform help")
    # ... and a property's, which is the part that maps the property to its JWT claim - exactly what
    # the issue reported as missing and needed.
    assert_contains(r.text, "iat", "a property's description must come from the platform help")
    # The markup the help stores its sections in must NOT reach the answer.
    for markup in ("<a href", "<br>", "&nbsp;", "SyntaxHelperContext"):
        assert_not_contains(r.text, markup, "the help markup %s must be stripped" % markup)
    assert_no_diff("a read tool must not touch the project")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_a_type_whose_name_collides_with_a_help_catalog_still_gets_its_documentation():
    # The help tree holds GROUPING CATALOGS that can carry a type's very name - there is a catalog
    # named like the Query type. Having children, such a catalog won the name search, the member
    # lookup then walked the wrong subtree, and the type silently lost every description. Types
    # without such a namesake (AccessToken, Chart, DateFractions) never showed it (issue #299 review).
    r = call("get_platform_documentation", {
        "projectName": PROJECT, "typeName": "Query", "responseFormat": "detailed",
        "memberName": "Execute"})
    assert_ok(r, "document a type whose name collides with a help catalog")
    assert_contains(r.text, "Executes the database query",
                    "the member description must come from the TYPE's page, not a same-named catalog")
    assert_no_diff("a read tool must not touch the project")


@e2e_test(tool="get_platform_documentation", kind="read")
def test_a_documented_return_value_is_rendered_next_to_the_modelled_type():
    # Issue #299: the model gives the return TYPE, the help says what the value MEANS - a caller
    # needs both. The documented-ONLY branch is deliberately not asserted here: AccessToken.Sign,
    # the case the report cited, is documented as a PROCEDURE, so neither source records a return
    # for it and none is invented.
    r = call("get_platform_documentation", {
        "projectName": PROJECT, "typeName": "Chart", "responseFormat": "detailed",
        "memberName": "GetValue"})
    assert_ok(r, "document a method whose return the platform help describes")
    assert_contains(r.text, "**Returns:** ChartValue", "the modelled return type must still render")
    assert_contains(r.text, "The chart value at the given point",
                    "the documented meaning of the return must render next to it")
    assert_no_diff("a read tool must not touch the project")


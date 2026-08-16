"""
e2e tests for get_method_call_hierarchy (kind: read).

What the tool does
------------------
Reports a BSL method's call hierarchy in ONE direction:
  - direction="callers" (default): who calls this method (incoming).
  - direction="callees": what this method calls (outgoing, per call site).
  - direction="outgoing": the AGGREGATED distinct outgoing-call targets of a method
    (when methodName is given) OR of the WHOLE module (methodName is OPTIONAL for
    outgoing). Rows are keyed by qualifier+method; an unqualified local call buckets
    under the '(local)' qualifier (always ExtAPI '-').
It addresses the *containing* method by (projectName, modulePath, methodName) —
there is NO line/column parameter; resolution is by method NAME (case-insensitive,
BslModuleUtils.findMethod). ResponseType is MARKDOWN, so the payload is in r.text
and r.structured is None. Callers are found EDT-style: text-prefilter every .bsl
that mentions the name, parse only those, then match each Invocation to THIS method
by its resolved AST feature entry (URI match), with a call-qualifier fallback.
Callees are collected by walking the target method's own AST.

Output contract (from formatCallersOutput / formatCalleesOutput):
  Heading:    "## Call Hierarchy: <modulePath> :: <methodName>"
  callers ->  "**Direction:** Callers (who calls this method)"
              "**Total references found:** <n>"
              then a table: "| # | Module | Method | Line | Call Code |"
              or "No callers found." when n == 0.
  callees ->  "**Direction:** Callees (what this method calls)"
              "**Total calls found:** <n>"
              then a table: "| # | Called Method | Line | Call Code |"
              or "No calls found in this method." when n == 0.

Fixture truth (committed) — CommonModule.Calc at
TestConfiguration/src/CommonModules/Calc/Module.bsl (tab-indented, 7 lines):
  1: Функция Add(A, B) Экспорт        <- Function "Add" (exported), params A, B
  2:     Возврат A + B;
  3: КонецФункции
  5: Процедура Test() Экспорт         <- Procedure "Test" (exported)
  6:     Результат = Add(1, 2);       <- the ONE call to Add (the incoming reference)
  7: КонецПроцедуры
So the ground truth used for the asserts:
  - callers(Add)  -> exactly 1 reference: in module CommonModules/Calc/Module.bsl,
                     caller method "Test", line 6 (the "Результат = Add(1, 2);" call).
  - callers(Test) -> 0 references ("No callers found.")  (nothing calls Test)
  - callees(Test) -> 1 call: "Add" at line 6.
  - callees(Add)  -> 0 calls ("No calls found in this method.")
Other modules: CommonModule.Error (body is the literal token "Error" — NO methods),
CommonModule.OK (empty). Catalog.Catalog exists (used for a non-module-path negative).
modulePath is the src/-relative path "CommonModules/Calc/Module.bsl".

Error contract (the REAL split — verified against the Java + McpProtocolHandler)
-------------------------------------------------------------------------------
A MARKDOWN tool's response is flagged isError:true ONLY when the returned string is a
ToolResult.error(...).toJson() payload ({"success":false,...}) — McpProtocolHandler
.isJsonErrorPayload diverts those to a structured JSON error. These paths set isError:
  - missing projectName / modulePath                -> "<name> is required"
  - missing methodName (callers/callees only)       -> "methodName is required for callers/callees"
  - direction not in {callers,callees,outgoing}     -> "direction must be 'callers', 'callees' or 'outgoing'"
  - project does not exist                          -> "Project not found: <name>"
  - module can't be loaded as a BSL Module          -> "Could not load EMF model for <modulePath>. ..."
But the METHOD-NOT-FOUND path returns BslModuleUtils.buildMethodNotFoundResponse — a
PLAIN markdown string ("Error: Method '<name>' not found in <modulePath>" + an
"Available methods" list). That is NOT a {"success":false} payload, so it is delivered
as a normal markdown resource with isError:FALSE (success-with-error-text). We assert
that REAL contract (success + the named bad method + the available-methods list) and
flag the inconsistency with an AUDIT note rather than fudging it to is_error.
"""

import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    wait_for_project_ready,
    _fail,
    PROJECT,
)

# src/-relative module path of the fixture CommonModule.Calc.
CALC = "CommonModules/Calc/Module.bsl"


def _assert_local_add_row(text):
    """Structural invariant for the aggregated OUTGOING row of the fixture's one call
    Test -> Add(1, 2): the row must be '(local)' qualifier + Method Add + ExtAPI '-'.

    Asserts on the row's cells (a single '| ... |' table line that carries BOTH the
    '(local)' qualifier AND the Add method AND a trailing ' - |' ExtAPI cell) rather than
    on a brittle exact line number / count, per the slice's uncertainty rule. This proves
    the frozen classification: an unqualified local call is bucketed under '(local)' and a
    '(local)' row is ALWAYS extApi=false (rendered '-', never 'yes')."""
    for line in (text or "").splitlines():
        cells = [c.strip() for c in line.split("|")]
        # A data row renders as: '' | Qualifier | Method | Count | First line | ExtAPI | ''
        # (leading/trailing empties from the surrounding pipes). Match the local Add target.
        if "(local)" in cells and "Add" in cells and cells and cells[-2] == "-":
            return
    _fail("expected an aggregated outgoing row for the local Add call "
          "(Qualifier '(local)', Method 'Add', ExtAPI '-') in:\n%s" % (text or "")[:500])


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_callers_of_add_finds_the_line6_call_in_test():
    """callers(Add): the ONLY caller is the "Результат = Add(1, 2);" call on line 6,
    inside method Test, in the Calc module. Asserting the heading, the Callers
    direction banner, the exact total (1), and the table row that carries BOTH the
    caller method "Test" AND line "6" proves the tool actually (a) resolved Add,
    (b) found the real invocation via AST, (c) attributed it to the enclosing method,
    and (d) reported the correct line. A broken finder (no-op / wrong direction /
    fabricated count) fails at least one of these fixture-specific asserts."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Add",
        "direction": "callers",
    })
    assert_ok(r, "callers of Calc.Add")
    assert r.structured is None, \
        "get_method_call_hierarchy is a MARKDOWN tool; structuredContent must be None"

    assert_contains(r.text, "## Call Hierarchy: " + CALC + " :: Add",
                    "heading must echo the module path and the resolved method name")
    assert_contains(r.text, "**Direction:** Callers (who calls this method)",
                    "callers direction banner must be present")
    # Exactly one incoming reference — the line-6 call. A precise count (not >=1)
    # catches a finder that over-counts (e.g. matching the definition) or under-counts.
    assert_contains(r.text, "**Total references found:** 1",
                    "Add has exactly one caller (the line-6 call in Test)")
    # The single caller row: enclosing method "Test" + line 6. Both are fixture facts;
    # a finder that lost the enclosing method or mis-reported the line would fail here.
    assert_contains(r.text, "| Test |",
                    "the caller's enclosing method must be Test")
    assert_contains(r.text, "| 6 |",
                    "the call to Add is on line 6")
    # The call snippet must name Add (the actual invocation text), not the definition.
    assert_contains(r.text, "Add(1, 2)",
                    "the rendered call code must be the real Add(1, 2) invocation")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_callers_default_direction_is_callers():
    """direction omitted -> execute() defaults it to "callers". Asserting the Callers
    banner AND the same single line-6 reference proves the default is wired to the
    callers branch (not callees, and not an error). A default flipped to callees would
    render "**Direction:** Callees ..." and fail this."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Add",
    })
    assert_ok(r, "default-direction callers of Calc.Add")
    assert_contains(r.text, "**Direction:** Callers (who calls this method)",
                    "omitted direction must default to Callers")
    assert_contains(r.text, "**Total references found:** 1",
                    "default callers must still find the single line-6 reference")
    assert_contains(r.text, "| Test |",
                    "default callers must attribute the call to method Test")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_method_name_is_case_insensitive():
    """findMethod matches case-insensitively. Requesting "add" (lowercase) must resolve
    the real "Add" and still find its caller. The heading echoes the REQUESTED name
    ("add"), while the resolution is the real method — so we assert the real result
    (1 reference, Test, line 6), which a case-sensitive (broken) resolver would miss
    by returning a method-not-found body instead."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "add",
        "direction": "callers",
    })
    assert_ok(r, "case-insensitive resolution of add")
    # Real resolution succeeded -> the callers table, not the not-found body.
    assert_contains(r.text, "**Total references found:** 1",
                    "case-insensitive 'add' must resolve real Add and find its caller")
    assert_contains(r.text, "| Test |",
                    "case-insensitive resolution must still attribute the caller to Test")
    assert_not_contains(r.text, "not found",
                        "a successful case-insensitive resolution must NOT emit a not-found body")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_callees_of_test_lists_add_at_line6():
    """callees(Test): Test's body makes exactly ONE call — Add, on line 6. Asserting
    the Callees banner, the exact total (1), the "Called Method" Add, and line 6 proves
    the AST walk of Test's own body found the outgoing call. A finder that confused
    callers/callees, or walked the wrong method, would not produce "Add" + line 6 here."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Test",
        "direction": "callees",
    })
    assert_ok(r, "callees of Calc.Test")
    assert_contains(r.text, "## Call Hierarchy: " + CALC + " :: Test",
                    "heading must echo the module path and Test")
    assert_contains(r.text, "**Direction:** Callees (what this method calls)",
                    "callees direction banner must be present")
    assert_contains(r.text, "**Total calls found:** 1",
                    "Test makes exactly one call (Add)")
    assert_contains(r.text, "| Add |",
                    "the called method must be Add")
    assert_contains(r.text, "| 6 |",
                    "the call to Add is on line 6")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_callers_of_uncalled_test_reports_none():
    """callers(Test): nothing in the fixture calls Test, so the tool must report the
    EMPTY-but-valid result: total 0 + "No callers found." This is a real, deterministic
    contract (not an error). Asserting "**Total references found:** 0" together with the
    "No callers found." sentinel proves the tool genuinely searched and found nothing —
    a broken finder that fabricates a caller (e.g. counts the definition) would NOT
    print 0 / the no-callers sentinel."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Test",
        "direction": "callers",
    })
    assert_ok(r, "callers of the uncalled Test")
    assert_contains(r.text, "**Direction:** Callers (who calls this method)",
                    "callers banner must be present even for zero results")
    assert_contains(r.text, "**Total references found:** 0",
                    "nothing calls Test -> zero references")
    assert_contains(r.text, "No callers found.",
                    "zero callers -> the explicit no-callers sentinel, not an empty table")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_callees_of_leaf_add_reports_none():
    """callees(Add): Add's body is just "Возврат A + B;" — it calls nothing. The tool
    must report total 0 + "No calls found in this method." Asserting the zero total and
    the sentinel proves the AST walk ran on Add and correctly found no invocations (a
    finder that leaks A/B or "+" as calls would NOT print 0)."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Add",
        "direction": "callees",
    })
    assert_ok(r, "callees of the leaf Add")
    assert_contains(r.text, "**Direction:** Callees (what this method calls)",
                    "callees banner must be present even for zero results")
    assert_contains(r.text, "**Total calls found:** 0",
                    "Add calls nothing -> zero calls")
    assert_contains(r.text, "No calls found in this method.",
                    "zero callees -> the explicit no-calls sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS — direction="outgoing" (aggregated outgoing-calls mode)
#
# "outgoing" aggregates the DISTINCT call targets of a scope (a single method when
# methodName is given, else the whole module) into a table keyed by qualifier+method,
# counting the call sites and reporting the first line. The classification is RESOLVED,
# not textual: an UNQUALIFIED local call (methodAccess is a StaticFeatureAccess) is
# bucketed under the '(local)' qualifier token and is ALWAYS extApi=false ('-').
#
# Fixture truth used below: Test's body makes ONE unqualified local call — Add(1, 2) on
# line 6. So both the scoped (methodName="Test") and the module-wide (no methodName,
# which also walks Test) outgoing views must surface a target row with Method=Add,
# Qualifier=(local), ExtAPI=-. Add itself calls nothing, so it contributes no target.
# We assert the frozen wire shape (heading, banner, columns, the (local) token, ExtAPI=-)
# rather than brittle exact counts/line numbers, since the module-wide total depends on
# how many distinct targets the whole module has.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_outgoing_scoped():
    """direction="outgoing" WITH methodName="Test": the aggregated outgoing-calls view of
    Test. Test makes exactly one call — the unqualified local Add(1, 2) on line 6 — so the
    scoped ('... :: Test') outgoing heading, the Outgoing banner, and a single '(local)'
    target row for Add (ExtAPI '-') prove: (a) the outgoing direction is wired, (b) it
    walks Test's own AST, (c) an unqualified call is classified as '(local)' (a
    StaticFeatureAccess), NOT as an ext-API or an '(expr)' qualifier, and (d) a local
    row is never marked ExtAPI. A finder that mis-classified the qualifier, or reused the
    callers/callees branch, would fail these asserts."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Test",
        "direction": "outgoing",
    })
    assert_ok(r, "outgoing of Calc.Test")
    assert r.structured is None, \
        "get_method_call_hierarchy is a MARKDOWN tool; structuredContent must be None"

    # Scoped heading echoes the module path AND the resolved method (the ' :: Test' tail).
    assert_contains(r.text, "## Outgoing Calls: " + CALC + " :: Test",
                    "scoped outgoing heading must echo the module path and the method")
    assert_contains(r.text, "**Direction:** Outgoing calls (aggregated targets)",
                    "outgoing direction banner must be present")
    assert_contains(r.text, "**Total distinct targets:**",
                    "outgoing view must report a distinct-target total")
    # The exact aggregation table header (the frozen wire columns).
    assert_contains(r.text, "| Qualifier | Method | Count | First line | ExtAPI |",
                    "outgoing table must use the frozen aggregated-columns header")
    # The one target: the unqualified local Add call -> Qualifier '(local)', Method Add,
    # ExtAPI '-' (a local row is always non-ext-API).
    assert_contains(r.text, "| Add |",
                    "the aggregated outgoing target must include the Add call")
    assert_contains(r.text, "| (local) |",
                    "an unqualified local call must be bucketed under the '(local)' qualifier")
    _assert_local_add_row(r.text)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_outgoing_module_wide():
    """direction="outgoing" WITHOUT methodName: methodName is OPTIONAL for outgoing, so the
    tool aggregates the outgoing calls of the WHOLE module. Asserting the module-wide
    heading (no ' :: ' scope tail), the Outgoing banner, and that the local Add target
    still appears proves the module-wide walk (module.eAllContents) collected Test's call
    to Add — i.e. omitting methodName does NOT error and does NOT scope to a single method.
    A tool that still required methodName for outgoing would error here."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "direction": "outgoing",
    })
    assert_ok(r, "module-wide outgoing of Calc")
    # Module-wide heading has NO ' :: <method>' scope tail.
    assert_contains(r.text, "## Outgoing Calls: " + CALC,
                    "module-wide outgoing heading must echo the module path")
    assert_not_contains(r.text, "## Outgoing Calls: " + CALC + " ::",
                        "module-wide outgoing (no methodName) must NOT carry a ' :: <method>' scope tail")
    assert_contains(r.text, "**Direction:** Outgoing calls (aggregated targets)",
                    "outgoing direction banner must be present module-wide")
    assert_contains(r.text, "| Qualifier | Method | Count | First line | ExtAPI |",
                    "module-wide outgoing table must use the frozen aggregated-columns header")
    # The module's only call is Test -> Add (unqualified local), so the (local) Add target
    # must appear in the module-wide aggregation too.
    assert_contains(r.text, "| Add |",
                    "the module-wide aggregation must include the Add target from Test")
    _assert_local_add_row(r.text)
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory) — true ToolResult.error (isError:true) paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_missing_projectname_errors():
    """requireArguments checks projectName FIRST -> "projectName is required". This is a
    ToolResult.error payload, so it surfaces as a structured isError even for a MARKDOWN
    tool (McpProtocolHandler.isJsonErrorPayload diversion)."""
    r = call("get_method_call_hierarchy", {
        "modulePath": CALC,
        "methodName": "Add",
    })
    e = assert_error(r, "missing projectName")
    # AUDIT: the guard names the missing param but offers no next step (no list_projects
    # hint to discover a valid project). suggests=[] is intentional -> fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_missing_modulepath_errors():
    """projectName present but modulePath omitted -> the second requireArguments check
    fires -> "modulePath is required". (projectName must be present, else its guard wins
    first — this isolates the modulePath branch.)"""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "methodName": "Add",
    })
    e = assert_error(r, "missing modulePath")
    # AUDIT: names the param but no actionable next step (no list_modules / path-shape
    # hint). suggests=[] -> fix-card.
    assert_error_quality(e, names=["modulePath"], suggests=[],
                         ctx="missing modulePath names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_missing_methodname_errors():
    """projectName + modulePath present but methodName omitted (direction omitted ->
    defaults to callers, where methodName IS required) -> the methodName guard fires ->
    "methodName is required for callers/callees". (methodName is OPTIONAL only for
    direction="outgoing" — see test_outgoing_module_wide; here the default callers
    direction still requires it.)"""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
    })
    e = assert_error(r, "missing methodName")
    # Actionable: names the missing param AND points at get_module_structure (the sibling
    # tool that lists the module's procedures and functions) as the next step.
    assert_error_quality(e, names=["methodName"], suggests=["get_module_structure"],
                         ctx="missing methodName names the param and points at get_module_structure")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_invalid_direction_enum_errors_and_names_valid_values():
    """direction is an enum {callers,callees,outgoing}. A value outside it ("sideways") is
    rejected AFTER the required-arg check -> ToolResult.error("direction must be
    'callers', 'callees' or 'outgoing'"). The message is actionable: it enumerates the
    three valid values. A tool that silently treated an unknown direction as the default
    would NOT error here -> this guards the enum validation."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Add",
        "direction": "sideways",
    })
    e = assert_error(r, "invalid direction enum")
    # Actionable: names the offending param AND lists the three valid enum values.
    assert_error_quality(e, names=["direction"], suggests=["callers", "callees", "outgoing"],
                         ctx="invalid direction names the param and the valid values")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists() is
    false (in findCallers) -> ToolResult.error(ProjectContext.notFoundMessage(bad)),
    i.e. "Project not found: <name>. Use list_projects to see available projects." Names
    the bad project so the caller knows WHICH value was wrong, AND points at list_projects
    to discover a valid one."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_method_call_hierarchy", {
        "projectName": bad,
        "modulePath": CALC,
        "methodName": "Add",
    })
    e = assert_error(r, "non-existent project")
    # Actionable: names the bad project AND points at list_projects (the sibling tool that
    # enumerates valid project names) via the shared ProjectContext.notFoundMessage tail.
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_nonexistent_module_path_errors_and_names_value():
    """A well-formed but non-existent modulePath cannot be loaded as a BSL Module ->
    loadModule returns null -> ToolResult.error("Could not load EMF model for
    <modulePath>. ..."). Names the offending path. This is the isError path (distinct
    from method-not-found below, which is success-with-error-text)."""
    bad = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": bad,
        "methodName": "Add",
    })
    e = assert_error(r, "non-existent module path")
    # The message names the bad path and points the user at the EDT Error Log; assert
    # the path is named. AUDIT: no sibling-tool hint (e.g. list_modules) to discover a
    # valid module path -> fix-card.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent module path names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_non_module_path_errors_and_names_value():
    """A path that exists in src/ but is NOT a BSL module ("Catalogs/Catalog/Catalog.mdo")
    also fails to load as a Module (loadModule returns null) -> "Could not load EMF model
    for <path>. ...". Uses a REAL metadata file so the rejection is about it not being a
    BSL Module, not about it being missing."""
    bad = "Catalogs/Catalog/Catalog.mdo"
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": bad,
        "methodName": "Add",
    })
    e = assert_error(r, "path is not a BSL module")
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-module path named in the load error")
    assert_no_diff("an invalid call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — method-not-found: REAL contract is success-with-error-text
# (isError:FALSE), NOT a structured error. Documented + asserted, not fudged.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_nonexistent_method_returns_notfound_body_listing_available_methods():
    """Project + module resolve, but the method does not exist in the module ->
    BslModuleUtils.buildMethodNotFoundResponse, which returns a PLAIN markdown string
    ("Error: Method '<name>' not found in <modulePath>" + an "Available methods" list).

    REAL CONTRACT (verified against McpProtocolHandler.isJsonErrorPayload): that string
    is NOT a {"success":false} payload, so it is delivered as a normal markdown resource
    with isError:FALSE — a success-with-error-text, NOT a structured error.

    AUDIT: this is an inconsistency — a genuine "method not found" failure is reported as
    a NON-error (is_error==false) for this MARKDOWN tool, while project/module/direction
    failures correctly set is_error. A schema-driven client checking only isError would
    treat this as success. -> fix-card: route method-not-found through ToolResult.error so
    it is machine-detectable, OR document the success-with-body contract deliberately.

    We assert the REAL contract precisely (so the test still fails if the tool breaks):
    success, the body NAMES the bad method, AND lists the actually-available methods
    (Add, Test) so the user can self-correct — which makes the body itself actionable."""
    bad_method = "NoSuchMethod_e2e"
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": bad_method,
        "direction": "callers",
    })
    # REAL: not a structured error -> assert_ok, then verify the error-text body.
    assert_ok(r, "method-not-found is delivered as success-with-error-text (documented)")
    # The body names the missing method and the module it searched.
    assert_contains(r.text, bad_method,
                    "the not-found body must name the missing method")
    assert_contains(r.text, "not found",
                    "the not-found body must say the method was not found")
    # Actionable body: it enumerates the available methods so the caller can fix the call.
    assert_contains(r.text, "Available methods",
                    "the not-found body must list the available methods")
    assert_contains(r.text, "Add",
                    "available-methods list must include the real method Add")
    assert_contains(r.text, "Test",
                    "available-methods list must include the real method Test")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_method_not_found_in_empty_module_lists_zero_methods():
    """Boundary for the not-found body: CommonModule.Error has NO methods (its body is the
    literal token "Error"). Asking for any method there yields the not-found body with an
    EMPTY available-methods list — "**Available methods** (0):". Asserting the (0) count
    proves the available-methods list reflects the REAL module (not a hardcoded/stale list)
    and that the bad method name is still echoed. Same documented success-with-error-text
    contract as above (isError:FALSE)."""
    bad_method = "Whatever_e2e"
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": "CommonModules/Error/Module.bsl",
        "methodName": bad_method,
        "direction": "callers",
    })
    assert_ok(r, "not-found in an empty module is success-with-error-text (documented)")
    assert_contains(r.text, bad_method,
                    "the not-found body must name the missing method")
    # The empty Error module has zero methods -> the list count must be (0).
    assert_contains(r.text, "(0)",
                    "an empty module's available-methods list must report a count of 0")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# TRANSITIVE CALLERS (depth > 1)
#
# The single-hop fixture (Test -> Add) is only two methods deep, so every test below
# SEEDS the chain it needs with write_module_source and is declared kind="write-metadata"
# so the runner reverts both the disk fixture and the in-memory model afterwards.
#
# Output contract asserted here (from formatTransitiveCallersOutput):
#   "## Call Hierarchy (transitive): <modulePath> :: <method>"
#   "**Direction:** Callers (who calls this method), transitive"
#   "**Depth:** <n>"                       (+ " (requested N, clamped to max 5)" when clamped)
#   "**Unique callers:** <n>"        (a module BODY is a caller too, hence not "methods")
#   "**Complete through depth <n>:** yes"  | "no - <reasons>"
#   "**Left unexpanded at the depth limit:** <n> ..."   (only when > 0)
#   "**Repeat edges collapsed:** <n> ..."               (only when > 0)
#   "**Not covered:** static invocations only - ..."
#   table: "| # | Level | Module | Method | Line | Via # | Flags |"
# ──────────────────────────────────────────────────────────────────────────────

# The empty fixture module used to seed extra links in the chain.
OK_MODULE = "CommonModules/OK/Module.bsl"
CASCADE_EN = "CommonModules/CascadeEn/Module.bsl"

# OK.Level2 -> Calc.Test -> Calc.Add, and OK.Level3 -> OK.Level2 (an UNQUALIFIED local call,
# which exercises the "caller inside the declaring module" fallback at level 3).
CHAIN_SOURCE = """
Процедура Level2() Экспорт
\tCalc.Test();
КонецПроцедуры

Процедура Level3() Экспорт
\tLevel2();
КонецПроцедуры
"""


def _seed(module_path, source, expect_method):
    """Write BSL into a fixture module and wait until the MODEL actually shows it.

    wait_for_project_ready() alone is not a sufficient gate here: the call hierarchy reads the
    shared BSL resource set, and "the project is ready" does not promise that this module's AST
    has been reparsed with the text we just wrote. So the wait is self-verifying - poll
    get_module_structure for the seeded method and only then run the assertion under test.
    Without it a failure would be indistinguishable from a broken walk."""
    w = call("write_module_source", {
        "projectName": PROJECT, "modulePath": module_path,
        "mode": "append", "source": source,
    })
    assert_ok(w, "seeding %s" % module_path)
    wait_for_project_ready()

    deadline = time.time() + 30
    last = ""
    while time.time() < deadline:
        s = call("get_module_structure", {"projectName": PROJECT, "modulePath": module_path})
        last = s.text or ""
        if expect_method in last:
            return
        time.sleep(0.5)
    _fail("seeded method %r never appeared in the model for %s; structure was:\n%s"
          % (expect_method, module_path, last[:800]))


def _transitive_rows(text):
    """Data rows of the transitive table as dicts keyed by column name.

    Parsing the real cells (rather than substring-matching the whole document) is what lets a
    test say "method X is at level 2 via row 1 with no flag" instead of "the text contains a 2
    somewhere" - the difference between pinning the contract and pinning a coincidence."""
    rows = []
    for line in (text or "").splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 7 or cells[0] == "#":
            continue
        if set(cells[0]) <= set("-"):
            continue
        rows.append({
            "num": cells[0], "level": cells[1], "module": cells[2],
            "method": cells[3], "line": cells[4], "via": cells[5], "flags": cells[6],
        })
    return rows


def _row_for(text, method):
    """The single transitive row for a method name, failing loudly when absent or duplicated."""
    hits = [r for r in _transitive_rows(text) if r["method"] == method]
    if len(hits) != 1:
        _fail("expected exactly ONE row for method %r, got %d in:\n%s"
              % (method, len(hits), (text or "")[:1500]))
    return hits[0]


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_depth_omitted_matches_an_explicit_depth_of_one_byte_for_byte():
    """The default must not change what this tool has always returned.

    depth omitted and depth=1 must produce the IDENTICAL document - same single-hop table, no
    transitive header, no new columns. Comparing the two responses (rather than eyeballing one)
    is what pins "adding the parameter changed nothing for everyone who does not pass it".

    HONEST LIMIT: this test does NOT discriminate against the pre-depth build - measured by
    pinning the stand to it, where an unknown `depth` argument is simply ignored and both calls
    return the same document for a different reason. It is a FORWARD regression guard: it fails
    the day someone lets depth=1 drift away from the single-hop path."""
    base = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add", "direction": "callers",
    })
    assert_ok(base, "callers of Calc.Add without depth")
    explicit = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add",
        "direction": "callers", "depth": 1,
    })
    assert_ok(explicit, "callers of Calc.Add with depth=1")

    if base.text != explicit.text:
        _fail("depth=1 must be byte-identical to omitting depth.\n--- omitted ---\n%s\n--- depth=1 ---\n%s"
              % ((base.text or "")[:900], (explicit.text or "")[:900]))
    # And it must still be the SINGLE-HOP shape, not the transitive one.
    assert_contains(base.text, "## Call Hierarchy: " + CALC + " :: Add",
                    "depth=1 keeps the original heading")
    assert_not_contains(base.text, "(transitive)",
                        "depth=1 must not switch to the transitive renderer")
    assert_not_contains(base.text, "Via #", "depth=1 must not grow the transitive columns")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="write-metadata")
def test_transitive_walk_climbs_three_levels_and_records_the_witness_chain():
    """depth=3 over the seeded chain Add <- Calc.Test <- OK.Level2 <- OK.Level3.

    This is the whole point of the issue: ONE call answers "what breaks if I change Add"
    three levels up. Asserting each method's LEVEL and its Via # (which must point at the row
    of the method that led there) proves the walk really climbed - a tool that just re-ran the
    single hop would report only Test, and one that lost the parent links could not produce a
    Via chain that resolves to the right rows."""
    _seed(OK_MODULE, CHAIN_SOURCE, "Level3")

    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add",
        "direction": "callers", "depth": 3,
    })
    assert_ok(r, "transitive callers of Calc.Add at depth 3")
    assert_contains(r.text, "## Call Hierarchy (transitive): " + CALC + " :: Add",
                    "the transitive renderer must announce itself")
    assert_contains(r.text, "**Direction:** Callers (who calls this method), transitive",
                    "transitive direction banner")
    assert_contains(r.text, "**Depth:** 3", "the effective depth must be stated")
    assert_contains(r.text, "**Unique callers:** 3",
                    "three distinct callers were found, and the header must count them")

    test_row = _row_for(r.text, "Test")
    level2 = _row_for(r.text, "Level2")
    level3 = _row_for(r.text, "Level3")

    assert test_row["level"] == "1", \
        "Calc.Test is a DIRECT caller of Add, got level %s" % test_row["level"]
    assert level2["level"] == "2", \
        "OK.Level2 calls Test, so it is level 2, got %s" % level2["level"]
    assert level3["level"] == "3", \
        "OK.Level3 calls Level2, so it is level 3, got %s" % level3["level"]

    # The witness chain: Level3 -> Level2 -> Test -> (the analyzed method itself).
    assert test_row["via"] == "-", "a level-1 row is reached from the analyzed method, so Via is '-'"
    assert level2["via"] == test_row["num"], \
        "Level2 must point at Test's row (%s), got %s" % (test_row["num"], level2["via"])
    assert level3["via"] == level2["num"], \
        "Level3 must point at Level2's row (%s), got %s" % (level2["num"], level3["via"])

    # Level3 reaches Level2 through an UNQUALIFIED local call inside the SAME module - the
    # fallback path that only counts a caller when the scanned module IS the declaring one.
    assert level3["module"] == OK_MODULE, "Level3 lives in the OK module"
    assert_contains(r.text, "**Complete through depth 3:** yes",
                    "the whole chain fits in the budget, so the walk is complete")


@e2e_test(tool="get_method_call_hierarchy", kind="write-metadata")
def test_the_depth_bound_marks_the_boundary_instead_of_hiding_it():
    """depth=2 over the same chain: Level3 is out of reach, and the row that would have led
    there says so.

    A walk that simply omitted Level3 would be indistinguishable from a chain that ends at
    Level2. The depth-limit flag is what tells the agent "there IS more above this - ask for a
    greater depth". And reaching the requested depth is NOT a truncation, so the result must
    still report itself complete THROUGH that depth."""
    _seed(OK_MODULE, CHAIN_SOURCE, "Level3")

    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add",
        "direction": "callers", "depth": 2,
    })
    assert_ok(r, "transitive callers of Calc.Add at depth 2")

    assert _row_for(r.text, "Test")["level"] == "1", "Test is still the direct caller"
    level2 = _row_for(r.text, "Level2")
    assert level2["level"] == "2", "Level2 is the boundary row"
    assert level2["flags"] == "depth-limit", \
        "the boundary row must say its own callers were not looked for, got %r" % level2["flags"]
    assert not [row for row in _transitive_rows(r.text) if row["method"] == "Level3"], \
        "Level3 is 3 hops away and must NOT appear at depth 2"

    assert_contains(r.text, "**Complete through depth 2:** yes",
                    "stopping at the REQUESTED depth is the answer, not a truncation")
    assert_contains(r.text, "**Left unexpanded at the depth limit:** 1",
                    "the header must count what was left at the boundary")


@e2e_test(tool="get_method_call_hierarchy", kind="write-metadata")
def test_a_cycle_terminates_and_lists_each_method_once():
    """Mutual recursion must not loop, and must not repeat rows.

    Ping calls Pong, Pong calls Ping, and Pong also calls Calc.Test. Asking for the callers of
    Add at depth 5 walks Test -> Pong -> Ping -> (back to Pong). The depth bound is what ends the
    walk; the report-once rule is what keeps each method to ONE row - without it the same methods
    come back on every remaining level - and the edge closing the loop is reported as collapsed
    rather than silently dropped."""
    _seed(OK_MODULE, """
Процедура Pong() Экспорт
\tCalc.Test();
\tPing();
КонецПроцедуры

Процедура Ping() Экспорт
\tPong();
КонецПроцедуры
""", "Ping")

    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add",
        "direction": "callers", "depth": 5,
    })
    assert_ok(r, "transitive callers across a cycle must return, not hang")

    # _row_for fails when a method appears more than once, so these three calls ARE the
    # "each method exactly once" assertion.
    assert _row_for(r.text, "Test")["level"] == "1", "Test is the direct caller of Add"
    assert _row_for(r.text, "Pong")["level"] == "2", "Pong calls Test"
    assert _row_for(r.text, "Ping")["level"] == "3", "Ping calls Pong"

    assert_contains(r.text, "**Repeat edges collapsed:**",
                    "the edge closing the cycle must be reported as collapsed, not hidden")
    assert_contains(r.text, "**Complete through depth 5:** yes",
                    "collapsing a re-convergence loses nothing")


@e2e_test(tool="get_method_call_hierarchy", kind="write-metadata")
def test_a_self_recursive_method_is_listed_once_and_flagged_recursive():
    """A method that calls itself is one of its own callers - a single-hop search shows that row,
    so the transitive walk must not lose it just because the walk starts there. It is reported
    once, flagged 'recursive', and never expanded again."""
    _seed(OK_MODULE, """
Процедура Loop() Экспорт
\tLoop();
КонецПроцедуры
""", "Loop")

    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": OK_MODULE, "methodName": "Loop",
        "direction": "callers", "depth": 4,
    })
    assert_ok(r, "transitive callers of a self-recursive method")
    row = _row_for(r.text, "Loop")
    assert row["level"] == "1", "the self-call is a level-1 caller"
    assert row["flags"] == "recursive", \
        "the analyzed method reached back through the graph must be flagged, got %r" % row["flags"]


@e2e_test(tool="get_method_call_hierarchy", kind="write-metadata")
def test_the_node_budget_cuts_the_walk_and_says_the_total_is_unknown():
    """limit at depth>1 bounds the WALK, not just the rendering - and the header must say which.

    With limit=1 the walk stops after one method, so how many callers exist is not merely
    unshown, it is UNKNOWN: the methods that were never expanded may have any number behind
    them. Reporting that as an ordinary "showing 1 of N" would be a claim the tool cannot make."""
    _seed(OK_MODULE, CHAIN_SOURCE, "Level3")

    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add",
        "direction": "callers", "depth": 3, "limit": 1,
    })
    assert_ok(r, "transitive callers with a budget of one node")

    rows = _transitive_rows(r.text)
    assert len(rows) == 1, "a budget of 1 must yield exactly one row, got %d" % len(rows)
    assert_contains(r.text, "**Complete through depth 3:** no",
                    "a walk cut by the budget is not complete")
    assert_contains(r.text, "unknown",
                    "the header must say the true number of callers is UNKNOWN, not just unshown")
    assert_contains(r.text, "limit=1", "the header must name the budget that cut the walk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_depth_above_the_ceiling_is_clamped_and_the_header_admits_it():
    """A depth beyond the maximum is clamped rather than refused, and the response says so -
    silently answering a shallower question than the one asked is how an agent concludes
    "nothing else calls this" from a walk that never went that far."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Add",
        "direction": "callers", "depth": 99,
    })
    assert_ok(r, "an over-large depth is clamped, not rejected")
    assert_contains(r.text, "**Depth:** 5 (requested 99, clamped to max 5)",
                    "the header must state both the effective and the requested depth")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="write-metadata")
def test_same_method_name_in_two_modules_does_not_merge_their_callers():
    """Two modules may declare the same method name; a batched walk must keep them apart.

    The fixture already has CascadeEn.Marker (called by CascadeUser.Запуск). Seeding a SECOND
    Marker in the OK module, with its own caller, creates exactly the collision that a walk
    keyed by method NAME instead of method IDENTITY would merge - it would report OK's caller as
    a caller of CascadeEn.Marker, which is simply false."""
    _seed(OK_MODULE, """
Функция Marker() Экспорт
\tВозврат 2;
КонецФункции

Процедура ВызовМаркераOK() Экспорт
\tX = OK.Marker();
КонецПроцедуры
""", "ВызовМаркераOK")

    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CASCADE_EN, "methodName": "Marker",
        "direction": "callers", "depth": 3,
    })
    assert_ok(r, "transitive callers of CascadeEn.Marker")

    methods = [row["method"] for row in _transitive_rows(r.text)]
    assert "Запуск" in methods, \
        "CascadeUser.Запуск calls CascadeEn.Marker and must be found, got %s" % methods
    assert "ВызовМаркераOK" not in methods, \
        "the caller of the SAME-NAMED OK.Marker must not be attributed here, got %s" % methods


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX for depth
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_depth_above_one_with_callees_is_refused_and_points_at_callers():
    """depth>1 is callers-only. callees reports the raw invocation names it finds and never
    resolves them to the modules that DEFINE them, so recursing it would fabricate a dependency
    graph. Refusing loudly beats answering confidently and wrongly - and the refusal must name
    the direction that does work."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Test",
        "direction": "callees", "depth": 3,
    })
    err = assert_error(r, "depth>1 with callees must be refused")
    assert_error_quality(err, names=["callees", "depth"], suggests=["callers"],
                         ctx="the refusal must name the bad combination and the working direction")
    assert_no_diff("a refused call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_depth_above_one_with_outgoing_is_refused():
    """Same rule for the aggregated outgoing view, which also does not resolve its targets."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "direction": "outgoing", "depth": 2,
    })
    err = assert_error(r, "depth>1 with outgoing must be refused")
    assert_error_quality(err, names=["outgoing", "depth"], suggests=["callers"],
                         ctx="the refusal must name the bad combination and the working direction")
    assert_no_diff("a refused call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_depth_clamped_back_to_one_is_accepted_for_every_direction():
    """depth=0 clamps to 1, i.e. it asks for the single hop every direction supports. Refusing it
    would reject a request identical to omitting the parameter - the guard must fire on the
    EFFECTIVE depth, not on the presence of the argument.

    HONEST LIMIT: like the byte-identical test above, this one also passes against the pre-depth
    build (which has no guard at all to fire), so it proves the guard's SHAPE going forward rather
    than proving this change."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT, "modulePath": CALC, "methodName": "Test",
        "direction": "callees", "depth": 0,
    })
    assert_ok(r, "depth=0 with callees is just the single hop")
    assert_contains(r.text, "**Direction:** Callees (what this method calls)",
                    "it must run the ordinary single-hop callees path")
    assert_no_diff("a read tool must not touch the project on disk")

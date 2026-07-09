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

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
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

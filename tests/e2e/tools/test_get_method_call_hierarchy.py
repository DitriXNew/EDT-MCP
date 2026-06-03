"""
e2e tests for get_method_call_hierarchy (kind: read).

What the tool does
------------------
Reports a BSL method's call hierarchy in ONE direction:
  - direction="callers" (default): who calls this method (incoming).
  - direction="callees": what this method calls (outgoing).
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
  - missing projectName / modulePath / methodName  -> "<name> is required"
  - direction not in {callers,callees}             -> "direction must be 'callers' or 'callees'"
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
    PROJECT,
)

# src/-relative module path of the fixture CommonModule.Calc.
CALC = "CommonModules/Calc/Module.bsl"


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
    """projectName + modulePath present but methodName omitted -> the third
    requireArguments check fires -> "methodName is required"."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
    })
    e = assert_error(r, "missing methodName")
    # AUDIT: names the param but no actionable next step (no hint to use
    # get_module_structure to list method names). suggests=[] -> fix-card.
    assert_error_quality(e, names=["methodName"], suggests=[],
                         ctx="missing methodName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_invalid_direction_enum_errors_and_names_valid_values():
    """direction is an enum {callers,callees}. A value outside it ("sideways") is
    rejected AFTER the required-arg check -> ToolResult.error("direction must be
    'callers' or 'callees'"). The message is actionable: it enumerates the two valid
    values. A tool that silently treated an unknown direction as the default would NOT
    error here -> this guards the enum validation."""
    r = call("get_method_call_hierarchy", {
        "projectName": PROJECT,
        "modulePath": CALC,
        "methodName": "Add",
        "direction": "sideways",
    })
    e = assert_error(r, "invalid direction enum")
    # Actionable: names the offending param AND lists the two valid enum values.
    assert_error_quality(e, names=["direction"], suggests=["callers", "callees"],
                         ctx="invalid direction names the param and the valid values")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_method_call_hierarchy", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists() is
    false (in findCallers) -> ToolResult.error("Project not found: <name>"). Names the
    bad project so the caller knows WHICH value was wrong."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_method_call_hierarchy", {
        "projectName": bad,
        "modulePath": CALC,
        "methodName": "Add",
    })
    e = assert_error(r, "non-existent project")
    # AUDIT: names the bad project but is not actionable — no pointer to list_projects
    # (the sibling tool that enumerates valid project names). suggests=[] -> fix-card.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
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

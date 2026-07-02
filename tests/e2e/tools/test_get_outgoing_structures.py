"""
e2e tests for get_outgoing_structures (kind: read).

What the tool does
------------------
Best-effort read tool (companion to the aggregated outgoing-call view). For each
OUTGOING qualified call `<var>.Метод(Аргумент)` in a module (or one method) it reports
the top-level LITERAL keys of the Структура (Structure) passed as that call's argument,
collected from `<Аргумент>.Вставить("key",…)` / `<Аргумент>.Insert("key",…)` on the
argument variable in the SAME method, expanding a same-module seed helper ONE level.
It is flow-insensitive and reports literal keys only; anything it cannot read reliably
(non-literal key / `Новый Структура("a,b")` / an external helper / a multi-part literal)
marks that record partial=true instead of guessing.

Addressing is by (projectName, modulePath) only — there is NO fqn / moduleType
parameter (this matches get_method_call_hierarchy). An optional `method` scopes the scan
to one procedure/function (resolved case-insensitively by the shared BslModuleUtils
finder); an optional `qualifier` keeps only calls whose qualifier (the name before the
dot) matches; an optional `limit` caps the record count.

Response shape (IMPORTANT — this is a JSON tool)
------------------------------------------------
GetOutgoingStructuresTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text — for a JSON tool r.text is just a "Done"/error
placeholder). The frozen success envelope is:
    {"success": true,
     "structures": [
        {"qualifier"?,          # omitted for a local (unqualified) call
         "method",              # enclosing method name
         "line",                # the call-site line
         "arg"?,                # the argument variable name (optional)
         "keys": [ "<literal key>", ... ],
         "viaHelper"?: true,    # only when a one-level same-module helper contributed
         "partial"?: true},     # only when a key source was unreliable
        ...],
     "structureCount": <int>,   # number of structure records
     "truncated": <bool>}       # true when the record cap (limit) was hit
On error the envelope is {"success": false, "error": "<message>"} and the protocol
layer marks the result isError; assert_error returns that error string.

Why the happy path does NOT assert fixed keys
---------------------------------------------
The committed TestConfiguration fixture has NO module that both builds a Структура with
`<var>.Вставить("...")` AND forwards it to an outgoing call, so there is no ground-truth
key list to assert (inventing fixture data is out of scope for this slice). Instead the
happy path pins the tool's STRUCTURAL INVARIANTS against KNOWN fixture modules, which
break if the tool is broken regardless of how many structures exist:
  - the JSON envelope is present and well-formed (success=true),
  - "structures" is a list, "structureCount" is an int == len(structures),
  - "truncated" is a bool,
  - every record obeys the frozen per-record shape (method/line/keys types; qualifier/
    arg/viaHelper/partial optional and correctly typed when present),
  - a module whose only outgoing qualified calls carry NO structure argument
    (CommonModule.CascadeUser: `CascadeEn.Marker()` / `Вычисление.Маркер()`) yields an
    EMPTY structures list — proving the tool distinguishes a struct-carrying argument
    from a plain call rather than emitting garbage.
This is ENVIRONMENT-TOLERANT in the same spirit as get_applications' count==0 handling.

Fixture truth (committed) used below
------------------------------------
  CommonModules/CascadeUser/Module.bsl  (procedure Запуск, exported):
      X = CascadeEn.Marker();     <- outgoing qualified call, NO structure argument
      Y = Вычисление.Маркер();    <- outgoing qualified call, NO structure argument
  CommonModules/Calc/Module.bsl  (Add exported; Test exported):
      Test body: `Результат = Add(1, 2);`  <- an UNqualified local call, no struct arg
  CommonModules/Error/Module.bsl  (body is the literal token "Error" — NO methods).
  Catalogs/Catalog/Catalog.mdo   (a real metadata file, NOT a BSL module).
modulePath is always the src/-relative path.

Error contract (mirrors the frozen skeleton = GetMethodCallHierarchyTool)
-------------------------------------------------------------------------
requireArguments(projectName, modulePath) runs BEFORE syncExec; a missing one yields a
ToolResult.error(...).toJson() payload -> isError:true. A non-existent project fails
ProjectContext.exists() -> "Project not found: <name>. Use list_projects ...". A path
that is not a loadable BSL Module -> "Could not load EMF model for <modulePath>. ...".
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

# src/-relative module paths of fixture modules used below.
CASCADE_USER = "CommonModules/CascadeUser/Module.bsl"   # 2 outgoing calls, NO struct args
CALC = "CommonModules/Calc/Module.bsl"                   # local call only, no struct arg


# ──────────────────────────────────────────────────────────────────────────────
# Shared shape validation (pins every assertion to the FROZEN JSON contract)
# ──────────────────────────────────────────────────────────────────────────────
def _validate_envelope(r, ctx):
    """Assert the success envelope obeys the frozen shape and return it. This is the
    contract every happy-path test shares, so a shape regression fails loudly here."""
    assert_ok(r, ctx)
    s = r.structured
    assert isinstance(s, dict), \
        "%s: get_outgoing_structures is a JSON tool; structuredContent must be a dict, got %r" % (ctx, type(s))
    assert s.get("success") is True, \
        "%s: envelope must carry success=true, got %r" % (ctx, s.get("success"))

    structures = s.get("structures")
    assert isinstance(structures, list), \
        "%s: 'structures' must be a list, got %r" % (ctx, type(structures))

    count = s.get("structureCount")
    assert isinstance(count, int) and not isinstance(count, bool), \
        "%s: 'structureCount' must be an int, got %r" % (ctx, count)
    assert count == len(structures), \
        "%s: structureCount (%r) must equal len(structures) (%d)" % (ctx, count, len(structures))

    trunc = s.get("truncated")
    assert isinstance(trunc, bool), \
        "%s: 'truncated' must be a bool, got %r" % (ctx, trunc)

    for i, rec in enumerate(structures):
        _validate_record(rec, "%s[record %d]" % (ctx, i))
    return s


def _validate_record(rec, ctx):
    """Assert one structure record obeys the frozen per-record shape."""
    assert isinstance(rec, dict), "%s: record must be a dict, got %r" % (ctx, type(rec))
    # Mandatory fields.
    assert isinstance(rec.get("method"), str) and rec.get("method"), \
        "%s: 'method' must be a non-empty string, got %r" % (ctx, rec.get("method"))
    assert isinstance(rec.get("line"), int) and not isinstance(rec.get("line"), bool), \
        "%s: 'line' must be an int, got %r" % (ctx, rec.get("line"))
    keys = rec.get("keys")
    assert isinstance(keys, list), "%s: 'keys' must be a list, got %r" % (ctx, type(keys))
    assert all(isinstance(k, str) for k in keys), \
        "%s: every key must be a string, got %r" % (ctx, keys)
    # Optional fields — present only in their documented cases, correctly typed when present.
    if "qualifier" in rec:
        assert isinstance(rec["qualifier"], str) and rec["qualifier"], \
            "%s: 'qualifier' when present must be a non-empty string, got %r" % (ctx, rec["qualifier"])
    if "arg" in rec:
        assert isinstance(rec["arg"], str), \
            "%s: 'arg' when present must be a string, got %r" % (ctx, rec["arg"])
    if "viaHelper" in rec:
        assert rec["viaHelper"] is True, \
            "%s: 'viaHelper' is emitted only as true, got %r" % (ctx, rec["viaHelper"])
    if "partial" in rec:
        assert rec["partial"] is True, \
            "%s: 'partial' is emitted only as true, got %r" % (ctx, rec["partial"])


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (environment-tolerant: structural invariants + empty-structures)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_outgoing_structures", kind="read")
def test_whole_module_returns_wellformed_envelope():
    """Scanning a whole KNOWN module returns the frozen success envelope with a list
    'structures', an int 'structureCount' equal to its length, and a bool 'truncated'.
    Every record (if any) obeys the per-record shape. This proves the tool runs the AST
    scan and emits the contract, independent of how many structures the fixture yields."""
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": CASCADE_USER,
    })
    _validate_envelope(r, "whole-module scan of CascadeUser")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_outgoing_calls_without_struct_arg_yield_empty_structures():
    """CommonModule.CascadeUser.Запуск makes TWO outgoing qualified calls
    (`CascadeEn.Marker()`, `Вычисление.Маркер()`) but NEITHER passes a Структура. The
    tool must therefore report an EMPTY structures list (structureCount==0, truncated
    false) — proving it distinguishes a struct-carrying argument from a plain call and
    does NOT fabricate keys for calls that carry none. A broken tool that emitted a
    record per outgoing call (or invented keys) would fail structureCount==0."""
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": CASCADE_USER,
    })
    s = _validate_envelope(r, "CascadeUser has outgoing calls but no struct arguments")
    assert s["structureCount"] == 0, \
        "outgoing calls carrying no Структура must yield zero structure records, got %d: %r" \
        % (s["structureCount"], s["structures"])
    assert s["structures"] == [], \
        "structures must be the empty list when no call carries a Структура, got %r" % (s["structures"],)
    assert s["truncated"] is False, \
        "an empty result cannot be truncated"
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_method_scope_restricts_to_one_method():
    """The optional `method` scopes the scan to a single procedure (resolved by the shared
    case-insensitive finder). Scoping CascadeUser to its real method Запуск must still
    return the well-formed envelope (and, with no struct-carrying call, an empty list),
    proving the method branch is wired and resolves the real method rather than erroring."""
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": CASCADE_USER,
        "method": "Запуск",
    })
    s = _validate_envelope(r, "method-scoped scan of CascadeUser.Запуск")
    # Запуск's outgoing calls carry no Структура -> empty, same ground truth as above.
    assert s["structureCount"] == 0, \
        "method-scoped Запуск carries no Структура argument -> zero records, got %d" % s["structureCount"]
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_qualifier_filter_narrows_calls():
    """The optional `qualifier` keeps only calls whose qualifier matches. Filtering
    CascadeUser to qualifier "CascadeEn" (which only its first outgoing call uses) must
    still return the well-formed envelope. Since neither call carries a Структура the
    result stays empty; the point is the filter path runs without error and preserves the
    frozen shape (a broken filter would crash or violate the envelope)."""
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": CASCADE_USER,
        "qualifier": "CascadeEn",
    })
    s = _validate_envelope(r, "qualifier-filtered scan of CascadeUser")
    # No struct-carrying call, so filtering cannot add records; must stay empty + well-formed.
    assert s["structureCount"] == 0, \
        "qualifier filter over struct-less calls -> zero records, got %d" % s["structureCount"]
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_local_only_module_yields_empty_structures():
    """CommonModule.Calc.Test makes only an UNqualified local call (`Результат = Add(1, 2);`)
    with no Структура argument. Scanning Calc must yield the well-formed envelope with zero
    structure records — a local call with a numeric argument must never be mistaken for a
    structure-carrying outgoing call."""
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": CALC,
    })
    s = _validate_envelope(r, "whole-module scan of Calc (local call only)")
    assert s["structureCount"] == 0, \
        "Calc has no Структура-carrying call -> zero records, got %d: %r" \
        % (s["structureCount"], s["structures"])
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory) — true ToolResult.error (isError:true) paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_outgoing_structures", kind="read")
def test_missing_projectname_errors():
    """requireArguments(projectName, modulePath) checks projectName FIRST -> "projectName
    is required". This runs BEFORE syncExec and returns a ToolResult.error payload, so it
    surfaces as a structured isError (McpProtocolHandler.isJsonErrorPayload diversion)."""
    r = call("get_outgoing_structures", {
        "modulePath": CASCADE_USER,
    })
    e = assert_error(r, "missing projectName")
    # AUDIT: the guard names the missing param but offers no next step (no list_projects
    # hint to discover a valid project). suggests=[] is intentional -> fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_missing_modulepath_errors():
    """projectName present but modulePath omitted -> the second requireArguments check
    fires -> "modulePath is required". (projectName must be present, else its guard wins
    first — this isolates the modulePath branch.)"""
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
    })
    e = assert_error(r, "missing modulePath")
    # AUDIT: names the param but no actionable next step (no list_modules / path-shape
    # hint). suggests=[] -> fix-card.
    assert_error_quality(e, names=["modulePath"], suggests=[],
                         ctx="missing modulePath names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists() is
    false -> ToolResult.error(ProjectContext.notFoundMessage(bad)), i.e. "Project not
    found: <name>. Use list_projects to see available projects." Names the bad project so
    the caller knows WHICH value was wrong AND points at list_projects to discover a valid
    one."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_outgoing_structures", {
        "projectName": bad,
        "modulePath": CASCADE_USER,
    })
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_nonexistent_module_path_errors_and_names_value():
    """A well-formed but non-existent modulePath cannot be loaded as a BSL Module ->
    loadModule returns null -> ToolResult.error("Could not load EMF model for
    <modulePath>. ..."). Names the offending path."""
    bad = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": bad,
    })
    e = assert_error(r, "non-existent module path")
    # AUDIT: names the bad path but no sibling-tool hint (e.g. list_modules) to discover a
    # valid module path -> fix-card. suggests=[].
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent module path names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_non_module_path_errors_and_names_value():
    """A path that exists in src/ but is NOT a BSL module ("Catalogs/Catalog/Catalog.mdo")
    also fails to load as a Module (loadModule returns null) -> "Could not load EMF model
    for <path>. ...". Uses a REAL metadata file so the rejection is about it not being a
    BSL Module, not about it being missing."""
    bad = "Catalogs/Catalog/Catalog.mdo"
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": bad,
    })
    e = assert_error(r, "path is not a BSL module")
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-module path named in the load error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_outgoing_structures", kind="read")
def test_nonexistent_method_returns_actionable_notfound():
    """Project + module resolve, but the requested `method` does not exist -> the frozen
    skeleton returns BslModuleUtils.buildMethodNotFoundResponse ("Error: Method '<name>'
    not found in <modulePath>" + an "**Available methods**" list).

    CHANNEL-TOLERANT contract. Whether that not-found body surfaces via r.text or via the
    JSON structuredContent (a JSON tool serializes the payload into structuredContent, and
    the tool-impl slice owns exactly which channel carries a raw not-found string) — and
    whether the protocol layer flags it isError — is not this slice's to pin. What MUST
    hold, in whatever channel, is the ACTIONABLE contract: the response names the missing
    method AND lists the module's real methods (CascadeUser defines Запуск) so the caller
    can self-correct. We search across every channel to assert exactly that."""
    import json as _json
    bad_method = "NoSuchMethod_e2e"
    r = call("get_outgoing_structures", {
        "projectName": PROJECT,
        "modulePath": CASCADE_USER,
        "method": bad_method,
    })
    # Gather all human-readable channels (text, structured error/blob) into one blob so the
    # assertion holds regardless of how the raw not-found string is delivered.
    parts = [r.text or ""]
    if isinstance(r.structured, dict):
        try:
            parts.append(_json.dumps(r.structured, ensure_ascii=False))
        except (TypeError, ValueError):
            parts.append(str(r.structured))
    blob = "\n".join(parts)
    assert bad_method in blob, \
        "the not-found response must name the missing method: %r" % blob[:400]
    assert "not found" in blob.lower(), \
        "the not-found response must say the method was not found: %r" % blob[:400]
    # Actionable: it enumerates the module's real methods (CascadeUser defines Запуск).
    assert "Запуск" in blob, \
        "the not-found response must list the module's available methods: %r" % blob[:400]
    assert_no_diff("a read tool must not touch the project on disk")

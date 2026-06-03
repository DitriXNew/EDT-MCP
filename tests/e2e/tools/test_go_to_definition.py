"""
e2e tests for go_to_definition (kind: read).

What the tool does
------------------
Semantic "Go to Definition" — the inverse of find_references. Given a SYMBOL it
finds where that symbol is DEFINED and returns the definition location + (by
default) its source. ResponseType is MARKDOWN, so the payload lives in r.text and
r.structured is always None. The result is a FrontMatter block (key: value lines)
optionally followed by a fenced ```bsl source block.

REAL input schema (verified against GoToDefinitionTool.getInputSchema / execute) —
there is NO line/column/position parameter; the tool resolves by NAME, not cursor
coordinates:
  - projectName  (required)
  - symbol       (required) — three accepted forms:
        * "ModuleName.MethodName"  (qualified method, e.g. "Calc.Add")
        * "MethodName"             (unqualified — REQUIRES modulePath)
        * "Type.Object"            (metadata FQN, e.g. "Catalog.Catalog";
                                    Russian type token supported)
  - modulePath   (optional) — needed only for an unqualified method name
  - includeSource(optional bool, default true) — emit the ```bsl block or not

Code fixture (committed): CommonModule.Calc at
TestConfiguration/src/CommonModules/Calc/Module.bsl, no BOM, LF, tab-indented:
  1: Функция Add(A, B) Экспорт      <- Function "Add", EXPORTED, params A,B
  2: \tВозврат A + B;
  3: КонецФункции
  4: (blank)
  5: Процедура Test() Экспорт       <- Procedure "Test", EXPORTED, calls Add
  6: \tРезультат = Add(1, 2);
  7: КонецПроцедуры
So go_to_definition("Calc.Add") -> the Function Add definition (line 1 body),
go_to_definition("Calc.Test") -> the Procedure Test definition, and the
unqualified form go_to_definition("Add", modulePath=".../Calc/Module.bsl")
resolves the same Add. Metadata-FQN fixture: Catalog.Catalog (a Catalog with NO
.bsl modules, only a form).

THE KEY CONTRACT GOTCHA (drives several negatives below)
--------------------------------------------------------
The wire `isError` flag is set ONLY when the tool returns a canonical
ToolResult.error JSON payload ({"success":false,...}) — see
McpProtocolHandler.isJsonErrorPayload (success==false boolean only). Two
"failure-shaped" outcomes are NOT JSON error payloads and therefore arrive as
SUCCESS (is_error == false) with a plain-markdown body:
  (a) method not found in a REAL module ->
        BslModuleUtils.buildMethodNotFoundResponse returns the plain string
        "Error: Method '<name>' not found in <modulePath>\n\n**Available methods**…"
  (b) a two-part symbol that is neither a CommonModule method nor a metadata
        object -> buildNotFoundResponse returns "## Symbol not found: <a>.<b>…"
Both literally read like errors but are delivered as is_error:false. assert_error
would FAIL on them, so they are tested as assert_ok + content assertions and the
machine-undetectability is flagged with `# AUDIT` (a fix-card, not fudged).

Outcomes that ARE real ToolResult.error (is_error == true):
  - missing required projectName / symbol      -> "<name> is required"
  - unqualified symbol w/o modulePath          -> "modulePath is required for an
                                                   unqualified method name like '<symbol>'…"
  - project not found                          -> "Project not found: <name>"
  - bad module path for an unqualified method  -> EMF load fails -> text fallback ->
                                                   "Module not found: src/<modulePath>"
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

CALC_MODULE_PATH = "CommonModules/Calc/Module.bsl"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="go_to_definition", kind="read")
def test_qualified_function_add_resolves_definition_with_source():
    """Qualified "Calc.Add" resolves the CommonModule.Calc Function Add and returns
    its real definition: the module path, the method name, type=Function,
    export=true, and the actual source body. Every asserted token is
    fixture-specific so a broken resolver (no-op, wrong module, wrong method, source
    dropped) FAILS at least one assertion."""
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Calc.Add",
    })
    assert_ok(r, "go_to_definition Calc.Add")

    # MARKDOWN tool -> data in r.text, never r.structured.
    assert r.structured is None, \
        "go_to_definition is a MARKDOWN tool; structuredContent must be None"

    # FrontMatter proves WHERE it resolved and WHAT it resolved to.
    assert_contains(r.text, "module: " + CALC_MODULE_PATH,
                    "must report the Calc module path it resolved into")
    assert_contains(r.text, "method: Add",
                    "must report the resolved method name Add")
    assert_contains(r.text, "type: Function",
                    "Add is a Функция -> type Function (not Procedure)")
    assert_contains(r.text, "export: true",
                    "Add is Экспорт -> export true")
    # qualifiedName is emitted because the symbol carried a module prefix.
    assert_contains(r.text, "qualifiedName: Calc.Add",
                    "qualified prefix must round-trip into qualifiedName")

    # The source block carries the REAL Add body (line 1 signature + line 2 return).
    # Proves includeSource defaults on AND the right line range was read.
    assert_contains(r.text, "Функция Add(A, B) Экспорт",
                    "source must contain the real Add signature line")
    assert_contains(r.text, "Возврат A + B;",
                    "source must contain the real Add body line")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_qualified_procedure_test_resolves_as_procedure():
    """Qualified "Calc.Test" resolves the Procedure Test (NOT the Function Add).
    type=Procedure and the body containing the Add call (line 6) distinguish this
    from Add — a resolver that ignored the method name and always returned the
    first method would return Add and FAIL here."""
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Calc.Test",
    })
    assert_ok(r, "go_to_definition Calc.Test")
    assert_contains(r.text, "method: Test",
                    "must resolve the Test method, not Add")
    assert_contains(r.text, "type: Procedure",
                    "Test is a Процедура -> type Procedure")
    # The Test body calls Add -> the call line proves the correct method range.
    assert_contains(r.text, "Результат = Add(1, 2);",
                    "Test's body (the Add call on line 6) must be in the source")
    # Mutation guard: must NOT bleed the Add function body into Test's definition.
    assert_not_contains(r.text, "method: Add",
                        "resolving Test must not also report method Add")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_unqualified_method_with_modulepath_resolves_same_definition():
    """Unqualified "Add" + modulePath resolves the SAME Function Add as the
    qualified form. With no module prefix, qualifiedPrefix is null -> no
    qualifiedName line is emitted (the distinguishing difference vs the qualified
    happy path). A resolver that ignored modulePath could not find Add and would
    fall into the symbol-not-found branch instead."""
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Add",
        "modulePath": CALC_MODULE_PATH,
    })
    assert_ok(r, "go_to_definition unqualified Add + modulePath")
    assert_contains(r.text, "method: Add",
                    "unqualified Add must resolve via modulePath context")
    assert_contains(r.text, "type: Function",
                    "the resolved Add is a Function")
    assert_contains(r.text, "Возврат A + B;",
                    "the resolved Add must carry its real body")
    # Unqualified -> no qualified prefix -> qualifiedName must be ABSENT.
    assert_not_contains(r.text, "qualifiedName:",
                        "an unqualified resolution emits no qualifiedName")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_include_source_false_omits_bsl_block_but_keeps_metadata():
    """includeSource=false must drop the ```bsl source block while still returning
    the definition metadata. Asserting the body line is ABSENT but the metadata is
    PRESENT proves the flag actually gates the source (a tool that ignored the flag
    would still emit the body and FAIL)."""
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Calc.Add",
        "includeSource": "false",
    })
    assert_ok(r, "go_to_definition Calc.Add includeSource=false")
    # Metadata still present.
    assert_contains(r.text, "method: Add",
                    "metadata must remain even with source suppressed")
    assert_contains(r.text, "type: Function",
                    "type metadata must remain even with source suppressed")
    # Source body must be gone (the fenced block + the return line).
    assert_not_contains(r.text, "Возврат A + B;",
                        "includeSource=false must omit the source body")
    assert_not_contains(r.text, "```bsl",
                        "includeSource=false must omit the fenced bsl block")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_metadata_fqn_resolves_object_definition():
    """A metadata FQN "Catalog.Catalog" resolves the Catalog object (NOT a method).
    The fixture Catalog has NO .bsl modules, so the tool reports kind=MetadataObject
    + name=Catalog + the explicit "No BSL modules found" marker. This exercises the
    metadata branch (resolveTwoPartSymbol step 2) distinct from the method branch."""
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Catalog.Catalog",
    })
    assert_ok(r, "go_to_definition Catalog.Catalog (metadata FQN)")
    assert_contains(r.text, "kind: MetadataObject",
                    "a metadata FQN must resolve as a MetadataObject, not a method")
    assert_contains(r.text, "type: Catalog",
                    "the resolved metadata type must be Catalog")
    assert_contains(r.text, "name: Catalog",
                    "the resolved object name must be Catalog")
    # Fixture Catalog has only a form, no .bsl -> the explicit empty-modules marker.
    assert_contains(r.text, "No BSL modules found",
                    "Catalog.Catalog has no .bsl modules -> the empty marker")
    # Mutation guard: a metadata object is not a method -> no method frontmatter.
    assert_not_contains(r.text, "method:",
                        "a metadata object resolution must not emit method metadata")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_russian_type_token_metadata_fqn_normalizes():
    """Bilingual addressing (CLAUDE.md): the metadata TYPE token may be Russian.
    "Справочник.Catalog" (Справочник == Catalog) must normalize via
    MetadataTypeUtils.normalizeFqn and resolve the SAME Catalog object. A tool that
    only accepted the English token would fall through to symbol-not-found here. The
    object NAME ("Catalog") is the programmatic Name and is identical in both
    dialects."""
    # "Справочник" is the Russian type token for Catalog. Kept literal so the test
    # reads in the dialect a 1C user would actually type.
    russian_fqn = "Справочник.Catalog"
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": russian_fqn,
    })
    assert_ok(r, "go_to_definition with Russian type token")
    assert_contains(r.text, "kind: MetadataObject",
                    "Russian type token must still resolve a MetadataObject")
    assert_contains(r.text, "name: Catalog",
                    "Russian-token resolution must yield the same Catalog object")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory) — real ToolResult.error (is_error == true)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="go_to_definition", kind="read")
def test_missing_symbol_errors_actionably():
    """Required `symbol` omitted -> requireArguments returns "symbol is required".
    Machine-detectable error that names the missing param."""
    r = call("go_to_definition", {"projectName": PROJECT})
    e = assert_error(r, "missing symbol")
    # AUDIT: names the missing param but gives no example of the accepted symbol
    # forms ('ModuleName.MethodName' / 'Catalog.Products') — not fully actionable.
    # suggests=[] is intentional -> fix-card, not a weakened assertion.
    assert_error_quality(e, names=["symbol"], suggests=[],
                         ctx="missing symbol names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_missing_projectname_errors_clearly():
    """`symbol` present but required `projectName` omitted -> "projectName is
    required". (symbol must be present, else the requireArguments order reports
    symbol first — projectName is the FIRST required name, so omitting it alone
    fires the projectName guard.)"""
    r = call("go_to_definition", {"symbol": "Calc.Add"})
    e = assert_error(r, "missing projectName")
    # AUDIT: names the param but offers no list_projects hint to discover a valid
    # project. suggests=[] -> fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_unqualified_symbol_without_modulepath_errors_actionably():
    """XOR/conditional guard: an unqualified method name (no dot) with NO modulePath
    cannot be located. execute() short-circuits with
    "modulePath is required for an unqualified method name like 'Add'. Or pass a
    qualified 'ModuleName.MethodName' or a metadata FQN like 'Catalog.Products'."
    This IS an actionable error: it names the offending symbol AND tells you both
    fixes (add modulePath OR qualify the name)."""
    r = call("go_to_definition", {"projectName": PROJECT, "symbol": "Add"})
    e = assert_error(r, "unqualified symbol without modulePath")
    # Names the bad value (Add) and is actionable (mentions modulePath as the fix).
    assert_error_quality(e, names=["Add"], suggests=["modulePath"],
                         ctx="unqualified-no-modulePath names value and the fix")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists() is
    false -> "Project not found: <name>"."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("go_to_definition", {"projectName": bad, "symbol": "Calc.Add"})
    e = assert_error(r, "non-existent project")
    # AUDIT: names the bad project but is not actionable — no pointer to
    # list_projects (the sibling that enumerates valid names). suggests=[] -> fix-card.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_unqualified_method_with_bad_modulepath_errors():
    """Unqualified method + a non-existent modulePath: EMF loadModule returns null,
    so resolution falls to the text fallback, whose file.exists() check fails ->
    ToolResult.error "Module not found: src/<modulePath>". This is a real
    machine-detectable error (distinct from the success-shaped method-not-found in a
    REAL module, tested separately below)."""
    bad_path = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Add",
        "modulePath": bad_path,
    })
    e = assert_error(r, "unqualified method + non-existent modulePath")
    # Names the bad path; "src/" hints where the lookup root is.
    assert_error_quality(e, names=[bad_path], suggests=["src/"],
                         ctx="bad modulePath names the missing path")
    assert_no_diff("an invalid call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — "failure-shaped" but delivered as SUCCESS (is_error == false).
# These are the documented contract gotchas: the body reads like an error but is
# NOT a JSON error payload, so the wire isError stays false. We assert the REAL
# contract (success + the diagnostic body + the helpful list) and AUDIT the fact
# that a programmatic caller cannot detect the failure via isError.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="go_to_definition", kind="read")
def test_method_not_found_in_real_module_is_success_with_diagnostic():
    """A qualified symbol whose MODULE exists but METHOD does not
    ("Calc.NoSuchMethod_e2e"): buildMethodNotFoundResponse returns a plain markdown
    body "Error: Method 'NoSuchMethod_e2e' not found in <modulePath>" + the list of
    available methods (Add, Test). This is NOT a JSON error payload, so the wire
    isError is FALSE. We assert the body names the missing method AND lists the real
    siblings — a broken resolver that silently returned the first method, or dropped
    the available-methods list, would FAIL.

    # AUDIT: method-not-found is delivered as is_error:false (success). A
    # programmatic MCP client cannot detect this failure via the isError flag — it
    # must string-match "not found". This should be a ToolResult.error (machine
    # detectable). Fix-card: route buildMethodNotFoundResponse through ToolResult.error."""
    r = call("go_to_definition", {
        "projectName": PROJECT,
        "symbol": "Calc.NoSuchMethod_e2e",
    })
    # REAL contract: success (NOT is_error) — assert_error would wrongly fail here.
    assert_ok(r, "method-not-found is delivered as success-with-diagnostic")
    assert r.is_error is False, \
        "method-not-found in a real module is delivered as is_error:false (AUDIT)"
    # The diagnostic names the missing method and the module it searched.
    assert_contains(r.text, "NoSuchMethod_e2e",
                    "diagnostic must name the missing method")
    assert_contains(r.text, "not found in",
                    "diagnostic must say the method was not found")
    assert_contains(r.text, CALC_MODULE_PATH,
                    "diagnostic must name the module it searched")
    # Actionable list of the REAL siblings present in the module.
    assert_contains(r.text, "Available methods",
                    "diagnostic must list the available methods")
    assert_contains(r.text, "- Add",
                    "the available-methods list must include the real method Add")
    assert_contains(r.text, "- Test",
                    "the available-methods list must include the real method Test")
    assert_no_diff("a failed lookup must not touch the project on disk")


@e2e_test(tool="go_to_definition", kind="read")
def test_unknown_two_part_symbol_is_success_with_not_found_body():
    """A two-part symbol that is NEITHER a CommonModule method NOR a metadata object
    ("NoSuchModule_e2e.Foo"): resolveTwoPartSymbol falls through to
    buildNotFoundResponse, a plain markdown body "## Symbol not found:
    NoSuchModule_e2e.Foo" followed by supported metadata types and a usage tip. NOT
    a JSON error payload -> wire isError is FALSE.

    # AUDIT: an unresolvable symbol is delivered as is_error:false (success) — a
    # programmatic client cannot detect the failure via isError; it must string-match
    # "Symbol not found". Fix-card: make the not-found branch a ToolResult.error."""
    bad = "NoSuchModule_e2e.Foo"
    r = call("go_to_definition", {"projectName": PROJECT, "symbol": bad})
    # REAL contract: success-with-empty (NOT is_error).
    assert_ok(r, "unresolvable two-part symbol is delivered as success")
    assert r.is_error is False, \
        "unresolvable symbol is delivered as is_error:false (AUDIT)"
    # Names the unresolved symbol so a human knows exactly what failed.
    assert_contains(r.text, "Symbol not found",
                    "body must state the symbol was not found")
    assert_contains(r.text, bad,
                    "body must name the unresolved symbol")
    # The body is at least actionable for a human: it lists supported metadata types
    # and a modulePath usage tip (the resolver's helpful fallback).
    assert_contains(r.text, "modulePath",
                    "the not-found body must include the actionable modulePath tip")
    assert_no_diff("a failed lookup must not touch the project on disk")

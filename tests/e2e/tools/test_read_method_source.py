"""
e2e tests for read_method_source (kind: read).

What the tool does
------------------
Reads procedures/functions out of a BSL module by name, instead of returning
the whole file. Params (see getInputSchema / execute):
  - projectName : EDT project name (required)
  - modulePath  : path from src/, e.g. 'CommonModules/MyModule/Module.bsl' (required)
  - methodName  : procedure/function name, CASE-INSENSITIVE (findMethod uses
                  equalsIgnoreCase) -> the single-method answer below
  - methodNames : up to 50 names read in one call -> one front matter for the module,
                  then one '### <Name>' section per found method in request order
  At least one of methodName / methodNames is required.
There is NO line/column/position parameter — addressing is purely by method
NAME. So the generic "position with no symbol / out-of-range line/column"
negatives do not apply to this tool; the negative matrix below is adapted to
its real surface (bad project / bad module file / bad method name / each missing
required arg).

ResponseType = MARKDOWN, so the happy payload is in r.text (r.structured is None).
A successful read is a YAML front-matter block (FrontMatter) followed by a
```bsl fenced code block carrying the EXACT source lines of the method:

    ---
    projectName: TestConfiguration
    module: CommonModules/Calc/Module.bsl
    method: Add
    type: Function
    export: true
    startLine: 1
    endLine: 3
    totalLines: 7
    ---
    ```bsl
    Функция Add(A, B) Экспорт
    \tВозврат A + B;
    КонецФункции
    ```

Error contract — TWO DISTINCT shapes (verified in the Java)
-----------------------------------------------------------
1. is_error TRUE (ToolResult.error -> {"success":false,...}; the protocol handler
   diverts these to a structured JSON error so is_error is set):
     - missing projectName / modulePath -> "<name> is required"
       (JsonUtils.requireArguments, checked in THAT order); then neither methodName
       nor methodNames -> "methodName is required (or pass methodNames, ...)"
     - non-existent project        -> "Project not found: <projectName>"
     - non-existent module file     -> "File not found: src/<modulePath>"
       (EMF loadModule returns null -> text fallback -> resolveModuleFile not
        exists -> ToolResult.error)
2. is_error FALSE but the text is a "not found" report (success-with-error-text):
     - method name not in the module -> buildMethodNotFoundResponse /
       buildTextMethodNotFoundResponse return a PLAIN markdown string:
         "Error: Method '<name>' not found in <modulePath>\n\n
          **Available methods** (N):\n\n- Add\n- Test\n"
       This string never goes through ToolResult.error, so success==false is
       NOT present and is_error stays FALSE. This is the REAL contract: a missing
       METHOD is a benign, listing-style success — NOT a machine error. We assert
       that real shape (and that it names the bad method AND lists the siblings),
       rather than fabricating an is_error we know does not happen.

The Calc module fixture (TestConfiguration/src/CommonModules/Calc/Module.bsl,
committed, 7 lines):
    1: Функция Add(A, B) Экспорт      -> Function Add, exported, body line 2
    2: \tВозврат A + B;
    3: КонецФункции
    4: (blank)
    5: Процедура Test() Экспорт       -> Procedure Test, exported, calls Add
    6: \tРезультат = Add(1, 2);
    7: КонецПроцедуры
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

# The committed code fixture: CommonModule.Calc -> src/CommonModules/Calc/Module.bsl.
CALC_MODULE = "CommonModules/Calc/Module.bsl"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="read_method_source", kind="read")
def test_reads_function_add_body_and_metadata():
    """Read the Function 'Add' by name. Assert the REAL extracted body lines AND
    the real front-matter metadata (type=Function, export=true, method=Add). Each
    assertion is fixture-specific: a broken tool (no-op, wrong method extracted,
    whole-file dump, dropped export flag) would FAIL at least one.

    Mutation thinking:
      - body line 'Возврат A + B;' proves it returned Add's actual source, not a
        stub and not Test's body (which has 'Результат = Add(1, 2);').
      - 'КонецПроцедуры' is Test's closer; asserting it is ABSENT proves the tool
        sliced just Add (lines 1-3), not the whole file.
      - 'type: Function' + 'export: true' prove the signature metadata is real."""
    r = call("read_method_source", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "methodName": "Add",
    })
    assert_ok(r, "read_method_source Add")

    # MARKDOWN tool -> payload in r.text, never structuredContent.
    assert r.structured is None, \
        "read_method_source is a MARKDOWN tool; structuredContent must be None"

    # Front-matter metadata (FrontMatter block) — real, method-specific values.
    assert_contains(r.text, "method: Add",
                    "front-matter must name the resolved method Add")
    assert_contains(r.text, "type: Function",
                    "Add is a Функция -> type Function")
    assert_contains(r.text, "export: true",
                    "Add is declared Экспорт -> export true")
    assert_contains(r.text, "module: CommonModules/Calc/Module.bsl",
                    "front-matter must echo the real module path")

    # The fenced code block must carry Add's EXACT source lines.
    assert_contains(r.text, "Функция Add(A, B) Экспорт",
                    "must return Add's real signature line")
    assert_contains(r.text, "Возврат A + B;",
                    "must return Add's real body line, not a stub")

    # Slicing guard: Add is lines 1-3 only. Test's closer must NOT leak in —
    # proves the tool extracted just the method, not the whole module.
    assert_not_contains(r.text, "КонецПроцедуры",
                        "Test's closer must not appear -> Add was sliced, not whole file")
    assert_not_contains(r.text, "Результат = Add(1, 2);",
                        "Test's body must not appear in Add's extraction")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_emits_whole_module_content_hash_matching_read_module_source():
    """The front-matter carries a contentHash — the WHOLE-module token (not just this
    method) — so a method read round-trips into write_module_source's expectedHash.

    Mutation thinking: the token MUST equal what read_module_source emits for the same
    file (both hash the canonical module text). If read_method_source hashed only the
    method fragment, or used a different canonical form, the two tokens would differ and
    the cross-tool round-trip would silently break — this asserts they are identical."""
    import re
    r = call("read_method_source", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "methodName": "Add",
    })
    assert_ok(r, "read_method_source Add for contentHash")
    assert_contains(r.text, "contentHash", "front-matter must carry a contentHash")
    m = re.search(r'contentHash:\s*"?([0-9a-f]{16})"?', r.text or "")
    if not m:
        from harness import E2EAssertion
        raise E2EAssertion("contentHash is not a 16-hex token:\n%s" % (r.text or "")[:300])
    # Must equal the module-level token, proving it's the whole-module hash.
    mod = call("read_module_source", {"projectName": PROJECT, "modulePath": CALC_MODULE})
    mm = re.search(r'contentHash:\s*"?([0-9a-f]{16})"?', mod.text or "")
    assert mm and mm.group(1) == m.group(1), \
        "read_method_source contentHash must equal read_module_source's whole-module token"
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_reads_procedure_test_body_and_metadata():
    """Read the Procedure 'Test' by name -> the OTHER method in the same module.
    Asserting Test's real body ('Результат = Add(1, 2);') and type=Procedure proves
    the tool resolves by NAME (not always returning the first method) and reports
    the correct Procedure-vs-Function kind. Add's body must be absent."""
    r = call("read_method_source", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "methodName": "Test",
    })
    assert_ok(r, "read_method_source Test")

    assert_contains(r.text, "method: Test",
                    "front-matter must name the resolved method Test")
    assert_contains(r.text, "type: Procedure",
                    "Test is a Процедура -> type Procedure (not Function)")
    assert_contains(r.text, "export: true",
                    "Test is declared Экспорт -> export true")

    assert_contains(r.text, "Процедура Test() Экспорт",
                    "must return Test's real signature line")
    assert_contains(r.text, "Результат = Add(1, 2);",
                    "must return Test's real body line (the call to Add)")

    # Resolved Test, not Add: Add's body line must NOT be present.
    assert_not_contains(r.text, "Возврат A + B;",
                        "Add's body must not appear -> the tool resolved Test by name")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_method_name_is_case_insensitive():
    """findMethod uses equalsIgnoreCase, so the lower-case 'add' must resolve to the
    Function Add and return its real body. A tool doing a case-SENSITIVE match would
    instead fall into the not-found listing path (no 'Возврат A + B;'), so asserting
    the real body here proves the documented case-insensitive contract.

    We assert on the BODY line (not the front-matter 'method:' value), because the
    front-matter method name differs between the EMF path (canonical 'Add') and the
    text-fallback path (echoes 'add'); the body line is invariant across both."""
    r = call("read_method_source", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "methodName": "add",  # lower-case on purpose
    })
    assert_ok(r, "read_method_source case-insensitive 'add'")
    assert_contains(r.text, "Возврат A + B;",
                    "lower-case 'add' must resolve to Function Add (case-insensitive)")
    # Not the not-found listing path: the listing would NOT contain the body line,
    # so its presence already proves resolution; also assert the file body opener.
    assert_contains(r.text, "Функция Add(A, B) Экспорт",
                    "case-insensitive resolution must return Add's signature")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — method not found (benign success-with-error-text, NOT is_error)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="read_method_source", kind="read")
def test_unknown_method_is_benign_success_listing_available_methods():
    """REAL contract (documented gotcha): a method name that does NOT exist in an
    EXISTING module is NOT a machine error. buildMethodNotFoundResponse /
    buildTextMethodNotFoundResponse return a PLAIN markdown string (it never goes
    through ToolResult.error), so is_error stays FALSE. The text:
      - starts with "Error: Method '<name>' not found in <modulePath>"
      - then lists the real available methods (Add, Test).

    We assert that REAL shape rather than fabricating an is_error that does not
    happen here. The listing IS the actionable next step (it names the siblings),
    so this is mutation-sensitive: a broken tool that returned Add's body for an
    unknown name, or an empty/blank listing, would FAIL.

    AUDIT: a NON-existent method returns is_error:false while a non-existent MODULE
    (next test) returns is_error:true — the same 'you asked for something that is
    not there' class split across two machine-detectability contracts. A client
    cannot reliably branch on is_error for 'not found'. Flagged as a fix-card
    (consider routing method-not-found through ToolResult.error too, or document
    the listing-success contract explicitly)."""
    bad_method = "NoSuchMethod_e2e"
    r = call("read_method_source", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "methodName": bad_method,
    })
    # This path is success-with-listing, NOT is_error -> assert the real contract.
    assert_ok(r, "unknown method returns a benign listing, not is_error")
    assert_contains(r.text, bad_method,
                    "the not-found report must name the method the caller asked for")
    assert_contains(r.text, "not found",
                    "the report must state the method was not found")
    # The listing must enumerate the REAL sibling methods of Calc — the actionable
    # next step. Both fixture methods must appear.
    assert_contains(r.text, "Available methods",
                    "the report must offer the available-methods listing")
    assert_contains(r.text, "Add",
                    "the available-methods listing must include the real method Add")
    assert_contains(r.text, "Test",
                    "the available-methods listing must include the real method Test")
    # Mutation guard: it must NOT have silently returned a method body.
    assert_not_contains(r.text, "Возврат A + B;",
                        "an unknown method must NOT return Add's body")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — ToolResult.error paths (is_error TRUE)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="read_method_source", kind="read")
def test_nonexistent_module_file_errors_and_names_path():
    """Module path that does not resolve to a file: EMF loadModule returns null ->
    text fallback -> resolveModuleFile.exists() is false ->
    ToolResult.error("File not found: src/<modulePath>") -> is_error TRUE.
    Names the bad path AND echoes 'src/' so the user sees the resolved location."""
    bad_module = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("read_method_source", {
        "projectName": PROJECT,
        "modulePath": bad_module,
        "methodName": "Add",
    })
    e = assert_error(r, "non-existent module file")
    # Names the exact bad path; 'src/' is the actionable locator (where it looked).
    assert_error_quality(e, names=[bad_module], suggests=["src/"],
                         ctx="non-existent module names the path and the src/ root")
    # AUDIT: the error names the path but offers no pointer to list_modules (the
    # sibling tool that enumerates valid module paths). Could be more actionable.
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists()
    is false (in readMethodViaEmf, before any module load) ->
    ToolResult.error(ProjectContext.notFoundMessage(name)) -> is_error TRUE. The
    message both names the bad value AND points at list_projects (the migrated,
    actionable wording shared by every project-not-found site)."""
    bad_project = "NoSuchProject_ZZZ_e2e"
    r = call("read_method_source", {
        "projectName": bad_project,
        "modulePath": CALC_MODULE,
        "methodName": "Add",
    })
    e = assert_error(r, "non-existent project")
    # Actionable: names the bad project AND points at list_projects (the sibling
    # that enumerates valid projects) — the shared ProjectContext.notFoundMessage.
    assert_error_quality(e, names=[bad_project], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_missing_projectname_errors_clearly():
    """projectName omitted. requireArguments checks projectName FIRST ->
    ToolResult.error("projectName is required") -> is_error TRUE."""
    r = call("read_method_source", {
        "modulePath": CALC_MODULE,
        "methodName": "Add",
    })
    e = assert_error(r, "missing projectName")
    # AUDIT: names the missing param but offers no next step (no list_projects hint).
    # suggests=[] is intentional -> fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_missing_modulepath_errors_clearly():
    """modulePath omitted (projectName present, so its guard passes first) ->
    requireArguments fires on modulePath -> "modulePath is required" -> is_error.
    Distinct guard from the projectName case (proves the per-arg checks fire in
    order, not just the first one)."""
    r = call("read_method_source", {
        "projectName": PROJECT,
        "methodName": "Add",
    })
    e = assert_error(r, "missing modulePath")
    # AUDIT: names the missing param but no example/next-step hint. suggests=[]
    # intentional -> fix-card.
    assert_error_quality(e, names=["modulePath"], suggests=[],
                         ctx="missing modulePath names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_missing_methodname_errors_clearly():
    """methodName omitted (projectName + modulePath present, so they pass first) ->
    the THIRD requireArguments guard fires -> "methodName is required" -> is_error.
    This is the last of the three required args; asserting it proves the full
    required-arg chain is enforced, not just the first one or two."""
    r = call("read_method_source", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
    })
    e = assert_error(r, "missing methodName")
    # AUDIT: names the missing param but offers no read_method_source-specific hint
    # (e.g. 'use get_module_structure to list method names'). suggests=[]
    # intentional -> fix-card.
    assert_error_quality(e, names=["methodName"], suggests=[],
                         ctx="missing methodName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# methodNames — several methods of one module in one call (#889)
# ──────────────────────────────────────────────────────────────────────────────
_HASH_RE = r'contentHash:\s*"?([0-9a-f]{16})"?'


def _module_hash():
    import re
    mod = call("read_module_source", {"projectName": PROJECT, "modulePath": CALC_MODULE})
    assert_ok(mod, "read_module_source Calc for the module token")
    m = re.search(_HASH_RE, mod.text or "")
    assert m, "read_module_source must emit a 16-hex contentHash:\n%s" % (mod.text or "")[:300]
    return m.group(1)


@e2e_test(tool="read_method_source", kind="read")
def test_method_names_reads_add_and_test_in_one_call_with_one_hash():
    """methodNames=[Add, Test] returns ONE front matter for the module and one section per
    method, in request order. Mutation thinking:
      - two '---' header blocks would mean the tool concatenated two single answers;
      - 'methodsFound: 2' and both bodies prove both methods were located;
      - the request [Test, Add] is the REVERSE of the module order, so Test's section
        coming first pins request order (a module-order renderer would put Add first);
      - the one contentHash must equal read_module_source's whole-module token."""
    import re
    r = call("read_method_source", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "methodNames": ["Test", "Add"],
    })
    assert_ok(r, "read_method_source methodNames [Test, Add]")
    text = r.text or ""
    assert r.structured is None, "read_method_source is a MARKDOWN tool"
    assert text.startswith("---\n"), "the answer must open with the one front matter:\n%s" % text[:200]
    assert text.count("\n---\n") == 1, "exactly one front-matter block expected:\n%s" % text[:600]
    assert_contains(text, "methodsFound: 2", "both methods were found")
    assert_not_contains(text, "methodsNotFound", "nothing is missing")
    assert_not_contains(text, "\nmethod: ", "the sectioned answer has no single-method key")
    hashes = re.findall(_HASH_RE, text)
    assert len(hashes) == 1, "one contentHash for the whole call, got %r" % hashes
    assert hashes[0] == _module_hash(), "contentHash must equal read_module_source's token"
    test_at = text.find("### Test")
    add_at = text.find("### Add")
    assert 0 <= test_at < add_at, "sections must follow the REQUEST order (Test, then Add):\n%s" % text
    assert_contains(text, "Процедура Test() Экспорт", "Test's real signature")
    assert_contains(text, "Результат = Add(1, 2);", "Test's real body")
    assert_contains(text, "Функция Add(A, B) Экспорт", "Add's real signature")
    assert_contains(text, "Возврат A + B;", "Add's real body")
    assert_contains(text, "Procedure · export · lines 5-7", "Test's summary line")
    assert_contains(text, "Function · export · lines 1-3", "Add's summary line")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_method_names_partial_miss_lists_not_found_and_returns_found():
    """One real and one missing name: the found method is rendered, the missing one is
    named in methodsNotFound, and the call is NOT the all-missing listing."""
    missing = "NoSuchMethod_e2e"
    r = call("read_method_source", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "methodNames": [missing, "Test"],
    })
    assert_ok(r, "partial miss is a success")
    assert_contains(r.text, "methodsFound: 1", "one method found")
    assert_contains(r.text, "methodsNotFound: [%s]" % missing, "the missing name is reported")
    assert_contains(r.text, "### Test", "the found method still gets its section")
    assert_contains(r.text, "Результат = Add(1, 2);", "Test's real body")
    assert_not_contains(r.text, "Available methods", "a partial miss is not the not-found listing")
    assert_not_contains(r.text, "Возврат A + B;", "Add was not requested")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_method_names_all_missing_lists_available_methods():
    """Every requested name missing: the existing not-found listing, naming EVERY requested
    name and listing the module's real methods (success-with-listing, as for methodName)."""
    r = call("read_method_source", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "methodNames": ["Nope1_e2e", "Nope2_e2e"],
    })
    assert_ok(r, "all-missing returns the benign listing")
    assert_contains(r.text, "Error: Methods 'Nope1_e2e', 'Nope2_e2e' not found in " + CALC_MODULE,
                    "the listing names every requested name")
    assert_contains(r.text, "Available methods", "the listing offers the module's methods")
    assert_contains(r.text, "- Add", "Add is listed")
    assert_contains(r.text, "- Test", "Test is listed")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_neither_name_parameter_errors_naming_both():
    """Neither methodName nor methodNames: a structured error naming both parameters and the
    discovery tool. An empty methodNames array counts as absent."""
    for extra in ({}, {"methodNames": []}):
        args = {"projectName": PROJECT, "modulePath": CALC_MODULE}
        args.update(extra)
        r = call("read_method_source", args)
        e = assert_error(r, "no method name at all %r" % (extra,))
        assert_error_quality(e, names=["methodName", "methodNames"], suggests=["get_module_structure"],
                             ctx="the refusal names both name parameters")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_more_than_fifty_method_names_is_refused():
    """methodNames is capped at 50 entries; a longer list is refused (never truncated)."""
    r = call("read_method_source", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "methodNames": ["M%d" % i for i in range(51)],
    })
    e = assert_error(r, "51 names")
    assert_error_quality(e, names=["methodNames", "50"], suggests=["read_module_source"],
                         ctx="the cap refusal names the limit and a way forward")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_legacy_method_name_shape_is_unchanged():
    """methodName alone keeps the historical single-method answer byte for byte (front matter
    with method/type/export/startLine/endLine, then the fence). Pinned against the committed
    Calc fixture; only the hash value is matched by pattern."""
    import re
    r = call("read_method_source", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "methodName": "Add",
    })
    assert_ok(r, "read_method_source methodName=Add")
    expected = (r'---\nprojectName: TestConfiguration\nmodule: CommonModules/Calc/Module\.bsl\n'
                r'contentHash: "?[0-9a-f]{16}"?\nmethod: Add\ntype: Function\nexport: true\n'
                r'startLine: 1\nendLine: 3\ntotalLines: 7\n---\n'
                r'```bsl\nФункция Add\(A, B\) Экспорт\n\tВозврат A \+ B;\nКонецФункции\n```\n')
    assert re.match(expected, r.text or ""), "legacy shape changed:\n%s" % (r.text or "")[:600]
    assert_not_contains(r.text, "methodsFound", "methodName alone must not switch to sections")
    assert_not_contains(r.text, "### ", "methodName alone must not switch to sections")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_windowed_formatter_contract():
    from read_host_contracts import contract_read_method_source
    contract_read_method_source(call, "WINDOWED")
    assert_no_diff("pinned formatter reads must not change fixture files")

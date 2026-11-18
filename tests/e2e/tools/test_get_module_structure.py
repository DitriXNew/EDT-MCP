"""
e2e tests for get_module_structure (kind: read).

get_module_structure parses a BSL module and returns a MARKDOWN structure
report (methods table, regions, totals). Response type is MARKDOWN, so the
payload lands in Result.text on success; error payloads go through
ToolResult.error(...).toJson() and are delivered as structured isError
(JsonUtils.requireArgument / GetModuleStructureTool.execute).

Happy paths assert the real report content AND the non-destructive guardrail
(a read tool must never touch the project on disk). The negative matrix covers
the tool's REAL error paths: non-existent module, non-existent project, and
each missing required parameter (projectName, modulePath).
"""

from contextlib import contextmanager
import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, read_disk, PROJECT,
)

OVERLAP_PATH = "CommonModules/Calc/Module.bsl"
OVERLAP_SOURCE = """Procedure Head()
EndProcedure
#Если Сервер Тогда
Процедура П1()
КонецПроцедуры
#Область Несовместимый
Процедура П2()
КонецПроцедуры
#КонецЕсли
Процедура П3()
КонецПроцедуры
#КонецОбласти
#Область Хвост
Функция Остаток() Экспорт
Возврат 42;
КонецФункции
Procedure Tail()
EndProcedure
#КонецОбласти
"""


@contextmanager
def temporary_module_source(path, original, source, *, skip_syntax_check=False):
    args = {"projectName": PROJECT, "modulePath": path, "mode": "replace"}
    disk_path = "src/" + path
    assert_ok(call("write_module_source", {**args, "expectedSource": original, "source": source,
        "skipSyntaxCheck": skip_syntax_check}), "install temporary BSL fixture")
    try:
        assert read_disk(disk_path).removeprefix("\ufeff") == source
        yield
    finally:
        assert_ok(call("write_module_source", {**args, "expectedSource": source,
            "source": original}), "restore temporary BSL fixture")
        assert read_disk(disk_path).removeprefix("\ufeff") == original


@e2e_test(tool="get_module_structure", kind="write-metadata")
def test_overlapping_preprocessors_report_incomplete_structure_and_recover_methods():
    original = read_disk("src/" + OVERLAP_PATH).removeprefix("\ufeff")
    with temporary_module_source(OVERLAP_PATH, original, OVERLAP_SOURCE, skip_syntax_check=True):
        result = call("get_module_structure", {"projectName": PROJECT, "modulePath": OVERLAP_PATH})
        assert_ok(result, "read overlapping preprocessor module structure")
        assert_contains(result.text, "**Incomplete EDT parse.**")
        assert_contains(result.text, "EDT model methods: 4. Saved text declarations: 6.")
        assert re.search(r"First syntax error at line 9: .+\. EDT could not reliably parse past", result.text), result.text
        assert_contains(result.text, "| Function | Остаток _(recovered from text)_ | Yes | - | 14-16 | Хвост |")
        assert_contains(result.text, "| Procedure | Tail _(recovered from text)_ | - | - | 17-18 | Хвост |")
        assert_contains(result.text, "**Total:** 5 procedures, 1 functions | **Lines:** 19")
    assert_no_diff("restored the original Calc module after reading its incomplete structure")


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_module_structure", kind="read")
def test_structure_of_real_module_and_does_not_mutate():
    # CommonModule.Error -> CommonModules/Error/Module.bsl (real fixture object).
    r = call("get_module_structure", {
        "projectName": PROJECT,
        "modulePath": "CommonModules/Error/Module.bsl",
    })
    assert_ok(r, "structure of CommonModules/Error/Module.bsl")
    # The report header echoes the requested modulePath and is emitted ONLY on a
    # successful parse (getStructureInternal). A broken/no-op tool would not
    # produce this exact line, so the assertion is mutation-sensitive.
    assert_contains(r.text, "## Module Structure: CommonModules/Error/Module.bsl",
                    "report header must echo the parsed module path")
    # The totals line is part of every successful report (procCount/funcCount/lines).
    assert_contains(r.text, "**Total:**", "report must include the procedure/function totals line")
    assert_no_diff("get_module_structure is a read tool and must not change the project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_structure_of_empty_module_succeeds():
    # CommonModule.OK -> CommonModules/OK/Module.bsl is an EMPTY (valid) module.
    # A valid-but-empty module must parse and report cleanly, NOT error.
    r = call("get_module_structure", {
        "projectName": PROJECT,
        "modulePath": "CommonModules/OK/Module.bsl",
    })
    assert_ok(r, "structure of empty CommonModules/OK/Module.bsl")
    assert_contains(r.text, "## Module Structure: CommonModules/OK/Module.bsl",
                    "report header must echo the empty module's path")
    # With no methods the tool emits this exact sentinel (appendMethodsTable is
    # skipped). Asserts the empty-module branch is actually exercised.
    assert_contains(r.text, "No methods found in this module.",
                    "empty module must report the no-methods sentinel")
    assert_no_diff("a read on an empty module must not change the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# responseFormat contract: concise (default) is leaner than detailed
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_module_structure", kind="read")
def test_responseFormat_concise_is_leaner_than_detailed():
    # Same module, two formats. CommonModules/Calc/Module.bsl has methods (Add, Test),
    # so both outputs carry a "### Methods" table and detailed carries the Parameters
    # column. (The Error module is a single token with NO methods, so it has no methods
    # table — wrong fixture for this contract test.)
    args = {"projectName": PROJECT, "modulePath": "CommonModules/Calc/Module.bsl"}

    # Default call (no responseFormat) must behave as concise.
    concise = call("get_module_structure", args)
    assert_ok(concise, "default (concise) structure of CommonModules/Calc/Module.bsl")
    detailed = call("get_module_structure", dict(args, responseFormat="detailed"))
    assert_ok(detailed, "detailed structure of CommonModules/Calc/Module.bsl")

    # Essential fields concise KEEPS (and the default still emits them).
    assert_contains(concise.text, "## Module Structure: CommonModules/Calc/Module.bsl",
                    "concise must keep the report header")
    assert_contains(concise.text, "**Total:**", "concise must keep the totals line")
    assert_contains(concise.text, "### Methods", "concise must keep the methods table")

    # detailed carries the verbose Parameters signature column; concise drops it.
    assert_contains(detailed.text, "| Parameters |",
                    "detailed must include the verbose Parameters column")
    assert_not_contains(concise.text, "| Parameters |",
                        "concise must omit the verbose Parameters column")

    # Net effect: the default (concise) output is strictly shorter (fewer tokens).
    if len(concise.text) >= len(detailed.text):
        raise AssertionError(
            "expected concise output to be leaner than detailed, but concise=%d >= detailed=%d chars"
            % (len(concise.text), len(detailed.text)))
    assert_no_diff("reading a module in either format must not change the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_module_structure", kind="read")
def test_nonexistent_module_path_errors_clearly():
    bad_path = "CommonModules/DoesNotExistModule/Module.bsl"
    r = call("get_module_structure", {
        "projectName": PROJECT,
        "modulePath": bad_path,
    })
    e = assert_error(r, "non-existent module path")
    # Real message: "BSL model is not available for '<path>'\nMake sure project
    # '<project>' is open and fully indexed in EDT." Names the bad path and the
    # project, and "indexed" is the actionable next-step.
    # NOTE: the message wraps the path in apostrophes which Gson HTML-escapes in
    # the JSON text, so we match the delimiter-free path/word, never the quoted form.
    assert_error_quality(e,
        names=[bad_path],
        suggests=["indexed"])
    assert_no_diff("a failed read must not change the project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_nonexistent_project_errors_clearly():
    bad_project = "NoSuchProjectXYZ"
    r = call("get_module_structure", {
        "projectName": bad_project,
        "modulePath": "CommonModules/Error/Module.bsl",
    })
    e = assert_error(r, "non-existent project")
    # Real message: "Project not found: NoSuchProjectXYZ. Use list_projects to
    # see available projects." (GetModuleStructureTool.getStructureInternal goes
    # through the shared ProjectContext.notFoundMessage). It names the bad project
    # AND points at the sibling discovery tool (list_projects) as the next step.
    assert_error_quality(e, names=[bad_project], suggests=["list_projects"])
    assert_no_diff("a failed read must not change the project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_missing_module_path_errors_clearly():
    r = call("get_module_structure", {
        "projectName": PROJECT,
        # modulePath omitted — required parameter.
    })
    e = assert_error(r, "missing required modulePath")
    # Real message: "modulePath is required. Example: 'CommonModules/MyModule/Module.bsl'".
    # Names the missing param and gives a concrete fix (the example path token).
    assert_error_quality(e,
        names=["modulePath"],
        suggests=["CommonModules/MyModule/Module.bsl"])
    assert_no_diff("a rejected call must not change the project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_missing_project_name_errors_clearly():
    r = call("get_module_structure", {
        # projectName omitted — required parameter.
        "modulePath": "CommonModules/Error/Module.bsl",
    })
    e = assert_error(r, "missing required projectName")
    # Real message: "projectName is required" (JsonUtils.requireArgument). It
    # names the missing param but gives no next step / sibling tool.
    # AUDIT: "projectName is required" is not actionable — it should suggest
    # list_projects to discover a valid project name. suggests=[] kept empty
    # deliberately rather than weakening the assertion.
    assert_error_quality(e, names=["projectName"], suggests=[])
    assert_no_diff("a rejected call must not change the project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_object_names_in_another_case_resolve_the_real_ast():
    for module, method in (("CommonModules/cALC/module.BSL", "Add"),
                           ("CommonModules/ВЫЧИСЛЕНИЕ/MODULE.bsl", "Маркер")):
        result = call("get_module_structure", {"projectName": PROJECT, "modulePath": module})
        assert_ok(result, "structure with a differently-cased object and module file name")
        assert_contains(result.text, "### Methods", "the real module's methods table must be returned")
        assert_contains(result.text, method, "the real module's method must be in the AST report")
    assert_no_diff("case-insensitive structure lookup must leave the fixture unchanged")


@e2e_test(tool="get_module_structure", kind="read")
def test_windowed_formatter_contract():
    from read_host_contracts import contract_get_module_structure
    contract_get_module_structure(call, "WINDOWED")
    assert_no_diff("pinned formatter reads must not change fixture files")

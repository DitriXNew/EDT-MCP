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

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


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
    # Real message: "Project not found: NoSuchProjectXYZ". It names the bad
    # project but offers NO next step (no sibling tool to discover valid names).
    # AUDIT: GetModuleStructureTool.getStructureInternal "Project not found"
    # error is not actionable — it should point to list_projects. suggests=[]
    # kept empty deliberately (do not weaken to a substring that is always there).
    assert_error_quality(e, names=[bad_project], suggests=[])
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

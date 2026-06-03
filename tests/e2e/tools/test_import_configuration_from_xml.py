"""
e2e tests for import_configuration_from_xml (kind: action).

THE TOOL (ImportConfigurationFromXmlTool, getResponseType() == MARKDOWN):
Wraps EDT's "Import -> Configuration from XML Files" CLI API
(com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi, via reflection).
It reads a directory of XML source files and creates a NEW EDT project in the
workspace from them. The reverse of export_configuration_to_xml.

Required params (JsonSchemaBuilder): importPath, projectName.
Optional params: projectNature, xmlVersion (empty string -> null -> EDT auto-detect).

WHY THERE IS NO HAPPY MUTATION TEST HERE (deliberate, per ACTION-TOOL SAFETY):
A genuine success calls api.importProject(...) which IMPORTS XML INTO A NEW
workspace project. This is destructive in two ways the e2e suite must never do:
  1. it requires a real, valid XML configuration dump as input — the only
     such artifact on hand is TestConfiguration itself, and re-importing it
     would mean creating a second project / risking the committed fixture;
  2. it mutates EDT workspace state (creates+opens a project) that the
     git-fixture protocol (which only resets TestConfiguration/) cannot undo.
So a "real import" happy path is OMITTED on purpose. Instead the positive-shape
coverage is the EXISTING-PROJECT guard (below): it is the deepest validation the
tool performs with VALID-shaped arguments, it is fully deterministic, and —
critically — it is reached BEFORE Activator...getImportConfigurationFilesApi()
is ever called, so NO import is attempted. That proves the tool validates and
REFUSES to overwrite an existing project, with zero risk to the fixture.

execute() validation order (each returns ToolResult.error -> isError, and each
happens BEFORE the CLI API is invoked, so every test below is non-destructive):
  1. requireArguments(importPath, projectName) -> "<name> is required"
       (checked in order: importPath first, then projectName)
  2. !Files.exists(importPath)        -> "importPath does not exist: <abs>"
  3. !Files.isDirectory(importPath)   -> "importPath is not a directory: <abs>"
  4. workspace project already exists -> "Project already exists in workspace:
       <name>. Import requires a new project name."
  5. api == null                      -> "IImportConfigurationFilesApi is not
       available. Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed."
  (only AFTER all of the above does it call importProject(...).)

Every test asserts assert_no_diff() on TestConfiguration: a rejected/validating
call must never touch the committed fixture on disk.
"""

import os

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
    REPO_ROOT,
)

# A real, existing DIRECTORY on disk (valid importPath shape) that is NOT a
# config XML dump. Using the repo root keeps the test self-contained and
# deterministic; it lets us reach the existing-project guard with a path that
# Files.exists()+isDirectory() both accept.
_EXISTING_DIR = REPO_ROOT
# A real, existing FILE (exists() true, isDirectory() false) to hit the
# "not a directory" branch. CLAUDE.md is committed at the repo root.
_EXISTING_FILE = os.path.join(REPO_ROOT, "CLAUDE.md")
# A path that does not exist at all.
_MISSING_PATH = os.path.join(REPO_ROOT, "no_such_import_dir_e2e_zzz")


# ──────────────────────────────────────────────────────────────────────────────
# POSITIVE-SHAPE (deepest non-destructive validation) — the existing-project guard
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_existing_project_name_is_refused_before_any_import():
    """Valid-shaped args (a real directory + a real project name), but the
    project name is TestConfiguration which ALREADY exists in the workspace.

    This is the deepest validation reachable without triggering a real import:
    the tool resolves the workspace, sees the project exists, and returns the
    "Project already exists" error -> the import API is NEVER invoked. It proves
    the tool refuses to clobber an existing project (a no-op/broken tool that
    skipped this guard would proceed to import and corrupt state).

    The on-disk fixture must be untouched (assert_no_diff): this rejected call
    creates nothing.
    """
    r = call("import_configuration_from_xml", {
        "importPath": _EXISTING_DIR,
        "projectName": PROJECT,
    })
    e = assert_error(r, "import into an already-existing project name")
    # Message names the conflicting project value AND tells the caller what to do
    # ("requires a new project name") -> genuinely actionable.
    assert_error_quality(
        e,
        names=[PROJECT],
        suggests=["new project name"],
        ctx="existing-project guard names the project and the fix",
    )
    # Belt-and-braces on the exact contract wording (the guard, not a generic fail).
    assert_contains(e, "already exists",
                    "must be the existing-project guard, not a generic error")
    assert_no_diff("a rejected import must not touch the committed fixture")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_missing_both_required_params_errors_on_importpath_first():
    """No args at all. requireArguments checks importPath FIRST, so the error
    must name importPath (a broken guard that checked the wrong param, or none,
    would name projectName / fail generically)."""
    r = call("import_configuration_from_xml", {})
    e = assert_error(r, "no required params supplied")
    # AUDIT: "importPath is required" names the param but offers no next step
    # (no pointer to export_configuration_to_xml as the source of a dump dir) ->
    # suggests=[] is intentional. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["importPath"], suggests=[],
                         ctx="missing importPath named first (check order)")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_missing_projectname_errors_clearly():
    """importPath present, projectName omitted -> the SECOND required-arg guard
    fires and names projectName specifically (proves both required params are
    enforced, not just the first)."""
    r = call("import_configuration_from_xml", {
        "importPath": _EXISTING_DIR,
    })
    e = assert_error(r, "missing projectName with importPath present")
    # AUDIT: "projectName is required" names the param but is not actionable
    # (no list_projects pointer) -> suggests=[] intentional. Fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName named (second required guard)")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_nonexistent_importpath_errors_and_names_path():
    """Both required params present, but importPath points at a path that does
    not exist -> Files.exists()==false branch. The error must echo the resolved
    (absolute) path so the caller can see exactly what was looked up."""
    r = call("import_configuration_from_xml", {
        "importPath": _MISSING_PATH,
        "projectName": "ImportTargetProject_e2e_zzz",
    })
    e = assert_error(r, "non-existent importPath")
    # The tool normalizes to an absolute path; the leaf name is stable across
    # that normalization, so assert on the distinctive leaf rather than the full
    # absolute string (which varies by checkout location).
    # AUDIT: "importPath does not exist: <abs>" names the bad path but does not
    # suggest a remedy (e.g. run export_configuration_to_xml first to produce a
    # dump) -> suggests=[]. Fix-card: make the path errors actionable.
    assert_error_quality(e, names=["no_such_import_dir_e2e_zzz"], suggests=[],
                         ctx="non-existent importPath echoes the path")
    assert_contains(e, "does not exist",
                    "must be the existence guard, not a generic error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_importpath_is_a_file_not_directory_errors_clearly():
    """importPath exists but is a FILE, not a directory -> the isDirectory()
    branch (distinct from the existence branch above). Confirms the tool
    distinguishes "missing" from "present-but-wrong-kind" (a real, committed
    file is used so the existence check passes and we reach this branch)."""
    r = call("import_configuration_from_xml", {
        "importPath": _EXISTING_FILE,
        "projectName": "ImportTargetProject_e2e_zzz",
    })
    e = assert_error(r, "importPath is a file, not a directory")
    # AUDIT: "importPath is not a directory: <abs>" names the path but is not
    # actionable (no "point importPath at the directory of XML files" hint) ->
    # suggests=[]. Fix-card.
    assert_error_quality(e, names=["CLAUDE.md"], suggests=[],
                         ctx="file-not-directory branch echoes the path")
    assert_contains(e, "not a directory",
                    "must be the directory-kind guard, distinct from existence")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_existence_check_precedes_directory_check():
    """Ordering guard: a non-existent path must surface the EXISTENCE error,
    never the directory-kind error. Files.exists() is checked before
    Files.isDirectory(), so a missing path can only ever produce "does not
    exist". This pins the validation order (a refactor that reordered the two
    checks would regress here)."""
    r = call("import_configuration_from_xml", {
        "importPath": _MISSING_PATH,
        "projectName": "ImportTargetProject_e2e_zzz",
    })
    e = assert_error(r, "ordering: existence before directory-kind")
    assert_contains(e, "does not exist",
                    "missing path must hit the existence branch first")
    # And must NOT mislabel a missing path as "present but not a directory".
    assert_not_contains(e, "is not a directory",
                        "a missing path must not surface the directory-kind error")
    assert_no_diff("a rejected call must not touch the fixture")

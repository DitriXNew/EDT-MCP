"""
e2e tests for import_project_from_file (kind: action).

THE TOOL (ImportProjectFromFileTool, getResponseType() == MARKDOWN):
Creates a NEW EDT project from a 1C binary file - .cf (configuration), .cfe (extension,
baseProjectName required), .epf / .erf (external data processor / report, baseProjectName
optional). EDT cannot read these files, so the tool converts the file to XML with the thick
client of an installed 1C:Enterprise platform, imports the XML through EDT's CLI import API
and starts the project. The work runs as a background job: a call that does not finish
within waitSeconds answers "**Pending:**" with a jobId for get_job_status.

THE FIXTURE FILES (tests/ImportFiles) were produced by the 1C platform from the committed
fixture projects: TestConfiguration.cf from TestConfiguration, tests.cfe from the extension
fixture, ExtProc.epf / ExtReport.erf from the ExternalObjects fixture. The happy paths import
them as NEW projects and delete those again; the fixtures themselves are only read.

WHAT NEEDS A PLATFORM:
The conversion needs a registered 1C:Enterprise platform with the thick client, which headless
CI does not have. The happy paths and the damaged-file case therefore run only with
EDT_MCP_LIVE_INFOBASE=1 (the stand), and skip otherwise; without a platform the tool must
instead refuse at once, which test_without_a_platform_the_call_is_refused_at_once pins on CI.

execute() validation, each a ToolResult.error BEFORE any job starts (CI-safe):
  1. filePath / projectName required
  2. waitSeconds outside 0..45
  3. filePath missing / a directory / an unsupported extension
  4. projectName illegal / taken / its workspace folder exists / an import of it running
  5. baseProjectName: refused for .cf, required for .cfe, must be an open CONFIGURATION project
  6. no installed platform fits (or none matches platformVersion)

Every test asserts assert_no_diff(): nothing here may touch the committed fixture.
"""

import os
import re
import tempfile
import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    assert_no_diff_rel,
    settle_or_fail,
    requires_live_infobase,
    e2e_test,
    E2ESkip,
    LIVE_INFOBASE,
    PROJECT,
    TESTS_PROJECT,
    EXT_OBJECTS_REL,
    TESTS_PROJECT_REL,
    REPO_ROOT,
)

TOOL = "import_project_from_file"

_FILES = os.path.join(REPO_ROOT, "tests", "ImportFiles")
_CF = os.path.join(_FILES, "TestConfiguration.cf")
_CFE = os.path.join(_FILES, "tests.cfe")
_EPF = os.path.join(_FILES, "ExtProc.epf")
_ERF = os.path.join(_FILES, "ExtReport.erf")

# Throwaway projects the happy paths create (never a committed fixture).
_CF_PROJECT = "ZZImportFileCf"
_CFE_PROJECT = "ZZImportFileCfe"
_EPF_PROJECT = "ZZImportFileEpf"
_ERF_PROJECT = "ZZImportFileErf"
_BROKEN_PROJECT = "ZZImportFileBroken"
_REFUSED_PROJECT = "ZZImportFileRefused"

# How long one import may take end to end on the stand, polling included.
_JOB_BUDGET_S = 900


def _ensure_absent(project_name):
    """Best-effort clean so a leftover from a crashed run cannot trip the taken-name guard."""
    call("delete_project", {"projectName": project_name, "deleteContent": True, "confirm": True})


def _project_state(list_projects_markdown, project_name):
    """The `state` cell list_projects prints for one project, or None when it has no row."""
    for line in list_projects_markdown.splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= set("|- "):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) >= 2 and cells[0] == project_name:
            return cells[1].strip().lower()
    return None


def _import_to_completion(arguments):
    """Run one import and follow its job to the end.

    Returns (finished, text): finished is True for a successful import and the text is its
    Markdown answer; False with the error text otherwise.
    """
    r = call(TOOL, arguments)
    if r.is_error:
        return False, r.error_text()
    text = r.text or ""
    if not text.startswith("**Pending:**"):
        return True, text
    match = re.search(r'jobId="([0-9a-f-]+)"', text)
    assert match, "a pending answer must carry the jobId to poll:\n" + text
    job_id = match.group(1)
    deadline = time.time() + _JOB_BUDGET_S
    while time.time() < deadline:
        status = call("get_job_status", {"jobId": job_id, "waitSeconds": 45})
        assert_ok(status, "poll the import job")
        body = status.text or ""
        if body.startswith("# Background job: done"):
            return True, body
        if body.startswith("# Background job: failed") or body.startswith("# Background job: cancelled"):
            return False, body
    raise AssertionError("the import job %s did not finish within %ss" % (job_id, _JOB_BUDGET_S))


def _assert_ready_in_list_projects(project_name):
    """Ground truth independent of the tool's own answer: EDT really started the project."""
    settle_or_fail("the list_projects check of imported project " + project_name)
    lp = call("list_projects", {})
    state = _project_state(lp.text, project_name)
    assert state == "ready", \
        "imported project %s must reach state 'ready', got %r:\n%s" % (project_name, state, lp.text)


def _assert_fixtures_untouched(ctx):
    assert_no_diff(ctx)
    assert_no_diff_rel(TESTS_PROJECT_REL, ctx)
    assert_no_diff_rel(EXT_OBJECTS_REL, ctx)


# ──────────────────────────────────────────────────────────────────────────────
# POSITIVE: real imports of each file kind (need a platform: the stand)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool=TOOL, kind="action")
def test_cf_file_becomes_a_started_configuration_project():
    """A .cf becomes a configuration project whose model holds the fixture's objects.

    Anti-cheat: the answer's kind and configuration name are read back from EDT, list_projects
    must report the project ready, and get_metadata_objects must find the fixture's catalog in
    the NEW project - an import that created an empty project fails the last check."""
    requires_live_infobase("the .cf conversion needs an installed 1C:Enterprise platform")
    _ensure_absent(_CF_PROJECT)
    try:
        finished, text = _import_to_completion({"filePath": _CF, "projectName": _CF_PROJECT})
        assert finished, "the .cf import must succeed:\n" + text
        assert_contains(text, "| Project | %s |" % _CF_PROJECT, "the answer names the new project")
        assert_contains(text, "| Project kind | configuration |", "EDT made a configuration project")
        assert_contains(text, "| Configuration name | TestConfiguration |",
                        "the configuration read back is the one in the file")
        assert_contains(text, "| Source kind | configuration (.cf) |", "the source kind is echoed")
        _assert_ready_in_list_projects(_CF_PROJECT)
        objects = call("get_metadata_objects",
                       {"projectName": _CF_PROJECT, "metadataType": "CommonModule"})
        assert_ok(objects, "read the imported configuration's common modules")
        assert_contains(objects.text, "CascadeUser",
                        "the imported model must hold the fixture's common modules")
    finally:
        _ensure_absent(_CF_PROJECT)
    _assert_fixtures_untouched("the .cf import creates a separate project")


@e2e_test(tool=TOOL, kind="action")
def test_cfe_file_becomes_an_extension_linked_to_its_base():
    """A .cfe becomes an extension project LINKED to the base - read back, not echoed - and
    keeps the extension's own name even though the tool loads it under a temporary one."""
    requires_live_infobase("the .cfe conversion needs an installed 1C:Enterprise platform")
    _ensure_absent(_CFE_PROJECT)
    try:
        finished, text = _import_to_completion(
            {"filePath": _CFE, "projectName": _CFE_PROJECT, "baseProjectName": PROJECT})
        assert finished, "the .cfe import must succeed:\n" + text
        assert_contains(text, "| Project kind | extension |", "EDT made an extension project")
        assert_contains(text, "| Base project | %s |" % PROJECT, "the extension is linked to its base")
        assert_contains(text, "| Configuration name | tests |",
                        "the extension keeps its own name, not the temporary load name")
        assert_not_contains(text, "McpImport", "the temporary extension name must not leak")
        _assert_ready_in_list_projects(_CFE_PROJECT)
    finally:
        _ensure_absent(_CFE_PROJECT)
    _assert_fixtures_untouched("the .cfe import creates a separate project")


@e2e_test(tool=TOOL, kind="action")
def test_epf_file_becomes_a_standalone_external_objects_project():
    """An .epf without a base becomes an external-objects project holding that processor."""
    requires_live_infobase("the .epf conversion needs an installed 1C:Enterprise platform")
    _ensure_absent(_EPF_PROJECT)
    try:
        finished, text = _import_to_completion({"filePath": _EPF, "projectName": _EPF_PROJECT})
        assert finished, "the .epf import must succeed:\n" + text
        assert_contains(text, "| Project kind | external objects |", "EDT made an external-objects project")
        assert_contains(text, "| External objects | ExternalDataProcessor.ExtProc |",
                        "the processor read back from the new project")
        assert_contains(text, "| Base project | none |", "no base was asked for, none is linked")
        _assert_ready_in_list_projects(_EPF_PROJECT)
    finally:
        _ensure_absent(_EPF_PROJECT)
    _assert_fixtures_untouched("the .epf import creates a separate project")


@e2e_test(tool=TOOL, kind="action")
def test_erf_file_with_a_base_is_linked_to_it():
    """An .erf with a base becomes an external-objects project linked to that base."""
    requires_live_infobase("the .erf conversion needs an installed 1C:Enterprise platform")
    _ensure_absent(_ERF_PROJECT)
    try:
        finished, text = _import_to_completion(
            {"filePath": _ERF, "projectName": _ERF_PROJECT, "baseProjectName": PROJECT,
             "waitSeconds": 0})
        assert finished, "the .erf import must succeed:\n" + text
        assert_contains(text, "| External objects | ExternalReport.ExtReport |",
                        "the report read back from the new project")
        assert_contains(text, "| Base project | %s |" % PROJECT, "the report is linked to its base")
        _assert_ready_in_list_projects(_ERF_PROJECT)
    finally:
        _ensure_absent(_ERF_PROJECT)
    _assert_fixtures_untouched("the .erf import creates a separate project")


@e2e_test(tool=TOOL, kind="action")
def test_a_damaged_file_fails_the_conversion_and_creates_nothing():
    """A file the platform cannot read fails in the conversion, BEFORE anything is created.
    The ground truth is list_projects: no row for the project."""
    requires_live_infobase("the conversion needs an installed 1C:Enterprise platform")
    _ensure_absent(_BROKEN_PROJECT)
    fd, broken = tempfile.mkstemp(prefix="edt_import_e2e_", suffix=".epf")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(b"this is not a 1C external data processor")
        finished, text = _import_to_completion({"filePath": broken, "projectName": _BROKEN_PROJECT})
        assert not finished, "a damaged file must not import:\n" + text
        assert_contains(text, "No project was created", "the failure says nothing was left behind")
        lp = call("list_projects", {})
        assert _project_state(lp.text, _BROKEN_PROJECT) is None, \
            "a failed conversion must not leave a project:\n" + lp.text
    finally:
        os.remove(broken)
        _ensure_absent(_BROKEN_PROJECT)
    _assert_fixtures_untouched("a failed conversion touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_a_platform_version_that_matches_nothing_is_refused_naming_it():
    """platformVersion is resolved before any job starts; an unmatched one is refused at once and
    named, with or without a platform on the machine."""
    r = call(TOOL, {"filePath": _CF, "projectName": _REFUSED_PROJECT, "platformVersion": "0.0.1"})
    e = assert_error(r, "an unmatched platformVersion")
    assert_error_quality(e, names=["0.0.1"], suggests=["platformVersion"],
                         ctx="the refusal names the value and the parameter")
    lp = call("list_projects", {})
    assert _project_state(lp.text, _REFUSED_PROJECT) is None, "a refusal creates nothing"
    _assert_fixtures_untouched("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_without_a_platform_the_call_is_refused_at_once():
    """Headless CI has no 1C platform: the call must be refused before any job, naming the
    missing platform and the XML route instead of hanging or creating anything."""
    if LIVE_INFOBASE:
        raise E2ESkip("a platform IS registered on the live stand; the happy paths cover it")
    r = call(TOOL, {"filePath": _CF, "projectName": _REFUSED_PROJECT})
    e = assert_error(r, "no installed platform")
    assert_error_quality(e, names=["platform"], suggests=["import_configuration_from_xml"],
                         ctx="the refusal names what is missing and the route without it")
    assert_contains(e, "No project was created", "the refusal says nothing was created")
    lp = call("list_projects", {})
    assert _project_state(lp.text, _REFUSED_PROJECT) is None, "a refusal creates nothing"
    _assert_fixtures_untouched("a refused call touches nothing")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every refusal comes before the platform is asked)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool=TOOL, kind="action")
def test_missing_file_path_is_refused():
    r = call(TOOL, {"projectName": _REFUSED_PROJECT})
    e = assert_error(r, "no filePath")
    assert_error_quality(e, names=["filePath"], suggests=[".cf"],
                         ctx="the refusal names the parameter and what it takes")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_missing_project_name_is_refused_without_sending_to_list_projects():
    """The name is of a NEW project, so pointing at list_projects would be the wrong advice."""
    r = call(TOOL, {"filePath": _CF})
    e = assert_error(r, "no projectName")
    assert_error_quality(e, names=["projectName"], suggests=["NEW project"],
                         ctx="the refusal says the name is for a new project")
    assert_not_contains(e, "list_projects", "a new name is not discovered with list_projects")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_missing_file_is_refused_naming_it():
    missing = os.path.join(REPO_ROOT, "tests", "ImportFiles", "no_such_file_e2e_zzz.cf")
    r = call(TOOL, {"filePath": missing, "projectName": _REFUSED_PROJECT})
    e = assert_error(r, "a missing file")
    assert_error_quality(e, names=["no_such_file_e2e_zzz.cf"], suggests=[".cf"],
                         ctx="the refusal echoes the path")
    assert_contains(e, "does not exist", "the existence guard, not a later one")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_a_directory_is_refused_and_pointed_at_the_xml_tool():
    r = call(TOOL, {"filePath": _FILES, "projectName": _REFUSED_PROJECT})
    e = assert_error(r, "a directory instead of a file")
    assert_error_quality(e, names=["ImportFiles"], suggests=["import_configuration_from_xml"],
                         ctx="a directory of XML belongs to the XML tool")
    assert_contains(e, "is not a file", "the file-kind guard")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_an_unsupported_extension_is_refused_listing_the_supported_ones():
    r = call(TOOL, {"filePath": os.path.join(REPO_ROOT, "CLAUDE.md"), "projectName": _REFUSED_PROJECT})
    e = assert_error(r, "an .md file")
    assert_error_quality(e, names=["CLAUDE.md"], suggests=[".cf, .cfe, .epf, .erf"],
                         ctx="the refusal lists what is supported")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_an_existing_project_name_is_refused():
    r = call(TOOL, {"filePath": _CF, "projectName": PROJECT})
    e = assert_error(r, "the fixture's own name")
    assert_error_quality(e, names=[PROJECT], suggests=["new project name"],
                         ctx="the taken-name guard names the project and the fix")
    assert_contains(e, "already exists", "the taken-name guard, not a generic failure")
    assert_no_diff("a refused call must not touch the fixture it names")


@e2e_test(tool=TOOL, kind="action")
def test_an_illegal_project_name_is_refused():
    r = call(TOOL, {"filePath": _CF, "projectName": "bad/name"})
    e = assert_error(r, "a project name with a slash")
    assert_error_quality(e, names=["bad/name"], suggests=["another name"],
                         ctx="the refusal names the value")
    assert_contains(e, "not a legal project name", "the name guard")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_a_cfe_without_a_base_is_refused():
    r = call(TOOL, {"filePath": _CFE, "projectName": _REFUSED_PROJECT})
    e = assert_error(r, "a .cfe with no base")
    assert_error_quality(e, names=["baseProjectName"], suggests=["list_projects"],
                         ctx="an extension needs the project it extends")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_a_cf_with_a_base_is_refused():
    r = call(TOOL, {"filePath": _CF, "projectName": _REFUSED_PROJECT, "baseProjectName": PROJECT})
    e = assert_error(r, "a .cf with a base")
    assert_error_quality(e, names=["baseProjectName"], suggests=["Omit baseProjectName"],
                         ctx="a configuration depends on nothing")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_an_unknown_base_is_refused():
    r = call(TOOL, {"filePath": _EPF, "projectName": _REFUSED_PROJECT,
                    "baseProjectName": "NoSuchBaseProject_e2e_zzz"})
    e = assert_error(r, "an unknown base")
    assert_error_quality(e, names=["NoSuchBaseProject_e2e_zzz"], suggests=["list_projects"],
                         ctx="the not-found refusal names the value and the discovery tool")
    assert_no_diff("a refused call touches nothing")


@e2e_test(tool=TOOL, kind="action")
def test_an_extension_is_refused_as_a_base():
    """The base must be a CONFIGURATION project; the extension fixture is not one."""
    r = call(TOOL, {"filePath": _CFE, "projectName": _REFUSED_PROJECT,
                    "baseProjectName": TESTS_PROJECT})
    e = assert_error(r, "an extension as the base")
    assert_error_quality(e, names=[TESTS_PROJECT], suggests=["configuration project"],
                         ctx="the refusal names the project and what the base must be")
    _assert_fixtures_untouched("a refused call must not touch the extension it names")


@e2e_test(tool=TOOL, kind="action")
def test_wait_seconds_out_of_range_is_refused():
    r = call(TOOL, {"filePath": _CF, "projectName": _REFUSED_PROJECT, "waitSeconds": 46})
    e = assert_error(r, "waitSeconds above the maximum")
    assert_error_quality(e, names=["waitSeconds"], suggests=["0 to 45"],
                         ctx="the refusal names the range")
    assert_no_diff("a refused call touches nothing")

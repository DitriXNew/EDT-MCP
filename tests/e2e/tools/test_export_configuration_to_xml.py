"""
e2e tests for export_configuration_to_xml (kind: action).

WHAT THE TOOL DOES (from ExportConfigurationToXmlTool.java):
  Wraps the EDT action "Export -> Configuration to XML Files" — dumps an open EDT
  configuration project to a DIRECTORY of XML source files (1C DumpConfigToFiles
  format) by calling, via reflection,
  IExportConfigurationFilesApi.exportProject(String projectName, Path outputPath).

  Response type is MARKDOWN (getResponseType() == MARKDOWN), so the payload is in
  r.text (NOT r.structured). On success the FrontMatter carries:
      ---
      tool: export_configuration_to_xml
      status: success
      project: <projectName>
      outputPath: <normalized ABSOLUTE path>
      [outsideWorkspace: true]   # only when outputPath is outside the EDT workspace
      ---
      # Configuration exported to XML files
      - Project: <projectName>
      - Output path: <abs>
      [> Note: outputPath is outside the EDT workspace; ...]

  Required params (BOTH guarded by JsonUtils.requireArguments("projectName",
  "outputPath"), projectName checked FIRST):
      projectName  -> missing/blank => "projectName is required"
      outputPath   -> missing/blank => "outputPath is required"

  outputPath handling in execute():
      - normalized to an absolute path (Paths.get(..).toAbsolutePath().normalize())
      - if it EXISTS but is NOT a directory (a plain file) =>
            "outputPath exists but is not a directory: <abs>"
      - otherwise Files.createDirectories(outputPath) (a missing dir is created;
        that is NOT an error)
      - export writes to ANY absolute path; a path outside the workspace is flagged
        (outsideWorkspace: true) but NOT rejected.

  Error/sentinel paths (env-dependent):
      - CLI API plugin com._1c.g5.v8.dt.cli.api absent (Activator returns null) =>
            "IExportConfigurationFilesApi is not available. Required EDT plugin
             com._1c.g5.v8.dt.cli.api is not installed."
      - exportProject throws (e.g. unknown/closed project — there is NO early
        project-existence guard; it falls through to the reflective invoke) =>
            "Export failed: <cause message>"
      - signature drift => "CLI API mismatch: <msg>"

ACTION-TOOL SAFETY (why the happy path is safe here):
  export_configuration_to_xml writes XML to the outputPath ARGUMENT, NOT into the
  project tree. The happy test exports to a fresh directory under the OS TEMP dir
  (tempfile.mkdtemp), i.e. OUTSIDE this repo, so it can NEVER create files inside
  TestConfiguration/ or dirty the git working tree. Every test still asserts
  assert_no_diff() on TestConfiguration — exporting the model to an external dir
  must not mutate the source project. The temp dir is removed in a finally block
  (it is outside the repo, so it has zero effect on the git fixture either way).

ENVIRONMENT ROBUSTNESS:
  Whether com._1c.g5.v8.dt.cli.api is installed in THIS EDT is environment-
  dependent (like LanguageTool for the translation tools). So the happy path has
  two correct branches:
    - API present  -> real success contract (FrontMatter status + XML files land
                      in the temp dir) — mutation-sensitive: a no-op / wrong status
                      / empty output dir fails it.
    - API absent   -> the clear, actionable "...is not available. ...not installed."
                      sentinel — asserted via assert_error_quality.
  Both branches are real behaviour and both fail if the tool is broken.
"""

import os
import shutil
import tempfile

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# Stable substring of the API-unavailable sentinel. Delimiter-free (no '.' issues)
# so it survives Gson HTML-escaping of the JSON error payload. When this appears,
# the CLI export API is simply not installed in this EDT — a documented, env-driven
# branch, not a tool bug.
_API_ABSENT_MARKER = "IExportConfigurationFilesApi is not available"


def _has_xml_output(directory):
    """True if the export actually wrote at least one .xml file under `directory`.

    A real configuration dump produces Configuration.xml plus a tree of per-object
    XML. Asserting on real files on disk (not just the response text) is the
    mutation-sensitive proof that the export happened: a tool that returned a
    success document but wrote nothing would fail this.
    """
    for _root, _dirs, files in os.walk(directory):
        for f in files:
            if f.lower().endswith(".xml"):
                return True
    return False


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (env-robust: real export OR the clear not-installed sentinel)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="export_configuration_to_xml", kind="action")
def test_export_to_temp_dir_succeeds_or_clear_api_sentinel():
    """Export TestConfiguration to a fresh OS-temp directory (outside the repo).

    API present  -> assert the real success contract: FrontMatter status: success,
                    the export heading, the echoed project, AND real .xml files on
                    disk in the temp dir.
    API absent   -> assert the clear, actionable not-installed sentinel.
    Either way TestConfiguration must stay byte-for-byte unchanged.
    """
    # mkdtemp creates a NEW empty dir under the OS temp root — guaranteed outside
    # this repo, so this export can never touch TestConfiguration/ or the git tree.
    out_dir = tempfile.mkdtemp(prefix="edt_export_e2e_")
    try:
        r = call("export_configuration_to_xml", {
            "projectName": PROJECT,
            "outputPath": out_dir,
        })
        if r.is_error:
            # CLI export API not installed in this EDT — the documented env branch.
            err = r.error_text()
            assert_contains(err, _API_ABSENT_MARKER,
                            "an error here must be the not-installed API sentinel, "
                            "not an unexpected failure")
            assert_error_quality(
                err,
                names=["IExportConfigurationFilesApi"],
                suggests=["not installed"],
                ctx="API-unavailable sentinel must name the API and say it is not installed")
            # No export happened -> temp dir must be empty of XML output.
            assert not _has_xml_output(out_dir), \
                "API-absent sentinel must not have written any XML files"
        else:
            # API present: assert the REAL success document, not just 'not error'.
            assert_ok(r, "export TestConfiguration to temp dir")
            # FrontMatter (MARKDOWN tool -> r.text). status: success proves the tool
            # reached the post-invoke success branch, not a half-finished path.
            assert_contains(r.text, "tool: export_configuration_to_xml",
                            "FrontMatter must tag the producing tool")
            assert_contains(r.text, "status: success",
                            "FrontMatter must report success after a real export")
            assert_contains(r.text, "project: " + PROJECT,
                            "FrontMatter must echo the exported project name")
            # Body heading + echoed project — a no-op/wrong document would miss these.
            assert_contains(r.text, "# Configuration exported to XML files",
                            "body must carry the export heading")
            assert_contains(r.text, "- Project: " + PROJECT,
                            "body must echo the exported project")
            # GROUND TRUTH: the export must have written real .xml files into the
            # temp dir. This is what fails if exportProject silently wrote nothing.
            assert _has_xml_output(out_dir), \
                "export reported success but wrote no .xml files under outputPath"
        # In BOTH branches: exporting the model to an EXTERNAL dir must never mutate
        # the source project on disk.
        assert_no_diff("export writes to an external dir, never into TestConfiguration")
    finally:
        # The temp dir is outside the repo; removing it has no effect on the git
        # fixture. Cleanup keeps the OS temp area tidy across the serial run.
        shutil.rmtree(out_dir, ignore_errors=True)


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="export_configuration_to_xml", kind="action")
def test_missing_project_name_errors_clearly():
    """Missing required projectName. requireArguments checks projectName FIRST, so
    omitting it (even though outputPath is present) must produce the projectName
    guard — not the outputPath guard, and not an export attempt."""
    out_dir = tempfile.mkdtemp(prefix="edt_export_e2e_")
    try:
        r = call("export_configuration_to_xml", {
            "outputPath": out_dir,
        })
        e = assert_error(r, "missing projectName")
        # Names the missing param. The required-arg guard is "<name> is required"
        # with no next-step pointer.
        # AUDIT: "projectName is required" names the param but offers no actionable
        # next step (e.g. list_projects to discover a valid open project name) ->
        # suggests=[] intentional. Fix-card: make the required-arg guard actionable.
        assert_error_quality(e, names=["projectName"], suggests=[],
                             ctx="missing projectName names the param")
        # A rejected call must not have written XML anywhere.
        assert not _has_xml_output(out_dir), \
            "a rejected (missing-arg) call must not export anything"
        assert_no_diff("a rejected call must not touch the project on disk")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool="export_configuration_to_xml", kind="action")
def test_missing_output_path_errors_clearly():
    """Missing required outputPath (projectName present, so the FIRST guard passes
    and the outputPath guard fires). Confirms outputPath is genuinely required and
    that a present projectName does not let the call slip through."""
    r = call("export_configuration_to_xml", {
        "projectName": PROJECT,
    })
    e = assert_error(r, "missing outputPath")
    # AUDIT: "outputPath is required" names the param but is not actionable (no hint
    # of the expected shape, e.g. an absolute scratch directory path) -> suggests=[].
    # Fix-card: append a usage hint to the outputPath required-arg guard.
    assert_error_quality(e, names=["outputPath"], suggests=[],
                         ctx="missing outputPath names the param")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="export_configuration_to_xml", kind="action")
def test_output_path_is_a_file_errors_clearly():
    """outputPath points at an EXISTING regular FILE, not a directory. execute()
    rejects this BEFORE Files.createDirectories with
    'outputPath exists but is not a directory: <abs>'. This is the only path-SHAPE
    validation in the tool and it must fire before any export work.

    Mutation thinking: a broken tool that skipped the isDirectory check would try
    to mkdir over a file (fail with a raw IOException) or — worse — proceed; this
    test pins the clear, specific rejection."""
    # A real file under the OS temp dir, outside the repo.
    fd, file_path = tempfile.mkstemp(prefix="edt_export_e2e_notdir_", suffix=".txt")
    os.close(fd)
    try:
        r = call("export_configuration_to_xml", {
            "projectName": PROJECT,
            "outputPath": file_path,
        })
        e = assert_error(r, "outputPath is an existing file")
        # The message must carry the distinctive 'not a directory' diagnostic. It is
        # delimiter-free, so it survives the JSON HTML-escaping of the path. The
        # absolute path itself is awkward to match through escaping, so the stable
        # diagnostic phrase is the robust signal.
        # AUDIT: the message states the problem ('exists but is not a directory')
        # but offers no fix ('pass a directory path or a new path') -> suggests=[].
        # Fix-card: make this guard suggest passing a directory/new path.
        assert_error_quality(e, names=["not a directory"], suggests=[],
                             ctx="file-as-outputPath rejection names the path-shape problem")
        assert_no_diff("a rejected (bad-path) call must not touch the project on disk")
    finally:
        os.remove(file_path)


@e2e_test(tool="export_configuration_to_xml", kind="action")
def test_nonexistent_project_errors_or_clear_api_sentinel():
    """A well-formed call but a project that does NOT exist. There is NO early
    project-existence guard in execute(): with the CLI API present the bad name
    flows to exportProject(...) which throws -> 'Export failed: <cause>'. With the
    API absent the call short-circuits at the not-installed sentinel BEFORE the
    project is ever looked at. Both are correct; assert the observed branch.

    Either way the call must fail (a non-existent project must NOT silently
    'succeed'), name something specific, and leave TestConfiguration untouched."""
    bad = "NoSuchProject_ZZZ_e2e"
    out_dir = tempfile.mkdtemp(prefix="edt_export_e2e_")
    try:
        r = call("export_configuration_to_xml", {
            "projectName": bad,
            "outputPath": out_dir,
        })
        e = assert_error(r, "non-existent project")
        if _API_ABSENT_MARKER in e:
            # API not installed -> short-circuit sentinel (project never inspected).
            assert_error_quality(
                e,
                names=["IExportConfigurationFilesApi"],
                suggests=["not installed"],
                ctx="API-unavailable sentinel is clear and actionable")
        else:
            # API present -> the reflective invoke threw and surfaced as 'Export failed'.
            # AUDIT: the tool does NOT pre-validate project existence; the failure is
            # the generic 'Export failed: <cause from the CLI API>'. The cause text is
            # produced by EDT and is NOT guaranteed to name the bad project or point
            # at list_projects, so we pin only the stable 'Export failed' prefix that
            # the tool itself controls. Fix-card: add an early project-existence guard
            # ('Project not found: <name> — use list_projects') instead of letting a
            # bad name fall through to a generic CLI-API failure.
            assert_error_quality(e, names=["Export failed"], suggests=[],
                                 ctx="bad project surfaces as a generic Export failed")
        # Whichever branch: nothing should have been exported for a bad project.
        assert not _has_xml_output(out_dir), \
            "a failed export (bad project) must not have written XML files"
        assert_no_diff("a failed export must not touch the source project on disk")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool="export_configuration_to_xml", kind="action")
def test_blank_project_name_rejected_by_required_guard():
    """Boundary: projectName present but the EMPTY string. requireArgument treats
    '' as missing (value == null || value.isEmpty()), so it must hit the same
    'projectName is required' guard rather than slipping through to an export of
    some default/blank project (which would be a silent-success bug)."""
    out_dir = tempfile.mkdtemp(prefix="edt_export_e2e_")
    try:
        r = call("export_configuration_to_xml", {
            "projectName": "",
            "outputPath": out_dir,
        })
        e = assert_error(r, "empty-string projectName")
        # AUDIT: an empty value is reported through the same 'projectName is required'
        # guard; it does not distinguish 'blank' from 'omitted' and offers no next
        # step -> suggests=[]. Fix-card: actionable required-arg guard.
        assert_error_quality(e, names=["projectName"], suggests=[],
                             ctx="empty-string projectName rejected by the required-arg guard")
        assert not _has_xml_output(out_dir), \
            "a blank projectName must not export anything"
        assert_no_diff("a rejected call must not touch the project on disk")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)

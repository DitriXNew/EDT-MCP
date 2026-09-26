"""
e2e tests for export_configuration_to_file (kind: action) - issue #458.

WHAT THE TOOL DOES (ExportConfigurationToFileTool.java):
  Dumps the configuration (.cf) or one extension (.cfe) FROM an application's infobase through
  EDT's public IExportConfigurationFileService - the chain EDT's "Export configuration to file"
  wizard runs. MARKDOWN response: the payload is in r.text.

  Synchronous refusals (before any job): required projectName/outputFile, waitSeconds 0..45,
  outputFile absolute + .cf/.cfe + existing folder + NOT existing (never replaced), an unknown
  project, and a .cf/.cfe name that does not match what is exported.

  The dump itself runs in a background job: prepare the application, read EDT's infobase-vs-
  project equality (outOfDate is refused unless allowOutOfDate=true), dump into a temporary
  sibling file, publish it by rename only after it is seen non-empty and fresh.

SAFETY:
  Every file goes to a fresh OS-temp directory (outside the repo), removed in a finally block.
  The tool changes neither the project nor the infobase, so every test asserts assert_no_diff().

ENVIRONMENT ROBUSTNESS:
  A real dump needs a default application with an infobase AND a locally installed 1C platform
  with a thick client. Headless CI has neither, so the happy paths accept the tool's own
  actionable environment refusals (no application / no platform) as the other correct branch -
  asserted for quality, with no file left behind.
"""

import os
import re
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
    PROJECT_DIR,
    REPO_ROOT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
)

TOOL = "export_configuration_to_file"
TESTS_PROJECT_DIR = os.path.join(REPO_ROOT, *TESTS_PROJECT_REL.split("/"))

# Polls of get_job_status (45 s each) before a dump counts as hung: 20 x 45 s = 15 min.
_MAX_POLLS = 20

# Refusals that mean "this environment cannot dump", not "the tool is broken".
_ENV_MARKERS = (
    "has no default application",
    "no registered 1C:Enterprise platform",
    "REMOTE thick client",
    "has no infobase to dump from",
)


def _job_id(markdown):
    m = re.search(r"^\| jobId \| ([^|]+) \|\s*$", markdown or "", re.MULTILINE)
    return m.group(1).strip() if m else None


def _job_status(markdown):
    m = re.search(r"^# Background job: (running|done|failed|cancelled)\s*$", markdown or "",
                  re.MULTILINE)
    return m.group(1) if m else None


def _export(args):
    """Runs one export to completion. Returns (outcome, text): outcome is 'done', 'error' or
    'cancelled'; text is the report or the error."""
    r = call(TOOL, dict(args, waitSeconds=45))
    if r.is_error:
        return "error", r.error_text()
    if not r.text.startswith("**Pending:**"):
        return "done", r.text
    job_id = _job_id(r.text)
    assert job_id, "a Pending answer must name its jobId: " + r.text[:300]
    for _ in range(_MAX_POLLS):
        s = call("get_job_status", {"jobId": job_id, "waitSeconds": 45})
        assert_ok(s, "poll the export job")
        status = _job_status(s.text)
        if status == "running":
            continue
        if status == "done":
            return "done", s.text
        if status == "failed":
            return "error", s.text
        return "cancelled", s.text
    call("cancel_job", {"jobId": job_id})
    raise AssertionError("the export job did not finish within %d polls" % _MAX_POLLS)


def _assert_env_refusal(err, ctx):
    """The dump could not run in this environment: the refusal must say why and what to do."""
    assert any(m in err for m in _ENV_MARKERS), \
        "unexpected failure (not an environment refusal) [%s]: %s" % (ctx, err[:600])
    assert_error_quality(err, names=[], suggests=[], ctx=ctx)


def _assert_no_partial_files(folder):
    leftovers = [f for f in os.listdir(folder) if ".partial-" in f]
    assert not leftovers, "temporary dump files were left behind: %s" % leftovers


def _assert_published(report, path, source, project_dir):
    assert_contains(report, "status: success", "the report must state success")
    assert_contains(report, "source: " + source, "the report must name what was dumped")
    assert_contains(report, "infobaseSync: ", "the report must state the infobase sync reading")
    # Ground truth: the file itself, not the report.
    assert os.path.isfile(path), "success reported but no file at " + path
    size = os.path.getsize(path)
    assert size > 0, "success reported but the file is empty: " + path
    assert_contains(report, "sizeBytes: %d" % size, "the reported size must match the file")
    # The 1C container names its root entry by the configuration's UUID (UTF-16LE).
    with open(os.path.join(project_dir, "src", "Configuration", "Configuration.mdo"),
              encoding="utf-8") as f:
        uuid = re.search(r'uuid="([0-9a-fA-F-]{36})"', f.read()).group(1)
    with open(path, "rb") as f:
        assert uuid.encode("utf-16-le") in f.read(), \
            "the file does not hold configuration %s of %s" % (uuid, project_dir)


def _export_allowing_a_stale_infobase(args, out_file, ctx):
    """Exports; a stale-infobase refusal is asserted for quality and then overridden, so the
    happy path is proven on a stand whose infobase lags the project too."""
    outcome, text = _export(args)
    if outcome == "error" and "is out of date" in text:
        assert_error_quality(text, names=["update_database", "allowOutOfDate=true"],
                             suggests=["update_database"], ctx=ctx + ": out-of-date refusal")
        assert not os.path.exists(out_file), "a refused export must not create the file"
        outcome, text = _export(dict(args, allowOutOfDate=True))
    elif outcome == "done":
        assert "infobaseSync: outOfDate" not in text, \
            "an out-of-date infobase must be refused without allowOutOfDate=true: " + text[:400]
    return outcome, text


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (env-robust)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool=TOOL, kind="action")
def test_configuration_dump_lands_as_a_non_empty_cf():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    out_file = os.path.join(out_dir, "TestConfiguration.cf")
    try:
        outcome, text = _export_allowing_a_stale_infobase(
            {"projectName": PROJECT, "outputFile": out_file}, out_file, "configuration dump")
        if outcome == "done":
            _assert_published(text, out_file, "configuration", PROJECT_DIR)
            assert_contains(text, "tool: " + TOOL, "front matter names the tool")
        else:
            assert outcome == "error", "unexpected job outcome %s: %s" % (outcome, text[:400])
            _assert_env_refusal(text, "configuration dump")
            assert not os.path.exists(out_file), "a failed export must not create the file"
        _assert_no_partial_files(out_dir)
        assert_no_diff("a dump reads the infobase and never touches the fixture")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_extension_project_dumps_its_extension_as_cfe():
    """projectName = the extension project: the base project's application is used and the
    extension's own name is resolved from the project."""
    out_dir = tempfile.mkdtemp(prefix="edt_export_cfe_e2e_")
    out_file = os.path.join(out_dir, "tests.cfe")
    try:
        outcome, text = _export_allowing_a_stale_infobase(
            {"projectName": TESTS_PROJECT, "outputFile": out_file}, out_file, "extension dump")
        if outcome == "done":
            _assert_published(text, out_file, "extension", TESTS_PROJECT_DIR)
            assert_contains(text, "project: " + PROJECT,
                            "the dump comes from the BASE project's application")
            assert_contains(text, "extensionName: ", "the report names the extension")
        else:
            assert outcome == "error", "unexpected job outcome %s: %s" % (outcome, text[:400])
            if not any(m in text for m in _ENV_MARKERS):
                # The extension is not installed in this infobase: the Designer's own refusal.
                assert_error_quality(text, names=["Designer"], suggests=["update_database"],
                                     ctx="extension missing from the infobase")
            assert not os.path.exists(out_file), "a failed export must not create the file"
        _assert_no_partial_files(out_dir)
        assert_no_diff("an extension dump never touches the fixture")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (synchronous, no job, no file)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool=TOOL, kind="action")
def test_missing_required_arguments():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    try:
        e = assert_error(call(TOOL, {"outputFile": os.path.join(out_dir, "a.cf")}), "no projectName")
        assert_error_quality(e, names=["projectName"], suggests=[], ctx="missing projectName")
        e = assert_error(call(TOOL, {"projectName": PROJECT}), "no outputFile")
        assert_error_quality(e, names=["outputFile"], suggests=[], ctx="missing outputFile")
        assert not os.listdir(out_dir), "a refused call must write nothing"
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_existing_output_file_is_never_replaced():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    existing = os.path.join(out_dir, "keep.cf")
    try:
        with open(existing, "wb") as f:
            f.write(b"keep me")
        e = assert_error(call(TOOL, {"projectName": PROJECT, "outputFile": existing}),
                         "existing outputFile")
        assert_error_quality(e, names=["already exists"], suggests=["new file name"],
                             ctx="existing outputFile")
        with open(existing, "rb") as f:
            assert f.read() == b"keep me", "the existing file must be left byte-for-byte intact"
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_output_file_shape_is_validated():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    try:
        e = assert_error(call(TOOL, {"projectName": PROJECT, "outputFile": "relative.cf"}),
                         "relative outputFile")
        assert_error_quality(e, names=["relative.cf"], suggests=["absolute path"],
                             ctx="relative outputFile")

        e = assert_error(call(TOOL, {"projectName": PROJECT,
                                     "outputFile": os.path.join(out_dir, "dump.zip")}),
                         "wrong file extension")
        assert_error_quality(e, names=["dump.zip"], suggests=[".cf", ".cfe"],
                             ctx="wrong file extension")

        missing = os.path.join(out_dir, "no-such-folder")
        e = assert_error(call(TOOL, {"projectName": PROJECT,
                                     "outputFile": os.path.join(missing, "a.cf")}),
                         "missing folder")
        assert_error_quality(e, names=["no-such-folder"], suggests=["Create it first"],
                             ctx="missing folder")
        assert not os.path.exists(missing), "the tool must not create folders"
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_file_kind_must_match_what_is_exported():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    try:
        e = assert_error(call(TOOL, {"projectName": PROJECT,
                                     "outputFile": os.path.join(out_dir, "a.cfe")}),
                         ".cfe for the configuration")
        assert_error_quality(e, names=[".cfe"], suggests=["Use a .cf name", "extensionName"],
                             ctx=".cfe for the configuration")

        e = assert_error(call(TOOL, {"projectName": TESTS_PROJECT,
                                     "outputFile": os.path.join(out_dir, "a.cf")}),
                         ".cf for an extension project")
        assert_error_quality(e, names=[".cf"], suggests=["Use a .cfe name"],
                             ctx=".cf for an extension project")
        assert not os.listdir(out_dir), "a refused call must write nothing"
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_extension_project_with_a_different_extension_name_is_refused():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    try:
        e = assert_error(call(TOOL, {"projectName": TESTS_PROJECT,
                                     "outputFile": os.path.join(out_dir, "a.cfe"),
                                     "extensionName": "SomeOtherExtension"}),
                         "conflicting extensionName")
        assert_error_quality(e, names=["SomeOtherExtension"], suggests=["Omit extensionName"],
                             ctx="conflicting extensionName")
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_extension_name_resolves_to_the_infobase_name():
    """projectName = the configuration: extensionName by the extension's configuration Name (any
    case) or by its EDT project name resolves to that Name; an unknown one is passed on as given.
    A .cf name makes the resolution visible synchronously, before any job or file."""
    with open(os.path.join(TESTS_PROJECT_DIR, "src", "Configuration", "Configuration.mdo"),
              encoding="utf-8") as f:
        ext_name = re.search(r"<name>([^<]+)</name>", f.read()).group(1)
    assert ext_name != TESTS_PROJECT, "the fixture must tell the two names apart"
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    try:
        cases = ((TESTS_PROJECT, ext_name), (ext_name.upper(), ext_name),
                 ("NoSuchExtension_e2e", "NoSuchExtension_e2e"))
        for requested, resolved in cases:
            e = assert_error(call(TOOL, {"projectName": PROJECT,
                                         "outputFile": os.path.join(out_dir, "a.cf"),
                                         "extensionName": requested}),
                             "extensionName=%s with a .cf name" % requested)
            assert "but the source is extension '%s'," % resolved in e, \
                "extensionName=%s must resolve to '%s': %s" % (requested, resolved, e[:400])
            assert_error_quality(e, names=[resolved], suggests=["Use a .cfe name"],
                                 ctx="extensionName=%s with a .cf name" % requested)
            if requested != resolved:
                assert "extension '%s'" % requested not in e, \
                    "the caller's spelling must not reach the Designer: " + e[:400]
        assert not os.listdir(out_dir), "a refused call must write nothing"
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_unknown_project_and_bad_wait_are_refused():
    out_dir = tempfile.mkdtemp(prefix="edt_export_cf_e2e_")
    try:
        bad = "NoSuchProject_ZZZ_e2e"
        e = assert_error(call(TOOL, {"projectName": bad,
                                     "outputFile": os.path.join(out_dir, "a.cf")}),
                         "unknown project")
        assert_error_quality(e, names=[bad], suggests=["list_projects"], ctx="unknown project")

        e = assert_error(call(TOOL, {"projectName": PROJECT,
                                     "outputFile": os.path.join(out_dir, "a.cf"),
                                     "waitSeconds": 46}),
                         "waitSeconds above the cap")
        assert_error_quality(e, names=["waitSeconds"], suggests=["0 to 45"],
                             ctx="waitSeconds above the cap")
        assert not os.listdir(out_dir), "a refused call must write nothing"
        assert_no_diff("refusals touch nothing")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)

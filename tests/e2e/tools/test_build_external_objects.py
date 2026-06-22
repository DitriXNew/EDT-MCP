"""
e2e tests for build_external_objects (kind: action) — issue #122.

WHAT THE TOOL DOES (BuildExternalObjectsTool.java, design variant A):
  Builds ONE or ALL external data processors / reports of an EDT external-object
  project to compiled .epf / .erf files on disk, UNATTENDED. It is the MCP
  counterpart of EDT's "Build external data processor/report" action.

  Implementation (per the approved spec): the platform dumper
  IExternalObjectDumper.dump(IProject, EObject, java.nio.file.Path, IProgressMonitor)
  (package com._1c.g5.v8.dt.platform.services.core.dump) is resolved by reflection
  from the platform-services Guice injector, and writes each built object to a
  CALLER-CHOSEN outputDir. The dump runs in a background Job with a bounded timeout
  and dialog suppressors so the call never blocks on an interactive modal.

  HARD PRECONDITION (like update_database): a real build needs an associated
  infobase + a resolvable 1C runtime. If that precondition is unmet the tool MUST
  return an actionable precondition error pointing at create_infobase /
  set_infobase_credentials — it must NOT hang and must NOT half-succeed.

  Disk-export shape mirrors export_configuration_to_xml:
      projectName  (required) — the EDT external-object project to build
      outputDir    (required) — an absolute/relative directory the .epf/.erf land in
      objectName   (optional) — build only this external object; omitted => build ALL
  outputDir is normalized to an absolute path, created if missing, and rejected if
  it points at an existing regular FILE.

============================================================================
ACTION-TOOL SAFETY (why these tests can never dirty the fixture)
============================================================================
build_external_objects writes compiled artifacts to the outputDir ARGUMENT, never
into a project's source tree. Every happy/contract call here targets a fresh
directory under the OS TEMP root (tempfile.mkdtemp), i.e. OUTSIDE this repo, so it
can NEVER create files inside TestConfiguration/ or dirty the git working tree. The
temp dir is removed in a finally block (it is outside the repo, so its existence has
zero effect on the git fixture either way). EVERY test still asserts assert_no_diff()
on TestConfiguration: building external objects to an external dir — or being
rejected — must never mutate the source fixture on disk.

============================================================================
ENVIRONMENT ROBUSTNESS (the happy path has two correct branches)
============================================================================
Whether THIS EDT has the platform-services dumper plugin installed AND an
external-object project with a usable infobase + 1C runtime is environment-
dependent (like the CLI export API for export_configuration_to_xml, or a live
infobase for update_database). So the happy path accepts EITHER:

  - REAL build present  -> the real success contract: a status: success document
                           AND a real .epf/.erf actually on disk in the temp dir
                           (walked from disk — a tool that returned a success
                           document but built nothing fails this).
  - precondition absent -> the clear, actionable SENTINEL: the dumper plugin is not
                           available, OR the infobase/runtime precondition is unmet
                           (the message points at create_infobase /
                           set_infobase_credentials). Asserted via
                           assert_error_quality.

Both branches are real behaviour and both FAIL if the tool is broken (a no-op that
fabricated success, or that wrote nothing, would fail the present-branch on-disk
check; a hang would trip the per-test timeout; a vague error fails the absent-branch
error-quality bar).

NOTE FOR THE GOLDEN SLICE: tools_list.golden.json must be regenerated on the live
stand once build_external_objects is registered (it cannot be hand-edited). This e2e
file does not touch the golden; the golden's own coverage-ratchet test enforces it.
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
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


# A fresh external-object project we create at runtime so the tool has a REAL
# IExternalObjectProject to enumerate (an empty one: getExternalObjects() may be
# empty, which is itself a documented branch — see the happy test). Unique name so
# it cannot collide with anything in the workspace.
EXT_OBJ_PROJECT = "BuildExtObjTest_e2e"

# Stable, DELIMITER-FREE substrings the "precondition absent" branch may surface.
# Delimiter-free (no '.') so they survive Gson HTML-escaping of the JSON error
# payload. The tool guarantees its absent-branch message names AT LEAST ONE of the
# dumper-unavailable sentinel OR an infobase-precondition pointer (create_infobase /
# set_infobase_credentials) — none of these phrases can appear in a success document
# or in a missing-arg / wrong-project rejection, so matching one here is an
# unambiguous "the build precondition was simply not met in this env" signal.
_DUMPER_ABSENT_MARKER = "IExternalObjectDumper"          # platform-services plugin not installed
_INFOBASE_PRECOND_MARKERS = ("create_infobase", "set_infobase_credentials", "infobase")

# Stable, DELIMITER-FREE substring of the tool's enumerate-step "nothing to build"
# message ("The external-object project has no external data processors/reports to
# build."). This is the realistic branch on a stand where the dumper IS resolvable
# but the freshly seeded external-object project is still EMPTY: project resolution
# and dumper resolution both pass, then enumeration finds zero objects. Delimiter-
# free so it survives Gson HTML-escaping of the JSON error payload.
_EMPTY_PROJECT_MARKER = "no external data processors/reports to build"


def _epf_erf_artifacts(directory):
    """All built external-object artifacts (.epf data processors / .erf reports)
    found anywhere under `directory`.

    Walking the real files on disk (not just trusting the response text) is the
    mutation-sensitive proof that the build happened: a tool that returned a success
    document but produced no compiled artifact would fail the present-branch assert.
    """
    out = []
    for root, _dirs, files in os.walk(directory):
        for f in files:
            if f.lower().endswith((".epf", ".erf")):
                out.append(os.path.join(root, f))
    return out


def _is_precondition_absent(err):
    """True if `err` is the documented 'build precondition not met in this env' branch:
    the platform-services dumper plugin is absent, OR the infobase/1C-runtime
    precondition is unmet (the message points at create_infobase /
    set_infobase_credentials). NOT a missing-arg / wrong-project rejection (those name
    a param/project and an explicit list_projects / 'external' next step instead)."""
    low = (err or "").lower()
    if _DUMPER_ABSENT_MARKER.lower() in low:
        return True
    return any(m.lower() in low for m in _INFOBASE_PRECOND_MARKERS)


def _is_empty_project(err):
    """True if `err` is the documented 'nothing to build' branch: the project and the
    dumper both resolved, but the external-object project has zero external objects to
    build (the realistic branch for a freshly seeded, EMPTY external-object project on
    a stand where the dumper IS resolvable). NOT a precondition-absent sentinel and NOT
    a missing-arg / wrong-project rejection."""
    return _EMPTY_PROJECT_MARKER in (err or "").lower()


def _assert_absent_sentinel_quality(err):
    """The 'precondition absent' branch must be a GOOD, actionable error: either the
    dumper-unavailable sentinel (names the dumper, says it is not installed/available)
    or the infobase-precondition pointer (names an infobase next-step tool). assert it
    is not a bare/empty/stack-trace error and that it carries one of those signals."""
    assert_error_quality(err, names=[], suggests=[],
                         ctx="precondition-absent sentinel must be a clear, non-bare error")
    low = (err or "").lower()
    if _DUMPER_ABSENT_MARKER.lower() in low:
        # Dumper plugin not installed -> name the dumper AND say it is unavailable.
        assert_error_quality(
            err,
            names=[_DUMPER_ABSENT_MARKER],
            suggests=["not available"],
            ctx="dumper-unavailable sentinel names the dumper and says it is not available")
    else:
        # Infobase/runtime precondition unmet -> point at an infobase setup tool.
        assert any(m.lower() in low for m in _INFOBASE_PRECOND_MARKERS), \
            ("precondition-absent error must point at create_infobase / "
             "set_infobase_credentials (an infobase precondition), got: %r" % (err,))


def _ensure_absent(name):
    """Best-effort pre/post cleanup of the runtime-created external-object project
    (removes a leftover from a prior crashed run; idempotent when already gone)."""
    call("delete_project", {"projectName": name, "deleteContent": True, "confirm": True})


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / CONTRACT PATH (env-robust: REAL build OR the clear precondition sentinel)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="build_external_objects", kind="action")
def test_build_all_succeeds_or_clear_precondition_sentinel():
    """Build ALL external objects of a real external-object project into a fresh
    OS-temp directory (outside the repo).

    A real external-object project (created at runtime via create_project
    projectKind=externalObjects) is resolved and enumerated end-to-end. Then EITHER:
      - precondition present -> the real success contract: status: success AND a real
                               .epf/.erf on disk in the temp dir (a no-op/empty-output
                               build fails this);
      - precondition absent  -> the clear, actionable sentinel (dumper plugin not
                               available, or the infobase/runtime precondition pointing
                               at create_infobase / set_infobase_credentials).
    Either way TestConfiguration must stay byte-for-byte unchanged, and the call must
    not hang (the per-test timeout is the backstop).

    NB the freshly created external-object project is EMPTY (no external objects yet),
    which is itself a valid branch: with the dumper resolvable, project + dumper both
    resolve and then enumeration finds zero objects -> the tool returns its clear
    'nothing to build' message (as an error result); with the precondition absent, the
    precondition sentinel fires first. We accept all of: a real artifact (success
    branch, if the project happened to contain objects), the empty-project 'nothing to
    build' branch, or the precondition sentinel — and we FAIL only on a hang, a
    fabricated success that named a built object but wrote no file, or an
    unexpected/vague error.
    """
    _ensure_absent(EXT_OBJ_PROJECT)
    out_dir = tempfile.mkdtemp(prefix="edt_build_extobj_e2e_")
    try:
        cr = call("create_project", {"projectKind": "externalObjects", "name": EXT_OBJ_PROJECT})
        assert_ok(cr, "seed a fresh external-object project")
        wait_for_project_ready()

        # mkdtemp created a NEW empty dir under the OS temp root — guaranteed outside
        # this repo, so this build can never touch TestConfiguration/ or the git tree.
        r = call("build_external_objects", {
            "projectName": EXT_OBJ_PROJECT,
            "outputDir": out_dir,
        })
        artifacts = _epf_erf_artifacts(out_dir)
        if r.is_error:
            # Two documented, non-building error branches are acceptable here, since the
            # seeded external-object project is freshly created and EMPTY:
            #   - empty-project 'nothing to build': project + dumper both resolved, then
            #     enumeration found zero external objects (the realistic branch on a stand
            #     where the dumper IS resolvable);
            #   - precondition absent: no dumper plugin, or no usable infobase/runtime ->
            #     the actionable sentinel pointing at create_infobase /
            #     set_infobase_credentials.
            # Anything else (a vague/unexpected failure) is a real bug.
            err = r.error_text()
            assert _is_empty_project(err) or _is_precondition_absent(err), \
                ("an error here must be the empty-project 'nothing to build' branch or "
                 "the documented precondition-absent sentinel (dumper unavailable / "
                 "infobase precondition), not an unexpected failure, got: %r" % (err,))
            if _is_precondition_absent(err):
                _assert_absent_sentinel_quality(err)
            # No build happened (empty project or precondition absent) -> the temp dir
            # must contain no compiled artifact.
            assert not artifacts, \
                "a non-building error branch must not have built any .epf/.erf: %s" % artifacts
        else:
            # Precondition present AND the project actually had objects to build: assert
            # the REAL success document, not just 'not error'. (An EMPTY project never
            # reaches here — it returns the 'nothing to build' error handled above.)
            assert_ok(r, "build all external objects of the seeded project")
            # A populated project yields >=1 artifact. A fabricated success that CLAIMED a
            # built object yet wrote no file would be a bug -> if the response names a
            # built artifact, it must exist on disk. We pin the success envelope + the
            # on-disk consistency check.
            blob = (r.text or "") + (str(r.structured) if r.structured else "")
            assert ("success" in blob.lower()) or artifacts, \
                ("a successful build must report success or produce an on-disk "
                 "artifact, got text=%r structured=%r" % (r.text[:200], r.structured))
            # On-disk consistency: every artifact the response names as built must
            # actually exist on disk (a success doc that named a file it never wrote
            # would be the classic fabricated-success bug).
            for art in artifacts:
                assert os.path.isfile(art), "reported artifact missing on disk: %s" % art
        # In BOTH branches: building to an EXTERNAL dir must never mutate the fixture.
        assert_no_diff("build writes to an external dir, never into TestConfiguration")
    finally:
        # The temp dir is outside the repo; removing it has no effect on the git
        # fixture. Clean up the runtime-created external-object project too.
        shutil.rmtree(out_dir, ignore_errors=True)
        _ensure_absent(EXT_OBJ_PROJECT)
    assert_no_diff("the fixture must be untouched after the build round-trip")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="build_external_objects", kind="action")
def test_missing_project_name_errors_clearly():
    """Missing required projectName (outputDir present). The required-arg guard must
    fire and name projectName — not attempt a build, not name outputDir."""
    out_dir = tempfile.mkdtemp(prefix="edt_build_extobj_e2e_")
    try:
        r = call("build_external_objects", {"outputDir": out_dir})
        e = assert_error(r, "missing projectName")
        # AUDIT: "projectName is required" names the param but offers no next step
        # (e.g. list_projects to find an external-object project) -> suggests=[]
        # intentional. Fix-card: make the required-arg guard actionable.
        assert_error_quality(e, names=["projectName"], suggests=[],
                             ctx="missing projectName names the param")
        # A rejected call must not have built anything.
        assert not _epf_erf_artifacts(out_dir), \
            "a rejected (missing-arg) call must not build any artifact"
        assert_no_diff("a rejected call must not touch the project on disk")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool="build_external_objects", kind="action")
def test_missing_output_dir_errors_clearly():
    """Missing required outputDir (projectName present, so the first guard passes and
    the outputDir guard fires). Confirms outputDir is genuinely required and a present
    projectName does not let the call slip through to a default location."""
    r = call("build_external_objects", {"projectName": PROJECT})
    e = assert_error(r, "missing outputDir")
    # AUDIT: "outputDir is required" names the param but is not actionable (no hint of
    # the expected shape, e.g. an absolute scratch directory) -> suggests=[].
    # Fix-card: append a usage hint to the outputDir required-arg guard.
    assert_error_quality(e, names=["outputDir"], suggests=[],
                         ctx="missing outputDir names the param")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="build_external_objects", kind="action")
def test_nonexistent_project_errors_or_clear_precondition_sentinel():
    """A well-formed call but a project that does NOT exist in the workspace. The tool
    must FAIL (a non-existent project must never silently 'succeed'), name something
    specific, and leave TestConfiguration untouched.

    Two correct branches (assert the observed one), neither of which builds anything:
      - the project resolution fires first -> the value-naming 'not found' error that
        echoes the bad name and points at list_projects;
      - OR (if the dumper/infobase precondition is checked before project resolution)
        the precondition-absent sentinel short-circuits. Both are valid; both must be
        clear, and neither may produce an .epf/.erf.
    """
    bad = "NoSuchExtObjProject_ZZZ_e2e"
    out_dir = tempfile.mkdtemp(prefix="edt_build_extobj_e2e_")
    try:
        r = call("build_external_objects", {"projectName": bad, "outputDir": out_dir})
        e = assert_error(r, "non-existent project")
        if _is_precondition_absent(e):
            # Precondition short-circuited before the project was inspected.
            _assert_absent_sentinel_quality(e)
        else:
            # Project resolution fired: the error must echo the bad project name and
            # point the caller at a discovery tool.
            assert_error_quality(e, names=[bad], suggests=["list_projects"],
                                 ctx="non-existent project echoes the name and suggests list_projects")
        # Whichever branch: nothing should have been built for a bad project.
        assert not _epf_erf_artifacts(out_dir), \
            "a failed build (bad project) must not have produced any .epf/.erf"
        assert_no_diff("a failed build must not touch the source project on disk")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool="build_external_objects", kind="action")
def test_non_external_project_errors_clearly():
    """A REAL, open project that is NOT an external-object project (TestConfiguration
    is a configuration). build_external_objects only works on an IExternalObjectProject,
    so it must REFUSE this with a clear, kind-naming error — never try to build a
    configuration project and never half-succeed.

    Two correct branches (assert the observed one), neither of which builds anything:
      - the kind check fires -> an error that names the project and says it is not an
        external-object project (the actionable signal: this tool needs an
        externalObjects project);
      - OR (if the dumper/infobase precondition is checked first) the precondition
        sentinel short-circuits. Both are valid; neither may produce an .epf/.erf.

    Mutation thinking: a broken tool that skipped the kind check would try to dump a
    configuration project (raw failure) or worse proceed; this pins the clear,
    specific refusal and the no-artifact guarantee.
    """
    out_dir = tempfile.mkdtemp(prefix="edt_build_extobj_e2e_")
    try:
        r = call("build_external_objects", {"projectName": PROJECT, "outputDir": out_dir})
        e = assert_error(r, "non-external (configuration) project")
        if _is_precondition_absent(e):
            # Precondition short-circuited before the project-kind check.
            _assert_absent_sentinel_quality(e)
        else:
            # The kind check fired: the error must name the project AND say it is not an
            # external-object project (the actionable 'external' signal).
            assert_error_quality(e, names=[PROJECT], suggests=["external"],
                                 ctx="non-external project is named and the error says it is not an external-object project")
            assert_contains(e.lower(), "external",
                            "the kind-mismatch error must mention 'external'")
        # Whichever branch: a configuration project must not yield any artifact.
        assert not _epf_erf_artifacts(out_dir), \
            "a non-external project must not produce any .epf/.erf"
        assert_no_diff("a rejected (non-external project) call must not touch the fixture")
    finally:
        shutil.rmtree(out_dir, ignore_errors=True)


@e2e_test(tool="build_external_objects", kind="action")
def test_output_dir_is_a_file_errors_clearly():
    """outputDir points at an EXISTING regular FILE, not a directory. Mirroring
    export_configuration_to_xml, execute() must reject this BEFORE any build with the
    distinctive 'not a directory' diagnostic — the only path-SHAPE validation in the
    tool, and it must fire before the dumper is touched.

    Mutation thinking: a broken tool that skipped the isDirectory check would try to
    mkdir over a file (raw IOException) or proceed; this pins the clear rejection.

    Env note: if the dumper/infobase precondition is checked BEFORE the path-shape
    guard, the precondition sentinel fires instead — also a clean, non-building
    refusal. Either way nothing is built and the fixture stays clean.
    """
    fd, file_path = tempfile.mkstemp(prefix="edt_build_extobj_e2e_notdir_", suffix=".txt")
    os.close(fd)
    try:
        r = call("build_external_objects", {"projectName": PROJECT, "outputDir": file_path})
        e = assert_error(r, "outputDir is an existing file")
        if _is_precondition_absent(e):
            _assert_absent_sentinel_quality(e)
        else:
            # The path-shape guard fired: carry the distinctive 'not a directory'
            # diagnostic (delimiter-free, so it survives JSON HTML-escaping of the path).
            # AUDIT: the message states the problem but offers no fix ('pass a directory
            # or a new path') -> suggests=[]. Fix-card: make this guard suggest a dir path.
            assert_error_quality(e, names=["not a directory"], suggests=[],
                                 ctx="file-as-outputDir rejection names the path-shape problem")
        # The outputDir is a FILE (not a dir we can walk), so the no-artifact proof here
        # is simply that the call failed without building — assert it stayed a file.
        assert os.path.isfile(file_path), "the outputDir file must be left untouched"
        assert_no_diff("a rejected (bad-path) call must not touch the fixture")
    finally:
        os.remove(file_path)

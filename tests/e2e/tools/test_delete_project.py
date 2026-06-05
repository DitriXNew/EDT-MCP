"""
e2e tests for delete_project (kind: action) — DESTRUCTIVE workspace project removal.

THE TOOL (DeleteProjectTool, getResponseType() == JSON):
Removes an EDT project from the workspace, optionally deleting its files from disk
(deleteContent). The inverse of import_configuration_from_xml. Destructive, so it is
guarded by a confirm-preview (mirroring delete_metadata): a bare call PREVIEWS
(action='preview', confirmationRequired=true) without removing anything; only confirm=true
performs the removal (action='deleted').

This file carries the import/export ROUND-TRIP happy path that the import/export tool tests
deliberately omit (they cannot create + clean up a real project): here delete_project IS the
cleanup, so the full cycle is exercised end to end —
    export TestConfiguration -> XML  ->  import as a NEW project  ->  verify it deployed
    ->  delete_project (preview, then confirm)  ->  verify it is gone.
The new project and the temp export dir are removed in a finally block, so the test is
repeatable and leaves no workspace residue. assert_no_diff guards the committed fixture
(TestConfiguration is only READ by the export; the import creates a SEPARATE project).
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
    assert_not_contains,
    assert_no_diff,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

# A new project name unlikely to collide with anything in the workspace.
NEW_PROJECT = "RoundTripImport_e2e"
# A temp directory (outside the git fixture) for the XML dump.
EXPORT_DIR = os.path.join(tempfile.gettempdir(), "edt_roundtrip_e2e_xml")


def _ensure_absent(project_name):
    """Best-effort pre/post clean: remove a leftover project from a prior crashed run so the
    import's 'project already exists' guard cannot mask the round-trip. Ignores the result."""
    call("delete_project", {"projectName": project_name, "deleteContent": True, "confirm": True})


# ──────────────────────────────────────────────────────────────────────────────
# CONTRACT / NEGATIVE
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="delete_project", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires and steers to list_projects."""
    r = call("delete_project", {})
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName names the param + steers to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_project", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A project that does not exist -> 'Project not found: <name>' with a list_projects hint,
    even with confirm=true (the existence guard precedes the deletion)."""
    bad = "NoSuchProject_dp_zzz"
    r = call("delete_project", {"projectName": bad, "deleteContent": True, "confirm": True})
    e = assert_error(r, "nonexistent project")
    assert_error_quality(e, names=[bad], suggests=["list_projects"], ctx="nonexistent project")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_project", kind="action")
def test_preview_does_not_remove_the_fixture_project():
    """A bare (no-confirm) call on the REAL fixture project must PREVIEW, not delete it — the
    safety property. The fixture must still be present afterwards."""
    r = call("delete_project", {"projectName": PROJECT})
    assert_ok(r, "delete_project preview on the fixture project")
    s = r.structured
    assert s is not None and s.get("action") == "preview", \
        "a no-confirm call must return action='preview', got %r" % (s,)
    assert s.get("confirmationRequired") is True, "preview must set confirmationRequired=true"
    # CRITICAL: the previewed project must still exist (the preview must not delete it).
    lp = call("list_projects", {})
    assert_contains(lp.text, PROJECT, "a preview must NOT remove the project")
    assert_no_diff("a preview must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# ROUND-TRIP: export -> import -> verify -> delete -> verify gone
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="delete_project", kind="action")
def test_export_import_roundtrip_then_delete_project():
    """Full export/import/delete round-trip. Dumps the fixture config to XML, imports it as a
    NEW project, verifies the project deployed, then removes it via delete_project (preview,
    then confirm) and verifies it is gone. A no-op import (project never appears) or a no-op
    delete (project still present) FAILS the test. Self-cleans in finally for repeatability."""
    _ensure_absent(NEW_PROJECT)
    shutil.rmtree(EXPORT_DIR, ignore_errors=True)
    try:
        # 1) Export the fixture configuration to a temp XML directory.
        ex = call("export_configuration_to_xml", {"projectName": PROJECT, "outputPath": EXPORT_DIR})
        assert_ok(ex, "export the fixture configuration to XML")
        assert os.path.isdir(EXPORT_DIR) and os.listdir(EXPORT_DIR), \
            "export must produce XML files in the output directory"

        # 2) Import those XML files into a NEW project (the real import happy path).
        im = call("import_configuration_from_xml",
                  {"importPath": EXPORT_DIR, "projectName": NEW_PROJECT})
        assert_ok(im, "import the dumped XML into a new project")

        # 3) Verify the new project deployed: it appears in list_projects (wait for indexing).
        wait_for_project_ready()
        lp = call("list_projects", {})
        assert_contains(lp.text, NEW_PROJECT,
                        "the imported project must appear in list_projects (import really created it)")

        # 4) delete_project: a preview must NOT remove it, then confirm removes it.
        prev = call("delete_project", {"projectName": NEW_PROJECT, "deleteContent": True})
        assert_ok(prev, "delete_project preview")
        assert prev.structured and prev.structured.get("action") == "preview", \
            "no-confirm delete must be a preview"
        lp_after_prev = call("list_projects", {})
        assert_contains(lp_after_prev.text, NEW_PROJECT, "a preview must not delete the project")

        dele = call("delete_project",
                    {"projectName": NEW_PROJECT, "deleteContent": True, "confirm": True})
        assert_ok(dele, "delete the imported project (confirm)")
        assert dele.structured and dele.structured.get("action") == "deleted", \
            "confirm delete must report action='deleted'"

        # 5) Verify it is gone.
        wait_for_project_ready()
        lp_gone = call("list_projects", {})
        assert_not_contains(lp_gone.text, NEW_PROJECT,
                            "the imported project must be GONE from list_projects after delete")
    finally:
        # Belt-and-braces: a mid-test failure must still leave no project / temp residue.
        _ensure_absent(NEW_PROJECT)
        shutil.rmtree(EXPORT_DIR, ignore_errors=True)

    assert_no_diff("the round-trip must not touch the committed fixture (TestConfiguration)")

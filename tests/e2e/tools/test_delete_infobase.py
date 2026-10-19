"""
e2e tests for delete_infobase (kind: action).

WHAT THE TOOL DOES
------------------
delete_infobase dissociates a FILE infobase from a configuration project and,
optionally, deregisters it from the global EDT Infobases list. It is the inverse
of create_infobase and the cleanup half of the create/delete round-trip.

Destructive, guarded by a confirm-preview: a bare call (confirm=false) reports what
would be removed WITHOUT changing anything; only confirm=true performs the removal.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  preview:  {"success": true, "action": "preview", "confirmationRequired": true,
             "project", "applicationId", "infobaseName", "deleteRegistration",
             ["databaseFilesKept"], "message"}
  deleted:  {"success": true, "action": "deleted", "project", "applicationId",
             "infobaseName", "deleteRegistration", "databaseFilesDeleted",
             ["databaseFilesKept"], "message"}
  error:    {"success": false, "error": "..."}

databaseFilesDeleted=false has six causes, and `databaseFilesKept` DECLARES which:
notRequested | noDirectory | shared | unknown | deleteFailed | deregistrationFailed.
It is absent exactly when the files were (or, on a preview, would be) deleted.
`shared` is a MEASURED co-owner; `unknown` is a shared-infobase check that did not
complete - it establishes nothing about sharing and is re-run by the next call.

CI STRATEGY
-----------
The negative matrix and confirm-gate tests are CI-safe (no platform needed). The
live happy-path (full create+delete round-trip) is covered by test_create_infobase.py
and gated behind EDT_MCP_LIVE_INFOBASE=1. The directory-warning probes below
use the same gate because association can start a Designer session on their fake marker.

NOTE: delete_infobase never writes TestConfiguration source files. All calls
leave the project tree clean: assert_no_diff() on all paths.
"""

import glob
import os
from pathlib import Path
import re
import shutil
import tempfile

from harness import (
    call,
    calls_aborted,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    e2e_test,
    requires_live_infobase,
    E2ESkip,
    PROJECT,
    cleanup_cluster_registration_probe,
    _workspace_dir,
)

NONEXISTENT_PROJECT = "NoSuchProject_di_zzz"
NONEXISTENT_APP_ID = "no_such_app_di_zzz"
NONEXISTENT_IB_NAME = "no_such_infobase_di_zzz"

# Every value the declared databaseFilesKept enum may take, and the subset a PREVIEW may
# reach: deleteFailed/deregistrationFailed describe steps a preview never runs.
KEPT_VALUES = ("notRequested", "noDirectory", "shared", "unknown",
               "deleteFailed", "deregistrationFailed")
KEPT_PREVIEW_VALUES = ("notRequested", "noDirectory", "shared", "unknown")


@e2e_test(tool="delete_infobase", kind="action")
def test_cluster_launcher_cleanup_does_not_require_a_visible_application():
    probe = "McpRegistrationProbe_" + os.urandom(8).hex()
    name = "McpServerRegistration_" + probe
    params = {"projectName": PROJECT, "mode": "register", "infobaseServer": "localhost:1541",
              "infobaseRef": probe, "infobaseName": name}
    registration_id = None
    try:
        created = call("create_infobase", params)
        registration_id = (created.structured or {}).get("registrationId")
        assert_ok(created, "register the cleanup probe")
        original_id = created.structured["applicationId"]
        detached = call("delete_infobase", {"projectName": PROJECT, "applicationId": original_id,
                        "deleteRegistration": False, "confirm": True})
        assert_ok(detached, "detach while retaining the launcher entry")
        apps = call("get_applications", {"projectName": PROJECT})
        assert_ok(apps, "probe must no longer be visible")
        assert not any(app.get("name") == name for app in apps.structured["applications"])
        name_only = call("delete_infobase", {"projectName": PROJECT, "infobaseName": name, "confirm": True})
        error = assert_error(name_only, "name alone must never delete a global launcher entry")
        assert_error_quality(error, names=[name], suggests=["get_applications"])
        cleanup = {"projectName": PROJECT, "infobaseName": name, "registrationId": registration_id,
                   "deleteRegistration": True,
                   "deleteDatabaseFiles": False}
        for overrides, hints in (
                ({"applicationId": original_id}, ["get_applications"]),
                ({"applicationId": "  "}, ["get_applications"]),
                ({"deleteRegistration": False}, ["deleteRegistration=true", "get_applications"]),
                ({"deleteDatabaseFiles": True}, ["deleteDatabaseFiles=false", "get_applications"]),
                ({"registrationId": "not-a-uuid"}, ["full UUID", "create_infobase"]),
                ({"registrationId": "00000000-0000-0000-0000-000000000000"},
                 ["create_infobase", "launcher"]),
                ({"infobaseName": name + "_wrong"}, ["create_infobase", "launcher"])):
            refused = call("delete_infobase", dict(cleanup, confirm=True, **overrides))
            error = assert_error(refused, "orphan deletion must keep its narrow flag guards")
            assert_error_quality(error, names=["registrationId"], suggests=hints)
            duplicate = call("create_infobase", params)
            error = assert_error(duplicate, "a refused orphan deletion must retain the launcher record")
            assert_error_quality(error, names=["address"], suggests=["get_applications"])
        preview = call("delete_infobase", cleanup)
        assert_ok(preview, "preview an invisible launcher entry")
        assert preview.structured["action"] == "preview", preview.structured
        assert preview.structured["confirmationRequired"] is True, preview.structured
        assert preview.structured["registrationId"] == registration_id, preview.structured
        assert "applicationId" not in preview.structured, preview.structured
        duplicate = call("create_infobase", params)
        error = assert_error(duplicate, "preview must retain the launcher record")
        assert_error_quality(error, names=["address"], suggests=["get_applications"])
        removed = call("delete_infobase", dict(cleanup, confirm=True))
        assert_ok(removed, "remove the invisible launcher entry")
        assert removed.structured["action"] == "deleted", removed.structured
        assert removed.structured["databaseFilesDeleted"] is False, removed.structured
        recreated = call("create_infobase", params)
        previous_id = registration_id
        registration_id = (recreated.structured or {}).get("registrationId")
        assert_ok(recreated, "registration can succeed only if the previous launcher record was removed")
        assert recreated.structured["boundToProject"] is True, recreated.structured
        assert recreated.structured["applicationId"] != original_id, recreated.structured
        assert registration_id and registration_id != previous_id, recreated.structured
        stale = call("delete_infobase", dict(cleanup, confirm=True))
        error = assert_error(stale, "a stale confirm must not delete a same-named replacement")
        assert_error_quality(error, names=["registrationId"], suggests=["create_infobase", "launcher"])
        duplicate = call("create_infobase", params)
        error = assert_error(duplicate, "stale confirmation must retain the replacement")
        assert_error_quality(error, names=["address"], suggests=["get_applications"])
    finally:
        try:
            if registration_id:
                removed = call("delete_infobase", {"projectName": PROJECT, "infobaseName": name,
                               "registrationId": registration_id,
                               "deleteRegistration": True, "deleteDatabaseFiles": False, "confirm": True})
                assert_ok(removed, "remove the exact UUID of the cleanup probe")
        finally:
            assert cleanup_cluster_registration_probe(name, "localhost:1541", probe, registration_id) == 0
    assert_no_diff()


# ──────────────────────────────────────────────────────────────────────────────
# CONTRACT / NEGATIVE
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_infobase", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("delete_infobase", {"applicationId": "someApp"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName: must name param and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_missing_both_id_and_name_errors_clearly():
    """Neither applicationId nor infobaseName provided -> the tool's own guard fires
    with an actionable message naming both parameters."""
    r = call("delete_infobase", {"projectName": PROJECT})
    err = assert_error(r, "missing applicationId and infobaseName")
    assert_error_quality(err, names=["applicationId", "infobaseName"],
                         ctx="must name both parameters that can satisfy the requirement")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project -> 'Project not found' with a list_projects hint."""
    r = call("delete_infobase",
             {"projectName": NONEXISTENT_PROJECT, "applicationId": NONEXISTENT_APP_ID,
              "confirm": True})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_nonexistent_application_id_errors_with_hint():
    """A real project but a non-existent applicationId -> 'Application not found' with
    a get_applications hint. Exercises the application-resolution chain."""
    r = call("delete_infobase",
             {"projectName": PROJECT, "applicationId": NONEXISTENT_APP_ID, "confirm": True})
    err = assert_error(r, "nonexistent applicationId")
    assert_error_quality(err, names=[NONEXISTENT_APP_ID], suggests=["get_applications"],
                         ctx="nonexistent applicationId is named with get_applications hint")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_nonexistent_infobase_name_errors_with_hint():
    """A real project but a non-existent infobaseName -> actionable not-found error
    suggesting get_applications."""
    r = call("delete_infobase",
             {"projectName": PROJECT, "infobaseName": NONEXISTENT_IB_NAME, "confirm": True})
    err = assert_error(r, "nonexistent infobaseName")
    assert_error_quality(err, names=[NONEXISTENT_IB_NAME], suggests=["get_applications"],
                         ctx="nonexistent infobaseName is named with get_applications hint")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_preview_on_nonexistent_is_not_ok():
    """Even without confirm=true, a non-existent application must error (not preview).
    The existence check precedes the confirm-preview gate to give a trustworthy preview."""
    r = call("delete_infobase",
             {"projectName": PROJECT, "applicationId": NONEXISTENT_APP_ID})
    # The existence check must reject this, not preview it.
    assert_error(r, "preview on nonexistent app must error, not preview")
    assert_no_diff("a rejected call must not touch the fixture")


def _first_infobase_application():
    """An application of the fixture project to preview a deletion of, or skip.

    A preview changes nothing, so it is the one way to assert the real serialized
    payload of this destructive tool without deleting anything.

    A FILE infobase is preferred, but a standalone-server application is accepted:
    both kinds write `databaseFilesKept` through the same helper. Verified live on a
    standalone application - files not requested -> 'notRequested', files requested ->
    the key absent because they would be deleted.

    STAND-ONLY COVERAGE, measured not assumed: on CI the committed fixture has NO
    applications at all (an application is workspace state, not fixture content), so
    this self-skips there - confirmed in the CI results XML. It asserts only on a stand
    whose workspace has one. Requiring a FILE infobase made it skip in BOTH places, which
    is why the accepted kinds were widened; a skip is not a pass either way.
    """
    r = call("get_applications", {"projectName": PROJECT})
    assert_ok(r, "get_applications for the delete_infobase preview")
    apps = (r.structured or {}).get("applications") or []
    for app in apps:
        if app.get("type") == "com.e1c.g5.dt.applications.type.infobase" and app.get("id"):
            return app
    for app in apps:
        if app.get("id"):
            return app
    raise E2ESkip("the fixture project has no application to preview a deletion of")


@e2e_test(tool="delete_infobase", kind="action")
def test_preview_declares_why_the_database_files_would_be_kept():
    """`databaseFilesKept` is a DECLARED enum, not prose: `databaseFilesDeleted=false` has
    six causes and only this field tells a programmatic client which. The preview is where
    the agent decides whether to call confirm=true, so the value must be present, be one of
    the declared six, and belong to the four a preview can reach — a preview runs neither
    the deletion nor the deregistration.

    Also pins the presence invariant in both directions: the key is absent EXACTLY when the
    files would be deleted, and a preview never claims an outcome (`databaseFilesDeleted`)."""
    app = _first_infobase_application()

    # Default: deleteDatabaseFiles is not asked for, so the reason is measured, not guessed.
    r = call("delete_infobase", {"projectName": PROJECT, "applicationId": app["id"]})
    assert_ok(r, "delete_infobase preview (files not requested)")
    sc = r.structured
    assert sc.get("action") == "preview", "no-confirm delete must preview: %r" % sc
    assert sc.get("confirmationRequired") is True, "preview must set confirmationRequired"
    assert "databaseFilesDeleted" not in sc, \
        "a preview changes nothing and must not claim an outcome: %r" % sc
    if sc.get("databaseFilesKept") != "notRequested":
        raise AssertionError(
            "deleteDatabaseFiles omitted must declare databaseFilesKept='notRequested': %r"
            % sc.get("databaseFilesKept"))

    # Asked for: the answer is now whatever the shared-infobase check established. It may
    # legitimately be absent (the files WOULD go), but never a deletion-only value.
    r2 = call("delete_infobase", {"projectName": PROJECT, "applicationId": app["id"],
                                  "deleteDatabaseFiles": True})
    assert_ok(r2, "delete_infobase preview (files requested)")
    sc2 = r2.structured
    assert sc2.get("action") == "preview", "no-confirm delete must preview: %r" % sc2
    kept = sc2.get("databaseFilesKept")
    if kept is not None and kept not in KEPT_PREVIEW_VALUES:
        raise AssertionError(
            "a preview may only report a reason it can actually establish (%r), not %r"
            % (list(KEPT_PREVIEW_VALUES), kept))
    if kept is not None and kept not in KEPT_VALUES:
        raise AssertionError("undeclared databaseFilesKept value: %r" % kept)
    # 'unknown' is an unconcluded check, never a co-owner: the two must stay distinct.
    if kept == "unknown":
        message = sc2.get("message") or ""
        assert "still used by other projects" not in message, \
            "an unconcluded check must not claim a co-owner was found: %r" % message
        assert "UNKNOWN" in message, \
            "an unconcluded check must say so in the message too: %r" % message
    if kept == "shared":
        assert "still used by other projects" in (sc2.get("message") or ""), \
            "a MEASURED co-owner must be named in the message: %r" % sc2.get("message")

    assert_no_diff("a preview must not touch the fixture")


def _assert_directory_warning(workspace, directory, message_fragment, with_detail=False):
    """The call logs synchronously; inspect current and rotated logs once, without polling."""
    metadata = Path(workspace) / ".metadata"
    paths = [metadata / ".log"] + [Path(path) for path in glob.glob(str(metadata / ".bak_*.log"))]
    entries = []
    for path in paths:
        if path.is_file():
            for entry in re.split(r"(?=^!ENTRY )", path.read_text(encoding="utf-8", errors="replace"), flags=re.MULTILINE):
                if ("!MESSAGE delete_infobase:" in entry and directory.name in entry
                        and message_fragment in entry):
                    entries.append(entry)
    assert entries, "the server must log the directory outcome in this workspace: %s" % directory
    for entry in entries:
        assert entry.startswith("!ENTRY com.ditrix.edt.mcp.server 2 "), entry
        assert "!STACK" not in entry, "a non-fatal directory outcome must carry no stack: %s" % entry
        if with_detail:
            message = entry.split("!MESSAGE ", 1)[1].splitlines()[0]
            prefix = "delete it manually): "
            assert prefix in message, "the warning must include failure detail: %s" % message
            detail = message.split(prefix, 1)[1]
            assert re.match(r"[A-Za-z_$][A-Za-z0-9_$]*Exception: \S", detail), \
                "the warning must retain the exception type and message: %s" % message


def _kept_directory_case(locked):
    workspace = _workspace_dir()
    if workspace is None:
        raise E2ESkip("set EDT_MCP_EDT_WORKSPACE so the directory-warning test can read EDT's log")
    directory = Path(tempfile.mkdtemp(prefix="edtmcp735_"))
    marker = directory / "1Cv8.1CD"
    sentinel = directory / "keep.txt"
    contents = b"test-owned fake database marker; attended live probe only"
    marker.write_bytes(contents)
    sentinel.write_bytes(b"keep this unrelated file")
    name = "McpDeleteWarning_" + directory.name
    registration_id = None
    deletion_finished = False
    locked_file = None
    try:
        created = call("create_infobase", {"projectName": PROJECT, "mode": "register",
                       "infobaseFile": str(directory), "infobaseName": name, "setDefault": False})
        registration_id = (created.structured or {}).get("registrationId")
        # Read the disk before another MCP round trip can mask an early return or unexpected write.
        assert marker.read_bytes() == contents
        assert_ok(created, "register only this test's file infobase")
        assert created.structured["boundToProject"] is True, created.structured
        application_id = created.structured["applicationId"]
        assert registration_id, created.structured

        if locked:
            # Windows file handles deny delete sharing. Unix permits unlinking an open file, so
            # the public test gates this case; the Java regression injects both failures on any OS.
            locked_file = marker.open("rb")
        else:
            marker.unlink()
        result = call("delete_infobase", {"projectName": PROJECT, "applicationId": application_id,
                      "deleteRegistration": True, "deleteDatabaseFiles": True, "confirm": True})
        deletion_finished = (not result.is_error
                             and (result.structured or {}).get("action") == "deleted")
        if locked:
            assert marker.read_bytes() == contents, "the locked database must remain on disk"
        else:
            assert sentinel.read_bytes() == b"keep this unrelated file"
            assert not marker.exists(), "refusal must not recreate the missing database marker"
        assert_ok(result, "a kept directory must not fail the completed deregistration")
        data = result.structured
        assert data["action"] == "deleted", data
        assert data["deleteRegistration"] is True, data
        assert data["databaseFilesDeleted"] is False, data
        assert data["databaseFilesKept"] == "deleteFailed", data
        assert "were NOT deleted" in data["message"], data
        assert "check/remove the directory manually." in data["message"], data
        _assert_directory_warning(workspace, directory,
                                  "could not delete the database directory" if locked
                                  else "is not a 1C infobase directory", with_detail=locked)
        applications = call("get_applications", {"projectName": PROJECT})
        assert_ok(applications, "the application is removed even when its directory is kept")
        assert not any(app.get("id") == application_id
                       for app in applications.structured["applications"]), applications.structured
    finally:
        if locked_file is not None:
            locked_file.close()
        # A timed-out tool may still be deleting. Leave its files alone until the harness can stop.
        if not calls_aborted():
            applications = call("get_applications", {"projectName": PROJECT})
            assert_ok(applications, "locate only this test's application for cleanup")
            remaining = [app for app in applications.structured["applications"] if app.get("name") == name]
            for app in remaining:
                cleanup = call("delete_infobase", {"projectName": PROJECT, "applicationId": app["id"],
                               "deleteRegistration": True, "deleteDatabaseFiles": False, "confirm": True})
                assert_ok(cleanup, "dissociate and deregister only this test's application")
            if not remaining and registration_id and not deletion_finished:
                cleanup = call("delete_infobase", {"projectName": PROJECT, "infobaseName": name,
                               "registrationId": registration_id, "deleteRegistration": True,
                               "deleteDatabaseFiles": False, "confirm": True})
                assert_ok(cleanup, "remove only the exact test-owned launcher registration")
            if not calls_aborted():
                shutil.rmtree(directory)
    assert_no_diff("the warning round-trip must leave the committed source fixture unchanged")


@e2e_test(tool="delete_infobase", kind="action")
def test_directory_without_database_marker_is_kept_with_a_warning():
    requires_live_infobase("delete_infobase missing-marker warning round-trip")
    _kept_directory_case(locked=False)


@e2e_test(tool="delete_infobase", kind="action")
def test_locked_database_directory_is_kept_with_a_warning_without_stack():
    requires_live_infobase("delete_infobase locked-directory warning round-trip")
    if os.name != "nt":
        raise E2ESkip("Windows delete-sharing semantics are required for the real locked-file probe")
    _kept_directory_case(locked=True)

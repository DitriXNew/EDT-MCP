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
and gated behind EDT_MCP_LIVE_INFOBASE=1.

NOTE: delete_infobase never writes TestConfiguration source files. All calls
leave the project tree clean: assert_no_diff() on all paths.
"""

import os

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    e2e_test,
    E2ESkip,
    PROJECT,
    cleanup_cluster_registration_probe,
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

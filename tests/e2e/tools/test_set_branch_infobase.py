"""
e2e tests for set_branch_infobase (kind: action).

WHAT THE TOOL DOES
------------------
set_branch_infobase attaches or detaches an EXISTING infobase (application) to/from
a git branch CONTEXT via IInfobaseAssociationManager, so list_git_branches' bindings
section and switch_git_branch's automatic binding follow that branch.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "action", "branch", "applicationId", "bound"?}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH ATTACH/DETACH
------------------------------------------------------------
!!! LOUD WARNING !!! Like list_git_branches/switch_git_branch, the CI fixture project
(PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's OWN git working tree
(git-dir-discovery resolves the PLUGIN repo). A real "happy path" attach/detach would
record an association against the PLUGIN's own workspace-metadata store (EDT instance
preferences), which is stateful and not reset by the git-fixture cleanup this suite
relies on. This file therefore contains ONLY negative-path tests that are guaranteed
to be rejected BEFORE any IInfobaseAssociationManager mutation runs. The real
happy-path attach/detach (a scratch repo + a real associated base) is a LIVE, ATTENDED
gate run by hand on a throwaway stand -- never automated here.

Every test below asserts assert_no_diff(): a rejected call must never touch the
fixture (it also never touches ANY association state, but that has no git-visible
signal to assert on, unlike the file-tree tools).

The two #656 tests at the end are the exception: LIVE-gated (EDT_MCP_LIVE_INFOBASE=1, an
attended stand), they bind the stand's existing infobase applications to a probe branch
context that is never checked out, and remove that probe context in `finally`. They need
infobase applications the project already lists (get_applications): two for the refusal test,
one for the sole-binding test. Seed them before the run, the way the #684 round trip's
precondition is seeded (bind them to the checked-out branch, refs/heads/<branch>);
create_infobase on a git project does not (it binds to the project-level context, #714). With
fewer, the tests are skipped with that reason.
"""

import os
import shutil

from harness import (
    call,
    E2ESkip,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    requires_live_infobase,
    e2e_test,
    PROJECT,
    _workspace_dir,
)

NONEXISTENT_PROJECT = "NoSuchProject_sbi_zzz"
NONEXISTENT_APPLICATION = "no_such_application_sbi_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any association mutation)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_branch_infobase", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("set_branch_infobase", {"branch": "main", "applicationId": "x"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_missing_branch_errors_clearly():
    """A real project but no branch -> the tool's own required-arg guard fires,
    naming the missing parameter."""
    r = call("set_branch_infobase", {"projectName": PROJECT, "applicationId": "x"})
    err = assert_error(r, "missing branch")
    assert_error_quality(err, names=["branch"], ctx="missing branch must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_missing_applicationid_errors_with_hint():
    """A real project and branch but no applicationId -> the required-arg guard
    fires, naming the parameter and steering to get_applications."""
    r = call("set_branch_infobase", {"projectName": PROJECT, "branch": "main"})
    err = assert_error(r, "missing applicationId")
    assert_error_quality(err, names=["applicationId"], suggests=["get_applications"],
                         ctx="missing applicationId must name it and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_invalid_action_errors_clearly():
    """An out-of-enum action value ('bogus') is rejected by the tool's own pure
    guard -- reachable even against a made-up project name, since it fires BEFORE
    any project/repository resolution."""
    r = call("set_branch_infobase", {
        "projectName": NONEXISTENT_PROJECT, "branch": "main",
        "applicationId": "x", "action": "bogus",
    })
    err = assert_error(r, "invalid action")
    assert_error_quality(err, names=["bogus", "attach", "detach"],
                         ctx="invalid action must name the bad value and the allowed ones")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("set_branch_infobase", {
        "projectName": NONEXISTENT_PROJECT, "branch": "main", "applicationId": "x",
    })
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_nonexistent_application_errors_with_hint():
    """A real project + branch but an unknown applicationId -> the application
    resolution fails, naming the bad id and steering to get_applications, BEFORE
    any IInfobaseAssociationManager call runs."""
    r = call("set_branch_infobase", {
        "projectName": PROJECT, "branch": "main", "applicationId": NONEXISTENT_APPLICATION,
    })
    err = assert_error(r, "nonexistent application")
    assert_error_quality(err, names=[NONEXISTENT_APPLICATION], suggests=["get_applications"],
                         ctx="nonexistent application must be named and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# #656: A DETACH FROM A BRANCH THAT IS NOT CHECKED OUT (LIVE-gated)
# ──────────────────────────────────────────────────────────────────────────────
#
# EDT removes a binding from a context other than the one it reads now by deleting that context's
# whole AssociationData.properties (probed live on the stand 2026-10-15: the sibling base was lost).
# So set_branch_infobase refuses such a detach unless the file holds only the binding being removed.

_GUARD_PROBE = "e2e-656-guard-probe"

_INFOBASE_TYPE = "com.e1c.g5.dt.applications.type.infobase"


def _current_branch():
    r = call("list_git_branches", {"projectName": PROJECT})
    assert_ok(r, "list_git_branches for the current branch")
    idx = r.text.index("**Current:**")
    return r.text[idx + len("**Current:**"):r.text.index("\n", idx)].strip()


def _binding_row(context_name):
    """The bindings-table row of a context, or None when it is not listed."""
    r = call("list_git_branches", {"projectName": PROJECT})
    assert_ok(r, "list_git_branches for the bindings of '%s'" % context_name)
    for line in r.text.splitlines():
        if line.startswith("| " + context_name + " |"):
            return line
    return None


def _applications():
    r = call("get_applications", {"projectName": PROJECT})
    assert_ok(r, "get_applications for the #656 detach probes")
    return r.structured or {}


def _infobase_applications(needed):
    """(id, name) of `needed` infobase applications. The tool is addressed by the id, while its
    refusal, its `bound` read-back and the list_git_branches bindings row name the infobase."""
    apps = _applications().get("applications", [])
    found = [(a["id"], a["name"]) for a in apps
             if a.get("type") == _INFOBASE_TYPE and a.get("id") and a.get("name")]
    if len(found) < needed:
        raise E2ESkip("needs %d infobase applications in get_applications, found %d - seed them first "
                      "(see the module docstring)" % (needed, len(found)))
    return found[:needed]


def _remove_probe_context():
    """Removes the probe branch's binding context from EDT's store, the way EDT's own wipe removes it.

    A probe that holds two bases cannot be detached through the tool from a branch that is not checked
    out (that is the point of the guard), and checking out a probe branch of the plugin's own repository
    is not an option. EDT re-reads the store on every call, so removing the context directory is seen at
    once. Only the probe context is touched."""
    workspace = _workspace_dir()
    assert workspace, "the stand workspace must be locatable (set EDT_MCP_EDT_WORKSPACE) to clean the probe"
    probe_dir = os.path.join(workspace, ".metadata", ".plugins", "org.eclipse.core.resources", ".projects",
                             PROJECT, "com._1c.g5.v8.dt.platform.services.core", "refs", "heads", _GUARD_PROBE)
    if os.path.isdir(probe_dir):
        shutil.rmtree(probe_dir)


@e2e_test(tool="set_branch_infobase", kind="action")
def test_live_detach_from_a_branch_not_checked_out_keeps_its_other_bindings():
    """#656: with two bases bound to a branch that is not checked out, a detach of one is REFUSED,
    naming the other and the remedy, and both bindings stay.

    Mutation check: without the guard (the master jar's detach) the call succeeds and EDT deletes the
    probe's whole binding file, so the other base is gone from the probe row."""
    requires_live_infobase("set_branch_infobase detach guard for a branch that is not checked out")
    current = _current_branch()
    assert current != _GUARD_PROBE, "the probe branch must not be the checked-out one: %r" % current
    (first_id, first_name), (second_id, second_name) = _infobase_applications(2)
    try:
        for app_id in (first_id, second_id):
            assert_ok(call("set_branch_infobase", {"projectName": PROJECT, "branch": _GUARD_PROBE,
                                                   "applicationId": app_id}),
                      "attach '%s' to the probe branch" % app_id)

        r = call("set_branch_infobase", {"projectName": PROJECT, "branch": _GUARD_PROBE,
                                         "applicationId": first_id, "action": "detach"})
        err = assert_error(r, "a detach that would delete another base's binding")
        # The sibling is named by its infobase NAME (the tool reads the binding file, which holds names).
        assert_error_quality(err, names=[_GUARD_PROBE, "infobase '%s'" % second_name, "not checked out"],
                             suggests=["switch_git_branch"],
                             ctx="the refusal names the branch, the base that would be lost and the remedy")
        assert "Nothing was changed." in err, err

        row = _binding_row(_GUARD_PROBE)
        assert row is not None and first_name in row and second_name in row, \
            "both bindings must stay on the probe branch: %r" % row
    finally:
        _remove_probe_context()

    assert _binding_row(_GUARD_PROBE) is None, "the probe context must be gone after cleanup"
    assert_no_diff("binding changes must not touch the committed fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_live_detach_of_the_only_binding_of_a_branch_not_checked_out_goes_through():
    """#656: a branch that is not checked out and holds ONLY this base can be detached (nothing else
    can be lost); the answer says so, and the checked-out branch's binding is untouched.

    Mutation check: a guard that refused every detach from a branch that is not checked out fails here,
    and a detach that dissociated in the wrong context would change the checked-out branch's row.

    The project's default application is compared too. It is not a binding, so the note does not cover
    it; the platform analysis recorded an unprobed path (the provisioning listener ignores the context)
    by which a non-current dissociation of a base also bound to the checked-out branch could clear it.
    This test takes such a base, so it is that probe: red here means the answer must report it."""
    requires_live_infobase("set_branch_infobase sole-binding detach from a branch that is not checked out")
    current = _current_branch()
    assert current != _GUARD_PROBE, "the probe branch must not be the checked-out one: %r" % current
    ((app_id, app_name),) = _infobase_applications(1)
    current_row_before = _binding_row(current)
    default_before = _applications().get("defaultApplication")
    try:
        assert_ok(call("set_branch_infobase", {"projectName": PROJECT, "branch": _GUARD_PROBE,
                                               "applicationId": app_id}),
                  "attach to the probe branch")

        r = call("set_branch_infobase", {"projectName": PROJECT, "branch": _GUARD_PROBE,
                                         "applicationId": app_id, "action": "detach"})
        assert_ok(r, "the only binding of a branch that is not checked out may be detached")
        message = (r.structured or {}).get("message") or ""
        assert "is not checked out" in message and "held no other binding" in message, message
        # `bound` names infobases, as the bindings row does; it is absent when nothing is left.
        bound = (r.structured or {}).get("bound") or {}
        assert app_name not in (bound.get("infobases") or []), "the base must be gone from the probe: %r" % bound
        probe_row = _binding_row(_GUARD_PROBE)
        assert probe_row is None or app_name not in probe_row, \
            "the base must be gone from the probe row: %r" % probe_row

        assert _binding_row(current) == current_row_before, \
            "the checked-out branch's binding must not change: %r -> %r" % (current_row_before,
                                                                             _binding_row(current))
        default_after = _applications().get("defaultApplication")
        assert default_after == default_before, \
            "the project's default application changed: %r -> %r" % (default_before, default_after)
    finally:
        _remove_probe_context()

    assert_no_diff("binding changes must not touch the committed fixture")

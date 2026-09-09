"""Real e2e coverage for the workspace-wide set_error_breakpoint tool."""

from harness import (
    E2ESkip,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)


def _exception_breakpoints(project_filter=None):
    params = {} if project_filter is None else {"projectName": project_filter}
    result = call("list_breakpoints", params)
    assert_ok(result, "list workspace-wide exception breakpoints")
    return [entry for entry in (result.structured or {}).get("breakpoints", [])
            if entry.get("kind") == "exception" and entry.get("workspaceWide") is True]


def _can_reconstruct(entry):
    """Whether _restore could put this entry back exactly as it is now.

    Catch-all needs nothing but the enabled flag; a FILTERED breakpoint needs its message, and an
    entry that came back with an error is not a description of anything.
    """
    if entry.get("error"):
        return False  # handled by the caller as a FAILURE, not a skip
    if not isinstance(entry.get("enabled"), bool):
        return False
    if entry.get("catchAllExceptions") is True:
        return True
    return isinstance(entry.get("exceptionMessage"), str) and entry["exceptionMessage"] != ""

def _restore(original):
    """Puts the workspace back and PROVES it, returning a problem description or None.

    A restoring call can fail like any other (the factory or a reflective setter going away is
    exactly the kind of breakage this suite exists to catch), and an unchecked cleanup would then
    delete the workspace's own exception breakpoint and still report a green test. The caller
    raises on the returned message once the body has finished, so a real failure in the body is
    still the one that surfaces.
    """
    _delete_exception_breakpoints()
    if not original:
        return None
    params = {"enabled": True}
    wanted_message = None
    if not original[0].get("catchAllExceptions") and "exceptionMessage" in original[0]:
        wanted_message = original[0]["exceptionMessage"]
        params["exceptionMessage"] = wanted_message
    recreated = call("set_error_breakpoint", params)
    if recreated.is_error or recreated.rpc_error:
        return "restoring the pre-existing exception breakpoint failed: %r" % (recreated.error_text(),)
    wanted_enabled = original[0].get("enabled") is not False
    if not wanted_enabled:
        disabled = call("set_error_breakpoint", {"enabled": False})
        if disabled.is_error or disabled.rpc_error:
            return "restoring the disabled state failed: %r" % (disabled.error_text(),)

    # Read it back: the calls answering OK is not the same observation as the workspace holding
    # what it held before.
    restored = _exception_breakpoints()
    if len(restored) != 1:
        return "restore left %d exception breakpoints, expected exactly 1" % len(restored)
    if restored[0].get("enabled") is not wanted_enabled:
        return "restored breakpoint has enabled=%r, expected %r" % (
            restored[0].get("enabled"), wanted_enabled)
    if restored[0].get("exceptionMessage") != wanted_message:
        return "restored breakpoint has exceptionMessage=%r, expected %r" % (
            restored[0].get("exceptionMessage"), wanted_message)
    return None


def _delete_exception_breakpoints():
    for entry in _exception_breakpoints():
        breakpoint_id = entry.get("breakpointId")
        if isinstance(breakpoint_id, int) and breakpoint_id > 0:
            result = call("remove_breakpoint", {"breakpointId": breakpoint_id})
            assert_ok(result, "delete workspace error-breakpoint test state")


@e2e_test(tool="set_error_breakpoint", kind="read")
def test_create_update_disable_and_reenable_workspace_error_breakpoint():
    """Exercise the real OSGi factory, manager registration, reflective setters,
    workspace-root listing, in-place update, and configuration-preserving disable."""
    original = _exception_breakpoints()
    broken = [entry for entry in original if entry.get("error")]
    if broken:
        # NOT a skip: the listing puts `error` there when a production marker/configuration read
        # threw, so skipping would turn a broken listing contract into a green run. Raised before
        # anything is deleted, so the operator state survives the failure.
        raise AssertionError(
            "list_breakpoints reported an unreadable exception breakpoint (%r); the listing "
            "contract is broken, so this test refuses to run against it" % (broken[0],)
        )
    unreconstructable = [entry for entry in original if not _can_reconstruct(entry)]
    if unreconstructable:
        # The saved state has to be READABLE before anything is deleted: an entry carrying an
        # error, or a filtered one whose message did not come back, cannot be put back the way it
        # was - and _restore would rebuild a plausible default that its own read-back then
        # accepts, losing the operator's filter for good.
        raise E2ESkip(
            "a pre-existing exception breakpoint cannot be reconstructed from what "
            "list_breakpoints reports (%r); refusing to delete it" % (unreconstructable[0],)
        )
    if len(original) > 1:
        # This test deletes the workspace's exception breakpoints and puts ONE back, because
        # set_error_breakpoint configures every registered one rather than creating a second.
        # More than one pre-existing entry therefore cannot be reconstructed, and destroying
        # someone's state to run a test is not a trade this suite makes.
        raise E2ESkip(
            "the workspace already has %d exception breakpoints; this test would not be able "
            "to restore them all afterwards" % len(original)
        )
    breakpoint_id = None
    try:
        # INSIDE the guard, not before it: this removal is already destructive, and an error or a
        # timeout raised out here would skip the finally entirely - leaving the operator's own
        # breakpoint deleted with no restoration installed.
        _delete_exception_breakpoints()
        created = call("set_error_breakpoint", {"enabled": True})
        assert_ok(created, "create catch-all workspace error breakpoint")
        created_state = created.structured or {}
        breakpoint_id = created_state.get("breakpointId")
        if not isinstance(breakpoint_id, int) or breakpoint_id <= 0:
            raise AssertionError("factory-created exception breakpoint needs a real marker id: %r"
                                 % created_state)
        if created_state.get("catchAllExceptions") is not True:
            raise AssertionError("omitted exceptionMessage must enable catch-all: %r" % created_state)
        if created_state.get("workspaceWide") is not True:
            raise AssertionError("result must label the setting workspace-wide: %r" % created_state)
        if created_state.get("breakpointIds") != [breakpoint_id]:
            raise AssertionError(
                "breakpointIds must list every configured marker, and here that is exactly the "
                "one just created: %r" % created_state
            )

        # A project filter cannot exclude a workspace-root exception breakpoint.
        listed = _exception_breakpoints("NoSuchProject_ZZZ_e2e")
        mine = [entry for entry in listed if entry.get("breakpointId") == breakpoint_id]
        if not mine:
            raise AssertionError("projectName filter excluded workspace-wide breakpoint id=%r: %r"
                                 % (breakpoint_id, listed))
        if mine[0].get("enabled") is not True or mine[0].get("catchAllExceptions") is not True:
            raise AssertionError("listed catch-all state is not the real configured state: %r" % mine[0])

        # Remove the catch-all instance so the message-filtered case proves creation,
        # rather than only an update of an existing breakpoint.
        removed = call("remove_breakpoint", {"breakpointId": breakpoint_id})
        assert_ok(removed, "remove catch-all workspace error breakpoint")
        if (removed.structured or {}).get("removed") is not True:
            raise AssertionError("catch-all breakpoint was not removed: %r"
                                 % (removed.structured or {}))

        filtered = call("set_error_breakpoint", {
            "enabled": True,
            "exceptionMessage": "Regression sentinel",
        })
        assert_ok(filtered, "create workspace error breakpoint with message filter")
        filtered_state = filtered.structured or {}
        if filtered_state.get("action") != "created":
            raise AssertionError("message filter must be tested on a new breakpoint: %r"
                                 % filtered_state)
        breakpoint_id = filtered_state.get("breakpointId")
        if not isinstance(breakpoint_id, int) or breakpoint_id <= 0:
            raise AssertionError("filtered exception breakpoint needs a real marker id: %r"
                                 % filtered_state)
        if filtered_state.get("catchAllExceptions") is not False \
                or filtered_state.get("exceptionMessage") != "Regression sentinel":
            raise AssertionError("create with a message did not retain the exact filter: %r"
                                 % filtered_state)

        listed = _exception_breakpoints()
        mine = [entry for entry in listed if entry.get("breakpointId") == breakpoint_id]
        if len(mine) != 1:
            raise AssertionError("filtered create must leave one workspace exception breakpoint: %r"
                                 % listed)
        if mine[0].get("catchAllExceptions") is not False:
            raise AssertionError("message filter must disable catch-all: %r" % mine[0])
        if mine[0].get("exceptionMessage") != "Regression sentinel":
            raise AssertionError("message filter did not round-trip through list_breakpoints: %r" % mine[0])

        disabled = call("set_error_breakpoint", {"enabled": False})
        assert_ok(disabled, "disable workspace error breakpoint")
        disabled_state = disabled.structured or {}
        if disabled_state.get("action") != "disabled" or disabled_state.get("disabledCount", 0) < 1:
            raise AssertionError("disable must report retained exception breakpoints: %r"
                                 % disabled_state)
        mine = [entry for entry in _exception_breakpoints()
                if entry.get("breakpointId") == breakpoint_id]
        if len(mine) != 1 or mine[0].get("enabled") is not False:
            raise AssertionError("disabled exception breakpoint must remain listed: %r" % mine)
        if mine[0].get("exceptionMessage") != "Regression sentinel":
            raise AssertionError("disable discarded the exception-message filter: %r" % mine[0])

        reenabled = call("set_error_breakpoint", {"enabled": True})
        assert_ok(reenabled, "re-enable workspace error breakpoint without repeating filter")
        reenabled_state = reenabled.structured or {}
        if reenabled_state.get("breakpointId") != breakpoint_id:
            raise AssertionError("re-enable must preserve marker id=%r: %r"
                                 % (breakpoint_id, reenabled_state))
        mine = [entry for entry in _exception_breakpoints()
                if entry.get("breakpointId") == breakpoint_id]
        if len(mine) != 1 or mine[0].get("enabled") is not True:
            raise AssertionError("re-enabled exception breakpoint is not active: %r" % mine)
        if mine[0].get("catchAllExceptions") is not False \
                or mine[0].get("exceptionMessage") != "Regression sentinel":
            raise AssertionError("re-enable without a message must keep the same filter: %r"
                                 % mine[0])

        cleared = call("set_error_breakpoint", {
            "enabled": True,
            "exceptionMessage": "",
        })
        assert_ok(cleared, "explicitly clear workspace error-breakpoint message filter")
        cleared_state = cleared.structured or {}
        if cleared_state.get("breakpointId") != breakpoint_id:
            raise AssertionError("clearing the filter must preserve marker id=%r: %r"
                                 % (breakpoint_id, cleared_state))
        if cleared_state.get("catchAllExceptions") is not True \
                or "exceptionMessage" in cleared_state:
            raise AssertionError("empty exceptionMessage must explicitly select catch-all: %r"
                                 % cleared_state)
        mine = [entry for entry in _exception_breakpoints()
                if entry.get("breakpointId") == breakpoint_id]
        if len(mine) != 1 or mine[0].get("catchAllExceptions") is not True:
            raise AssertionError("cleared filter did not round-trip as catch-all: %r" % mine)
        if "exceptionMessage" in mine[0]:
            raise AssertionError("cleared exception-message filter is still listed: %r" % mine[0])
    finally:
        # The restoration runs while an assertion may already be propagating, so a raise here
        # would REPLACE the failure the test exists to report. Its own trouble is worth saying,
        # but never at the price of the original.
        try:
            restore_problem = _restore(original)
        except Exception as restoration_failure:  # noqa: BLE001 - see above
            restore_problem = "restoring the saved breakpoint raised %r" % (restoration_failure,)
    if restore_problem:
        raise AssertionError(restore_problem)
    assert_no_diff("workspace-wide error-breakpoint changes must not modify project source")


@e2e_test(tool="set_error_breakpoint", kind="read")
def test_required_and_boolean_refusals_are_actionable():
    missing = call("set_error_breakpoint", {})
    error = assert_error(missing, "missing enabled")
    assert_error_quality(error, names=["enabled"], suggests=[],
                         ctx="required enabled parameter is named")

    invalid = call("set_error_breakpoint", {"enabled": "sometimes"})
    error = assert_error(invalid, "non-boolean enabled")
    assert_error_quality(error, names=["enabled", "sometimes"],
                         suggests=["true", "false"],
                         ctx="invalid boolean names the value and exact fix")
    assert_no_diff("set_error_breakpoint refusals must not modify project source")

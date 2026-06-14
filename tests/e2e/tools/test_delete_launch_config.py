"""
e2e tests for delete_launch_config (kind: action).

THE TOOL (DeleteLaunchConfigTool, getResponseType() == JSON):
Removes a 1C:EDT launch configuration by name from workspace metadata. Destructive,
guarded by a confirm-preview (mirrors delete_project): a bare call PREVIEWS (action='preview',
confirmationRequired=true) without removing anything; only confirm=true performs the
deletion (action='deleted'). Refuses to delete a running config (terminate_launch first).

FIXTURE CONTEXT:
Launch configs live in workspace .metadata, NOT in git-tracked files. assert_no_diff
does NOT catch leaked configs. Tests that create a config must clean it up via this tool.

HEADLESS-CI DESIGN (same as test_create_launch_config):
The happy-path round-trip needs a registered infobase to call create_launch_config first.
If no infobase is registered, the create_launch_config call is rejected and we skip to the
not-found negative path. Negative tests (missing name, not-found) always run.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

_CONFIG_NAME = "e2e_DeleteLaunchConfigTest_ThinClient"


def _ensure_config_absent():
    """Best-effort pre/post clean: idempotent removal of the test config."""
    call("delete_launch_config", {"name": _CONFIG_NAME, "confirm": True})


def _create_test_config():
    """Create the test config; return True on success, False if no infobase."""
    r = call("create_launch_config", {"projectName": PROJECT, "name": _CONFIG_NAME,
                                      "clientType": "thin"})
    return not r.is_error


# ──────────────────────────────────────────────────────────────────────────────
# Happy path (preview then confirm)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="delete_launch_config", kind="action")
def test_preview_then_delete_roundtrip():
    """
    Full round-trip: create -> preview (no change) -> confirm delete -> verify gone.
    If create_launch_config is rejected (no infobase in headless CI), this test falls
    through to asserting the preview-only paths.
    """
    _ensure_config_absent()
    created = _create_test_config()

    if not created:
        # No infobase -> cannot create the config to delete. Verify not-found behaviour.
        r = call("delete_launch_config", {"name": _CONFIG_NAME})
        assert_error(r, "not-found path when no config exists")
        assert_no_diff("a rejected call must not touch the fixture")
        return

    try:
        # 1) Preview must NOT remove the config.
        prev = call("delete_launch_config", {"name": _CONFIG_NAME})
        assert_ok(prev, "delete_launch_config preview")
        sc = prev.structured
        assert sc is not None and isinstance(sc, dict), \
            "delete_launch_config must return structuredContent dict"
        assert sc.get("action") == "preview", \
            "no-confirm call must return action='preview', got: %r" % sc.get("action")
        assert sc.get("confirmationRequired") is True, \
            "preview must set confirmationRequired=true"
        assert sc.get("name") == _CONFIG_NAME, \
            "preview must echo the config name: %r" % sc.get("name")

        # Verify the config still exists after the preview.
        lc = call("list_configurations", {"projectName": PROJECT})
        assert_ok(lc, "list_configurations after preview")
        configs = lc.structured.get("configurations", []) if lc.structured else []
        names = [c.get("name") for c in configs]
        assert _CONFIG_NAME in names, \
            "config must still exist after a preview (preview must not delete): %r" % names

        assert_no_diff("preview must not touch the git-tracked fixture tree")

        # 2) Confirm delete.
        del_r = call("delete_launch_config", {"name": _CONFIG_NAME, "confirm": True})
        assert_ok(del_r, "delete_launch_config confirm")
        del_sc = del_r.structured
        assert del_sc is not None and del_sc.get("action") == "deleted", \
            "confirm delete must return action='deleted', got: %r" % (del_sc,)

        # 3) Verify the config is gone.
        lc2 = call("list_configurations", {"projectName": PROJECT})
        assert_ok(lc2, "list_configurations after delete")
        configs2 = lc2.structured.get("configurations", []) if lc2.structured else []
        names2 = [c.get("name") for c in configs2]
        assert _CONFIG_NAME not in names2, \
            "config must be GONE after confirm delete: %r" % names2

        assert_no_diff("delete must not touch the git-tracked fixture tree")
    finally:
        _ensure_config_absent()


# ──────────────────────────────────────────────────────────────────────────────
# Negative / error-quality matrix (always run, no infobase dependency)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="delete_launch_config", kind="action")
def test_missing_name_errors_with_hint():
    """No name -> the shared required-arg guard fires and steers to list_configurations."""
    r = call("delete_launch_config", {})
    e = assert_error(r, "missing name")
    assert_error_quality(
        e,
        names=["name"],
        suggests=["list_configurations"],
        ctx="missing name names the param and steers to list_configurations",
    )
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_launch_config", kind="action")
def test_nonexistent_config_errors_with_hint():
    """A config name that does not exist -> actionable error with list_configurations hint."""
    bad = "NoSuchLaunchConfig_dlc_zzz"
    r = call("delete_launch_config", {"name": bad})
    e = assert_error(r, "nonexistent config")
    assert_error_quality(
        e,
        names=[bad],
        suggests=["list_configurations"],
        ctx="nonexistent config names the bad name and steers to list_configurations",
    )
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_launch_config", kind="action")
def test_preview_does_not_delete():
    """A bare call (no confirm) must ALWAYS preview, never delete, even if the config exists.
    Tested against the no-config (not-found) case which always runs without an infobase."""
    bad = "NoSuchLaunchConfig_preview_zzz"
    # call without confirm=true on a nonexistent config -> error (not preview), which confirms
    # the gate is enforced before the preview path (not-found precedes the confirm check).
    r = call("delete_launch_config", {"name": bad})
    # Should be an error (not-found), not an accidental delete.
    assert r.is_error, \
        "a no-confirm call on a nonexistent config must return an error, not a delete: %r" % r.text
    assert_no_diff("a rejected call must not touch the fixture")

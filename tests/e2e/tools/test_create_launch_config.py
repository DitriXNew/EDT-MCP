"""
e2e tests for create_launch_config (kind: action).

THE TOOL (CreateLaunchConfigTool, getResponseType() == JSON):
Creates a 1C:EDT runtime-client launch configuration (thin/thick/web) and persists it in
workspace metadata. Returns the created config's name, project, clientType, applicationId,
and type id. The SAME config works for run and debug (mode is chosen at launch time).

FIXTURE CONTEXT (read before editing):
Launch configs live in workspace .metadata, NOT in the git-tracked TestConfiguration/ tree.
assert_no_diff does NOT catch leaked configs. The cleanup counterpart delete_launch_config
(also in this PR) is therefore used in a finally block to remove any config created by a
happy-path test.

HEADLESS-CI DESIGN DECISION:
create_launch_config requires a real applicationId. The fixture project (TestConfiguration)
may have no infobase registered in a fresh headless CI run (launch configs live in .metadata
and infobases are registered via Window -> Preferences, not tracked in git). This file
handles both environments:

1. If the project has a default application (a live infobase is registered): run the full
   happy path — create -> list_configurations verifies the real applicationId -> delete.
2. If the project has NO application: assert the actionable "no applications" error path
   (this is the validation gate that prevents saving a useless config). The test is honest
   about what it checks in each environment and does NOT fake a green result.

A full create -> launch -> verify round-trip is Tier-2 (live stand with EDT_MCP_LIVE_INFOBASE=1).
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

# Config name unlikely to collide with existing configs in the workspace.
_CONFIG_NAME = "e2e_CreateLaunchConfigTest_ThinClient"


def _ensure_config_absent():
    """Best-effort pre/post clean: remove a leftover config from a prior crashed run."""
    call("delete_launch_config", {"name": _CONFIG_NAME, "confirm": True})


def _get_default_app_id():
    """Return the fixture project's default application id, or None if none."""
    r = call("get_applications", {"projectName": PROJECT})
    if not r.structured or not isinstance(r.structured, dict):
        return None
    return r.structured.get("defaultApplicationId")


# ──────────────────────────────────────────────────────────────────────────────
# Happy path (environment-conditional: needs a registered infobase)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="create_launch_config", kind="action")
def test_create_thin_client_config_happy_path():
    """
    Full round-trip: create_launch_config -> list_configurations verifies the real
    applicationId (NOT the synthetic 'launch:<name>' fallback) -> delete_launch_config cleanup.

    If the fixture project has no registered infobase (headless CI without an EDT stand),
    we assert the 'no applications' error instead — see docstring above for the design rationale.
    This is HONEST: we do NOT fake a green result when the infobase precondition is unmet.
    """
    _ensure_config_absent()
    default_app_id = _get_default_app_id()

    if default_app_id is None:
        # Headless CI: no infobase registered -> assert the actionable error path.
        r = call("create_launch_config", {"projectName": PROJECT, "clientType": "thin",
                                          "name": _CONFIG_NAME})
        e = assert_error(r, "no-infobase path: tool must reject with actionable error")
        assert_error_quality(
            e,
            names=["application", PROJECT],
            suggests=["get_applications"],
            ctx="no infobase: error must name the project and steer to get_applications",
        )
        assert_no_diff("a rejected call must not touch the fixture")
        return

    # Live stand: there IS an application — run the full round-trip.
    try:
        # 1) Create the config with an explicit name so we can clean it up reliably.
        r = call("create_launch_config", {
            "projectName": PROJECT,
            "clientType": "thin",
            "name": _CONFIG_NAME,
        })
        assert_ok(r, "create_launch_config thin client")
        sc = r.structured
        assert sc is not None and isinstance(sc, dict), \
            "create_launch_config must return structuredContent dict"
        assert sc.get("action") == "created", \
            "success response must have action='created', got: %r" % sc.get("action")
        assert sc.get("name") == _CONFIG_NAME, \
            "returned name must match requested name: %r" % sc.get("name")
        assert sc.get("project") == PROJECT, \
            "returned project must match: %r" % sc.get("project")
        assert sc.get("clientType") == "thin", \
            "returned clientType must be 'thin': %r" % sc.get("clientType")
        created_app_id = sc.get("applicationId")
        assert created_app_id and not created_app_id.startswith("launch:"), \
            ("applicationId in create result must be a REAL id (not the synthetic 'launch:' " +
             "fallback): %r") % created_app_id

        # 2) list_configurations: verify the config is visible with the real applicationId.
        #    This is the CRITICAL assertion (point 3 from the brief): a config saved WITHOUT
        #    ATTR_APPLICATION_ID would show 'launch:<name>' here, which breaks debug_launch.
        lc = call("list_configurations", {"projectName": PROJECT, "type": "client"})
        assert_ok(lc, "list_configurations after create")
        configs = lc.structured.get("configurations", []) if lc.structured else []
        created_entry = next((c for c in configs if c.get("name") == _CONFIG_NAME), None)
        assert created_entry is not None, \
            ("The created config '%s' must appear in list_configurations" % _CONFIG_NAME)
        entry_app_id = created_entry.get("applicationId", "")
        assert entry_app_id == created_app_id, \
            ("list_configurations must show the REAL applicationId '%s', got '%s'. "
             "A synthetic 'launch:' id means ATTR_APPLICATION_ID was not persisted.") % (
                 created_app_id, entry_app_id)
        assert not entry_app_id.startswith("launch:"), \
            ("applicationId in list_configurations must not be synthetic: '%s'" % entry_app_id)

        assert_no_diff("create must not touch the git-tracked fixture tree")

    finally:
        # Always clean up — configs are in workspace .metadata and are not git-tracked.
        _ensure_config_absent()


# ──────────────────────────────────────────────────────────────────────────────
# Negative / error-quality matrix
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="create_launch_config", kind="action")
def test_missing_project_name_errors_with_hint():
    """No projectName -> the shared required-arg guard fires and steers to list_projects."""
    r = call("create_launch_config", {})
    e = assert_error(r, "missing projectName")
    assert_error_quality(
        e,
        names=["projectName"],
        suggests=["list_projects"],
        ctx="missing projectName names the param and steers to list_projects",
    )
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_launch_config", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A project that does not exist -> actionable 'Project not found' error."""
    bad = "NoSuchProject_clc_zzz"
    r = call("create_launch_config", {"projectName": bad})
    e = assert_error(r, "nonexistent project")
    assert_error_quality(
        e,
        names=[bad],
        suggests=["list_projects"],
        ctx="nonexistent project names the bad project and steers to list_projects",
    )
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_launch_config", kind="action")
def test_invalid_application_id_errors_with_hint():
    """An applicationId that does not exist for the project -> actionable error."""
    bogus_app = "bogus-app-id-e2e-zzz"
    r = call("create_launch_config", {
        "projectName": PROJECT,
        "clientType": "thin",
        "applicationId": bogus_app,
    })
    e = assert_error(r, "invalid applicationId")
    assert_error_quality(
        e,
        names=[bogus_app, PROJECT],
        suggests=["get_applications"],
        ctx="invalid applicationId names the bad id and steers to get_applications",
    )
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_launch_config", kind="action")
def test_duplicate_name_errors_with_hint():
    """
    If a config with the given name already exists, the call must be rejected with a
    list_configurations hint. Needs a registered infobase to create the first config;
    skips gracefully (asserts the no-application error) if none is registered.
    """
    _ensure_config_absent()
    default_app_id = _get_default_app_id()

    if default_app_id is None:
        # Cannot create the first config without an infobase; skip this path cleanly.
        r = call("create_launch_config", {"projectName": PROJECT, "name": _CONFIG_NAME})
        assert_error(r, "no-infobase skip: tool rejects as expected")
        assert_no_diff("a rejected call must not touch the fixture")
        return

    try:
        # Create once.
        r1 = call("create_launch_config", {"projectName": PROJECT, "name": _CONFIG_NAME})
        assert_ok(r1, "first create (for duplicate-name test)")

        # Try to create again with the same name -> must be rejected.
        r2 = call("create_launch_config", {"projectName": PROJECT, "name": _CONFIG_NAME})
        e = assert_error(r2, "duplicate config name must be rejected")
        assert_error_quality(
            e,
            names=[_CONFIG_NAME],
            suggests=["list_configurations"],
            ctx="duplicate name names the conflicting name and steers to list_configurations",
        )
        assert_no_diff("rejected duplicate must not touch the fixture")
    finally:
        _ensure_config_absent()

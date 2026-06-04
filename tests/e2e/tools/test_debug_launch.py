"""
e2e tests for debug_launch (kind: read).

What the tool does
------------------
debug_launch starts an EDT debug session. It has TWO mutually-reachable modes,
selected by which params are present (see DebugLaunchTool.execute):

  Mode 1 — launchConfigurationName: start any existing EDT debug launch
           configuration by its EXACT name (runtime client OR an
           "Attach to 1C:Enterprise Debug Server" config). projectName /
           applicationId are NOT required in this mode.
  Mode 2 — projectName + applicationId: legacy path that finds the matching
           runtime-client launch config for that project+application and starts it.

ENVIRONMENT (why these are sentinel/negative tests, not a real launch)
----------------------------------------------------------------------
This is a DEBUG/RUNTIME tool. In THIS EDT there is NO running infobase for
TestConfiguration, NO registered application, and NO pre-created EDT launch
configuration. Actually starting a debug session is heavy, spawns a 1C client,
and is not configured here — so we deliberately do NOT drive a real launch.

The realistic, CORRECT contract in this environment is that EVERY reachable call
fails FAST with a CLEAR, ACTIONABLE sentinel that names the missing precondition
and the next step (which sibling tool to call / what to create in EDT). That is
exactly what these tests assert — and each assertion is mutation-sensitive: a tool
that no-oped, returned a bogus success, or emitted a vague/blank error would fail.

Response shape (IMPORTANT)
--------------------------
DebugLaunchTool.getResponseType() == JSON, so the payload is a JSON envelope. On
error the envelope is {"success": false, "error": "<message>"} and the protocol
layer flags the Result isError; assert_error returns that error string (the harness
reads structuredContent.error first). For a JSON tool r.text is only a placeholder.

Gson note: ToolResult.toJson() HTML-/quote-escapes some chars, and several of these
messages embed a single-quoted value ('NoSuchConfig'). So every error-quality
assertion below matches DELIMITER-FREE substrings (the bad bareword, "not found",
"is required", "Use get_applications", "Create it in EDT") — never a raw "'...'"
or a ">=" that Gson would have rewritten to \\uXXXX.

DIFF: debug_launch operates on the EDT launch manager / a (would-be) running
infobase — NOT the git-tracked TestConfiguration/ source tree. So a non-destructive
guardrail applies to EVERY test: assert_no_diff() (the project source must never
change as a side effect of trying to launch a debugger).

Negative matrix coverage (all reachable branches in execute())
---------------------------------------------------------------
  - missing required params (no launchConfigurationName, no projectName)
        -> "projectName is required (or pass launchConfigurationName)"
  - Mode 2 partial: projectName present but applicationId missing
        -> "applicationId is required. Use get_applications ... or pass
            launchConfigurationName ..."
  - Mode 1: non-existent launchConfigurationName
        -> "Launch configuration not found: '<name>'. Create it in EDT first."
           (+ an availableConfigurations diagnostic list)
  - Mode 2: valid (ready) project but a non-existent applicationId
        -> "Application not found: <id>. Use get_applications to get valid
            application IDs."
  - Mode 2: non-existent projectName (+ some applicationId)
        -> readiness pre-check now refuses only the transient BUILDING state, so a
            missing project falls through to launchDebug's value-naming branch (the
            shared ProjectContext.notFoundMessage):
            "Project not found: <name>. Use list_projects to see available projects."
  - empty-string projectName behaves like missing (execute() checks isEmpty()).

Fixture inventory used (TestConfiguration, English Names): the project itself
("TestConfiguration"). It has NO registered application and NO launch config.
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL
#
# There is no precondition-free success path for debug_launch in this environment
# (no infobase, no application, no launch config — and we must NOT spawn a real
# client). The realistic happy contract is therefore the CLEAR SENTINEL: the most
# common real call (Mode 1 by config name) names the missing config + tells you to
# create it, and even hands back the list of configurations that DO exist. We
# assert that actionable shape; a broken tool that silently "succeeded" (claimed a
# session it could not start) or returned a blank error fails this.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_launch", kind="read")
def test_launch_by_unknown_config_name_returns_actionable_sentinel():
    """Mode 1 with a config name that does not exist -> the canonical sentinel.

    DebugLaunchTool.launchByConfigName: findLaunchConfigByName returns null, so the
    tool returns "Launch configuration not found: '<name>'. Create it in EDT first."
    and ALSO attaches an `availableConfigurations` diagnostic array (the configs the
    client CAN choose from). This is the actionable, no-session contract: it names
    the bad config, the fix (create it in EDT), and enumerates the real options.

    Mutation sense: a tool that ignored the name and faked a "session started"
    success, or that returned a bare "Error", fails assert_error / the quality check.
    """
    bad_cfg = "NoSuchLaunchConfig_ZZZ_e2e"
    r = call("debug_launch", {"launchConfigurationName": bad_cfg})
    err = assert_error(r, "Mode 1: unknown launch configuration name")
    # Names the bad bareword + is actionable (the "create it in EDT" next step).
    # Match delimiter-free substrings so Gson's apostrophe-escaping can't break it.
    assert_error_quality(
        err,
        names=[bad_cfg, "not found"],
        suggests=["Create it in EDT"],
        ctx="unknown config name is named AND the fix is spelled out",
    )
    # The diagnostic list of available configurations is part of the actionable
    # contract — it tells the caller what they CAN launch. Assert the envelope
    # carries it (a regression that dropped the discovery aid would slip past a
    # message-only check). structuredContent holds the JSON envelope for a JSON tool.
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("JSON tool must populate structuredContent: %r" % sc)
    if "availableConfigurations" not in sc:
        raise AssertionError(
            "not-found sentinel must enumerate availableConfigurations: %r" % sc)
    if not isinstance(sc.get("availableConfigurations"), list):
        raise AssertionError(
            "availableConfigurations must be a list: %r" % sc.get("availableConfigurations"))
    assert_no_diff("trying to launch a debugger must not touch the project source")


@e2e_test(tool="debug_launch", kind="read")
def test_mode2_unknown_application_points_at_get_applications():
    """Mode 2 against the REAL, ready fixture project but a non-existent applicationId.

    The project IS ready, so the BUILDING-only readiness gate
    (ProjectStateChecker.buildingErrorOrNull) returns null and control reaches
    launchDebug -> ctx.exists() passes -> appManager.getApplication(project, id)
    returns empty ->
    "Application not found: <id>. Use get_applications to get valid application IDs."
    This is the no-infobase sentinel for the legacy path: it names the bad id and
    routes the caller to the discovery tool that yields valid ids.

    Mutation sense: this only fires if the tool actually validated the application
    against the project; a tool that blindly launched (or faked success) would not
    produce this named, actionable error.
    """
    bad_app = "NoSuchApplicationId_ZZZ_e2e"
    r = call("debug_launch", {"projectName": PROJECT, "applicationId": bad_app})
    err = assert_error(r, "Mode 2: unknown applicationId on a ready project")
    assert_error_quality(
        err,
        names=[bad_app, "Application not found"],
        suggests=["get_applications"],
        ctx="unknown applicationId is named AND routed to get_applications",
    )
    assert_no_diff("a rejected launch must not touch the project source")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — missing required params
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_launch", kind="read")
def test_no_params_at_all_requires_project_or_config_name():
    """Neither mode selected: no launchConfigurationName AND no projectName.

    execute() falls through Mode 1 (config empty) into Mode 2, where the
    projectName==null/empty guard fires:
    "projectName is required (or pass launchConfigurationName)". The message is
    actionable: it names the missing param AND points at the alternative entry
    point (launchConfigurationName) that selects Mode 1.
    """
    r = call("debug_launch", {})
    err = assert_error(r, "no params -> neither launch mode is satisfiable")
    assert_error_quality(
        err,
        names=["projectName is required"],
        suggests=["launchConfigurationName"],
        ctx="missing projectName names it AND offers the launchConfigurationName alternative",
    )
    assert_no_diff("an invalid call must not touch the project source")


@e2e_test(tool="debug_launch", kind="read")
def test_empty_project_name_behaves_like_missing():
    """Boundary: projectName="" (and no config name). execute() guards with
    `projectName == null || projectName.isEmpty()`, so the empty string is treated
    as missing and hits the SAME "projectName is required" sentinel — it must NOT be
    coerced into a real project. (extractStringArgument returns the raw value, no
    trim, and the guard uses isEmpty(), so "" is caught here.)
    """
    r = call("debug_launch", {"projectName": "", "applicationId": "x"})
    err = assert_error(r, "empty-string projectName")
    assert_error_quality(
        err,
        names=["projectName is required"],
        suggests=["launchConfigurationName"],
        ctx="empty projectName hits the same required-arg sentinel as missing",
    )
    assert_no_diff("an invalid call must not touch the project source")


@e2e_test(tool="debug_launch", kind="read")
def test_mode2_missing_application_id_points_at_get_applications():
    """Mode 2 partial: projectName present but applicationId missing.

    execute() passes the projectName guard, then the applicationId==null/empty guard
    fires BEFORE any project lookup:
    "applicationId is required. Use get_applications to get application list, or pass
     launchConfigurationName to start a config by name (e.g. an Attach config)."
    Conditional-required coverage for the Mode-2 branch: the message names the
    missing param AND offers both next steps (get_applications, launchConfigurationName).
    """
    r = call("debug_launch", {"projectName": PROJECT})
    err = assert_error(r, "Mode 2 missing applicationId")
    assert_error_quality(
        err,
        names=["applicationId is required"],
        suggests=["get_applications"],
        ctx="missing applicationId names it AND routes to get_applications",
    )
    # The same message also offers the Mode-1 escape hatch; assert it so a regression
    # that dropped the alternative (leaving only a dead-end) is caught.
    assert_error_quality(
        err,
        names=[],
        suggests=["launchConfigurationName"],
        ctx="missing applicationId also offers the launchConfigurationName alternative",
    )
    assert_no_diff("an invalid call must not touch the project source")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — invalid / non-existent values
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_launch", kind="read")
def test_mode2_nonexistent_project_is_rejected_before_launch():
    """Mode 2 with a syntactically valid but NON-EXISTENT projectName (+ some
    applicationId). The readiness pre-check in execute() now refuses ONLY the
    transient BUILDING state (ProjectStateChecker.buildingErrorOrNull), which returns
    null for a missing project. So control falls THROUGH to launchDebug, whose first
    act is ctx.exists()==false -> "Project not found: <name>". The client therefore
    sees a value-naming error that echoes the bad projectName, and NO launch happens.

    Mutation sense: a tool that stopped rejecting unknown projects, or that proceeded
    to launch, fails assert_error outright; and the named bad value pins that the
    sharper downstream branch (not the building gate) is the one that fired.
    The not-found message comes from the shared ProjectContext.notFoundMessage, so it
    carries the actionable list_projects discovery tail — asserted via suggests below.
    """
    bad_proj = "NoSuchProject_ZZZ_e2e"
    r = call("debug_launch", {"projectName": bad_proj, "applicationId": "AppId"})
    err = assert_error(r, "Mode 2 non-existent project")
    assert_error_quality(
        err,
        names=[bad_proj, "Project not found"],
        suggests=["list_projects"],
        ctx="non-existent project falls through to the value-naming 'Project not found' branch AND points at list_projects",
    )
    assert_no_diff("a rejected launch must not touch the project source")


@e2e_test(tool="debug_launch", kind="read")
def test_unknown_config_name_takes_precedence_over_project_mode():
    """Mode selection: when BOTH launchConfigurationName and projectName+applicationId
    are supplied, execute() takes Mode 1 (config name wins — the `configName != null
    && !configName.isEmpty()` branch returns before the Mode-2 code). Proof: with a
    BAD config name AND a perfectly valid project+applicationId, the error is the
    Mode-1 "Launch configuration not found: '<name>'." sentinel, NOT a Mode-2
    project/application error. This pins the documented precedence so a refactor that
    reorders the modes (and silently ignored launchConfigurationName) is caught.
    """
    bad_cfg = "NoSuchLaunchConfig_PRECEDENCE_e2e"
    r = call("debug_launch", {
        "launchConfigurationName": bad_cfg,
        "projectName": PROJECT,
        "applicationId": "SomeAppId",
    })
    err = assert_error(r, "config-name precedence over project mode")
    # Must be the Mode-1 message (names the config + create-in-EDT), proving Mode 1 ran.
    assert_error_quality(
        err,
        names=[bad_cfg, "not found"],
        suggests=["Create it in EDT"],
        ctx="config name takes precedence -> Mode-1 sentinel, not a Mode-2 error",
    )
    # And it must NOT be the Mode-2 application error (which would mean Mode 2 ran instead).
    low = (err or "").lower()
    if "application not found" in low:
        raise AssertionError(
            "config name must win: Mode-1 should run, but got the Mode-2 application error: %r"
            % err)
    assert_no_diff("a rejected launch must not touch the project source")

"""
e2e tests for launch (kind: read).

What the tool does
------------------
launch starts an EDT session in debug mode (default) or regular run mode. It has
TWO target-selection forms, selected by which params are present (see LaunchTool.execute):

  Mode 1 — launchConfigurationName: start any existing EDT debug launch
           configuration by its EXACT name (runtime client OR an
           "Attach to 1C:Enterprise Debug Server" config). projectName /
           applicationId are NOT required in this mode.
  Mode 2 — projectName + applicationId: legacy path that finds the matching
           runtime-client launch config for that project+application and starts it.

ENVIRONMENT (why these are sentinel/negative tests, not a real launch)
----------------------------------------------------------------------
This is a RUNTIME tool. In THIS EDT there is NO running infobase for
TestConfiguration, NO registered application, and NO pre-created EDT launch
configuration. Actually starting a session is heavy, spawns a 1C client,
and is not configured here — so we deliberately do NOT drive a real launch.

The realistic, CORRECT contract in this environment is that EVERY reachable call
fails FAST with a CLEAR, ACTIONABLE sentinel that names the missing precondition
and the next step (which sibling tool to call / what to create in EDT). That is
exactly what these tests assert — and each assertion is mutation-sensitive: a tool
that no-oped, returned a bogus success, or emitted a vague/blank error would fail.

Response shape (IMPORTANT)
--------------------------
LaunchTool.getResponseType() == JSON, so the payload is a JSON envelope. On
error the envelope is {"success": false, "error": "<message>"} and the protocol
layer flags the Result isError; assert_error returns that error string (the harness
reads structuredContent.error first). For a JSON tool r.text is only a placeholder.

Gson note: ToolResult.toJson() HTML-/quote-escapes some chars, and several of these
messages embed a single-quoted value ('NoSuchConfig'). So every error-quality
assertion below matches DELIMITER-FREE substrings (the bad bareword, "not found",
"is required", "Use get_applications", "Create it in EDT") — never a raw "'...'"
or a ">=" that Gson would have rewritten to \\uXXXX.

DIFF: launch operates on the EDT launch manager / a (would-be) running
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
            missing project falls through to LaunchTool.launch's value-naming branch (the
            shared ProjectContext.notFoundMessage):
            "Project not found: <name>. Use list_projects to see available projects."
  - empty-string projectName behaves like missing (execute() checks isEmpty()).

Fixture inventory used (TestConfiguration, English Names): the project itself
("TestConfiguration"). It has NO registered application and NO launch config.
"""

import time

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    e2e_test,
    requires_live_infobase,
    _post,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL
#
# There is no precondition-free success path for launch in this environment
# (no infobase, no application, no launch config — and we must NOT spawn a real
# client). The realistic happy contract is therefore the CLEAR SENTINEL: the most
# common real call (Mode 1 by config name) names the missing config + tells you to
# create it, and even hands back the list of configurations that DO exist. We
# assert that actionable shape; a broken tool that silently "succeeded" (claimed a
# session it could not start) or returned a blank error fails this.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="launch", kind="read")
def test_run_mode_is_advertised_accepted_and_echoed():
    """The wire schema exposes debug/run and a run request survives mode validation.

    A real 1C launch is intentionally not required: the non-existent configuration
    reaches the normal configuration sentinel, whose structured response echoes the
    accepted mode.
    """
    raw = _post("tools/list", {})
    advertised = (raw.get("result", {}) or {}).get("tools", []) or []
    launches = [tool for tool in advertised if tool.get("name") == "launch"]
    if len(launches) != 1:
        raise AssertionError("tools/list must advertise launch exactly once: %r" % launches)
    descriptor = launches[0]
    mode_schema = ((descriptor.get("inputSchema") or {}).get("properties") or {}).get("mode") or {}
    if mode_schema.get("enum") != ["debug", "run"]:
        raise AssertionError("launch.mode must advertise enum [debug, run]: %r" % mode_schema)
    output_mode = ((descriptor.get("outputSchema") or {}).get("properties") or {}).get("mode") or {}
    if output_mode.get("type") != "string":
        raise AssertionError("launch outputSchema must declare the mode echo: %r" % output_mode)

    bad_cfg = "NoSuchRunLaunchConfig_ZZZ_e2e"
    r = call("launch", {"launchConfigurationName": bad_cfg, "mode": "run"})
    err = assert_error(r, "run mode reaches the normal configuration sentinel")
    assert_error_quality(err, names=[bad_cfg, "not found"], suggests=["Create it in EDT"],
                         ctx="run is accepted before configuration resolution")
    if (r.structured or {}).get("mode") != "run":
        raise AssertionError("the response must echo mode=run: %r" % (r.structured,))
    assert_no_diff("a rejected run launch must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_unknown_launch_mode_is_rejected_with_allowed_values():
    bad = "profile"
    r = call("launch", {"mode": bad})
    err = assert_error(r, "unknown launch mode")
    assert_error_quality(err, names=[bad], suggests=["debug", "run"],
                         ctx="unknown mode names the bad value and both accepted values")
    assert_no_diff("mode validation must fail before any launch side effect")


@e2e_test(tool="launch", kind="read")
def test_launch_by_unknown_config_name_returns_actionable_sentinel():
    """Mode 1 with a config name that does not exist -> the canonical sentinel.

    LaunchTool.launchByConfigName: findLaunchConfigByName returns null, so the
    tool returns "Launch configuration not found: '<name>'. Create it in EDT first."
    and ALSO attaches an `availableConfigurations` diagnostic array (the configs the
    client CAN choose from). This is the actionable, no-session contract: it names
    the bad config, the fix (create it in EDT), and enumerates the real options.

    Mutation sense: a tool that ignored the name and faked a "session started"
    success, or that returned a bare "Error", fails assert_error / the quality check.
    """
    bad_cfg = "NoSuchLaunchConfig_ZZZ_e2e"
    r = call("launch", {"launchConfigurationName": bad_cfg})
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


@e2e_test(tool="launch", kind="read")
def test_mode2_unknown_application_points_at_get_applications():
    """Mode 2 against the REAL, ready fixture project but a non-existent applicationId.

    The project IS ready, so the BUILDING-only readiness gate
    (ProjectStateChecker.buildingErrorOrNull) returns null and control reaches
    LaunchTool.launch -> ctx.exists() passes -> appManager.getApplication(project, id)
    returns empty ->
    "Application not found: <id>. Use get_applications to get valid application IDs."
    This is the no-infobase sentinel for the legacy path: it names the bad id and
    routes the caller to the discovery tool that yields valid ids.

    Mutation sense: this only fires if the tool actually validated the application
    against the project; a tool that blindly launched (or faked success) would not
    produce this named, actionable error.
    """
    bad_app = "NoSuchApplicationId_ZZZ_e2e"
    r = call("launch", {"projectName": PROJECT, "applicationId": bad_app})
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
@e2e_test(tool="launch", kind="read")
def test_no_params_at_all_requires_project_or_config_name():
    """Neither mode selected: no launchConfigurationName AND no projectName.

    execute() falls through Mode 1 (config empty) into Mode 2, where the
    projectName==null/empty guard fires:
    "projectName is required (or pass launchConfigurationName)". The message is
    actionable: it names the missing param AND points at the alternative entry
    point (launchConfigurationName) that selects Mode 1.
    """
    r = call("launch", {})
    err = assert_error(r, "no params -> neither launch mode is satisfiable")
    assert_error_quality(
        err,
        names=["projectName is required"],
        suggests=["launchConfigurationName"],
        ctx="missing projectName names it AND offers the launchConfigurationName alternative",
    )
    assert_no_diff("an invalid call must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_empty_project_name_behaves_like_missing():
    """Boundary: projectName="" (and no config name). execute() guards with
    `projectName == null || projectName.isEmpty()`, so the empty string is treated
    as missing and hits the SAME "projectName is required" sentinel — it must NOT be
    coerced into a real project. (extractStringArgument returns the raw value, no
    trim, and the guard uses isEmpty(), so "" is caught here.)
    """
    r = call("launch", {"projectName": "", "applicationId": "x"})
    err = assert_error(r, "empty-string projectName")
    assert_error_quality(
        err,
        names=["projectName is required"],
        suggests=["launchConfigurationName"],
        ctx="empty projectName hits the same required-arg sentinel as missing",
    )
    assert_no_diff("an invalid call must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_mode2_missing_application_id_points_at_get_applications():
    """Mode 2 partial: projectName present but applicationId missing.

    execute() passes the projectName guard, then the applicationId==null/empty guard
    fires BEFORE any project lookup:
    "applicationId is required. Use get_applications to get application list, or pass
     launchConfigurationName to start a config by name (e.g. an Attach config)."
    Conditional-required coverage for the Mode-2 branch: the message names the
    missing param AND offers both next steps (get_applications, launchConfigurationName).
    """
    r = call("launch", {"projectName": PROJECT})
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
@e2e_test(tool="launch", kind="read")
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
    r = call("launch", {"projectName": bad_proj, "applicationId": "AppId"})
    err = assert_error(r, "Mode 2 non-existent project")
    assert_error_quality(
        err,
        names=[bad_proj, "Project not found"],
        suggests=["list_projects"],
        ctx="non-existent project falls through to the value-naming 'Project not found' branch AND points at list_projects",
    )
    assert_no_diff("a rejected launch must not touch the project source")


@e2e_test(tool="launch", kind="read")
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
    r = call("launch", {
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


@e2e_test(tool="launch", kind="action")
def test_unknown_external_infobase_changes_value_is_rejected():
    """externalInfobaseChanges decides how the pre-launch update answers EDT's blocking
    "Infobase configuration changes" modal. An unrecognised token must be rejected with
    the accepted values rather than silently defaulting to 'override', which writes the
    infobase. Rejected before any launch is attempted, so nothing is started."""
    bad = "merge"
    r = call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_e2e",
        "externalInfobaseChanges": bad,
    })
    e = assert_error(r, "unknown externalInfobaseChanges value")
    assert_error_quality(e, names=[bad], suggests=["override", "import", "cancel"],
                         ctx="unknown externalInfobaseChanges names the bad value and lists the accepted ones")
    assert_no_diff("a rejected launch must not touch the project on disk")


@e2e_test(tool="launch", kind="read")
def test_unknown_standalone_server_port_conflict_value_is_rejected():
    """standaloneServerPortConflict answers EDT's blocking "Standalone server port
    conflict" modal. One of its two answers makes EDT REWRITE the server configuration,
    so a typo must never resolve to it - nor silently fall back to the default. An
    unrecognised token is rejected up front, naming the bad value and the accepted ones."""
    bad = "find-free-port"
    r = call("launch", {
        "launchConfigurationName": "no such configuration at all",
        "standaloneServerPortConflict": bad,
    })
    e = assert_error(r, "unknown standaloneServerPortConflict value")
    assert_error_quality(e, names=[bad], suggests=["cancel", "reassign"],
                         ctx="unknown standaloneServerPortConflict names the bad value and lists the accepted ones")


# ──────────────────────────────────────────────────────────────────────────────
# PER-LAUNCH OVERRIDES (issue #344): /C startup option + the external data
# processor / report to run on startup (/Execute).
#
# A real launch is still out of scope here (no infobase, no client), but the
# whole point of these arguments is that a BAD one must be refused instead of
# producing a launch that looks fine and runs nothing - EDT's own delegate only
# LOGS when it cannot build the dump. So the refusals are the contract, and they
# are asserted to happen BEFORE any launch configuration is even resolved.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="launch", kind="read")
def test_external_object_half_address_is_refused_and_names_the_missing_half():
    """externalObjectProjectName and externalObjectName are one address in two fields.

    Half of it can only be a mistake, and the refusal must say WHICH half is missing rather
    than a generic "bad arguments" - the caller cannot see which field they forgot.

    Asserted with a launch configuration name that does NOT exist: the refusal must still be
    about the arguments, proving the check runs before the configuration is resolved. That
    ordering is the safety property - both launch modes can terminate a live client session
    and update the infobase on their way to the launch.
    """
    project_only = call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": "ExternalObjects",
    })
    e = assert_error(project_only, "external object project without an object name")
    assert_error_quality(
        e,
        names=["externalObjectName is missing"],
        suggests=["externalObjectProjectName and externalObjectName go together"],
        ctx="the missing half is named, not just rejected",
    )
    if "not found" in e and "NoSuchLaunchConfig" in e:
        raise AssertionError(
            "the argument check must run BEFORE the launch configuration is resolved, "
            "got the config-not-found sentinel instead: %s" % e)

    object_only = call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectName": "ExtProc",
    })
    e = assert_error(object_only, "external object name without a project")
    assert_error_quality(
        e,
        names=["externalObjectProjectName is missing"],
        suggests=["never by a"],
        ctx="the other missing half is named too",
    )
    assert_no_diff("a refused launch must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_external_object_project_must_actually_be_an_external_objects_project():
    """Pointing externalObjectProjectName at the CONFIGURATION is the obvious first mistake.

    The two projects are different things - projectName is the configuration being debugged,
    externalObjectProjectName is the project whose nature is external objects - and the
    refusal has to say so, because "not found" would send the caller looking for a typo in
    the object name instead.
    """
    r = call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": PROJECT,
        "externalObjectName": "ExtProc",
    })
    e = assert_error(r, "the configuration project named as the external-objects one")
    assert_error_quality(
        e,
        names=[PROJECT, "not an external-objects project"],
        suggests=["projectName"],
        ctx="the refusal separates the two projects instead of reporting a missing object",
    )
    assert_no_diff("a refused launch must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_unknown_external_object_lists_what_the_project_does_have():
    """A misspelt object name is answered with the names that exist.

    This is the refusal that replaces EDT's silent behaviour: its launch delegate resolves
    the same object, finds nothing, writes one line to the log and starts the session with no
    /Execute - a launch that succeeds and runs nothing. Listing the real names turns that into
    a fixable error.
    """
    r = call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": "ExternalObjects",
        "externalObjectName": "NoSuchProcessor_ZZZ_e2e",
    })
    e = assert_error(r, "unknown external object")
    assert_error_quality(
        e,
        names=["NoSuchProcessor_ZZZ_e2e", "External object not found"],
        suggests=["Available"],
        ctx="the bad name is quoted back and the real ones are listed",
    )
    # The fixture holds one external data processor and one external report; both must be
    # offered, so the listing cannot be a single hardcoded name.
    for existing in ("ExtProc", "ExtReport"):
        if existing not in e:
            raise AssertionError(
                "the refusal must list the external objects that DO exist, %r missing from: %s"
                % (existing, e))
    assert_no_diff("a refused launch must not touch the project source")


def _assert_object_resolved(err, ctx):
    """Assert the call got PAST external-object resolution, whatever it then failed on.

    Two outcomes are both correct, and which one appears depends on the machine rather than on
    the code under test:

    * on a stand with a 1C platform the object resolves, the dump gate passes, and the call
      goes on to fail on the missing launch configuration;
    * on a bare CI runner the object still resolves, but EDT reports it cannot generate dumps
      at all ("parent project must have a developing application with an infobase") - the
      pre-launch gate firing exactly as designed, because a launch there really would start a
      session with no /Execute.

    Pinning either one alone would make the test a statement about the environment. What must
    hold everywhere is the negative: the failure is NOT the object failing to resolve.
    """
    for resolution_failure in ("External object not found",
                               "not an external-objects project",
                               "is not an external object kind",
                               "go together"):
        if resolution_failure in err:
            raise AssertionError(
                "%s: the external object must resolve, but the call failed on %r: %s"
                % (ctx, resolution_failure, err))
    for expected in ("Launch configuration not found", "cannot build the external object"):
        if expected in err:
            return
    raise AssertionError(
        "%s: expected either the launch-configuration sentinel or the dump-precondition "
        "refusal, got: %s" % (ctx, err))


@e2e_test(tool="launch", kind="read")
def test_a_resolvable_external_object_gets_past_the_argument_check():
    """The positive half: a REAL object must not be refused by the argument check.

    Without this the three refusals above could all be passing for the wrong reason - a check
    that rejects everything satisfies them. A launch cannot be driven here (no infobase, no
    client), so the assertion is that the call proceeds PAST the overrides and fails on the
    launch configuration instead: the sentinel it reaches is the config-not-found one.
    """
    r = call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": "ExternalObjects",
        "externalObjectName": "ExtProc",
        "startupOption": "SomeStartupCommand",
    })
    e = assert_error(r, "a resolvable external object with no launch configuration")
    _assert_object_resolved(e, "a valid external object is not what the call fails on")
    assert_no_diff("a refused launch must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_external_object_name_may_be_qualified_by_kind():
    """A data processor and a report may share a programmatic name, so the name alone is not a key.

    EDT keys on (name, type) - its own resolver filters by both and the launch stores the type as
    a separate attribute - so a bare name is accepted only while it is unambiguous, and a
    qualified one always works. The fixture holds no collision to trigger the ambiguity refusal,
    but the qualification it would tell the caller to use is exercised here in all three shapes.
    """
    # Right kind: resolves, so the call fails on something LATER (see _assert_object_resolved).
    e = assert_error(call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": "ExternalObjects",
        "externalObjectName": "ExternalDataProcessor.ExtProc",
    }), "a qualified name of the right kind")
    _assert_object_resolved(e, "a correctly qualified object must pass the argument check")

    # Right name, WRONG kind: ExtProc is a data processor, not a report.
    e = assert_error(call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": "ExternalObjects",
        "externalObjectName": "ExternalReport.ExtProc",
    }), "a qualified name of the wrong kind")
    assert_error_quality(
        e,
        names=["ExtProc", "ExternalReport"],
        suggests=["Available"],
        ctx="the kind is part of the address, so the wrong one must not resolve",
    )

    # A qualifier that is not an external object kind at all.
    e = assert_error(call("launch", {
        "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
        "externalObjectProjectName": "ExternalObjects",
        "externalObjectName": "Catalog.ExtProc",
    }), "a qualifier that is not an external object kind")
    assert_error_quality(
        e,
        names=["Catalog", "not an external"],
        suggests=["ExternalDataProcessor", "ExternalReport"],
        ctx="the refusal names the bad token and the two that work",
    )
    assert_no_diff("a refused launch must not touch the project source")


@e2e_test(tool="launch", kind="read")
def test_a_bare_name_resolves_the_same_way_a_qualified_one_does():
    """One address, two spellings - they must accept the same names.

    The qualified form goes through `MetadataScope.findObject`, which matches programmatic names
    case-insensitively like the rest of the tool surface. The bare form used to compare exactly,
    so `extproc` resolved when written as `ExternalDataProcessor.extproc` and was reported
    missing when written on its own. Whichever spelling is used, the canonical name is what gets
    stamped onto the launch, so the casing a caller types never reaches EDT either way.
    """
    for name in ("extproc", "EXTPROC", "ExternalDataProcessor.extproc"):
        e = assert_error(call("launch", {
            "launchConfigurationName": "NoSuchLaunchConfig_ZZZ_e2e",
            "externalObjectProjectName": "ExternalObjects",
            "externalObjectName": name,
        }), "the object addressed as %r" % name)
        _assert_object_resolved(e, "%r must resolve to ExtProc" % name)
    assert_no_diff("a refused launch must not touch the project source")


@e2e_test(tool="launch", kind="action")
def test_live_external_processor_is_actually_launched_with_execute():
    """ATTENDED: a REAL launch reaches EDT and starts a session with the overrides applied.

    Skipped unless EDT_MCP_LIVE_INFOBASE=1, because it spawns a 1C client against a live
    infobase.

    WHAT THIS PROVES: the arguments survive the whole path - a stamped working copy is accepted
    by EDT's launch delegate and a debug session really comes up. Waiting for debug_status to
    report the session is the load-bearing part; without it the test would also pass on a launch
    that never started.

    WHAT IT DOES NOT PROVE, deliberately and worth knowing before trusting it: that the 1C client
    actually RAN the processor. If the stamped name/type pair did not match what EDT resolves,
    the delegate logs and starts the client WITHOUT /Execute - and this test would still pass,
    because the echoed fields are built locally by the tool before the delegate runs, and
    debug_status only shows that some session exists. Closing that gap needs the fixture
    processor to leave an observable trace (write a marker file whose path arrives through the
    /C startup option, then poll for the file), which is fixture work that cannot be authored
    blind - it has to be written and RUN against a live infobase. Until then the name/type
    stamping is pinned by LaunchOverridesTest instead.

    Cleans up after itself - the spawned session is terminated and the throwaway launch
    configuration removed - so an attended run leaves the workspace as it found it.
    """
    requires_live_infobase("spawns a real 1C client running an external data processor")

    cfg_name = "e2e_344_external_object_launch"
    created = call("create_launch_config", {"projectName": PROJECT, "name": cfg_name})
    assert_ok(created, "a throwaway runtime-client configuration to launch")
    app_id = None
    try:
        r = call("launch", {
            "launchConfigurationName": cfg_name,
            "externalObjectProjectName": "ExternalObjects",
            "externalObjectName": "ExtProc",
            "startupOption": "E2E344",
            "restartIfRunning": True,
        })
        assert_ok(r, "launch the external data processor")
        sc = r.structured
        if not isinstance(sc, dict):
            raise AssertionError("JSON tool must populate structuredContent: %r" % sc)
        # The echo is a LOCAL statement of what the tool applied - it is built before the
        # delegate runs, so it says "the override was not dropped on our side", not "the
        # processor ran". Asserted for that narrower reason only.
        for key, expected in (("externalObjectProjectName", "ExternalObjects"),
                              ("externalObjectName", "ExtProc"),
                              ("startupOption", "E2E344")):
            if sc.get(key) != expected:
                raise AssertionError(
                    "the response must echo the applied override %s=%r, got %r" % (key, expected, sc.get(key)))

        # The launch is ASYNCHRONOUS - the tool returns status:"launching" the moment the
        # background job is scheduled. Without waiting for the session to actually appear, this
        # test would pass on a launch that never started, and the cleanup below would find
        # nothing to kill while the client came up afterwards and stayed. So observe it live
        # first; that observation is also the only thing here that proves EDT accepted the
        # stamped working copy.
        app_id = sc.get("applicationId")
        if not app_id:
            raise AssertionError("the launch handle carried no applicationId: %r" % sc)
        live = False
        deadline = time.time() + 120
        while time.time() < deadline and not live:
            st = call("debug_status", {"applicationId": app_id})
            for lp in (st.structured or {}).get("launches", []) or []:
                if lp.get("applicationId") == app_id:
                    live = True
                    break
            if not live:
                time.sleep(2)
        if not live:
            raise AssertionError(
                "debug_status never reported the launch %r as live, so nothing here observed a "
                "real session - the echo alone would pass on a launch that never started"
                % (app_id,))
    finally:
        term = call("terminate_launch", {"projectName": PROJECT, "applicationId": app_id}
                    ) if app_id else None
        call("delete_launch_config", {"name": cfg_name, "confirm": True})

    # Cleanup has to be asserted, not hoped for: a not_found here means the session outlived the
    # test with its configuration already deleted.
    assert_ok(term, "terminate the session this test started")
    term_text = (term.text or "").lower()
    if "not_found" in term_text:
        raise AssertionError(
            "terminate_launch found no live launch although debug_status had just reported one: "
            "%s" % ((term.text or "")[:300]))

    # The saved configuration is gone, but while it existed the override must never have been
    # written into it - that is the acceptance criterion the unit test pins on a mock and this
    # one would have caught for real had applyTo saved the working copy.
    assert_no_diff("launching a processor must not touch the project source")

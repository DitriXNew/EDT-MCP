"""
e2e tests for run_yaxunit_tests (kind: read for our purposes — it must NOT modify
the git-tracked project tree).

What the tool does
------------------
RunYaxunitTestsTool starts a named BackgroundJobs job that launches a 1C runtime-client
with the RunUnitTests startup parameter and collects its JUnit XML. The start call waits
up to `timeout`: a short run returns the original Markdown report in r.text; otherwise
**Pending** carries a jobId that is polled with get_job_status. Repeating reconstructed
arguments is only a live duplicate guard and is not how a known run is addressed.
The report.md / junit.xml / xUnitParams.json files all live under the SYSTEM TEMP
dir (java.io.tmpdir/edt-mcp-yaxunit/...), NEVER inside the TestConfiguration/ git
tree — so a correct run leaves the fixture clean. The attended #668 regression is
gated by EDT_MCP_LIVE_INFOBASE=1 and needs a debug standalone server already running.

ENVIRONMENT (this EDT / fixture) — why the SENTINEL is the realistic happy contract
-----------------------------------------------------------------------------------
This is a runtime/debug tool. In THIS environment TestConfiguration has:
  - NO runtime-client launch configuration registered, and
  - NO running infobase / launched application.
Actually spawning a YAXUnit run is heavy and not configured (no infobase, no
YAXUnit extension), and the SKILL forbids starting one. So a well-formed call
(real project + a syntactic applicationId) cannot reach a real launch; it stops at
config resolution and returns a CLEAR, ACTIONABLE sentinel. THAT sentinel IS the
correct, realistic happy contract here, and we assert it precisely.

Control-flow facts pinned from RunYaxunitTestsTool.java (so the asserts are
mutation-sensitive against the SPECIFIC message, not just is_error):

  execute() — argument guards, fire FIRST, before any launch logic:
    * no launchConfigurationName AND no projectName
        -> "projectName is required (or pass launchConfigurationName)"
    * no launchConfigurationName AND projectName present but no applicationId
        -> "applicationId is required (or pass launchConfigurationName). Use
            get_applications or list_configurations."

  runTests() — only reached once the above guards pass:
    * updateScope naming an unknown extension (projectName call style,
      updateBeforeLaunch default true) -> validated FIRST, before launch-config
      resolution (LaunchLifecycleUtils.validateUpdateScope) — so it is reachable
      headlessly, with no launch config and no infobase:
        "updateScope requests unknown extension project name: <Name>. Available
         extension projects for this configuration: <names>. ..."
    * launchConfigurationName given but not found (resolveLaunchConfig -> null)
        -> "Launch configuration not found: '<name>'. Use list_configurations to
            see what's available."
    * projectName + applicationId given but NO matching runtime-client config
      (the TestConfiguration reality here) -> buildNoConfigError():
        "No launch configuration found for project '<proj>' and application
         '<app>'.\n\nCreate a launch configuration in EDT first (Run > Run
         Configurations > 1C:Enterprise Runtime Client)." (+ an optional table of
         any available configs). is_error=true.

  ORDERING NOTE (important for the negative matrix): in runTests() the launch
  config is resolved BEFORE ProjectStateChecker.checkReadyOrError and BEFORE the
  ProjectContext.exists()/isOpen() checks (those run at lines ~224-239, only after
  a config matches). So for a NON-EXISTENT project + applicationId with no config,
  the reachable error is the buildNoConfigError "No launch configuration found for
  project '<bad>' ..." branch — the bad project name IS echoed there, but the
  "Project not found" branch is shadowed and never reached for our no-config env.

Parameter shape (from getInputSchema / execute) — all OPTIONAL at the schema level;
the required-ness is conditional and enforced in code:
    launchConfigurationName (str), projectName (str), applicationId (str),
    extensions (array), modules (array), tests (array), tags (array) -- each declared
    type:array but a comma-separated string is also accepted (shared extractArrayArgument),
    timeout (int, default 45, clamped into [1, 45] — see below), updateBeforeLaunch
    (bool, default true).
There is NO closed enum and NO declared XOR pair; the real conditional-required
branches (projectName/applicationId vs launchConfigurationName) ARE exercised below.

TIMEOUT BOUNDS THE START CALL, AND IT IS CLAMPED (#357)
------------------------------------------------------
`timeout` bounds how long the start call waits for the job (whose work covers resolve +
pre-launch preparation + spawn + poll), and RunYaxunitTestsTool.clampTimeout() caps it at
MAX_TIMEOUT_SECONDS = 45. An MCP transport cuts a call at roughly 60s, so a larger
window could only turn the tool's answer into a bare transport error — which is
exactly what #357 reported. The clamp is SILENT: a large value is accepted and
quietly reduced, never rejected, so an existing caller passing `timeout: 240` keeps
working. That silence is asserted below, because "reject anything above 45" would be
a plausible-looking implementation that breaks every such caller.

Whenever the call has not finished the work it returns **Pending** with a `jobId`, the
`run_yaxunit_tests` owning-tool name, and progress naming the phase (`resolve` /
`prep:terminate` / `prep:check-changes` / `prep:recompute` / `prep:settle` /
`prep:db-update` / `spawn` / `run`). `prep:recompute` is CONDITIONAL - only dirty
extensions require a full rebuild. Configurations drain EDT's existing incremental
contexts under `prep:settle` (#663). An unchanged scope also goes
`prep:check-changes` -> `prep:settle` (#310). The job continues
independently of this window and is polled by id, never by rebuilt arguments.

The named job declares a YAXUnit-specific destructive cancellation capability. A
cancel_job preview does nothing and warns that a confirmed live-run stop kills the
client, does not roll back the infobase, and can leave a partial/absent report. This
headless fixture cannot launch a client, so the actual termination path is proved by
the Java unit tests with a committed job and launch double rather than faked here.

Fixture inventory used (TestConfiguration, English Names): the project itself
(projectName "TestConfiguration"); CommonModule.Calc exists but is irrelevant here
(YAXUnit needs an infobase, not a module). No launch configuration registered.
"""

from harness import (
    call,
    assert_ok,
    assert_contains,
    assert_diff_contains,
    follow_pending,
    requires_live_infobase,
    parse_yaxunit_counts,
    wait_until_no_running_launch,
    wait_for_project_ready,
    assert_error,
    assert_error_quality,
    assert_ok,
    assert_no_diff,
    assert_no_substantive_diff,
    e2e_test,
    E2ESkip,
    follow_pending,
    extract_application_id,
    requires_live_infobase,
    wait_for_project_ready,
    wait_until_no_running_launch,
    LIVE_LAUNCH_CONFIG,
    PROJECT,
    TESTS_PROJECT,
    LIVE_LAUNCH_CONFIG,
)


@e2e_test(tool="run_yaxunit_tests", kind="write-metadata")
def test_live_changed_configuration_module_is_current_on_first_run():
    """Prove model/infobase freshness without a forced configuration rebuild.

    The existing extension test expects Calc.Add(2, 3) == 5. Changing only the base
    module makes that test fail on the FIRST run; stale code would incorrectly pass.
    The large-configuration FORM denominator is a separate attended log/progress check.
    """
    requires_live_infobase("changes Calc and runs the real YAXUnit suite")
    module = "CommonModules/Calc/Module.bsl"
    old = "A + B"
    new = "A + B + 663"
    launch = {"launchConfigurationName": LIVE_LAUNCH_CONFIG}
    try:
        assert_ok(call("terminate_launch", launch), "quiet the selected infobase")
        wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG, timeout=60)
        wait_for_project_ready(timeout=180)
        write = call("write_module_source", {
            "projectName": PROJECT, "modulePath": module,
            "mode": "searchReplace", "oldSource": old, "source": new,
        })
        assert_ok(write, "change one base configuration module")
        assert_diff_contains(new, "the edited module is persisted")
        readback = call("read_module_source", {"projectName": PROJECT, "modulePath": module})
        assert_ok(readback, "read back the changed model")
        assert_contains(readback.text, new, "the model contains the new expression")
        arguments = dict(launch, tests="tests_SampleTests.CallsBaseConfigCalcModule",
                         updateBeforeLaunch=True, updateScope="configuration", timeout=45)
        for attempt in range(2):
            result = follow_pending(call("run_yaxunit_tests", arguments),
                                    "first changed run" if attempt == 0 else "unchanged repeat")
            assert_ok(result, "YAXUnit returns the real test report")
            counts = parse_yaxunit_counts(result.text)
            assert counts.get("total") == 1, counts
            assert counts.get("failed") == 1 and counts.get("errors") == 0, counts
            assert counts.get("result") == "FAILED", counts
            wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG, timeout=60)
    finally:
        assert_ok(call("terminate_launch", launch), "stop the test client before fixture restoration")
        wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG, timeout=60)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL
#
# In this no-infobase / no-launch-config environment the realistic "happy"
# observation for a WELL-FORMED call is the no-config sentinel. We assert its
# SPECIFIC, actionable text (not merely is_error), so the test fails if the tool
# silently no-ops, returns a blank/bare error, spawns a phantom launch, or loses
# the actionable next step.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_wellformed_call_without_launch_config_returns_clear_no_config_sentinel():
    """project + applicationId, but TestConfiguration has NO runtime-client launch
    config -> runTests() resolves no config -> buildNoConfigError.

    This is the REAL happy contract in this environment. The sentinel MUST:
      - be an error (is_error=true; the protocol layer marks ToolResult.error),
      - name the offending project AND application (so the user knows what failed),
      - be actionable — point at creating a launch configuration in EDT.
    A broken tool that returned a Pending/blank/garbage report, or that swallowed
    the missing precondition, fails this. We do NOT start a real launch (forbidden +
    heavy); the sentinel + the negative matrix is the coverage."""
    app = "app_that_has_no_launch_config_e2e"
    r = call("run_yaxunit_tests", {"projectName": PROJECT, "applicationId": app})
    err = assert_error(r, "well-formed call with no launch configuration")
    # The no-config sentinel echoes BOTH the project and the application id, and
    # tells the caller exactly what to do (create a launch configuration in EDT).
    assert_error_quality(
        err,
        names=[PROJECT, app],
        suggests=["Create a launch configuration"],
        ctx="no-config sentinel names project+application and is actionable",
    )
    assert_no_diff("a YAXUnit run must never write into the project tree")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_nonexistent_launch_configuration_name_sentinel_points_at_list_configurations():
    """launchConfigurationName branch: a name that does not exist ->
    resolveLaunchConfig returns null -> "Launch configuration not found: '<name>'.
    Use list_configurations to see what's available."

    This exercises the OTHER call style (by config name, projectName/applicationId
    omitted). The sentinel must name the bad config value AND point at the sibling
    discovery tool (list_configurations). This is mutation-sensitive on the specific
    name and the next-step tool — a generic "not found" would fail the actionable
    check."""
    bad_cfg = "NoSuchLaunchConfig_ZZZ_e2e"
    r = call("run_yaxunit_tests", {"launchConfigurationName": bad_cfg})
    err = assert_error(r, "non-existent launchConfigurationName")
    assert_error_quality(
        err,
        names=[bad_cfg],
        suggests=["list_configurations"],
        ctx="missing launch config names the value and points at list_configurations",
    )
    assert_no_diff("a rejected launch must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — conditional-required params, invalid values, missing required
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_no_identifying_params_errors_on_missing_projectname():
    """Neither launchConfigurationName NOR projectName supplied -> the first
    execute() guard fires: "projectName is required (or pass
    launchConfigurationName)".

    This is the conditional-required XOR-ish branch: projectName is required ONLY
    when launchConfigurationName is absent. The message both names the missing
    parameter AND offers the alternative (launchConfigurationName), which is the
    actionable next step."""
    r = call("run_yaxunit_tests", {})
    err = assert_error(r, "no identifying params at all")
    assert_error_quality(
        err,
        names=["projectName"],
        suggests=["launchConfigurationName"],
        ctx="missing projectName names the param and offers the launchConfigurationName alternative",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_projectname_without_applicationid_errors_with_actionable_message():
    """Conditional-required branch 2: projectName present, launchConfigurationName
    absent, but applicationId missing -> "applicationId is required (or pass
    launchConfigurationName). Use get_applications or list_configurations."

    The message names the missing parameter AND points at the sibling tools that
    produce a valid value (get_applications / list_configurations) — assert both the
    named param and an actionable next-step tool."""
    r = call("run_yaxunit_tests", {"projectName": PROJECT})
    err = assert_error(r, "projectName given but applicationId missing")
    assert_error_quality(
        err,
        names=["applicationId"],
        suggests=["get_applications"],
        ctx="missing applicationId names the param and points at get_applications",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_empty_projectname_treated_as_missing():
    """Boundary: projectName="" with no launchConfigurationName. extractStringArgument
    yields an empty string, and the guard checks `projectName.isEmpty()`, so "" hits
    the SAME "projectName is required" branch as the omitted case — it is NOT
    mistaken for a real project. Proves the empty-string boundary is rejected, not
    coerced into a default/blank-named project."""
    r = call("run_yaxunit_tests", {"projectName": ""})
    err = assert_error(r, "empty-string projectName")
    assert_error_quality(
        err,
        names=["projectName"],
        suggests=["launchConfigurationName"],
        ctx="empty projectName hits the required-arg guard, not a silent default",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_unknown_updatescope_extension_fails_fast_with_available_names():
    """updateScope negative: an unknown 'extension:<Name>' is a HARD pre-launch error.

    Pinned control flow: runTests() validates updateScope
    (LaunchLifecycleUtils.validateUpdateScope) BEFORE launch-config resolution when
    the caller named the project directly, so this negative is reachable headlessly —
    no launch configuration and no infobase needed; nothing is launched, terminated,
    or updated. A typo'd name must NOT be silently dropped (a narrowed recompute
    scope would produce the exact stale-green run updateScope was built to prevent).
    The error must name the unknown extension AND list the available extension
    project names (the fixture extension TESTS_PROJECT), so the caller can fix the
    typo without another discovery round-trip.

    Mutation sense: a tool that silently ignored the bad scope would proceed to the
    no-config sentinel (which lacks the 'unknown extension' wording) and fail the
    quality check; one that dropped the available-names list fails the suggests."""
    bad = "NoSuchExtension_ZZZ_e2e"
    r = call("run_yaxunit_tests", {
        "projectName": PROJECT,
        "applicationId": "any_app_id_e2e",  # irrelevant: the scope check fires first
        "updateScope": "extension:" + bad,
    })
    err = assert_error(r, "unknown updateScope extension name")
    assert_error_quality(
        err,
        names=[bad, "unknown extension"],
        suggests=["Available extension projects", TESTS_PROJECT],
        ctx="unknown updateScope extension is named AND the available names are listed",
    )
    assert_no_diff("a rejected updateScope must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_nonexistent_project_with_appid_surfaces_no_config_sentinel():
    """A syntactically valid but NON-EXISTENT project + an applicationId.

    ORDERING NOTE (pinned from runTests()): the launch config is resolved BEFORE the
    project existence / readiness checks. For an unknown project there is no matching
    runtime-client config, so the reachable error is buildNoConfigError — which DOES
    echo the bad project name. The "Project not found: <name>" / readiness branches
    are shadowed and never reached for this no-config input.

    We assert the REAL reachable contract: the no-config sentinel names the bad
    project and is actionable. A broken tool that returned a fake Pending/empty
    success for an unknown project would fail assert_error outright."""
    bad = "NoSuchProject_ZZZ_e2e"
    app = "some_application_id_e2e"
    r = call("run_yaxunit_tests", {"projectName": bad, "applicationId": app})
    err = assert_error(r, "non-existent project + applicationId")
    # The no-config sentinel echoes the (bad) project name and the application, and
    # tells the caller to create a launch configuration in EDT.
    assert_error_quality(
        err,
        names=[bad],
        suggests=["Create a launch configuration"],
        ctx="unknown project surfaces via the no-config sentinel that names it",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="action")
def test_unknown_external_infobase_changes_value_is_rejected():
    """The YAXUnit auto-chain runs the same pre-launch update, so it takes the same
    externalInfobaseChanges policy. An unrecognised token is rejected with the accepted
    values instead of silently defaulting to 'override' (which overwrites the infobase)."""
    bad = "OVERRIDE!"
    r = call("run_yaxunit_tests", {
        "launchConfigurationName": "NoSuchLaunchConfig_e2e",
        "externalInfobaseChanges": bad,
    })
    e = assert_error(r, "unknown externalInfobaseChanges value")
    assert_error_quality(e, names=[bad], suggests=["override", "import", "cancel"],
                         ctx="unknown externalInfobaseChanges names the bad value and lists the accepted ones")
    assert_no_diff("a rejected run must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_live_debug_rerun_after_client_termination_preserves_standalone_server():
    """Requires a prestarted debug standalone server; never pauses the debugger (#668)."""
    requires_live_infobase("repeat a debug YAXUnit launch while preserving its standalone server")
    wait_for_project_ready(timeout=180)
    status = call("debug_status", {})
    assert_ok(status, "find the prestarted standalone server")
    servers = [entry for entry in (status.structured or {}).get("launches", [])
               if entry.get("project") == PROJECT and entry.get("configurationType") ==
               "com.e1c.g5.v8.dt.platform.standaloneserver.launchConfigurationType"]
    if not servers:
        raise E2ESkip("Start the standalone server in debug mode before testing #668")
    assert len(servers) == 1, "#668 needs one unambiguous standalone server: %r" % servers
    server_id = servers[0]["applicationId"]
    server_config = servers[0]["launchConfiguration"]

    def assert_server_alive(phase):
        observed = call("debug_status", {"applicationId": server_id})
        assert_ok(observed, phase)
        entries = [entry for entry in (observed.structured or {}).get("launches", [])
                   if entry.get("applicationId") == server_id]
        assert len(entries) == 1, "%s: standalone server disappeared: %r" % (phase, observed.structured)
        configs = call("list_configurations", {"projectName": PROJECT, "type": "all"})
        assert_ok(configs, phase + ": configuration state")
        matching = [entry for entry in (configs.structured or {}).get("configurations", [])
                    if entry.get("name") == server_config]
        assert len(matching) == 1 and matching[0].get("running") is True, (
            "%s: standalone server is not running: %r" % (phase, matching))
        return entries[0]

    def terminate_client():
        result = call("terminate_launch", {"launchConfigurationName": LIVE_LAUNCH_CONFIG})
        assert_ok(result, "terminate only the thin client")
        wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG, timeout=60)

    try:
        terminate_client()
        assert_server_alive("before the first debug run")
        for attempt in range(2):
            result = follow_pending(call("run_yaxunit_tests", {
                "launchConfigurationName": LIVE_LAUNCH_CONFIG,
                "tests": ["tests_SampleTests.TwoPlusTwoIsFour"],
                "debug": True,
            }), "debug YAXUnit run %d" % (attempt + 1))
            assert_ok(result, "debug YAXUnit run %d" % (attempt + 1))
            assert extract_application_id(result.text), "debug run returned no launch applicationId"
            assert_server_alive("after debug run %d" % (attempt + 1))
            if attempt == 0:
                terminate_client()
                remaining = assert_server_alive("after terminating the first client")
                assert remaining.get("threadCount", 0) > 0, (
                    "#668 requires lingering threads on the standalone-server target")
    finally:
        terminate_client()
    assert_no_substantive_diff("a repeated debug launch must preserve the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# #357 — the timeout ceiling is enforced SILENTLY
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_timeout_above_the_ceiling_is_clamped_not_rejected():
    """`timeout: 240` — the exact value from the #357 report — must be ACCEPTED and
    quietly clamped to MAX_TIMEOUT_SECONDS, not rejected.

    The tool cannot honour a window longer than the MCP transport lives, so the
    ceiling is real; but turning an over-large value into an error would break every
    caller that already passes one (and #357's reporter passed 240). So the call must
    reach the ordinary flow and fail on its ACTUAL precondition — the missing launch
    configuration — with no mention of `timeout` anywhere in the message.

    Mutation-sensitive both ways: an implementation that rejected the value would
    produce a timeout-shaped error (caught by the substring check), and one that
    honoured it unclamped would be invisible here but is pinned by the unit ratchet
    RunYaxunitTestsToolTest.testTimeoutIsClampedToTheTransportSafeCeiling."""
    # NB the name must not itself contain the word this test greps for.
    bad_cfg = "NoSuchLaunchConfig_Ceiling_e2e"
    r = call("run_yaxunit_tests", {"launchConfigurationName": bad_cfg, "timeout": 240})
    err = assert_error(r, "over-large timeout must reach the normal flow")
    assert_error_quality(
        err,
        names=[bad_cfg],
        suggests=["list_configurations"],
        ctx="an over-large timeout is clamped silently: the call still fails on its real precondition",
    )
    assert "timeout" not in err.lower(), (
        "an over-large timeout must be clamped SILENTLY, not rejected: " + err
    )
    assert_no_diff("a rejected launch must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# #409 — the tag filter is a first-class filter family
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_tags_filter_is_accepted_as_an_array():
    """`tags` must be ACCEPTED and carried into the ordinary flow.

    SCOPE, stated honestly: this call dies at launch-config resolution, long before any
    xUnitParams.json is written, so it does NOT prove the tag reaches the filter — an
    implementation that ignored `tags` outright would also pass. What it does prove is
    the wire contract: the argument is accepted in its declared array form and the call
    proceeds to fail on its REAL precondition (the missing launch configuration) instead
    of on the parameter. A schema/validation regression that rejected the new key would
    surface here; a carriage regression would not.

    Carriage is pinned by the unit ratchets RunYaxunitTestsToolTest.
    testTagsLandInTheGeneratedFilter and ...testRunKeyDistinguishesTagSelections, which
    drive the production seams with the same RunRequest the run path builds, and which
    were verified to go red when the filter branch or the key term is deleted."""
    bad_cfg = "NoSuchLaunchConfig_Filtered_e2e"
    r = call(
        "run_yaxunit_tests",
        {"launchConfigurationName": bad_cfg, "tags": ["smoke", "nodb"]},
    )
    err = assert_error(r, "a tag filter must reach the normal flow")
    assert_error_quality(
        err,
        names=[bad_cfg],
        suggests=["list_configurations"],
        ctx="a tag filter is accepted like any other filter family",
    )
    # The failure must be the no-config sentinel, NOT a complaint about the argument:
    # an unknown/rejected parameter would surface as an error naming the value instead.
    assert "smoke" not in err and "nodb" not in err, (
        "tags must be accepted silently, not echoed back as a bad argument: " + err
    )
    assert_no_diff("a rejected launch must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_tags_filter_also_accepts_a_comma_separated_string():
    """`tags` follows the same dual wire shape as the other filter families.

    `extensions`/`modules`/`tests` are declared as arrays but a comma-separated string
    is accepted too (shared extractArrayArgument). A new family that took only one of
    the two shapes would be an inconsistency callers trip over, so pin the string form
    explicitly — it is the shape the deprecated debug_yaxunit_tests alias forwards."""
    bad_cfg = "NoSuchLaunchConfig_FilteredCsv_e2e"
    r = call(
        "run_yaxunit_tests",
        {"launchConfigurationName": bad_cfg, "tags": "smoke,nodb"},
    )
    err = assert_error(r, "a comma-separated tag filter must reach the normal flow")
    assert_error_quality(
        err,
        names=[bad_cfg],
        suggests=["list_configurations"],
        ctx="a comma-separated tag filter is accepted like the array form",
    )
    assert_no_diff("a rejected launch must not touch the project on disk")


@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_unknown_standalone_server_port_conflict_value_is_rejected():
    """standaloneServerPortConflict answers EDT's blocking "Standalone server port
    conflict" modal. One of its two answers makes EDT REWRITE the server configuration,
    so a typo must never resolve to it - nor silently fall back to the default. An
    unrecognised token is rejected up front, naming the bad value and the accepted ones."""
    bad = "find-free-port"
    r = call("run_yaxunit_tests", {
        "launchConfigurationName": "no such configuration at all",
        "standaloneServerPortConflict": bad,
    })
    e = assert_error(r, "unknown standaloneServerPortConflict value")
    assert_error_quality(e, names=[bad], suggests=["cancel", "reassign"],
                         ctx="unknown standaloneServerPortConflict names the bad value and lists the accepted ones")

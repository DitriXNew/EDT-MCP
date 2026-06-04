"""
e2e tests for run_yaxunit_tests (kind: read for our purposes — it must NOT modify
the git-tracked project tree).

What the tool does
------------------
RunYaxunitTestsTool launches a 1C runtime-client with the RunUnitTests startup
parameter, polls until the launch terminates (or `timeout` seconds elapse), parses
the JUnit XML the YAXUnit run drops on disk, and returns a Markdown report
(getResponseType() == MARKDOWN, so the payload is in r.text, NOT r.structured).
The report.md / junit.xml / xUnitParams.json files all live under the SYSTEM TEMP
dir (java.io.tmpdir/edt-mcp-yaxunit/...), NEVER inside the TestConfiguration/ git
tree — so a correct run leaves the fixture clean. Every test here ends with
assert_no_diff(); a YAXUnit tool that wrote into the project tree would be a bug.

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
    extensions (array), modules (array), tests (array) -- each declared type:array
    but a comma-separated string is also accepted (shared extractArrayArgument),
    timeout (int, default 60, clamped to >=1), updateBeforeLaunch (bool, default true).
There is NO closed enum and NO declared XOR pair; the real conditional-required
branches (projectName/applicationId vs launchConfigurationName) ARE exercised below.

Fixture inventory used (TestConfiguration, English Names): the project itself
(projectName "TestConfiguration"); CommonModule.Calc exists but is irrelevant here
(YAXUnit needs an infobase, not a module). No launch configuration registered.
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

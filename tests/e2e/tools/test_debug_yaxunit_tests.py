"""
e2e tests for debug_yaxunit_tests (kind: read).

What the tool does
------------------
debug_yaxunit_tests launches YAXUnit tests in DEBUG mode (so set_breakpoint
breakpoints trip) and returns IMMEDIATELY after the launch is queued; the caller
is then expected to call wait_for_break. It is a RUNTIME/DEBUG tool: it drives a
1C runtime-client launch configuration against a running infobase. It is NOT a
project-source mutator — the only on-disk writes it makes are a private
xUnitParams.json + junit.xml under java.io.tmpdir/edt-mcp-yaxunit-debug, never in
the git-tracked TestConfiguration/ tree. So EVERY test ends with assert_no_diff().

Response shape (IMPORTANT)
--------------------------
DebugYaxunitTestsTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text — for a JSON tool r.text is only a placeholder).
On error the envelope is {"success": false, "error": "<message>"} and the protocol
layer marks the result isError; assert_error()/error_text() surfaces structured.error.

ENVIRONMENT (the realistic, correct happy contract here)
--------------------------------------------------------
In THIS EDT there is NO runtime-client launch configuration for TestConfiguration
and NO running infobase / launched application. The tool can therefore never reach
its success branch (a real workingCopy.launch()): the dual-input resolver
(LaunchConfigUtils.resolveLaunchConfig) returns null long before any launch is
attempted, and execute() returns a CLEAR, ACTIONABLE sentinel:

    "No runtime-client launch configuration for project '<project>' and
     application '<app>'. Use list_configurations to see what's available."

That sentinel IS the happy/realistic contract in this environment: it names the
missing precondition (no launch config for this project+application) AND the next
step (list_configurations). We assert that exact contract; it is mutation-sensitive
(a tool that no-opped, returned a fake success envelope, or emitted a vague/blank
error fails it). We do NOT attempt to start an infobase / real debug launch (heavy,
not configured) — the sentinel + the negative matrix IS the coverage.

Reachable error/sentinel paths (read from DebugYaxunitTestsTool.execute, top-down)
----------------------------------------------------------------------------------
The dual input is: EITHER launchConfigurationName, OR (projectName + applicationId).
  1. no launchConfigurationName AND no projectName
        -> "projectName is required (or pass launchConfigurationName)"
  2. no launchConfigurationName, projectName present, no applicationId
        -> "applicationId is required (or pass launchConfigurationName)"
  3. launchConfigurationName given but no such config (any EDT debug type)
        -> "Launch configuration not found: '<name>'"
  4. projectName + applicationId given but no runtime-client config matches the pair
     (the case in THIS environment, including for a non-existent project name —
     resolveLaunchConfig just finds no matching config and returns null)
        -> "No runtime-client launch configuration for project '<project>' and
            application '<app>'. Use list_configurations to see what's available."
All four are reached BEFORE any project-state / application validation or launch,
so none of them touch a running infobase — they are deterministic in this env.

Parameter shape (from getInputSchema / execute)
------------------------------------------------
All parameters are schema-OPTIONAL strings (+ one boolean updateBeforeLaunch),
but execute() enforces a CONDITIONAL contract: launchConfigurationName XOR
(projectName AND applicationId). The reachable negatives below exercise each
branch of that conditional plus the not-found paths. There is no closed string
enum to violate.
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
# In this no-infobase / no-launch-config environment the realistic happy contract
# is the "no launch configuration" sentinel: it names the missing precondition and
# the next step. (Asserted as the primary contract — see module docstring.)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_yaxunit_tests", kind="read")
def test_no_launch_config_returns_clear_actionable_sentinel():
    """Happy/realistic path for THIS environment: a valid project + an applicationId
    for which NO runtime-client launch configuration exists.

    TestConfiguration has no *.launch config in this workspace, so
    resolveLaunchConfig() returns null and the tool emits its richest sentinel:
    it NAMES the project + application that had no config AND points the caller at
    list_configurations. This is the correct, actionable behavior when the debug
    precondition (a launch configuration) is absent — not a crash, not a silent
    fake-success, not a hang waiting on an infobase.

    Mutation-sensitive: a tool that no-opped (returned success), that emitted a
    vague/blank error, or that dropped the next-step hint would fail here. A read
    tool that touched the project tree fails assert_no_diff.
    """
    app = "no_such_app_e2e"
    r = call("debug_yaxunit_tests", {"projectName": PROJECT, "applicationId": app})
    err = assert_error(r, "no launch config for project+application")
    # Names BOTH the missing-precondition values (project + application) and gives
    # the concrete next step (list_configurations). All three must be present.
    assert_error_quality(
        err,
        names=[PROJECT, app],
        suggests=["list_configurations"],
        ctx="no-launch-config sentinel names project+application and points at list_configurations")
    # Belt-and-suspenders: the sentinel must explicitly state WHAT is missing, not
    # merely echo the values. This wording is the actionable core of the message.
    if "no runtime-client launch configuration" not in err.lower():
        raise AssertionError(
            "sentinel must state the missing precondition (no runtime-client launch "
            "configuration), got: %r" % err)
    assert_no_diff("a debug/read tool must not touch the project source on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — conditional-input branches + not-found paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_yaxunit_tests", kind="read")
def test_missing_all_required_inputs_errors_on_projectname():
    """Neither launchConfigurationName nor projectName/applicationId supplied.

    execute() checks projectName FIRST when launchConfigurationName is absent, so
    the conditional-required guard fires on projectName. A tool that NPE'd or hung
    (e.g. tried to launch with null inputs) instead of returning this guard fails.
    """
    r = call("debug_yaxunit_tests", {})
    err = assert_error(r, "no inputs at all")
    # Names the missing parameter AND tells you the alternative input
    # (launchConfigurationName) — that alternative IS the actionable next step.
    assert_error_quality(
        err,
        names=["projectName"],
        suggests=["launchConfigurationName"],
        ctx="missing-all guard names projectName and offers launchConfigurationName")
    assert_no_diff("a rejected call must not touch the project source on disk")


@e2e_test(tool="debug_yaxunit_tests", kind="read")
def test_projectname_without_applicationid_errors_on_applicationid():
    """projectName present but applicationId omitted (and no launchConfigurationName):
    the SECOND branch of the conditional-required guard must fire on applicationId.

    This distinguishes the two halves of the (projectName AND applicationId) pair —
    a guard that only checked projectName would wrongly fall through here and then
    fail opaquely deeper in. The message must name applicationId specifically.
    """
    r = call("debug_yaxunit_tests", {"projectName": PROJECT})
    err = assert_error(r, "projectName without applicationId")
    assert_error_quality(
        err,
        names=["applicationId"],
        suggests=["launchConfigurationName"],
        ctx="missing applicationId guard names applicationId and offers launchConfigurationName")
    # Must be the applicationId guard, NOT the projectName guard (proves the pair is
    # validated independently and we did not just re-trip the first guard).
    if "applicationid is required" not in err.lower():
        raise AssertionError(
            "expected the applicationId-required guard, got: %r" % err)
    assert_no_diff("a rejected call must not touch the project source on disk")


@e2e_test(tool="debug_yaxunit_tests", kind="read")
def test_nonexistent_launch_configuration_name_errors_and_names_value():
    """launchConfigurationName given but no EDT debug config (runtime-client OR
    attach) carries that name: findLaunchConfigByName returns null and, because
    hasName is true, the tool returns "Launch configuration not found: '<name>'".

    The bad name MUST be echoed verbatim; a resolver that silently matched the
    wrong config (or a different not-found branch that dropped the name) fails this.
    """
    bad = "NoSuchLaunchConfig_ZZZ_e2e"
    r = call("debug_yaxunit_tests", {"launchConfigurationName": bad})
    err = assert_error(r, "non-existent launch configuration name")
    # AUDIT: "Launch configuration not found: '<name>'" names the bad value but
    # points at NO sibling tool (e.g. list_configurations, which the
    # project+application not-found branch DOES mention). suggests=[] is deliberate;
    # fix-card: make the by-name not-found branch actionable too (mention
    # list_configurations to discover valid configuration names).
    assert_error_quality(
        err,
        names=[bad],
        suggests=[],
        ctx="by-name not-found echoes the bad configuration name")
    assert_no_diff("a rejected call must not touch the project source on disk")


@e2e_test(tool="debug_yaxunit_tests", kind="read")
def test_nonexistent_project_with_appid_errors_via_no_config_sentinel():
    """A syntactically valid but non-existent projectName (plus some applicationId)
    must NOT silently succeed.

    ORDERING NOTE: resolveLaunchConfig() runs BEFORE any project-existence check.
    For a project that owns no runtime-client config (a non-existent one owns none),
    the strict (project, applicationId) lookup finds nothing and returns null, so the
    reachable error is the SAME "No runtime-client launch configuration..." sentinel
    rather than a dedicated "Project not found" message. We assert the REAL reachable
    contract (which still fails loudly if the tool stopped erroring / faked success).
    """
    bad_project = "NoSuchProject_ZZZ_e2e"
    app = "no_such_app_e2e"
    r = call("debug_yaxunit_tests",
             {"projectName": bad_project, "applicationId": app})
    err = assert_error(r, "non-existent project with applicationId")
    # AUDIT: a non-existent project surfaces as the generic "no launch configuration"
    # sentinel (the config lookup precedes the ProjectContext.exists() check), so the
    # message blames a missing config rather than a missing project. It still names
    # the bad project value (good) and points at list_configurations (good), but a
    # clearer "Project not found: <name>" diagnostic is unreachable for this input.
    # Fix-card: validate project existence before/alongside the config lookup so an
    # unknown project gets a project-specific error.
    assert_error_quality(
        err,
        names=[bad_project],
        suggests=["list_configurations"],
        ctx="non-existent project surfaces via the no-config sentinel naming the project")
    assert_no_diff("a rejected call must not touch the project source on disk")


@e2e_test(tool="debug_yaxunit_tests", kind="read")
def test_empty_launch_config_name_falls_back_to_projectname_guard():
    """Boundary: launchConfigurationName="" must be treated as ABSENT (hasName uses
    isEmpty), so the tool falls back to the projectName/applicationId branch and —
    with neither supplied — trips the projectName-required guard. This proves the
    empty string is not mistaken for a real configuration name (which would later
    surface as a confusing "Launch configuration not found: ''").
    """
    r = call("debug_yaxunit_tests", {"launchConfigurationName": ""})
    err = assert_error(r, "empty launchConfigurationName falls through to projectName guard")
    # Empty name => fall through => the projectName-required guard, NOT the by-name
    # not-found branch. Asserting the projectName guard proves the empty-string
    # boundary is handled as "absent", not as a (bogus) named config "".
    assert_error_quality(
        err,
        names=["projectName"],
        suggests=["launchConfigurationName"],
        ctx="empty launchConfigurationName is treated as absent, hits projectName guard")
    if "projectname is required" not in err.lower():
        raise AssertionError(
            "empty launchConfigurationName must fall through to the projectName guard, "
            "not the by-name not-found branch, got: %r" % err)
    assert_no_diff("a rejected call must not touch the project source on disk")

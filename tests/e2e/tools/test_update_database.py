"""
e2e tests for update_database (kind: action) — DESTRUCTIVE infobase mutation.

update_database drives EDT's IApplicationManager.update(application, FULL|INCREMENTAL)
against the project's infobase (the database, NOT the project source files). It is a
JSON tool (getResponseType() == JSON): on success the data is in r.structured
(project/applicationId/updateType/stateBefore/stateAfter/message); on failure the
server sets isError and the message is in structured.error / r.text.

============================================================================
WHY THERE IS NO "REAL UPDATE" HAPPY PATH HERE (action-tool safety)
============================================================================
A genuinely-successful call performs appManager.update(...), which MUTATES the
infobase DB. The e2e contract forbids any happy mutation that could corrupt the
committed TestConfiguration fixture / its infobase, and a real update also needs a
live, non-exclusive infobase + a valid applicationId we cannot guarantee headlessly.
So we DELIBERATELY do not call the destructive update() success path.

Instead the "happy"/contract coverage exercises the tool's REAL resolution chain
end-to-end up to — but stopping short of — the destructive update(): a REAL, open
project (TestConfiguration) plus a non-existent applicationId. That call flows
through the full execute() pipeline (arg validation -> ProjectStateChecker ready
gate -> ProjectContext.exists()/isOpen() -> Activator IApplicationManager ->
appManager.getApplication(project, id)) and is rejected at getApplication() with
"Application not found: <id>. Use get_applications ..." BEFORE update() runs. This
is mutation-sensitive: a tool that no-op'd, succeeded blindly, or skipped resolution
would NOT produce this exact, project-resolved rejection — and it touches no DB and
no files. assert_no_diff() proves the fixture source is untouched.

update_database never writes TestConfiguration source FILES (it targets the infobase),
so EVERY call in this file — accepted-shape or rejected — must leave the project tree
clean: assert_no_diff() on all paths.

============================================================================
REAL error/sentinel paths in UpdateDatabaseTool.execute() (verified vs the Java)
============================================================================
Targeting is XOR-ish: pass launchConfigurationName (preferred) OR projectName+applicationId.
  - no launchConfigurationName & no projectName
        -> "projectName is required (or pass launchConfigurationName)"
  - no launchConfigurationName & projectName but no applicationId
        -> "applicationId is required (or pass launchConfigurationName). Use get_applications or list_configurations."
  - launchConfigurationName that does not exist
        -> "Launch configuration not found: '<name>'. Use list_configurations to see what's available."
  - projectName+applicationId, project does not exist
        -> the readiness pre-check (ProjectStateChecker.buildingErrorOrNull) refuses ONLY the
           transient BUILDING state and returns null for a missing project, so the call falls
           through to updateDatabase()'s own value-naming branch (the shared
           ProjectContext.notFoundMessage):
           -> "Project not found: <name>. Use list_projects to see available projects."
  - real open project + non-existent applicationId
        -> "Application not found: <id>. Use get_applications to get valid application IDs."
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# A sentinel applicationId guaranteed not to be a real application of the fixture.
# Plain ASCII (no quotes/JSON delimiters) so it round-trips verbatim through the
# JSON error payload and can be matched by assert_error_quality(names=[...]).
BOGUS_APP_ID = "no_such_app_e2e_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# "HAPPY"/CONTRACT PATH (SAFE — real resolution, NO destructive update, NO mutation)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="update_database", kind="action")
def test_real_project_unknown_application_is_resolved_and_rejected_without_mutating():
    """SAFE end-to-end exercise of the real resolution chain that STOPS before the
    destructive update().

    A real, open project (TestConfiguration) + a non-existent applicationId passes
    every earlier guard (arg validation, ProjectStateChecker ready gate,
    ProjectContext.exists()/isOpen(), IApplicationManager lookup) and is rejected at
    appManager.getApplication() with the application-not-found error — BEFORE update()
    runs. This is the closest we can get to the success path without mutating the
    infobase, and it is fully mutation-sensitive:
      * a no-op tool / a tool that fabricated success would NOT return isError here;
      * a tool that skipped real project resolution could not have reached the
        application lookup to produce this specific, app-id-naming rejection.
    It also proves the action does not touch the project source tree.
    """
    r = call("update_database", {
        "projectName": PROJECT,
        "applicationId": BOGUS_APP_ID,
    })
    e = assert_error(r, "real project + non-existent applicationId")
    # Names the bad applicationId AND is actionable (points at get_applications).
    assert_error_quality(e, names=[BOGUS_APP_ID], suggests=["get_applications"],
                         ctx="application-not-found names the bad id and suggests get_applications")
    # Proof we reached the real application-lookup stage (not an earlier generic guard):
    # only updateDatabase()'s getApplication() branch emits "Application not found".
    assert_contains(e, "Application not found",
                    "must be rejected at the real application lookup, not an earlier guard")
    assert_no_diff("a rejected update must not touch the project source on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — targeting argument validation (XOR-ish projectName+applicationId
# vs launchConfigurationName), plus invalid targets. Every call leaves the tree clean.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="update_database", kind="action")
def test_no_targeting_args_errors_clearly():
    """Neither launchConfigurationName nor projectName: the tool cannot know what to
    update. The first guard names the missing projectName AND offers the alternative
    targeting route (launchConfigurationName) — that alternative IS the actionable
    next step, so suggests=[launchConfigurationName]."""
    r = call("update_database", {})
    e = assert_error(r, "no targeting arguments at all")
    assert_error_quality(e, names=["projectName"], suggests=["launchConfigurationName"],
                         ctx="missing target names projectName and offers launchConfigurationName")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="update_database", kind="action")
def test_project_without_application_id_errors_clearly():
    """Partial target via the projectName route: projectName present but applicationId
    omitted. The second guard must fire (NOT silently pick a default application). It
    names the missing applicationId and is actionable (get_applications / list_configurations)."""
    r = call("update_database", {
        "projectName": PROJECT,
    })
    e = assert_error(r, "projectName given, applicationId missing")
    assert_error_quality(e, names=["applicationId"], suggests=["get_applications"],
                         ctx="missing applicationId names the param and suggests get_applications")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="update_database", kind="action")
def test_application_id_without_project_errors_clearly():
    """The mirror partial target: applicationId present but projectName omitted (and no
    launchConfigurationName). Because launchConfigurationName is absent, the projectName
    guard fires FIRST — proving applicationId alone is not enough to identify a project
    and the tool does not coerce a default project."""
    r = call("update_database", {
        "applicationId": BOGUS_APP_ID,
    })
    e = assert_error(r, "applicationId given, projectName missing")
    assert_error_quality(e, names=["projectName"], suggests=["launchConfigurationName"],
                         ctx="applicationId alone is rejected for a missing projectName")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="update_database", kind="action")
def test_nonexistent_launch_configuration_errors_and_names_value():
    """Targeting via the preferred launchConfigurationName route, but the name does not
    exist. LaunchConfigUtils.findLaunchConfigByName returns null -> the tool returns
    "Launch configuration not found: '<name>'. Use list_configurations ...". Environment-
    robust: holds whether or not the fixture has any runtime-client config, because a
    bogus name never matches. Names the bad value AND points at list_configurations."""
    bad = "NoSuchLaunchConfig_e2e"
    r = call("update_database", {
        "launchConfigurationName": bad,
    })
    e = assert_error(r, "non-existent launchConfigurationName")
    assert_error_quality(e, names=[bad], suggests=["list_configurations"],
                         ctx="launch-config-not-found names the bad value and suggests list_configurations")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="update_database", kind="action")
def test_nonexistent_project_is_rejected_without_mutating():
    """Valid-shaped target (projectName + applicationId) but the project does not exist.
    The readiness pre-check (ProjectStateChecker.buildingErrorOrNull) refuses ONLY the
    transient BUILDING state and returns null for a missing project, so the call falls
    through to updateDatabase()'s own value-naming branch, which returns the shared
    ProjectContext.notFoundMessage(projectName):
    "Project not found: <name>. Use list_projects to see available projects." That
    message ECHOES the bad project name (names=[bad]) AND appends the actionable
    list_projects discovery tail (suggests=["list_projects"]). The call must be
    rejected and the real fixture untouched.
    """
    bad = "NoSuchProject_e2e_zzz"
    r = call("update_database", {
        "projectName": bad,
        "applicationId": BOGUS_APP_ID,
    })
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project surfaces the value-naming 'Project not found: <name>' with a list_projects tail")
    # Distinguish this from the application-not-found path: a non-existent project must be
    # stopped at the project gate, never reaching the application lookup.
    assert_contains(e, "Project not found",
                    "a non-existent project must hit the value-naming not-found branch")
    assert_no_diff("a rejected update must not touch the project on disk")


@e2e_test(tool="update_database", kind="action")
def test_terminate_flag_does_not_change_resolution_or_mutate():
    """The transparent session-termination flag (terminateRunningClients) must not alter
    the resolution chain or mutate anything when the target is invalid. Session termination
    only runs on confirm=true AFTER the application is resolved; a bogus applicationId is
    rejected at the application lookup BEFORE any termination, regardless of the flag, and
    the project tree stays clean. Proves the new param is inert on the rejection path."""
    r = call("update_database", {
        "projectName": PROJECT,
        "applicationId": BOGUS_APP_ID,
        "terminateRunningClients": False,
    })
    e = assert_error(r, "real project + bogus app + terminateRunningClients=false")
    assert_contains(e, "Application not found",
                    "must still be rejected at the real application lookup, flag is inert here")
    assert_no_diff("the terminate flag must not touch the project source on disk")

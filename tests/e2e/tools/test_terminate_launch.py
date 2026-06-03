"""
e2e tests for terminate_launch (kind: read).

terminate_launch kills/disconnects 1С launches started from THIS EDT instance,
selected via ONE of three mutually-exclusive modes:
  - launchConfigurationName               (single live launch by exact config name)
  - projectName + applicationId           (single live launch by project + appId)
  - all=true (requires confirm=true)      (every live EDT launch, optionally
                                           narrowed by projectName)
getResponseType() == MARKDOWN, so the body is in r.text (NOT r.structured).

ENVIRONMENT (this EDT): there is NO active debug session and TestConfiguration
has NO running infobase / launched application, so ILaunchManager.getLaunches()
yields nothing live. The SENTINEL is therefore the realistic happy contract here.

Two response families, both proven against the real server:

1. VALIDATION ERRORS (TerminateLaunchTool.validateSelection, runs BEFORE the
   launch manager is even touched) — returned via ToolResult.error(...).toJson()
   so r.is_error is TRUE. These fire regardless of whether anything is running:
     - no selection mode at all     -> "Provide exactly one of: `launchConfigurationName`,
                                         `projectName + applicationId`, or `all=true`."
     - >1 mode engaged              -> "Selection modes are mutually exclusive. ..."
     - applicationId w/o projectName-> "`applicationId` requires `projectName`."
     - projectName alone            -> "`projectName` alone is not a selection. ..."
     - applicationId + all=true     -> "`applicationId` cannot be combined with `all=true`. ..."
     - all=true w/o confirm=true    -> "Confirmation required: pass `confirm=true` ..."

2. BENIGN SUCCESS-WITH-STATUS (the no-session SENTINEL): a *valid* single-mode
   selection that matches no live launch. selectTargets() returns empty ->
   renderNothingToTerminate() returns a PLAIN Markdown string (NOT .toJson(), NOT
   ToolResult.error), so r.is_error is FALSE and r.text carries:
       # No Running Launches
       **Result:** not_found
       **Scope:** <scope>
       ...
       No live EDT launch matched the request. Use `list_configurations` to see
       what is currently running (look for entries with `running: true`).
   THIS is the clear, actionable no-session sentinel: it names the missing
   precondition (no live launch matched) and the next step (list_configurations).

DIFF: terminate_launch operates on the running infobase / Eclipse launch manager,
NEVER on the git-tracked project tree. EVERY test asserts assert_no_diff() — a
debug/runtime tool that mutated TestConfiguration source would be a bug.

We deliberately do NOT start a real infobase / debug_launch (heavy, not configured
in this EDT); the sentinel + the full validation negative matrix IS the coverage.

Fixture inventory used (TestConfiguration, English Names): the project itself
(projectName "TestConfiguration"); no running launches.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL  (no live launch in this env -> benign success-with-status)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="terminate_launch", kind="read")
def test_by_config_name_no_live_launch_returns_clear_not_found_sentinel():
    """Happy/sentinel: a VALID single-mode selection (launchConfigurationName) that
    matches no live launch. In this no-session EDT, findLiveLaunchByName returns
    null -> targets empty -> renderNothingToTerminate() returns a PLAIN Markdown
    document (is_error FALSE) whose contract is the no-session sentinel.

    This is mutation-sensitive: a broken tool that errored on a well-formed call,
    rendered the wrong document, or silently 'terminated' something would fail one
    of the specific-string asserts below. The sentinel must name the missing
    precondition (no live launch matched) AND the next step (list_configurations).
    """
    cfg = "NoSuchLaunchConfig_ZZZ_e2e"
    r = call("terminate_launch", {"launchConfigurationName": cfg})
    assert_ok(r, "valid config-name selection that matches no live launch")
    # The no-session sentinel: a not_found status document, NOT an error.
    assert_contains(r.text, "# No Running Launches",
                    "sentinel renders the No Running Launches document")
    assert_contains(r.text, "**Result:** not_found",
                    "sentinel reports the not_found result status")
    assert_contains(r.text, "No live EDT launch matched the request",
                    "sentinel names the missing precondition (no live launch matched)")
    assert_contains(r.text, "list_configurations",
                    "sentinel points at list_configurations as the next step")
    # The scope must echo the exact config name we asked for (proves it didn't
    # silently match a different / all launches).
    assert_contains(r.text, "launchConfigurationName=" + cfg,
                    "sentinel echoes the requested config name in the scope")
    assert_no_diff("terminate_launch must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_all_confirmed_no_live_launch_returns_clear_not_found_sentinel():
    """Happy/sentinel for the all=true mode: with confirm=true (so validation
    passes) and nothing running, getAllLiveLaunches returns empty -> the same
    benign not_found document. The scope line must read 'all live EDT launches'
    (NOT a single-config scope), proving the all-mode branch was taken.

    Mutation-sensitive: a tool that mass-terminated (or errored) on an empty
    launch set, or rendered a single-config scope, fails here.
    """
    r = call("terminate_launch", {"all": True, "confirm": True})
    assert_ok(r, "all=true,confirm=true with nothing running")
    assert_contains(r.text, "**Result:** not_found",
                    "all-mode with nothing running yields the not_found sentinel")
    assert_contains(r.text, "No live EDT launch matched the request",
                    "sentinel names the missing precondition")
    assert_contains(r.text, "all live EDT launches",
                    "scope reflects the all-mode branch (every live EDT launch)")
    assert_contains(r.text, "list_configurations",
                    "sentinel points at list_configurations as the next step")
    assert_no_diff("terminate_launch must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_project_plus_appid_no_live_launch_returns_not_found_sentinel():
    """Happy/sentinel for the projectName+applicationId mode: a well-formed pair
    that matches no live launch. selectTargets scopes to the project then matches
    appId; with nothing running it returns empty -> the not_found document. The
    scope must echo BOTH project= and applicationId= (proves both params were
    consumed, not ignored).
    """
    app = "app-does-not-exist-e2e"
    r = call("terminate_launch", {"projectName": PROJECT, "applicationId": app})
    assert_ok(r, "valid project+appId selection that matches no live launch")
    assert_contains(r.text, "**Result:** not_found",
                    "project+appId with nothing running yields the not_found sentinel")
    assert_contains(r.text, "No live EDT launch matched the request",
                    "sentinel names the missing precondition")
    assert_contains(r.text, "project=" + PROJECT,
                    "scope echoes the requested project")
    assert_contains(r.text, "applicationId=" + app,
                    "scope echoes the requested applicationId (param was consumed)")
    assert_no_diff("terminate_launch must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (validation fires before the launch manager is touched)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="terminate_launch", kind="read")
def test_no_selection_mode_errors_clearly():
    """Missing required selection: NONE of the three modes provided. modeCount==0
    -> a clear error telling the caller to provide exactly one mode, naming all
    three options. A broken validator that defaulted to 'all' (and mass-killed)
    would NOT produce this error — this guards the dangerous default.
    """
    r = call("terminate_launch", {})
    e = assert_error(r, "no selection mode given")
    assert_error_quality(
        e,
        names=["launchConfigurationName"],
        suggests=["projectName + applicationId", "all=true"],
        ctx="no-selection error lists exactly the three valid modes",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_mutually_exclusive_modes_error_clearly():
    """Two modes engaged at once (launchConfigurationName AND all=true): modeCount>1
    -> the mutual-exclusion error. It must name the conflict and tell the caller to
    choose ONE mode. A validator that silently picked one mode and proceeded
    (potentially terminating) would fail this assertion.
    """
    r = call("terminate_launch", {
        "launchConfigurationName": "SomeConfig_e2e",
        "all": True,
        "confirm": True,
    })
    e = assert_error(r, "launchConfigurationName + all=true both engaged")
    assert_error_quality(
        e,
        names=["mutually exclusive"],
        suggests=["launchConfigurationName", "all=true"],
        ctx="mutual-exclusion error names the conflicting modes and the fix (choose ONE)",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_application_id_without_project_errors_clearly():
    """applicationId alone (no projectName, no all): the appId mode is incomplete.
    validateSelection -> "`applicationId` requires `projectName`." It must name
    BOTH params so the caller knows the exact fix.
    """
    r = call("terminate_launch", {"applicationId": "app-orphan-e2e"})
    e = assert_error(r, "applicationId without projectName")
    assert_error_quality(
        e,
        names=["applicationId"],
        suggests=["projectName"],
        ctx="orphan applicationId error names the missing required projectName",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_project_name_alone_is_not_a_selection_errors_clearly():
    """projectName alone (no applicationId, no all): ambiguous — could mean one
    launch or every launch of the project. validateSelection rejects it with
    "`projectName` alone is not a selection." and offers BOTH disambiguations
    (add applicationId, or set all=true).
    """
    r = call("terminate_launch", {"projectName": PROJECT})
    e = assert_error(r, "projectName alone")
    assert_error_quality(
        e,
        names=["projectName"],
        suggests=["applicationId", "all=true"],
        ctx="projectName-alone error offers both disambiguations",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_application_id_with_all_errors_clearly():
    """Invalid combination: applicationId + all=true. all-mode is project-scoped at
    best, so an appId is meaningless there. validateSelection -> "`applicationId`
    cannot be combined with `all=true`." and steers to the correct mode for each
    intent. (projectName included so this engages the appId+all branch rather than
    the bare 'applicationId requires projectName' branch.)
    """
    r = call("terminate_launch", {
        "projectName": PROJECT,
        "applicationId": "app-x-e2e",
        "all": True,
        "confirm": True,
    })
    e = assert_error(r, "applicationId combined with all=true")
    assert_error_quality(
        e,
        names=["applicationId"],
        suggests=["all=true", "projectName + applicationId"],
        ctx="appId+all error explains the incompatibility and the correct alternatives",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="terminate_launch", kind="read")
def test_all_without_confirm_is_guarded():
    """Safety guard: all=true WITHOUT confirm=true must be refused (guards against
    accidental mass termination). validateSelection -> "Confirmation required: pass
    `confirm=true` ...". It must name confirm=true and point at list_configurations
    to inspect first. A tool that mass-terminated without confirmation would be a
    serious bug — this is the assertion that catches it.
    """
    r = call("terminate_launch", {"all": True})
    e = assert_error(r, "all=true without confirm=true")
    assert_error_quality(
        e,
        names=["confirm=true"],
        suggests=["list_configurations"],
        ctx="all-without-confirm guard names confirm=true and the inspect-first step",
    )
    assert_no_diff("a rejected call must not touch the project on disk")

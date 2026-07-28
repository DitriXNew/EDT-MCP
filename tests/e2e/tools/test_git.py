"""
e2e tests for git (kind: write — but see CI STRATEGY, nothing here writes).

WHAT THE TOOL DOES
------------------
git runs a whitelisted git command in a project's repository through the real git
CLI: the agent sends a shell-style command STRING ("status", "commit -m fix",
"push origin main"), the tool PARSES it, accepts only whitelisted subcommands and
executes git as an argument VECTOR (never through a shell).

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload lands in r.structured:
  {"success": true, "exitCode": 0, "command": "git status --short",
   "output": "...", "truncated": false}
  error: {"success": false, "error": "..."}

CI STRATEGY — WHY THIS FILE ASSERTS THE OPT-IN, NOT THE EXECUTION
------------------------------------------------------------------
Two constraints shape this file, and both are deliberate:

1. The tool is DISABLED BY DEFAULT (its own `git` toolset, off until the operator
   ticks it in the MCP Server preferences). A default server therefore does not
   advertise it in tools/list at all, and calling it must be refused. That opt-in
   IS the contract worth pinning here — a regression that silently enabled a tool
   able to run push/checkout would be the worst possible failure of this feature.

2. The CI fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP
   plugin's own git working tree — it has no repository of its own, so the tool's
   git-dir discovery resolves the PLUGIN repo. Running a WRITE command there would
   mutate the checkout the suite itself runs from. Even the read commands would
   assert against whatever branch/state CI happens to be on.

So: the default-off contract is asserted unconditionally, and the execution paths
are gated behind EDT_MCP_GIT_E2E=1 (attended, against a throwaway stand where the
operator has enabled the tool), where they run READ-ONLY commands only.
"""

import os

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    E2ESkip,
    _post,
    PROJECT,
)

# Opt-in gate for the ATTENDED part: set EDT_MCP_GIT_E2E=1 on a stand where the git
# tool has been enabled in the preferences. A normal headless run skips those.
GIT_ENABLED = os.environ.get("EDT_MCP_GIT_E2E", "").strip() not in ("", "0", "false", "no")

NONEXISTENT_PROJECT = "NoSuchProject_git_zzz"


def _advertised_tools():
    raw = _post("tools/list", {})
    return set(t["name"] for t in (raw.get("result", {}).get("tools", []) or []))


def _requires_enabled_git():
    if not GIT_ENABLED:
        raise E2ESkip("git is disabled by default; set EDT_MCP_GIT_E2E=1 on a stand "
                      "where the operator enabled it to run the execution paths")


# ──────────────────────────────────────────────────────────────────────────────
# THE OPT-IN CONTRACT (runs everywhere, including CI)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="git", kind="read")
def test_disabled_by_default_is_not_advertised_and_is_refused():
    """A default server must NOT advertise git, and must refuse to run it.

    Mutation check: a regression that registered the tool as enabled (a wrong
    default, a preset or "Restore Defaults" clearing the disabled set) would make
    it appear in tools/list and answer the call — both asserted against here.
    """
    if GIT_ENABLED:
        raise E2ESkip("this stand has git ENABLED on purpose; the default-off contract "
                      "is asserted on a default server")

    if "git" in _advertised_tools():
        raise AssertionError(
            "git must be DISABLED by default: it appeared in tools/list. A preset or a "
            "defaults reset that clears the disabled set would do this.")

    # The shared disabled-tool path answers with a TEXT result (not isError) - see
    # McpProtocolHandler: a tool the user switched off is a configuration state, not a tool
    # failure. What matters is that nothing ran and the answer says why.
    r = call("git", {"projectName": PROJECT, "command": "status --short"})
    expected = "Tool 'git' is disabled by the user"
    if expected not in (r.text or ""):
        raise AssertionError(
            "a disabled tool must answer with the shared disabled-path message %r, got: %r"
            % (expected, (r.text or "")[:300]))
    if r.structured:
        raise AssertionError(
            "the disabled path carries no structured payload - anything here means the tool RAN: %r"
            % r.structured)

    assert_no_diff("a refused call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (attended: needs the tool enabled)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="git", kind="read")
def test_missing_arguments_error_clearly():
    """The shared required-arg guard fires for both parameters and names them."""
    _requires_enabled_git()

    err = assert_error(call("git", {"command": "status"}), "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="a missing project must name the parameter and where to look")

    err = assert_error(call("git", {"projectName": PROJECT}), "missing command")
    assert_error_quality(err, names=["command"],
                         ctx="a missing command must name the parameter")

    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="git", kind="read")
def test_unknown_project_errors_and_names_it():
    """An unknown project fails on resolution, before anything is executed."""
    _requires_enabled_git()

    err = assert_error(call("git", {"projectName": NONEXISTENT_PROJECT, "command": "status"}),
                       "unknown project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT],
                         ctx="the error must quote the project that was not found")

    assert_no_diff("a failed resolution must not touch the project on disk")


@e2e_test(tool="git", kind="read")
def test_non_whitelisted_subcommand_is_refused():
    """Only whitelisted subcommands run; the refusal names the offending one."""
    _requires_enabled_git()

    err = assert_error(call("git", {"projectName": PROJECT, "command": "gc --aggressive"}),
                       "non-whitelisted subcommand")
    assert_error_quality(err, names=["gc"],
                         ctx="the refusal must quote the subcommand it rejected")

    assert_no_diff("a refused command must not touch the project on disk")


@e2e_test(tool="git", kind="read")
def test_dangerous_option_is_refused():
    """An option that could make git run a program is refused wherever it appears."""
    _requires_enabled_git()

    err = assert_error(call("git", {"projectName": PROJECT,
                                    "command": "fetch --upload-pack=/tmp/payload origin"}),
                       "arbitrary-program option")
    assert_error_quality(err, names=["--upload-pack"],
                         ctx="the refusal must quote the option it rejected")

    assert_no_diff("a refused command must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (attended, READ-ONLY commands only — see module docstring)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="git", kind="read")
def test_status_runs_and_reports_the_real_exit_code():
    """A read-only command reaches the real git CLI and comes back structured.

    Structural invariants only: the repository state on a stand is whatever it is,
    so this asserts the response CONTRACT (exit code, echoed command, an output
    field) rather than any particular repository content.
    """
    _requires_enabled_git()

    r = call("git", {"projectName": PROJECT, "command": "status --short"})
    assert_ok(r, "git status on an enabled stand")

    data = r.structured or {}
    if data.get("exitCode") != 0:
        raise AssertionError("a clean 'status --short' must exit 0, got %r" % data.get("exitCode"))
    # The tool records the command it actually RAN, i.e. the argv with the git executable in
    # front ("git status --short") - that is what its output schema documents.
    if data.get("command") != "git status --short":
        raise AssertionError("the result must echo the command it ran, got %r" % data.get("command"))
    if "output" not in data:
        raise AssertionError("the result must carry git's own output, got keys %r" % sorted(data))

    assert_no_diff("'status' must not touch the project on disk")

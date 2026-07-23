"""
e2e tests for get_git_status (kind: read).

WHAT THE TOOL DOES
------------------
get_git_status reports a project's git working-tree status: the current branch
(detached HEAD flagged), whether the tree is clean, and the porcelain change sets
(added / changed / modified / missing / removed / untracked / conflicting).

RESPONSE SHAPE
--------------
MARKDOWN tool (getResponseType() == MARKDOWN); payload lands in r.text:
  "## Git Status: <project>\\n\\n**Current:** <branch>\\n\\n**Clean:** Yes|No\\n\\n"
  clean:  "*Working tree clean - nothing to commit.*"
  dirty:  "**Changed entries:** N\\n\\n| Path | State |\\n| --- | --- |\\n| <path> | <state> |..."
  error:  {"success": false, "error": "..."}

CI STRATEGY
-----------
The happy path IS safe to run in CI (read-only, never mutates anything) -- BUT the
CI fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's
OWN git working tree (the tool's git-dir-discovery fallback resolves the PLUGIN
repo, same as list_git_branches / create_git_branch). We therefore assert only
STRUCTURAL invariants on the happy path -- the header, a non-blank Current line, and
a Clean: Yes|No line -- NEVER a specific clean/dirty verdict, which depends on
whatever the plugin working tree happens to look like when the suite runs.

The "an edit shows up in the untracked set" case is made deterministic WITHOUT
pinning to the ambient repo state: it CREATES a uniquely-named untracked probe file
under the project directory, asserts it appears in the untracked set, then removes
it (the harness also `clean -fd`s PROJECT_REL before the next test, so a leftover
probe cannot escape). This proves the change sets are populated from the real
working tree, not stubbed -- a broken tool that returned an empty/no-op body fails.

Every non-mutating path asserts assert_no_diff(): a read tool must not touch the
fixture (the probe test cleans up its own file first, then asserts no diff).
"""

import os

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
    PROJECT_REL,
    PROJECT_DIR,
)

NONEXISTENT_PROJECT = "NoSuchProject_ggs_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (structural invariants ONLY -- see module docstring)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_git_status", kind="read")
def test_reports_status_with_structural_invariants_only():
    """Read-only happy path against the real (plugin-repo-backed) fixture. Asserts
    the report shape -- header, a non-blank Current line, and a Clean: verdict --
    never a specific clean/dirty value (see module docstring).

    Mutation check: a broken tool that returned an empty/no-op body would fail the
    header / current-line / clean-line assertions below.
    """
    r = call("get_git_status", {"projectName": PROJECT})
    assert_ok(r, "get_git_status happy path")

    assert_contains(r.text, "## Git Status: " + PROJECT, "report header names the project")
    assert_contains(r.text, "**Current:**", "current branch/HEAD line is present")
    assert_contains(r.text, "**Clean:**", "clean verdict line is present")

    idx = r.text.index("**Current:**")
    line_end = r.text.index("\n", idx)
    current_value = r.text[idx + len("**Current:**"):line_end].strip()
    if not current_value:
        raise AssertionError(
            "Current: line must name a branch or detached HEAD, got blank: %r" % r.text[idx:idx + 80]
        )
    if "**Clean:** Yes" not in r.text and "**Clean:** No" not in r.text:
        raise AssertionError("Clean line must be exactly Yes or No: %r" % r.text[:200])

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_git_status", kind="read")
def test_untracked_probe_file_appears_in_the_untracked_set():
    """Create a uniquely-named untracked probe file under the project dir, then assert
    get_git_status places it in the `untracked` set (repo-root-relative path). This is
    deterministic regardless of the ambient plugin-repo state, and is strong anti-cheat:
    a tool that stubbed/emptied the change sets could not surface a file that was created
    a millisecond earlier. Cleans the probe up before asserting no residual diff."""
    probe_name = "zzz_e2e_get_git_status_probe.txt"
    probe_path = os.path.join(PROJECT_DIR, probe_name)
    # Repository-root-relative path, forward slashes (JGit Status reports paths this way).
    probe_rel = PROJECT_REL + "/" + probe_name
    try:
        with open(probe_path, "w", encoding="utf-8") as f:
            f.write("probe for get_git_status e2e\n")

        r = call("get_git_status", {"projectName": PROJECT})
        assert_ok(r, "get_git_status with an untracked probe file")

        assert_contains(r.text, "**Clean:** No", "an untracked probe file makes the tree dirty")
        assert_contains(r.text, "| Path | State |", "the change table is rendered when dirty")
        # The exact table row proves the probe path is classified `untracked`, not merely
        # that its name appears somewhere in the report.
        assert_contains(r.text, "| " + probe_rel + " | untracked |",
                        "the probe file must appear in the untracked set with its repo-relative path")
    finally:
        if os.path.exists(probe_path):
            os.remove(probe_path)

    assert_no_diff("the probe file was removed; the fixture must be clean again")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_git_status", kind="read")
def test_missing_projectname_errors_with_hint():
    """Missing the required projectName -> the shared required-arg guard fires,
    names the parameter, and steers to list_projects."""
    r = call("get_git_status", {})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="get_git_status", kind="read")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("get_git_status", {"projectName": NONEXISTENT_PROJECT})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")

"""
e2e tests for list_git_branches (kind: read).

WHAT THE TOOL DOES
------------------
list_git_branches lists a project's git branches (local + remote-tracking, current
branch marked, detached HEAD flagged) and a best-effort "Application Bindings"
section showing which 1C infobase(s) each branch context is bound to.

RESPONSE SHAPE
--------------
MARKDOWN tool (getResponseType() == MARKDOWN); payload lands in r.text:
  "## Git Branches: <project>\\n\\n**Current:** <branch>\\n\\n"
  "| Branch | Type | Current |\\n|--------|------|---------|\\n..."
  "\\n### Application Bindings\\n\\n..."
  error: {"success": false, "error": "..."}

CI STRATEGY
-----------
The happy path IS safe to run in CI (read-only, never mutates anything) — BUT the
CI fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's
own git working tree (it has no separate git repo of its own, so the tool's
git-dir-discovery fallback resolves the PLUGIN repo). We therefore assert only
STRUCTURAL invariants (a current branch/HEAD line is present, the branch table is
non-empty) — NEVER a specific branch name, which would pin CI to whatever branch
happens to be checked out when the suite runs and break on every rebase/merge.

list_git_branches is read-only and never writes the project tree: assert_no_diff()
on every path here.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    requires_live_infobase,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_lgb_zzz"

BINDINGS_HEADER = "### Application Bindings"

# #684: a branch that does not exist in the plugin repository - a binding needs no real branch.
_PROBE_BRANCH = "e2e-684-binding-probe"

_INFOBASE_TYPE = "com.e1c.g5.dt.applications.type.infobase"


def _bindings_section(text):
    """The Application Bindings section of a list_git_branches report (header to end)."""
    assert_contains(text, BINDINGS_HEADER, "bindings section is present")
    return text[text.index(BINDINGS_HEADER):]


def _binding_rows(section, first_cell):
    """Table rows of the bindings section whose first cell starts with `first_cell`."""
    return [line for line in section.splitlines() if line.startswith("| " + first_cell)]


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (structural invariants ONLY — see module docstring)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_git_branches", kind="read")
def test_lists_branches_with_structural_invariants_only():
    """Read-only happy path against the real (plugin-repo-backed) fixture. Asserts
    the report shape and that SOME branch/HEAD state is reported, never a specific
    branch name (see module docstring for why).

    Mutation check: a broken tool that returned an empty/no-op body would fail the
    header/current-line/table-header/bindings-section assertions below; a tool that
    left the "Current:" value blank (e.g. repo.getBranch() silently swallowed) would
    fail the non-empty-current-value check.
    """
    r = call("list_git_branches", {"projectName": PROJECT})
    assert_ok(r, "list_git_branches happy path")

    assert_contains(r.text, "## Git Branches: " + PROJECT, "report header names the project")
    assert_contains(r.text, "**Current:**", "current branch/HEAD line is present")
    assert_contains(r.text, "| Branch | Type | Current |", "branch table header")
    assert_contains(r.text, "### Application Bindings", "bindings section is present")

    idx = r.text.index("**Current:**")
    line_end = r.text.index("\n", idx)
    current_value = r.text[idx + len("**Current:**"):line_end].strip()
    if not current_value:
        raise AssertionError(
            "Current: line must name a branch or detached HEAD, got blank: %r" % r.text[idx:idx + 80]
        )

    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_git_branches", kind="read")
def test_missing_projectname_errors_with_hint():
    """Missing the required projectName -> the shared required-arg guard fires,
    names the parameter, and steers to list_projects."""
    r = call("list_git_branches", {})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="list_git_branches", kind="read")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("list_git_branches", {"projectName": NONEXISTENT_PROJECT})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# #684: BRANCH CONTEXTS ARE SHOWN BY BRANCH NAME (EDT keys them by refs/heads/<branch>)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_git_branches", kind="read")
def test_bindings_section_never_prints_a_raw_heads_prefix():
    """Format pin, CI-safe (read-only, needs no infobase): EDT stores a branch context as
    refs/heads/<branch>; the report must name the branch, never print the raw ref.

    Mutation check: a renderer that prints the context string as is would show
    'refs/heads/...' for every binding EDT or set_branch_infobase recorded. On a stand with no
    binding at all the section reads 'No application bindings', which this pin also accepts -
    the live round trip below covers a recorded binding."""
    r = call("list_git_branches", {"projectName": PROJECT})
    assert_ok(r, "list_git_branches for the bindings format pin")
    section = _bindings_section(r.text)
    if "refs/heads/" in section:
        raise AssertionError(
            "the bindings section must show a branch context by its branch name, not the raw "
            "refs/heads/ ref: %r" % section[:600])
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_git_branches", kind="read")
def test_live_binding_round_trip_is_keyed_by_the_branch_ref():
    """#684 live round trip (Tier-2 / live-gated): set_branch_infobase attach stores the binding
    under refs/heads/<branch>, list_git_branches shows it as the plain branch name with no legacy
    marker, and detach removes it again.

    The detach goes through the FULL ref form ('refs/heads/<branch>') of the branch the attach named
    by its short name: both forms name the same branch, so it must find the binding under the key
    the attach wrote.

    Mutation check: a set_branch_infobase that went back to the short-name key would make the row
    carry the 'legacy short key' marker, and the full-ref detach would be refused as "not bound"
    (the pre-#684 jar fails exactly there: its attach wrote the short key); a renderer that printed
    the raw context would show 'refs/heads/e2e-684-binding-probe' instead of the branch name; a
    detach that missed the key would leave the row behind.

    REQUIRES: EDT_MCP_LIVE_INFOBASE=1 (a live EDT stand whose TestConfiguration has a registered
    infobase application). The binding is recorded in EDT's workspace metadata, which the git
    fixture reset does not cover, so it is always detached in `finally`."""
    requires_live_infobase("set_branch_infobase -> list_git_branches -> detach round trip")

    r_apps = call("get_applications", {"projectName": PROJECT})
    assert_ok(r_apps, "get_applications for the binding round trip")
    apps = (r_apps.structured or {}).get("applications", [])
    infobases = [a for a in apps if a.get("type") == _INFOBASE_TYPE and a.get("id")]
    assert infobases, "the live stand needs an infobase application: %r" % apps
    app_id = infobases[0]["id"]
    detach = {"projectName": PROJECT, "branch": _PROBE_BRANCH, "applicationId": app_id,
              "action": "detach"}
    detach_by_ref = dict(detach, branch="refs/heads/" + _PROBE_BRANCH)
    try:
        r_attach = call("set_branch_infobase",
                        {"projectName": PROJECT, "branch": _PROBE_BRANCH, "applicationId": app_id})
        assert_ok(r_attach, "attach to the probe branch")

        r_list = call("list_git_branches", {"projectName": PROJECT})
        assert_ok(r_list, "list_git_branches after attach")
        section = _bindings_section(r_list.text)
        rows = _binding_rows(section, _PROBE_BRANCH)
        assert rows, "the binding must be listed under the branch name: %r" % section[:800]
        assert any(row.startswith("| " + _PROBE_BRANCH + " |") for row in rows), \
            "the probe branch must have a plain branch row (no marker): %r" % rows
        assert not any("legacy short key" in row for row in rows), \
            "the binding must not be a legacy short-name key: %r" % rows
        assert "refs/heads/" not in section, \
            "the raw ref must not be printed: %r" % section[:800]

        r_detach = call("set_branch_infobase", detach_by_ref)
        assert_ok(r_detach, "detach through the full ref removes the short-name attach's binding")
        message = (r_detach.structured or {}).get("message") or ""
        assert "legacy" not in message, \
            "the attach above wrote the full-ref key, so detach must remove THAT key: %r" % message

        r_after = call("list_git_branches", {"projectName": PROJECT})
        assert_ok(r_after, "list_git_branches after detach")
        # EDT may keep an emptied context listed; it then reads '(none)'.
        after_rows = _binding_rows(_bindings_section(r_after.text), _PROBE_BRANCH + " |")
        assert all("| (none) |" in row for row in after_rows), \
            "the binding must be gone after detach: %r" % after_rows
    finally:
        call("set_branch_infobase", detach)

    assert_no_diff("the round trip must not touch the committed fixture (TestConfiguration)")

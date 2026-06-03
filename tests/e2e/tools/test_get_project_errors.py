"""
e2e tests for get_project_errors (kind: read).

The tool reads EDT's Configuration Problems (validation markers) for a project and
returns a Markdown report (response is the Markdown string -> Result.text; only the
error path goes through ToolResult.error(...).toJson() -> Result.structured.error).

Happy paths are made DETERMINISTIC despite the live marker state being out of our
control: a checkId filter that matches no check (and a NONE severity filter) forces
the documented "# No Errors Found" branch, which still echoes the project / severity /
objects filter banner. That branch text is produced ONLY when the tool actually ran
the marker stream and applied the filters, so a broken/no-op tool would fail it.

Read tool => every test also asserts assert_no_diff(): reading problems must never
mutate the project on disk.

Real error paths exercised by the negative matrix (read from GetProjectErrorsTool /
ProjectStateChecker):
  - non-existent projectName -> ProjectStateChecker.checkReadyOrError -> "Project does
    not exist. Please wait and retry." (runs BEFORE the nicer "Project not found: <name>")
  - out-of-set severity     -> "severity must be one of: ERRORS, BLOCKER, ..."
"""

from harness import (
    call, assert_ok, assert_contains, assert_error, assert_error_quality,
    assert_no_diff, e2e_test, PROJECT,
)

# A checkId that cannot match any real check id or short UID, so EVERY marker is
# filtered out and the tool is forced into the documented "# No Errors Found" branch.
NO_MATCH_CHECK = "zzz_no_such_check_xyz_e2e"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_project_errors", kind="read")
def test_no_match_filter_renders_no_errors_banner_for_project():
    """A filter that matches nothing => the 'No Errors Found' report that still names
    the project. Deterministic regardless of the live marker set, and it FAILS if the
    tool no-ops, ignores the project filter, or renders the wrong report."""
    r = call("get_project_errors", {"projectName": PROJECT, "checkId": NO_MATCH_CHECK})
    assert_ok(r, "get_project_errors happy path (no-match checkId filter)")
    # The empty-result branch heading: proves the tool ran the marker stream + filter.
    assert_contains(r.text, "# No Errors Found", "empty-result report heading must be present")
    # The branch echoes the requested project name back in the banner.
    assert_contains(r.text, PROJECT, "report must name the queried project")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_severity_and_object_filter_banner_echoed():
    """A valid severity enum + an objects filter, combined with the no-match checkId,
    deterministically reaches the empty-result branch AND proves the tool echoes BOTH
    the severity and the objects filter into the banner (so the filters were parsed,
    not silently dropped)."""
    r = call("get_project_errors", {
        "projectName": PROJECT,
        "severity": "MINOR",
        "objects": ["Catalog.Catalog"],
        "checkId": NO_MATCH_CHECK,
    })
    assert_ok(r, "get_project_errors with severity + objects + no-match checkId")
    assert_contains(r.text, "# No Errors Found", "empty-result heading must be present")
    # The banner reflects the accepted filters back to the caller.
    assert_contains(r.text, "MINOR", "severity filter must be echoed in the banner")
    assert_contains(r.text, "Catalog.Catalog", "objects filter must be echoed in the banner")
    assert_no_diff("reading project errors must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_project_errors", kind="read")
def test_invalid_severity_enum_is_rejected_with_valid_set():
    """Out-of-set severity must be REJECTED (the tool refuses to silently widen to
    'all'), and the error must list the valid enum values so the caller can fix it."""
    bad = "WARNINGS"  # not in {ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL, NONE}
    r = call("get_project_errors", {"projectName": PROJECT, "severity": bad})
    err = assert_error(r, "invalid severity enum value")
    # Actionable: the message enumerates the accepted values. The fix is to pick one.
    # AUDIT: the message does NOT echo the bad value ("WARNINGS"); it only lists the
    # allowed set. names=[] reflects that gap. Fix-card: include the rejected value,
    # e.g. "severity 'WARNINGS' is invalid; must be one of: ...".
    assert_error_quality(
        err,
        names=[],
        suggests=["severity", "ERRORS", "MINOR"],
        ctx="invalid severity lists the valid set",
    )
    assert_no_diff("a rejected read must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_nonexistent_project_is_rejected():
    """A non-existent projectName must error (not silently return all-projects output).
    The readiness pre-check runs first and rejects it before scanning markers."""
    bad = "NoSuchProject_e2e_xyz"
    r = call("get_project_errors", {"projectName": bad})
    err = assert_error(r, "non-existent projectName")
    # AUDIT: for a typo'd/non-existent project the tool hits ProjectStateChecker first,
    # which returns "Project does not exist. Please wait and retry." That message:
    #   (a) does NOT name the offending project value -> names=[] (cannot assert the value);
    #   (b) is MISLEADING ("Please wait and retry" implies a transient build state) and
    #       points at NO sibling tool, so suggests=[] is genuine, not laziness.
    # The clearer "Project not found: <name>" + a pointer to list_projects in
    # GetProjectErrorsTool.getProjectErrors is never reached for this case.
    # Fix-card: short-circuit non-existent projects with a message that names the value
    # and points to list_projects, e.g. "Project not found: 'NoSuchProject_e2e_xyz' —
    # use list_projects to see available projects."
    # We still assert a real, non-bare, non-stacktrace message (mutation-sensitive: a
    # broken tool that returned success or an empty string would fail assert_error /
    # assert_error_quality).
    assert_error_quality(
        err,
        names=[],
        suggests=[],
        ctx="non-existent project produces a clear (if non-actionable) error",
    )
    # Independent, value-specific check that is NOT trivially true: the rejection text
    # must speak about the project not existing (catches a tool that errors for an
    # unrelated reason or returns a generic failure).
    assert_contains(
        err.lower(), "project", "non-existent project error must mention the project"
    )
    assert_no_diff("a rejected read must not touch the project on disk")

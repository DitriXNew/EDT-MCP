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
  - non-existent projectName -> ProjectStateChecker.buildingErrorOrNull guards only the
    transient BUILDING state, so it falls through to "Project not found: <name>" (names the value)
  - out-of-set severity     -> "severity must be one of: ERRORS, BLOCKER, ..."
"""

from harness import (
    call, assert_ok, assert_contains, assert_not_contains, assert_error,
    assert_error_quality, assert_no_diff, e2e_test, PROJECT, _fail,
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


@e2e_test(tool="get_project_errors", kind="read")
def test_concise_is_default_and_leaner_than_detailed():
    """responseFormat contract: the DEFAULT (concise) output is never larger than the
    explicit detailed output, and concise never carries the verbose 'Has docs' column.

    Determinism: this runs an unfiltered scan whose marker count we cannot control, so the
    invariants are written to hold for BOTH a populated and an empty marker set:
      - the default call (omitting responseFormat) is byte-identical to an explicit
        concise call (proves concise is the default);
      - detailed is never shorter than concise (the only difference is an extra column);
      - concise never contains the 'Has docs' column header.
    When a table is actually rendered ('# Configuration Problems'), we additionally prove
    the real token saving: detailed has the 'Has docs' column and concise omits it."""
    default = call("get_project_errors", {"projectName": PROJECT})
    concise = call("get_project_errors", {"projectName": PROJECT, "responseFormat": "concise"})
    detailed = call("get_project_errors", {"projectName": PROJECT, "responseFormat": "detailed"})
    assert_ok(default, "default (concise) scan")
    assert_ok(concise, "explicit concise scan")
    assert_ok(detailed, "detailed scan")

    # Omitting responseFormat must behave exactly like concise (concise is the default).
    if default.text != concise.text:
        _fail("default output must equal explicit concise output (concise is the default)")

    # The lean default never carries the secondary 'Has docs' column; detailed reintroduces it.
    assert_not_contains(concise.text, "Has docs", "concise must omit the 'Has docs' column")

    # Detailed is never smaller than concise: the only delta is an extra column.
    if len(detailed.text) < len(concise.text):
        _fail("detailed output must be >= concise output in length")

    # When real problems are present a table is rendered: prove the genuine token saving.
    if "# Configuration Problems" in detailed.text:
        assert_contains(detailed.text, "Has docs", "detailed must include the 'Has docs' column")
        # Same query, leaner output -> concise must be strictly smaller here.
        if len(concise.text) >= len(detailed.text):
            _fail("with problems present, concise must be strictly leaner than detailed")
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
    # Actionable: the message echoes the rejected value AND enumerates the accepted
    # values. The fix is to pick one of the listed values.
    assert_error_quality(
        err,
        names=["WARNINGS"],
        suggests=["severity", "ERRORS", "MINOR"],
        ctx="invalid severity echoes the bad value and lists the valid set",
    )
    assert_no_diff("a rejected read must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_nonexistent_project_is_rejected():
    """A non-existent projectName must error (not silently return all-projects output).
    The BUILDING-only readiness pre-check lets it fall through to the value-naming
    "Project not found: <name>" rejection."""
    bad = "NoSuchProject_e2e_xyz"
    r = call("get_project_errors", {"projectName": bad})
    err = assert_error(r, "non-existent projectName")
    # execute() now guards only the transient BUILDING state (buildingErrorOrNull), so a
    # non-existent project reaches getProjectErrors' "Project not found: <name>" branch,
    # which NAMES the bad value -- no longer the misleading "Project does not exist.
    # Please wait and retry." (which implied a transient build a retry would resolve).
    # The shared ProjectContext.notFoundMessage now appends the discovery tail, so the
    # error both names the value AND points the caller at list_projects.
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_projects"],
        ctx="non-existent project: names the bad value and points at list_projects",
    )
    # Independent, value-specific check that is NOT trivially true: the rejection text
    # must speak about the project not existing (catches a tool that errors for an
    # unrelated reason or returns a generic failure).
    assert_contains(
        err.lower(), "project", "non-existent project error must mention the project"
    )
    assert_no_diff("a rejected read must not touch the project on disk")

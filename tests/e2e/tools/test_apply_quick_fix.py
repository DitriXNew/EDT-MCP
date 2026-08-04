"""
e2e tests for apply_quick_fix.

Applies EDT's official quick-fix to one validation marker, addressed by the SAME
locator get_project_errors prints (Check code + optional Module path + Line) — EDT
markers carry no stable per-marker id. JSON-response tool: success lands in
Result.structured, errors in structured.error.

The happy path is DISCOVERY-based: it scans the extension project's detailed problems
for a row whose 'Fix registered' column says 'yes' (the doc-comment checks reliably
expose fixes), reads that row's Check code / Module path / Line, and applies it; a
locator that still matches several markers (or a multi-variant fix) is disambiguated with
index / variant. It then RE-SCANS get_project_errors and requires the marker COUNT for
that locator to have dropped - proving the fix actually changed something, not just
trusting the tool's self-reported success=true (a stub that always returned success
without doing anything would otherwise still pass). It then cleans up after itself
(revert + clean_project) so the run's tree stays clean. If the live fixture exposes no
auto-fixable marker the happy test SKIPS - a known, accepted tradeoff of discovery-based
scanning (there is no fixture-independent way to GUARANTEE a fixable marker without
seeding one, which this suite does not do here).

The table parsing itself is NOT discovery-dependent: it goes through the shared
escape-aware harness.split_markdown_row, whose contract is pinned deterministically by
test_markdown_table.py - so a parser regression fails there even when this test skips.

Negative matrix (real error paths from ApplyQuickFixTool):
  - unknown checkId           -> "No marker matches check '<id>' ... run get_project_errors"
  - missing required checkId  -> requireArguments rejects it, naming the parameter
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality, e2e_test,
    PROJECT, TESTS_PROJECT, E2ESkip, reset_all_fixtures, wait_for_project_ready,
    split_markdown_row,
)


def _scan_detailed_rows(project):
    """Yields each detailed-table row of get_project_errors(project) as a 7-cell dict:
    desc, loc, modulePath, line, checkId, fix, hasDocs.

    Rows are split with the shared escape-aware parser (harness.split_markdown_row): a
    literal '|' inside a cell is escaped as '\\|' by production, and a naive split would
    cut such a row into 8+ cells, so the exact-7 filter below would silently DROP a real,
    fixable marker. The parser's own contract is pinned by test_markdown_table.py.
    """
    r = call("get_project_errors", {"projectName": project, "responseFormat": "detailed"})
    assert_ok(r, "get_project_errors detailed scan")
    for line in (r.text or "").splitlines():
        cells = split_markdown_row(line)
        if len(cells) != 7 or cells[0].lower() == "description":  # skip the header row too
            continue
        desc, loc, module, ln, check, fix, docs = cells
        yield {"desc": desc, "loc": loc, "modulePath": module, "line": ln,
               "checkId": check.strip("`"), "fix": fix, "hasDocs": docs}


def _find_fixable(project):
    """Scan a project's DETAILED problems for rows flagged fixable (Fix registered == 'yes').
    Returns a list of {checkId, modulePath, line}."""
    return [{"checkId": row["checkId"], "modulePath": row["modulePath"], "line": row["line"]}
            for row in _scan_detailed_rows(project) if row["fix"].lower() == "yes"]


def _count_markers(project, check_id, module_path):
    """How many markers get_project_errors(project) reports for this exact
    (checkId, modulePath) pair.

    A COUNT, not a boolean: the locator can legitimately match SEVERAL markers (that is
    exactly the collision this tool disambiguates with `index`), so "is any still there?"
    is the wrong question after fixing ONE of them - a surviving sibling would read as
    "the fix did nothing". Comparing the count before/after proves one marker went away
    while tolerating its siblings. The line is deliberately not part of the key: a fix can
    shift the lines below it (e.g. inserting a doc-comment stub), so pinning the old line
    number would be fragile in the other direction.
    """
    return sum(1 for row in _scan_detailed_rows(project)
               if row["checkId"] == check_id and row["modulePath"] == module_path)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (discovery-based, self-cleaning)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="apply_quick_fix", kind="write-metadata")
def test_apply_a_discovered_fixable_marker():
    """Discover a fixable marker (Fix=yes), apply its quick-fix, and confirm the marker
    ACTUALLY disappeared from a fresh get_project_errors scan - not just that the tool's own
    response claimed success. Locator collisions (several markers / fix variants) are
    resolved with index / variant. Self-cleans the extension fixture afterwards."""
    candidates = _find_fixable(TESTS_PROJECT)
    if not candidates:
        raise E2ESkip("no auto-fixable marker in the extension fixture (env-dependent)")
    try:
        c = candidates[0]
        # Baseline for the effect assertion below, captured BEFORE the mutation.
        before = _count_markers(TESTS_PROJECT, c["checkId"], c["modulePath"])
        args = {"projectName": TESTS_PROJECT, "checkId": c["checkId"],
                "modulePath": c["modulePath"]}
        if c["line"]:
            args["line"] = int(c["line"])
        r = call("apply_quick_fix", args)
        # Disambiguate if several markers share the locator, or the fix has several variants.
        if r.is_error and "index=" in (r.error_text() or ""):
            args["index"] = 1
            r = call("apply_quick_fix", args)
        if r.is_error and "variant=" in (r.error_text() or ""):
            args["variant"] = 1
            r = call("apply_quick_fix", args)

        assert_ok(r, "apply_quick_fix on a discovered fixable marker")
        assert r.structured is not None, "apply_quick_fix must return structured content"
        if not r.structured.get("success"):
            raise AssertionError("apply_quick_fix structured.success must be true: %r" % r.structured)
        if not r.structured.get("appliedVariant"):
            raise AssertionError("apply_quick_fix must name the applied fix variant: %r" % r.structured)

        # Anti-cheat: a self-reported success alone does not prove the fix did anything (a
        # stub that always returns success=true without touching the source would pass
        # everything above). Re-scan after the model revalidates and require the marker
        # COUNT for this locator to have DROPPED.
        #
        # Deliberately a count comparison, not "no marker with this locator survives": the
        # locator can match several markers (the very collision resolved with `index`
        # above), and this call fixed exactly ONE of them - demanding the whole locator be
        # clean would fail the test even though the tool worked correctly.
        wait_for_project_ready()
        after = _count_markers(TESTS_PROJECT, c["checkId"], c["modulePath"])
        if after >= before:
            raise AssertionError(
                "applying the quick-fix for '%s' in %s must REMOVE a marker, but the count "
                "did not drop (before=%d, after=%d) - success:true alone does not prove the "
                "fix changed anything" % (c["checkId"], c["modulePath"], before, after))
    finally:
        # The fix mutated the extension (model + disk); the per-test reset only covers the
        # BASE, so revert + re-sync the extension here to keep the whole tree clean.
        reset_all_fixtures()
        r_clean = call("clean_project", {"projectName": TESTS_PROJECT})
        assert_ok(r_clean, "clean_project after apply_quick_fix must succeed, "
                  "or the extension model/tree stays polluted for later tests")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="apply_quick_fix", kind="write")
def test_unknown_check_is_rejected():
    """A checkId that matches no marker must error, naming the bad value and pointing the
    caller back at get_project_errors."""
    bad = "no_such_check_id_e2e_xyz"
    r = call("apply_quick_fix", {"projectName": PROJECT, "checkId": bad})
    err = assert_error(r, "unknown checkId")
    assert_error_quality(
        err,
        names=[bad],
        suggests=["get_project_errors"],
        ctx="unknown checkId names the bad value and points at get_project_errors",
    )


@e2e_test(tool="apply_quick_fix", kind="write")
def test_missing_check_id_is_rejected():
    """checkId is required: omitting it must be rejected with a message naming the missing
    parameter (not a generic failure or a silent no-op)."""
    r = call("apply_quick_fix", {"projectName": PROJECT})
    err = assert_error(r, "missing required checkId")
    assert_error_quality(
        err,
        names=["checkId"],
        ctx="missing checkId is rejected naming the parameter",
    )


@e2e_test(tool="apply_quick_fix", kind="write")
def test_nonexistent_project_is_rejected():
    """A non-existent projectName must error and name the bad value."""
    bad = "NoSuchProject_e2e_qfix"
    r = call("apply_quick_fix", {"projectName": bad, "checkId": "anything"})
    err = assert_error(r, "non-existent projectName")
    assert_error_quality(
        err,
        names=[bad],
        ctx="non-existent project names the bad value",
    )

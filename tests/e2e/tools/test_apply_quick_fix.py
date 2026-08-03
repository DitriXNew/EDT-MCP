"""
e2e tests for apply_quick_fix.

Applies EDT's official quick-fix to one validation marker, addressed by the SAME
locator get_project_errors prints (Check code + optional Module path + Line) — EDT
markers carry no stable per-marker id. JSON-response tool: success lands in
Result.structured, errors in structured.error.

The happy path is DISCOVERY-based: it scans the extension project's detailed problems
for a row whose 'Fix' column says 'yes' (the doc-comment checks reliably expose fixes),
reads that row's Check code / Module path / Line, and applies it; a locator that still
matches several markers (or a multi-variant fix) is disambiguated with index / variant.
It then RE-SCANS get_project_errors and asserts the fixed check/module no longer appears -
proving the fix actually changed something, not just trusting the tool's self-reported
success=true (a stub that always returned success without doing anything would otherwise
still pass). It then cleans up after itself (revert + clean_project) so the run's tree
stays clean. If the live fixture exposes no auto-fixable marker the happy test SKIPS - a
known, accepted tradeoff of discovery-based scanning (there is no fixture-independent way
to GUARANTEE a fixable marker without seeding one, which this suite does not do here).

Negative matrix (real error paths from ApplyQuickFixTool):
  - unknown checkId           -> "No marker matches check '<id>' ... run get_project_errors"
  - missing required checkId  -> requireArguments rejects it, naming the parameter
"""

import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality, e2e_test,
    PROJECT, TESTS_PROJECT, E2ESkip, reset_all_fixtures, wait_for_project_ready,
)

# Splits a markdown table row on '|' delimiters, but NOT on an escaped '\|' - production
# (MarkdownUtils.escapeForTable) escapes a literal '|' inside a cell's own text (e.g. a
# Description mentioning a BSL "?" operator's surrounding pipe-like syntax, or any text that
# happens to contain '|') exactly so it cannot be mistaken for a column delimiter. A naive
# str.split("|") does not know about that escape and cuts the row at the WRONG points, so a
# row whose Description/Location cell contains a real '|' would parse to more than 7 cells and
# get silently skipped here - even though it is a perfectly good, fixable marker.
_CELL_SPLIT = re.compile(r"(?<!\\)\|")


def _split_table_row(line):
    """Splits one '| c1 | c2 | ... |' row into its (unescaped) cell strings."""
    parts = _CELL_SPLIT.split(line.strip())
    # A well-formed row's boundary delimiters produce an empty string before the first and
    # after the last real cell - drop those two by position, not by stripping '|' characters
    # off the ends (which could eat into a real trailing "\|" in the last cell's content).
    if len(parts) >= 2 and parts[0] == "" and parts[-1] == "":
        parts = parts[1:-1]
    return [p.strip().replace("\\|", "|") for p in parts]


def _scan_detailed_rows(project):
    """Yields each detailed-table row of get_project_errors(project) as a 7-cell dict:
    desc, loc, modulePath, line, checkId, fix, hasDocs."""
    r = call("get_project_errors", {"projectName": project, "responseFormat": "detailed"})
    assert_ok(r, "get_project_errors detailed scan")
    for line in (r.text or "").splitlines():
        if not line.startswith("|"):
            continue
        cells = _split_table_row(line)
        if len(cells) != 7 or cells[0].lower() == "description":  # skip the header row too
            continue
        desc, loc, module, ln, check, fix, docs = cells
        yield {"desc": desc, "loc": loc, "modulePath": module, "line": ln,
               "checkId": check.strip("`"), "fix": fix, "hasDocs": docs}


def _find_fixable(project):
    """Scan a project's DETAILED problems for rows flagged fixable (Fix == 'yes').
    Returns a list of {checkId, modulePath, line}."""
    return [{"checkId": row["checkId"], "modulePath": row["modulePath"], "line": row["line"]}
            for row in _scan_detailed_rows(project) if row["fix"].lower() == "yes"]


def _has_marker(project, check_id, module_path):
    """True when get_project_errors(project) still reports ANY marker for this exact
    (checkId, modulePath) pair - used to confirm a fix actually made the ORIGINAL issue go
    away (the line number is not part of the match: the fix itself can shift subsequent
    lines, e.g. inserting a doc-comment stub, so re-matching the exact old line would be
    fragile)."""
    return any(row["checkId"] == check_id and row["modulePath"] == module_path
               for row in _scan_detailed_rows(project))


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
        # everything above). Re-scan after the model revalidates and confirm the ORIGINAL
        # check/module combination is genuinely gone.
        wait_for_project_ready()
        assert not _has_marker(TESTS_PROJECT, c["checkId"], c["modulePath"]), (
            "check '%s' must be gone from %s after its quick-fix was applied, not just "
            "reported as success" % (c["checkId"], c["modulePath"]))
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

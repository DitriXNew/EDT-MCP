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
It then cleans up after itself (revert + clean_project) so the run's tree stays clean.
If the live fixture exposes no auto-fixable marker the happy test SKIPS.

Negative matrix (real error paths from ApplyQuickFixTool):
  - unknown checkId           -> "No marker matches check '<id>' ... run get_project_errors"
  - missing required checkId  -> requireArguments rejects it, naming the parameter
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality, e2e_test,
    PROJECT, TESTS_PROJECT, E2ESkip, reset_all_fixtures,
)


def _find_fixable(project):
    """Scan a project's DETAILED problems for rows flagged fixable (Fix == 'yes').
    Returns a list of {checkId, modulePath, line}. Detailed columns:
    Description | Location | Module path | Line | Check code | Fix | Has docs
    """
    r = call("get_project_errors", {"projectName": project, "responseFormat": "detailed"})
    assert_ok(r, "get_project_errors detailed scan (discover fixable marker)")
    out = []
    for line in (r.text or "").splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 7:
            continue
        _desc, _loc, module, ln, check, fix, _docs = cells
        if fix.lower() == "yes":
            out.append({"checkId": check.strip("`"), "modulePath": module, "line": ln})
    return out


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (discovery-based, self-cleaning)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="apply_quick_fix", kind="write-metadata")
def test_apply_a_discovered_fixable_marker():
    """Discover a fixable marker (Fix=yes) and apply its quick-fix; assert success and a
    named applied variant. Locator collisions (several markers / fix variants) are resolved
    with index / variant. Self-cleans the extension fixture afterwards."""
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
    finally:
        # The fix mutated the extension (model + disk); the per-test reset only covers the
        # BASE, so revert + re-sync the extension here to keep the whole tree clean.
        reset_all_fixtures()
        try:
            call("clean_project", {"projectName": TESTS_PROJECT})
        except Exception:
            pass


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

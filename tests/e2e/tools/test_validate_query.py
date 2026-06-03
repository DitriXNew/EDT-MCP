"""
e2e tests for validate_query (kind: read).

validate_query parses 1C:Enterprise query (QL) text in the context of a real
EDT project and reports syntax/semantic issues. It is a READ tool: it loads a
throwaway in-memory Xtext resource, validates, and unloads it — it must NEVER
mutate the project on disk, so every test ends with assert_no_diff().

Response type is JSON (ValidateQueryTool.getResponseType() == JSON), so the
semantic payload lands in Result.structured ({valid, dcsMode, errorCount,
warningCount, infoCount, issues}); Result.text is only a bounded digest.
Tool-level failures (ToolResult.error) surface as is_error with the real
message in structuredContent.error (Result.error_text() reads it).

Fixture: TestConfiguration contains the metadata object Catalog.Catalog, so a
query against it must validate clean; a deliberately broken query must validate
DIRTY — that contrast is what proves the tool actually parses rather than
rubber-stamping.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="validate_query", kind="read")
def test_valid_query_against_fixture_catalog_is_clean():
    # Catalog.Catalog is a real metadata object in TestConfiguration, so this
    # well-formed query must parse AND resolve with zero errors.
    r = call("validate_query", {
        "projectName": PROJECT,
        "queryText": "SELECT Ref FROM Catalog.Catalog",
    })
    assert_ok(r, "valid query against an existing catalog")

    # The semantic verdict lives in structuredContent (JSON response type).
    assert r.structured is not None, \
        "JSON tool must populate structuredContent; got: %r" % (r.text,)
    # Load-bearing: a clean query must report valid=True with no errors. If the
    # tool were broken (always-invalid, no-op, wrong payload) this fails.
    assert r.structured.get("valid") is True, \
        "expected valid=True for a correct query, got: %r" % (r.structured,)
    assert r.structured.get("errorCount") == 0, \
        "expected errorCount=0 for a correct query, got: %r" % (r.structured,)
    assert r.structured.get("issues") == [], \
        "expected no issues for a correct query, got: %r" % (r.structured,)

    # The digest text mirrors the structured keys (proves the JSON wiring, not
    # a stale 'Done' placeholder).
    assert_contains(r.text, "valid", "success digest must reflect the result keys")

    assert_no_diff("validate_query is read-only; nothing may change on disk")


@e2e_test(tool="validate_query", kind="read")
def test_broken_query_is_reported_invalid_with_issues():
    # A query with a syntax error ("SELCT" + an empty FROM source) must be
    # detected: the call itself SUCCEEDS (reporting problems is the tool's job),
    # but the verdict must be invalid with at least one ERROR issue. This is the
    # mutation-sensitive counterpart to the clean case: a tool that always
    # answered valid=True would pass the happy test but FAIL here.
    r = call("validate_query", {
        "projectName": PROJECT,
        "queryText": "SELCT FROM",
    })
    assert_ok(r, "validate_query must succeed even when the query is malformed")

    assert r.structured is not None, \
        "JSON tool must populate structuredContent; got: %r" % (r.text,)
    assert r.structured.get("valid") is False, \
        "expected valid=False for a malformed query, got: %r" % (r.structured,)
    assert r.structured.get("errorCount", 0) >= 1, \
        "expected at least one error for a malformed query, got: %r" % (r.structured,)
    issues = r.structured.get("issues") or []
    assert any(i.get("severity") == "ERROR" for i in issues), \
        "expected an ERROR-severity issue for a malformed query, got: %r" % (issues,)

    assert_no_diff("validate_query is read-only; nothing may change on disk")


@e2e_test(tool="validate_query", kind="read")
def test_dcs_mode_flag_is_reflected_in_result():
    # The dcsMode flag is echoed back in the payload; passing it true must be
    # reflected as dcsMode=true (proves the boolean argument is read & honoured,
    # not silently dropped).
    r = call("validate_query", {
        "projectName": PROJECT,
        "queryText": "SELECT Ref FROM Catalog.Catalog",
        "dcsMode": True,
    })
    assert_ok(r, "valid query in DCS mode")
    assert r.structured is not None, \
        "JSON tool must populate structuredContent; got: %r" % (r.text,)
    assert r.structured.get("dcsMode") is True, \
        "dcsMode=true must round-trip into the result, got: %r" % (r.structured,)
    assert_no_diff("validate_query is read-only; nothing may change on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="validate_query", kind="read")
def test_missing_projectname_errors_clearly():
    # projectName is required (JsonUtils.requireArguments). Omitting it must be a
    # machine-detectable error that names the missing parameter.
    r = call("validate_query", {
        "queryText": "SELECT Ref FROM Catalog.Catalog",
    })
    err = assert_error(r, "missing required projectName")
    # AUDIT: the message is exactly "projectName is required" — it names the
    # parameter but gives NO actionable next step (no mention of list_projects
    # or how to discover a valid project name). suggests=[] until the tool's
    # required-arg error is enriched with a next-step hint. -> fix-card.
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the parameter")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="validate_query", kind="read")
def test_missing_querytext_errors_clearly():
    # queryText is the second required argument; omitting it must error and name
    # the missing parameter.
    r = call("validate_query", {
        "projectName": PROJECT,
    })
    err = assert_error(r, "missing required queryText")
    # AUDIT: message is "queryText is required" — names the parameter but offers
    # no example/next step. suggests=[] until enriched. -> fix-card.
    assert_error_quality(err, names=["queryText"], suggests=[],
                         ctx="missing queryText names the parameter")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="validate_query", kind="read")
def test_nonexistent_project_errors_clearly():
    # A projectName that does not exist must fail with an error that NAMES the
    # bad value (ProjectContext.exists() == false -> "Project not found: <name>").
    bogus = "NoSuchProject_ZZZ"
    r = call("validate_query", {
        "projectName": bogus,
        "queryText": "SELECT Ref FROM Catalog.Catalog",
    })
    err = assert_error(r, "non-existent project")
    # AUDIT: message is "Project not found: <name>" — it names the bad value but
    # does NOT point at a recovery path (e.g. "use list_projects to find a valid
    # project"). suggests=[] until the message becomes actionable. -> fix-card.
    assert_error_quality(err, names=[bogus], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("a rejected call must not touch the project on disk")

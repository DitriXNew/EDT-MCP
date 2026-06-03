"""
e2e tests for read_module_source (kind: read).

read_module_source returns YAML frontmatter (projectName, module, totalLines;
plus startLine/endLine for non-empty files) followed by the source in a fenced
bsl block. Response type is MARKDOWN, so the payload lands in Result.text.

A read tool must never mutate the project on disk: every test below ends with
assert_no_diff() as the non-destructive guardrail (SKILL §4).

Happy-path fixtures (committed baseline of TestConfiguration):
  - CommonModules/Error/Module.bsl  -> single token "Error"
  - CommonModules/OK/Module.bsl     -> EMPTY (totalLines == 0)

Negative matrix targets the tool's REAL execute() errors (read from the Java):
  - missing projectName        -> "projectName is required"
  - missing modulePath         -> "modulePath is required. Example: ..."
  - non-existent project       -> "Project not found: <name>"
  - non-existent module path   -> "File not found: src/<path>. Use format ..."
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test, PROJECT,
)

# Fixture with real content: the file holds the single token "Error".
ERROR_MODULE = "CommonModules/Error/Module.bsl"
# Fixture that is empty in the committed baseline.
EMPTY_MODULE = "CommonModules/OK/Module.bsl"


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="read_module_source", kind="read")
def test_reads_module_content_and_does_not_mutate():
    """Full-file read of a non-empty module returns its real source + frontmatter.

    Mutation thinking: if the tool returned a no-op / wrong file / empty body,
    the 'Error' token or the module path would be absent and this would FAIL.
    """
    r = call("read_module_source", {"projectName": PROJECT, "modulePath": ERROR_MODULE})
    assert_ok(r, "read non-empty module")
    # Frontmatter must echo the module that was actually read.
    assert_contains(r.text, ERROR_MODULE, "frontmatter must name the module read")
    # The real on-disk content of this module is the single token "Error".
    assert_contains(r.text, "Error", "fenced body must contain the module's real source")
    # Non-empty file -> frontmatter carries a line count and a fenced bsl block.
    assert_contains(r.text, "totalLines", "frontmatter must report totalLines")
    assert_contains(r.text, "```bsl", "source must be in a fenced bsl block")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_emits_content_hash_for_lost_update_round_trip():
    """The frontmatter carries a contentHash token for the optimistic-lock round-trip.

    Mutation thinking: if the tool stopped emitting the token (or emitted a
    non-deterministic / wrong-shaped value), the 16-hex-char match would FAIL.
    The token must be stable across two reads of the unchanged file, since
    write_module_source recomputes it to validate an expectedHash.
    """
    import re
    r = call("read_module_source", {"projectName": PROJECT, "modulePath": ERROR_MODULE})
    assert_ok(r, "read for contentHash")
    assert_contains(r.text, "contentHash", "frontmatter must carry a contentHash")
    m = re.search(r'contentHash:\s*"?([0-9a-f]{16})"?', r.text or "")
    if not m:
        from harness import E2EAssertion
        raise E2EAssertion("contentHash is not a 16-hex token:\n%s" % (r.text or "")[:300])
    # Stable: a second read of the unchanged file yields the same token.
    r2 = call("read_module_source", {"projectName": PROJECT, "modulePath": ERROR_MODULE})
    m2 = re.search(r'contentHash:\s*"?([0-9a-f]{16})"?', r2.text or "")
    assert m2 and m2.group(1) == m.group(1), "contentHash must be stable across reads of an unchanged file"
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_reads_empty_module_reports_zero_lines():
    """An empty module reads back with totalLines: 0 (the documented contract).

    Mutation thinking: a broken tool that injected text or mis-counted would
    not report 'totalLines: 0'; a tool that errored on empty files would fail
    assert_ok. Both regressions are caught here.
    """
    r = call("read_module_source", {"projectName": PROJECT, "modulePath": EMPTY_MODULE})
    assert_ok(r, "read empty module")
    assert_contains(r.text, EMPTY_MODULE, "frontmatter must name the empty module")
    # Per the tool contract: for an empty file totalLines is 0.
    assert_contains(r.text, "totalLines: 0", "empty file must report totalLines: 0")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_line_range_clamps_to_first_line():
    """A startLine/endLine range returns the requested window with matching frontmatter.

    The Error module has exactly one line ('Error'); requesting line 1..1 must
    echo startLine: 1 / endLine: 1 and still contain the source token.

    Mutation thinking: a tool that ignored the range params or off-by-one'd the
    window would emit a different startLine/endLine and this would FAIL.
    """
    r = call("read_module_source", {
        "projectName": PROJECT, "modulePath": ERROR_MODULE,
        "startLine": 1, "endLine": 1,
    })
    assert_ok(r, "read line range 1..1")
    assert_contains(r.text, "startLine: 1", "frontmatter must echo the requested startLine")
    assert_contains(r.text, "endLine: 1", "frontmatter must echo the requested endLine")
    assert_contains(r.text, "Error", "the single source line must be present in the window")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (missing required params + non-existent project/module)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="read_module_source", kind="read")
def test_missing_projectname_errors_clearly():
    """Missing required projectName -> clear, value-naming error; no disk change."""
    r = call("read_module_source", {"modulePath": ERROR_MODULE})
    err = assert_error(r, "missing required projectName")
    # The tool's real message is exactly "projectName is required".
    # AUDIT: this message names the param but offers NO next step (no sibling
    # tool / fix hint), so suggests=[] is intentional, not laziness.
    assert_error_quality(err, names=["projectName is required"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_missing_modulepath_errors_with_example():
    """Missing required modulePath -> error names the param AND gives an example path."""
    r = call("read_module_source", {"projectName": PROJECT})
    err = assert_error(r, "missing required modulePath")
    # Real message: "modulePath is required. Example: 'CommonModules/MyModule/Module.bsl'".
    # The example path token is delimiter-free and survives Gson HTML-escaping,
    # so it is a safe actionable anchor (the surrounding quotes are escaped).
    assert_error_quality(err, names=["modulePath is required"], suggests=["CommonModules"],
                         ctx="missing modulePath names the param and shows a format example")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_nonexistent_project_errors_clearly():
    """A non-existent projectName -> error names the bad project; no disk change."""
    bad = "NoSuchProject_ZZZ"
    r = call("read_module_source", {"projectName": bad, "modulePath": ERROR_MODULE})
    err = assert_error(r, "non-existent project")
    # Real message: "Project not found: <name>". Names the value.
    # AUDIT: the message offers NO next step (does not point to list_projects to
    # find a valid project name). suggests=[] is intentional; this is a fix-card
    # to make the error actionable.
    assert_error_quality(err, names=[bad, "Project not found"], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_nonexistent_module_path_errors_with_format_hint():
    """A non-existent modulePath -> error names the bad path AND shows the format.

    Mutation thinking: a tool that silently returned empty source (instead of an
    error) for a missing file would fail assert_error; a tool whose message
    omitted the bad path would fail assert_error_quality.
    """
    bad = "Catalog/DoesNotExist/Module.bsl"
    r = call("read_module_source", {"projectName": PROJECT, "modulePath": bad})
    err = assert_error(r, "non-existent module path")
    # Real message: "File not found: src/<path>. Use format like
    # 'CommonModules/ModuleName/Module.bsl' or 'Documents/DocName/ObjectModule.bsl'".
    # Names the bad path; the "File not found" + "CommonModules" format hint are
    # delimiter-free actionable anchors.
    # AUDIT: the hint shows the path FORMAT but names no sibling discovery tool
    # (e.g. list_modules / get_metadata_objects) to find a valid module path.
    assert_error_quality(err, names=[bad, "File not found"],
                         suggests=["CommonModules"],
                         ctx="non-existent module path names the value and the format")
    assert_no_diff("an invalid call must not touch the project on disk")

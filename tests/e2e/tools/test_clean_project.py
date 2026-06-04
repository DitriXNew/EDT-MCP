"""
e2e tests for clean_project (kind: action).

What the tool does
------------------
clean_project triggers Eclipse "Project -> Clean" (CLEAN_BUILD) on one or all EDT
projects: it refreshes files from disk, clears ALL validation markers, waits for the
EDT project context to restart (STOPPED -> STARTED) and for derived-data
recomputation, then reports how many projects were cleaned. It is an ACTION tool:
it recomputes WORKSPACE state (validation markers, derived data, the in-memory
model) — it does NOT, and must NOT, rewrite the project's tracked source files. So
the correct on-disk assertion for a successful clean is assert_no_diff() on
TestConfiguration: the action is safe to run on the fixture precisely because it
touches workspace state, not the committed tree. (Reference: CleanProjectTool.java
phases 1-4; only project.refreshLocal + CLEAN_BUILD + waiters, no MDO/BSL writes.)

Response shape (IMPORTANT)
--------------------------
CleanProjectTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text — for a JSON tool r.text is just a placeholder).
The success envelope (ToolResult.success().put(...)) is:
    {"success": true,
     "projectsCleaned": <int>,            # == len(projects)
     "projects": [ "<name>", ... ],       # the cleaned project names
     "message": "Clean and revalidation completed."}
On error the envelope is {"success": false, "error": "<message>"} and the protocol
layer marks the result isError; assert_error returns that error string.

Parameter shape (from CleanProjectTool.getInputSchema / execute)
----------------------------------------------------------------
There is exactly ONE parameter: projectName (string, OPTIONAL). Omitting it cleans
ALL open EDT projects (not an error — a real happy branch). There is NO enum, NO XOR
pair, and NO conditional-required parameter, so the "invalid enum" /
"mutually-exclusive" / "conditional-required" negatives from the matrix do not apply
here (documented, not skipped). The reachable negatives are driven entirely by the
project-state pre-check:

  - non-existent project -> the readiness pre-check is now
    ProjectStateChecker.buildingErrorOrNull(projectName), which refuses ONLY the
    transient BUILDING state and returns null for a missing/closed/unknown project.
    So a name absent from the workspace falls through to cleanProject(), where
    ProjectContext.of(name).exists()==false returns the actionable, value-naming
    "Project not found: <name>" error (see test_nonexistent_project_*).
  - empty-string projectName -> NOT an error: extractStringArgument returns "" ,
    `projectName != null && !projectName.isEmpty()` is false, the readiness check is
    skipped, and the tool falls through to the "clean ALL projects" branch (the same
    branch as the omitted-param happy path). This is asserted as a happy branch, not
    a negative — documenting that "" behaves like "omitted", not like a bad name.

ACTION-TOOL SAFETY (why there is a real happy path here, unlike import/update)
-----------------------------------------------------------------------------
clean_project only clears/recomputes markers; it cannot corrupt the committed
fixture. So a happy call IS run, and the test proves the fixture tree is unchanged
afterwards (assert_no_diff). This is distinct from the destructive action tools
(import_configuration_from_xml, update_database) whose happy path WOULD overwrite the
fixture / mutate the infobase and is therefore intentionally never exercised; those
tools test only their negative/sentinel matrix. clean_project has no such hazard.

Mutation thinking
-----------------
Each test fails if the tool were broken: a no-op clean that returned the wrong
envelope (no success flag / wrong message / desynced count) fails the happy asserts;
a clean that silently rewrote a tracked file fails assert_no_diff; a tool that
stopped rejecting an unknown project fails assert_error.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


def _success_envelope(r, ctx):
    """Validate the JSON success envelope and return (projects_list, projectsCleaned).

    A JSON tool MUST populate structuredContent; a missing/typed-wrong envelope means
    the tool returned the wrong shape (a real regression), so we hard-fail rather than
    tolerate it."""
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    # success=true positively discriminates the success payload from the error payload
    # ({"success": false, "error": ...}); a tool that mislabels the result is caught.
    if sc.get("success") is not True:
        raise AssertionError("clean envelope must set success=true [%s]: %r" % (ctx, sc))
    if "error" in sc:
        raise AssertionError("success envelope must NOT carry an 'error' field [%s]: %r" % (ctx, sc))
    projects = sc.get("projects")
    cleaned = sc.get("projectsCleaned")
    if not isinstance(projects, list):
        raise AssertionError("'projects' must be a list [%s]: %r" % (ctx, projects))
    if not isinstance(cleaned, int):
        raise AssertionError("'projectsCleaned' must be an int [%s]: %r" % (ctx, cleaned))
    # projectsCleaned is built from projectNamesList.size() — the two MUST agree; a
    # desync means the tool miscounted (counted before/after filtering), a real bug.
    if cleaned != len(projects):
        raise AssertionError(
            "projectsCleaned(%d) must equal len(projects)(%d) [%s]" % (cleaned, len(projects), ctx))
    # The fixed completion message is part of the contract: a tool that finished the
    # CLEAN_BUILD but skipped/garbled the message (or returned a stale one) is caught.
    if sc.get("message") != "Clean and revalidation completed.":
        raise AssertionError(
            "success envelope must carry the exact completion message [%s]: %r"
            % (ctx, sc.get("message")))
    return projects, cleaned


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="clean_project", kind="action")
def test_clean_named_project_succeeds_and_does_not_mutate_tree():
    """Clean the named fixture project: the action completes (CLEAN_BUILD + restart +
    derived-data wait) and reports the fixture in its cleaned set, WITHOUT touching
    any tracked file.

    This is the core action-safety proof: clean_project recomputes workspace markers
    and the in-memory model, so the success envelope must name TestConfiguration as
    cleaned (projectsCleaned==1, projects==["TestConfiguration"]) AND the git working
    tree must stay clean. A clean that secretly rewrote an .mdo/.bsl (or a no-op that
    returned a bogus success without actually cleaning the requested project) is caught:
    the first by assert_no_diff, the second by the project-name assertion below."""
    r = call("clean_project", {"projectName": PROJECT})
    assert_ok(r, "clean_project on the fixture project")

    projects, cleaned = _success_envelope(r, "named-project clean")
    # The requested project MUST be the one cleaned — a tool that cleaned the wrong
    # project (or none) would not echo TestConfiguration here.
    if PROJECT not in projects:
        raise AssertionError(
            "cleaned set must include the requested project %r: %r" % (PROJECT, projects))
    # Cleaning ONE named project must clean exactly that one (the named branch builds a
    # single-element list); a count != 1 means the named filter leaked into clean-all.
    if cleaned != 1:
        raise AssertionError(
            "cleaning a single named project must report projectsCleaned==1: %r" % cleaned)

    # The ground truth: a clean clears workspace markers, NOT the committed source tree.
    assert_no_diff("clean_project must clear markers, never rewrite tracked project files")


@e2e_test(tool="clean_project", kind="action")
def test_clean_all_projects_succeeds_and_includes_fixture():
    """Omitting projectName cleans ALL open EDT projects (a distinct, real branch).

    The clean-all branch iterates dtProjectManager.getDtProjects() and cleans every
    open one, so the cleaned set must be non-empty and MUST include the loaded fixture
    project. A tool that ignored the omitted-param branch (e.g. erroring on a missing
    'required' arg, or cleaning nothing) would fail here. The fixture tree must remain
    unchanged."""
    r = call("clean_project", {})
    assert_ok(r, "clean_project with no projectName (clean all)")

    projects, cleaned = _success_envelope(r, "clean-all")
    # At least the fixture project is loaded, so the clean-all set cannot be empty and
    # must contain it; an empty set means the clean-all enumeration was a no-op.
    if cleaned < 1:
        raise AssertionError("clean-all must clean at least the loaded fixture project: %r" % cleaned)
    if PROJECT not in projects:
        raise AssertionError(
            "clean-all set must include the loaded fixture project %r: %r" % (PROJECT, projects))

    assert_no_diff("clean-all must clear markers, never rewrite tracked project files")


@e2e_test(tool="clean_project", kind="action")
def test_empty_projectname_falls_through_to_clean_all():
    """Boundary: projectName="" is NOT a bad name and is NOT an error.

    extractStringArgument returns "" ; the guard `projectName != null &&
    !projectName.isEmpty()` is false, so the readiness pre-check is skipped and the
    tool takes the SAME "clean all projects" branch as the omitted-param case. This
    proves the empty string is treated as "no project specified" (clean all), not
    mistaken for a project literally named "" (which would error). A tool that routed
    "" into the named branch would hit ProjectContext.of("") / a "Project not found"
    error and fail assert_ok here."""
    r = call("clean_project", {"projectName": ""})
    assert_ok(r, "empty-string projectName -> clean all branch")

    projects, cleaned = _success_envelope(r, "empty-string projectName")
    # Same observable contract as clean-all: the fixture is among the cleaned projects.
    if PROJECT not in projects:
        raise AssertionError(
            'empty projectName must behave like clean-all and include %r: %r' % (PROJECT, projects))

    assert_no_diff("empty-name clean-all must not rewrite tracked project files")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="clean_project", kind="action")
def test_nonexistent_project_errors_and_does_not_mutate():
    """A syntactically valid but non-existent project name must error, not silently
    clean nothing and report success.

    ORDERING NOTE: in execute() the readiness pre-check is now
    ProjectStateChecker.buildingErrorOrNull(projectName), which refuses ONLY the
    transient BUILDING state and returns null for a missing/closed/unknown project. So
    for a name that is not a workspace project the pre-check no longer shadows the
    downstream branch: control falls through to cleanProject(), where
    ProjectContext.of(name).exists()==false yields the actionable, value-naming
    "Project not found: <name>" error the client sees.

    names=[bad] asserts that REAL downstream message echoes the offending project value
    (a broken tool that returned a fake empty-success instead of an error fails
    assert_error outright; a tool whose message regressed to a bare 'Error'/stacktrace
    fails the error-quality bareness/stacktrace check). suggests=[] — the reachable
    message does not point at a next-step tool (the list_projects discovery tail is a
    separate change)."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("clean_project", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # Assert the REAL downstream message contract ("Project not found: <bad>"), which now
    # names the offending value. Still fails loudly if the tool stopped erroring on an
    # unknown project (e.g. cleaned all + faked success).
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project surfaces the value-naming 'Project not found' error")
    # A rejected clean must not have touched any tracked file.
    assert_no_diff("a rejected clean must not touch the project on disk")


@e2e_test(tool="clean_project", kind="action")
def test_whitespace_project_name_errors_and_does_not_mutate():
    """Boundary: a whitespace-only projectName ("   ") is non-empty, so the named
    branch IS taken: the guard `!projectName.isEmpty()` is true, the BUILDING-only
    readiness pre-check (buildingErrorOrNull) returns null for the "   " handle (it is
    not building, just not an existing project), and control falls through to
    cleanProject(), where ProjectContext.of("   ").exists()==false returns
    "Project not found:    ". This proves the tool does not strip/coerce a blank name
    into clean-all or into a real project (either would be a silent-success bug). A tool
    that trimmed "   " to "" would instead fall through to clean-all and succeed — which
    would fail assert_error here.

    The reachable error is the downstream "Project not found: <value>"; we assert its
    stable, delimiter-free "Project not found" text rather than the raw blank value
    (awkward to match through JSON whitespace). suggests=[]."""
    bad = "   "
    r = call("clean_project", {"projectName": bad})
    err = assert_error(r, "whitespace-only projectName")
    # The whitespace handle is not an existing project -> the downstream not-found error
    # fires. Asserting the stable text (not the blank value) is the robust signal.
    assert_error_quality(err, names=["Project not found"], suggests=[],
                         ctx="whitespace projectName surfaces the 'Project not found' error")
    assert_no_diff("a rejected clean must not touch the project on disk")

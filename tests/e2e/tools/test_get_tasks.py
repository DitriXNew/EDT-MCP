"""
e2e tests for get_tasks (kind: read).

Read tool: returns a MARKDOWN report of workspace task markers (TODO / FIXME /
XXX / HACK) — a "## Tasks" header, a "**Found:** N tasks" banner, and (when any
exist) a table of Type | Priority | Message | Path | Line. ResponseType is the
default MARKDOWN, so the payload is in r.text (NOT r.structured); a
ToolResult.error payload is diverted to a structured JSON error (isError:true).

Optional filters (ALL params are optional — there is NO required-param error path):
  - projectName  : restrict to one project (else all open projects)
  - filePath     : case-insensitive substring match on the resource path
  - priority     : enum {high, normal, low}; an out-of-set value is rejected
  - limit        : capped at 1000

Happy paths assert the structurally-guaranteed banner that the tool ALWAYS emits
when it actually runs its markdown build (header + "**Found:**" count). They do
NOT assert a specific task row: task markers are produced by EDT's live Xtext
indexing at runtime, not stored in the git fixture, so the presence of a given
TODO row is non-deterministic and is NOT on-disk truth. The empty/near-empty
"## Tasks ... **Found:** N tasks" report is itself a valid happy result. Every
test ends with assert_no_diff() — a read tool must never mutate the project.

Negative matrix targets the tool's REAL execute() error paths:
  - non-existent project          -> "Project not found: <name>. Use list_projects
                                      to see available projects." (actionable tail)
  - out-of-set priority enum      -> "priority must be one of: high, normal, low"
There is intentionally NO "missing required parameter" case: every parameter is
optional, so call("get_tasks", {}) is a HAPPY path, not an error (covered below).

Fixture inventory used (TestConfiguration, English Names): the project itself
(PROJECT) plus its CommonModule "OK" (whose source path contains "CommonModules/OK").
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_tasks", kind="read")
def test_no_args_returns_well_formed_report_and_does_not_mutate():
    # All params optional -> {} is a valid call that scans every open project.
    # The tool ALWAYS emits the "## Tasks" header and a "**Found:** N tasks"
    # banner when it actually ran the markdown build. A broken tool that returned
    # a no-op / empty string / wrong payload would NOT contain these, so this
    # FAILS on regression even though the fixture may surface zero task rows.
    r = call("get_tasks", {})
    assert_ok(r, "get_tasks no args")
    assert_contains(r.text, "## Tasks", "report must carry the Tasks header")
    assert_contains(r.text, "**Found:**", "report must carry the Found-count banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_tasks", kind="read")
def test_project_filter_returns_well_formed_report():
    # projectName filter resolves the fixture project (must EXIST, else this would
    # take the "Project not found" branch). A valid project still yields the
    # banner-bearing report. This proves project resolution succeeded AND that the
    # markdown build ran for the scoped project.
    r = call("get_tasks", {"projectName": PROJECT})
    assert_ok(r, "get_tasks projectName=fixture")
    assert_contains(r.text, "## Tasks", "scoped report must carry the Tasks header")
    assert_contains(r.text, "**Found:**", "scoped report must carry the Found-count banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_tasks", kind="read")
def test_nonmatching_filepath_yields_zero_tasks_report():
    # A filePath substring that cannot match any resource path is a VALID call
    # (not an error) and must return an EMPTY result: "**Found:** 0 tasks" +
    # "*No tasks found.*". This is the discriminating mutation signal: a tool that
    # IGNORED the filePath filter would surface whatever real task rows exist and
    # report a non-zero count, so the "0 tasks" / "No tasks found" assertion would
    # FAIL. The garbage substring is chosen to never occur in any path.
    r = call("get_tasks", {"filePath": "zzz_no_such_path_substring_e2e"})
    assert_ok(r, "get_tasks non-matching filePath")
    assert_contains(r.text, "**Found:** 0 tasks",
                    "an unmatchable filePath must filter every task out")
    assert_contains(r.text, "No tasks found",
                    "the empty-state banner must be emitted when nothing matches")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_tasks", kind="read")
def test_valid_priority_enum_is_accepted():
    # "low" is a valid priority enum value -> the call must SUCCEED (no error) and
    # produce the banner-bearing report. This guards the enum's accept side: if the
    # validator wrongly rejected an in-set value, assert_ok would FAIL. (The reject
    # side is covered by the negative matrix below.)
    r = call("get_tasks", {"priority": "low"})
    assert_ok(r, "get_tasks priority=low (valid enum)")
    assert_contains(r.text, "## Tasks", "valid-priority report must carry the Tasks header")
    assert_contains(r.text, "**Found:**", "valid-priority report must carry the Found banner")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (mandatory)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_tasks", kind="read")
def test_nonexistent_project_errors_and_names_value():
    # projectName resolves via ProjectContext; a missing project ->
    # ToolResult.error(ProjectContext.notFoundMessage(name)) ==
    # "Project not found: <name>. Use list_projects to see available projects."
    # The error MUST name the bad value so the caller knows which project was wrong
    # AND point at list_projects, the sibling tool that enumerates valid names.
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_tasks", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # The migrated message is actionable: it names the bad project AND gives the
    # next step (call list_projects to find a valid name), so we assert both the
    # echoed bad value and the list_projects pointer.
    assert_error_quality(err, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_tasks", kind="read")
def test_invalid_priority_enum_is_rejected_actionably():
    # priority is a closed enum {high, normal, low}; an out-of-set value is
    # rejected up front (before any workspace access) with
    # ToolResult.error("priority must be one of: high, normal, low"). The message
    # is actionable: it enumerates the valid values, so a caller can self-correct.
    bad = "urgent_e2e"
    r = call("get_tasks", {"priority": bad})
    err = assert_error(r, "out-of-set priority enum")
    # AUDIT: the error lists the valid values (actionable) but does NOT echo the
    # rejected value the caller passed ("urgent_e2e"), so names=[bad] is NOT
    # assertable here — the message cannot identify which bad input was supplied
    # when several params are in play. We assert the actionable valid-value list
    # via suggests and flag the missing bad-value echo as a fix-card.
    assert_error_quality(err, names=[], suggests=["high", "normal", "low"],
                         ctx="invalid priority lists the valid enum values")
    # Mutation guard: the rejection must be real, not a swallowed filter. A tool
    # that silently ignored the bad priority would have produced the success
    # report instead — assert the success header is absent from the error text.
    assert "## Tasks" not in (err or ""), \
        "a rejected priority must NOT fall through to the task report"
    assert_no_diff("an invalid call must not touch the project on disk")

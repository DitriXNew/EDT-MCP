"""
e2e tests for get_tags (kind: read).

Read tool: returns a MARKDOWN report of all user-defined tags in a project
(table of #, Name, Color, Description, Objects + a "Total tags" footer). Tags are
a TagService/TagStorage feature, NOT part of the 1C metadata model. ResponseType
is MARKDOWN, so the payload lives in r.text (NOT r.structured).

The TestConfiguration fixture defines NO tags (TagStorage is empty). For an
empty-state tool the HAPPY path is the valid EMPTY result: GetTagsTool.getTags
short-circuits to the literal banner "No tags defined in project: <name>" when
storage.getTags() is empty. That banner is a real, well-formed happy response and
we assert it exactly (and assert the table footer is ABSENT, so a broken tool that
emitted a spurious table would fail). A read tool must never touch disk, so every
test ends with assert_no_diff().

Negative matrix targets GetTagsTool.execute()'s REAL error paths. The tool has a
SINGLE parameter (projectName, required) — no XOR, no enum, no conditional-required
params — so the matrix is exactly:
  - missing required projectName -> JsonUtils.requireArgument -> "projectName is required"
  - non-existent project         -> ProjectStateChecker.checkReadyOrError runs FIRST and,
                                     because the handle does not exist, returns
                                     "Project does not exist. Please wait and retry."
                                     (the ProjectContext "Project not found: <name>" branch
                                     is unreachable for a bad name — the state check fires first).

Fixture inventory (TestConfiguration, English Names): Catalog.Catalog,
CommonModule.Error, CommonModule.OK, CommonForm.Form, Subsystem.Subsystem,
CommonAttribute.CommonAttribute, SessionParameter.SessionParameter — none of which
carry tags, confirming the empty-state happy path.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy path (valid EMPTY result — the fixture defines no tags)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_tags", kind="read")
def test_empty_state_returns_no_tags_banner_and_does_not_mutate():
    # The fixture's TagStorage is empty -> the tool MUST return the empty-state
    # banner that names the project. This is a real happy case (a valid response
    # shape), not an error.
    r = call("get_tags", {"projectName": PROJECT})
    assert_ok(r, "get_tags on a tag-free project")
    # Exact banner from GetTagsTool.getTags() when storage.getTags().isEmpty().
    # Includes the project name -> proves the tool resolved the project and walked
    # storage (not a canned constant): a no-op/broken tool returning "" or the
    # wrong project would fail here.
    assert_contains(r.text, "No tags defined in project: " + PROJECT,
                    "empty TagStorage must yield the named no-tags banner")
    # Mutation guard: a broken tool that fabricated rows would emit the table
    # header / footer. With zero tags neither may appear.
    assert_not_contains(r.text, "Total tags:",
                        "an empty project must NOT render a tag-count footer")
    assert_not_contains(r.text, "| Color ",
                        "an empty project must NOT render the tags table header")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (mandatory)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_tags", kind="read")
def test_missing_projectname_errors_clearly():
    # projectName omitted: checkReadyOrError(null) short-circuits to null, then
    # JsonUtils.requireArgument(params, "projectName") -> ToolResult.error(
    # "projectName is required").toJson(). The error names the missing param.
    r = call("get_tags", {})
    err = assert_error(r, "missing required projectName")
    # AUDIT: the message names the missing parameter but gives NO next step — it
    # does not point at list_projects (the sibling that enumerates valid project
    # names). suggests=[] is deliberate; this is a fix-card to add a next-step hint.
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_tags", kind="read")
def test_nonexistent_project_errors():
    # A non-empty but non-existent project name. execute() calls
    # ProjectStateChecker.checkReadyOrError(projectName) BEFORE ProjectContext, and
    # the workspace handle's exists() is false -> checkProjectState returns
    # NOT_AVAILABLE "Project does not exist" -> the returned message is
    # "Project does not exist. Please wait and retry." (the ProjectContext
    # "Project not found: <name>" branch is never reached for a bad name).
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_tags", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # AUDIT: this error is BOTH non-actionable AND misleading. It does NOT name the
    # bad project value, and "Please wait and retry" implies a transient build
    # state when the project simply does not exist — a caller would loop forever.
    # It should instead name the bad value and point at list_projects. We assert
    # only the stable, discriminating substring "does not exist" (proves the
    # not-available branch fired, distinguishing it from the empty-tags happy path);
    # names=[] / suggests=[] are deliberate, NOT a weakened bar — see fix-card.
    assert_contains(err, "does not exist",
                    "non-existent project must report the not-available state")
    assert_error_quality(err, names=[], suggests=[],
                         ctx="non-existent project: error fires but is vague (audited)")
    assert_no_diff("an invalid call must not touch the project on disk")

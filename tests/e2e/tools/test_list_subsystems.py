"""
e2e tests for list_subsystems (kind: read).

list_subsystems is a MARKDOWN tool (getResponseType()==MARKDOWN), so the payload
lands in Result.text (structuredContent is None). It lists the configuration's
subsystems as a flat table: | FQN | Synonym | Comment | InCommandInterface |
Content | Children |.

Fixture ground truth (TestConfiguration/src/Subsystems/Subsystem/Subsystem.mdo):
  - exactly ONE subsystem, name "Subsystem", FQN "Subsystem.Subsystem"
  - <synonym><key>en</key><value>Subsystem</value></synonym>
  - <includeInCommandInterface>true</includeInCommandInterface>  -> renders "Yes"
  - no nested subsystems  -> Children count 0
Configuration default language is English (code "en").

Negative matrix: the tool has NO XOR/conditional/enum params. Its only real
validation/error paths in execute()/listSubsystemsInternal() are:
  1. missing required projectName        -> "projectName is required"
  2. non-existent project                -> "Project not found: <name>"
(nameFilter matching nothing is NOT an error -> "No subsystems found." happy edge.)
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_subsystems", kind="read")
def test_lists_fixture_subsystem_and_does_not_mutate():
    """Default call must render the real subsystem from the model as a table row.

    Mutation check: a broken tool that returned a no-op/empty body, the wrong
    project, or skipped the model walk would not contain the FQN row, the header,
    or "Yes" (IncludeInCommandInterface read straight off the .mdo) — so each of
    these asserts a value the tool can only produce by actually reading the model.
    """
    r = call("list_subsystems", {"projectName": PROJECT})
    assert_ok(r, "list_subsystems happy path")

    # Header proves the markdown report is for THIS project.
    assert_contains(r.text, "## Subsystems: " + PROJECT, "report header names the project")
    # Exactly one subsystem in the fixture -> the count must read 1.
    assert_contains(r.text, "**Total:** 1 subsystems", "fixture has exactly one subsystem")
    # The table header must be present (proves a table, not an error/empty body).
    assert_contains(
        r.text,
        "| FQN | Synonym | Comment | InCommandInterface | Content | Children |",
        "subsystem table header",
    )
    # The real FQN row, built by collect() as "Subsystem." + name.
    assert_contains(r.text, "Subsystem.Subsystem", "the fixture subsystem FQN")
    # includeInCommandInterface=true in the .mdo must render as "Yes".
    assert_contains(r.text, "| Yes |", "IncludeInCommandInterface flag read from the model")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_subsystems", kind="read")
def test_namefilter_matching_returns_the_subsystem():
    """nameFilter is a case-insensitive Name match; a matching filter keeps the row.

    Mutation check: a broken filter that dropped everything would yield
    "No subsystems found." and fail the FQN assertion below. Lower-case input
    ("subsys") also proves the case-insensitive contains() in matchesFilter().
    """
    r = call("list_subsystems", {"projectName": PROJECT, "nameFilter": "subsys"})
    assert_ok(r, "list_subsystems nameFilter match")
    assert_contains(r.text, "Subsystem.Subsystem", "matching filter keeps the subsystem row")
    assert_contains(r.text, "**Total:** 1 subsystems", "the single matching subsystem is counted")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_subsystems", kind="read")
def test_namefilter_no_match_reports_empty_not_error():
    """A non-matching nameFilter is a valid empty result, NOT an error.

    This pins the tool's documented behaviour: a filter with no hits returns
    "No subsystems found." with Total 0 and is_error=false. A broken tool that
    either errored or still listed the subsystem would fail here.
    """
    r = call(
        "list_subsystems",
        {"projectName": PROJECT, "nameFilter": "ZZZ_NoSuchSubsystem_ZZZ"},
    )
    assert_ok(r, "non-matching nameFilter is a valid empty result")
    assert_contains(r.text, "**Total:** 0 subsystems", "no subsystem matches the filter")
    assert_contains(r.text, "No subsystems found.", "empty-result message")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_subsystems", kind="read")
def test_non_recursive_marks_top_level_mode():
    """recursive=false must switch to top-level-only mode (the documented enum branch).

    formatOutput() only emits "**Mode:** top-level only" when recursive is false,
    so this assertion proves the boolean param was actually honoured. The single
    fixture subsystem is top-level, so it still appears.
    """
    r = call("list_subsystems", {"projectName": PROJECT, "recursive": False})
    assert_ok(r, "list_subsystems recursive=false")
    assert_contains(r.text, "**Mode:** top-level only", "non-recursive mode banner")
    assert_contains(r.text, "Subsystem.Subsystem", "the top-level subsystem still listed")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_subsystems", kind="read")
def test_explicit_language_resolves_english_synonym():
    """language='en' must resolve the en synonym ("Subsystem") into the Synonym column.

    The synonym is keyed by the language CODE ("en"), and the fixture only has an
    "en" entry valued "Subsystem". Requesting "en" must surface that synonym.
    Because the synonym value equals the name we cannot distinguish columns by the
    word alone, so we assert the full rendered row to prove FQN + Synonym + Yes all
    came out together (a broken language resolver would blank the Synonym cell).
    """
    r = call("list_subsystems", {"projectName": PROJECT, "language": "en"})
    assert_ok(r, "list_subsystems language=en")
    # Full row: FQN | Synonym | Comment(empty) | Yes | Content | Children.
    assert_contains(
        r.text,
        "| Subsystem.Subsystem | Subsystem |  | Yes |",
        "en synonym rendered in the Synonym column alongside the FQN",
    )
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_subsystems", kind="read")
def test_missing_projectname_errors_clearly():
    """Missing the required projectName must be a clear, named error.

    Guarded by JsonUtils.requireArgument -> "projectName is required". The error
    names the parameter; that is the actionable next step (supply projectName).
    """
    r = call("list_subsystems", {})
    e = assert_error(r, "missing required projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="list_subsystems", kind="read")
def test_nonexistent_project_errors_and_names_it():
    """A non-existent projectName must fail and echo the bad name back.

    listSubsystemsInternal() -> ProjectContext.exists() is false ->
    "Project not found: <projectName>". The message names the bad value.
    """
    bad = "NoSuchProject_ZZZ"
    r = call("list_subsystems", {"projectName": bad})
    e = assert_error(r, "non-existent project")
    # AUDIT: "Project not found: <name>" names the bad value but offers NO next
    # step — it does not point the caller at list_projects to discover a valid
    # project name. suggests=[] is intentional; this is a real error-quality gap
    # to fix (add a "use list_projects to find a valid project" hint).
    assert_error_quality(e, names=[bad], suggests=[])
    assert_contains(e, "not found", "error states the project was not found")
    assert_no_diff("a rejected call must not touch the project on disk")

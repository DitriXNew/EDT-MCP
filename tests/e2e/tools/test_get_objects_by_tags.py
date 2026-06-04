"""
e2e tests for get_objects_by_tags (kind: read).

Read tool: given a project and an array of tag names, returns the metadata
objects that carry ANY of those tags, plus per-tag color/description/count and a
"Tags not found" section for unknown tags. ResponseType is MARKDOWN, so the
payload is in r.text (NOT r.structured).

THE FIXTURE HAS NO TAGS. Tags live in the EDT workspace's TagStorage, not in the
git-tracked TestConfiguration project (confirmed: nothing under TestConfiguration
matches *tag*, and TagStorage starts empty). So the real, well-formed HAPPY path
here is the EMPTY-STATE response: a valid project + a tag name that does not
exist yields a "Tags not found" section listing the probe tag and a summary
"Found 0 objects across 0 tags". That is a genuine happy case (the tool walked
the storage and built the markdown), and a read tool must leave the tree clean,
so each test ends with assert_no_diff().

Negative matrix targets the tool's REAL execute() error paths, IN THE ORDER they
fire (GetObjectsByTagsTool.execute):
  1. missing projectName        -> JsonUtils.requireArgument -> "projectName is required"
  2. ProjectStateChecker.buildingErrorOrNull(projectName) guards only the transient
     BUILDING state, so a NON-existent project name falls through to the shared
     ProjectContext.notFoundMessage branch -> "Project not found: <name>. Use
     list_projects to see available projects." (NAMES the bad value AND points at
     list_projects as the discovery next step).
  3. empty/missing tags (valid project) -> parseTagsList() empty ->
     "Tags array is required. Example: [\"Important\", \"NeedsReview\"]"

Fixture inventory (TestConfiguration, English Names): Catalog.Catalog,
CommonModule.Error, CommonModule.OK, CommonForm.Form, Subsystem.Subsystem,
CommonAttribute.CommonAttribute, SessionParameter.SessionParameter -- none
tagged.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test, PROJECT,
)

# A tag name guaranteed NOT to exist in the empty TagStorage. Distinctive so the
# "Tags not found" echo is a discriminating signal (not an always-present token).
_ABSENT_TAG = "e2e_NoSuchTag_ZZZ"


# ──────────────────────────────────────────────────────────────────────────────
# Happy path (the fixture's real empty-tag state — a valid, well-formed result)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_objects_by_tags", kind="read")
def test_unknown_tag_yields_wellformed_empty_state():
    # Valid project + a tag that does not exist. The tool MUST still build a
    # well-formed markdown report: a project header, a "Tags not found" section
    # naming our probe tag, and a zero summary. If the tool were broken (no-op /
    # wrong storage / swallowed the tag list), one of these three would be absent.
    r = call("get_objects_by_tags", {"projectName": PROJECT, "tags": [_ABSENT_TAG]})
    assert_ok(r, "get_objects_by_tags unknown tag -> empty state")
    # Header proves the tool resolved the project and started the report (echoes
    # the real project name, not a canned string).
    assert_contains(r.text, "Objects by Tags in project: " + PROJECT,
                    "report header must name the resolved project")
    # The unknown tag must be reported back by name in the not-found section.
    # This is the mutation guard: a broken tag walk would not echo our probe.
    assert_contains(r.text, "Tags not found",
                    "an unknown tag must produce the 'Tags not found' section")
    assert_contains(r.text, _ABSENT_TAG,
                    "the not-found section must name the exact unknown tag we asked for")
    # The empty banner: zero objects, zero matched tags (1 asked, 1 not found).
    assert_contains(r.text, "Found 0 objects across 0 tags",
                    "summary must report the valid empty result (0 objects / 0 tags)")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_objects_by_tags", kind="read")
def test_multiple_unknown_tags_all_listed_in_not_found():
    # Two distinct unknown tags -> BOTH must appear in the not-found section and
    # the summary must still be 0/0. Proves the tool iterates the whole tags
    # array (not just the first element) and counts not-found per tag.
    second = _ABSENT_TAG + "_2"
    r = call("get_objects_by_tags", {"projectName": PROJECT, "tags": [_ABSENT_TAG, second]})
    assert_ok(r, "get_objects_by_tags two unknown tags")
    assert_contains(r.text, _ABSENT_TAG, "first unknown tag must be listed")
    assert_contains(r.text, second, "second unknown tag must be listed (full array iterated)")
    assert_contains(r.text, "Found 0 objects across 0 tags",
                    "two unknown tags still yield 0 objects / 0 matched tags")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (mandatory)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_objects_by_tags", kind="read")
def test_missing_projectname_errors_clearly():
    # Required param omitted -> JsonUtils.requireArgument -> "projectName is required".
    # (tags supplied so we isolate the projectName guard, which is checked first.)
    r = call("get_objects_by_tags", {"tags": [_ABSENT_TAG]})
    err = assert_error(r, "missing required projectName")
    # AUDIT: the message names the missing param but offers NO next step (it does
    # not mention list_projects to discover a valid project name). suggests=[] is
    # deliberate and is a fix-card to add an actionable hint.
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_objects_by_tags", kind="read")
def test_missing_tags_errors_actionably():
    # Valid project but no `tags` array -> parseTagsList() returns empty ->
    # "Tags array is required. Example: [\"Important\", \"NeedsReview\"]".
    # This error IS actionable: it shows an example of the expected shape.
    r = call("get_objects_by_tags", {"projectName": PROJECT})
    err = assert_error(r, "missing required tags array")
    # names=["Tags"] -> the message identifies the offending parameter ("Tags
    # array is required"); suggests=["Example"] -> the inline example is the
    # actionable next step (how to fix the call).
    assert_error_quality(err, names=["Tags"], suggests=["Example"],
                         ctx="missing tags names the param and gives an example")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_objects_by_tags", kind="read")
def test_empty_tags_array_errors_actionably():
    # An EMPTY array [] parses fine as JSON but yields zero tag names -> same
    # "Tags array is required" guard. This is the invalid-param-combination case:
    # the param is syntactically present yet semantically empty. A broken tool
    # that skipped the emptiness check would fall through to a 0/0 success banner
    # instead of an error -> this test would then FAIL, as intended.
    r = call("get_objects_by_tags", {"projectName": PROJECT, "tags": []})
    err = assert_error(r, "empty tags array")
    assert_error_quality(err, names=["Tags"], suggests=["Example"],
                         ctx="empty tags array rejected with an example")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_objects_by_tags", kind="read")
def test_nonexistent_project_errors():
    # A non-existent project. execute() now guards only the transient BUILDING state
    # (ProjectStateChecker.buildingErrorOrNull), so a name whose IProject does not
    # exist falls through to the "Project not found: <name>" branch, which NAMES the
    # bad value (no longer the misleading "Project does not exist. Please wait and
    # retry." that implied a transient build).
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_objects_by_tags", {"projectName": bad, "tags": [_ABSENT_TAG]})
    err = assert_error(r, "non-existent project")
    # The error names the offending value (this still fails if the tool wrongly
    # treated the bad name as valid and produced a 0/0 success banner) AND points at
    # the discovery next step: the shared ProjectContext.notFoundMessage emits
    # "Project not found: <name>. Use list_projects to see available projects.",
    # so suggests=["list_projects"] asserts that actionable tail is present.
    assert_error_quality(err, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project: names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")

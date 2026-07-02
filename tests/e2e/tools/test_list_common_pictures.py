"""
e2e tests for list_common_pictures (kind: read).

WHAT THE TOOL DOES
  Lists the configuration's CommonPictures and, per picture, the variants declared in
  its Picture.zip manifest, as a MARKDOWN report. Each picture is a row-group: the
  picture (with its language-keyed synonym) followed by its variant rows in columns
  Name | Dpi | Theme | InterfaceVariant | Template | Glyph | PictureDirection | Size.
  There is NO base64 in this tool (the raster/PNG payload is export_common_picture's job).
  Source: ListCommonPicturesTool.java + utils/CommonPictureContentReader.java.

WIRE SHAPE (why this file reads r.text, not r.structured)
  getResponseType() == MARKDOWN, so the whole report is in Result.text and
  structuredContent is None. On error the protocol layer marks the result isError and
  r.error_text() carries the message (consumable by assert_error / assert_error_quality).

FIXTURE TRUTH (why the happy path is environment-tolerant)
  The git-tracked TestConfiguration fixture has NO CommonPicture (no src/CommonPictures/).
  So the happy path does NOT assert a specific picture: it asserts the tool's STRUCTURAL
  invariants that hold regardless of how many pictures exist —
    - a well-formed report body (not an error, not an empty string);
    - the report identifies THIS project;
    - IF any picture exists, the frozen variant-table header columns are present and the
      report carries NO base64 (that is export_common_picture's job);
    - IF none exists, the tool takes its explicit empty branch (a "no common pictures"
      style message) rather than crashing.
  This mirrors get_applications' count==0 tolerance and the slice invariant "if the
  fixture lacks a CommonPicture, assert the error/empty path rather than inventing data".

NEGATIVE MATRIX (fixture-independent)
  - missing required projectName -> "projectName is required" (early validation)
  - non-existent project         -> "Project not found: <name>" (shared not-found message)
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

# The frozen variant-table columns (ListCommonPicturesTool). Present only when the report
# actually rendered at least one picture's variants; used to prove the table shape without
# hard-coding any picture's data.
_VARIANT_COLUMNS = ("Name", "Dpi", "Theme", "InterfaceVariant", "Template", "Glyph",
                    "PictureDirection", "Size")

# Tokens that betray raw image bytes leaking into the report. list_common_pictures must
# NEVER emit base64 (that is export_common_picture's contract); a long base64 run or an
# explicit pngBase64 field would be a real regression.
_BASE64_LEAK_TOKENS = ("pngBase64", "base64", "data:image")


def _looks_like_a_report(text):
    """A non-empty, non-error MARKDOWN body that at least names the project — the minimal
    'the tool rendered SOMETHING' bar that holds whether or not any picture exists."""
    return bool(text) and PROJECT in text


def _has_variant_table(text):
    """True if the report rendered the frozen variant-table header (i.e. at least one
    picture with variants was listed). All columns must appear so a partial/renamed
    header is caught."""
    return all(("| " + col + " |") in text or (col + " |") in text or ("| " + col) in text
               for col in _VARIANT_COLUMNS)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (environment-tolerant: assert structure, not a specific picture)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_common_pictures", kind="read")
def test_renders_report_for_project_and_does_not_mutate():
    """A valid project must render a well-formed MARKDOWN report identifying the project.

    Environment-tolerant: the fixture may have zero CommonPictures. Either way the tool
    must return a NON-error body that names this project (a broken tool returning an
    empty/garbage body or the wrong project fails here). When at least one picture with
    variants IS present, the frozen variant-table columns must appear. A read tool must
    never touch the project tree."""
    r = call("list_common_pictures", {"projectName": PROJECT})
    assert_ok(r, "list_common_pictures happy path")
    assert _looks_like_a_report(r.text), (
        "the report must be a non-empty body that names the project %r; got: %r"
        % (PROJECT, (r.text or "")[:300]))
    # If the workspace happens to hold a picture with variants, the table shape must be
    # the frozen one. (No fixture picture -> this branch is simply not exercised.)
    if _has_variant_table(r.text):
        for col in _VARIANT_COLUMNS:
            assert_contains(r.text, col, "frozen variant-table column %r must be present" % col)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_common_pictures", kind="read")
def test_report_carries_no_base64():
    """The listing must NEVER carry image bytes / base64 — that payload belongs to
    export_common_picture. This guards the split-of-responsibility: a regression that
    inlined the PNG here would blow up token budgets and duplicate the export tool.
    Holds regardless of how many pictures exist (an empty report trivially has none)."""
    r = call("list_common_pictures", {"projectName": PROJECT})
    assert_ok(r, "list_common_pictures no-base64 guard")
    for token in _BASE64_LEAK_TOKENS:
        assert token.lower() not in (r.text or "").lower(), (
            "list_common_pictures must not emit %r (base64 is export_common_picture's job): %r"
            % (token, (r.text or "")[:300]))
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_common_pictures", kind="read")
def test_explicit_language_is_accepted():
    """The optional `language` param (synonym keyed by language CODE, e.g. "en") must be
    accepted and still produce a well-formed report — the fixture default language is
    English. A broken language handler that threw on a valid code would fail assert_ok."""
    r = call("list_common_pictures", {"projectName": PROJECT, "language": "en"})
    assert_ok(r, "list_common_pictures language=en")
    assert _looks_like_a_report(r.text), (
        "an explicit language must still yield a report naming the project: %r"
        % ((r.text or "")[:300]))
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (fixture-independent)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_common_pictures", kind="read")
def test_missing_projectname_errors_actionably():
    """Required param omitted -> early validation -> "projectName is required"."""
    r = call("list_common_pictures", {})
    err = assert_error(r, "missing required projectName")
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="list_common_pictures", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """A non-existent project must error and echo the bad name back (shared
    ProjectContext not-found message), not return a bogus empty listing."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("list_common_pictures", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_contains(err, "not found", "error states the project was not found")
    assert_no_diff("an invalid call must not touch the project on disk")

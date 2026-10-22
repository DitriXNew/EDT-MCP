"""Hybrid rename previews and module-file text occurrences (#803).

The disposable modules are authored through tools and restored through the harness.
No slow-preview seam is needed: waitSeconds=0 exercises the job path. The unit
tests cover attachment deterministically with latches and cover the deadline.

EDT makes every whole-word text match its own change point (FullTextSearchCustomCompositeChange
holds one FullTextSearchSourceFileChange per match). WHICH seeded occurrences its text search keeps
is EDT's and varies by version: on 2026.2.0 only the comment is reported, never the string literal.
So the expected text points are derived from what the settled preview reports - every one must sit
on a seeded comment/literal line - rather than assumed from the seed.

EDT's reference index lags the seed: the first preview right after seeding can lack the semantic
reference row, and its contentHash then differs from the plan a confirm rebuilds a few seconds later.
Every preview whose hash a confirm relies on is therefore taken from _settled_preview: repeated until
the seeded reference row is present and two consecutive previews give the same contentHash.
"""

import re
import time
from contextlib import contextmanager

from harness import (
    PENDING_PREFIX,
    PROJECT,
    assert_contains,
    assert_error,
    assert_no_diff,
    assert_not_contains,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    follow_pending,
    poll_disk_contains,
    rename_call,
    reset_model,
    settle_or_fail,
    split_markdown_row,
    tree_snapshot,
)
from tools.test_rename_metadata_object import _frontmatter_value


_TARGET = "E2E803TextHit"
_RENAMED = "E2E803TextHitRenamed"
_USER = "E2E803TextHitUser"
_USER_PATH = "CommonModules/%s/Module.bsl" % _USER
_COMMENT = "// %s is mentioned here only as text" % _TARGET
_LITERAL = 'S = "%s";' % _TARGET
_REFERENCE = "X = %s.Marker();" % _TARGET
_USER_SOURCE = ("Procedure Probe() Export\n    %s\n    %s\n    %s\nEndProcedure\n"
                % (_REFERENCE, _COMMENT, _LITERAL))
_WHOLE_WORD = re.compile(r"(?<![0-9A-Za-z_])%s(?![0-9A-Za-z_])" % re.escape(_TARGET))


_SOURCE_LINES = _USER_SOURCE.splitlines()
_REFERENCE_LINE = next(number for number, line in enumerate(_SOURCE_LINES, 1) if _REFERENCE in line)
# How long a freshly seeded fixture may take until EDT's reference index shows the seeded reference
# and the plan stops moving; on stand C (2026.2.0) the reference appeared within ~20 s.
_SETTLE_PREVIEW_SECONDS = 90
_SETTLE_PREVIEW_PAUSE = 3


def _seeded_text_lines():
    """1-based lines of the user module where the old name occurs as text, not as a reference.

    Each such whole-word match is one text change point; the reference line is EDT's semantic
    reference and is reported with Kind reference."""
    return {number for number, line in enumerate(_USER_SOURCE.splitlines(), 1)
            if _WHOLE_WORD.search(line) and _REFERENCE not in line}


def _preview(**options):
    result = rename_call(dict({
        "projectName": PROJECT, "objectFqn": "CommonModule." + _TARGET,
        "newName": _RENAMED, "maxResults": 0,
    }, **options))
    assert_ok(result, "preview the disposable common module")
    assert_contains(result.text, "action: preview", "the answer must be the preview")
    return result.text


def _has_seeded_reference(rows):
    return any(row["kind"] == "reference" and row["line"] == str(_REFERENCE_LINE)
               and row["fqn"] == "CommonModule." + _USER for row in rows)


def _settled_preview(**options):
    """A preview whose plan a confirm will rebuild: the seeded reference is visible and the hash is stable.

    Repeats the preview (bounded by _SETTLE_PREVIEW_SECONDS) until the seeded reference row is present
    AND two consecutive previews carry the same contentHash, and returns the later one."""
    start = time.time()
    history = []
    previous = None
    while True:
        preview = _preview(**options)
        content_hash = _frontmatter_value(preview, "contentHash")
        has_reference = _has_seeded_reference(_rows(preview))
        history.append((round(time.time() - start), content_hash, has_reference))
        if has_reference and content_hash == previous:
            return preview
        previous = content_hash if has_reference else None
        if time.time() - start >= _SETTLE_PREVIEW_SECONDS:
            raise AssertionError(
                "the preview never settled within %ds (seconds, contentHash, seeded reference row "
                "present): %s\nlast preview:\n%s" % (_SETTLE_PREVIEW_SECONDS, history, preview))
        time.sleep(_SETTLE_PREVIEW_PAUSE)


def _rows(text):
    rows = []
    for line in text.splitlines():
        cells = split_markdown_row(line)
        if len(cells) >= 9 and cells[0].isdigit():
            assert len(cells) == 10, "Kind must be appended to the nine existing columns: " + line
            rows.append(dict(index=int(cells[0]), type=cells[1], line=cells[3], default=cells[5],
                             skippable=cells[6], fqn=cells[8], kind=cells[9]))
    return rows


def _text_indices(rows):
    """Distinct '#' indices of the text rows; asserts each sits on a seeded comment/literal line."""
    seeded = _seeded_text_lines()
    for row in rows:
        if row["kind"] == "text":
            assert row["line"].isdigit() and int(row["line"]) in seeded, (
                "a text row must point at a seeded comment or literal line %s: %r" % (sorted(seeded), row))
    return {row["index"] for row in rows if row["kind"] == "text"}


def _text_lines(rows):
    """The seeded source lines (stripped) EDT reported as text occurrences; at least one is required.

    Which seeded occurrences are text points depends on the EDT version (2026.2.0 reports the comment
    but not the string literal), so the expectation is taken from the preview, bounded by the seed."""
    lines = {_SOURCE_LINES[int(row["line"]) - 1].strip() for row in rows if row["kind"] == "text"}
    assert lines, "the seed must yield at least one text occurrence: %r" % rows
    return lines


def _renamed(line):
    return _WHOLE_WORD.sub(_RENAMED, line)


def _footer_lists(preview, indices):
    footer = [line for line in preview.splitlines() if line.startswith("> Text occurrences")]
    assert len(footer) == 1, "exactly one text footer line:\n" + preview
    listed = {int(value) for value in re.findall(r"#(\d+)", footer[0])}
    assert listed == indices, "the footer must list every text index %s, got %s" % (
        sorted(indices), sorted(listed))
    return footer[0]


def _assert_renamed_object():
    poll_disk_contains("src/CommonModules/%s/%s.mdo" % (_RENAMED, _RENAMED),
                       "<name>%s</name>" % _RENAMED, timeout=30,
                       ctx="the renamed object's descriptor must land on disk")
    poll_disk_contains("src/Configuration/Configuration.mdo", "CommonModule." + _RENAMED,
                       timeout=30, ctx="the configuration must reference the renamed object")
    modules = call("get_metadata_objects", {
        "projectName": PROJECT, "metadataType": "commonModules", "nameFilter": _TARGET,
    })
    assert_ok(modules, "read back the renamed common module from the model")
    assert_contains(modules.text, "| %s |" % _RENAMED, "the renamed object must exist in the model")
    assert_not_contains(modules.text, "| %s |" % _TARGET, "the old object name must be gone")


@contextmanager
def _seed_modules():
    """Independent modules; shared CascadeUser and its pinned row count stay untouched."""
    try:
        sources = {
            _TARGET: "Function Marker() Export\n    Return 1;\nEndFunction\n",
            _USER: _USER_SOURCE,
        }
        for name, source in sources.items():
            assert_ok(call("create_metadata", {
                "projectName": PROJECT, "fqn": "CommonModule." + name,
                "commonModuleKind": "Server",
            }), "create disposable common module " + name)
            settle_or_fail("write the disposable common module")
            path = "CommonModules/%s/Module.bsl" % name
            assert_ok(call("write_module_source", {
                "projectName": PROJECT, "modulePath": path, "mode": "replace",
                "overwrite": True, "source": source,
            }), "seed disposable module source " + name)
            poll_disk_contains("src/" + path, "End", timeout=30,
                               ctx="the seeded module must be exported before preview")
        settle_or_fail("build the disposable rename fixture")
        yield
    finally:
        reset_model()


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_wait_zero_returns_a_job_whose_hash_confirms_with_an_unknown_index():
    with _seed_modules():
        settled_hash = _frontmatter_value(_settled_preview(), "contentHash")
        before = tree_snapshot()
        pending = call("rename_metadata_object", {
            "projectName": PROJECT, "objectFqn": "CommonModule." + _TARGET,
            "newName": _RENAMED, "waitSeconds": 0, "maxResults": 0,
        })
        assert_ok(pending, "submit preview without waiting")
        assert_contains(pending.text, PENDING_PREFIX, "zero wait must exercise background retrieval")
        finished = follow_pending(pending, "retrieve the background preview")
        assert_ok(finished, "the preview job must finish successfully")
        preview = finished.text
        assert_contains(preview, "action: preview", "job Result must be the preview")
        assert_contains(preview, "## Change Points", "job Result must contain the index table")
        content_hash = _frontmatter_value(preview, "contentHash")
        assert content_hash == settled_hash, (
            "the job's Result must carry the contentHash an inline preview of the settled plan gave "
            "(%s), got %s" % (settled_hash, content_hash))
        assert_tree_unchanged(before, "a background preview must not change seeded files")
        confirm = rename_call({
            "projectName": PROJECT, "objectFqn": "CommonModule." + _TARGET,
            "newName": _RENAMED, "confirm": True, "disableIndices": "1000000",
            "expectedHash": content_hash,
        })
        assert_ok(confirm, "confirm the job-built plan synchronously")
        assert_contains(confirm.text, "action: executed", "the existing selective confirm must execute")
        assert_contains(confirm.text, "unknownIndices: [1000000]", "unknown indices retain their existing meaning")
        _assert_renamed_object()
        poll_disk_contains("src/" + _USER_PATH, _RENAMED + ".Marker()", timeout=30,
                           ctx="confirm must rewrite the semantic reference")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_text_rows_have_kind_counters_context_and_a_footer_listing_their_indices():
    with _seed_modules():
        before = tree_snapshot()
        preview = _settled_preview()
        rows = _rows(preview)
        text_indices = _text_indices(rows)
        text_lines = _text_lines(rows)
        reference_indices = {row["index"] for row in rows if row["kind"] == "reference"}
        assert len(text_indices) == len({row["line"] for row in rows if row["kind"] == "text"}), (
            "each reported text line holds one whole-word match, so one change point:\n" + preview)
        assert len(reference_indices) == 1, "one semantic reference leaf:\n" + preview
        assert _frontmatter_value(preview, "textChanges") == str(len(text_indices))
        assert _frontmatter_value(preview, "referenceChanges") == "1"
        assert_contains(preview, "Kind: text", "text code-context blocks must label their kind")
        for line in text_lines:
            assert_contains(preview, line, "context must show each reported whole-word text hit")
        footer = _footer_lists(preview, text_indices)
        assert_contains(footer, "pass them in disableIndices", "include points at disableIndices")
        assert all(row["default"] == "✅" for row in rows if row["kind"] == "text")
        assert_tree_unchanged(before, "the text-aware preview is read-only")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_exclude_disables_text_in_both_walks_and_preserves_comment_and_literal_on_disk():
    with _seed_modules():
        preview = _settled_preview(textOccurrences="exclude", waitSeconds=0)
        rows = _rows(preview)
        text_indices = _text_indices(rows)
        _text_lines(rows)
        assert _frontmatter_value(preview, "textChanges") == str(len(text_indices))
        assert all(row["default"] == "❌" for row in rows if row["kind"] == "text")
        assert all(row["default"] == "✅" for row in rows if row["kind"] == "reference")
        footer = _footer_lists(preview, text_indices)
        assert_contains(footer, "textOccurrences=exclude has already switched them off",
                        "under exclude the footer must not ask for disableIndices")
        confirm = rename_call({
            "projectName": PROJECT, "objectFqn": "CommonModule." + _TARGET,
            "newName": _RENAMED, "confirm": True, "textOccurrences": "exclude",
            "disableIndices": "1000000", "expectedHash": _frontmatter_value(preview, "contentHash"),
        })
        assert_ok(confirm, "confirm under the same exclude policy and enforce the hash walk")
        assert_contains(confirm.text, "textOccurrencesSkipped: %d" % len(text_indices),
                        "count implicit skips separately, one per text change point")
        assert_contains(confirm.text, "disabledCount: 0", "an unknown requested index disabled nothing")
        _assert_renamed_object()
        disk = poll_disk_contains("src/" + _USER_PATH, _RENAMED + ".Marker()", timeout=30,
                                  ctx="semantic reference must still be renamed")
        assert_contains(disk, _COMMENT, "exclude must preserve the comment on disk")
        assert_contains(disk, _LITERAL,
                        "the literal is either not a text point or excluded, so it stays unchanged")
        source = call("read_module_source", {"projectName": PROJECT, "modulePath": _USER_PATH})
        assert_ok(source, "read the confirmed module from the model")
        assert_contains(source.text, _COMMENT, "model must agree with the disk")
        assert_contains(source.text, _LITERAL, "model must agree with the disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_default_include_rewrites_reference_comment_and_literal():
    with _seed_modules():
        preview = _settled_preview()
        text_lines = _text_lines(_rows(preview))
        untouched = {_SOURCE_LINES[number - 1].strip() for number in _seeded_text_lines()} - text_lines
        result = rename_call({
            "projectName": PROJECT, "objectFqn": "CommonModule." + _TARGET,
            "newName": _RENAMED, "confirm": True, "disableIndices": "1000000",
            "expectedHash": _frontmatter_value(preview, "contentHash"),
        })
        assert_ok(result, "default include confirm")
        _assert_renamed_object()
        disk = poll_disk_contains("src/" + _USER_PATH, _RENAMED + ".Marker()", timeout=30,
                                  ctx="semantic reference must be renamed")
        for line in text_lines:
            assert_contains(disk, _renamed(line), "default include must rename every reported text hit")
            assert_not_contains(disk, line, "a reported text hit must not survive an include confirm")
        for line in untouched:
            assert_contains(disk, line, "a seeded line the preview did not list must stay unchanged")
        assert_not_contains(result.text, "textOccurrencesSkipped:", "include has no implicit text skip")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_mixed_flags_refuse_the_stale_hash_and_leave_the_seed_unchanged():
    with _seed_modules():
        include_hash = _frontmatter_value(_settled_preview(textOccurrences="include"), "contentHash")
        before = tree_snapshot()
        result = rename_call({
            "projectName": PROJECT, "objectFqn": "CommonModule." + _TARGET,
            "newName": _RENAMED, "confirm": True, "textOccurrences": "exclude",
            "disableIndices": "1000000", "expectedHash": include_hash,
        })
        error = assert_error(result, "mixed preview/confirm flags")
        assert_contains(error, "preview is stale", "different enabled plans must refuse")
        assert_tree_unchanged(before, "a stale preview must never execute")
        # The refusal must be the flag's, not a moving plan's: the include plan is still the same.
        again = _frontmatter_value(_preview(textOccurrences="include"), "contentHash")
        assert again == include_hash, (
            "the include plan moved (%s -> %s), so the refusal does not prove the flag is signed"
            % (include_hash, again))


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_wait_seconds_with_confirm_is_refused_before_any_rename():
    result = rename_call({
        "projectName": PROJECT, "objectFqn": "CommonModule.CascadeEn", "newName": "Reckoner",
        "confirm": True, "waitSeconds": 5,
    })
    error = assert_error(result, "confirm cannot have a preview polling wait")
    assert_contains(error, "preview only", "refusal must explain which phase accepts waitSeconds")
    assert_contains(error, "synchronously under timeout", "refusal must name confirm's actual bound")
    assert_no_diff()


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_invalid_preview_options_are_refused_without_mutating_the_fixture():
    for option, value in (("waitSeconds", -1), ("waitSeconds", 46), ("waitSeconds", 1.5),
                          ("textOccurrences", "neither")):
        args = {"projectName": PROJECT, "objectFqn": "CommonModule.CascadeEn", "newName": "Reckoner"}
        args[option] = value
        error = assert_error(rename_call(args), "invalid preview option")
        assert_contains(error, option, "the refusal must name the invalid option")
        assert_contains(error, "must be", "the refusal must explain accepted values")
        assert_no_diff()


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_default_wait_can_still_return_the_preview_inline_on_the_fixture():
    settle_or_fail("preview the small fixture inline")
    result = call("rename_metadata_object", {
        "projectName": PROJECT, "objectFqn": "CommonModule.CascadeEn", "newName": "Reckoner",
    })
    assert_ok(result, "preview the small fixture under the default 45s wait")
    assert_contains(result.text, "action: preview", "a short preview must still answer inline")
    assert_not_contains(result.text, PENDING_PREFIX, "short previews preserve their plain answer")
    assert_no_diff()

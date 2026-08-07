"""
e2e tests for write_module_source (kind: write).

EXEMPLAR — write tool. Covers all three write modes with the effect proven the
strongest way per mode, plus the negative matrix:
  * append       -> on-disk git diff + read_module_source read-back.
  * replace      -> on-disk git diff + read-back (needs overwrite over the
                    empty-but-existing OK module; the lost-update guard otherwise
                    rejects a blind full replace — see the reject test).
  * searchReplace-> seed content, swap a unique fragment, prove the swap by disk
                    diff + read-back (old value gone, surrounding code intact).
  * negatives    -> rejected write must not touch disk; missing required param;
                    stale searchReplace (oldSource absent); blind replace without
                    a precondition — each with an error-quality assertion.

Fixture target: CommonModules/OK/Module.bsl (empty in the committed baseline).
The orchestrator resets the fixture before every test, so each starts empty.
"""

import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains,
    assert_diff_contains, assert_no_diff, e2e_test, PROJECT, _fail,
)

MODULE = "CommonModules/OK/Module.bsl"

# The opaque revision token read_module_source / read_method_source emit in their
# YAML frontmatter (16 lowercase hex chars, possibly double-quoted by the YAML escaper).
_HASH_RE = re.compile(r'contentHash:\s*"?([0-9a-f]{16})"?')


def _read_content_hash(module=MODULE):
    """Read the module and return its frontmatter contentHash token (or fail)."""
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": module})
    assert_ok(src, "read for contentHash")
    m = _HASH_RE.search(src.text or "")
    if not m:
        from harness import E2EAssertion
        raise E2EAssertion("read_module_source did not emit a contentHash:\n%s" % (src.text or "")[:300])
    return m.group(1)


@e2e_test(tool="write_module_source", kind="write")
def test_append_lands_on_disk():
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "append", "source": "// e2e_probe_append\n",
    })
    assert_ok(r, "append happy path")
    # On-disk truth: the new line must be in the .bsl file on disk (git sees it).
    assert_diff_contains("// e2e_probe_append", "append must persist to the .bsl on disk")
    # Read-back: the model now serves the appended line too.
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(src, "read-back after append")
    assert_contains(src.text, "// e2e_probe_append", "read-back shows the appended line")


@e2e_test(tool="write_module_source", kind="write")
def test_missing_projectname_errors_clearly_and_no_write():
    r = call("write_module_source", {
        "modulePath": MODULE, "mode": "append", "source": "// x\n",
    })
    err = assert_error(r, "missing required projectName")
    assert_error_quality(err, names=["projectName"], ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="write_module_source", kind="write")
def test_searchreplace_stale_oldsource_errors_and_no_write():
    # Default-ish mode that requires oldSource; against an empty module the marker
    # cannot be found, so the write must be rejected and the disk left untouched.
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "searchReplace", "oldSource": "NOPE_NOT_PRESENT_XYZ", "source": "x",
    })
    err = assert_error(r, "searchReplace with absent oldSource")
    # Error must be clear and not a bare 'Error'/stack trace. (Whether it names the
    # exact oldSource value is an AUDIT point — tighten once the real message is seen.)
    assert_error_quality(err, ctx="stale oldSource produces a clear error")
    assert_no_diff("a rejected write must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Mode coverage — replace (full file) and a SUCCESSFUL searchReplace, each proven
# on disk AND by read_module_source read-back. Russian content exercises the BSL
# round-trip with non-ASCII identifiers.
# ──────────────────────────────────────────────────────────────────────────────

# A small, syntactically valid module (balanced Procedure/EndProcedure so the
# tool's built-in syntax check passes) with one easily-targeted body line.
_SEED = "Процедура Demo() Экспорт\n\tЗначение = 1;\nКонецПроцедуры\n"


@e2e_test(tool="write_module_source", kind="write")
def test_replace_overwrites_whole_file_and_readback_matches():
    # OK is empty-but-existing in the baseline, so a full replace needs the explicit
    # lost-update override (overwrite=true); the blind case is rejected (test below).
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(r, "replace happy path (overwrite=true)")
    # On-disk truth: the whole body now lives in the .bsl on disk.
    assert_diff_contains("Значение = 1;", "replace must persist the new body to disk")
    # Read-back: the model serves exactly the replaced content. A no-op replace would
    # leave the module empty and fail both checks below.
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(src, "read-back after replace")
    assert_contains(src.text, "Процедура Demo() Экспорт", "read-back shows the replaced procedure header")
    assert_contains(src.text, "Значение = 1;", "read-back shows the replaced body line")


@e2e_test(tool="write_module_source", kind="write")
def test_searchreplace_swaps_found_fragment_and_readback_matches():
    # Seed known content first (replace needs overwrite over the empty-existing OK).
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(seed, "seed content for searchReplace")

    # Swap a fragment that occurs exactly once. searchReplace must find it and replace
    # only that occurrence (it is surgical, not a full rewrite).
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "searchReplace", "oldSource": "Значение = 1;", "source": "Значение = 42;",
    })
    assert_ok(r, "searchReplace happy path (oldSource found exactly once)")
    # On-disk + read-back: the fragment was swapped and the old value is gone.
    assert_diff_contains("Значение = 42;", "searchReplace must persist the swapped fragment to disk")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(src, "read-back after searchReplace")
    assert_contains(src.text, "Значение = 42;", "read-back shows the replaced value")
    assert_not_contains(src.text, "Значение = 1;", "read-back must not retain the old value")
    # Surgical: the surrounding procedure is untouched.
    assert_contains(src.text, "Процедура Demo() Экспорт", "searchReplace preserves the rest of the module")


@e2e_test(tool="write_module_source", kind="write")
def test_replace_over_existing_without_precondition_is_rejected_and_no_write():
    # OK exists (empty) in the baseline. A blind full replace — no overwrite, no
    # expectedSource — must be rejected by the lost-update guard and leave disk intact.
    # (The guard itself is owned by write-replace-mode-precondition; here we prove the
    # tool actually enforces it and steers the caller to the right next step.)
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": "// blind overwrite attempt\n",
    })
    err = assert_error(r, "blind replace over an existing module")
    # Message names the safe alternatives. NB the JSON error channel HTML-escapes '=',
    # so assert on the bare tokens 'expectedSource' / 'overwrite', never 'overwrite=true'.
    assert_error_quality(err, suggests=["expectedSource", "overwrite"],
                         ctx="blind replace steers to expectedSource / overwrite / searchReplace")
    assert_no_diff("a rejected replace must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Lost-update + ambiguity guards — both the ACCEPT and the REJECT branches.
# These tests seed content first (a real write), so they verify "no further change"
# via read-back, NOT assert_no_diff (the seed already dirtied the tree).
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="write_module_source", kind="write")
def test_replace_with_matching_expectedsource_succeeds():
    # Optimistic-lock HAPPY path: a guarded replace whose expectedSource equals the
    # current content proceeds without overwrite=true. Proves the guard ACCEPTS a
    # correct precondition (complements the reject test above).
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(seed, "seed content")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace",
        "source": "Процедура Renewed() Экспорт\nКонецПроцедуры\n", "expectedSource": _SEED,
    })
    assert_ok(r, "replace with a matching expectedSource must be accepted")
    assert_diff_contains("Процедура Renewed()", "the guarded replace must persist to disk")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Процедура Renewed()", "read-back shows the guarded replacement")
    assert_not_contains(src.text, "Demo", "the previous Demo procedure was replaced")


@e2e_test(tool="write_module_source", kind="write")
def test_replace_with_stale_expectedsource_rejected_and_keeps_content():
    # Lost-update REJECT: expectedSource no longer matches current content (a concurrent
    # edit happened) -> the replace is refused and the seeded content must survive intact.
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(seed, "seed content")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace",
        "source": "// CLOBBERED_e2e\n", "expectedSource": "STALE CONTENT THAT DOES NOT MATCH",
    })
    err = assert_error(r, "replace with a stale expectedSource")
    assert_error_quality(err, suggests=["expectedSource", "match"],
                         ctx="stale expectedSource names the param + explains the concurrent edit")
    # The rejected write must NOT have clobbered the seeded content.
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Значение = 1;",
                    "the seeded content must survive a rejected stale-precondition replace")
    assert_not_contains(src.text, "CLOBBERED_e2e",
                        "the rejected replace must not have written its payload")


@e2e_test(tool="write_module_source", kind="write")
def test_searchreplace_ambiguous_oldsource_rejected_and_keeps_content():
    # Ambiguity guard: an oldSource that matches more than once is refused (the tool
    # cannot know which occurrence to swap) and nothing is partially applied.
    dup = "Процедура Demo() Экспорт\n\tЗначение = 1;\n\tЗначение = 1;\nКонецПроцедуры\n"
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": dup, "overwrite": True,
    })
    assert_ok(seed, "seed content with a duplicated fragment")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "searchReplace",
        "oldSource": "Значение = 1;", "source": "Значение = 9;",
    })
    err = assert_error(r, "searchReplace with an ambiguous oldSource (2 occurrences)")
    assert_error_quality(err, suggests=["multiple times", "specific"],
                         ctx="ambiguous oldSource reports the count + asks for a more specific fragment")
    # Nothing was swapped: both originals remain, the new value never appears.
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_not_contains(src.text, "Значение = 9;", "an ambiguous searchReplace must not partially apply")
    assert_contains(src.text, "Значение = 1;", "the original duplicated fragment must remain")


# ──────────────────────────────────────────────────────────────────────────────
# expectedHash — the cheap optimistic lock (contentHash round-trip from a read).
# Both branches: a matching token ACCEPTS, a stale token REJECTS without clobbering.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="write_module_source", kind="write")
def test_replace_with_matching_expectedhash_succeeds():
    # Round-trip: seed -> read the contentHash -> guarded replace with that exact token.
    # The token still matches the unchanged file, so the write proceeds WITHOUT
    # overwrite=true (the hash is the proof the agent saw the current state).
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(seed, "seed content")
    token = _read_content_hash()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace",
        "source": "Процедура Rehashed() Экспорт\nКонецПроцедуры\n", "expectedHash": token,
    })
    assert_ok(r, "replace with a matching expectedHash must be accepted")
    assert_diff_contains("Процедура Rehashed()", "the hash-guarded replace must persist to disk")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Процедура Rehashed()", "read-back shows the guarded replacement")
    assert_not_contains(src.text, "Demo", "the previous Demo procedure was replaced")


@e2e_test(tool="write_module_source", kind="write")
def test_replace_with_stale_expectedhash_rejected_and_keeps_content():
    # A wrong/stale token means the file changed since the agent read it: the write is
    # refused with a re-read steer and the seeded content must survive untouched.
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(seed, "seed content")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace",
        "source": "// CLOBBERED_via_hash_e2e\n", "expectedHash": "0123456789abcdef",
    })
    err = assert_error(r, "replace with a stale expectedHash")
    assert_error_quality(err, names=["expectedHash"], suggests=["read_module_source"],
                         ctx="stale expectedHash names the param + steers to re-read")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Значение = 1;",
                    "the seeded content must survive a rejected stale-hash replace")
    assert_not_contains(src.text, "CLOBBERED_via_hash_e2e",
                        "the rejected replace must not have written its payload")


@e2e_test(tool="write_module_source", kind="write")
def test_searchreplace_with_matching_expectedhash_succeeds():
    # expectedHash is mode-agnostic: it also guards searchReplace. A matching token plus
    # a found oldSource swaps the fragment; proves the cheap guard does not block the
    # normal surgical edit.
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SEED, "overwrite": True,
    })
    assert_ok(seed, "seed content")
    token = _read_content_hash()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "searchReplace",
        "oldSource": "Значение = 1;", "source": "Значение = 7;", "expectedHash": token,
    })
    assert_ok(r, "searchReplace with a matching expectedHash must be accepted")
    assert_diff_contains("Значение = 7;", "the hash-guarded searchReplace must persist to disk")


# ──────────────────────────────────────────────────────────────────────────────
# dryRun (preview, no write)
# ──────────────────────────────────────────────────────────────────────────────

_DRYRUN_SRC = "Процедура Демо() Экспорт\n\tВозврат;\nКонецПроцедуры\n"


@e2e_test(tool="write_module_source", kind="write")
def test_dryrun_previews_without_writing():
    """dryRun runs the full pipeline (guards + compute + syntax check) but writes NOTHING:
    the response is a preview echoing the would-be content, and the project on disk is
    untouched. Uses replace+overwrite so the ONLY reason nothing lands is dryRun itself
    (a bare replace would be rejected by the lost-update guard, masking the point)."""
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "overwrite": True, "source": _DRYRUN_SRC, "dryRun": True,
    })
    assert_ok(r, "dryRun preview must succeed")
    assert_contains(r.text, "status: preview", "response must be marked status: preview")
    assert_contains(r.text, "written: false", "preview must state nothing was written")
    assert_contains(r.text, "Процедура Демо", "preview must echo the would-be content")
    assert_no_diff("a dryRun must not touch the project on disk")
    # An independent read-back proves the preview did NOT persist (belt and suspenders).
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_not_contains(src.text, "Процедура Демо", "previewed content must NOT be on disk")


@e2e_test(tool="write_module_source", kind="write")
def test_dryrun_syntax_error_is_reported_and_no_write():
    """The BSL syntax check runs in dryRun too: previewing unbalanced BSL returns the
    syntax error (not a preview) and still writes nothing."""
    bad = "Процедура Broken() Экспорт\n\t// no EndProcedure\n"
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "overwrite": True, "source": bad, "dryRun": True,
    })
    assert_error(r, "a dryRun over invalid BSL must report the syntax error")
    assert_no_diff("a failed dryRun must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# replaceMethod (swap a whole method by name)
# ──────────────────────────────────────────────────────────────────────────────

_TWO_METHODS = (
    "Функция Add(A, B) Экспорт\n"
    "\tВозврат A + B;\n"
    "КонецФункции\n"
    "\n"
    "Процедура Test() Экспорт\n"
    "\tРезультат = Add(1, 2);\n"
    "КонецПроцедуры\n"
)


def _seed_two_methods():
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _TWO_METHODS, "overwrite": True,
    })
    assert_ok(seed, "seed a two-method module")


@e2e_test(tool="write_module_source", kind="write")
def test_replacemethod_swaps_named_method_only():
    """replaceMethod swaps ONE method by name: the new body lands, the old body is gone,
    and the OTHER method is left intact. No oldSource needed (expectedHash IS needed - see
    test_replacemethod_requires_expected_hash)."""
    _seed_two_methods()
    token = _read_content_hash()
    new_test = ("Процедура Test() Экспорт\n"
                "\tСлагаемое = 2;\n"
                "\tAdd(1, Слагаемое);\n"
                "КонецПроцедуры")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replaceMethod", "methodName": "Test", "source": new_test, "expectedHash": token,
    })
    assert_ok(r, "replaceMethod must succeed for an existing method")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Слагаемое = 2;", "the new method body must be on disk")
    assert_not_contains(src.text, "Результат = Add(1, 2);", "the old method body must be gone")
    assert_contains(src.text, "Функция Add(A, B)", "the untouched sibling method must remain")


@e2e_test(tool="write_module_source", kind="write")
def test_replacemethod_unknown_method_rejected_and_keeps_content():
    """An unknown methodName is rejected with the available method names, and nothing is
    written (the seeded content survives)."""
    _seed_two_methods()
    token = _read_content_hash()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replaceMethod", "methodName": "NoSuchMethod_e2e",
        "source": "Процедура X() Экспорт\nКонецПроцедуры", "expectedHash": token,
    })
    err = assert_error(r, "replaceMethod for an absent method must be rejected")
    # Actionable: the error lists the real method names so the caller can correct it.
    assert_contains(err, "Test", "not-found error should list the available methods")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Результат = Add(1, 2);",
                    "a rejected replaceMethod must not have altered the module")


@e2e_test(tool="write_module_source", kind="write")
def test_replacemethod_missing_methodname_rejected():
    """replaceMethod without methodName is rejected before any file work."""
    _seed_two_methods()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replaceMethod", "source": "Процедура X() Экспорт\nКонецПроцедуры",
    })
    err = assert_error(r, "replaceMethod without methodName must be rejected")
    assert_contains(err, "methodName", "the error must name the missing methodName param")


@e2e_test(tool="write_module_source", kind="write")
def test_replacemethod_requires_expected_hash():
    """replaceMethod blindly swaps the whole method body, so (unlike insertBefore/insertAfter,
    which only ADD text) it requires expectedHash - without one, a stale read could silently
    clobber a concurrent edit to the same method. Rejected before the method lookup even runs,
    and nothing is written."""
    _seed_two_methods()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replaceMethod", "methodName": "Test",
        "source": "Процедура Test() Экспорт\nКонецПроцедуры",
    })
    err = assert_error(r, "replaceMethod without expectedHash must be rejected")
    assert_error_quality(err, names=["expectedHash"], suggests=["read_module_source"],
                          ctx="missing expectedHash names the param + steers to re-read")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Результат = Add(1, 2);",
                    "a rejected replaceMethod must not have altered the module")


@e2e_test(tool="write_module_source", kind="write")
def test_replacemethod_dryrun_previews_without_writing():
    """replaceMethod honors dryRun: the preview shows the swapped module, disk is untouched."""
    _seed_two_methods()
    token = _read_content_hash()
    new_test = "Процедура Test() Экспорт\n\tВозврат;\nКонецПроцедуры"
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replaceMethod", "methodName": "Test", "source": new_test, "dryRun": True,
        "expectedHash": token,
    })
    assert_ok(r, "replaceMethod dryRun must succeed")
    assert_contains(r.text, "status: preview", "must be a preview")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Результат = Add(1, 2);",
                    "a dryRun replaceMethod must NOT change the module on disk")


# ──────────────────────────────────────────────────────────────────────────────
# insertBefore / insertAfter (add a method next to a named anchor)
# ──────────────────────────────────────────────────────────────────────────────

_NEW_SUB = "\nФункция Sub(A, B) Экспорт\n\tВозврат A - B;\nКонецФункции\n"


def _order_ok(text, first, second, third):
    """True when first < second < third by first-occurrence index (all must be present)."""
    i1, i2, i3 = text.find(first), text.find(second), text.find(third)
    return -1 < i1 < i2 < i3


@e2e_test(tool="write_module_source", kind="write")
def test_insertafter_places_method_after_anchor():
    """insertAfter splices source right after the anchor method: the new method lands
    BETWEEN the anchor (Add) and the next method (Test), and both originals survive."""
    _seed_two_methods()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "insertAfter", "methodName": "Add", "source": _NEW_SUB,
    })
    assert_ok(r, "insertAfter must succeed for an existing anchor")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Функция Sub(A, B)", "the new method must be on disk")
    assert_contains(src.text, "Функция Add(A, B)", "the anchor method must survive")
    assert_contains(src.text, "Процедура Test()", "the sibling method must survive")
    if not _order_ok(src.text, "Функция Add", "Функция Sub", "Процедура Test"):
        _fail("insertAfter must place Sub AFTER Add and BEFORE Test")


@e2e_test(tool="write_module_source", kind="write")
def test_insertbefore_places_method_before_anchor():
    """insertBefore splices source right before the anchor method: the new method lands
    BEFORE Test (and after Add)."""
    _seed_two_methods()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "insertBefore", "methodName": "Test", "source": _NEW_SUB,
    })
    assert_ok(r, "insertBefore must succeed for an existing anchor")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_contains(src.text, "Функция Sub(A, B)", "the new method must be on disk")
    if not _order_ok(src.text, "Функция Add", "Функция Sub", "Процедура Test"):
        _fail("insertBefore Test must place Sub before Test (and after Add)")


@e2e_test(tool="write_module_source", kind="write")
def test_insert_unknown_anchor_rejected_and_keeps_content():
    """insert modes share the method-not-found guard: an unknown anchor is rejected with
    the available method names and nothing is written."""
    _seed_two_methods()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "insertAfter", "methodName": "NoSuchAnchor_e2e", "source": _NEW_SUB,
    })
    err = assert_error(r, "insertAfter for an absent anchor must be rejected")
    assert_contains(err, "Add", "not-found error should list the available methods")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_not_contains(src.text, "Функция Sub", "a rejected insert must not have written anything")

"""
e2e tests for write_module_source (kind: write).

EXEMPLAR — write tool. Covers all six write modes with the effect proven the
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

ADOPTED OBJECTS (#711, at the end): a write into the module of an object adopted into the
extension fixture records that module's state Extended in the owner's .mdo, proven on disk; a
Checked state and a root without its extension block are left as they are, and the write answers
a success that names them; the extension's own modules and base modules stay untouched.
"""

import os
import re
import shutil
import tempfile
import time

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains,
    assert_diff_contains, assert_diff_paths, assert_no_diff, e2e_test, settle_or_fail,
    calls_aborted, read_disk, PROJECT_DIR, PROJECT_REL,
    poll_disk_contains_all, read_fixture_file, restore_extension_fixture,
    E2EAssertion, E2ECallTimeout, PROJECT, REPO_ROOT, TESTS_PROJECT, TESTS_PROJECT_REL,
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


def _read_module_text(module=MODULE):
    """Read the complete module response for exact before/after comparisons."""
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": module})
    assert_ok(src, "read complete module")
    return src.text or ""


def _assert_readback_unchanged(before, context):
    after = _read_module_text()
    if after != before:
        raise E2EAssertion("%s changed the module despite being rejected\nbefore:\n%s\nafter:\n%s"
                           % (context, before[:500], after[:500]))


def _assert_order(text, parts, context):
    previous = -1
    for part in parts:
        current = text.find(part, previous + 1)
        if current < 0:
            raise E2EAssertion("%s: missing %r in read-back\n%s" % (context, part, text[:800]))
        if current <= previous:
            raise E2EAssertion("%s: %r is out of order\n%s" % (context, part, text[:800]))
        previous = current


_TARGETED_SEED = (
    "Procedure First() Export\n"
    "EndProcedure\n\n"
    "// anchor documentation\n"
    "&AtServer\n"
    "Procedure Target() Export\n"
    "\tEndProcedureResult = 1;\n"
    "\tValue = 1;\n"
    "EndProcedure\n\n"
    "Function Last() Export\n"
    "\tReturn 3;\n"
    "EndFunction\n"
)


def _seed_targeted(source=_TARGETED_SEED):
    seeded = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": source, "overwrite": True,
    })
    assert_ok(seeded, "seed method-targeted module")
    return _read_content_hash()


@e2e_test(tool="write_module_source", kind="write-metadata")
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


@e2e_test(tool="write_module_source", kind="write-metadata")
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


@e2e_test(tool="write_module_source", kind="write-metadata")
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
    #
    # The guard only applies to an EXISTING module (creating a new one is unconditional), so
    # "the module is there" is this test's premise, not an assumption. Prove it first: when
    # EDT's view of the file is stale — the fixture is restored by git, behind the workbench's
    # back — the tool legitimately treats the write as a CREATE and succeeds, and the bare
    # assertion below would report that as "the guard is broken", which is the wrong bug.
    existing = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(existing, "the blind-replace guard needs the module to exist first (%s)" % MODULE)

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

@e2e_test(tool="write_module_source", kind="write-metadata")
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


@e2e_test(tool="write_module_source", kind="write-metadata")
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


@e2e_test(tool="write_module_source", kind="write-metadata")
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

@e2e_test(tool="write_module_source", kind="write-metadata")
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


@e2e_test(tool="write_module_source", kind="write-metadata")
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


@e2e_test(tool="write_module_source", kind="write-metadata")
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
# Method-targeted modes (#542). Every mutation is proven by read-back; every
# refusal snapshots the complete read response and proves it remains identical.

@e2e_test(tool="write_module_source", kind="write")
def test_method_targeted_modes_require_methodname_and_expectedhash():
    for mode in ("replaceMethod", "insertBefore", "insertAfter"):
        missing_name = call("write_module_source", {
            "projectName": PROJECT, "modulePath": MODULE, "mode": mode,
            "source": "Procedure Added()\nEndProcedure\n",
            "expectedHash": "0123456789abcdef",
        })
        name_error = assert_error(missing_name, "%s without methodName" % mode)
        assert_error_quality(name_error, names=["methodName"],
                             ctx="%s requires methodName" % mode)

        missing_hash = call("write_module_source", {
            "projectName": PROJECT, "modulePath": MODULE, "mode": mode,
            "methodName": "Target", "source": "Procedure Added()\nEndProcedure\n",
        })
        hash_error = assert_error(missing_hash, "%s without expectedHash" % mode)
        assert_error_quality(hash_error, names=["expectedHash"],
                             suggests=["read_module_source", "read_method_source"],
                             ctx="%s requires a hash from a read tool" % mode)
    assert_no_diff("missing targeted preconditions must not write")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_method_replaces_only_target_with_owned_preamble():
    token = _seed_targeted()
    replacement = (
        "// replacement documentation\n"
        "&AtServer\n"
        "Procedure Target() Export\n"
        "\tValue = 42;\n"
        "EndProcedure\n"
    )
    result = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": token, "source": replacement,
    })
    assert_ok(result, "replaceMethod happy path")

    module = _read_module_text()
    assert_contains(module, "Procedure First() Export", "replaceMethod preserves the first neighbor")
    assert_contains(module, "Function Last() Export", "replaceMethod preserves the last neighbor")
    assert_contains(module, "// replacement documentation\n&AtServer\nProcedure Target() Export",
                    "replacement preamble stays owned by Target")
    assert_not_contains(module, "anchor documentation", "the old owned doc comment was replaced")
    assert_not_contains(module, "Value = 1;", "the old target body was replaced")

    settle_or_fail("read_method_source after replaceMethod")
    method = call("read_method_source", {
        "projectName": PROJECT, "modulePath": MODULE, "methodName": "Target",
    })
    assert_ok(method, "read back replaced Target method")
    assert_contains(method.text, "Value = 42;", "read_method_source sees the real replacement")

    revalidated = call("revalidate_objects", {
        "projectName": PROJECT, "objects": ["CommonModule.OK"],
    })
    assert_ok(revalidated, "revalidate CommonModule.OK after replaceMethod")
    settle_or_fail("checking project errors after replaceMethod")
    problems = call("get_project_errors", {
        "projectName": PROJECT, "severity": "ERRORS",
        "objectFqns": ["CommonModule.OK"],
    })
    assert_ok(problems, "read project errors for CommonModule.OK")
    if not isinstance(problems.structured, dict):
        raise E2EAssertion("exact get_project_errors call returned no structuredContent")
    if problems.structured.get("objectsResolved") != ["CommonModule.OK"]:
        raise E2EAssertion("CommonModule.OK did not resolve in get_project_errors: %r"
                           % (problems.structured,))
    problem_report = problems.structured.get("report", "")
    assert_contains(problem_report, "# No Errors Found",
                    "valid replaceMethod leaves CommonModule.OK error-free")
    assert_not_contains(problem_report, "# Configuration Problems",
                        "a valid replaceMethod must introduce no EDT errors")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_insert_before_lands_before_anchor_documentation_and_annotation():
    token = _seed_targeted()
    result = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "insertBefore",
        "methodName": "Target", "expectedHash": token,
        "source": "Procedure AddedBefore() Export\nEndProcedure\n",
    })
    assert_ok(result, "insertBefore happy path")
    module = _read_module_text()
    _assert_order(module, (
        "Procedure AddedBefore() Export", "EndProcedure",
        "// anchor documentation", "&AtServer", "Procedure Target() Export",
    ), "insertBefore must not split the Target preamble")
    assert_contains(module, "&AtServer\nProcedure Target() Export",
                    "the annotation remains adjacent to Target")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_insert_after_uses_real_terminator_not_endprocedureresult():
    token = _seed_targeted()
    result = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "insertAfter",
        "methodName": "Target", "expectedHash": token,
        "source": "Function AddedAfter() Export\n\tReturn 9;\nEndFunction\n",
    })
    assert_ok(result, "insertAfter happy path")
    module = _read_module_text()
    _assert_order(module, (
        "EndProcedureResult = 1;", "Value = 1;", "EndProcedure",
        "Function AddedAfter() Export", "EndFunction", "Function Last() Export",
    ), "insertAfter must land after the complete Target method")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_method_supports_russian_keywords():
    russian = "Функция Цель() Экспорт\n\tВозврат 1;\nКонецФункции\n"
    token = _seed_targeted(russian)
    result = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Цель", "expectedHash": token,
        "source": "Функция Цель() Экспорт\n\tВозврат 2;\nКонецФункции\n",
    })
    assert_ok(result, "replaceMethod with Russian Function keywords")
    module = _read_module_text()
    assert_contains(module, "Возврат 2;", "Russian replacement reached the module")
    assert_not_contains(module, "Возврат 1;", "Russian old body is gone")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_method_stale_hash_rejected_and_exact_readback_unchanged():
    stale_token = _seed_targeted()
    concurrent = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "searchReplace",
        "oldSource": "Value = 1;", "source": "Value = 2;",
        "expectedHash": stale_token,
    })
    assert_ok(concurrent, "simulate a concurrent module edit")
    before = _read_module_text()
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": stale_token,
        "source": "Procedure Target() Export\n\tCLOBBERED = True;\nEndProcedure\n",
    })
    error = assert_error(rejected, "replaceMethod with a stale expectedHash")
    assert_error_quality(error, names=["expectedHash"], suggests=["read_module_source"],
                         ctx="stale targeted hash steers to a fresh read")
    _assert_readback_unchanged(before, "stale expectedHash refusal")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_ambiguous_method_target_rejected_and_readback_unchanged():
    ambiguous = (
        "#If Server Then\nProcedure Target()\nEndProcedure\n"
        "#Else\nProcedure Target()\nEndProcedure\n#EndIf\n"
    )
    token = _seed_targeted(ambiguous)
    before = _read_module_text()
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": token,
        "source": "Procedure Target()\nEndProcedure\n",
    })
    error = assert_error(rejected, "ambiguous method target")
    assert_error_quality(error, names=["Target"], suggests=["ambiguous", "preprocessor"],
                         ctx="duplicate preprocessor declarations are never selected silently")
    _assert_readback_unchanged(before, "ambiguous target refusal")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_method_source_with_zero_methods_rejected_and_unchanged():
    token = _seed_targeted()
    before = _read_module_text()
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": token, "source": "Value = 10;\n",
    })
    error = assert_error(rejected, "targeted source with zero methods")
    assert_error_quality(error, names=["source"], suggests=["exactly one"],
                         ctx="zero-method source is refused")
    _assert_readback_unchanged(before, "zero-method source refusal")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_method_source_with_two_methods_rejected_and_unchanged():
    token = _seed_targeted()
    before = _read_module_text()
    two = "Procedure Target()\nEndProcedure\nProcedure Extra()\nEndProcedure\n"
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": token, "source": two,
    })
    error = assert_error(rejected, "targeted source with two methods")
    assert_error_quality(error, names=["source"], suggests=["exactly one"],
                         ctx="two-method source is refused")
    _assert_readback_unchanged(before, "two-method source refusal")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_method_name_mismatch_rejected_and_unchanged():
    token = _seed_targeted()
    before = _read_module_text()
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": token,
        "source": "Procedure Renamed()\nEndProcedure\n",
    })
    error = assert_error(rejected, "replaceMethod name mismatch")
    assert_error_quality(error, names=["Target", "Renamed"], suggests=["Rename is not supported"],
                         ctx="replaceMethod cannot rename")
    _assert_readback_unchanged(before, "method name mismatch refusal")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_insert_existing_method_name_rejected_and_unchanged():
    token = _seed_targeted()
    before = _read_module_text()
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "insertBefore",
        "methodName": "Target", "expectedHash": token,
        "source": "Function Last() Export\n\tReturn 4;\nEndFunction\n",
    })
    error = assert_error(rejected, "insert of existing method name")
    assert_error_quality(error, names=["Last"], suggests=["already exists", "unique"],
                         ctx="insert refuses a colliding method name")
    _assert_readback_unchanged(before, "existing-name insert refusal")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_repeated_insert_rejected_without_duplicate():
    token = _seed_targeted()
    source = "Procedure InsertOnce() Export\nEndProcedure\n"
    first = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "insertAfter",
        "methodName": "Target", "expectedHash": token, "source": source,
    })
    assert_ok(first, "first insertAfter")
    fresh_token = _read_content_hash()
    before = _read_module_text()
    repeated = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "insertAfter",
        "methodName": "Target", "expectedHash": fresh_token, "source": source,
    })
    error = assert_error(repeated, "repeated insertAfter")
    assert_error_quality(error, names=["InsertOnce"], suggests=["already exists"],
                         ctx="repeating an insert is idempotently refused")
    _assert_readback_unchanged(before, "repeated insert refusal")
    if before.count("Procedure InsertOnce() Export") != 1:
        raise E2EAssertion("repeated insert produced a duplicate:\n%s" % before[:800])


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_endprocedure_prefix_without_real_terminator_is_rejected():
    token = _seed_targeted()
    before = _read_module_text()
    rejected = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod",
        "methodName": "Target", "expectedHash": token,
        "source": "Procedure Target()\nEndProcedureResult = 1;\n",
    })
    error = assert_error(rejected, "EndProcedureResult is not a terminator")
    assert_error_quality(error, names=["source"], suggests=["complete", "terminator"],
                         ctx="terminator-prefix identifier cannot complete a method")
    _assert_readback_unchanged(before, "false terminator refusal")


# Built-in BSL syntax check (#397 / #109) — the gate must let the legal single-line
# block through while still blocking a genuine imbalance. Both directions matter:
# a false positive makes a whole module unwritable, a false negative writes broken
# BSL. Asserted over the wire because the gate's verdict is what the caller sees.
# ──────────────────────────────────────────────────────────────────────────────

_SINGLE_LINE_IF = (
    "Процедура Расш_ПередЗаписью(Отказ) Экспорт\n"
    "\tЕсли Отказ Тогда Возврат; КонецЕсли;\n"
    "\tЗначение = 1;\n"
    "КонецПроцедуры\n"
)


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_single_line_if_is_not_blocked_by_the_syntax_check():
    # The one-line Если ... Тогда ... КонецЕсли; is a whole block. It used to be
    # counted as unclosed, which then mismatched КонецПроцедуры and blocked the write.
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": _SINGLE_LINE_IF, "overwrite": True,
    })
    assert_ok(r, "a single-line Если must pass the built-in syntax check")
    assert_diff_contains("Если Отказ Тогда Возврат; КонецЕсли;",
                         "the module carrying a single-line block must reach disk")


@e2e_test(tool="write_module_source", kind="write")
def test_genuinely_unbalanced_module_is_still_blocked():
    # The gate must not have become a no-op: the second Если below is never closed,
    # even though the first one is a valid single-line block.
    broken = (
        "Процедура Тест(Отказ) Экспорт\n"
        "\tЕсли Отказ Тогда Возврат; КонецЕсли;\n"
        "\tЕсли Истина Тогда\n"
        "\t\tЗначение = 1;\n"
        "КонецПроцедуры\n"
    )
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "replace", "source": broken, "overwrite": True,
    })
    err = assert_error(r, "an unclosed Если must still block the write")
    # Actionable means it points at the offending block AND at the way out, not merely
    # that it is non-empty: name the unclosed construct and the override flag.
    assert_error_quality(err, names=["Если/If"], suggests=["skipSyntaxCheck"],
                         ctx="the block-balance error names the block and the override")
    assert_no_diff("a blocked write must not touch the project on disk")

# ──────────────────────────────────────────────────────────────────────────────
# InvalidCharacterInFile (#161): the seven characters the 1C standard forbids in a
# source file are replaced in the source the caller HANDS the tool.
#
# Every one of them is written here as a Python \uXXXX escape, never as a literal:
# an em dash and a hyphen are the same glyph to a reviewer, so a literal test could
# assert the wrong character and still read as correct.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="write_module_source", kind="write-metadata")
def test_forbidden_characters_are_normalized_and_reported():
    # A dash, a no-break space and a soft hyphen, all inside one comment line.
    dirty = "// dash \u2014 nbsp\u00A0here soft\u00ADhyphen\n"
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "append", "source": dirty,
    })
    assert_ok(r, "a source carrying forbidden characters is still written")

    # The response must SAY what it replaced - a silent fix would leave the caller
    # believing it wrote what it sent.
    assert_contains(r.text, "normalizedCharacters",
                    "the write must report the characters it replaced")
    assert_contains(r.text, "EM DASH", "the report must name the em dash")
    assert_contains(r.text, "NO-BREAK SPACE", "the report must name the no-break space")
    assert_contains(r.text, "SOFT HYPHEN", "the report must name the soft hyphen")

    # On-disk truth: the ASCII twins are there ...
    assert_diff_contains("// dash - nbsp here softhyphen",
                         "the dash became '-', the nbsp a space, the soft hyphen was dropped")
    # ... and not one of the forbidden characters survived into the file.
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(src, "read-back after a normalized write")
    for name, ch in (("EM DASH", "\u2014"), ("NO-BREAK SPACE", "\u00A0"),
                     ("SOFT HYPHEN", "\u00AD"), ("EN DASH", "\u2013"),
                     ("MINUS SIGN", "\u2212")):
        assert_not_contains(src.text, ch, "%s must not survive the write" % name)


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_normalization_can_be_turned_off():
    # The opt-out writes the bytes through untouched - the other direction of the
    # same switch, without which this feature could not be declined.
    dirty = "// kept \u2014 verbatim\n"
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "append", "source": dirty,
        "normalizeInvalidCharacters": False,
    })
    assert_ok(r, "normalizeInvalidCharacters=False is accepted")
    # Nothing was replaced, so nothing is reported.
    assert_not_contains(r.text, "normalizedCharacters",
                        "an untouched write must report no normalization")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(src, "read-back after an opted-out write")
    assert_contains(src.text, "\u2014",
                    "the em dash must survive when normalization is off")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_oldsource_is_matched_against_the_file_not_normalized():
    # searchReplace matches oldSource against what is ALREADY in the file, so
    # normalizing it would stop it matching. Seed a line through the opted-out path
    # (so the file really holds an em dash), then address it by that same text.
    seeded = "// anchor \u2014 tail\n"
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "append", "source": seeded,
        "normalizeInvalidCharacters": False,
    })
    assert_ok(seed, "seed a line that really carries an em dash")

    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "searchReplace",
        "oldSource": "// anchor \u2014 tail",
        "source": "// replaced",
    })
    assert_ok(r, "oldSource carrying an em dash must still find its line")
    src = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(src, "read-back after the swap")
    assert_contains(src.text, "// replaced", "the swap happened")
    assert_not_contains(src.text, "// anchor", "the old line is gone")


# ──────────────────────────────────────────────────────────────────────────────
# ADOPTED OBJECTS (#711): writing the module of an object ADOPTED into an extension records that
# module's state Extended in the owner's .mdo at once - the line EDT's UI records when code is added
# to an adopted module. EDT's own listener sets the state only in its model, a few seconds after the
# write, and never saves the .mdo. Nothing here asserts an export or infobase consequence of the
# line: on the stand EDT's XML dump carried an adopted module that was not Extended as well.
#
# Every test here writes into the extension fixture (TESTS_PROJECT, at TESTS_PROJECT_REL) and puts it
# back with harness.restore_extension_fixture in a finally block - except on E2ECallTimeout, where the
# timed-out call may still be writing these very files (the orchestrator aborts the run on that).
#
# WHERE A JAR WITHOUT #711 FAILS - read this before reading a red run. Each test that expects the line
# polls the owner's .mdo for it FIRST, for up to _STATE_POLL_S right after the write, and only then
# asserts the response key. On the branch the tool reads that .mdo back for the line before it
# answers, so the poll passes at once; a .mdo that never gets the line fails the WRITE instead
# (errorAfterMutation at assert_ok: the .mdo "did not carry" the line), whether the tool switched the
# state or found it Extended. On a jar without the step the POLL fails, on the .mdo line - the
# effect. A run on such a jar that passes the poll and fails only at the key is the stand pin's H0:
# EDT saved the line by itself, which falsifies the premise the step exports for - STOP and report
# it. The two tests whose state is left as it is (Checked; a root without its block) fail on such a
# jar at the key: the write answers a plain success that names nothing.
#
# The KEY's value is not pinned to `switchedToExtended`: EDT's own listener also flips the MODEL
# state after it synchronizes the written file (it just never saves the .mdo), and when it wins that
# race the tool reports `alreadyExtended` and exports the .mdo all the same. The .mdo line is the
# effect, and it is asserted on disk; which of the two values said so depends on a race - and only
# the value does: whether the write succeeds is decided by the .mdo alone, the same either way.
# ──────────────────────────────────────────────────────────────────────────────

_ADOPTED = "<objectBelonging>Adopted</objectBelonging>"
_STATE_KEY = "moduleExtensionState"
_STATE_VALUES = ("moduleExtensionState: switchedToExtended", "moduleExtensionState: alreadyExtended")
# The base common module the reporter's flow adopts: not adopted in the committed fixture.
_DRY_SIGNAL_MODULE = "CommonModules/DrySignal/Module.bsl"
_DRY_SIGNAL_MDO = "src/CommonModules/DrySignal/DrySignal.mdo"
# An interceptor of the base DrySignal.Test (the reporter's shape).
_INTERCEPTOR = '&Before("Test")\nProcedure tests_TestBefore()\nEndProcedure\n'
# Stable substring of export_configuration_to_xml's not-installed sentinel (CLI API absent).
_DUMP_API_ABSENT = "IExportConfigurationFilesApi is not available"
# How long the owner's .mdo is polled for its state line after a write: the stand pin's window. On the
# branch the tool answers only after that export, so the poll passes at once.
_STATE_POLL_S = 60
# The extension root's own extension block, as the fixture commits it (the missing-block test removes it).
_ROOT_BLOCK_OPEN = '<extension xsi:type="mdclassExtension:ConfigurationExtension">'
# The adopted DrySignal's first extension-block line, as adopt_metadata_object writes it; a planted
# module state goes right after it (the kept-state test).
_DRY_SIGNAL_BLOCK_ANCHOR = "<extendedConfigurationObject>Checked</extendedConfigurationObject>"
# The claim round 2 of #711 removed, in each phrasing it had: no response may say it again.
_REMOVED_CLAIM = ("infobase", "left out", "leaves the module", "exports and loads", "whatever its state")
# The claims the round-2 gate of #711 found broader than EDT's bytecode: EDT's module adopter, which its UI runs to
# add a method to an extension, sets Extended without asking (its listener asks for None first), and 8.3.8's
# property-type delegate lists no module.
_OVERBROAD_CLAIM = ("like EDT,", "EDT never switches", "which EDT never", "EDT only switches", "every other module")


def _assert_no_removed_claim(text, ctx):
    """The response claims no export or infobase consequence of the module state, and nothing broader
    than EDT's bytecode."""
    for fragment in _REMOVED_CLAIM:
        assert_not_contains(text or "", fragment, "%s: no export or infobase claim" % ctx)
    for fragment in _OVERBROAD_CLAIM:
        assert_not_contains(text or "", fragment, "%s: no claim broader than EDT's bytecode" % ctx)


def _assert_module_state_key(r, ctx):
    """The response carries the module-state key with one of its two values; returns the value line."""
    text = r.text or ""
    for value in _STATE_VALUES:
        if value in text:
            return value
    raise E2EAssertion("expected the frontmatter to carry %s (switchedToExtended or alreadyExtended) [%s]; "
                       "got:\n%s" % (_STATE_KEY, ctx, text[:600]))


def _adopt(fqn, ctx):
    """Adopt a base object into the single fixture extension; the adoption must really run."""
    r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, ctx)
    s = r.structured
    if not isinstance(s, dict) or s.get("action") != "adopted" or s.get("extensionProject") != TESTS_PROJECT:
        raise E2EAssertion("expected %s to be adopted into %s [%s]; got %r" % (fqn, TESTS_PROJECT, ctx, s))


def _is_transient_teardown_race(err):
    """The two shapes in which EDT's shared WorkspaceOrchestrator refuses an export while a sibling
    project's teardown drains - the same signatures test_export_configuration_to_xml retries on."""
    e = (err or "").lower()
    return "assertion failed" in e or ("closing" in e and "phase" in e)


def _dump_extension(ctx):
    """Dump the extension with export_configuration_to_xml into a fresh temp dir.

    Returns the directory, or None when the CLI export API is not installed in this EDT (the tool's
    documented sentinel - asserted here, so any OTHER error fails). The caller removes the directory."""
    out_dir = tempfile.mkdtemp(prefix="edt_e2e_711_")
    args = {"projectName": TESTS_PROJECT, "outputPath": out_dir}
    r = call("export_configuration_to_xml", args)
    for _ in range(8):
        if not r.is_error or not _is_transient_teardown_race(r.error_text()):
            break
        time.sleep(3)
        r = call("export_configuration_to_xml", args)
    if r.is_error:
        shutil.rmtree(out_dir, ignore_errors=True)
        assert_contains(r.error_text(), _DUMP_API_ABSENT,
                        "%s: the only acceptable export error is the not-installed API sentinel" % ctx)
        return None
    return out_dir


def _assert_dump_carries(out_dir, module_dir, needle, ctx):
    """The XML dump holds <module_dir>/Ext/Module.bsl containing `needle`: the module text is in the
    extension EDT exports. A no-regression check - it says nothing about the module's state."""
    path = os.path.join(out_dir, *module_dir.split("/"), "Ext", "Module.bsl")
    if not os.path.exists(path):
        raise E2EAssertion("expected the extension's XML dump to hold %s/Ext/Module.bsl [%s]"
                           % (module_dir, ctx))
    with open(path, encoding="utf-8-sig", errors="replace") as f:
        text = f.read()
    if needle not in text:
        raise E2EAssertion("expected %s in the dumped %s/Ext/Module.bsl [%s]; it holds:\n%s"
                           % (needle, module_dir, ctx, text[:400]))


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_adopted_common_module_gets_extended_module_state():
    """#711 in the reporter's flow: adopt the base CommonModule.DrySignal, write an interceptor into
    the adopted module, and the module must come out Extended - in its .mdo, then in the response.
    Then the rerun recipe the errors teach (an empty append) reports alreadyExtended and leaves the
    text as it is.

    Red on a jar without #711 at the .mdo poll: no <module>Extended</module> in DrySignal.mdo (EDT's
    listener may switch the model, but its commit never saves the .mdo).

    The XML-dump check at the end is a NO-REGRESSION check, not evidence for the step: on the stand
    EDT's dump carried an adopted module that was not Extended as well, so a jar without #711 passes
    it too. After clean_project re-imports the extension from disk, the recorded line is still in the
    .mdo - where the state is kept."""
    try:
        _adopt("CommonModule.DrySignal", "adopt the base common module DrySignal")
        # Poll for the adoption first, then read what it left: the adoption alone sets no module state.
        adopted = poll_disk_contains_all(_DRY_SIGNAL_MDO, [_ADOPTED],
                                         ctx="the adopted DrySignal has its own .mdo in the extension",
                                         fixture_rel=TESTS_PROJECT_REL)
        if "<module>Extended</module>" in adopted:
            raise E2EAssertion("precondition: adopting the object must not mark its module Extended - "
                               "this test proves the WRITE does:\n%s" % adopted[:600])

        r = call("write_module_source", {
            "projectName": TESTS_PROJECT, "modulePath": _DRY_SIGNAL_MODULE,
            "mode": "replace", "source": _INTERCEPTOR,
        })
        assert_ok(r, "write an interceptor into the adopted module")
        poll_disk_contains_all(_DRY_SIGNAL_MDO, [_ADOPTED, "<module>Extended</module>"],
                               timeout=_STATE_POLL_S, ctx="the adopted module is Extended in its .mdo",
                               fixture_rel=TESTS_PROJECT_REL)
        _assert_module_state_key(r, "a write into an adopted object's module reports its module state")
        _assert_no_removed_claim(r.text, "the response to the write into the adopted module")
        written = read_fixture_file(TESTS_PROJECT_REL, "src/" + _DRY_SIGNAL_MODULE)
        assert_contains(written, "tests_TestBefore", "the interceptor text itself is on disk")

        # The rerun recipe every failure of this step names: an empty append rewrites the same text.
        again = call("write_module_source", {
            "projectName": TESTS_PROJECT, "modulePath": _DRY_SIGNAL_MODULE,
            "mode": "append", "source": "",
        })
        assert_ok(again, "the rerun recipe: an empty append into the adopted module")
        assert_contains(again.text, "moduleExtensionState: alreadyExtended",
                        "the second write finds the module already Extended")
        if read_fixture_file(TESTS_PROJECT_REL, "src/" + _DRY_SIGNAL_MODULE) != written:
            raise E2EAssertion("an empty append must leave the module text as it was")
        poll_disk_contains_all(_DRY_SIGNAL_MDO, ["<module>Extended</module>"],
                               ctx="the line survives the second write", fixture_rel=TESTS_PROJECT_REL)

        # No-regression: the extension's XML dump carries the adopted module next to the fixture's own
        # Extended one. A jar without #711 passes this too - it is no evidence for the step.
        out_dir = _dump_extension("dump the extension after the write")
        if out_dir is not None:
            try:
                _assert_dump_carries(out_dir, "CommonModules/Calc", "tests_Test",
                                     "control: the fixture's own Extended module is dumped")
                _assert_dump_carries(out_dir, "CommonModules/DrySignal", "tests_TestBefore",
                                     "the adopted module is dumped")
            finally:
                shutil.rmtree(out_dir, ignore_errors=True)

        # A re-import from disk rebuilds the model from the files; the recorded state stays in the .mdo.
        assert_ok(call("clean_project", {"projectName": TESTS_PROJECT}),
                  "re-import the extension from disk")
        settle_or_fail("the extension after its re-import")
        poll_disk_contains_all(_DRY_SIGNAL_MDO, [_ADOPTED, "<module>Extended</module>"],
                               ctx="the recorded module state stays in the .mdo after a re-import from disk",
                               fixture_rel=TESTS_PROJECT_REL)
    except E2ECallTimeout:
        # NO cleanup: the timed-out call may still be writing these very files.
        raise
    except BaseException:
        restore_extension_fixture("a write into an adopted common module")
        raise
    else:
        restore_extension_fixture("a write into an adopted common module")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_adopted_module_addressed_in_russian_gets_extended_module_state():
    """Bilingual: the object is adopted and the module addressed by the Russian type token, with a
    Cyrillic programmatic Name. The owner is read off the written file's path, so the spelling of
    the address must not matter. Red on a jar without #711 at the .mdo poll: no line in the .mdo."""
    try:
        _adopt("ОбщийМодуль.Вычисление", "adopt the base common module Вычисление by the Russian type token")
        mdo = "src/CommonModules/Вычисление/Вычисление.mdo"
        adopted = poll_disk_contains_all(mdo, [_ADOPTED], ctx="the adopted Вычисление has its own .mdo",
                                         fixture_rel=TESTS_PROJECT_REL)
        if "<module>Extended</module>" in adopted:
            raise E2EAssertion("precondition: adopting the object must not mark its module Extended - "
                               "this test proves the WRITE does:\n%s" % adopted[:600])
        r = call("write_module_source", {
            "projectName": TESTS_PROJECT, "objectName": "ОбщийМодуль.Вычисление", "moduleType": "Module",
            "mode": "replace", "source": "Процедура tests_Расчет() Экспорт\nКонецПроцедуры\n",
        })
        assert_ok(r, "write into the adopted module addressed in Russian")
        poll_disk_contains_all(mdo, [_ADOPTED, "<module>Extended</module>"], timeout=_STATE_POLL_S,
                               ctx="the adopted Вычисление module is Extended in its .mdo",
                               fixture_rel=TESTS_PROJECT_REL)
        _assert_module_state_key(r, "the Russian address reports the module state too")
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture("a write into a module adopted by its Russian address")
        raise
    else:
        restore_extension_fixture("a write into a module adopted by its Russian address")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_adopted_catalog_object_module_gets_extended_state():
    """A second module kind, on the object the fixture already adopts: Catalog.Catalog has an
    extension block without an objectModule state. Writing its ObjectModule marks THAT state, in the
    catalog's .mdo, next to the adopted form's own <form>Extended</form>. Red on a jar without #711
    at the .mdo poll."""
    mdo = "src/Catalogs/Catalog/Catalog.mdo"
    try:
        before = read_fixture_file(TESTS_PROJECT_REL, mdo)
        if "<objectModule>" in before:
            raise E2EAssertion("precondition: the fixture's adopted catalog must not carry an objectModule "
                               "state yet - this test proves the WRITE adds it:\n%s" % before[:600])
        r = call("write_module_source", {
            "projectName": TESTS_PROJECT, "objectName": "Catalog.Catalog", "moduleType": "ObjectModule",
            "mode": "replace", "source": "Procedure tests_ObjectModuleProbe() Export\nEndProcedure\n",
        })
        assert_ok(r, "write the adopted catalog's object module")
        poll_disk_contains_all(mdo, [_ADOPTED, "<objectModule>Extended</objectModule>", "<form>Extended</form>"],
                               timeout=_STATE_POLL_S,
                               ctx="the catalog's objectModule state is Extended and its form's stays",
                               fixture_rel=TESTS_PROJECT_REL)
        _assert_module_state_key(r, "an adopted catalog's object module reports its state")
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture("a write into an adopted catalog's object module")
        raise
    else:
        restore_extension_fixture("a write into an adopted catalog's object module")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_extension_root_session_module_gets_extended_state():
    """The extension ROOT's own modules go through the same step: Configuration is adopted, and the
    fixture's ConfigurationExtension block carries no sessionModule state. Writing
    Configuration/SessionModule.bsl adds <sessionModule>Extended</sessionModule> to the extension's
    Configuration.mdo - the only path through the configuration owner. Red on a jar without #711 at
    the .mdo poll."""
    mdo = "src/Configuration/Configuration.mdo"
    try:
        before = read_fixture_file(TESTS_PROJECT_REL, mdo)
        if "<sessionModule>" in before:
            raise E2EAssertion("precondition: the fixture root must not carry a sessionModule state yet")
        r = call("write_module_source", {
            "projectName": TESTS_PROJECT, "modulePath": "Configuration/SessionModule.bsl",
            "mode": "replace", "source": "Procedure tests_SessionProbe()\nEndProcedure\n",
        })
        assert_ok(r, "write the extension's session module")
        poll_disk_contains_all(mdo, ["<sessionModule>Extended</sessionModule>", "<defaultRoles>Extended</defaultRoles>"],
                               timeout=_STATE_POLL_S,
                               ctx="the root's sessionModule is Extended; its other states are kept",
                               fixture_rel=TESTS_PROJECT_REL)
        _assert_module_state_key(r, "the extension root's module reports its state")
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture("a write into the extension's session module")
        raise
    else:
        restore_extension_fixture("a write into the extension's session module")


def _remove_root_extension_block(mdo_rel):
    """Remove the extension ROOT's own extension block from its .mdo on disk - the shape of a root an
    earlier create_project made from a new configuration (#706) - keeping every other byte, line ends
    included. Returns the removed block, so the caller can check what was in it."""
    path = os.path.join(REPO_ROOT, *TESTS_PROJECT_REL.split("/"), *mdo_rel.split("/"))
    with open(path, encoding="utf-8", newline="") as f:
        text = f.read()
    start = text.find(_ROOT_BLOCK_OPEN)
    if start < 0:
        raise E2EAssertion("setup failed: %s carries no %s" % (mdo_rel, _ROOT_BLOCK_OPEN))
    end = text.index("</extension>", start) + len("</extension>")
    first = text.rfind("\n", 0, start) + 1
    after = text.find("\n", end) + 1
    if text[first:start].strip() or text[end:after].strip():
        raise E2EAssertion("setup failed: the root's extension block does not stand on lines of its own "
                           "in %s" % mdo_rel)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text[:first] + text[after:])
    return text[start:end]


def _read_extension_bytes(rel):
    """The raw bytes of a file in the extension fixture - for a check that the file was left byte for byte."""
    with open(os.path.join(REPO_ROOT, *TESTS_PROJECT_REL.split("/"), *rel.split("/")), "rb") as f:
        return f.read()


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_adopted_root_without_its_extension_block_answers_a_success_with_a_note():
    """An extension root adopted with NO extension block in its .mdo - the shape of a root an earlier
    create_project made from a new configuration (#706) - has no place for a module state. A write of its
    session module is still a SUCCESS - the text is written - and says what it found: `moduleExtensionState:
    noExtensionBlock` and a note that names the root and the missing block, says that EDT's wizard and
    create_project make roots with the block while an earlier create_project did not, and gives the way out -
    recreating the extension, and no property state to set in EDT (#747 round 3: red on a jar without it at
    those assertions). The .mdo is left byte for byte: no state is
    recorded and no block is created.

    The block is removed on disk and brought into the model with clean_project, the deterministic
    re-import. That also proves live what the unit tests only mock: EDT's adopted-property service
    answers a missing block without throwing. Red on a jar without #711: a plain success without the
    key."""
    mdo = "src/Configuration/Configuration.mdo"
    module = "Configuration/SessionModule.bsl"
    ctx = "a write into the session module of an extension root without its extension block"
    try:
        block = _remove_root_extension_block(mdo)
        if "<sessionModule>" in block:
            raise E2EAssertion("precondition: the fixture root must not carry a sessionModule state yet")
        settle_or_fail("the extension before its root is re-imported without the block")
        assert_ok(call("clean_project", {"projectName": TESTS_PROJECT}),
                  "re-import the extension root without its extension block")
        settle_or_fail("the extension after its root was re-imported without the block")
        before = _read_extension_bytes(mdo)

        r = call("write_module_source", {
            "projectName": TESTS_PROJECT, "modulePath": module,
            "mode": "replace", "source": "Procedure tests_SessionProbe()\nEndProcedure\n",
        })
        if r.is_error:
            raise E2EAssertion("a root without its extension block must answer a success - the text is "
                               "written - not an error:\n%s" % (r.error_text() or "")[:600])
        text = r.text or ""
        if "moduleExtensionState: noExtensionBlock" not in text:
            why = ("another key: EDT's model of the re-imported root carries an extension block after all - "
                   "the test's premise failed, not the step" if _STATE_KEY in text
                   else "a plain success: nothing checked the module's state")
            raise E2EAssertion("expected moduleExtensionState: noExtensionBlock for a root without its "
                               "extension block (%s); got:\n%s" % (why, text[:600]))
        assert_contains(text, "the extension's root object (Configuration)", "the note names the root")
        assert_contains(text, "there is no extension block in src/Configuration/Configuration.mdo",
                        "the note names the missing block and its .mdo")
        assert_contains(text, "the module state cannot be recorded", "the note says no state could be recorded")
        assert_contains(text, "new-extension wizard", "the note says which roots carry the block")
        assert_contains(text, "and so does one that create_project creates",
                        "the note says create_project's roots carry the block too (#747 round 3)")
        assert_contains(text, "a root made by an earlier version of create_project",
                        "the note names the roots that lack the block")
        assert_contains(text, "recreate the extension with create_project", "the note gives the way out")
        assert_not_contains(text, "default language",
                            "no property state set in EDT is advised: on such a root a Checked default language is a "
                            "critical error that stops EDT from updating an infobase")
        assert_not_contains(text, "one of the root's properties",
                            "not just any of the root's properties: 'Extended configuration object' gets a block "
                            "of another class")
        _assert_no_removed_claim(text, ctx)
        assert_contains(read_fixture_file(TESTS_PROJECT_REL, "src/" + module), "tests_SessionProbe",
                        "the module text itself is on disk")
        if _read_extension_bytes(mdo) != before:
            raise E2EAssertion("the root's .mdo must be left byte for byte - no state recorded, no block "
                               "created:\n%s" % read_fixture_file(TESTS_PROJECT_REL, mdo)[:600])
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture(ctx)
        raise
    else:
        restore_extension_fixture(ctx)


def _plant_module_state(mdo_rel, state):
    """Plant <module>STATE</module> in an adopted common module's extension block on disk, on its own
    line right after the block's first line, keeping every other byte, line ends included - a state
    the author set in EDT. Returns the planted text."""
    path = os.path.join(REPO_ROOT, *TESTS_PROJECT_REL.split("/"), *mdo_rel.split("/"))
    with open(path, encoding="utf-8", newline="") as f:
        text = f.read()
    if text.count(_DRY_SIGNAL_BLOCK_ANCHOR) != 1:
        raise E2EAssertion("setup failed: %s carries %d x %s" % (mdo_rel, text.count(_DRY_SIGNAL_BLOCK_ANCHOR),
                                                                  _DRY_SIGNAL_BLOCK_ANCHOR))
    if "<module>" in text:
        raise E2EAssertion("precondition: the adopted module must carry no module state yet:\n%s" % text[:600])
    nl = "\r\n" if "\r\n" in text else "\n"
    start = text.index(_DRY_SIGNAL_BLOCK_ANCHOR)
    indent = text[text.rfind("\n", 0, start) + 1:start]
    end = start + len(_DRY_SIGNAL_BLOCK_ANCHOR)
    planted = text[:end] + nl + indent + "<module>%s</module>" % state + text[end:]
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(planted)
    return planted


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_adopted_module_in_state_checked_is_kept_and_named():
    """A module state the author set - Checked - is left exactly as it is, and the write answers a
    SUCCESS that names it - `moduleExtensionState: keptChecked` and a note - never an error: the text
    is written. The .mdo is left byte for byte.

    The state is planted in the adopted DrySignal's .mdo and brought into the model with clean_project,
    the deterministic re-import (EDT's own listener acts only on None, so it leaves Checked alone). Red
    on a jar without #711: a plain success without the key."""
    ctx = "a write into an adopted module whose state is Checked"
    try:
        _adopt("CommonModule.DrySignal", "adopt the base common module DrySignal")
        poll_disk_contains_all(_DRY_SIGNAL_MDO, [_ADOPTED, _DRY_SIGNAL_BLOCK_ANCHOR],
                               ctx="the adopted DrySignal has its own .mdo with its extension block",
                               fixture_rel=TESTS_PROJECT_REL)
        settle_or_fail("the extension after the adoption")
        _plant_module_state(_DRY_SIGNAL_MDO, "Checked")
        settle_or_fail("the extension before its re-import with the planted state")
        assert_ok(call("clean_project", {"projectName": TESTS_PROJECT}),
                  "re-import the extension with the planted Checked state")
        settle_or_fail("the extension after its re-import with the planted state")
        before = _read_extension_bytes(_DRY_SIGNAL_MDO)
        if b"<module>Checked</module>" not in before:
            raise E2EAssertion("precondition: the re-imported .mdo must still carry the planted Checked state")

        r = call("write_module_source", {
            "projectName": TESTS_PROJECT, "modulePath": _DRY_SIGNAL_MODULE,
            "mode": "replace", "source": _INTERCEPTOR,
        })
        assert_ok(r, "a module state left as it is is no failure: the text is written")
        text = r.text or ""
        if "moduleExtensionState: keptChecked" not in text:
            raise E2EAssertion("expected moduleExtensionState: keptChecked for a Checked module (a plain success "
                               "means nothing checked the module's state); got:\n%s" % text[:600])
        assert_contains(text, "is Checked (<module>Checked</module> in " + _DRY_SIGNAL_MDO,
                        "the note names the state and its .mdo")
        assert_contains(text, "was left as it is", "the note says the state was left as it is")
        assert_contains(text, "like EDT's own listener, this tool only switches a module's state from None to Extended",
                        "the note compares the step with EDT's listener, which asks for None first as well")
        _assert_no_removed_claim(text, ctx)
        assert_contains(read_fixture_file(TESTS_PROJECT_REL, "src/" + _DRY_SIGNAL_MODULE), "tests_TestBefore",
                        "the interceptor text itself is on disk")
        if _read_extension_bytes(_DRY_SIGNAL_MDO) != before:
            raise E2EAssertion("a state left as it is must leave the .mdo byte for byte:\n%s"
                               % read_fixture_file(TESTS_PROJECT_REL, _DRY_SIGNAL_MDO)[:600])
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture(ctx)
        raise
    else:
        restore_extension_fixture(ctx)


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_own_and_base_modules_carry_no_module_state():
    """The guard: only an ADOPTED object's module is touched. A module of the extension's OWN object
    and a module of the base configuration answer exactly as before - no key - and the own object's
    .mdo is left byte for byte. Passes on master by design; dropping the ADOPTED check turns it red."""
    own_mdo = "src/CommonModules/tests_MathHelper/tests_MathHelper.mdo"
    try:
        before = read_fixture_file(TESTS_PROJECT_REL, own_mdo)
        own = call("write_module_source", {
            "projectName": TESTS_PROJECT, "modulePath": "CommonModules/tests_MathHelper/Module.bsl",
            "mode": "append", "source": "// e2e_711_own_module\n",
        })
        assert_ok(own, "append to the extension's own common module")
        assert_not_contains(own.text, _STATE_KEY, "the extension's own module has no adopted state to report")
        if read_fixture_file(TESTS_PROJECT_REL, own_mdo) != before:
            raise E2EAssertion("a write into the extension's own module must not touch its .mdo")

        base = call("write_module_source", {
            "projectName": PROJECT, "modulePath": MODULE,
            "mode": "append", "source": "// e2e_711_base_module\n",
        })
        assert_ok(base, "append to a base configuration module")
        assert_not_contains(base.text, _STATE_KEY, "a base configuration module has no module state to report")
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture("a write into the extension's own module")
        raise
    else:
        restore_extension_fixture("a write into the extension's own module")


def _assert_case_variants_write_existing_module(canonical_name, variants):
    """Check disk immediately; the write-metadata harness restores the fixture and model."""
    relative = "src/CommonModules/%s/Module.bsl" % canonical_name
    path = os.path.join(PROJECT_DIR, *relative.split("/"))
    modules = os.path.dirname(os.path.dirname(path))
    folder = os.path.dirname(path)
    object_names = set(os.listdir(modules))
    file_names = set(os.listdir(folder))
    markers = []
    for index, variant in enumerate(variants):
        marker = "// e2e_550_existing_file_%d" % index
        mode = "append" if index == 0 else "replace"
        previous = list(markers)
        markers = markers + [marker] if mode == "append" else [marker]
        address = ({"modulePath": "CommonModules/%s/module.bsl" % variant} if index == 0
                   else {"objectName": "CommonModule.%s" % variant})
        result = call("write_module_source", {
            "projectName": PROJECT, **address,
            "mode": mode, "source": marker + "\n", "overwrite": mode == "replace",
        })
        assert_ok(result, "write using a modulePath or objectName in another case")
        # No intervening MCP call or polling may hide an early return or a write to a sibling.
        contents = read_disk(relative)
        for expected in markers:
            assert_contains(contents, expected, "the canonical existing file must contain the written source")
        if mode == "replace":
            for replaced in previous:
                assert_not_contains(contents, replaced, "replace must overwrite the canonical existing file")
        assert set(os.listdir(modules)) == object_names, "no differently-cased object folder may be created"
        assert set(os.listdir(folder)) == file_names, "no differently-cased module file may be created"
        assert_diff_contains(marker, "the write must persist on disk before the tool returns")
        assert_diff_paths([PROJECT_REL + "/" + relative], "the canonical tracked file must be changed")
        assert_contains(result.text, "CommonModules/%s/Module.bsl" % canonical_name,
                        "the response must teach the caller the canonical module path")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_english_case_variants_write_the_real_existing_file():
    _assert_case_variants_write_existing_module("OK", ("ok", "Ok"))


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_russian_case_variants_write_the_real_existing_file():
    _assert_case_variants_write_existing_module("Вычисление", ("ВЫЧИСЛЕНИЕ", "вычисление"))


def _assert_first_module_uses_existing_object_folder(address):
    """A missing leaf must be created under Catalog, never a case-variant sibling."""
    relative = "src/Catalogs/Catalog/ObjectModule.bsl"
    catalogs = os.path.join(PROJECT_DIR, "src", "Catalogs")
    canonical = os.path.join(PROJECT_DIR, *relative.split("/"))
    before = set(os.listdir(catalogs))
    assert not os.path.exists(canonical), "the committed fixture must have no object module"
    marker = "// e2e_550_first_object_module"
    result = call("write_module_source", {
        "projectName": PROJECT, **address, "mode": "replace", "source": marker + "\n",
    })
    assert_ok(result, "create the first module with an object name in another case")
    # Read disk once before any other tool call; the harness owns the fixture reset.
    contents = read_disk(relative)
    assert_contains(contents, marker, "replace must create the module inside the real object folder")
    after = os.listdir(catalogs)
    assert set(after) == before, "first-module creation must not introduce another object folder"
    assert [name for name in after if name.casefold() == "catalog"] == ["Catalog"], after
    assert_contains(result.text, "Catalogs/Catalog/ObjectModule.bsl", "echo the canonical creation target")
    readback = call("read_module_source", {
        "projectName": PROJECT, "modulePath": "Catalogs/Catalog/ObjectModule.bsl",
    })
    assert_ok(readback, "read the module from its canonical location")
    assert_contains(readback.text, marker, "the created module must be readable through EDT")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_creates_first_module_under_wrong_case_existing_object():
    _assert_first_module_uses_existing_object_folder({"modulePath": "Catalogs/cATALOG/ObjectModule.bsl"})


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_objectname_replace_creates_first_module_under_real_object_folder():
    _assert_first_module_uses_existing_object_folder({
        "objectName": "Catalog.catalog", "moduleType": "ObjectModule",
    })

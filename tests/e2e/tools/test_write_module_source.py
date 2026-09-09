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
"""

import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains,
    assert_diff_contains, assert_no_diff, e2e_test, settle_or_fail,
    E2EAssertion, PROJECT,
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

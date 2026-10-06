"""
e2e tests for adopt_metadata_object (kind: write).

WHAT IT DOES (read AdoptMetadataObjectTool.java for the exact branches):
  Adopts (заимствовать) a BASE-configuration metadata object/member into a configuration
  EXTENSION via the platform IModelObjectAdopter.adoptAndAttach — EDT's "Add To Extension"
  for the metadata side. getResponseType()==JSON, so the payload is in r.structured.
  Params: projectName (the BASE config, required), fqn (required), extensionProjectName
  (optional; auto when the configuration has exactly one extension).

ENVIRONMENT / SCOPE:
  The fixture `TestConfiguration` has exactly one extension `TestConfiguration.tests`, which
  already ADOPTS `CommonModule.Calc` and `Catalog.Catalog` from the base. That gives us a
  stable, NON-MUTATING happy assertion: adopting an already-adopted object returns
  action='alreadyAdopted' (the tool's isAdopted branch) WITHOUT changing anything. The
  negative/contract cases (missing args, unknown object, unknown extension name) are benign,
  mutation-sensitive, and leave the tree clean.

  The NESTED-SUBSYSTEM cases (issue #708) run the real, mutating adoptAndAttach: they seed a
  nested child under the base's `Subsystem.Subsystem` with create_metadata, adopt it into the
  extension, and read the extension's files back. That used to be deliberately avoided because
  the harness reset only the BASE fixture between tests; it no longer does: a write tool's
  response names the projects it wrote (`writtenProjects`), the harness records that evidence
  (harness._record_outcome) and the orchestrator resets the model of every fixture project a
  test wrote (inside run_all._reset_after_write), so an adoption into the extension is reset
  like a write into the base. Each such test still restores the extension itself in a finally
  block, the way test_create_metadata's extension test does, so a later extension-reading test
  never depends on that order.

DIFF: the non-mutating cases assert assert_no_diff() on the base fixture; the nested-subsystem
cases mutate the base (their seed) and the extension (the adoption), so they assert the files
the adoption must write instead, and that a refused adoption leaves the extension untouched.
"""

import time

from harness import (
    E2ECallTimeout,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    assert_no_diff_rel,
    read_fixture_file,
    reset_all_fixtures,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
    _fail,
)


def _structured(r, ctx):
    """adopt_metadata_object is a JSON tool: the payload is in structuredContent."""
    s = r.structured
    if not isinstance(s, dict):
        raise AssertionError(
            "expected structuredContent dict [%s]; got %r / text=%r"
            % (ctx, s, (r.text or "")[:200]))
    return s


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY (non-mutating): adopting an ALREADY-adopted object is benign
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="adopt_metadata_object", kind="write-metadata")
def test_already_adopted_object_is_benign_not_readopted():
    """The fixture extension already adopts CommonModule.Calc. Adopting it again must hit the
    isAdopted branch: action='alreadyAdopted', objectBelonging='ADOPTED', and NO mutation.

    Mutation thinking: a tool that ignored the already-adopted state would either re-run
    adoptAndAttach (action='adopted') or error — this asserts the specific benign branch and a
    clean tree, so a regression in the isAdopted gate fails here."""
    r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": "CommonModule.Calc"})
    assert_ok(r, "re-adopting an already-adopted object must be a benign success")

    s = _structured(r, "already-adopted payload")
    if s.get("action") != "alreadyAdopted":
        raise AssertionError("expected action=alreadyAdopted; got %r" % s.get("action"))
    if s.get("objectBelonging") != "ADOPTED":
        raise AssertionError("expected objectBelonging=ADOPTED; got %r" % s.get("objectBelonging"))
    if s.get("extensionProject") != TESTS_PROJECT:
        raise AssertionError("expected extensionProject=%s; got %r" % (TESTS_PROJECT, s.get("extensionProject")))

    # #408: "queued nothing" is a FINDING and is published as an empty list. The distinction
    # that matters is against ABSENT, which means "the tool could not tell" - collapsing the two
    # is what let a no-op success and an unknown scope share one value in the old barrier.
    if s.get("writtenProjects") != []:
        raise AssertionError(
            "a success that queued nothing must publish an EMPTY writtenProjects, got %r"
            % (s.get("writtenProjects"),))

    assert_no_diff("adopt of an already-adopted object must not touch the base project")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE / CONTRACT (all non-mutating)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="adopt_metadata_object", kind="write")
def test_missing_required_args_error_clearly():
    """projectName and fqn are required; omitting them is a clear is_error, not a crash."""
    r = call("adopt_metadata_object", {})
    err = assert_error(r, "missing required args")
    low = err.lower()
    if "projectname" not in low and "fqn" not in low:
        raise AssertionError("error must name the missing required parameter; got: " + err)
    assert_no_diff("a rejected adopt must not touch the project")


@e2e_test(tool="adopt_metadata_object", kind="write")
def test_unknown_object_errors_and_names_it():
    """An FQN that resolves to nothing is rejected with a clear, actionable 'not found' that
    names the value and explains the FQN shape — not a stack trace."""
    bogus = "Catalog.NoSuchCatalog_e2e_zzz"
    r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": bogus})
    err = assert_error(r, "unknown object to adopt")
    assert_error_quality(err, names=[bogus], suggests=["Type.Name"],
                         ctx="unknown adopt source names the value + FQN shape")
    assert_no_diff("a rejected adopt must not touch the project")


@e2e_test(tool="adopt_metadata_object", kind="write")
def test_unknown_extension_name_lists_candidates():
    """A bogus extensionProjectName is rejected with an error that names the value AND lists the
    real candidate extension(s), so the caller can correct it.

    Mutation thinking: a tool that ignored extensionProjectName (silently used the only
    extension) or that errored without naming candidates would fail this."""
    r = call("adopt_metadata_object",
             {"projectName": PROJECT, "fqn": "CommonModule.Calc",
              "extensionProjectName": "NoSuchExtension_e2e"})
    err = assert_error(r, "unknown extension name")
    assert_error_quality(err, names=["NoSuchExtension_e2e", TESTS_PROJECT], suggests=[],
                         ctx="unknown extension names the bad value + the real candidate")
    assert_no_diff("a rejected adopt must not touch the project")


# ──────────────────────────────────────────────────────────────────────────────
# NESTED SUBSYSTEM (issue #708) — mutating: the adoption lands in the extension
# ──────────────────────────────────────────────────────────────────────────────
#
# A nested subsystem is a separate top object that its parent only REFERS to, so the source
# resolver never found it: every spelling of the chain - the one list_subsystems prints included -
# answered "Object not found". The base fixture's only subsystem is Subsystem.Subsystem; each test
# seeds a nested child under it, adopts it, and reads the EXTENSION's files back directly. The
# adoption submits the export of every file it changed (the child, each parent subsystem, the
# extension's Configuration.mdo) and waits for the extension's export queue before it answers, so
# the files are normally settled when the call returns. Each check still POLLS for one read that
# holds every needle: that wait is skipped where the export state cannot be observed, and a late
# export pass (the platform also exports a changed top object on its own) may still be rewriting
# the file.

_FIXTURE_SUBSYSTEM = "Subsystem"
_PARENT_FQN = "Subsystem." + _FIXTURE_SUBSYSTEM
# "Подсистема" - the Russian type token, written as escapes like the Java tests.
_RU_SUBSYSTEM = "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430"
# Extension-side files, relative to the extension fixture (TESTS_PROJECT_REL).
_EXT_CONFIGURATION_MDO = "src/Configuration/Configuration.mdo"
_EXT_PARENT_MDO = "src/Subsystems/%s/%s.mdo" % (_FIXTURE_SUBSYSTEM, _FIXTURE_SUBSYSTEM)
_ADOPTED = "<objectBelonging>Adopted</objectBelonging>"


def _nested_fqn(child):
    """The chain of a subsystem nested directly under the fixture subsystem."""
    return "%s.Subsystem.%s" % (_PARENT_FQN, child)


def _ext_child_mdo(child):
    """The extension-relative .mdo of a nested subsystem under the fixture subsystem."""
    return "src/Subsystems/%s/Subsystems/%s/%s.mdo" % (_FIXTURE_SUBSYSTEM, child, child)


def _poll_extension_file(relpath, needles, ctx, timeout=10):
    """Poll until ONE read of an EXTENSION fixture file holds every needle, and return that read.

    poll_disk_contains_all, its base-fixture twin, cannot be used: it reads the base project only.
    Polling each needle separately would prove each was present at SOME moment, not that one
    snapshot of the file holds them all. A missing file - or a read that lands mid-rewrite - just
    keeps polling: the export may not have finished yet."""
    deadline = time.time() + timeout
    text = None
    while True:
        try:
            text = read_fixture_file(TESTS_PROJECT_REL, relpath)
        except (OSError, ValueError):
            text = None
        if text is not None and all(needle in text for needle in needles):
            return text
        if time.time() >= deadline:
            missing = [needle for needle in needles if text is None or needle not in text]
            _fail("expected %s/%s to contain %r [%s]; it holds:\n%s"
                  % (TESTS_PROJECT_REL, relpath, missing, ctx,
                     "(the file does not exist)" if text is None else text[:700]))
        time.sleep(0.5)


def _seed_nested_child(child):
    """Create a nested subsystem under the fixture subsystem in the BASE project; return its chain."""
    fqn = _nested_fqn(child)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
              "seed the nested subsystem %s in the base" % fqn)
    wait_for_project_ready()
    return fqn


def _assert_adopted(r, fqn, ctx):
    """An adoption that ran: the canonical chain, into the extension, written there and only there."""
    assert_ok(r, ctx)
    s = _structured(r, ctx)
    for key, value in (("action", "adopted"), ("fqn", fqn), ("extensionProject", TESTS_PROJECT),
                       ("objectBelonging", "ADOPTED")):
        if s.get(key) != value:
            raise AssertionError("expected %s=%r [%s]; got %r" % (key, value, ctx, s))
    if s.get("writtenProjects") != [TESTS_PROJECT]:
        raise AssertionError("an adoption writes the EXTENSION and nothing else [%s]; got "
                             "writtenProjects=%r" % (ctx, s.get("writtenProjects")))
    return s


def _assert_nested_adoption_on_disk(child, ctx):
    """The three files a nested adoption changes in the extension, each by its structural element."""
    _poll_extension_file(
        _ext_child_mdo(child),
        ["<name>%s</name>" % child, _ADOPTED, "<parentSubsystem>%s</parentSubsystem>" % _PARENT_FQN],
        ctx + ": the adopted child has its own .mdo, linked to its parent")
    _poll_extension_file(
        _EXT_PARENT_MDO,
        ["<name>%s</name>" % _FIXTURE_SUBSYSTEM, _ADOPTED, "<subsystems>%s</subsystems>" % child],
        ctx + ": the adopted parent lists the child in <subsystems>")
    _poll_extension_file(
        _EXT_CONFIGURATION_MDO, ["<subsystems>%s</subsystems>" % _PARENT_FQN],
        ctx + ": the extension's configuration lists the top-level parent")


def _restore_extension_fixture():
    """Revert the EXTENSION fixture (disk + model) after an adoption mutated it.

    The sequence test_create_metadata and test_apply_quick_fix use: reset -> clean_project ->
    ready -> a SECOND reset (a late export pass can still race the first) -> ready again, so the
    next extension-reading test starts from the committed fixture."""
    reset_all_fixtures()
    r_clean = call("clean_project", {"projectName": TESTS_PROJECT})
    assert_ok(r_clean, "clean_project after a nested-subsystem adoption must succeed, or the "
              "extension model stays polluted for later tests")
    wait_for_project_ready()
    reset_all_fixtures()
    wait_for_project_ready()


@e2e_test(tool="adopt_metadata_object", kind="write-metadata")
def test_nested_subsystem_is_adopted_by_the_chain_list_subsystems_prints():
    """#708 in the reporter's order: the top-level parent is adopted FIRST (step 4, which always
    worked), then the nested child by exactly the chain list_subsystems prints (step 2, which
    answered "Object not found"). The platform then links the child under the parent the extension
    already holds - a different branch from the cascade below - and the parent's .mdo has to be
    exported again, because it now lists the child.

    Mutation thinking: without the chain branch the child is refused; a chain resolved one level
    short answers 'alreadyAdopted' for the PARENT; a result that echoes the input instead of the
    canonical chain fails the Russian re-adoption, in its `fqn` and in its `message`. WHICH files
    the adoption queues for export is pinned by the unit test (AdoptMetadataObjectToolTest), not
    here: the platform also exports a changed top object on its own after the commit, so this test
    asserts the state the extension ends in on disk, not which call queued it."""
    child = "E2EAdoptNestedAfterParent"
    try:
        fqn = _seed_nested_child(child)
        listed = call("list_subsystems", {"projectName": PROJECT})
        assert_ok(listed, "list_subsystems read-back of the seed")
        assert_contains(listed.text, fqn, "list_subsystems prints the chain the adoption is addressed by")

        _assert_adopted(call("adopt_metadata_object", {"projectName": PROJECT, "fqn": _PARENT_FQN}),
                        _PARENT_FQN, "adopt the top-level parent first")
        r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": fqn})
        _assert_adopted(r, fqn, "adopt the nested subsystem by the chain list_subsystems prints")
        _assert_nested_adoption_on_disk(child, "adopted under an already adopted parent")

        # MODEL read-back in the extension: its own tree renders the adopted child by the chain.
        wait_for_project_ready()
        details = call("get_metadata_details", {"projectName": TESTS_PROJECT, "objectFqns": [fqn]})
        assert_ok(details, "read the adopted nested subsystem back from the extension")
        assert_contains(details.text, "## Subsystem: " + child,
                        "the extension renders the adopted NESTED subsystem, not its parent")
        assert_contains(details.text, "**Origin:** core (adopted)",
                        "the extension's copy is an adopted base object")

        # The Russian spelling of the same chain addresses the same, now adopted, object - and the
        # result names it by the canonical chain, not by the half-translated input.
        ru = "%s.%s.%s.%s" % (_RU_SUBSYSTEM, _FIXTURE_SUBSYSTEM, _RU_SUBSYSTEM, child)
        again = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": ru})
        assert_ok(again, "re-adopt the nested subsystem by its Russian spelling")
        s = _structured(again, "re-adoption payload")
        if s.get("action") != "alreadyAdopted":
            raise AssertionError("the same nested subsystem must be found already adopted; got %r" % (s,))
        if s.get("fqn") != fqn:
            raise AssertionError("the Russian spelling must come back as the canonical chain %r; got %r"
                                 % (fqn, s.get("fqn")))
        message = "'%s' is already adopted in extension '%s'." % (fqn, TESTS_PROJECT)
        if s.get("message") != message:
            raise AssertionError("the message must name the canonical chain too: expected %r; got %r"
                                 % (message, s.get("message")))
        if s.get("writtenProjects") != []:
            raise AssertionError("a re-adoption queues nothing and must say so; got writtenProjects=%r"
                                 % (s.get("writtenProjects"),))
    except E2ECallTimeout:
        # NO cleanup here: the timed-out call may still be writing these very files, and a git
        # reset would race it. The orchestrator aborts the run on this.
        raise
    except BaseException:
        _restore_extension_fixture()
        raise
    else:
        _restore_extension_fixture()


@e2e_test(tool="adopt_metadata_object", kind="write-metadata")
def test_nested_subsystem_adoption_adopts_its_parent_too():
    """The cascade: the nested child is adopted - by its Russian spelling - while the extension does
    not hold its parent, so the platform adopts the parent first. Before that, the two refusals
    around it: the bare child name (the issue's fourth spelling, which names a TOP-level subsystem
    only) and a chain that does not exist - neither may adopt anything, the parent in particular.

    Mutation thinking: a resolver that answered a missing chain with its PARENT would adopt
    Subsystem.Subsystem here - a success where the refusal is expected, so assert_error fails
    first (the extension would not stay clean either); a refusal without the chain hint leaves the
    caller where the issue left the reporter; a result that echoes the Russian input instead of the
    canonical chain fails _assert_adopted. The export list is pinned by the unit test, as above."""
    child = "E2EAdoptNestedCascade"
    try:
        fqn = _seed_nested_child(child)

        bare = "Subsystem." + child
        e = assert_error(call("adopt_metadata_object", {"projectName": PROJECT, "fqn": bare}),
                         "a bare nested child names a top-level subsystem only")
        assert_error_quality(e, names=[bare],
                             suggests=["Type.Name", "Subsystem.<Parent>.Subsystem.<Child>",
                                       "list_subsystems"],
                             ctx="the refusal names the value and teaches the chain")
        missing = _nested_fqn("E2EAdoptNoSuchChild")
        e = assert_error(call("adopt_metadata_object", {"projectName": PROJECT, "fqn": missing}),
                         "a nested chain that does not exist")
        assert_error_quality(e, names=[missing], suggests=["not found", "list_subsystems"],
                             ctx="a missing chain is not found - never its parent")
        assert_no_diff_rel(TESTS_PROJECT_REL, "a refused adoption must not touch the extension - "
                           "in particular, the parent must not have been adopted in its place")

        # Adopted by the Russian spelling of the chain: the result must still name the canonical one.
        ru = "%s.%s.%s.%s" % (_RU_SUBSYSTEM, _FIXTURE_SUBSYSTEM, _RU_SUBSYSTEM, child)
        r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": ru})
        _assert_adopted(r, fqn, "adopt a nested subsystem whose parent the extension does not hold, "
                        "by its Russian spelling")
        _assert_nested_adoption_on_disk(child, "the cascade adopted the parent")

        # MODEL read-back: the cascade really adopted the parent, and the extension's own tree
        # holds the whole chain.
        wait_for_project_ready()
        parent = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": _PARENT_FQN})
        assert_ok(parent, "the parent after the cascade")
        if _structured(parent, "parent payload").get("action") != "alreadyAdopted":
            raise AssertionError("the cascade must have adopted the parent; got %r" % (parent.structured,))
        listed = call("list_subsystems", {"projectName": TESTS_PROJECT})
        assert_ok(listed, "list_subsystems on the extension")
        assert_contains(listed.text, fqn, "the extension's tree holds the adopted chain")
    except E2ECallTimeout:
        # NO cleanup here: the timed-out call may still be writing these very files.
        raise
    except BaseException:
        _restore_extension_fixture()
        raise
    else:
        _restore_extension_fixture()

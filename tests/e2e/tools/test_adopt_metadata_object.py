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
  action='alreadyAdopted' (the tool's isAdopted branch) WITHOUT changing anything. The other
  cases are negative/contract (missing args, unknown object, unknown extension name) — all
  benign, mutation-sensitive, and leaving the tree clean. The actual mutating adoptAndAttach
  happy path (adopt a NOT-yet-adopted base object → objectBelonging=Adopted +
  extendedConfigurationObject on disk) is validated LIVE during the dev redeploy loop and is a
  candidate for the gated live-infobase suite; it is deliberately NOT run headless here because
  the black-box harness resets only the BASE fixture per-test, not the extension, so a real
  adopt would pollute tests/tests for later extension-reading tests (final_cleanup reverts it
  only at run end).

DIFF: adopt mutates the EXTENSION, never the base git-tracked project on these paths; every
case is non-mutating, so each asserts assert_no_diff() on the base fixture.
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
    TESTS_PROJECT,
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
@e2e_test(tool="adopt_metadata_object", kind="write")
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

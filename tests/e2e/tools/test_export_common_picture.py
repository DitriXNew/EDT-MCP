"""
e2e tests for export_common_picture (kind: read).

WHAT THE TOOL DOES
  Exports the raster/vector content of a configuration CommonPicture as JSON. It
  resolves the CommonPicture by FQN (MetadataTypeUtils.COMMON_PICTURE +
  MetadataNodeResolver.resolveExisting, bilingual — English programmatic Name or the
  Russian TYPE token ОбщаяКартинка), enumerates the picture's variants from its
  Picture.zip manifest, and — when a `variant` is requested — decodes/rasterizes that
  one entry to a PNG returned as base64.
  Source: ExportCommonPictureTool.java + utils/CommonPictureContentReader.java.

WIRE SHAPE (why this file reads r.structured, not r.text)
  getResponseType() == JSON, so on SUCCESS the real payload lands in
  Result.structured (r.text is just a "Done" placeholder). The success envelope is the
  frozen E3 shape:
      { "fqn": "CommonPicture.<Name>",
        "variants": [ {"name","dpi","theme","interfaceVariant","pictureDirection",
                       "template","glyphWidth","glyphHeight","contentType","sizeBytes"}, ...],
        "selected"?: {"name","contentType":"image/png","sizeBytes","pngBase64"} }
  `selected` is present ONLY when a `variant` argument was supplied (list-only omits it).
  On error the protocol layer marks the result isError and r.error_text() carries the
  "error" string (consumable by assert_error / assert_error_quality).

FIXTURE TRUTH (why the happy paths are DISCOVERY-GATED, not hard-coded)
  The git-tracked TestConfiguration fixture has NO CommonPicture (there is no
  src/CommonPictures/ directory). A picture's binary content (Picture.zip) is also not
  something these tests may invent. So the happy paths do NOT hard-code a picture name:
  they DISCOVER whether the workspace holds any CommonPicture via the sibling
  list_common_pictures tool and, only if one is present, assert the frozen E3 STRUCTURAL
  INVARIANTS against it (they never pin a specific picture's bytes). When no picture
  exists the happy paths assert the well-formed error/empty behaviour instead and
  E2ESkip the content-bearing assertions — exactly the fixture-lacks-data contract the
  slice requires (assert the error/empty path rather than inventing data).

  The negative matrix is fixture-independent: every case targets a real execute()
  resolution/validation error path and is always exercised.
"""

import re

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    E2ESkip,
    PROJECT,
)

# ListCommonPicturesTool renders each picture as a Markdown sub-heading of its bare
# programmatic Name (optionally followed by " (synonym)") — e.g. "### MyIcon" or
# "### MyIcon (My Icon)" — it does NOT print the "CommonPicture." FQN token (the only
# near-match, the "## Common Pictures:" page header, is excluded by the three-hash
# anchor). So we parse the "### <Name>" picture headings and rebuild the FQN as
# "CommonPicture." + <Name> ourselves. group(1) is the heading text up to (but excluding)
# any trailing " (synonym)" group.
_PICTURE_HEADING_RE = re.compile(r"(?m)^###[ \t]+(.+?)(?:[ \t]+\([^)]*\))?[ \t]*$")


def _discover_common_picture():
    """Return the FQN of SOME existing CommonPicture in the fixture project, or None.

    Environment-tolerant discovery (mirrors get_applications' count==0 tolerance): the
    fixture may carry no CommonPicture, in which case the content-bearing happy paths
    have nothing to assert and are skipped. Uses the sibling list_common_pictures tool
    (MARKDOWN -> r.text) so we never invent a picture name: it parses that tool's
    "### <Name>" picture sub-headings and rebuilds the FQN as "CommonPicture.<Name>"
    (the list tool prints the bare Name, never the FQN token)."""
    r = call("list_common_pictures", {"projectName": PROJECT})
    if r.is_error:
        return None
    m = _PICTURE_HEADING_RE.search(r.text or "")
    if not m:
        return None
    name = m.group(1).strip()
    return "CommonPicture." + name if name else None


def _assert_export_envelope(sc, fqn, ctx):
    """Validate the frozen E3 success envelope (list-only or selected) STRUCTURALLY.

    Pins the wire contract without hard-coding any specific picture's data:
      - the envelope echoes the requested fqn;
      - "variants" is a list; each entry carries every E3 field with the right JSON type
        (string name/dpi/theme/interfaceVariant/pictureDirection/contentType, boolean
        template, int glyphWidth/glyphHeight, int sizeBytes)."""
    if not isinstance(sc, dict):
        raise AssertionError("expected a JSON object envelope [%s]: %r" % (ctx, sc))
    if sc.get("fqn") != fqn:
        raise AssertionError("envelope must echo fqn %r [%s]: %r" % (fqn, ctx, sc.get("fqn")))
    variants = sc.get("variants")
    if not isinstance(variants, list):
        raise AssertionError("'variants' must be a list [%s]: %r" % (ctx, variants))
    for v in variants:
        if not isinstance(v, dict):
            raise AssertionError("each variant must be an object [%s]: %r" % (ctx, v))
        for key in ("name", "dpi", "theme", "interfaceVariant", "pictureDirection", "contentType"):
            if not isinstance(v.get(key), str):
                raise AssertionError(
                    "variant.%s must be a string [%s]: %r" % (key, ctx, v.get(key)))
        if not isinstance(v.get("template"), bool):
            raise AssertionError("variant.template must be a boolean [%s]: %r" % (ctx, v.get("template")))
        for key in ("glyphWidth", "glyphHeight", "sizeBytes"):
            # bool is a subclass of int in Python; reject it explicitly so a mistyped
            # boolean does not sneak past the int check.
            if not isinstance(v.get(key), int) or isinstance(v.get(key), bool):
                raise AssertionError(
                    "variant.%s must be an int [%s]: %r" % (key, ctx, v.get(key)))
    return variants


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (discovery-gated: assert the frozen E3 shape only if a picture exists)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="export_common_picture", kind="read")
def test_list_only_returns_variants_without_selected():
    """No `variant` argument -> the E3 envelope carries variants[] and OMITS `selected`.

    Discovery-gated: if the fixture has no CommonPicture there is nothing to export, so
    we E2ESkip (the fixture-lacks-data path is proved separately by the negative matrix).
    When a picture IS present this pins the core list-only contract: a well-formed
    variants list AND the absence of the `selected` key (selected appears ONLY when a
    variant was requested)."""
    fqn = _discover_common_picture()
    if fqn is None:
        raise E2ESkip("no CommonPicture in the fixture workspace -> nothing to export "
                      "(negative matrix proves the resolution errors)")
    r = call("export_common_picture", {"projectName": PROJECT, "fqn": fqn})
    assert not r.is_error, (
        "exporting the existing %r (list-only) must succeed; got error: %r"
        % (fqn, r.error_text()[:300]))
    sc = r.structured
    _assert_export_envelope(sc, fqn, "list-only envelope")
    # The defining list-only signal: no variant requested -> no `selected` in the shape.
    if "selected" in sc:
        raise AssertionError(
            "list-only (no variant) must OMIT 'selected' from the E3 envelope: %r" % sc)
    assert_no_diff("an export read must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_selected_variant_returns_png_base64():
    """A chosen `variant` -> the E3 envelope adds `selected` with a PNG payload.

    Discovery-gated on BOTH a picture existing AND it having at least one variant to
    select. Picks the first enumerated variant name (list-only) and re-exports it, then
    pins the frozen `selected` contract: name, contentType == "image/png", an int
    sizeBytes, and a non-empty pngBase64 string (the tool ALWAYS emits PNG regardless of
    the source content type)."""
    fqn = _discover_common_picture()
    if fqn is None:
        raise E2ESkip("no CommonPicture in the fixture workspace -> no variant to select")
    # First enumerate to obtain a real variant name (never invent one).
    listing = call("export_common_picture", {"projectName": PROJECT, "fqn": fqn})
    assert not listing.is_error, (
        "list-only export of %r must succeed before selecting a variant; got: %r"
        % (fqn, listing.error_text()[:300]))
    variants = _assert_export_envelope(listing.structured, fqn, "pre-select listing")
    if not variants:
        raise E2ESkip("CommonPicture %r has no enumerable variants -> nothing to select" % fqn)
    variant_name = variants[0]["name"]

    r = call("export_common_picture",
             {"projectName": PROJECT, "fqn": fqn, "variant": variant_name})
    assert not r.is_error, (
        "exporting %r variant %r must succeed; got error: %r"
        % (fqn, variant_name, r.error_text()[:300]))
    sc = r.structured
    _assert_export_envelope(sc, fqn, "selected envelope")
    selected = sc.get("selected")
    if not isinstance(selected, dict):
        raise AssertionError("a requested variant must produce a 'selected' object: %r" % sc)
    if not selected.get("name"):
        raise AssertionError("selected must carry a non-empty 'name': %r" % selected)
    if selected.get("contentType") != "image/png":
        raise AssertionError(
            "selected.contentType must be 'image/png' (the tool always emits PNG): %r"
            % selected.get("contentType"))
    if not isinstance(selected.get("sizeBytes"), int) or isinstance(selected.get("sizeBytes"), bool):
        raise AssertionError("selected.sizeBytes must be an int: %r" % selected.get("sizeBytes"))
    b64 = selected.get("pngBase64")
    if not isinstance(b64, str) or not b64:
        raise AssertionError("selected must carry a non-empty base64 'pngBase64' string: %r" % selected)
    assert_no_diff("an export read must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_russian_type_token_resolves_same_picture():
    """The Russian TYPE token `ОбщаяКартинка.<Name>` resolves the SAME picture as the
    English `CommonPicture.<Name>` FQN.

    Closes the bilingual ratchet end-to-end: list_common_pictures only ever yields the
    English token, so the RU token path is never otherwise exercised live. We DISCOVER an
    existing picture (English FQN), swap only the TYPE token to the Russian
    `ОбщаяКартинка` (the programmatic Name is unchanged — only the token is bilingual),
    re-export, and pin the SAME frozen E3 envelope (list-only: variants[], no `selected`).
    The envelope echoes the requested fqn verbatim, so it carries the RU-token string.

    Discovery-gated: with no CommonPicture in the fixture there is nothing to re-resolve,
    so we E2ESkip (the fixture-lacks-data path is proved by the negative matrix)."""
    fqn = _discover_common_picture()
    if fqn is None:
        raise E2ESkip("no CommonPicture in the fixture workspace -> no picture to re-resolve "
                      "via the Russian type token")
    # Swap ONLY the leading English type token for its Russian counterpart; the
    # programmatic Name after the dot is identical (only the TYPE token is bilingual).
    ru_fqn = "ОбщаяКартинка." + fqn.split(".", 1)[1]
    r = call("export_common_picture", {"projectName": PROJECT, "fqn": ru_fqn})
    assert not r.is_error, (
        "the Russian type token %r must resolve the same picture as %r; got error: %r"
        % (ru_fqn, fqn, r.error_text()[:300]))
    sc = r.structured
    _assert_export_envelope(sc, ru_fqn, "russian-type-token envelope")
    # Same list-only contract as the English path: no variant requested -> no `selected`.
    if "selected" in sc:
        raise AssertionError(
            "list-only (no variant) via the Russian token must OMIT 'selected': %r" % sc)
    assert_no_diff("an export read must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (fixture-independent — each targets a real execute() error path)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="export_common_picture", kind="read")
def test_missing_projectname_errors_actionably():
    """fqn supplied but projectName omitted. Display-free early validation short-circuits
    before any model access -> "projectName is required"."""
    r = call("export_common_picture", {"fqn": "CommonPicture.Whatever"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_missing_fqn_errors_actionably():
    """projectName supplied but fqn omitted -> "fqn is required" (early validation, no
    model access)."""
    r = call("export_common_picture", {"projectName": PROJECT})
    err = assert_error(r, "missing fqn")
    assert_error_quality(err, names=["fqn"], suggests=[],
                         ctx="missing fqn names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """The project does not exist -> the shared ProjectContext not-found message names the
    bad value and points at list_projects."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("export_common_picture", {"projectName": bad, "fqn": "CommonPicture.Whatever"})
    err = assert_error(r, "non-existent project")
    assert_error_quality(err, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_nonexistent_picture_cannot_resolve():
    """A well-formed CommonPicture FQN that does not resolve to an existing object must
    error and NAME the bad FQN (resolution failure inside the read transaction), not
    return a bogus empty-success envelope."""
    bad = "CommonPicture.NoSuchPicture_ZZZ_e2e"
    r = call("export_common_picture", {"projectName": PROJECT, "fqn": bad})
    err = assert_error(r, "non-existent common picture")
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="unresolvable picture names the bad FQN")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_russian_type_token_resolves_bilingually():
    """The Russian CommonPicture TYPE token ОбщаяКартинка must be accepted end-to-end.

    The tool resolves the picture bilingually (MetadataTypeUtils.COMMON_PICTURE +
    MetadataNodeResolver.resolveExisting), so an 'ОбщаяКартинка.<Name>' FQN must reach the
    SAME resolution path as 'CommonPicture.<Name>'. This is a fixture-independent negative
    case: a well-formed Russian FQN that does not resolve must error and NAME the bad FQN
    (proving the ОбщаяКартинка token was recognised as a CommonPicture type, not rejected
    as an unknown type), never a bogus empty-success envelope."""
    bad = "ОбщаяКартинка.NoSuchPicture_ZZZ_e2e"
    r = call("export_common_picture", {"projectName": PROJECT, "fqn": bad})
    err = assert_error(r, "non-existent common picture via Russian type token")
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="unresolvable ОбщаяКартинка FQN names the bad FQN")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="export_common_picture", kind="read")
def test_unknown_variant_on_picture_with_variants_errors_actionably():
    """An existing picture that HAS variants + an UNRESOLVABLE `variant` -> an ERROR.

    This pins the shipped contract (ExportCommonPictureTool lines 201-207 and the export
    guide line 53, which BOTH agree): when a non-blank `variant` does not resolve against a
    picture that DOES expose variants (an unknown exact name, or `svg` on a picture with no
    vector variant), the call **errors** with
        "Unknown variant '<variant>' for CommonPicture '<fqn>'. Available: <names> (or the
         keywords 'best'/'svg')."
    The error carries no `selected`/`variants` payload; it names the bad value and lists the
    valid entry names so the caller can pick one (the frozen error-shape rule: name the bad
    value + the fix). This is NOT a list-only success — a typo'd selector is a real error.

    (The frozen null/blank-selector => list-only, no-`selected` path is a DIFFERENT branch
    and is already covered by test_list_only_returns_variants_without_selected; do not
    conflate an unknown non-blank selector, which errors, with a null selector, which lists.)

    Discovery-gated on a picture existing AND exposing at least one variant: the
    unknown-variant error branch only triggers when `variants` is non-empty (a single-image
    picture with an empty inventory has no entry to reject, so `selected` is simply absent
    there — a separate documented case). With no such picture the resolution branch is
    already covered above, so we skip."""
    fqn = _discover_common_picture()
    if fqn is None:
        raise E2ESkip("no CommonPicture in the fixture -> cannot reach the variant-selection path")
    # The error branch requires the picture to expose variants; enumerate first so we skip
    # a single-image (empty-inventory) picture, which would take the no-error absent-selected
    # branch instead of the unknown-variant error branch.
    listing = call("export_common_picture", {"projectName": PROJECT, "fqn": fqn})
    assert not listing.is_error, (
        "list-only export of %r must succeed before probing an unknown variant; got: %r"
        % (fqn, listing.error_text()[:300]))
    variants = _assert_export_envelope(listing.structured, fqn, "pre-probe listing")
    if not variants:
        raise E2ESkip("CommonPicture %r exposes no variants -> an unknown variant is the "
                      "absent-selected branch, not the unknown-variant error branch" % fqn)
    bad_variant = "NoSuchVariant_ZZZ_e2e"
    r = call("export_common_picture",
             {"projectName": PROJECT, "fqn": fqn, "variant": bad_variant})
    err = assert_error(r, "unknown variant on a picture that has variants")
    # The message must name the bad selector AND the fqn, and list the valid entry names +
    # the 'best'/'svg' keywords so the caller can pick a real one (ExportCommonPictureTool
    # lines 204-206; guide line 53).
    assert_error_quality(
        err,
        names=[bad_variant, fqn, variants[0]["name"]],
        suggests=["best", "svg"],
        ctx="unknown variant names the bad value + fqn and lists the available entries")
    assert_no_diff("a failed export must not touch the project on disk")

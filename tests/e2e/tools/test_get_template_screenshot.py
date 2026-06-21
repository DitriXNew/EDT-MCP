"""
e2e tests for get_template_screenshot (kind: read).

WHAT THE TOOL DOES
  Renders a 1C template (макет) - a SpreadsheetDocument print form - to a PNG and
  returns it as an IMAGE response. Resolves the template object by FQN
  (MetadataNodeResolver) and opens it in the EDT template editor via the same path
  the navigator's "Open" uses, so it works for BOTH a common template
  (CommonTemplate.<Name>) AND an object-owned template (<Type>.<Owner>.Template.<Name>);
  then rasterizes the embedded spreadsheet off-screen via EDT's print/preview pipeline.
  Source: GetTemplateScreenshotTool.java + utils/TemplateScreenshotHelper.java.

WIRE SHAPE (why this file reads r.raw, not r.text/r.structured)
  getResponseType() == IMAGE - identical wire contract to get_form_screenshot:
    * SUCCESS -> content[0] = {"type":"resource","resource":{"mimeType":"image/png",
                 "blob":<base64>}}; r.is_error == False, r.text == "".
    * FAILURE -> ToolResult.error(...).toJson() -> isError:true; r.error_text() carries
                 the "error" string -> consumable by assert_error / assert_error_quality.

RENDER (no JVM flag - unlike forms)
  A SpreadsheetDocument renders off-screen on the UI thread inside an executeAndRollback
  BM sandbox (the print pipeline lazily mutates the doc while painting; the sandbox
  discards those edits). There is NO -DnativeFormBufferedLayoutRender dependency, so a
  healthy stand renders a real, non-empty PNG. The happy paths decode the blob and
  assert a valid PNG whose IHDR width AND height are > 0; the only tolerated error is the
  cold-editor sentinel (a headless/cold workbench where the editor never realizes).

  Read-only w.r.t. model + disk (the render's transient model writes are rolled back),
  so every test ends with assert_no_diff().

FIXTURE TRUTH (TestConfiguration, English Names)
  - Common template "PrintForm" at src/CommonTemplates/PrintForm/ (cell "E2E Print
    Template") -> FQN "CommonTemplate.PrintForm".
  - Owned template "Invoice" on Catalog "Catalog" at
    src/Catalogs/Catalog/Templates/Invoice/Template.mxlx (cell "Owned Invoice Template")
    -> FQN "Catalog.Catalog.Template.Invoice".
  - "Catalog.Catalog" itself is a Catalog (a real object of the WRONG kind for a template
    FQN). "Document"/non-existent names exercise the resolution errors.

KNOWN UNTESTED BRANCHES (live-only; no fixture exercises them)
  - Non-SpreadsheetDocument template rejection ("is not a SpreadsheetDocument template"):
    needs a template of a different TemplateType.
  - Multi-page vertical stitching (combinePagesVertically): both fixtures are one small
    page. The single-page path IS covered by both happy paths.
"""

import base64
import struct

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# The ONLY error tolerated by a happy path: the cold-editor sentinel, when the template
# editor's spreadsheet control never realizes (e.g. a headless/cold workbench).
_COLD_EDITOR_SENTINEL = "did not finish initializing"


def _blob(result):
    """Extract the IMAGE resource blob from the raw JSON-RPC response, or None."""
    res = result.raw.get("result") if isinstance(result.raw, dict) else None
    if not isinstance(res, dict):
        return None
    content = res.get("content") or []
    if content and isinstance(content[0], dict):
        resource = content[0].get("resource")
        if isinstance(resource, dict):
            return resource.get("blob")
    return None


def _png_dimensions(blob_b64):
    """Decode a base64 PNG blob and return (width, height) from its IHDR chunk, or None if the
    bytes are not a valid PNG. A real PNG starts with the 8-byte signature; its first chunk is
    IHDR, carrying width and height as big-endian uint32 at byte offsets 16 and 20. This is what
    proves the screenshot is a genuine, NON-EMPTY image rather than an empty/stub blob."""
    try:
        data = base64.b64decode(blob_b64)
    except Exception:
        return None
    if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) < 24 or data[12:16] != b"IHDR":
        return None
    width, height = struct.unpack(">II", data[16:24])
    return (width, height)


def _assert_nonempty_png_or_cold_sentinel(r, fqn):
    """Shared happy-path contract: the success channel MUST carry a genuine, NON-EMPTY PNG
    (valid signature + IHDR width AND height > 0); the only acceptable error is the cold-editor
    sentinel. Any other error for an existing, content-bearing template is a real bug."""
    if r.is_error:
        err = r.error_text()
        assert _COLD_EDITOR_SENTINEL in err, (
            "for the existing, content-bearing %r the only acceptable error is the cold-editor "
            "%r sentinel; got: %r" % (fqn, _COLD_EDITOR_SENTINEL, err[:300])
        )
        return
    blob = _blob(r)
    assert blob, (
        "success channel must carry an image blob at content[0].resource.blob; got none "
        "(raw: %r)" % (str(r.raw)[:300])
    )
    dims = _png_dimensions(blob)
    assert dims is not None, (
        "the image blob must decode to a valid PNG (signature + IHDR); got prefix %r" % (blob[:16])
    )
    width, height = dims
    assert width > 0 and height > 0, (
        "the screenshot must be a NON-EMPTY PNG (IHDR width and height > 0); got %dx%d"
        % (width, height)
    )


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (render-dependent — assert a real NON-EMPTY PNG)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_template_screenshot", kind="read")
def test_capture_common_template_returns_nonempty_png():
    """Render the real fixture COMMON template CommonTemplate.PrintForm and validate a
    non-empty PNG is produced (the core contract). See _assert_nonempty_png_or_cold_sentinel."""
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": "CommonTemplate.PrintForm",
    })
    _assert_nonempty_png_or_cold_sentinel(r, "CommonTemplate.PrintForm")
    assert_no_diff("a template screenshot read must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_capture_owned_template_returns_nonempty_png():
    """Render the real fixture OBJECT-OWNED template Catalog.Catalog.Template.Invoice (a template
    that belongs to a Catalog, serialized inline in the owner's .mdo) and validate a non-empty
    PNG. This proves owned templates - not just common ones - render."""
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": "Catalog.Catalog.Template.Invoice",
    })
    _assert_nonempty_png_or_cold_sentinel(r, "Catalog.Catalog.Template.Invoice")
    assert_no_diff("a template screenshot read must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (each targets a REAL execute()/resolution error path)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_template_screenshot", kind="read")
def test_missing_projectname_errors_actionably():
    """templatePath supplied but projectName omitted. execute() short-circuits before any
    Display access -> "projectName is required"."""
    r = call("get_template_screenshot", {"templatePath": "CommonTemplate.PrintForm"})
    err = assert_error(r, "projectName missing")
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_missing_templatepath_errors_actionably():
    """projectName supplied but templatePath omitted -> "templatePath is required"."""
    r = call("get_template_screenshot", {"projectName": PROJECT})
    err = assert_error(r, "templatePath missing")
    assert_error_quality(err, names=["templatePath"], suggests=[],
                         ctx="missing templatePath names the required param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """The project does not exist -> "Project not found: <projectName>. Use list_projects ..."."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_template_screenshot", {"projectName": bad, "templatePath": "CommonTemplate.PrintForm"})
    err = assert_error(r, "non-existent project")
    assert_error_quality(err, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_nonexistent_common_template_cannot_resolve():
    """A well-formed common-template FQN that does not resolve to an existing object ->
    "Cannot resolve template '<fqn>'. Expected a template FQN: ...". Names the bad FQN and the
    accepted shapes."""
    bad = "CommonTemplate.NoSuchTemplate_ZZZ_e2e"
    r = call("get_template_screenshot", {"projectName": PROJECT, "templatePath": bad})
    err = assert_error(r, "non-existent common template")
    assert_error_quality(err, names=[bad], suggests=["CommonTemplate."],
                         ctx="unresolvable template names the value and the accepted shape")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_nonexistent_owned_template_cannot_resolve():
    """An owned-template FQN whose owner exists but the template name does not ->
    "Cannot resolve template '<fqn>'." (the owner Catalog 'Catalog' exists; 'NoSuch' template
    does not). Confirms owned templates resolve through the same model path."""
    bad = "Catalog.Catalog.Template.NoSuch_ZZZ_e2e"
    r = call("get_template_screenshot", {"projectName": PROJECT, "templatePath": bad})
    err = assert_error(r, "non-existent owned template")
    assert_error_quality(err, names=[bad], suggests=["Template"],
                         ctx="unresolvable owned template names the value and the accepted shape")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_non_template_object_rejected_with_guidance():
    """An FQN that resolves to a real object of the WRONG kind (Catalog 'Catalog' is a Catalog,
    not a template) -> "'<fqn>' is not a template (it resolves to a Catalog). Pass a template
    FQN ...". Names the value and the accepted shapes."""
    bad = "Catalog.Catalog"
    r = call("get_template_screenshot", {"projectName": PROJECT, "templatePath": bad})
    err = assert_error(r, "non-template object")
    assert_error_quality(err, names=[bad, "is not a template"], suggests=["CommonTemplate."],
                         ctx="wrong-kind object names the value and the accepted shape")
    assert_no_diff("an invalid call must not touch the project on disk")

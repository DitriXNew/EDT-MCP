"""
e2e tests for get_template_screenshot (kind: read).

WHAT THE TOOL DOES
  Renders a 1C template (макет) - a SpreadsheetDocument print form - to a PNG and
  returns it as an IMAGE response. Opens the common-template editor by metadata FQN
  (CommonTemplate.<Name>), reaches its embedded spreadsheet (moxel) editor and
  rasterizes it off-screen via EDT's print/preview pipeline.
  Source: GetTemplateScreenshotTool.java (capture in utils/TemplateScreenshotHelper.java,
  FQN->file mapping in utils/MetadataPathResolver.resolveTemplateMdoPath).

WIRE SHAPE (why this file reads r.raw, not r.text/r.structured)
  getResponseType() == IMAGE - identical wire contract to get_form_screenshot:
    * SUCCESS -> content[0] = {"type":"resource","resource":{"mimeType":"image/png",
                 "blob":<base64>}}; r.is_error == False, r.text == "".
    * FAILURE -> ToolResult.error(...).toJson() -> isError:true; r.error_text() carries
                 the "error" string -> consumable by assert_error / assert_error_quality.

RENDER (no JVM flag — unlike forms)
  A SpreadsheetDocument is rendered off-screen on the UI thread inside an
  executeAndRollback BM sandbox (the print pipeline lazily mutates the doc while
  painting; the sandbox discards those edits). There is NO -DnativeFormBufferedLayoutRender
  dependency, so a healthy stand renders a real PNG. The happy path still tolerates the
  documented clean sentinels (e.g. the editor not finishing init in a cold/headless run)
  rather than asserting a specific image — never a crash, never a no-image-no-error.

  Read-only w.r.t. model + disk (opens an editor and reads pixels; the render's transient
  model writes are rolled back), so every test ends with assert_no_diff().

FIXTURE TRUTH (TestConfiguration, English Names)
  CommonTemplate "PrintForm" exists on disk at
  src/CommonTemplates/PrintForm/PrintForm.mdo (+ Template.mxlx, a SpreadsheetDocument
  with the cell text "E2E Print Template"), registered in Configuration.mdo. So the FQN
  "CommonTemplate.PrintForm" resolves to a real, renderable common template.
  Catalog "Catalog" exists but is the WRONG KIND for a template FQN.

KNOWN UNTESTED BRANCHES (live-only; no fixture exercises them)
  - Non-SpreadsheetDocument template rejection ("is not a SpreadsheetDocument template"):
    would need a 2nd common template of a different TemplateType.
  - Multi-page vertical stitching (combinePagesVertically): the fixture is one small page;
    a tall multi-page template would be needed. The single-page path IS covered above.
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# The PNG signature, base64-encoded, always begins with "iVBOR".
_PNG_B64_PREFIX = "iVBOR"

# Documented CLEAN sentinels for a render-unavailable outcome (cold editor, headless
# control that never realizes). EXPECTED, not failures — the happy path accepts them as
# the valid alternative to a real image.
_RENDER_UNAVAILABLE_SENTINELS = (
    "did not finish initializing",
    "Template image is not available",
    "has no content to render",
)


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


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (render-dependent — assert the REAL observed contract)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_template_screenshot", kind="read")
def test_capture_commontemplate_real_image_or_clean_render_sentinel():
    """Open + capture the real fixture CommonTemplate.PrintForm. BOTH are a VALID,
    healthy contract:

      (a) a genuine PNG blob (base64 starting with the PNG signature), is_error False; OR
      (b) a CLEAN, documented render-unavailable sentinel on the error channel.

    NOT acceptable (and what this FAILS on): a crash/stacktrace, an opaque error that
    carries no documented sentinel, OR a success channel with no real PNG blob.
    """
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": "CommonTemplate.PrintForm",
    })

    if r.is_error:
        # A render-unavailable outcome MUST be one of the clean, documented sentinels.
        # Any resolution failure here (e.g. "Template file not found", "Cannot resolve")
        # would mean the tool mis-resolved a template that genuinely exists -> a real bug.
        err = r.error_text()
        assert any(s in err for s in _RENDER_UNAVAILABLE_SENTINELS), (
            "for an existing CommonTemplate.PrintForm, an error MUST be a documented "
            "render-unavailable sentinel %r, not a resolution failure; got: %r"
            % (list(_RENDER_UNAVAILABLE_SENTINELS), err[:300])
        )
    else:
        blob = _blob(r)
        assert blob, (
            "success channel for get_template_screenshot must carry an image blob at "
            "content[0].resource.blob; got none (raw: %r)" % (str(r.raw)[:300])
        )
        assert blob.startswith(_PNG_B64_PREFIX), (
            "the image blob must be a real PNG (base64 must start with the PNG "
            "signature %r); got prefix %r" % (_PNG_B64_PREFIX, blob[:16])
        )

    assert_no_diff("a template screenshot read must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (each targets a REAL execute()/openTemplateEditor error path)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_template_screenshot", kind="read")
def test_missing_projectname_errors_actionably():
    """templatePath supplied but projectName omitted. execute() short-circuits before
    any Display access -> "projectName is required"."""
    r = call("get_template_screenshot", {
        "templatePath": "CommonTemplate.PrintForm",
    })
    err = assert_error(r, "projectName missing")
    assert_error_quality(
        err,
        names=["projectName"],
        suggests=[],
        ctx="missing projectName names the required param",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_missing_templatepath_errors_actionably():
    """projectName supplied but templatePath omitted -> "templatePath is required"."""
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
    })
    err = assert_error(r, "templatePath missing")
    assert_error_quality(
        err,
        names=["templatePath"],
        suggests=[],
        ctx="missing templatePath names the required param",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid resolvable FQN shape, but the project does not exist ->
    "Project not found: <projectName>"."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_template_screenshot", {
        "projectName": bad,
        "templatePath": "CommonTemplate.PrintForm",
    })
    err = assert_error(r, "non-existent project")
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_projects"],
        ctx="non-existent project names the bad value and points at list_projects",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_owned_template_fqn_rejected_with_guidance():
    """An owned object-template FQN (Catalog.X.Template.Y) is NOT a common template; it
    has no own .mdo and is unsupported here -> "Cannot resolve template path" naming the
    accepted CommonTemplate.<Name> shape."""
    bad_fqn = "Catalog.Catalog.Template.Print"
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": bad_fqn,
    })
    err = assert_error(r, "owned-template FQN is unsupported")
    assert_error_quality(
        err,
        names=[bad_fqn],
        suggests=["CommonTemplate."],
        ctx="unresolvable template FQN names the value and the accepted shape",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_wrong_type_token_rejected_with_guidance():
    """A 2-part FQN whose type token is NOT CommonTemplate (here Catalog.Catalog - a real
    object but the wrong KIND) -> resolveTemplateMdoPath returns null -> the same
    "Cannot resolve template path" actionable error."""
    bad_fqn = "Catalog.Catalog"
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": bad_fqn,
    })
    err = assert_error(r, "wrong type token")
    assert_error_quality(
        err,
        names=[bad_fqn],
        suggests=["CommonTemplate."],
        ctx="wrong-kind FQN names the value and the accepted shape",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_template_screenshot", kind="read")
def test_resolvable_fqn_but_missing_template_file_errors_clearly():
    """The FQN resolves to a valid CommonTemplates path (CommonTemplate.<X> ->
    src/CommonTemplates/<X>/<X>.mdo) but NO such template exists -> distinct
    "Template file not found: <relativePath> in project <projectName>"."""
    bad = "NoSuchTemplate_ZZZ_e2e"
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": "CommonTemplate." + bad,
    })
    err = assert_error(r, "resolvable FQN but the template file does not exist")
    assert_error_quality(
        err,
        names=[bad, "CommonTemplates"],
        suggests=[],
        ctx="missing template file names the resolved path",
    )
    assert_no_diff("an invalid call must not touch the project on disk")

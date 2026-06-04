"""
e2e tests for get_form_screenshot (kind: read).

WHAT THE TOOL DOES
  Renders a managed form's WYSIWYG editor to a PNG and returns it as an IMAGE
  response. With `formPath` it opens+activates the form editor by metadata FQN,
  then captures it; with no `formPath` it captures whatever form editor is active.
  Source: GetFormScreenshotTool.java (capture in utils/EditorScreenshotHelper.java,
  FQN->file mapping in utils/MetadataPathResolver.java).

WIRE SHAPE (why this file reads r.raw, not r.text/r.structured)
  getResponseType() == IMAGE. The MCP layer (McpProtocolHandler) delivers the two
  outcomes on DIFFERENT channels:
    * SUCCESS  -> buildToolCallResourceBlobResponse -> ToolCallResult.resourceBlob:
                  content[0] = {"type":"resource",
                                "resource":{"uri","mimeType":"image/png","blob":<base64>}}.
                  There is NO `text` and NO `structuredContent`, so the harness
                  Result exposes the PNG ONLY at
                  r.raw["result"]["content"][0]["resource"]["blob"].
                  r.is_error == False, r.text == "", r.structured is None.
    * FAILURE  -> any ToolResult.error(...).toJson() ({"success":false,"error":...})
                  is detected by isJsonErrorPayload and diverted to
                  buildToolCallJsonResponse -> isError:true. So r.is_error == True
                  and r.error_text() returns the "error" string -> consumable by
                  assert_error / assert_error_quality.

RENDER DEPENDENCY (the headline trap — a blank/empty result is NOT a bug)
  The primary capture path (getFormImageData) only yields real pixels when EDT is
  launched with the JVM flag  -DnativeFormBufferedLayoutRender=true . Without it
  the offscreen handler is absent, extractFormImageData + the Control.print
  fallback both return null, and the tool returns the CLEAN sentinel error
  "Form image data is not available" (or "WYSIWYG viewer is not available" /
  "...WYSIWYG page is not available"). ensureBufferedNativeRenderMode() is
  best-effort and cannot retrofit the handler at runtime. Therefore the happy
  path asserts the REAL render-dependent contract: EITHER a genuine PNG blob OR
  one of the documented render-unavailable sentinels — never a crash, never an
  opaque/empty response, never a no-image-no-error. It does NOT assert a specific
  image. This is by design, see SKILL "FORM-RENDER tools" guidance.

  Read-only w.r.t. model + disk (it only opens/activates an editor and reads
  pixels), so every test ends with assert_no_diff().

REAL error paths exercised by the negative matrix (source-exact messages):
  execute():
    - formPath set + projectName missing -> "projectName is required when formPath is specified"
  EditorScreenshotHelper.openAndActivateForm():
    - resolveFormFilePath()==null (unresolvable FQN: wrong part count, wrong
      'Forms' keyword, unknown/wrong 2-part type token)
        -> "Cannot resolve form path: <formPath>. Expected format: ..."
    - project missing -> "Project not found: <projectName>"
    - file missing (FQN resolves to a path but no Form.form there)
        -> "Form file not found: <relativePath> in project <projectName>"

FIXTURE TRUTH (TestConfiguration, English Names)
  CommonForm "Form" exists on disk at src/CommonForms/Form/Form.form, so the FQN
  "CommonForm.Form" resolves to a real, existing form file (confirmed by glob).
  Catalog "Catalog" exists but is the WRONG KIND for a 2-part form FQN.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# The PNG signature, base64-encoded, always begins with "iVBOR" (bytes 89 50 4E 47).
# A genuine screenshot blob must start with this; a stub/garbage blob would not.
_PNG_B64_PREFIX = "iVBOR"

# Documented CLEAN sentinels for a render-unavailable outcome (missing JVM flag,
# cold index, hidden control). These are EXPECTED, not failures of the tool — the
# happy path accepts them as the valid alternative to a real image.
_RENDER_UNAVAILABLE_SENTINELS = (
    "Form image data is not available",
    "WYSIWYG viewer is not available",
    "WYSIWYG page is not available",
)


def _blob(result):
    """Extract the IMAGE resource blob from the raw JSON-RPC response, or None.

    The harness Result has no first-class accessor for a resourceBlob (it surfaces
    only .text/.structured), so we read the documented wire location directly from
    the public .raw attribute. Returns the base64 string, or None if absent."""
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
@e2e_test(tool="get_form_screenshot", kind="read")
def test_capture_commonform_real_image_or_clean_render_sentinel():
    """Open + capture the real fixture CommonForm.Form. The outcome is
    render-dependent (see module docstring: the JVM flag governs whether real
    pixels are produced), so BOTH of these are a VALID, healthy contract:

      (a) JVM flag present  -> a genuine PNG blob (base64 starting with the PNG
          signature) on the success channel, is_error == False; OR
      (b) JVM flag absent / cold render -> a CLEAN, documented render-unavailable
          sentinel error on the error channel, is_error == True.

    What is NOT acceptable (and what this test FAILS on, proving it is not a
    rubber-stamp): a crash/stacktrace, an opaque error that neither carries a
    documented sentinel nor is otherwise actionable, OR a success channel with no
    real PNG blob (a 'succeeded but produced nothing' regression).

    DOCUMENTED DEPENDENCY: -DnativeFormBufferedLayoutRender=true. A blank result
    here is a deployment/JVM-flag condition, not a code bug.
    """
    r = call("get_form_screenshot", {
        "projectName": PROJECT,
        "formPath": "CommonForm.Form",
    })

    if r.is_error:
        # Branch (b): a render-unavailable outcome MUST be one of the clean,
        # documented sentinels. Any other error here (e.g. "Project not found",
        # "Cannot resolve form path", "Form file not found") would mean the tool
        # mis-resolved a form that genuinely exists on disk -> a real bug.
        err = r.error_text()
        assert any(s in err for s in _RENDER_UNAVAILABLE_SENTINELS), (
            "for an existing CommonForm.Form, an error MUST be a documented "
            "render-unavailable sentinel %r, not a resolution failure; got: %r"
            % (list(_RENDER_UNAVAILABLE_SENTINELS), err[:300])
        )
    else:
        # Branch (a): success MUST carry a real PNG blob on the resource channel.
        # A success with no blob = the IMAGE contract is broken.
        blob = _blob(r)
        assert blob, (
            "success channel for get_form_screenshot must carry an image blob at "
            "content[0].resource.blob; got none (raw: %r)" % (str(r.raw)[:300])
        )
        assert blob.startswith(_PNG_B64_PREFIX), (
            "the image blob must be a real PNG (base64 must start with the PNG "
            "signature %r); got prefix %r" % (_PNG_B64_PREFIX, blob[:16])
        )

    assert_no_diff("a screenshot read must not touch the project on disk")


@e2e_test(tool="get_form_screenshot", kind="read")
def test_no_formpath_captures_active_or_clean_no_active_sentinel():
    """Boundary: omit formPath entirely. This is NOT an error condition — it is
    the 'capture the currently active form editor' mode. With nothing form-focused
    (the normal headless-CI state) the tool returns the CLEAN, actionable sentinel
    'No active form editor page found. Specify formPath ...'. If a form editor
    happens to be active it returns a real PNG blob. Either is a healthy contract;
    a crash or an opaque error is not.

    This documents WHY 'missing formPath' is not in the error matrix: the param is
    optional by design, and its absence selects a different valid mode.
    """
    r = call("get_form_screenshot", {})

    if r.is_error:
        err = r.error_text()
        # The active-editor-absent sentinel names the situation AND the fix
        # (specify formPath) -> genuinely actionable. A render sentinel is also
        # acceptable if an editor was active but its image was unavailable.
        actionable = (
            "No active form editor page found" in err
            or any(s in err for s in _RENDER_UNAVAILABLE_SENTINELS)
        )
        assert actionable, (
            "no-formPath with no active form editor must return the actionable "
            "'No active form editor page found. Specify formPath ...' sentinel "
            "(or a render sentinel); got: %r" % (err[:300])
        )
        # Assert the actionable next step is present when it is the no-active case.
        if "No active form editor page found" in err:
            assert "formPath" in err, (
                "the no-active sentinel must point at the formPath fix; got: %r"
                % (err[:300])
            )
    else:
        blob = _blob(r)
        assert blob and blob.startswith(_PNG_B64_PREFIX), (
            "if an active form editor was captured, the success channel must "
            "carry a real PNG blob; got: %r" % (str(r.raw)[:300])
        )

    assert_no_diff("a screenshot read must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (each targets a REAL execute()/openAndActivateForm error path)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_form_screenshot", kind="read")
def test_formpath_without_projectname_errors_actionably():
    """Conditional-required violation: formPath supplied but projectName omitted.
    execute() short-circuits before any Display access ->
    "projectName is required when formPath is specified".

    This IS an actionable error: it names the missing param (projectName) AND the
    condition that requires it (formPath) — a self-contained fix instruction.
    """
    r = call("get_form_screenshot", {
        "formPath": "CommonForm.Form",
    })
    err = assert_error(r, "formPath set but projectName missing")
    assert_error_quality(
        err,
        names=["projectName"],
        suggests=["formPath"],
        ctx="conditional-required projectName names the param and the trigger",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_screenshot", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid resolvable FQN shape, but the project does not exist ->
    "Project not found: <projectName>" (openAndActivateForm)."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_form_screenshot", {
        "projectName": bad,
        "formPath": "CommonForm.Form",
    })
    err = assert_error(r, "non-existent project")
    # "Project not found: <name>. Use list_projects to see available projects."
    # (via the shared ProjectContext.notFoundMessage) names the bad value AND
    # points at list_projects as the discovery next step -> genuinely actionable.
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_projects"],
        ctx="non-existent project names the bad value and points at list_projects",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_screenshot", kind="read")
def test_resolvable_fqn_but_missing_form_file_errors_clearly():
    """The FQN resolves to a valid CommonForms path (CommonForm.<X> ->
    src/CommonForms/<X>/Form.form) but NO such form exists -> distinct
    "Form file not found: <relativePath> in project <projectName>" path.
    Discriminates a missing FILE from an unresolvable FQN (next test)."""
    bad_form = "NoSuchForm_ZZZ_e2e"
    r = call("get_form_screenshot", {
        "projectName": PROJECT,
        "formPath": "CommonForm." + bad_form,
    })
    err = assert_error(r, "resolvable FQN but the form file does not exist")
    # The message embeds the resolved relative path, which contains the bad form
    # name; assert both the bad name and the path fragment are present so we know
    # the tool walked the resolve->exists() branch (not a generic resolve fail).
    # AUDIT: names the resolved path but suggests no discovery tool (e.g.
    # get_metadata_objects / list of forms). suggests=[] intentionally — fix-card.
    assert_error_quality(
        err,
        names=[bad_form, "CommonForms"],
        suggests=[],
        ctx="missing form file names the resolved path",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_screenshot", kind="read")
def test_malformed_fqn_wrong_part_count_errors_actionably():
    """A 3-part FQN is neither the 2-part CommonForm shape nor the 4-part object
    shape -> resolveFormFilePath returns null -> "Cannot resolve form path: ...
    Expected format: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'."
    This error IS actionable: it states the two accepted formats verbatim."""
    bad_fqn = "Catalog.Catalog.ItemForm"  # missing the 'Forms' segment -> 3 parts
    r = call("get_form_screenshot", {
        "projectName": PROJECT,
        "formPath": bad_fqn,
    })
    err = assert_error(r, "malformed FQN (wrong part count)")
    # Names the bad value AND points at the correct shape ('CommonForm.' is part
    # of the documented expected format) -> genuinely actionable.
    assert_error_quality(
        err,
        names=[bad_fqn],
        suggests=["CommonForm."],
        ctx="unresolvable FQN names the value and the accepted formats",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_screenshot", kind="read")
def test_wrong_type_token_two_part_fqn_errors_actionably():
    """A 2-part FQN whose type token is NOT CommonForm (here Catalog.Catalog — a
    real object but the wrong KIND): resolveMetadataDir != 'CommonForms' ->
    resolveFormFilePath returns null -> the same "Cannot resolve form path"
    actionable error. Confirms the tool does not silently accept a catalog FQN as
    a form."""
    bad_fqn = "Catalog.Catalog"
    r = call("get_form_screenshot", {
        "projectName": PROJECT,
        "formPath": bad_fqn,
    })
    err = assert_error(r, "wrong type token in 2-part FQN")
    assert_error_quality(
        err,
        names=[bad_fqn],
        suggests=["CommonForm."],
        ctx="wrong-kind 2-part FQN names the value and the accepted formats",
    )
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_screenshot", kind="read")
def test_bad_forms_keyword_four_part_fqn_errors_actionably():
    """A 4-part FQN whose 3rd segment is not the (case-insensitive) 'Forms'
    keyword -> resolveFormFilePath returns null -> "Cannot resolve form path".
    Exercises the 4-part branch's keyword guard specifically (distinct from the
    part-count and type-token branches above)."""
    bad_fqn = "Catalog.Catalog.Form.ItemForm"  # 'Form' (singular) != 'Forms'
    r = call("get_form_screenshot", {
        "projectName": PROJECT,
        "formPath": bad_fqn,
    })
    err = assert_error(r, "4-part FQN with wrong 'Forms' keyword")
    assert_error_quality(
        err,
        names=[bad_fqn],
        suggests=["CommonForm."],
        ctx="bad Forms keyword names the value and the accepted formats",
    )
    assert_no_diff("an invalid call must not touch the project on disk")

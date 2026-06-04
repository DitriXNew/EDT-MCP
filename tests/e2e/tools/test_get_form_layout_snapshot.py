"""
e2e tests for get_form_layout_snapshot (kind: read).

WHAT THE TOOL DOES
------------------
Returns a YAML snapshot of a 1C form's calculated WYSIWYG layout (element
bounds, types, display-affecting properties). It can open + activate a form by
its metadata FQN (formPath), or snapshot the currently active form editor.

CRITICAL CONTRACT FACTS (verified against the Java, not assumed)
----------------------------------------------------------------
1. ResponseType is **TEXT** (GetFormLayoutSnapshotTool.getResponseType()), and the
   body is a YAML document, NOT JSON and NOT structuredContent. So the payload is
   in ``r.text`` (``r.structured`` is None).

2. **Failures do NOT set isError.** Every error path in the tool returns
   ``FormLayoutSnapshotService.errorYaml(msg)`` -> a YAML document
   ``"success: false\nerror: <msg>\n"``. The protocol handler only flips isError
   when ``isJsonErrorPayload(result)`` is true, and that helper does
   ``JsonParser.parseString(result)`` — a *block* YAML map is NOT valid JSON, so it
   throws, isJsonErrorPayload returns false, and the response is delivered as a
   plain TEXT result with **isError:false** (McpProtocolHandler, case TEXT).
   => ``r.is_error`` is ALWAYS False for this tool, even on a validation error.
   => We therefore CANNOT use harness.assert_error()/assert_error (which key off
      is_error); a negative is detected by ``success: false`` + the ``error:`` line
      in ``r.text``. The bad value is still named there, so we still assert error
      QUALITY via the harness assert_error_quality() on the extracted message.
   # AUDIT (major): this tool reports ALL failures with isError:false. A
   #   schema-driven MCP client cannot machine-detect that a snapshot call failed
   #   (bad formPath, missing project, render unavailable all look "ok" at the
   #   protocol layer). Fix-card: route errorYaml failures through ToolResult.error
   #   (or declare ResponseType.YAML and emit a JSON-detectable error payload) so
   #   the wire isError flag reflects the failure.

3. **Render-flag dependency (documented, not a bug).** A populated snapshot needs
   EDT launched with -DnativeFormBufferedLayoutRender=true. Without it (or while the
   form is still rendering) the tool returns a *render-availability* sentinel such
   as "WYSIWYG ... is not available" / "WYSIWYG page is not available ... try again".
   A blank/empty snapshot is NOT a bad call. The happy-path test below therefore
   accepts EITHER a real snapshot (success:true + the snapshot scaffold keyed to our
   call) OR a known render-availability sentinel — but it still FAILS if a VALID call
   produces an *argument* failure (Cannot resolve form path / Form file not found /
   Project not found), because that would mean the tool mishandled good input.

Fixture truth (TestConfiguration, English Names):
  - CommonForm "Form" on disk at src/CommonForms/Form/Form.form  -> FQN "CommonForm.Form"
    (MetadataPathResolver.resolveFormFilePath maps "CommonForm.X" -> src/CommonForms/X/Form.form).
  - Catalog "Catalog" exists but has NO form -> any "Catalog.Catalog.Forms.X" form file
    is absent (a real "Form file not found" case).

Read tool -> every test ends with assert_no_diff(): opening/snapshotting a form must
never mutate the project tree on disk.
"""

from harness import (
    call,
    assert_ok,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Local helpers (this tool's YAML-body error contract; see module docstring fact 2).
# These do NOT re-implement harness logic — they only adapt the YAML body so the
# shared assert_error_quality() can judge the message.
# ──────────────────────────────────────────────────────────────────────────────

# Error-availability sentinels that mean "the form was resolved & opened, but the
# WYSIWYG layout is not rendered" (render-flag dependency / still loading). These are
# the ONLY acceptable failures for an otherwise-valid call.
_RENDER_UNAVAILABLE_SENTINELS = (
    "WYSIWYG page is not available",          # editorPage resolved, page not ready
    "WYSIWYG viewer is not available",
    "WYSIWYG representation is not available",
    "WYSIWYG layout model is not available",
    "No active form editor page found",       # active-editor mode, nothing open
)

# Argument-level failures: if a VALID call hits one of these, the tool mishandled
# good input -> that IS a bug and the happy path must fail.
_ARG_FAILURE_MARKERS = (
    "Cannot resolve form path",
    "Form file not found",
    "Project not found",
    "projectName is required",
    "Invalid mode",
)


def _yaml_error_message(text):
    """Extract the value of the YAML 'error:' key from an errorYaml() body.

    The body is produced by snakeyaml block dump, e.g.:
        success: false
        error: 'Cannot resolve form path: Bad. Expected ...'
    Returns the message (quotes stripped) or "" if there is no error line."""
    for line in (text or "").splitlines():
        stripped = line.strip()
        if stripped.startswith("error:"):
            msg = stripped[len("error:"):].strip()
            if len(msg) >= 2 and msg[0] in "'\"" and msg[-1] == msg[0]:
                msg = msg[1:-1]
            # snakeyaml escapes a single quote inside a single-quoted scalar as ''.
            return msg.replace("''", "'")
    return ""


def _assert_is_failure(r, ctx):
    """This tool can't set isError (fact 2). A failure is 'success: false' in the
    YAML body. Assert that and return the extracted error message for quality checks."""
    # success:true must be ABSENT and success:false present -> unambiguous failure.
    assert "success: false" in (r.text or ""), \
        "[%s] expected a 'success: false' failure body, got:\n%s" % (ctx, (r.text or "")[:400])
    assert "success: true" not in (r.text or ""), \
        "[%s] failure body must not also claim success: true:\n%s" % (ctx, (r.text or "")[:400])
    msg = _yaml_error_message(r.text)
    assert msg, "[%s] failure body must carry a non-empty 'error:' message:\n%s" \
        % (ctx, (r.text or "")[:400])
    return msg


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_snapshot_common_form_returns_render_dependent_yaml():
    """Address the real fixture CommonForm by FQN. Because the result is
    render-flag dependent (module docstring fact 3) we accept EITHER a real
    snapshot OR a render-availability sentinel — but a VALID call must NEVER yield
    an argument-level failure (that would mean the form/project was mishandled).

    Mutation sensitivity: if the tool were broken so that a good FQN produced
    "Form file not found"/"Cannot resolve form path"/"Project not found", this test
    fails. If it produced a populated snapshot, we assert the snapshot scaffold is
    really keyed to OUR call (formPath echoed, mode echoed) — not a canned blob."""
    r = call("get_form_layout_snapshot", {
        "projectName": PROJECT,
        "formPath": "CommonForm.Form",
    })
    # TEXT/YAML tool: the protocol layer reports success (isError stays false even on
    # the tool's own errors, fact 2). assert_ok only guards against a *protocol* error
    # (e.g. an exception escaping execute), which must not happen for a valid call.
    assert_ok(r, "snapshot CommonForm.Form")
    assert r.structured is None, \
        "TEXT tool must not populate structuredContent; got: %r" % (r.structured,)

    body = r.text or ""
    # A VALID call must never be rejected as an argument error.
    for marker in _ARG_FAILURE_MARKERS:
        assert marker not in body, (
            "a valid CommonForm.Form call must NOT fail with %r (the form exists at "
            "src/CommonForms/Form/Form.form); body:\n%s" % (marker, body[:500])
        )

    if "success: true" in body:
        # Real snapshot path: assert the scaffold is keyed to our request, proving the
        # tool actually built the YAML for THIS form (not an echo / canned response).
        assert_contains(body, "formPath: CommonForm.Form",
                        "snapshot must echo the requested formPath")
        assert_contains(body, "mode: compact",
                        "default mode must be 'compact' and echoed in the snapshot")
        # Structural keys only the real builder emits (FormLayoutSnapshotService).
        assert_contains(body, "elementCount:",
                        "a real snapshot carries an elementCount")
        assert_contains(body, "boundsCoordinateSpace: form WYSIWYG pixels",
                        "a real snapshot states its bounds coordinate space")
    else:
        # Render-unavailable path (flag missing / still rendering): DOCUMENTED, not a
        # bug. Must be one of the known render sentinels, not a random failure.
        msg = _assert_is_failure(r, "render-unavailable sentinel")
        assert any(s in msg for s in _RENDER_UNAVAILABLE_SENTINELS), (
            "render-unavailable result must be a known WYSIWYG sentinel "
            "(missing -DnativeFormBufferedLayoutRender flag?), got: %r" % (msg,)
        )

    assert_no_diff("snapshotting a form is read-only; nothing may change on disk")


@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_full_mode_is_honoured_and_echoed():
    """mode='full' is a real branch (FormLayoutSnapshotService MODE_FULL): on a
    rendered form it echoes 'mode: full' and emits all layout nodes. On a
    render-unavailable environment it falls back to the same WYSIWYG sentinel.
    Either way the call is valid -> no argument-level failure, no disk change. The
    discriminator: if a snapshot is produced, it must echo 'mode: full' (NOT
    'compact'), proving the enum value was read and honoured rather than dropped."""
    r = call("get_form_layout_snapshot", {
        "projectName": PROJECT,
        "formPath": "CommonForm.Form",
        "mode": "full",
    })
    assert_ok(r, "snapshot CommonForm.Form mode=full")
    body = r.text or ""
    for marker in _ARG_FAILURE_MARKERS:
        assert marker not in body, \
            "valid full-mode call must not fail with %r; body:\n%s" % (marker, body[:500])

    if "success: true" in body:
        assert_contains(body, "mode: full",
                        "full mode must round-trip into the snapshot, not fall back to compact")
        assert "mode: compact" not in body, \
            "full mode must NOT be silently downgraded to compact:\n%s" % body[:400]
    else:
        msg = _assert_is_failure(r, "full-mode render-unavailable")
        assert any(s in msg for s in _RENDER_UNAVAILABLE_SENTINELS), \
            "full-mode render-unavailable must be a known WYSIWYG sentinel, got: %r" % (msg,)

    assert_no_diff("snapshotting a form is read-only; nothing may change on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# Note (fact 2): these failures come back with isError:false; we detect them via the
# 'success: false' YAML body and judge the message with assert_error_quality().
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_invalid_mode_enum_errors_actionably():
    """mode is an enum {compact, full}; normalizeMode returns null for anything
    else -> execute() returns errorYaml("Invalid mode: <v>. Expected 'compact' or
    'full'."). This is the tool's BEST error: it names the bad value AND lists the
    valid alternatives. (No projectName/formPath needed: the mode check runs first.)"""
    bad = "wide_e2e"
    r = call("get_form_layout_snapshot", {
        "projectName": PROJECT,
        "formPath": "CommonForm.Form",
        "mode": bad,
    })
    msg = _assert_is_failure(r, "invalid mode enum")
    # Names the bad value and points at the valid alternatives ('compact'/'full').
    assert_error_quality(msg, names=[bad], suggests=["compact", "full"],
                         ctx="invalid mode names value and lists valid ones")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_formpath_without_projectname_errors_clearly():
    """Conditional-required: formPath is specified but projectName is missing ->
    execute() returns errorYaml("projectName is required when formPath is specified").
    Without projectName the tool cannot resolve which project owns the form."""
    r = call("get_form_layout_snapshot", {
        "formPath": "CommonForm.Form",
    })
    msg = _assert_is_failure(r, "formPath without projectName")
    # The message names the missing parameter AND states the condition that requires
    # it ("when formPath is specified") -> genuinely actionable for this branch.
    assert_error_quality(msg, names=["projectName"], suggests=["formPath"],
                         ctx="formPath-without-projectName names the missing param + condition")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Well-formed args but the project does not exist. openAndActivateForm ->
    ProjectContext.notFoundMessage(projectName) (JSON), unwrapped by
    extractToolErrorMessage and re-wrapped as errorYaml. The migrated message names
    the bad project AND points the caller at list_projects to discover a valid one:
    "Project not found: <name>. Use list_projects to see available projects."."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_form_layout_snapshot", {
        "projectName": bad,
        "formPath": "CommonForm.Form",
    })
    msg = _assert_is_failure(r, "non-existent project")
    # The migrated not-found message names the bad value AND offers the next step
    # (the list_projects discovery tail) -> genuinely actionable.
    assert_error_quality(msg, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_nonexistent_form_file_errors_and_names_value():
    """The FQN is well-formed and the project exists, but no such form file is on
    disk: Catalog "Catalog" exists yet has no form, so resolveFormFilePath yields
    src/Catalogs/Catalog/Forms/NoForm/Form.form which does not exist ->
    "Form file not found: <relativePath> in project <project>"."""
    bad_form = "Catalog.Catalog.Forms.NoSuchForm_e2e"
    r = call("get_form_layout_snapshot", {
        "projectName": PROJECT,
        "formPath": bad_form,
    })
    msg = _assert_is_failure(r, "non-existent form file")
    # The resolved relative path is named in the message; assert on the discriminating
    # parts (object + form name) that prove the right path was computed and reported.
    # AUDIT: the message names the missing file but gives no recovery hint (e.g.
    # get_metadata_objects/get_form_layout_snapshot with a valid CommonForm.Form, or
    # list the object's forms). suggests=[] -> fix-card.
    assert_error_quality(msg, names=["Form file not found", "NoSuchForm_e2e"], suggests=[],
                         ctx="non-existent form file names the missing path")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_malformed_formpath_errors_with_format_hint():
    """A malformed FQN that resolveFormFilePath cannot parse: a single-segment path
    has neither the 2-part 'CommonForm.X' nor the 4-part 'Type.Obj.Forms.Name' shape
    -> resolveFormFilePath returns null -> "Cannot resolve form path: <v>. Expected
    format: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'."
    This is the tool's most actionable error: it names the bad value AND the formats."""
    bad_form = "GarbageFormPath_e2e"
    r = call("get_form_layout_snapshot", {
        "projectName": PROJECT,
        "formPath": bad_form,
    })
    msg = _assert_is_failure(r, "malformed formPath")
    # Names the bad value and points at the two accepted FQN shapes -> actionable.
    assert_error_quality(msg, names=[bad_form], suggests=["CommonForm.FormName"],
                         ctx="malformed formPath names value and shows the expected format")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="get_form_layout_snapshot", kind="read")
def test_wrong_type_token_in_formpath_errors_clearly():
    """A 2-part FQN whose type token is NOT a common form: "Catalog.Catalog" parses
    as 2 parts but resolveMetadataDir("Catalog")="Catalogs" != "CommonForms" ->
    resolveFormFilePath returns null -> the same "Cannot resolve form path" error.
    Confirms the tool does not silently treat a catalog FQN as a form path."""
    bad_form = "Catalog.Catalog"
    r = call("get_form_layout_snapshot", {
        "projectName": PROJECT,
        "formPath": bad_form,
    })
    msg = _assert_is_failure(r, "wrong type token (2-part non-CommonForm)")
    # AUDIT: a 2-part "Catalog.Catalog" is reported with the generic "Cannot resolve
    # form path" message; it does not explain that a 2-part FQN must use the
    # 'CommonForm.' token (vs the 4-part 'Catalog.Catalog.Forms.Name' shape for object
    # forms). It does name the bad value and lists the formats, so it is partially
    # actionable; the format hint is the recoverable next step.
    assert_error_quality(msg, names=[bad_form], suggests=["CommonForm.FormName"],
                         ctx="wrong-type-token formPath names value and shows the expected format")
    assert_no_diff("a rejected call must not touch the project on disk")

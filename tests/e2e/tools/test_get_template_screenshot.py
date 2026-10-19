"""
e2e tests for get_template_screenshot (kind: read; one write-metadata test for the
render teardown).

WHAT THE TOOL DOES
  Renders a 1C template (макет) - a SpreadsheetDocument print form - to a PNG and
  returns it as an IMAGE response. Resolves the template object by FQN
  (MetadataNodeResolver), reads its content SpreadsheetDocument from the BM model, and
  rasterizes it off-screen via a standalone moxel control - NO editor is opened - so it
  works for BOTH a common template (CommonTemplate.<Name>) AND an object-owned template
  (<Type>.<Owner>.Template.<Name>).
  Source: GetTemplateScreenshotTool.java + utils/TemplateScreenshotHelper.java.

WIRE SHAPE (why this file reads r.raw, not r.text/r.structured)
  getResponseType() == IMAGE - identical wire contract to get_form_screenshot:
    * SUCCESS -> content[0] = {"type":"resource","resource":{"mimeType":"image/png",
                 "blob":<base64>}}; r.is_error == False, r.text == "".
    * FAILURE -> ToolResult.error(...).toJson() -> isError:true; r.error_text() carries
                 the "error" string -> consumable by assert_error / assert_error_quality.

RENDER (no JVM flag - unlike forms; no editor)
  The whole used cell range is painted as ONE continuous off-screen image (the editor
  canvas, not print pages) via a standalone MoxelControl.paintViewPort built directly from
  the document, on the UI thread inside an executeAndRollback BM sandbox (painting lazily
  touches the model; the sandbox discards those edits). There is NO
  -DnativeFormBufferedLayoutRender dependency and no editor is opened, so the render does
  not depend on the editor's page structure (which varies across EDT builds / headless
  runs). The happy paths therefore assert STRICTLY: a real, non-empty PNG whose IHDR width
  AND height are > 0; an error here is a real bug.

  Read-only w.r.t. model + disk (the render's transient model writes are rolled back),
  so every read test ends with assert_no_diff().

RENDER TEARDOWN (issue #770; the one write-metadata test)
  MoxelControl.setDocument registers project / editing-language / style listeners that only
  MoxelControl.dispose() removes, and Shell.dispose() never calls that override. A Shell-only
  teardown therefore leaves a listener bound to the rolled-back render document; once the
  project context restarts, the next language add/remove makes it read an inactive BM
  namespace and EDT logs BmNamespaceInactiveException at ERROR under com._1c.g5.v8.dt.core -
  invisible to every tool response and to the log ratchet (which reads only OUR plugin's
  entries). The contract is observable only in the EDT log, so that test reads it: screenshot
  -> clean_project -> add and remove a Language -> zero such entries. It is e2e-only: the
  control needs a live Display, so no unit test can build it.

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
"""

import base64
import glob
import os
import struct
import time

from harness import (
    call,
    calls_aborted,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    wait_for_project_ready,
    E2ESkip,
    _fail,
    _workspace_dir,
    PROJECT,
)
from tools.test_edt_log_ratchet import _ENTRY, _emit_log_probe, _entry_epoch


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


def _assert_nonempty_png(r, fqn):
    """Shared happy-path contract: rendering an existing, content-bearing template MUST succeed and
    return a genuine, NON-EMPTY PNG (valid signature + IHDR width AND height > 0). The render is
    editor-free (it builds the moxel control directly from the document), so there is no cold-editor
    flake to tolerate - any error here is a real bug."""
    assert not r.is_error, (
        "rendering the existing, content-bearing %r must succeed (the render is editor-free); "
        "got error: %r" % (fqn, r.error_text()[:300])
    )
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
    non-empty PNG is produced (the core contract). See _assert_nonempty_png."""
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": "CommonTemplate.PrintForm",
    })
    _assert_nonempty_png(r, "CommonTemplate.PrintForm")
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
    _assert_nonempty_png(r, "Catalog.Catalog.Template.Invoice")
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


# ──────────────────────────────────────────────────────────────────────────────
# RENDER TEARDOWN (issue #770) - judged from the EDT log, the only place it shows
# ──────────────────────────────────────────────────────────────────────────────
_LEAKED_LISTENER_FRAME = "MoxelControl.lambda$"
_INACTIVE_NAMESPACE = "BmNamespaceInactiveException"
# The stale listener runs on EDT's asynchronous project-event delivery, so its ERROR can land a
# little after the language change that triggers it. A clean log is only evidence once this
# window has passed without the entry appearing.
_LEAK_ERROR_WINDOW_SECONDS = 20


def _edt_log_blocks(workspace):
    """Yield (severity, epoch, block text) for every !ENTRY in .metadata/.log and its rotated
    .bak_*.log generations (the log rotates at ~1 MB, so one test can span several files). The
    block runs to the next !ENTRY / !SESSION header, so it carries the entry's !STACK and any
    !SUBENTRY - the leaked listener's frame is in the stack, not in the !MESSAGE line."""
    metadata = os.path.join(workspace, ".metadata")
    files = sorted(glob.glob(os.path.join(metadata, ".bak_*.log"))) + [os.path.join(metadata, ".log")]
    for path in files:
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                lines = handle.readlines()
        except OSError:
            continue
        header = None
        body = []
        for line in lines + ["!SESSION end-of-file\n"]:
            if line.startswith("!ENTRY ") or line.startswith("!SESSION"):
                if header is not None:
                    yield header[0], header[1], "".join(body)
                header = None
                body = []
                match = _ENTRY.match(line)
                if match:
                    _plugin, severity, _code, stamp = match.groups()
                    try:
                        header = (severity, _entry_epoch(stamp))
                    except ValueError:
                        header = None
            if header is not None:
                body.append(line)


def _leaked_listener_errors(workspace, since):
    """ERROR (severity 4) entries stamped at/after `since` whose stack is the leaked moxel
    listener reading an inactive namespace: both the MoxelControl lambda frame AND the
    BmNamespaceInactiveException must be in the same entry."""
    return [text for severity, epoch, text in _edt_log_blocks(workspace)
            if severity == "4" and epoch >= since - 1
            and _LEAKED_LISTENER_FRAME in text and _INACTIVE_NAMESPACE in text]


def _log_carries_probe(workspace, token):
    return any(token in text for _severity, _epoch, text in _edt_log_blocks(workspace))


@e2e_test(tool="get_template_screenshot", kind="write-metadata")
def test_screenshot_leaves_no_moxel_listener_behind_after_a_project_restart():
    """Issue #770: the off-screen MoxelControl must be disposed with its listeners, not only its
    Shell. Observable effect of the defect, reproduced on the stand: one screenshot, then a
    project context restart (clean_project), then any Language add/remove in the configuration
    -> EDT logs `BmNamespaceInactiveException ... at MoxelControl.lambda$...` at ERROR, because
    the leaked IV8ProjectManager listener reads the language settings of the long-rolled-back
    render document. Red on a jar whose teardown is Shell.dispose() alone (the leak); green
    when the control is disposed first."""
    workspace_override = os.environ.get("EDT_MCP_EDT_WORKSPACE")
    workspace = _workspace_dir()
    if workspace is None:
        if workspace_override:
            _fail("EDT_MCP_EDT_WORKSPACE names %s, but there is no .metadata directory there, so "
                  "the leaked-listener check has no EDT log to read." % workspace_override)
        raise E2ESkip("EDT workspace not found: set EDT_MCP_EDT_WORKSPACE to the -data directory "
                      "so the leaked-listener check can read <workspace>/.metadata/.log")
    # Same binding as the log ratchet: a located workspace is only this server's when a
    # run-unique line the server was made to write comes back out of its log.
    token = _emit_log_probe()
    if not _log_carries_probe(workspace, token):
        if workspace_override:
            _fail("EDT_MCP_EDT_WORKSPACE names %s, but the run-unique log probe sent through "
                  "get_project_errors did not come back in .metadata/.log or .bak_*.log, so "
                  "that is not this server's log." % workspace)
        raise E2ESkip("EDT workspace %s does not carry this server's log output (the run-unique "
                      "probe was not found), so its log cannot judge this server." % workspace)

    started = time.time()
    r = call("get_template_screenshot", {
        "projectName": PROJECT,
        "templatePath": "CommonTemplate.PrintForm",
    })
    _assert_nonempty_png(r, "CommonTemplate.PrintForm")

    # Restart the project context: the namespace the render document lived in goes inactive.
    assert_ok(call("clean_project", {"projectName": PROJECT}),
              "clean_project restarts the fixture's project context")
    wait_for_project_ready()

    # A language composition change is what the leaked listener reacts to. Add one and remove it.
    language = "Language.E2E770ScreenshotTeardown"
    created = False
    try:
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": language}),
                  "add a Language after the restart (a language composition change)")
        created = True
        wait_for_project_ready()
        assert_ok(call("delete_metadata", {"projectName": PROJECT, "fqn": language,
                                           "confirm": True}),
                  "remove that Language again (a second composition change)")
        created = False
        wait_for_project_ready()

        deadline = time.time() + _LEAK_ERROR_WINDOW_SECONDS
        while True:
            leaked = _leaked_listener_errors(workspace, started)
            if leaked or time.time() >= deadline:
                break
            time.sleep(1)
        assert not leaked, (
            "get_template_screenshot left a live MoxelControl listener behind: after the project "
            "restart, the language change made EDT log %d ERROR entr%s with %s in %s (the listener "
            "reads the rolled-back render document). TemplateScreenshotHelper.renderSpreadsheet "
            "must dispose the MoxelControl itself - Shell.dispose() never calls its dispose() "
            "override, which is the only place those listeners are removed. First entry:\n%s"
            % (len(leaked), "y" if len(leaked) == 1 else "ies", _INACTIVE_NAMESPACE,
               _LEAKED_LISTENER_FRAME, leaked[0][:1500]))
    finally:
        # A timed-out write may still be executing; the harness stops such a run instead of racing it.
        if created and not calls_aborted():
            assert_ok(call("delete_metadata", {"projectName": PROJECT, "fqn": language,
                                               "confirm": True}),
                      "remove the test-owned Language")
            wait_for_project_ready()

# get_form_screenshot — how to test

**Purpose.** Renders a managed form's WYSIWYG editor to a **PNG image** and returns it as an `IMAGE` response (base64 PNG). It can either capture the currently active form editor, or — when given `formPath` — open + activate the form editor automatically by metadata FQN, then capture it. Source: `GetFormScreenshotTool.java` (capture logic in `utils/EditorScreenshotHelper.java`, FQN→file mapping in `utils/MetadataPathResolver.java`).

**Preconditions.**
- Live (non-elevated) EDT copy, workspace `D:\WS\EDT`, MCP on `:8765`, project `TestConfiguration` open and **`ready`** (check via `list_projects` — after a `-clean` relaunch the Xtext/BSL index rebuilds for a while; a form editor opened before the index settles can render blank or `WYSIWYG page is not available`).
- After a plugin change: `pwsh D:\Soft\edt-redeploy.ps1` (build → kill → swap → `-clean` relaunch). The script may **exit 1** yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code.
- **CRITICAL JVM-flag precondition.** The primary capture path (`EditorScreenshotHelper.extractFormImageData` → `getFormImageData()`) only returns real pixels when EDT runs with the VM option **`-DnativeFormBufferedLayoutRender=true`** in `1cedt.ini` under `-vmargs`. Without it the singleton `HippoLayoutService.INSTANCE` is constructed with no `offscreenHandler`, so the buffered offscreen image is never produced and the result is **BLANK — that is NOT a code bug**. The tool calls `ensureBufferedNativeRenderMode()` (sets the `nativeFormBufferedLayoutRender` system property and reflectively flips `NativeRenderService.NATIVE_FORM_BUFFERED_LAYOUT_RENDER`) as a best-effort, but if buffered render is still off it only logs a warning (`"Restart EDT with VM option: -DnativeFormBufferedLayoutRender=true"`) — it cannot retrofit the offscreen handler at runtime. **Verify the flag is set before trusting a blank/empty image.** Do NOT assume live works without the flag.
- **UI-thread tool.** The whole capture runs in `Display.getDefault().syncExec(...)`; it physically opens (and pre-closes any existing) form editor and activates the main WYSIWYG page. Needs a live, non-headless workbench (`Display` must exist and not be disposed, else `"Display is not available"`). Avoid hammering it concurrently with other UI-thread tools (`get_symbol_info`, `get_content_assist`, `get_form_layout_snapshot`).
- Read-only with respect to the model and disk: it opens/activates an editor and reads pixels; it does **not** mutate metadata or BSL. **No revert needed.** (It may leave the form editor tab open — see Gotchas.)

**Picking a form.** `formPath` is a metadata FQN, NOT a file path. Two shapes are accepted (`MetadataPathResolver.resolveFormFilePath`):
- 4-part object form: `MetadataType.ObjectName.Forms.FormName` → `src/<DirName>/<ObjectName>/Forms/<FormName>/Form.form`.
- 2-part common form: `CommonForm.FormName` → `src/CommonForms/<FormName>/Form.form`.
The `Forms` keyword is case-insensitive; the **type token is bilingual** (e.g. `Catalog`/`Справочник`, resolved via `MetadataTypeUtils.getDirectoryName`), but the **object NAME and form NAME are programmatic** (never the synonym). For TestConfiguration use the catalog form that exists on disk: `Catalog.Catalog.Forms.ItemForm` (resolves to `src/Catalogs/Catalog/Forms/ItemForm/Form.form`).

**Call (real or documented).** Open + capture a known TestConfiguration form:
```
get_form_screenshot(
  projectName="TestConfiguration",
  formPath="Catalog.Catalog.Forms.ItemForm",
  refresh=false)
```
Capture whatever form editor is already focused (no auto-open):
```
get_form_screenshot()          # no formPath → captures the active form editor
```
Notes on params (all three are declared in `getInputSchema()` and read in `execute()`):
- `projectName` — EDT project name. **Required when `formPath` is specified** (else `"projectName is required when formPath is specified"`). Ignored when capturing the active editor.
- `formPath` — the FQN above. Omit to capture the active form editor.
- `refresh` — boolean, default `false`. When `true`, forces a WYSIWYG `refresh()` before capture (`EditorScreenshotHelper.refreshViewer`). Use it if a stale render is suspected.

This tool only opens/reads, so there is no setup/verify/revert cycle. If you ever need to confirm the form actually opened, cross-check the EDT log `D:\WS\EDT\.metadata\.log` (the open/activate path logs warnings/errors) — there is no read tool that reports "which editor is active". `get_form_layout_snapshot` is the structural sibling for the same form if you want non-pixel verification.

**Result.** `ResponseType.IMAGE`. On success `execute()` returns the **bare base64 PNG string** (no JSON envelope); the MCP layer delivers it as an image resource and `getResultFileName()` names it after the last FQN segment — e.g. `ItemForm.png` for the call above (or `form.png` when `formPath` is empty). Representative shape from source (the success branch returns `result.getBase64Data()` directly):
```
iVBORw0KGgoAAAANSUhEUgAAA... (base64 PNG bytes, decode to a PNG of the rendered form)
```
On failure `execute()` returns the error JSON produced by `ToolResult.error(...)` (carried via `CaptureResult.error`). Representative error shape from source:
```json
{"success":false,"error":"projectName is required when formPath is specified"}
```
Other source-exact error messages (each wrapped as the same `{success:false,error:…}` JSON, `isError:true`):
- `Display is not available` — no live/non-headless workbench.
- `Cannot resolve form path: <formPath>. Expected format: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'.` — bad/unknown FQN or wrong part count.
- `Project not found: <projectName>` / `Form file not found: <relativePath> in project <projectName>`.
- `No active workbench page` / `Could not open form editor for: <formPath>` / `Failed to open form editor: <msg>`.
- `Form editor opened but WYSIWYG page is not available. The form may still be loading.` — editor up but `wysiwygViewer` not ready (index/load race; retry once when `ready`).
- `No active form editor page found. Specify formPath parameter to open a form automatically.` — no-`formPath` call with nothing focused.
- `WYSIWYG viewer is not available` / `Form image data is not available` — viewer found but image extraction (primary `getFormImageData()` and the `Control.print()` fallback) both yielded `null`/zero-size. **This is the symptom of a missing JVM flag** (offscreen handler absent → no buffered image), not necessarily a code defect.
- `Failed to capture form screenshot: <msg>` — caught exception (also logged via `Activator.logError`).

**Gotchas.**
- **The JVM flag is the headline trap.** A blank PNG or `Form image data is not available` almost always means EDT was launched **without** `-DnativeFormBufferedLayoutRender=true`. The tool's `ensureBufferedNativeRenderMode()` is best-effort and cannot install the offscreen handler post-launch; it only logs the restart warning. Before filing this as a bug: grep `1cedt.ini` (the running EDT copy's) for the flag, and check `D:\WS\EDT\.metadata\.log` for `"Buffered native render is still disabled"`. A blank result is NOT a code bug.
- **Native vs. buffered render are different switches.** `NativeRenderService.isNativeRender()` (the default native C++ visualizer) governs whether the form is rendered natively at all; **buffered** (`isBufferedRender()` / `NATIVE_FORM_BUFFERED_LAYOUT_RENDER`) is what produces the offscreen image this tool reads. In native render mode the C++ side returns only form-level metrics and per-element Java projections are not populated — relevant to `get_form_layout_snapshot`, but it explains why the screenshot leans on the buffered offscreen image rather than per-control SWT widgets. Do not confuse Configuration `compatibilityMode` (e.g. `8.5.1`) with the form `ClientInterfaceVariant` (TAXI vs VERSION8_5).
- **Two capture methods, in order.** Primary: `extractFormImageData` triggers a `rebuild(true)` on `wysiwygRepresentation`, pumps the event loop, then calls `getFormImageData()`. Fallback (only if primary is `null`): `captureControlImageData` paints the SWT control via `Control.print(gc)` onto a white-filled `Image`. The fallback needs a control with positive bounds; a hidden/zero-size or disposed control yields `null` → `Form image data is not available`.
- **Editor side-effects / readiness.** Opening with `formPath` first **closes any existing editor** for that form (to re-apply the current render mode), then re-opens it and activates page `editors.form.pages.main`. It then waits up to ~15×500ms (`waitForFormEditorPage`) for `wysiwygViewer` to appear, pumping UI events. A cold/just-relaunched workspace can still hit `WYSIWYG page is not available` — wait until `list_projects` shows the project `ready`, then retry **once**; do not retry-spam. The opened tab is left open (no auto-close), unlike `get_symbol_info`/`get_content_assist`.
- **Active-editor mode is fragile.** With no `formPath`, capture works only if a form editor is the active editor page (`FormEditor.getActiveFormEditorPage()` is non-null). If anything else is focused you get `No active form editor page found`. Prefer the explicit `formPath` form for reproducible e2e.
- **Schema/param consistency.** All three params (`projectName`, `formPath`, `refresh`) are both declared in `getInputSchema()` and read in `execute()`; `refresh` is parsed leniently (`"true"` case-insensitive via `JsonUtils.extractStringArgument`).
- **Error contract.** Genuine failures come back as structured `{"success":false,"error":"…"}` with `isError:true` (never a thrown exception escaping the tool, never a bare `"Error: …"` string) — the tool routes every failure through `ToolResult.error(...).toJson()`. Success returns raw base64, not JSON.
- **Flaky output channel.** If the response is a bare `Error`/`Done`/garbled text instead of base64 or a clean error JSON, do NOT trust the echo and do NOT retry-spam — re-verify against the EDT log `D:\WS\EDT\.metadata\.log` (the open/activate/capture path logs the failure reason there) and re-call once.
- **Bilingual.** Only the FQN **type token** is dialect-aware (`Catalog`/`Справочник` both resolve); the object NAME and form NAME are programmatic (resolved by `Name`, not synonym). The rendered text inside the PNG follows the form/configuration's own language, not a tool arg.
- **Infobase exclusivity / mutation safety.** This tool does not touch an infobase and does not mutate the model or disk, so the `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` revert is not required. It is still a heavy UI-thread operation — keep it serialized and do not run it live as a "quick check" while a destructive tool is mid-cascade.

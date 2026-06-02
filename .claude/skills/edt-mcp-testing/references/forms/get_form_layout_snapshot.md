# get_form_layout_snapshot — how to test

**Purpose.** Return a **YAML** snapshot of the active form editor's WYSIWYG layout: the
element tree (`layoutType`/`formEntityType`/`name`/`title`), calculated `bounds`
(left/top/width/height/right/bottom in form WYSIWYG pixels), the `boundsSource`
(`layoutProjection` or `viewProjection`), and display-affecting `properties`/`formProperties`.
It can **open and activate a form automatically** when `formPath` is given, otherwise it reads
the currently active form editor. Implemented by `GetFormLayoutSnapshotTool`
(`ResponseType.TEXT`, output serialized with SnakeYAML block style — NOT JSON).

This is the structured/numeric sibling of `get_form_screenshot` (which returns a PNG). Use it
when you want element positions and properties as data instead of an image. It does **not**
mutate the model (it only opens an editor and reads layout projections via reflection), so no
git revert is needed.

**Preconditions.**
- Live (non-elevated) EDT copy, workspace `D:\WS\EDT`, MCP on `:8765`. After a plugin change:
  `pwsh D:\Soft\edt-redeploy.ps1` (build → kill → swap → `-clean` relaunch → waits for `:8765`).
  The script may **exit 1** yet print `MCP server UP on 8765` — that *is* success; confirm with
  `get_edt_version`, not the exit code.
- Target project **indexed and `ready`** (check `list_projects` → `State: ready`, not `building`).
  Test base: **`TestConfiguration`**, which ships a form at
  `TestConfiguration/src/Catalogs/Catalog/Forms/ItemForm/Form.form` → FQN
  `Catalog.Catalog.Forms.ItemForm`.
- Runs on the **SWT UI thread** (`display.syncExec`). It opens/activates an editor and pumps the
  event loop with sleeps (it waits up to ~15 retries × 500ms for the WYSIWYG page, plus refresh
  pauses), so a cold first call can take several seconds. Do not fire it concurrently with other
  UI-thread tools (`get_form_screenshot`, `get_symbol_info`, `get_content_assist`).
- **JVM-flag dependency / render mode (read this before "fixing" empty bounds).** EDT renders
  forms in two modes:
  - **Native render mode** — `-DnativeFormLayoutRender=true`, the EDT **default**. A C++
    visualizer computes the layout and returns only **form-level** metrics. The Java-side
    `layoutProjection` / `viewProjection` per-element bounds populate **only when
    `!isNativeRender()`**. So in the default native mode you typically get the `formSize` and the
    element tree, but most/all elements have **no per-element `bounds`** → `elementsWithBounds: 0`
    and the warning `"No calculated element bounds were found…"`. This is **expected**, not a bug.
  - **Java render mode** — native render disabled. Then the layout/view projections are populated
    and per-element `bounds` appear. To get rich per-element bounds, run EDT with
    `-DnativeFormLayoutRender=false` in `1cedt.ini` `-vmargs`.
  - NOTE: unlike `get_form_screenshot`, this tool does **not** call
    `EditorScreenshotHelper.ensureBufferedNativeRenderMode()` — the `nativeFormBufferedLayoutRender`
    buffered-screenshot flag is a separate concern and does not change whether per-element bounds
    are computed. Do not confuse the two flags.
- Does NOT mutate — no `git checkout`/`git clean` revert needed.

**Call (documented from source — NOT live-called; UI-thread / heavy / render-mode-dependent).**
Open a known form explicitly (`projectName` is **required** whenever `formPath` is set):
```
get_form_layout_snapshot(
  projectName="TestConfiguration",
  formPath="Catalog.Catalog.Forms.ItemForm",
  mode="compact",
  refresh=true
)
```
Read whatever form is already active (omit `projectName`/`formPath`):
```
get_form_layout_snapshot(mode="full")
```
Parameters (every one below is declared in `getInputSchema()` and read in `execute()`):
- `projectName` — EDT project name. **Required when `formPath` is specified** (else
  `"projectName is required when formPath is specified"`).
- `formPath` — metadata FQN to the form. `MetadataType.ObjectName.Forms.FormName`
  (e.g. `Catalog.Products.Forms.ItemForm`, `Document.SalesOrder.Forms.DocumentForm`) or
  `CommonForm.FormName`. Omit to snapshot the active form editor. Resolved to a file by
  `MetadataPathResolver.resolveFormFilePath` (4-part → `src/<Dir>/<Object>/Forms/<Form>/Form.form`;
  2-part only when the type resolves to `CommonForms`).
- `mode` — `"compact"` (default) returns **only** visual elements that have **positive bounds**
  plus the selected `DISPLAY_PROPERTY_NAMES` (visible/enabled/grid*/width/height/colors/etc.).
  `"full"` returns **all** layout nodes (even without bounds) and all non-containment properties
  (plus decoded HippoLayouter `categories`). Any other value → error `"Invalid mode: <x>. Expected
  'compact' or 'full'."`.
- `refresh` — `true` (default) forces a WYSIWYG refresh + form-image-size extraction before the
  snapshot; `false` skips the refresh and falls back to the control image for `formSize`.

**Result.** Plain-text **YAML** (not a JSON envelope, not an embedded resource). Representative
*success* shape, assembled from the source field-by-field (`captureLayoutSnapshot` →
`boundsMap` → `createElementItem`). In the default **native render mode** most elements have no
per-element `bounds`, so a realistic compact result looks like:
```yaml
success: true
projectName: TestConfiguration
formPath: Catalog.Catalog.Forms.ItemForm
mode: compact
formSize:
  left: 0
  top: 0
  width: 400
  height: 300
  right: 400
  bottom: 300
elementCount: 12
elementsWithBounds: 0
boundsCoordinateSpace: form WYSIWYG pixels
warnings:
- No calculated element bounds were found. The form may not be fully rendered yet.
elements:
- layoutType: hippoLayout:HippoLayForm
  formEntityType: form:Form
  name: Form
  children:
  - layoutType: hippoLayout:HippoLayField
    formEntityType: form:FormField
    name: Description
    title: Description
    formEntity:
      type: form:FormField
      name: Description
    bounds: null
    boundsSource: null
    properties:
      visible: true
      enabled: true
```
With **Java render mode** (`!isNativeRender()`) the leaf elements carry real bounds and a source,
e.g.:
```yaml
  - layoutType: hippoLayout:HippoLayField
    formEntityType: form:FormField
    name: Description
    title: Description
    bounds:
      left: 8
      top: 24
      width: 384
      height: 22
      right: 392
      bottom: 46
    boundsSource: layoutProjection
    properties:
      visible: true
      enabled: true
      width: 384
```
Top-level keys are always, in order: `success`, `projectName`, `formPath`, `mode`, `formSize`,
`elementCount` (total nodes in the tree), `elementsWithBounds`, `boundsCoordinateSpace`
(`"form WYSIWYG pixels"`), `warnings` (list), `elements` (the recursive tree; children under a
`children` key only when non-empty). `compact` mode prunes any element without positive `bounds`
(its children are hoisted up), so in native mode a compact snapshot can legitimately be a short
tree or essentially just the form root.

**Gotchas.**
- **Empty/zero `bounds` ≠ a code bug — it's the render mode.** Native render mode
  (`-DnativeFormLayoutRender=true`, default) gives form-level metrics only; per-element bounds need
  `!isNativeRender()` (Java render mode). If you need positions, switch render mode, do **not**
  "fix" the tool. `elementsWithBounds: 0` + the no-bounds warning is the documented native-mode
  outcome.
- **`compatibilityMode` (configuration) vs `ClientInterfaceVariant` (form) are unrelated.** The
  configuration's `compatibilityMode` (e.g. `8.5.1`) is a platform-version compatibility setting; a
  form's `ClientInterfaceVariant` (`TAXI` vs `VERSION8_5`) is its managed-form interface flavor.
  Neither controls per-element bounds — that is the render-mode/JVM-flag axis above. Don't
  conflate them when reasoning about a result.
- **Output is YAML text, and the error contract is the unusual part.** Genuine failures come back
  as a YAML body `{success: false, error: "<message>"}` produced by `errorYaml(...)`. Because the
  tool is `ResponseType.TEXT` **and the body is YAML, not JSON**, the protocol's
  `isJsonErrorPayload` check (which `JsonParser.parseString`s the body) does **not** match — so
  these failures are delivered as a **normal text result whose YAML says `success: false`, and
  `isError:true` is NOT set**. Assert on the YAML `success:`/`error:` fields, not on a JSON
  `isError` flag. Error messages you can hit: `"projectName is required when formPath is
  specified"`, `"Invalid mode: <x>. Expected 'compact' or 'full'."`, `"Display is not available"`,
  `"No active form editor page found. Specify formPath to open a form automatically."`,
  `"Form editor opened but WYSIWYG page is not available…"`, `"WYSIWYG viewer is not available"`,
  `"WYSIWYG representation is not available"`, `"WYSIWYG layout model is not available"`, plus the
  open-form errors surfaced from `EditorScreenshotHelper.openAndActivateForm` (e.g. `"Project not
  found: <name>"`, `"Form file not found: <path> in project <name>"`, `"Cannot resolve form path:
  <formPath>…"`) — these are extracted from the open helper's JSON via `extractToolErrorMessage`
  and re-wrapped as YAML `error`.
- **Readiness race.** On a cold project or right after a `-clean` relaunch the WYSIWYG page may not
  be built yet → you get `"…WYSIWYG page is not available. The form may still be loading…"` or a
  success with `elementsWithBounds: 0`. Confirm `list_projects` → `ready`, give the index/editor
  time, and re-issue once. Don't retry-spam (each call opens/activates an editor and sleeps on the
  UI thread).
- **Flaky output channel.** The text result can drop/garble (you may see a bare `Error`/`Done`
  instead of the YAML). Do not retry-spam. Re-verify independently via the EDT log
  `D:\WS\EDT\.metadata\.log` (full request/response is logged there); a single re-issue is fine.
  The tool is read-only, so `git status` on `TestConfiguration` should stay clean regardless.
- **Form FQN addressing is programmatic + bilingual TYPE token only.** `formPath` uses the object's
  programmatic `Name` (`Catalog.**Catalog**.Forms.**ItemForm**`), **never** the synonym. Only the
  metadata TYPE token may be bilingual: `Справочник.Catalog.Forms.ItemForm` resolves the same as
  `Catalog.Catalog.Forms.ItemForm` (via `MetadataTypeUtils.getDirectoryName`). The `Forms` keyword
  is matched case-insensitively. A wrong slash/case/name or a non-existent form yields a "Cannot
  resolve form path" / "Form file not found" error, not an empty snapshot.
- **`refresh=false` changes only `formSize` sourcing**, not whether per-element bounds exist: with
  `refresh=true` `formSize` comes from `getFormImageData()` (rebuilt), otherwise from the control
  image; either way bounds population is governed by render mode.

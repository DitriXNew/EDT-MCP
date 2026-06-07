# adopt_metadata_object — how to test

**Purpose.** Adopt (заимствовать) a **base-configuration** metadata object — or a member of it (form / attribute / tabular section / ...) — into a configuration **EXTENSION**, so the extension can override / intercept it. The MCP equivalent of EDT's **"Add To Extension"** (Alt+F3) for the metadata OBJECT side. Adopting BSL code/methods (`&Before/&After/&Around/&ChangeAndValidate`) is a separate, not-implemented concern. The adopt+attach is done by the platform `IModelObjectAdopter.adoptAndAttach` (obtained via `ServiceAccess`): it creates the adopted copy with `<objectBelonging>Adopted</objectBelonging>` and an `extendedConfigurationObject` UUID link to the base (mapping is by-ID, so the adopted copy may later be renamed). The new `.mdo` + the extension's `Configuration.mdo` registration are force-exported to disk.

> **MUTATES the EXTENSION (not the base).** Run only on `TestConfiguration` + its extension `TestConfiguration.tests`. Revert through MCP-synced git: `git checkout HEAD -- tests/tests && git clean -fd tests/tests`, then `clean_project(TestConfiguration.tests)` to re-sync EDT's in-memory model to the reverted disk (else autosave brings the adopted copy back). The e2e harness resets only the BASE fixture per-test, so the mutating happy path is validated manually here, not headless.

**Preconditions.**
- MCP server up (`:8765`), live EDT, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (may exit 1 yet print `MCP server UP on 8765` — that is success).
- BOTH projects `State=ready` — poll `list_projects` (after a `-clean` relaunch the index rebuilds). Adopt mutates the extension model; a still-indexing project can flake.
- `projectName` = the **base configuration** (`TestConfiguration`), NOT the extension. The single extension `TestConfiguration.tests` is auto-selected; pass `extensionProjectName` only when several extensions extend the configuration.
- Runs on the SWT UI thread (extends AbstractMetadataWriteTool).

**Call.** Parameters: `projectName` (required), `fqn` (required), `extensionProjectName` (optional). FQN forms: top object `Catalog.Catalog`; member `Catalog.Catalog.Attribute.Name`; form `Catalog.Catalog.Form.ItemForm` (singular `Form` accepted; resolved via the form reader).

Adopt a fresh top object (mutating — explicit-request-only; revert after):
```
adopt_metadata_object(projectName="TestConfiguration", fqn="CommonModule.OK")
```
**Real result:** `{success:true, action:"adopted", fqn:"CommonModule.OK", extensionProject:"TestConfiguration.tests", objectBelonging:"ADOPTED", persisted:true}`. On disk `tests/tests/src/CommonModules/OK/OK.mdo` then carries `<objectBelonging>Adopted</objectBelonging>` + `extendedConfigurationObject="<base-uuid>"`, and `tests/tests/src/Configuration/Configuration.mdo` gains the registration.

Already-adopted (benign, non-mutating — the fixture already adopts these):
```
adopt_metadata_object(projectName="TestConfiguration", fqn="CommonModule.Calc")
adopt_metadata_object(projectName="TestConfiguration", fqn="Catalog.Catalog.Form.ItemForm")
```
→ `action:"alreadyAdopted"` (the `isAdopted` branch; the form case proves form-FQN resolution).

**Negative (non-mutating).** Missing `projectName`/`fqn` → clear is_error. Unknown FQN (`Catalog.NoSuch`) → `Object not found: ...` naming the value + the FQN shape. Bogus `extensionProjectName` → error naming the bad value AND listing the real candidate extensions.

**Gotchas.**
- `((IBmObject)x).bmGetFqn()` works ONLY on a TOP object; on an adopted MEMBER (form/attribute) it throws "may be called on top objects only" — the tool reports the input FQN and exports via `bmGetTopObject().bmGetFqn()`.
- Adopting a member implicitly adopts its owning object (cascade), mirroring the platform.
- To revert an adopt, delete the adopted copy with `delete_metadata` against the extension (no automatic undo).

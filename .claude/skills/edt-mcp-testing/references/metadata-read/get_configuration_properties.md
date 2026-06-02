# get_configuration_properties — how to test

**Purpose.** Returns the top-level properties of a configuration (the `Configuration` MD object): `name`, `synonym`, `comment`, `scriptVariant`, `defaultRunMode`, `dataLockControlMode`, `compatibilityMode`, `modalityUseMode`, `interfaceCompatibilityMode`, `objectAutonumerationMode`, `usePurposes`, vendor/version/copyright and the localized info-address fields, plus `defaultLanguage`. Source: `GetConfigurationPropertiesTool.java`. The body is a **human-readable YAML document** (not JSON) where null scalars and empty localized maps are omitted, so the output only shows fields that are actually set.

**Preconditions.**
- Live EDT (non-elevated copy), MCP on `:8765`, workspace `D:\WS\EDT`, project `TestConfiguration` open.
- The project must be `State=ready` (check via `list_projects`). After a `-clean` redeploy the index rebuilds and reads can be empty/partial until ready.
- `projectName` is **optional**. When omitted, the tool walks the workspace and picks the **first open `IConfigurationProject`** it finds. With a busy workspace (e.g. `IRP` also open) the "first" project is not deterministic — pass `projectName` explicitly to target `TestConfiguration`.
- Read-only. Does **not** mutate the model or disk. No revert needed. Resolution runs on the UI thread (`Display.syncExec`); avoid hammering this concurrently with other UI-thread tools.

**Call (real, 2026-06-02):**
```
get_configuration_properties(projectName="TestConfiguration")
```
(`projectName` is the only parameter — see `getInputSchema()`. Omitting it returns the first configuration project instead.)

**Result.** `ResponseType.MARKDOWN`, returned as an MCP **resource** (`embedded://configuration-properties.yaml`), NOT a `{success:true,...}` JSON envelope. The body is YAML. Real output for the call above:
```
name: TestConfiguration
synonym:
  en: Test configuration
scriptVariant: English
defaultRunMode: ManagedApplication
dataLockControlMode: Managed
compatibilityMode: 8.5.1
modalityUseMode: DontUse
interfaceCompatibilityMode: Taxi
objectAutonumerationMode: NotAutoFree
usePurposes:
  - PersonalComputer
defaultLanguage: en
defaultLanguageName: English
projectName: TestConfiguration
```
Field notes (from the source):
- **`synonym`** (and `briefInformation`, `detailedInformation`, `copyright`, `vendorInformationAddress`, `configurationInformationAddress`) are nested localized maps emitted as `langCode: value` (e.g. `en: …`, `ru: …`). The map key is the **language CODE**, not the language name. Here only `en` is configured, so only `en` appears.
- **`defaultLanguage`** is the language **CODE** (`en`) — the same key used in the synonym map — and **`defaultLanguageName`** is its human-readable name (`English`). Both lines appear only when a default language is set.
- **Omitted fields** in this output (`comment`, `vendor`, `version`, `copyright`, `briefInformation`, `detailedInformation`, the info-address maps) are unset on TestConfiguration, so their lines are dropped entirely — an absent line means "not set", never `{}` or `null`. A large real config (e.g. `IRP`) typically populates many of these.
- Scalar enum fields (`scriptVariant`, `defaultRunMode`, `dataLockControlMode`, `compatibilityMode`, `modalityUseMode`, `interfaceCompatibilityMode`, `objectAutonumerationMode`) are each emitted only when non-null, as the enum's `toString()`.
- **`usePurposes`** is a YAML list (`- item` per element).
- **`projectName`** at the end echoes the actually-resolved project name — useful to confirm which config you got when `projectName` was omitted.

**Gotchas.**
- **YAML resource, not JSON.** Unlike argument-validating tools there is no `{success:true,...}` envelope on the happy path; parse the YAML resource body, not JSON. The resource file name is `configuration-properties.yaml`.
- **Error contract is split.** Genuine failures still travel as structured JSON — `ToolResult.error(...).toJson()` → delivered as `{success:false, error:"…"}` with `isError:true` via the protocol's JSON-error diversion, independent of the MARKDOWN body. Concrete error messages: `Project manager not available`, `No configuration project found` (or `… with name: <projectName>` when a name was given but not matched), `Configuration object not available`, and the raw exception message on an unexpected internal failure. There are no argument-validation errors (the single param is optional).
- **`compatibilityMode` ≠ form interface variant.** `compatibilityMode: 8.5.1` is the configuration's platform compatibility level. Do not confuse it with a form's `ClientInterfaceVariant` (TAXI vs VERSION8_5) — `interfaceCompatibilityMode: Taxi` here is the configuration-level interface compatibility, a separate property.
- **Default-language code vs name (the bilingual trap).** `defaultLanguage` is intentionally the language **CODE** (the synonym map key), not the language `Name`. If you see a name like `English`/`Русский` under `defaultLanguage` instead of `en`/`ru`, that is a regression (keying synonyms/default language by name silently breaks multi-language configs). The human name lives separately in `defaultLanguageName`.
- **Empty-map omission is by design.** Missing localized blocks (e.g. no `copyright:`) are intended — the old JSON path used to emit empty objects like `copyright: {}`; the YAML path drops them. Absence is not a bug.
- **Project selection when `projectName` omitted.** With multiple open configuration projects, the first-found one is returned (non-deterministic order). Always pass `projectName` for a reproducible e2e result, and confirm via the echoed `projectName` line.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of the YAML), do NOT retry-spam. Re-verify independently from the EDT log `D:\WS\EDT\.metadata\.log` (full request/response); the same properties are visible in EDT's Configuration properties editor.

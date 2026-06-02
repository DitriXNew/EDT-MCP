# get_subsystem_content — how to test

**Purpose.** Inspect one specific 1C subsystem (identified by its FQN) and emit a Markdown report with three parts: a `## Properties` table (FQN, Name, Synonym, optional Comment, the command-interface/help/one-command flags, optional Explanation, optional Parent Subsystem), a `## Content` table of the metadata objects placed in the subsystem (Type / Name / Synonym / FQN), and a `## Child Subsystems` table (one row per directly nested subsystem). Read-only. Use `list_subsystems` first to discover the FQNs, then drill into one with this tool. By default it lists only the requested subsystem's own content (`recursive=false`); `recursive=true` also folds in objects from nested subsystems (deduplicated).

**Preconditions.** The project must be open in the workspace and `State=ready` (check with `list_projects` first — right after a `-clean` redeploy the index rebuilds and the configuration model is empty/partial until ready). The tool reads the in-memory `Configuration` model via `IConfigurationProvider` (it does NOT scan `src/`) and resolves the subsystem by FQN through `SubsystemUtils.resolveByFqn`. It runs on the SWT UI thread (`display.syncExec`). It does NOT mutate anything, so no revert is needed. `TestConfiguration` is a valid target but has a single, empty subsystem — good for the empty-content case; use a larger config (e.g. `IRP`) for a populated Content/Children example.

**Call (real, 2026-06-02).**
```
get_subsystem_content(projectName="TestConfiguration", subsystemFqn="Subsystem.Subsystem")
```
Populated example against a nested subsystem (real run, `IRP`):
```
get_subsystem_content(projectName="IRP", subsystemFqn="Subsystem.Salary")
```
Required args: `projectName`, `subsystemFqn`. Optional: `recursive` (default `false`; `true` merges objects from nested subsystems into the Content section, deduplicated), `language` (synonym language code, e.g. `"en"`/`"ru"`; defaults to the configuration's first/default language). The FQN is the same `Subsystem.<Name>` / `Subsystem.<Parent>.Subsystem.<Child>` form that `list_subsystems` emits.

**Result.** Markdown resource (`ResponseType.MARKDOWN`, filename `subsystem-<dash-lowercased-fqn>.md`), NOT JSON. Heading `# Subsystem: <Name> (<Synonym>)` (the `(Synonym)` suffix is dropped when the synonym is empty), then `## Properties`, then `## Content — N objects`, then (only when present) `## Child Subsystems — N`.

Real output — `TestConfiguration`'s lone subsystem has no objects and no children, so Content is the informational empty form and the Child Subsystems section is omitted entirely:
```markdown
# Subsystem: Subsystem (Subsystem)

## Properties

| Property | Value |
|----------|-------|
| FQN | Subsystem.Subsystem |
| Name | Subsystem |
| Synonym | Subsystem |
| Include In Command Interface | Yes |
| Include Help In Contents | Yes |
| Use One Command | No |

## Content — 0 objects

*No objects in this subsystem.*
```

Real output for a populated, nested subsystem (`IRP` → `Subsystem.Salary`) showing the Content table (sorted by Type then Name) and the Child Subsystems table:
```markdown
# Subsystem: Salary (Salary)

## Properties

| Property | Value |
|----------|-------|
| FQN | Subsystem.Salary |
| Name | Salary |
| Synonym | Salary |
| Include In Command Interface | Yes |
| Include Help In Contents | Yes |
| Use One Command | No |

## Content — 9 objects

| Type | Name | Synonym | FQN |
|------|------|---------|-----|
| AccumulationRegister | R9541T_VacationUsage | R9541T Vacation usage | AccumulationRegister.R9541T_VacationUsage |
| CommonCommand | OpenPartnersEmployee | Employee | CommonCommand.OpenPartnersEmployee |
| Document | AdditionalAccrual | Additional accrual | Document.AdditionalAccrual |
| Document | AdditionalDeduction | Additional deduction | Document.AdditionalDeduction |
| Document | CalculationDeservedVacations | Calculation deserved vacations | Document.CalculationDeservedVacations |
| Document | Payroll | Payroll | Document.Payroll |
| Document | TimeSheet | Time sheet | Document.TimeSheet |
| Report | R3027A_CashAdvance | R3027A Cash advance | Report.R3027A_CashAdvance |
| Report | R9510A_Salary | R9510A Salary | Report.R9510A_Salary |

## Child Subsystems — 4

| FQN | Synonym | Content | Children |
|-----|---------|---------|----------|
| Subsystem.Salary.Subsystem.Settings | Settings | 4 | 0 |
| Subsystem.Salary.Subsystem.Catalogs | Catalogs | 4 | 0 |
| Subsystem.Salary.Subsystem.PersonnelRecords | Personnel records | 5 | 0 |
| Subsystem.Salary.Subsystem.Other | Other | 4 | 0 |
```

**Gotchas.**
- **FQN is the addressing contract — it must resolve segment-by-segment.** `subsystemFqn` is parsed as alternating `Type.Name` pairs (`Subsystem.Salary.Subsystem.Settings`); odd arity, a bad/unknown type token, or an empty name segment make `parseSubsystemPath` return `null` and yield `"Subsystem not found: <fqn>"`. The type token is bilingual and case-insensitive (English `Subsystem`/`Subsystems`, Russian `Подсистема`/`Подсистемы`, even mixed across segments via `MetadataTypeUtils.toEnglishSingular`), and the subsystem **Name** match is case-insensitive — but the Name itself is the programmatic identifier (never the synonym). Note a non-subsystem token like `Catalog.Products` resolves to `null` → not-found.
- **Object NAME is programmatic; only the Synonym is bilingual.** In the Content table the `Name`/`FQN` columns use each object's programmatic `Name` and EClass type (`obj.eClass().getName()`, e.g. `AccumulationRegister`, `CommonCommand`), never a translation. The `Synonym` column (and the heading's `(Synonym)`, the Properties `Synonym`/`Explanation` rows, the child `Synonym` column) come from the synonym EMap keyed by **language CODE** (`ru`/`en`) via `SubsystemUtils.getSynonymForLanguage` — `language="ru"` selects the Russian synonym, `language="en"` the English one. With no `language` arg the tool resolves the configuration's default/first language code (`SubsystemUtils.resolveLanguage` → `MetadataLanguageUtils.resolveLanguageCode`); a missing entry falls back to any non-empty synonym, then to empty string.
- **`recursive` only changes the Content section, not Children.** `recursive=false` (default) lists only the subsystem's own `getContent()`. `recursive=true` walks nested subsystems and merges their objects into Content, deduplicated by object identity (`seen` set), and the header becomes `## Content (recursive) — N objects`. The `## Child Subsystems` table is unaffected by `recursive` — its `Content`/`Children` columns are always the child's own direct counts.
- **Empty content is success, not error.** `## Content — 0 objects` followed by `*No objects in this subsystem.*` is a valid Markdown result (as with `TestConfiguration`). The `## Child Subsystems` section is **omitted entirely** when the subsystem has no nested subsystems — do not assert it is always present.
- **Properties rows are conditional.** `Comment`, `Explanation`, and `Parent Subsystem` rows appear only when set/non-empty; FQN/Name/Synonym and the three flag rows (Include In Command Interface, Include Help In Contents, Use One Command) are always present. Flags render as `Yes`/`No`.
- **Structured error contract.** Genuine failures come back as `ToolResult.error(...)` JSON — `{"success":false,"error":"…"}` with `isError:true` — even though the success path is Markdown. Triggers: missing/empty `projectName` (`"projectName is required"`), missing/empty `subsystemFqn` (`"subsystemFqn is required (e.g. 'Subsystem.Sales')"`), project not in the workspace (`"Project not found: <name>"`), no configuration provider (`"Configuration provider not available"`), no configuration model (`"Could not get configuration for project: <name>"`), or an unresolvable FQN (`"Subsystem not found: <fqn>"`). The success-path Markdown has no `success` field — assert on the `# Subsystem:` heading / tables, not on a JSON flag.
- **UI-thread tool — don't hammer it concurrently** with other `display.syncExec` tools. The output channel can garble: a not-found probe in this session returned a bare `Error` with the body dropped (the source guarantees the real text is `Subsystem not found: <fqn>`). Do NOT retry-spam — re-verify against the EDT log at `D:\WS\EDT\.metadata\.log` (it records the full request/response) and trust that over the dropped echo.
- **Markdown table safety.** All cell values (types, names, synonyms, FQNs, property values) go through `MarkdownUtils.escapeForTable`, so a `|` or newline inside a synonym/comment will not break the table — assert on a delimiter-free substring of the value if you compare exact text.

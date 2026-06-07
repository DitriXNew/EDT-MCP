# list_subsystems — how to test

**Purpose.** List the 1C subsystems of a configuration as a flat markdown table: FQN (e.g. `Subsystem.Sales` or the nested `Subsystem.Sales.Subsystem.Orders`), Synonym, Comment, IncludeInCommandInterface flag, content count (objects placed in the subsystem) and children count (nested subsystems). Read-only. Walks the whole subsystem tree by default (`recursive=true`); use `get_subsystem_content` to inspect the objects inside one subsystem.

**Preconditions.** The project must be open in the workspace and `State=ready` (check with `list_projects` first — right after a `-clean` redeploy the index rebuilds and the configuration model is empty/partial until ready). The tool reads the in-memory `Configuration` model via `IConfigurationProvider` (it does NOT scan `src/`). It runs on the SWT UI thread (`display.syncExec`). It does NOT mutate anything, so no revert is needed. `TestConfiguration` is a valid target; it is small, so expect a tiny tree.

**Call (real).**
```
list_subsystems(projectName="TestConfiguration")
```
Top-level-only variant:
```
list_subsystems(projectName="TestConfiguration", recursive=false)
```
Other args (all optional): `nameFilter` (case-insensitive substring, matches the programmatic **Name** only — not the Synonym), `recursive` (default `true`), `limit` (default 100 from preferences, clamped to 1..1000), `language` (synonym language code, e.g. `"en"`/`"ru"`; defaults to the configuration's first/default language).

**Result.** Markdown resource (`ResponseType.MARKDOWN`, filename `subsystems-<projectname>.md`), NOT JSON. A `## Subsystems: <project>` heading, an optional `**Mode:** top-level only` line (only when `recursive=false`), a `**Total:** N subsystems` line (with `(showing M)` appended when truncated by `limit`), then a 6-column table: FQN | Synonym | Comment | InCommandInterface | Content | Children.

Real output from the live workspace (`TestConfiguration` has exactly one subsystem):
```
## Subsystems: TestConfiguration

**Total:** 1 subsystems

| FQN | Synonym | Comment | InCommandInterface | Content | Children |
|-----|---------|---------|--------------------|---------|----------|
| Subsystem.Subsystem | Subsystem |  | Yes | 0 | 0 |
```

Representative shape for a configuration with a nested tree (e.g. the big `IRP` config — illustrative, not a captured run): a recursive walk emits a parent row followed by each descendant, the FQN growing one `Subsystem.<Name>` segment per nesting level:
```
## Subsystems: IRP

**Total:** 12 subsystems

| FQN | Synonym | Comment | InCommandInterface | Content | Children |
|-----|---------|---------|--------------------|---------|----------|
| Subsystem.Sales | Продажи |  | Yes | 14 | 2 |
| Subsystem.Sales.Subsystem.Orders | Заказы |  | Yes | 8 | 0 |
| Subsystem.Sales.Subsystem.Invoices | Счета |  | No | 5 | 0 |
| Subsystem.Warehouse | Склад |  | Yes | 9 | 0 |
```

Informational empty result (a project that has no subsystems) — success, not an error:
```
## Subsystems: SomeProject

**Total:** 0 subsystems

No subsystems found.
```

**Gotchas.**
- **Object NAME is programmatic; only the Synonym is bilingual.** The FQN segments use the subsystem's programmatic `Name` (`Subsystem.<Name>`, never translated). The Synonym column comes from the synonym EMap keyed by **language CODE** (`ru`/`en`), not by the language name — `language="ru"` selects the Russian synonym, `language="en"` the English one. With no `language` arg the tool resolves the configuration's default/first language code (`SubsystemUtils.resolveLanguage` → `MetadataLanguageUtils.resolveLanguageCode`); if that code has no entry it falls back to any non-empty synonym, and to empty string when none is set. `nameFilter` matches the Name only — searching by synonym text will not match.
- **FQN is the contract for `get_subsystem_content`.** The FQN column (`Subsystem.Sales.Subsystem.Orders`) is exactly the form `get_subsystem_content` accepts. That resolver (`SubsystemUtils.resolveByFqn`) also accepts the Russian type token `Подсистема` and mixed/case-insensitive tokens, but `list_subsystems` always **emits** the English `Subsystem.` token.
- **`recursive` changes both the rows and the header.** `recursive=true` (default) flattens the entire tree into one table; `recursive=false` lists only top-level subsystems AND adds a `**Mode:** top-level only` line under the heading. The `Children` column is the direct nested-subsystem count regardless of mode, so a top-level row can show `Children > 0` even when `recursive=false` hides those children.
- **`Content` vs `Children`.** `Content` = number of metadata objects assigned to the subsystem (`subsystem.getContent().size()`); `Children` = number of directly nested subsystems (`subsystem.getSubsystems().size()`). Both are plain integers, not links. `TestConfiguration`'s lone subsystem has `Content 0 / Children 0`.
- **`limit` is clamped to 1..1000** (default 100 from preferences). When truncated, the header shows `**Total:** N subsystems (showing M)` and the table holds only the first `M` rows — there is no offset/pagination, so narrow with `nameFilter` or `recursive=false` instead of paging.
- **Empty result is success, not error.** A project with no subsystems returns the heading, `**Total:** 0 subsystems`, and `No subsystems found.` as Markdown — not a JSON error. (Note the literal grammar `1 subsystems` / `0 subsystems` — the count word is never singularized.)
- **Structured error contract.** Genuine failures come back as `ToolResult.error(...)` JSON — `{"success":false,"error":"…"}` with `isError:true` — even though the success path is Markdown. Triggers: missing/empty `projectName` (`"projectName is required"`), project not in the workspace (`"Project not found: <name>"`), no configuration provider (`"Configuration provider not available"`), or no configuration model for the project. The success-path Markdown has no `success` field — assert on the `## Subsystems:` heading / table, not on a JSON flag.
- **UI-thread tool — don't hammer it concurrently** with other `display.syncExec` tools. If the output channel garbles (you see a bare `Error`/`Done` instead of the table), do NOT retry-spam: re-verify against the EDT log at `D:\WS\EDT\.metadata\.log` (it records the full request/response) and trust that over the dropped echo.

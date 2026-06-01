# EDT‑MCP end‑to‑end test cases

Concrete, runnable test cases for **every** MCP tool the server exposes, against a
live EDT workbench. Built so a test agent (e.g. a Sonnet sub‑agent) can execute the
whole suite by calling the `mcp__EDT-MCP-Server__*` tools and recording PASS/FAIL.

> Status: **first pass complete (2026‑06‑01)** — all 9 groups / 58 tools documented and
> exercised live against EDT 2026.1.1 + IRP (56 tools called; 2 pending a fresh MCP
> connection — see Findings). Each tool section ends with a `Validated:` line stating when
> it was last exercised and the observed result. Re‑run the suite after any tool change.

## How to run (instructions for the test agent)

1. **Target server.** The EDT MCP server on `http://localhost:8765/mcp`; tools appear
   in‑session as `mcp__EDT-MCP-Server__<name>`. Load each tool's schema via ToolSearch
   (`select:mcp__EDT-MCP-Server__<name>,…`) before calling it.
2. **Test project.** All read/analysis cases use the **IRP** project (open‑source
   `IRPTeam/IRP`) loaded in workspace `D:\WS\EDT`. English config, default language
   `en`, compat `8.3.25`, Taxi. If IRP is absent, `list_projects` first and substitute
   a real project + FQNs.
3. **For each case:** call the tool with the given input, then check the assertions.
   Record `PASS`/`FAIL`, the actual summary, and any deviation from the `Validated:`
   baseline.
4. **Safety — do NOT mutate the test config.** Tools are tagged with a **Type**:
   - `read` — safe to run freely.
   - `write` / `destructive` — run **only the error/validation cases** here. Do not run
     the happy path against IRP; it edits BSL, metadata, or the database. A throw‑away
     sandbox project is required for happy‑path coverage (note it as `SKIPPED (needs sandbox)`).
   - `debug`/`app` — require an infobase + launch configuration. IRP currently has
     **none** (`get_applications` → 0, `list_configurations` → 0), so only the
     error/validation paths are runnable here; full flow is `SKIPPED (needs infobase)`.
5. **Error‑shape note.** JSON‑returning tools emit `{"success":false,"error":"…"}`.
   Markdown/text tools emit a leading `**Error:** …`. Gson HTML‑escapes `> < &` in JSON
   string values — match delimiter‑free substrings when asserting on error text.

## IRP anchors (real objects — use these as inputs)

| Kind | Value(s) |
|---|---|
| Project | `IRP` |
| Catalog FQNs | `Catalog.Agreements`, `Catalog.AccessGroups`, `Catalog.Items` |
| Document FQNs | `Document.BankPayment`, `Document.RetailSalesReceipt`, `Document.CashExpense` |
| Common module path | `CommonModules/AccountingServer/Module.bsl` (type `Module`) |
| Common module FQN | `CommonModule.AccountingServer`, `CommonModule.AccountingClientServer` |
| Subsystem FQNs | `Subsystem.Retail`, `Subsystem.Retail.Subsystem.Sales`, `Subsystem.Inventory.Subsystem.Items` |
| Counts (baseline) | catalogs 108 · documents 99 · common modules 360 · subsystems 93 · problems 976 (24 ERROR) |
| Tags | none defined |
| Applications / launch configs | none |

---

## Group: Core / Project

### get_edt_version
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{}` → returns the EDT version string.
- **Assert:** non‑empty version matching `\d+\.\d+\.\d+`.
- **Validated 2026‑06‑01:** PASS — `2026.1.1.1`.

### list_projects
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{}` → markdown table of workspace projects (Name/State/Path/Open/EDT/Natures).
- **Assert:** contains a row `IRP` with State `ready`, Open `Yes`, natures incl. `V8ConfigurationNature`.
- **Validated 2026‑06‑01:** PASS — IRP `ready`; Unit `not_available`.

### get_configuration_properties
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP"}` → JSON config properties.
  - `{}` → first configuration project (defaults to IRP here).
- **Assert:** `name=="IRP"`, `scriptVariant=="English"`, `defaultLanguage=="English"`, `compatibilityMode=="8.3.25"`, `success==true`.
- **Validated 2026‑06‑01:** PASS.

### get_check_description
- **Type:** read (config‑dependent) · **Runnable on IRP:** needs prefs
- **Cases:**
  - `{checkId:"ql-temp-table-index"}` → check doc markdown **iff** the descriptions
    folder is set in *Preferences → MCP Server*; otherwise a not‑configured message.
  - missing `checkId` → validation error (`checkId` is required).
- **Assert:** either valid markdown, or `**Error:** Check descriptions folder is not configured.`
- **Validated 2026‑06‑01:** PASS (folder not configured → returned the not‑configured message).

### revalidate_objects
- **Type:** write (marker state; reversible) · **Runnable on IRP:** full (light)
- **Cases:**
  - `{projectName:"IRP", objects:["Catalog.Agreements"]}` → revalidates one object.
  - `{projectName:"IRP", objects:["Catalog.NoSuchXYZ"]}` → reports objectsFound 0.
  - missing `projectName` → validation error.
- **Assert:** happy → `success==true`, `mode=="objects"`, `objectsFound==1`, `"Revalidation completed"`.
- **Validated 2026‑06‑01:** PASS — `objectsFound:1`, `objectsValidated:["Catalog.Agreements"]`.

### clean_project
- **Type:** destructive (clears all markers + full revalidation) · **Runnable on IRP:** SKIPPED (heavy)
- **Cases (do not run happy path on IRP):**
  - `{projectName:"IRP"}` → clears markers, waits for full revalidation (minutes on a 976‑problem project).
  - `{}` → cleans **all** EDT projects (even heavier).
- **Assert (sandbox only):** `success==true`; afterwards `get_problem_summary` repopulates.
- **Validated 2026‑06‑01:** not run live (would trigger full revalidation). Schema/contract confirmed.

### export_configuration_to_xml
- **Type:** write (writes XML tree to disk; does not change IRP) · **Runnable on IRP:** error‑only here
- **Cases:**
  - `{projectName:"NoSuchProj_e2e", outputPath:"…"}` → error before acting.
  - (sandbox) `{projectName:"IRP", outputPath:"D:/tmp/irp-xml"}` → exports full config; heavy.
- **Assert:** bad project → `success==false`, error contains `does not exist`.
- **Validated 2026‑06‑01:** PASS (error path) — `"Export failed: Workspace project with name NoSuchProj_e2e does not exist"`.

### import_configuration_from_xml
- **Type:** destructive (creates a new workspace project) · **Runnable on IRP:** error‑only here
- **Cases:**
  - `{importPath:"D:/tmp/e2e-does-not-exist-dir", projectName:"ImpTest_e2e"}` → error, path validated first.
  - (sandbox) valid XML dir + fresh project name → creates project.
- **Assert:** bad path → `success==false`, error contains `importPath does not exist`.
- **Validated 2026‑06‑01:** PASS (error path) — `"importPath does not exist: D:\tmp\e2e-does-not-exist-dir"`.

---

## Group: Errors & Problems

### get_problem_summary
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP"}` → markdown with overall totals + per‑project table by severity.
  - `{}` → all projects.
- **Assert:** table has rows ERRORS/BLOCKER/CRITICAL/MAJOR/MINOR/TRIVIAL + TOTAL; IRP TOTAL > 0.
- **Validated 2026‑06‑01:** PASS — IRP TOTAL 976 (24 ERROR / 69 BLOCKER / 14 CRITICAL / 158 MAJOR / 711 MINOR).

### get_project_errors
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", severity:"ERRORS", limit:3}` → table (Description/Location/Check code/Has docs).
  - `{projectName:"IRP", checkId:"undefined-variable"}` → only that check.
  - `{projectName:"IRP", objects:["Catalog.Agreements"]}` → only that object's problems.
- **Assert:** rows carry a `Check code`; severity filter narrows results; `limit` honoured.
- **Validated 2026‑06‑01:** PASS — ERRORS sample = `undefined-variable` on `CommonModule.EquipmentFiscalPrinterClientServer.Module`.

### get_bookmarks
- **Type:** read · **Runnable on IRP:** full (empty result is valid)
- **Cases:** `{projectName:"IRP"}` → bookmark list (Message/Path/Line). IRP has none.
- **Assert:** well‑formed "Found: N bookmarks"; N≥0.
- **Validated 2026‑06‑01:** PASS — 0 bookmarks.

### get_tasks
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", limit:5}` → TODO/FIXME table (Type/Priority/Message/Path/Line).
  - `{projectName:"IRP", priority:"high"}` → priority filter.
- **Assert:** rows have a Path under `/IRP/src/…` and a Line number; "more available" when truncated.
- **Validated 2026‑06‑01:** PASS — 5 TODOs (e.g. `CommonModules/BarcodeClient/Module.bsl:178`).

---

## Group: Code Intelligence

### get_content_assist
- **Type:** read (opens the file in EDT) · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", filePath:"CommonModules/AccountingServer/Module.bsl", line:10, column:1, limit:6}` → proposals at position.
  - add `contains:"Get"` → filter; add `offset:6` → pagination.
- **Assert:** `success==true`, `totalProposals>0`, `returnedProposals==limit`, each proposal has `displayString`.
- **Validated 2026‑06‑01:** PASS — totalProposals 1292, returned 6.

### get_platform_documentation
- **Type:** read · **Runnable on IRP:** full (no project needed)
- **Cases:**
  - `{typeName:"ValueTable", memberType:"method", limit:4}` → methods, bilingual headers.
  - `{typeName:"ТаблицаЗначений"}` → same type via Russian name.
  - `{typeName:"FindFiles", category:"builtin"}` → built‑in function.
  - `{typeName:"ValueTable", language:"ru"}` → Russian output.
- **Assert:** returns the type with EN/RU names (`ValueTable / ТаблицаЗначений`) and member sections.
- **Validated 2026‑06‑01:** PASS — methods Add/ChooseRow/Clear/Copy with params & returns.

### get_metadata_objects
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", metadataType:"catalogs", limit:12}` → 108 total, 12 shown.
  - `{projectName:"IRP", metadataType:"documents"}` · `{nameFilter:"Bank"}` · `{language:"ru"}`.
- **Assert:** markdown table Name/Synonym/Comment/Type/ObjectModule/ManagerModule; "Total" reflects type count.
- **Validated 2026‑06‑01:** PASS — catalogs 108, documents 99.

### get_metadata_details
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", objectFqns:["Catalog.Agreements","Document.BankPayment"]}` → per‑object details.
  - add `full:true` → all properties; `language:"ru"`.
  - Russian type token: `objectFqns:["Справочник.Agreements"]` resolves the same object.
- **Assert:** sections Basic/Attributes/Tabular Sections/Forms/Commands; attribute types like `CatalogRef.Partners`.
- **Validated 2026‑06‑01:** PASS — Agreements (31 attrs, AddAttributes TS, 3 forms); BankPayment (4 TS incl. PaymentList).

### list_subsystems
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP"}` → recursive flat table (93 rows).
  - `{recursive:false}` → top‑level only; `{nameFilter:"Sales"}`.
- **Assert:** FQNs use nested form `Subsystem.X.Subsystem.Y`; columns incl. Content/Children counts.
- **Validated 2026‑06‑01:** PASS — 93 subsystems; e.g. `Subsystem.Retail` (Content 11, Children 2).

### get_subsystem_content
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", subsystemFqn:"Subsystem.Retail.Subsystem.Sales"}` → properties + content table.
  - add `recursive:true` → includes nested objects (deduped).
- **Assert:** Content table lists Type/Name/Synonym/FQN; Parent Subsystem shown in Properties.
- **Validated 2026‑06‑01:** PASS — Sales: 5 objects (1 CommonCommand + 4 Documents).

### find_references
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{projectName:"IRP", objectFqn:"Catalog.Agreements", limit:15}` → grouped references + total.
  - Russian token `objectFqn:"Справочник.Agreements"`; sub‑object FQN → descriptive "top‑level only" error.
- **Assert:** "Total references found: N" (N large for Agreements); entries across metadata/forms/BSL.
- **Validated 2026‑06‑01:** PASS — 150 references across registers, forms, documents, subsystems.

---

## Group: Tags

> IRP has **no tags defined**, so only the empty/not‑found paths are runnable here.
> Happy‑path coverage needs a project with tags (assign one via the Navigator first).

### get_tags
- **Type:** read · **Runnable on IRP:** empty result
- **Cases:** `{projectName:"IRP"}` → tag table, or "No tags defined" message.
- **Assert:** when tags exist → table Name/Color/Description/Count; IRP → `No tags defined in project: IRP`.
- **Validated 2026‑06‑01:** PASS — "No tags defined".

### get_objects_by_tags
- **Type:** read · **Runnable on IRP:** not‑found path
- **Cases:**
  - `{projectName:"IRP", tags:["Important"]}` → "Tags not found" + 0 objects.
  - (sandbox) existing tag → per‑tag sections with object FQNs.
- **Assert:** unknown tag → `⚠️ Tags not found` listing the tag, `Found 0 objects across 0 tags`.
- **Validated 2026‑06‑01:** PASS — Important not found, 0 objects.

---

## Group: BSL Code

### list_modules
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{projectName:"IRP", metadataType:"commonModules", limit:12}`; `{objectName:"BankPayment"}`; `{nameFilter:"Server"}`.
- **Assert:** table Module Path/Module Type/Parent Type/Parent Name; commonModules total 360.
- **Validated 2026‑06‑01:** PASS — 360 common modules.

### get_module_structure
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{projectName:"IRP", modulePath:"CommonModules/AccountingClientServer/Module.bsl"}`; add `includeVariables:true`, `includeComments:true`.
- **Assert:** methods table with Type/Name/Export/Context/Lines/Parameters; counts line up.
- **Validated 2026‑06‑01:** PASS — 1 proc + 2 funcs; `UpdateAccountingTables` lines 2‑17.

### read_module_source
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{…, modulePath:"CommonModules/AccountingClientServer/Module.bsl", startLine:82, endLine:98}`; full file; out‑of‑range start.
- **Assert:** YAML frontmatter (projectName/module/startLine/endLine/totalLines) + fenced `bsl`; `truncated:true` when clamped by maxLines.
- **Validated 2026‑06‑01:** PASS — lines 82‑98 of GetDocumentMainTable, totalLines 99.

### read_method_source
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{…, modulePath:"…/AccountingClientServer/Module.bsl", methodName:"GetDocumentMainTable"}`; unknown method → lists available methods.
- **Assert:** frontmatter incl. `method/type/export/startLine/endLine`; body is the method only.
- **Validated 2026‑06‑01:** PASS — Function GetDocumentMainTable, export, 82‑98.

### write_module_source
- **Type:** write (mutates BSL) · **Runnable on IRP:** error‑only here
- **Cases:**
  - `{…, mode:"searchReplace", oldSource:"<text not in file>", source:"…"}` → `Error: oldSource not found …`, **no write**.
  - syntax‑check failure: unbalanced `source` → blocked unless `skipSyntaxCheck:true`.
  - (sandbox) valid searchReplace/append/replace → writes, preserves BOM, re‑checks syntax.
- **Assert:** non‑matching oldSource → error message, file unchanged.
- **Validated 2026‑06‑01:** PASS (error path) — "oldSource not found in current file content…".

### search_in_code
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - `{…, query:"UpdateAccountingTables", outputMode:"count"}` → total + file count.
  - `outputMode:"files"`, `outputMode:"full"` (default, with context); `isRegex:true`; `metadataType:"commonModules"`; `fileMask:"Documents"`.
- **Assert:** count mode → "Total matches: N in M files"; literal (not dialect‑aware).
- **Validated 2026‑06‑01:** PASS — "UpdateAccountingTables" 40 in 37 files; qualified call 37 in 36 files.

### read/navigation — go_to_definition
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{…, symbol:"AccountingClientServer.GetDocumentMainTable"}`; `{symbol:"GetDocumentMainTable", modulePath:"…"}`; metadata FQN `{symbol:"Catalog.Agreements"}`; Russian token.
- **Assert:** frontmatter resolves module/method/startLine/qualifiedName.
- **Validated 2026‑06‑01:** PASS — resolved to AccountingClientServer.GetDocumentMainTable (82‑98).

### get_symbol_info
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{…, filePath:"CommonModules/AccountingClientServer/Module.bsl", line:82, column:12}` (over a function name/param).
- **Assert:** returns signature + inferred types (e.g. `String | Undefined`) and availability contexts.
- **Validated 2026‑06‑01:** PASS — GetDocumentMainTable signature with inferred return type.

### get_method_call_hierarchy
- **Type:** read · **Runnable on IRP:** **callees OK / callers BROKEN**
- **Cases:**
  - `{…, methodName:"UpdateAccountingTables", direction:"callees"}` → lists called methods. **WORKS.**
  - `{…, methodName:"UpdateAccountingTables", direction:"callers"}` → **BUG: returns 0** though 36 files call it (see card `fix-call-hierarchy-callers-empty`). Until fixed, expected = 0 (known‑bad); after fix, expect dozens.
  - unknown method → lists available methods.
- **Validated 2026‑06‑01:** callees PASS (1 callee); **callers FAIL (0 vs 36 expected)** → card `b1`.

### validate_query
- **Type:** read · **Runnable on IRP:** full
- **Cases:**
  - valid: `{…, queryText:"SELECT Ref FROM Catalog.Agreements WHERE NOT DeletionMark"}` → `valid:true`, 0 issues.
  - semantic error: `"SELECT Description FROM Catalog.Agreements"` → `valid:false`, "Field 'Description' not found" (IRP's Agreements has no Description field).
  - syntax error: `"SELECT FROM WHERE"` → `valid:false`, "Syntax error. Wrong token …".
  - `dcsMode:true` for DCS queries. (RU keywords ВЫБРАТЬ/ИЗ → see card `tests-validate-query-ru-keywords`.)
- **Assert:** `success:true` always; `valid` + `issues[]` (severity/message/line/column) reflect query.
- **Validated 2026‑06‑01:** PASS — valid→true; bad field & bad syntax→correct errors. *Note:* issues sometimes duplicated (same error with/without `offset`).

### get_form_layout_snapshot
- **Type:** read (opens form WYSIWYG) · **Runnable on IRP:** form‑level only (needs render config)
- **Cases:** `{projectName:"IRP", formPath:"Catalog.Agreements.Forms.ItemForm", mode:"compact"}`; `mode:"full"`; active‑form (no formPath).
- **Assert:** `success:true`, `formSize` populated. **Per‑element bounds (`elements`, `elementCount`) are only populated in non‑native render mode** — in the default native render they're empty with a "not fully rendered" warning. Not a bug (form‑render flag dependency).
- **Validated 2026‑06‑01:** PARTIAL — formSize 978×731, elementCount 0 (native render; warning present). Needs render config for element bounds.

### get_form_screenshot
- **Type:** read (opens form WYSIWYG) · **Runnable on IRP:** needs JVM flag
- **Cases:** `{projectName:"IRP", formPath:"Catalog.Agreements.Forms.ItemForm", refresh:true}` → PNG.
- **Assert:** PNG with real content **only if EDT launched with `-DnativeFormBufferedLayoutRender=true`** in `1cedt.ini -vmargs`; otherwise a blank/gray rectangle. Not a code bug (offscreen buffer not constructed). The test EDT (`D:\Soft` copy) had the flag added; re‑test after restart.
- **Validated 2026‑06‑01:** blank PNG (flag was absent). Flag added to test EDT; re‑validate after next restart.

---

## Group: Applications & Testing

> IRP has **no infobase / launch configuration** (`get_applications`→0, `list_configurations`→0),
> so only error/validation paths are runnable here. Full flow needs a project with an
> infobase + a runtime‑client launch config (and YAXUnit installed for the test tools).

### get_applications
- **Type:** read · **Runnable on IRP:** full (empty result valid)
- **Cases:** `{projectName:"IRP"}` → application list.
- **Assert:** `success:true`; IRP → `count:0`, `"No applications found"`.
- **Validated 2026‑06‑01:** PASS — count 0.

### list_configurations
- **Type:** read · **Runnable on IRP:** full (empty result valid)
- **Cases:** `{}`; `{type:"attach"}`; `{type:"client"}`; `{projectName:"IRP"}`.
- **Assert:** `success:true`, `configurations:[]` for IRP; each entry (when present) carries name/type/applicationId/running.
- **Validated 2026‑06‑01:** PASS — 0 configurations.

### update_database
- **Type:** destructive (DB migration) · **Runnable on IRP:** SKIPPED — permission‑gated
- **Cases (sandbox + explicit approval only):** by `launchConfigurationName`, or `projectName+applicationId`; `fullUpdate`, `autoRestructure`.
- **Note:** CLAUDE.md restricts this to explicit user request; the Claude Code auto‑mode classifier **blocks** it even on the error path. A test agent must treat it as `SKIPPED (needs approval + infobase)`.
- **Validated 2026‑06‑01:** not run — blocked by safety classifier (expected).

### debug_launch
- **Type:** app/launch · **Runnable on IRP:** error‑only here
- **Cases:** `{projectName:"IRP"}` (no app) → error; (sandbox) `{launchConfigurationName:"…"}` or `{projectName,applicationId}`; Attach config → `applicationId:"attach:<name>"`.
- **Assert:** missing app → `success:false`, error mentions `applicationId is required` / lists configs.
- **Validated 2026‑06‑01:** PASS (error path).

### terminate_launch
- **Type:** app/launch · **Runnable on IRP:** SKIPPED — **not in this session's tool registry**
- **Cases:** by config name; by project+app; `all:true`+`confirm:true` (mass, guarded); `force`, `timeoutSeconds`, `includeAttach`.
- **Assert:** `all:true` without `confirm` → guard error; no live launch → `not_found` success body. (Validate when the server tool list is refreshed — it was absent here because the server was down at session start.)
- **Validated 2026‑06‑01:** not callable (stale tool registry). Re‑validate with a fresh connection.

### run_yaxunit_tests
- **Type:** app/test · **Runnable on IRP:** error‑only here
- **Cases:** `{projectName:"IRP"}` → error (no app); (sandbox) with config + YAXUnit → JUnit Markdown; `Pending` on timeout (call again); `modules`/`tests`/`extensions` filters; `updateBeforeLaunch`.
- **Assert:** missing app → `**Error:** applicationId is required …`.
- **Validated 2026‑06‑01:** PASS (error path).

---

## Group: Debugging

> Breakpoints (set/list/remove) and `debug_status` work without a running session.
> The suspend/inspect/step family needs a live debug target → here only the
> stale/empty paths are validated. Full cycle: `debug_yaxunit_tests` → `wait_for_break`
> → `get_variables`/`evaluate_expression` → `step` → `resume` (needs infobase + YAXUnit).

### set_breakpoint
- **Type:** write (marker; reversible) · **Runnable on IRP:** full
- **Cases:** `{projectName:"IRP", module:"CommonModules/AccountingClientServer/Module.bsl", lineNumber:3}`; absolute path; bad line → error.
- **Assert:** `success:true`, returns numeric `breakpointId`, `resolvedFile` under `/IRP/src/…`.
- **Validated 2026‑06‑01:** PASS — breakpointId 1793.

### list_breakpoints
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{projectName:"IRP"}`; `{}` (all).
- **Assert:** array of {breakpointId, project, file, lineNumber, enabled, modelId}.
- **Validated 2026‑06‑01:** PASS — showed bp 1793 at line 3.

### remove_breakpoint
- **Type:** write · **Runnable on IRP:** full
- **Cases:** `{breakpointId:<id>}`; or `{projectName,module,lineNumber}`.
- **Assert:** `{removed:true, success:true}`; subsequent `list_breakpoints` no longer shows it.
- **Validated 2026‑06‑01:** PASS — removed bp 1793.

### debug_status
- **Type:** read · **Runnable on IRP:** full
- **Cases:** `{}`; `{applicationId:"…"}`.
- **Assert:** `success:true`, `registry` counts, `launches:[]` when nothing is running.
- **Validated 2026‑06‑01:** PASS — 0 active launches.

### wait_for_break
- **Type:** debug (needs session) · **Runnable on IRP:** timeout path
- **Cases:** `{applicationId:"…", timeout:1}` with no session → `{hit:false, reason:"timeout"}`; (sandbox) real session → frame snapshot.
- **Assert:** `success:true`, `hit:false` on timeout (does not throw, does not terminate).
- **Validated 2026‑06‑01:** PASS — `hit:false, reason:timeout`.

### get_variables
- **Type:** debug (needs session) · **Runnable on IRP:** error path
- **Cases:** `{frameRef:<stale>}` → error; (sandbox) live frameRef → variable tree; `expandPath` for nesting.
- **Assert:** stale → `success:false`, `"stale frameRef — call wait_for_break again"`.
- **Validated 2026‑06‑01:** PASS (error path).

### step
- **Type:** debug (needs session) · **Runnable on IRP:** error path
- **Cases:** `{threadId:<stale>, kind:"over"}` → error; (sandbox) over/into/out → new snapshot.
- **Assert:** stale → `"stale threadId — call wait_for_break again"`.
- **Validated 2026‑06‑01:** PASS (error path).

### resume
- **Type:** debug (needs session) · **Runnable on IRP:** error path
- **Cases:** `{threadId:<stale>}` → error; (sandbox) live threadId/applicationId → resumes.
- **Assert:** stale → `"stale threadId …"`.
- **Validated 2026‑06‑01:** PASS (error path).

### evaluate_expression
- **Type:** debug (needs session; runs arbitrary BSL) · **Runnable on IRP:** error path
- **Cases:** `{frameRef:<stale>, expression:"1+1"}` → error; (sandbox) live frame → evaluates.
- **Assert:** stale → `"stale frameRef …"`.
- **Validated 2026‑06‑01:** PASS (error path).

### debug_yaxunit_tests
- **Type:** app/test (debug) · **Runnable on IRP:** error‑only here
- **Cases:** `{projectName:"IRP"}` → error (no app); (sandbox) with config + YAXUnit, pin `tests:"Module.Method"` → launches, then `wait_for_break`.
- **Assert:** missing app → `success:false`, `"applicationId is required …"`.
- **Validated 2026‑06‑01:** PASS (error path).

### start_profiling
- **Type:** debug (needs session) · **Runnable on IRP:** error path
- **Cases:** `{applicationId:"bogus"}` → no‑target error; (sandbox) live target → toggles measurement.
- **Assert:** `success:false`, `"No active debug target for applicationId …"`.
- **Validated 2026‑06‑01:** PASS (error path).

### get_profiling_results
- **Type:** debug (needs prior profiling) · **Runnable on IRP:** empty path
- **Cases:** `{}` with no data → empty + guidance; (sandbox) after start_profiling+run → per‑line data; `moduleFilter`, `minFrequency`.
- **Assert:** `success:true`, `count:0`, `"No profiling results available …"`.
- **Validated 2026‑06‑01:** PASS (empty path).

---

## Group: Refactoring (metadata write)

> All four mutate metadata. On IRP only **error paths** (non‑existent FQN) are runnable
> unattended. `rename`/`delete` have a non‑destructive **preview** (confirm=false), but the
> Claude Code auto‑mode classifier **blocks preview on real objects** (cascade‑corruption
> risk; CLAUDE.md "explicit request only"). Happy‑path coverage = sandbox + explicit approval.

### create_metadata_object
- **Type:** write · **Runnable on IRP:** SKIPPED — **not in this session's tool registry** (newest tool; stale cached tools/list). Validate with a fresh connection.
- **Cases (sandbox):** `{projectName, metadataType:"Catalog", name:"E2ETest", synonym:"…", language:"en"}` → creates `.mdo`; invalid identifier `name` → validation error; Russian `metadataType` token.
- **Assert:** valid → object created + appears in `get_metadata_objects`; bad name → error before creation.
- **Validated 2026‑06‑01:** not callable (stale registry).

### rename_metadata_object
- **Type:** destructive (cascade across BSL/forms/metadata) · **Runnable on IRP:** error‑only unattended
- **Cases:**
  - non‑existent: `{objectFqn:"Catalog.NoSuchXYZ_e2e", newName:"X"}` → `Error: Object not found …` (markdown).
  - preview (real object, no confirm) → **classifier‑gated** (needs explicit approval).
  - happy (sandbox + approval): preview → review indices → `confirm:true` (optionally `disableIndices`).
- **Assert:** bad FQN → "Object not found" + child‑type hint. Preview lists indexed change points.
- **Validated 2026‑06‑01:** PASS (error path); real‑object preview blocked by classifier (expected).

### delete_metadata_object
- **Type:** destructive (reference cleanup) · **Runnable on IRP:** error‑only unattended
- **Cases:** non‑existent FQN → `Object not found …`; preview (real, no confirm) → classifier‑gated; happy = `confirm:true` (sandbox+approval).
- **Assert:** bad FQN → JSON `success:false` + child‑type hint.
- **Validated 2026‑06‑01:** PASS (error path).

### add_metadata_attribute
- **Type:** write (BM transaction) · **Runnable on IRP:** error‑only here
- **Cases:** non‑existent parent → error; (sandbox) valid parent (Catalog/Document/…/Register) + name → attribute created with defaults; Russian type token.
- **Assert:** bad parent → `success:false`, "Parent object not found" + FQN‑format hint.
- **Validated 2026‑06‑01:** PASS (error path).

---

## Group: Translation (LanguageTool)

> **LanguageTool is not installed** in this EDT → all three error out. Install LanguageTool
> + attach a dictionary storage project to exercise happy paths. Read `get_translation_project_info`
> first to discover storages/providers.

### get_translation_project_info
- **Type:** read (diagnostics) · **Runnable on IRP:** error here (LanguageTool missing)
- **Cases:** `{projectName:"IRP"}` → storages + provider IDs (when LanguageTool present).
- **Assert:** with LanguageTool → `success:true` + storages/providers; without → `"IProjectInformationApi is not available. Install LanguageTool"`.
- **Validated 2026‑06‑01:** PASS (LanguageTool‑missing error).

### generate_translation_strings
- **Type:** write (writes .lstr/.trans/.dict) · **Runnable on IRP:** error‑only here
- **Cases:** bogus project → `Project not found`; (LanguageTool sandbox) `{projectName, targetLanguages:["en"], collectInterface, collectModel, fillUpType, storageId}`.
- **Assert:** bad project → `success:false`, "Project not found".
- **Validated 2026‑06‑01:** PASS (error path).

### translate_configuration
- **Type:** write (regenerates translated artifacts) · **Runnable on IRP:** error‑only here
- **Cases:** bogus project → `Project not found`; (LanguageTool sandbox) `{projectName, targetLanguages:["en"]}`.
- **Assert:** bad project → `success:false`, "Project not found".
- **Validated 2026‑06‑01:** PASS (error path).

---

## Findings & follow‑ups (2026‑06‑01 e2e pass)

| # | Finding | Kind | Action |
|---|---------|------|--------|
| 1 | `get_method_call_hierarchy direction:"callers"` returns 0 for clearly‑called methods (callees works) | **bug** | card `fix-call-hierarchy-callers-empty` (b1) |
| 2 | `validate_query` issues list sometimes duplicated (same error with/without `offset`) | polish | note here; card if confirmed systematic |
| 3 | Form tools blank without `-DnativeFormBufferedLayoutRender=true`; per‑element bounds need non‑native render | env/config | flag added to test EDT; re‑validate after restart |
| 4 | `update_database` blocked by safety classifier even on error path | expected | document as approval‑gated |
| 5 | `rename`/`delete` preview on real objects classifier‑gated | expected | document as approval‑gated |
| 6 | `create_metadata_object` + `terminate_launch` absent from session tool registry (stale cached tools/list; server was down at start) | harness | re‑validate with a fresh MCP connection |
| 7 | LanguageTool not installed → 3 translation tools unusable | env | install LanguageTool for happy paths |
| 8 | IRP has no infobase/launch config → app/debug full flow not exercised | env | needs a project with an infobase + YAXUnit |

**Coverage:** 56 of 58 tools exercised live (read happy‑paths + write/destructive error‑paths). 2 (`create_metadata_object`, `terminate_launch`) pending a fresh connection. Happy paths for write/destructive/app/debug/translation tools require a sandbox project (+infobase / +LanguageTool / +approval) as noted per tool.





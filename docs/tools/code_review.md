# code_review

Review BSL code quality with the BSL Language Server engine: its FULL diagnostic catalog (magic number, cyclomatic/cognitive complexity, method/line length, nesting, naming, unused code, …) — this overlaps with EDT's own v8-code-style checks (get_project_errors), it is not a strict delta over them; use excludeRule to drop rule ids you already get elsewhere. Each finding is a defect to FIX: it carries the rule, severity, Module path and Line, ready for read_module_source / write_module_source — fix each, then re-run code_review to verify. Scope the whole project or one module; filter by severity, rule or excludeRule. Needs the engine jar (see the guide). Full parameters and examples: call get_tool_guide('code_review').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name to review. |
| modulePath | — | string | Optional: narrow the review to a single module, path from src/ (e.g. 'CommonModules/Calc/Module.bsl'); must be a .bsl module. Omit to review the whole configuration - a scoped run cannot see cross-module context, so rules like unused-export are only reliable without it. |
| severity | — | string (one of: error, warning, information, hint) | Optional: minimum severity to report (error > warning > information > hint). Omit to report all. |
| rule | — | string | Optional: report only diagnostics whose rule id contains this substring (e.g. 'Magic', 'Complexity'). |
| excludeRule | — | string | Optional: drop diagnostics whose rule id contains this substring — e.g. to exclude rules you already get from get_project_errors and avoid double-reporting the same issue. |
| limit | — | integer | Max findings; default 100, max 1000 (optional). |

## Guide
Review BSL code quality by running the external BSL Language Server engine over a project (or a single module) and reporting its diagnostics as an actionable table. Every finding is a concrete defect located by `Module path` + `Line` — the same coordinates `read_module_source` and `write_module_source` use — so the intended workflow is **review → fix → re-run to verify**.

## When to use
- To run the BSL Language Server's FULL diagnostic catalog over your code: magic numbers/dates, cyclomatic & cognitive complexity, method/line length, parameter counts, nesting, deprecated calls, unused code, naming, service tags, and well over a hundred more rules.
- As the first step of an automated clean-up loop: run `code_review`, fix each finding in place with `write_module_source`, then run `code_review` again (optionally scoped to the one module) to confirm the finding is gone.
- **This is NOT a strict delta over `get_project_errors`.** Both `code_review` and EDT's own `v8-code-style` (surfaced by `get_project_errors`) are BSL static analyzers with a PARTIALLY SHARED rule set, so some findings here will duplicate ones you already saw there. Use `get_project_errors` for EDT's native check surface, `code_review` for the (larger, partially different) BSL Language Server rule set, or run both to cross-check. Pass `excludeRule` to drop rule ids you already get elsewhere so they stop double-reporting.

## How the findings should be handled
The rows are defects to FIX, not just a report:
- **Mechanical** issues (e.g. `MagicNumber`, `MagicDate`, an unused variable, a missing comment space) can be fixed directly with `write_module_source`.
- **Complexity / nesting / length** issues (e.g. `CyclomaticComplexity`, `CognitiveComplexity`, `NestedTernaryOperator`) usually need a judged refactor — extract a method, invert a guard, split a loop — so apply care and keep behaviour identical.
- After fixing, re-run `code_review` (scope it with `modulePath` for a fast check) to verify the finding is resolved before moving on.

## Parameter details
- `projectName` (required) — the EDT project to review.
- `modulePath` — narrow the review to a single module, given as a path from `src/` (e.g. `CommonModules/Calc/Module.bsl`). Must be a `.bsl` module; a metadata or template file is rejected rather than reviewed to an empty (falsely clean) result. Omit to review the whole configuration. This is the same path form the `Module path` column returns, so you can feed a row straight back in.
  **Narrowing also narrows what the engine can SEE.** A scoped run analyses only that module's folder, so diagnostics that need project-wide context — an exported method reported unused because its only caller lives in another object, for example — can be wrong in a scoped run and right in a full one. Use `modulePath` for fast iteration while fixing; confirm cross-module findings with a full-project run (omit `modulePath`).
- `severity` — minimum severity to report: `error` > `warning` > `information` > `hint`. Omit to report every severity. (These are the engine's LSP severities, independent of EDT's BLOCKER/MAJOR/… taxonomy.)
- `rule` — report only diagnostics whose rule id contains this substring, case-insensitive (e.g. `Magic`, `Complexity`, `Unused`). Handy for a focused pass or a targeted re-verify.
- `excludeRule` — drop diagnostics whose rule id contains this substring, case-insensitive — e.g. to exclude rules you already get from `get_project_errors` and avoid reviewing the same issue twice.
- `limit` — maximum number of rows to render; default 100, capped at 1000. The summary counts above the table reflect the requested SCOPE (the whole project, or just the target module when `modulePath` narrows it) — `severity`/`rule`/`excludeRule` narrow only which rows are DISPLAYED in the table below, not the summary counts, so a filtered report never makes a project look cleaner than it is. When such a filter does remove rows, the same line also states how many findings match them.

## Output
- Markdown. A heading with the scope, a one-line summary of counts per severity, a short instruction to fix-and-re-verify, then a table with columns: `Severity`, `Rule`, `Module path`, `Line`, `Message`, `Docs` (the rule's documentation URL).
- A clean project renders "No BSL code-quality issues found." with no table.
- When filters exclude everything (but the project did have findings) the table is replaced by a "_No findings match the current filters._" note.

## The engine (jar + Java) — one-time setup
`code_review` does not implement any rules; it calls the BSL Language Server engine as a subprocess over its stable CLI (`--analyze --reporter json`). Provide the engine once:
- **Jar** — download `bsl-language-server-<version>-exec.jar` from <https://github.com/1c-syntax/bsl-language-server/releases> and point `EDT_MCP_BSL_LS_JAR` at it (or place it in `<user.home>/bsl-language-server`). The rules are compiled into the jar; to get newer rules, swap in a newer jar — no plugin rebuild.
- **Java** — the `1.x` engine line needs Java 21; the `0.28.x` line runs on Java 17. Set `EDT_MCP_BSL_LS_JAVA` to a suitable `java` executable; if unset, the JRE running EDT is used (Java 17 → pair it with a `0.28.x` jar).
- If the jar or Java cannot be found, the tool returns an actionable error naming the environment variable to set and the download page.

## Which checks run
- The engine reads the project's own `.bsl-language-server.json` (at the project root) if present; otherwise one next to the jar (the "engine home"); otherwise one the engine would find on its own — in the project's `src/` (the directory the engine runs in) or in your home directory; otherwise the engine defaults.
- **`traceLog` is stripped** from whatever config is used. The engine writes that log relative to its working directory, which is inside the project — and `code_review` is a read-only tool, so it must not leave files behind. Every other setting is passed through untouched. If you need the trace, run the engine yourself outside EDT.
- Use that file to enable/disable rules (`"parameters": { "SomeRule": false }`), tune thresholds (`"MagicNumber": { "authorizedNumbers": "-1,0,1" }`) and set the message language (`"diagnosticLanguage": "en"`). Exact per-rule parameter names are on each rule's documentation page (the `Docs` column URL).

## Examples
- Whole project: `{projectName: "MyProject"}`.
- One module, fast re-verify after a fix: `{projectName: "MyProject", modulePath: "CommonModules/Calc/Module.bsl"}`.
- Only the important ones: `{projectName: "MyProject", severity: "warning"}`.
- Only magic numbers: `{projectName: "MyProject", rule: "Magic"}`.
- Skip a rule already covered elsewhere: `{projectName: "MyProject", excludeRule: "SemicolonPresence"}`.

## Notes & gotchas
- Line numbers are 1-based (converted from the engine's 0-based LSP output), matching `read_module_source`/`set_breakpoint`.
- `Module path` is relativized to `src/`; a finding outside `src/` (rare) shows its absolute path instead.
- The engine analyzes files on disk. If you just edited a module through the model, ensure it is exported to disk (the write tools do this) before reviewing, or the review may read a stale file.
- **The whole-project run is bounded at 45 s** — deliberately under what the MCP transport will hold open, so a configuration too large to finish in that window returns this tool's own explanation instead of a bare transport timeout. Scope with `modulePath` for anything that big; a configuration that cannot be analysed within the window cannot be reviewed whole through MCP at all.
- The engine's report is capped at 50 MB; a report larger than that (a pathological run, or a misconfigured/corrupt engine process) is rejected with an actionable error instead of being read into memory — narrow the scope with `modulePath` and re-run.
- A `modulePath` must resolve INSIDE the requested project's own `src/` — an absolute path or one using `..` to point elsewhere is rejected.
- A scoped run is not a smaller version of the full run: see `modulePath` above — rules needing cross-module context are only reliable without `modulePath`.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

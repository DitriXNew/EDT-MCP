Review BSL code quality by running the external BSL Language Server engine over a project (or a single module) and reporting its diagnostics as an actionable table. Every finding is a concrete defect located by `Module path` + `Line` — the same coordinates `read_module_source` and `write_module_source` use — so the intended workflow is **review → fix → re-run to verify**.

## When to use
- To surface code-metric defects EDT's own checks do not raise: magic numbers/dates, cyclomatic & cognitive complexity, method/line length, parameter counts, nesting, deprecated calls, service tags, and more.
- As the first step of an automated clean-up loop: run `code_review`, fix each finding in place with `write_module_source`, then run `code_review` again (optionally scoped to the one module) to confirm the finding is gone.
- Prefer `get_project_errors` when you want EDT's configuration-development standards (`v8-code-style`) — that half is already covered there. `code_review` is the BSL Language Server metric layer on top.

## How the findings should be handled
The rows are defects to FIX, not just a report:
- **Mechanical** issues (e.g. `MagicNumber`, `MagicDate`, an unused variable, a missing comment space) can be fixed directly with `write_module_source`.
- **Complexity / nesting / length** issues (e.g. `CyclomaticComplexity`, `CognitiveComplexity`, `NestedTernaryOperator`) usually need a judged refactor — extract a method, invert a guard, split a loop — so apply care and keep behaviour identical.
- After fixing, re-run `code_review` (scope it with `modulePath` for a fast check) to verify the finding is resolved before moving on.

## Parameter details
- `projectName` (required) — the EDT project to review.
- `modulePath` — narrow the review to a single module, given as a path from `src/` (e.g. `CommonModules/Calc/Module.bsl`). Omit to review the whole configuration. This is the same path form the `Module path` column returns, so you can feed a row straight back in.
- `severity` — minimum severity to report: `error` > `warning` > `information` > `hint`. Omit to report every severity. (These are the engine's LSP severities, independent of EDT's BLOCKER/MAJOR/… taxonomy.)
- `rule` — report only diagnostics whose rule id contains this substring, case-insensitive (e.g. `Magic`, `Complexity`, `Unused`). Handy for a focused pass or a targeted re-verify.
- `limit` — maximum number of rows to render; default 100, capped at 1000. The summary counts above the table always reflect the full report, not the capped table.

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
- The engine reads the project's own `.bsl-language-server.json` (at the project root) if present; otherwise a `.bsl-language-server.json` sitting next to the jar (the "engine home"); otherwise the engine defaults.
- Use that file to enable/disable rules (`"parameters": { "SomeRule": false }`), tune thresholds (`"MagicNumber": { "authorizedNumbers": "-1,0,1" }`) and set the message language (`"diagnosticLanguage": "en"`). Exact per-rule parameter names are on each rule's documentation page (the `Docs` column URL).

## Examples
- Whole project: `{projectName: "MyProject"}`.
- One module, fast re-verify after a fix: `{projectName: "MyProject", modulePath: "CommonModules/Calc/Module.bsl"}`.
- Only the important ones: `{projectName: "MyProject", severity: "warning"}`.
- Only magic numbers: `{projectName: "MyProject", rule: "Magic"}`.

## Notes & gotchas
- Line numbers are 1-based (converted from the engine's 0-based LSP output), matching `read_module_source`/`set_breakpoint`.
- `Module path` is relativized to `src/`; a finding outside `src/` (rare) shows its absolute path instead.
- The engine analyzes files on disk. If you just edited a module through the model, ensure it is exported to disk (the write tools do this) before reviewing, or the review may read a stale file.
- A large configuration can take a while to analyze; scope with `modulePath` for quick iterative checks.

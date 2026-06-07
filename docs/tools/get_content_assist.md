# get_content_assist

Get code-completion proposals at a 1-based line/column in a BSL module - the members, globals and variables valid at that caret (e.g. after a '.'). May return a 'not ready' error while the editor loads; just retry. Full parameters and examples: call get_tool_guide('get_content_assist').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name |
| modulePath | — | string | BSL module path under src (e.g. 'CommonModules/MyModule/Module.bsl'); alias: filePath |
| filePath | — | string | Deprecated alias for modulePath |
| line | yes | integer | Line number (1-based) |
| column | yes | integer | Column number (1-based) |
| limit | — | integer | Max proposals to return (default: from preferences, max 1000) |
| offset | — | integer | Skip first N matching proposals for pagination (default: 0) |
| contains | — | string | Keep proposals whose display string contains any of these substrings (comma-separated, e.g. 'Insert,Add') |
| extendedDocumentation | — | boolean | Include full documentation per proposal (default: false) |

## Guide
Returns the Xtext content-assist (code-completion) proposals EDT would offer at a specific position in a BSL module. The tool opens (or activates) the module in the EDT editor, moves the caret to the given line/column, and runs the same completion engine the IDE uses. Output is JSON.

## When to use
- Discover the members (methods/properties) valid after a `.` on an object or manager - put the caret right after the dot.
- List the global functions, keywords, local variables and parameters in scope at a position.
- Confirm the exact spelling/casing of an identifier before writing code.

## Position semantics
`line` and `column` are **1-based** (line 1, column 1 is the very start of the file), matching what an editor's status bar shows. The proposals are computed for the offset *before* that column, i.e. the caret sits just to the left of the character at `column`. To complete members of `Object.`, place the caret one column past the dot. A position outside the document returns an error.

## Parameters
- `projectName` (required) - EDT project name.
- `modulePath` (required) - module path relative to the project `src/` folder, e.g. `CommonModules/MyModule/Module.bsl` or `Catalogs/Products/Forms/ItemForm/Module.bsl`. The deprecated alias `filePath` is accepted; if both are given `modulePath` wins. One of them is required.
- `line` (required) - 1-based line number.
- `column` (required) - 1-based column number. Both accept integer (`33`) or decimal (`33.0`) text; both must be >= 1.
- `limit` - max proposals returned; defaults to the configured tool preference (100), clamped to a maximum of 1000.
- `offset` - skip the first N proposals that pass the `contains` filter, for pagination; default 0.
- `contains` - comma-separated, case-insensitive substrings; a proposal is kept if its display string contains *any* of them (e.g. `Insert,Add`). Filtering is applied before `offset`/`limit`.
- `extendedDocumentation` - when `true`, each proposal also carries its full documentation, converted from HTML to Markdown; default `false` returns only the display string (much smaller and faster).

## Result shape
A JSON object with `file`, `line`, `column`, `totalProposals` (count before any filter), `filteredOut` (removed by `contains`), `skipped` (consumed by `offset`), `returnedProposals`, and `proposals` - an array of `{ displayString[, documentation] }`. `documentation` is present only when `extendedDocumentation=true` and non-empty.

## Examples
- Members after a dot: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", line: 12, column: 15}`.
- Only `Add*`/`Insert*`, with docs: `{projectName, modulePath, line, column, contains: "Add,Insert", extendedDocumentation: true}`.
- Second page of 50: `{projectName, modulePath, line, column, limit: 50, offset: 50}`.

## ru/en note
Proposal display strings come straight from the EDT engine, so they follow the module's own BSL dialect (English or Russian keywords/built-ins). The `contains` filter is a literal, case-insensitive substring match - it is NOT dialect-aware, so filter on the spelling actually used in that module.

## Gotchas
- The target must be a BSL module backed by an Xtext editor; other file types return an error.
- On a cold/rapid call the editor's Xtext resource may still be parsing; the tool waits briefly, but can return `Xtext editor not ready ... Please retry` rather than an empty (misleading) success - just call again.
- An empty `proposals` array with a successful result means the engine genuinely offered nothing at that position (check the line/column).

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*

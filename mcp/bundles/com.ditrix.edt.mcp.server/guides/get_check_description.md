Returns the full human-readable explanation of an EDT validation check - what it flags, why it matters, and how to fix it - from a folder of check-description Markdown files. Use it to understand a marker that `get_project_errors` reported.

## When to use
- `get_project_errors` showed a check code/id and you want the detailed rationale and fix.
- Deciding whether a warning is worth fixing or can be justified.

## Parameter details
- `checkId` (required) - either the symbolic dash-cased id (e.g. `begin-transaction`, `ql-temp-table-index`) or the short UID code shown by `get_project_errors` (e.g. `SU23`).
- `projectName` - needed only to resolve a short UID to its symbolic id; ignored when `checkId` is already symbolic.

## What you get
The check's Markdown documentation (explanation, examples, how to fix), returned as-is.

## Notes & gotchas
- **Precondition:** a check-descriptions folder must be configured in EDT Preferences → MCP Server. Without it the tool returns a clear configuration error - this is setup, not a code bug.
- If you only have the short UID (e.g. `SU23`), pass `projectName` too so it can be resolved; otherwise pass the symbolic id directly.
- A not-found id returns an actionable error naming the value; check the spelling against the code in `get_project_errors`.

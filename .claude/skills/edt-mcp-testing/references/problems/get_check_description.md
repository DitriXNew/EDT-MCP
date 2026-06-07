# get_check_description — how to test

**Purpose.** Returns the human-readable documentation Markdown for a single EDT check, looked up by its check ID. It is **not** a live model query: the tool reads a `<checkId>.md` file from a folder configured in Preferences -> MCP Server ("Check descriptions folder") and returns the file contents verbatim. It exists to expand a check code seen in `get_project_errors` (the `Check code` column, e.g. `bsl-legacy-check-simple-statement`, `module-structure-method-in-regions`, `common-module-type`) into an explanation with examples and a fix. Source: `GetCheckDescriptionTool.java` — `getCheckDescription(checkId)` reads `PreferenceConstants.PREF_CHECKS_FOLDER`, resolves `<folder>/<checkId>.md` (then a lowercased fallback), and returns `Files.readString(...)`.

**Preconditions.**
- Live EDT (non-elevated copy), MCP on `:8765`, workspace `D:\WS\EDT`. `list_projects` State for `TestConfiguration` should be `ready` — but note this tool does **not** touch any project or the model; the `checkId` is the only required input and there is no `projectName` parameter (despite the task hint, the schema has only `checkId`).
- The check-docs feature is **opt-in and OFF by default**: `DEFAULT_CHECKS_FOLDER` is the empty string (feature disabled). Real content is returned **only if** an operator has set a non-empty, existing folder in Preferences -> MCP Server that contains a matching `<checkId>.md`. On the current `D:\WS\EDT` workspace this folder is **not configured** (see Result), so every call returns the "not configured" message.
- To get a real check ID, first run `get_project_errors` on `TestConfiguration` and read the `Check code` column. Real codes present there today: `common-module-type`, `org.eclipse.xtext.diagnostics.Diagnostic.Syntax`, `bsl-legacy-check-simple-statement`, `bsl-legacy-check-static-feature-access`, `bsl-legacy-check-library-module`, `md-legacy-emf-check`, `md-list-object-presentation`, `module-structure-method-in-regions`, `use-non-recommended-method`. Their `Has docs` column is `false` here precisely because the folder is unset.
- Read-only. No model mutation, no disk write, no revert needed (it only reads a doc file).

**Call (real).** Use a check code observed in `get_project_errors`:
```
get_check_description(checkId="module-structure-method-in-regions")
```

**Result.** `ResponseType.MARKDOWN`, delivered as a resource (`embedded://module-structure-method-in-regions.md` — `getResultFileName` names the file `<checkId>.md`). On the current workspace the folder is unset, so the **real, verified** output is the not-configured message (plain Markdown, NOT a structured error — see Gotchas):
```
**Error:** Check descriptions folder is not configured.

Please set it in Preferences -> MCP Server.
```
Representative shape **when the folder IS configured and the file exists** (from source — the tool returns the file body verbatim, so the layout is whatever the author wrote; a check doc typically looks like):
```
# module-structure-method-in-regions

Methods must be placed inside one of the standard module regions
(Variables, EventHandlers, Private, Initialize).

## Why
...

## How to fix
Move the procedure/function into the appropriate `#Region`.
```
Representative shape for a **configured folder but a missing/unknown check id** (from source):
```
**Error:** Check description not found for: no-such-check
```

**Gotchas.**
- **It reads files, not the model.** A blank or "not configured"/"not found" result is almost always a missing/unset folder or a missing `<checkId>.md`, NOT a code bug and NOT a stale index. The result reflects the Preferences folder contents, so it does not depend on which project is open or its State.
- **Folder OFF by default.** `DEFAULT_CHECKS_FOLDER` = `""`. Until an operator points the preference at a real folder of `*.md` files, every call returns `**Error:** Check descriptions folder is not configured.` This is the expected baseline on `D:\WS\EDT`.
- **Error contract is informational here, not structured.** All of this tool's failures (`checkId parameter is required`, `Check descriptions folder is not configured.`, `Check descriptions folder does not exist: <path>`, `Check description not found for: <id>`, `Failed to read check description: <io>`) are returned as ordinary `**Error:** …` Markdown with `success:true` / `isError:false` — they are treated as informational "not-found" output, not as the `{success:false,error,isError:true}` genuine-failure envelope. Do not assert on a structured error shape for this tool; match a delimiter-free substring of the Markdown (e.g. `not configured`, `not found for`).
- **checkId is sanitized against path traversal.** Lookup strips anything outside `[a-zA-Z0-9_-]`; if the sanitized id differs from the input, the file is treated as not found. So a code containing dots — e.g. `org.eclipse.xtext.diagnostics.Diagnostic.Syntax` or `md-legacy-emf-check` (dotted Xtext/EMF codes seen in `get_project_errors`) — will **never** resolve to a file (dots are removed, the sanitized id no longer equals the input → `not found`). Only simple dash/underscore codes like `module-structure-method-in-regions` can have a doc file.
- **Case fallback.** Lookup tries `<checkId>.md`, then `<checkId.toLowerCase()>.md`. There is no fuzzy match and no listing of valid alternatives in the not-found message.
- **Flaky output channel.** If the returned text drops to a bare `Error`/`Done` or looks garbled, do NOT retry-spam — cross-check the EDT log `D:\WS\EDT\.metadata\.log` (the tool logs IO/unexpected errors via `Activator.logError`) and re-verify the Preferences folder value.
- **Bilingual.** The check ID is a programmatic, language-neutral token (lowercase-dashed) — it is never localized; pass exactly what `get_project_errors` shows in the `Check code` column. The returned documentation body is whatever language the author wrote the `.md` file in (folder content), independent of EDT UI locale and of any tool argument.

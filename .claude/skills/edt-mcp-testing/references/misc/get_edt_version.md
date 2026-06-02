# get_edt_version — how to test

**Purpose.** Return the running 1C:EDT product version as a single plain-text string (e.g. `2026.1.1.1`). It takes no arguments and touches no project, so it is the cheapest **liveness probe** for the workbench — if it answers with a version, the MCP server on `:8765` is up and serving tools. The redeploy script and the navigation SETUP both use it exactly this way ("confirm with `get_edt_version`, not the exit code").

**Preconditions.** None beyond a running MCP server (`:8765`). No `projectName`, no open editor, no cursor, no index — it does not read the model at all, so it does not depend on any project being `ready` and it never mutates anything (no revert needed). It works the instant the OSGi runtime is up, even while every project still shows `State=building` after a `-clean` relaunch. The input schema is the empty object `{}`.

**Call (real, vs TestConfiguration).** No project is involved; the call is identical regardless of which configuration is loaded:
```
get_edt_version()
```
(No arguments. The tool ignores any extra params — its input schema is `{}`.)

**Result.** Real output from the live workspace (EDT 2026.1.1, captured clean):
```
[Resource from EDT-MCP-Server at embedded://get_edt_version.md] 2026.1.1.1
```
The payload is just the version token `2026.1.1.1`. The tool's `execute()` returns a bare `String` (`ResponseType.TEXT`) — **not** JSON and **not** a markdown table; the server wraps that string as an MCP resource (`embedded://get_edt_version.md`) at the transport layer, the same way `list_projects` is delivered. There is no `{success:true,...}` envelope and no markdown formatting to parse — read the trailing string directly.

The exact shape depends on how `GetEdtVersionTool.getEdtVersion()` resolves the version (it tries methods in order and returns the first hit):
- **`eclipse.buildId` system property** (Method 1) — returned verbatim, e.g. `2026.1.1.1` or `2025.2.0.454`. This is what the live workbench returns; note it is the raw build id with **no** marketing parenthetical.
- **Eclipse product / EDT RCP bundle** (Methods 2–4, fallbacks) — formatted as `<marketing> (<osgi-version>)`, e.g. `2025.2.0 (2.5.0.v202512221402)`. `convertToMarketingVersion` derives `YYYY.<1|2>.0` from the `.vYYYYMM…` qualifier (month ≤ 6 → release `1`, else `2`).
- **`eclipse.product` system property** (Method 5) — a product id string, if nothing above matched.
- **`Unknown`** — only if every method fails or an exception is caught (logged via `Activator.logError`).

So a representative result is either a plain `2026.1.1.1`-style token (live case) or a `2025.2.0 (2.5.0.v…)` token; treat any non-empty version-looking string as a healthy probe.

**Gotchas.**
- **Plain text, not JSON/markdown.** Do not look for a `success` field or a table. The whole payload is the version string after the `embedded://get_edt_version.md` resource marker.
- **Liveness probe semantics.** A clean version string = server up. An empty/garbled echo does **not** prove the server is down — see the flaky-channel note. Conversely `Unknown` means the server is up but could not resolve a version (a real but rare internal condition), not a transport failure.
- **No readiness dependency.** Unlike resolution/AST/metadata tools, this returns correctly even while projects are still indexing (`State=building`). It is the right call to make immediately after `D:\Soft\edt-redeploy.ps1` (which may `exit 1` yet print `MCP server UP on 8765`).
- **Error contract.** There are no arguments to validate, so the structured `{"success":false,"error":…}` / `isError:true` path is effectively unreachable from the caller side; any genuine resolution failure surfaces as the literal text `Unknown`, not an error envelope.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done` instead of a version), do **not** retry-spam — the version is constant per running instance. Re-verify independently via the EDT log `D:\WS\EDT\.metadata\.log` (full request/response logged there) or the EDT Help → About dialog.
- **No bilingual concern.** Output is a fixed version token with no 1C metadata, no synonyms, no TYPE token — nothing here is translated or dialect-sensitive.

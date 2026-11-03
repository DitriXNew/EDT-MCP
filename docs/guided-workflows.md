# Guided EDT workflows (#339)

Launch EDT with `EDT_MCP_GUIDED_WORKFLOWS=true` and reconnect the MCP client to
enable this optional, read-only surface. The variable is read when the protocol
handler is created; changing it requires restarting the server. It adds no tools
and leaves the default `tools/list` unchanged. Without the flag, prompt and
completion methods remain unsupported and `resources/templates/list` returns an
empty list. Existing `guide://` resources continue to work.

## First-release contract

One prompt and two resource templates are selected. Project health has an existing
bounded problem reader and a complete workflow usable through a prompt even when
a client lacks a resource-template picker. Project-scoped guides reuse the existing
how-to renderer. Debug/test setup would need a live infobase, while metadata context
depends on the owner decision on #516; neither is part of this release. No separate
metadata discovery engine or index is introduced.

| Surface | Stable identifier | Arguments | Result |
| --- | --- | --- | --- |
| Prompt | `edt_project_health` | Required string `projectName`; no other arguments | Three user messages: review instructions, embedded current problem report, embedded existing `get_project_errors` guide |
| Resource template | `edt://projects/{projectName}/problems` | `projectName` | One `text/markdown` resource containing `get_project_errors` with an explicit 100-row limit |
| Resource template | `edt://projects/{projectName}/guides/{guideName}` | `projectName`, `guideName` | One `text/markdown` resource from `GuideRenderer`; `guideName` is a currently visible, enabled tool name |

Prompt description: “Review current EDT project problems and propose a prioritized
repair plan without changing the project.” The project argument selects an
accessible, open project in the EDT server workspace. The returned instructions
ask the agent to summarize problems, distinguish missing/building state from an
empty report, group and prioritize causes, cite reported locations, propose
specific read-only follow-ups when needed, and return a repair/validation plan.
The prompt explicitly requires a separate user request before any modification,
clean, rebuild, or database update. Report text is data, not instructions.

`get_project_errors` must be enabled for the prompt and problems template to be
listed or used. Tool enablement is rechecked on each request. Progressive disclosure
continues to filter guide names; the health prompt can embed its own reader guide
through the existing `guide://get_project_errors` resource even when the reader's
toolset has not been revealed. Guides never execute their associated tools.

Each URI argument is a single UTF-8 path segment. Percent-encode spaces, Unicode,
and reserved characters with RFC 6570 simple variable expansion. A literal `+`
remains a plus, not a space. Empty, dot, dot-dot, slash, backslash, control-character,
malformed UTF-8, and over-255-character arguments are rejected. Queries, fragments,
extra path segments, and other authorities are rejected. Invalid arguments and
unknown prompts/templates return JSON-RPC `-32602`; unavailable project/guide
resources return `-32002`. An existing reader's failure returns `-32603`, preserving
its actionable explanation rather than returning a successful empty report.

## Completion and access boundary

**MCP completion applies only to prompt and resource-template arguments. It does
not autocomplete ordinary tool arguments.** Use `ref/prompt` with
`name: "edt_project_health"` or `ref/resource` with an exact template URI. The
supported argument names are `projectName` and, on the guides template, `guideName`.
Provide `context.arguments.projectName` to complete guides. Without an accessible
project context guide completion returns an empty list. Completion values are raw
argument strings; percent-encode them when expanding a URI.

Completion filters by case-insensitive substring, ranks exact matches before
prefixes before other substrings, then sorts case-insensitively with an ordinal
tie-break. Responses contain at most 50 values, `total` and `hasMore`. There is no
fuzzy subsequence matching. Prefixes/context strings are limited to 255 characters.
A server-wide budget permits 100 completion requests per second; excess requests
return `-32000` with a retry/debounce instruction. This limit is shared across clients.

Project candidates are enumerated through `ProjectContext` and filtered by Eclipse
`IProject.isAccessible()` **before their names are read**. Reads also require an
accessible project. A long problem read rechecks project access before returning.
Completion, problem reports and guides are not cached: each request observes current
workspace access and tool settings. Closing/removing a project or disabling a tool
therefore requires no cache invalidation. Clients should debounce requests and
refresh their own completion caches when context changes.

The existing server access boundary is the authorized EDT workspace: all clients
authorized to connect to one server share that workspace and its enabled tools.
There is no per-client project ACL or roots-based authorization in this server.
Client roots are not treated as credentials. If separate client project scopes are
required, the owner must define that authorization policy before enabling this
feature for those clients; use separate EDT workspaces/server instances meanwhile.

## Client discovery and compatibility

Discovery checked against primary documentation/source on 2026-11-03. These are
implementation/documentation findings, **not measured client UI acceptance**.

| Client | Prompts | Resources / templates | Argument completion |
| --- | --- | --- | --- |
| Claude Code | Documented MCP prompt commands | Documented resource mentions and resource read/list tools; template picker not confirmed | Not documented; needs live acceptance |
| VS Code / GitHub Copilot (built-in chat MCP client) | Documented prompt commands | Documented resource picker; templates implemented in source | Prompt and template `completion/complete` implemented in source with `context.arguments` |
| Cursor IDE | Documented support | Documented resources; template picker not confirmed | Not documented; needs live acceptance |

Sources: [Claude Code MCP](https://code.claude.com/docs/en/mcp),
[VS Code MCP](https://code.visualstudio.com/docs/agent-customization/mcp-servers),
[VS Code prompt/template implementations](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/mcp/common/mcpServer.ts),
[Cursor MCP](https://cursor.com/docs/mcp).
Do not infer Copilot CLI, Cursor CLI, or Claude connector support from IDE support.

The wire surface uses fields common to every currently negotiated server version
(`2024-11-05` through `2025-11-25`). The `completions` capability is advertised only
from `2025-03-26`, when that capability was introduced. Older clients can still call
completion without context; dependent guide completion requires the later contextual
request shape. A request for `2026-07-28` still negotiates down to `2025-11-25`.
No subscription or change notification API is added, and no dependency on legacy
`resources/subscribe` or `resources/unsubscribe` is introduced. Future notifications
must use `2026-07-28` `subscriptions/listen` after that protocol is supported.

Protocol references: [prompts](https://modelcontextprotocol.io/specification/2025-11-25/server/prompts),
[resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources),
[completion](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion).

## Live acceptance

1. Start EDT with the opt-in flag and a ready `TestConfiguration`, with
   `get_project_errors` enabled. Connect each target client and record its version
   and negotiated protocol. Use `initialize` then `notifications/initialized`,
   retaining the session ID for subsequent HTTP calls. Expect `prompts` and
   `completions` capabilities on `2025-11-25`, no resource subscription capability.
2. `prompts/list {}` returns `edt_project_health` with required `projectName`.
   `resources/templates/list {}` returns exactly the two templates above.
3. `completion/complete {"ref":{"type":"ref/prompt","name":"edt_project_health"},"argument":{"name":"projectName","value":"Test"}}`
   includes `TestConfiguration` when accessible. Repeat with
   `ref: {"type":"ref/resource","uri":"edt://projects/{projectName}/problems"}`.
4. `completion/complete {"ref":{"type":"ref/resource","uri":"edt://projects/{projectName}/guides/{guideName}"},"argument":{"name":"guideName","value":"get_project_errors"},"context":{"arguments":{"projectName":"TestConfiguration"}}}`
   returns exactly `get_project_errors` when visible. An inaccessible/missing
   context returns `values: []`, `total: 0`, `hasMore: false`.
5. `resources/read {"uri":"edt://projects/TestConfiguration/problems"}` returns
   a Markdown validation snapshot without a `tools/call`. Read
   `edt://projects/TestConfiguration/guides/get_project_errors` and compare its text
   with `guide://get_project_errors`.
6. `prompts/get {"name":"edt_project_health","arguments":{"projectName":"TestConfiguration"}}`
   returns instructions plus the embedded live report and guide. Select the prompt
   in each client's MCP prompt UI and verify the agent produces a prioritized plan
   without writing. In VS Code also exercise template expansion and completion in
   the resource picker. Record unsupported/missing UI instead of claiming success.
7. Close a disposable project in EDT after completing its name. Repeat completion:
   its name must disappear immediately; its resource read must return `-32002`.
   Reopen it and confirm it reappears. Do this in a separate fixture/workspace if
   other acceptance work depends on the project remaining open.
8. `resources/read {"uri":"edt://projects/%2e%2e/problems"}` returns `-32602`;
   a valid-shaped missing-project URI returns `-32002`. Missing prompt arguments
   and `ref/tool` completion return `-32602`. Restart without the opt-in flag:
   no prompt/completion capabilities, templates empty, default tool catalog unchanged.

Automated live checks (not run by the implementer): launch EDT with the flag,
then from `tests/e2e` run `python -m tools.test_guided_workflows`, or use
`run_all.py --filter guided_workflows` under the normal fixture orchestrator.
The Java suite tests wire dispatch, access revocation, strict URI parsing,
fresh reads, ordering/limits, rate reset, and existing-service delegation.

`tests/conformance/baseline.yml` is deliberately unchanged: default-off conformance
still has no prompts/completion and the named fixture scenarios are not proved by
this production workflow. No pinned gap is claimed closed without a live official
conformance run. The opt-in workflow and actual client UI checks above remain
orchestrator acceptance work.

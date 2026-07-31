# validate_xdto_package

Validate a single XDTO package by running EDT's OWN configuration validation (the same check engine behind get_project_errors) scoped to that package, and return a one-line verdict plus any problems found (e.g. a dangling reference to a deleted ObjectType). The verdict has THREE outcomes: valid, problems found, or - when NOTHING matched but a marker's location could not be resolved - undecided, which asks for revalidate_objects and another run rather than asserting validity. It reflects the LATEST validation state already computed by EDT (reads existing markers) rather than forcing a fresh compile; run revalidate_objects first if you need up-to-the-second results. Does not implement any XDTO-specific rule itself - it is a scoped view over get_project_errors. Full parameters and examples: call get_tool_guide('validate_xdto_package').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | FQN of the XDTO package to validate, as 'XDTOPackage.<Name>' (required). Must already exist; list the packages with get_metadata_objects(metadataType: 'xdtoPackages'). |
| limit | — | integer | Max problem rows to report; default 100, max 1000 (optional) |

## Guide
Runs EDT's own configuration validation (the same check engine and marker set `get_project_errors` exposes) scoped to a single XDTO package, and reports a one-line verdict (valid / problems found / undecided - see Output). It is a thin, read-only wrapper: the `fqn` you pass is checked to resolve to an `XDTOPackage` top object, then the request is delegated to `get_project_errors` with an `objects` filter of `[fqn]`, and the returned Markdown is reframed with a one-line verdict.

## When to use
- After authoring/editing an XDTO package (`create_metadata` / `modify_metadata` / `delete_metadata` on `XDTOPackage.<Name>...`), confirm it is still valid - e.g. after deleting an `ObjectType` that another `Property` referenced.
- As a scoped alternative to `get_project_errors` when you only care about ONE package and want the "is it valid" question answered directly instead of reading a raw problem table.

## Parameter details
- `projectName` - EDT project name (required).
- `fqn` - the package to validate, as `XDTOPackage.<Name>` (required). Must already exist - list the configuration's packages with `get_metadata_objects` (`metadataType: 'xdtoPackages'`, or the type name `XDTOPackage` / `ПакетXDTO`). An FQN that does not resolve, or resolves to something other than an XDTOPackage (e.g. a Catalog, or an `ObjectType`/`Property` member FQN), is rejected with an actionable error - point this tool at the PACKAGE, not a member inside it.
- `limit` - max problem rows; default 100, max 1000.

The verdict always considers EVERY severity (a severity-filtered "no matches" would not be a validity guarantee), so there is intentionally no `severity` parameter - use `get_project_errors` if you need to filter by severity.

## Output
Three outcomes, not two - a client that branches on the verdict line must handle all of them. The undecided one is reached ONLY when nothing matched: a matched problem always reports "problems found", whether or not another marker was unresolvable.
- Valid: `**XDTO package `XDTOPackage.<Name>` is valid** — no problems reported.` with no table below it.
- Invalid: `**XDTO package `XDTOPackage.<Name>`: problems found**` followed by the SAME Markdown problem table `get_project_errors` renders (`Description` / `Location` / `Module path` / `Line` / `Check code`).
- UNDECIDED: `**XDTO package `XDTOPackage.<Name>`: no problems matched, but some markers could not be checked** — run revalidate_objects and validate again.` No problem matched the package, but at least one marker's LOCATION could not be resolved, so whether it belongs to this package is unknown - the package is NOT asserted valid. This is what a stale marker index looks like after an edit: call `revalidate_objects` (or `clean_project`) and validate again; if it persists, read the raw markers with `get_project_errors`.

## Gotchas
- This reads EDT's ALREADY-COMPUTED validation markers - it does not force a fresh compile/revalidation. If you just made an edit and want up-to-the-second results, call `revalidate_objects` (or `clean_project`) first, then `validate_xdto_package`.
- The scoping uses the RESOLVED package's canonical FQN with EXACT (segment-boundary) matching: a problem on the package itself or on a member strictly under it (an `ObjectType` / `Property`, whose presentation is `XDTOPackage.<Name>....`) is included, while a prefix-sharing SIBLING package (`XDTOPackage.<Name>2`) is not - so the verdict is about THIS package only. (`get_project_errors` on its own uses looser substring matching; this tool opts into the exact mode.)
- This tool implements NO XDTO-specific validation rule of its own; it is strictly a scoped view over EDT's existing checks. It will not catch anything `get_project_errors` (with the matching `objects` filter) would not also show.

## Examples
- `{projectName: "MyConfig", fqn: "XDTOPackage.Orders"}` - full validation, default limit.
- `{projectName: "MyConfig", fqn: "XDTOPackage.Orders", limit: 500}` - raise the reported-row cap.
- A non-package FQN (`{projectName: "MyConfig", fqn: "Catalog.Products"}`) is rejected: use `get_project_errors` directly for a non-XDTO-scoped query.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

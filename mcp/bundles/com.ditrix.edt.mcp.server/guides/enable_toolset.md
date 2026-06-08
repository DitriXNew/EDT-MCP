Reveal (or hide) tool groups when progressive tool disclosure is on, so the tools you need show up in `tools/list` without the full list being exposed all the time. The companion of `list_toolsets`.

## When to use
- A tool you want is hidden: call `list_toolsets`, find its toolset id, then reveal it here.
- To tidy up by hiding toolsets you are done with (`disable=true`).

## Parameter details
- `toolsets` (required) - one or more toolset ids, e.g. `["code","debug"]`. Get valid ids from `list_toolsets`.
- `disable` - set `true` to hide the listed toolsets instead of revealing them (default `false`).

## What you get
JSON: `action` (`enabled`/`disabled`), `applied` (ids changed), `invalid` (unknown ids), `ignored` (ids that can't be toggled - i.e. `core`), `visibleToolsets` (what's visible after the change), `progressiveDisclosure`, and a `note` with the next step.

## Notes & gotchas
- **Re-request `tools/list` after revealing** to actually see the new tools. If your client holds an open SSE stream the server also pushes `notifications/tools/list_changed` automatically.
- When progressive disclosure is **off**, all tools are already listed - the change is recorded but has no visible effect until you turn disclosure on in EDT Preferences → MCP Server.
- The `core` toolset is always visible; asking to toggle it is ignored, not an error. All-unknown ids return an actionable error pointing you back to `list_toolsets`.

The catalogue of tool groups (toolsets) behind progressive tool disclosure. Use it to discover which toolset id to hand to `enable_toolset` when the tool you want isn't currently in `tools/list`.

## When to use
- A tool you expected isn't listed - find the toolset it belongs to so you can reveal it.
- To understand how the tools are grouped, and which groups are currently visible.

## Parameter details
None.

## What you get
JSON: `progressiveDisclosure` (on/off), `count`, and `toolsets` - each with `id`, `title`, `description`, `tools` (member names), `toolCount`, `visible`, and `core`. A `note` field spells out the next step for the current mode.

## Notes & gotchas
- When progressive disclosure is **off** (the default) every toolset reports `visible: true` because the full tool list is already exposed - `enable_toolset` then has nothing to do.
- The `core` toolset is always visible and cannot be hidden.
- Pair with `enable_toolset` (reveal/hide) and re-request `tools/list` afterwards.

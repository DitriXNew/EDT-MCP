The version of 1C:EDT this MCP server is running inside, as a plain string.

## When to use
- You need to know which EDT release you are driving - for version-specific behaviour, bug reports, or to confirm a setup before running version-sensitive tools.
- As a quick liveness check that the server is attached to a real EDT.

## Parameter details
None.

## What you get
A short text line such as `2025.2.0 (2025.2.0.v202506...)` - the marketing version followed by the raw OSGi build version in parentheses - or just the raw version when the marketing form cannot be derived. Returns `Unknown` if the version cannot be determined at all.

## Notes & gotchas
- For the broader picture (port, protocol version, plugin version, enabled tool counts, render flags) use `get_server_status`, which includes this same EDT version.

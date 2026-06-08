A self-diagnosis snapshot of the running MCP server. Reach for it when something behaves oddly and you want the facts instead of guessing - especially a blank form screenshot or a JSON tool that came back as plain text.

## When to use
- `get_form_screenshot` / `get_form_layout_snapshot` returned blank - check the form-render JVM flags here.
- A JSON-response tool gave you plain text - check `plainTextMode`.
- You want to confirm the port, protocol version, plugin/EDT version, or how many tools are enabled vs. registered (e.g. when progressive disclosure is hiding tools).
- Quick "is the server actually up and reachable" check.

## Parameter details
None.

## What you get
JSON with: `port`, `running`, `protocolVersion`, `pluginVersion`, `edtVersion`, `enabledTools` / `totalTools`, `plainTextMode`, `checksFolderConfigured`, `authEnabled`, and `formRenderFlags` (the two render flags `nativeFormBufferedLayoutRender` and `nativeFormLayoutRender` with their boolean states).

## Notes & gotchas
- Secrets are never exposed: you get only the `authEnabled` boolean (never the token) and `checksFolderConfigured` (never the folder path).
- If a form screenshot is blank, look for `nativeFormBufferedLayoutRender=true` - without it the layout renderer has no offscreen buffer and screenshots stay empty (fixed by adding the flag to the EDT `-vmargs`, not by changing code).
- `enabledTools` < `totalTools` is normal when progressive disclosure is on - use `list_toolsets` / `enable_toolset` to reveal more.

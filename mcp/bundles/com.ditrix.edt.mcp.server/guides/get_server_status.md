A self-diagnosis snapshot of the running MCP server. Reach for it when something behaves oddly and you want the facts instead of guessing - especially a blank form screenshot or a JSON tool that came back as plain text.

## When to use
- `get_form_screenshot` / `get_form_layout_snapshot` returned blank - check the effective form-render modes here.
- A JSON-response tool gave you plain text - check `plainTextMode`.
- You want to confirm the port, protocol version, plugin/EDT version, or how many tools are enabled vs. registered (e.g. when progressive disclosure is hiding tools).
- Quick "is the server actually up and reachable" check.

## Parameter details
None.

## What you get
JSON with: `port`, `running`, `protocolVersion`, `pluginVersion`, `edtVersion`, `enabledTools` / `totalTools`, `plainTextMode`, `checksFolderConfigured`, `authEnabled`, and `formRenderFlags`. The latter contains `nativeFormLayoutRender` followed by `nativeFormBufferedLayoutRender`. Each flag always has `effective` (`on`, `off`, or `unknown`), the mode captured at EDT startup; it can also have `requested`, the raw system-property string currently set (omitted when unset), and `forcedAtRuntime: true` when the known live mode differs from the known startup snapshot.

## Notes & gotchas
- Secrets are never exposed: you get only the `authEnabled` boolean (never the token) and `checksFolderConfigured` (never the folder path).
- Judge how EDT was launched by `effective`, which is captured before this plugin's screenshot path can mutate a live flag. `requested` is the system property now; this plugin can overwrite it after startup, so it may no longer reflect the line that EDT read from `1cedt.ini`.
- `forcedAtRuntime` means only that the live flag was forced after startup; it does **not** mean buffered rendering works. `HippoLayoutService` creates its offscreen handler once, when the layout-service singleton is initialized, so changing the flag later does not create the missing handler.
- If a form screenshot is blank, check `nativeFormBufferedLayoutRender.effective`. `off` explains the empty image: add `-DnativeFormBufferedLayoutRender=true` to `1cedt.ini` and restart EDT. Calling the screenshot tool again cannot repair that startup state. `unknown` means the startup mode probe failed.
- `enabledTools` < `totalTools` is normal when progressive disclosure is on - use `list_toolsets` / `enable_toolset` to reveal more.

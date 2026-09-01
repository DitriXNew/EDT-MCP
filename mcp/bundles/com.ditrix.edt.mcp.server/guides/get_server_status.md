A self-diagnosis snapshot of the running MCP server. Reach for it when something behaves oddly and you want the facts instead of guessing - especially a blank form screenshot or a JSON tool that came back as plain text.

## When to use
- `get_form_screenshot` / `get_form_layout_snapshot` returned blank - check the form-render modes here.
- A JSON-response tool gave you plain text - check `plainTextMode`.
- You want to confirm the port, protocol version, plugin/EDT version, or how many tools are enabled vs. registered (e.g. when progressive disclosure is hiding tools).
- Quick "is the server actually up and reachable" check.

## Parameter details
None.

## What you get
JSON with: `port`, `running`, `protocolVersion`, `pluginVersion`, `edtVersion`, `enabledTools` / `totalTools`, `plainTextMode`, `checksFolderConfigured`, `authEnabled`, and `formRenderFlags`. The latter contains `nativeFormLayoutRender` followed by `nativeFormBufferedLayoutRender`. Each flag always has `atStartup` (`on`, `off`, or `unknown`), the mode sampled when the MCP plugin activates - this bundle is lazily activated, so that is not literally JVM start, but the two flags are derived from system properties at class-init and nothing in EDT rewrites them afterwards, so in practice it is the launch state; it can also have `requested`, the raw system-property string currently set (omitted when unset), and `forcedAtRuntime: true` when the known live mode differs from the known startup snapshot. None of the three is named `effective`, and deliberately: EDT binds buffered render ONCE, when `HippoLayoutService` initialises its static singleton, and every later render branches on what that constructor decided rather than on the flag. A runtime force therefore reaches the renderer only if it preceded that moment, and no tool can report which happened without initialising the class and thereby deciding it. Set the flag in `1cedt.ini` if you need it to hold.

## Notes & gotchas
- Secrets are never exposed: you get only the `authEnabled` boolean (never the token) and `checksFolderConfigured` (never the folder path).
- Judge how EDT was launched by `atStartup`, which is sampled before this plugin's screenshot path can mutate a live flag - that path runs from a tool call, which cannot happen before the bundle it lives in has activated. `requested` is the system property now; this plugin can overwrite it after startup, so it may no longer reflect the line that EDT read from `1cedt.ini`.
- `forcedAtRuntime` means only that the live flag was forced after startup; it does **not** mean buffered rendering works. `HippoLayoutService` creates its offscreen handler once, when the layout-service singleton is initialized, so changing the flag later does not create the missing handler.
- If a form screenshot is blank, read `nativeFormBufferedLayoutRender.atStartup` as what it is - how EDT was LAUNCHED - and not as a diagnosis. `on` means the buffer was configured at launch, so look elsewhere for the blank image. `off` does NOT by itself explain it: the screenshot path forces the flag before opening a form, so if EDT's layout service had not initialised yet the render may well be buffered anyway, and the status looks identical in both cases (`atStartup: off`, `requested: true`, `forcedAtRuntime: true`). The way to remove the ambiguity rather than guess at it: put `-DnativeFormBufferedLayoutRender=true` in `1cedt.ini`, restart EDT, and confirm `atStartup: on`. `unknown` means the startup mode probe failed.
- `enabledTools` < `totalTools` is normal when progressive disclosure is on - use `list_toolsets` / `enable_toolset` to reveal more.

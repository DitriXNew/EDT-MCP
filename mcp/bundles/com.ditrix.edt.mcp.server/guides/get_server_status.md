A self-diagnosis snapshot of the running MCP server. Reach for it when something behaves oddly and you want the facts instead of guessing - especially a blank form screenshot or a JSON tool that came back as plain text.

## When to use
- `get_form_screenshot` / `get_form_layout_snapshot` returned blank - check the form-render modes here.
- A JSON-response tool gave you plain text - check `plainTextMode`.
- You want to confirm the port, protocol version, plugin/EDT version, or how many tools are enabled vs. registered (e.g. when progressive disclosure is hiding tools).
- Quick "is the server actually up and reachable" check.

## Parameter details
None.

## What you get
JSON with: `port`, `running`, `protocolVersion`, `pluginVersion`, `edtVersion`, `enabledTools` / `totalTools`, `plainTextMode`, `checksFolderConfigured`, `authEnabled`, and `formRenderFlags`. The latter contains `nativeFormLayoutRender` followed by `nativeFormBufferedLayoutRender`. Each flag always has `atStartup` (`on`, `off`, or `unknown`), the mode sampled when the MCP plugin activates - this bundle is lazily activated, so that is not literally JVM start, but the two flags are derived from system properties at class-init and nothing in EDT rewrites them afterwards, so in practice it is the launch state; it can also have `requested`, the raw system-property string currently set (omitted when unset), and `forcedAtRuntime: true` when the known live mode differs from the known startup snapshot. None of the three is named `effective`, and deliberately: EDT binds buffered render ONCE, when `HippoLayoutService` initialises its static singleton, and every later render branches on what that constructor decided rather than on the flag. A runtime force therefore reaches the renderer only if it preceded that moment, and no tool can report which happened without initialising the class and thereby deciding it. On Windows, configure the screenshot flag after `-vmargs` in `1cedt.ini` if needed; Linux and macOS already use buffered native rendering. `get_form_layout_snapshot` does not require the buffered-render flag.

## Notes & gotchas
- Secrets are never exposed: you get only the `authEnabled` boolean (never the token) and `checksFolderConfigured` (never the folder path).
- `checksFolderConfigured: false` does **not** mean `get_check_description` is unavailable: the descriptions ship with the plugin. The flag reports only whether an operator configured a folder that overrides them.
- Judge how EDT was launched by `atStartup`, which is sampled before this plugin's screenshot path can mutate a live flag - that path runs from a tool call, which cannot happen before the bundle it lives in has activated. `requested` is the system property now; this plugin can overwrite it after startup, so it may no longer reflect the line that EDT read from `1cedt.ini`.
- `forcedAtRuntime` means only that the live flag was forced after startup; it does **not** mean buffered rendering works. `HippoLayoutService` creates its offscreen handler once, when the layout-service singleton is initialized, so changing the flag later does not create the missing handler.
- For blank or gray screenshots, `nativeFormBufferedLayoutRender.atStartup: on` does not prove EDT could create its offscreen image. On Windows, buffered mode also switches the human designer to that image; an unusable buffer leaves the designer gray. Check the EDT log for `AccessDeniedException` on `dtgraphics-form.bin` (possibly followed by `NoClassDefFoundError: org/eclipse/core/runtime/Plugin`). EDT uses `<java.io.tmpdir>dtgraphics-form.bin` without inserting a separator: change `-Djava.io.tmpdir=C:\edt_tmp` to `-Djava.io.tmpdir=C:\edt_tmp\` and restart EDT. If nothing helps, remove the buffered-render flag and restart EDT.
- `atStartup: off` does not by itself diagnose a missing buffer: the screenshot path attempts to force the flag before opening a form, and the handler may already have been initialized. Runtime forcing can also affect the human designer and does not guarantee rendering. On Windows, use the launch flag only with a usable buffer path; Linux and macOS need no buffered-render flag. `unknown` means the startup mode probe failed.
- `enabledTools` < `totalTools` is normal when progressive disclosure is on - use `list_toolsets` / `enable_toolset` to reveal more.

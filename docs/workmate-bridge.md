# 1C:Workmate in-process bridge

The MCP-to-Workmate conversation adapter supports 1C:Workmate (`com.e1c.edt.ai*`
1.0.5 or 1.0.7) in the same EDT JVM. The two builds differ in the conversation
API — 1.0.5 wraps the project in `ProjectId`, 1.0.7 takes the Eclipse `IProject`
and adds a progress-listener argument — and the adapter picks the layout by
probing the installed classes, so no version has to be configured. One behaviour
follows from that difference: on 1.0.7 a conversation is bound to a project, so
`projectName` is required there and omitting it is refused before the question is
sent.

- `ask_workmate` starts Workmate's full conversation/tool loop in a bounded
  background job and returns a pollable `jobId`, so the MCP transport request
  does not stay open for the full cloud round-trip. Poll that id with the shared
  `get_job_status` tool; `cancel_job` first previews and only acts with
  `confirm=true`, and it never claims that a request already handed to Workmate
  was cancelled. The Workmate bundles remain
  optional and are loaded only at runtime through OSGi/reflection; they are not
  part of this project's target platform. **The tool ships disabled** — it sends
  the question to an external cloud service and Workmate may change the
  configuration with its own tools — so enable it under
  *Preferences → EDT MCP Server → Tools* first.
- The OSGi service `com.ditrix.edt.mcp.server.bridge.IEdtMcpBridge` lets
  Workmate/JShell list and call EDT-MCP tools without importing EDT-MCP packages.
  `callTool` goes through the same dispatcher as MCP `tools/call` and returns its
  JSON-RPC response.

Two details decide whether this actually works, both measured against Workmate
1.0.5:

- **The skill.** `ConversationFacade` defaults to `raw`, under which the cloud
  answers from the model alone — one assistant message, no tool call, no look at
  the project. `ask_workmate` therefore sends `custom` (the skill Workmate's own
  autopilot uses), which runs the full tool loop. Override with `skillName` only
  if you know the name is accepted; the cloud refuses most others outright.
- **The question carries the bridge.** Workmate's chat reads a project's
  `.workmate` rules, but this Java path does not, so `ask_workmate` prefixes the
  question with the bridge instructions and the list of tool NAMES (full
  descriptions stay behind `get_tool_guide`, which it calls when it needs one).
  Pass `shareMcpTools=false` to send the question verbatim instead.

The same instance is published under the JDK types `BiFunction<String,String,String>`
(`callTool`) and `Supplier<String>` (`listTools`), both carrying the service property
`edt.mcp.bridge=v1`. Prefer that alias: it needs no reflection and no access to the
bridge package, which matters for callers whose rules forbid unproven Java API - such
as Workmate's JShell tool.

```java
// Take the context from an ALWAYS-ACTIVE bundle, not from EDT-MCP's own: this bundle
// uses lazy activation, so `Platform.getBundle("com.ditrix.edt.mcp.server")
// .getBundleContext()` can hand back null and the next line then fails with
// "because ctx is null". OSGi services are global, so any live context finds this one.
var bundleContext = org.osgi.framework.FrameworkUtil
    .getBundle(org.eclipse.core.runtime.Platform.class).getBundleContext();
var references = bundleContext.getServiceReferences(
    java.util.function.BiFunction.class, "(edt.mcp.bridge=v1)");
if (references.isEmpty()) {
    throw new IllegalStateException("EDT-MCP bridge service is not registered");
}
var mcp = bundleContext.getService(references.iterator().next());
System.out.println(mcp.apply("get_edt_version", "{}"));
```

A consumer that prefers the named contract can still resolve it by string name and
invoke it reflectively:

```java
var serviceReference = bundleContext.getServiceReference(
    "com.ditrix.edt.mcp.server.bridge.IEdtMcpBridge");
var bridgeService = bundleContext.getService(serviceReference);
try {
    var callTool = bridgeService.getClass().getMethod(
        "callTool", String.class, String.class);
    System.out.println((String) callTool.invoke(
        bridgeService, "get_edt_version", "{}"));
} finally {
    bundleContext.ungetService(serviceReference);
}
```

## Letting Workmate's chat use the bridge

Workmate's agentic chat holds its `JShell` tool but **not** `JShellSession`, and
`JShell` rejects every call whose `repl_session_id` it cannot resolve. The chat can
therefore execute code but cannot obtain the one value executing code requires.

EDT-MCP breaks that deadlock: shortly after startup it registers a JShell session
under the constant id **`edt-mcp`** (retried in the background while Workmate comes
up, and again if Workmate ever evicts it). Call `JShell` directly with that session
id and the following manual id:

```
scope           = "eclipse"
repl_session_id = "edt-mcp"
manual_ids      = ["active_workbench"]
request_description  = "Call EDT-MCP through its in-process bridge."
response_description = "EDT-MCP tool response was printed."
```

This chat-to-bridge setup was verified on **Workmate 1.0.8**, EDT 2026.2.1 on
Windows 11, as reported in [#787](https://github.com/DitriXNew/EDT-MCP/issues/787).
`active_workbench` is also registered by `EclipsePlatformBindingProvider.getManualEntries()`
and used in the `JShell` example in the inspected Workmate **1.0.5 and 1.0.7** sources.
The old `jshell_edt_canonical_imports` id belongs to the EDT metadata manual catalogue
in the inspected **1.0.5** build; it is absent from the inspected **1.0.7** builds
and was rejected on **1.0.8** in #787. Use `active_workbench` for this Eclipse bridge
setup across these versions.

`manual_ids` is validated against the installed build's live scenario catalogue;
an unknown or blank id is rejected **before any Java code executes**. For another
build, use `JShellManual` when available to obtain an exact `manual_id` from its
catalogue. If the chat cannot access it, report the installed Workmate version and
the exact error instead of guessing an id. The session id remains `edt-mcp`.

[`tests/TestConfiguration/.workmate/WORKMATE.md`](../tests/TestConfiguration/.workmate/WORKMATE.md)
is a working example of such a rules file; copy it into `<project>/.workmate/` to give
the chat the same access in your own configuration, replacing `TestConfiguration`
with your EDT project name (`list_projects` lists valid names). **Start a new chat**
after changing the rules so Workmate loads them. If `JShell` reports `Session not
found`, the startup registration has not completed; report that error and retry
after EDT-MCP and Workmate have started.

For a request such as "show the last three commits through EDT-MCP", first enable
**`git`** in **Window → Preferences → MCP Server → Tools → Git**. Both `git` and
`ask_workmate` ship **disabled by default**, and **`enable_toolset` does not enable
them**. The bridge obeys the same tool settings as HTTP MCP: a response such as
`Tool 'git' is disabled by the user` means the bridge reached the dispatcher;
ask the user to enable the tool in the Tools tab before retrying.

The chat cannot reach the HTTP endpoint at all — `java.net.URL`, `java.net.Socket`
and `ProcessBuilder` are on Workmate's restricted-types list — so the in-process
bridge is its only route.

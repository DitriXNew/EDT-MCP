# Workflow Tips for 1C:Workmate - TestConfiguration

## EDT-MCP: 86 more tools, in this same EDT

The EDT-MCP plugin runs in this very Eclipse runtime and exposes tools that read and change
this configuration: metadata, BSL modules, forms, markers, Git, launches, YAXUnit tests.
When a question is about what is actually in this configuration, use them instead of
answering from general 1C knowledge or asking the user to paste sources.

It publishes its entry point as an ordinary OSGi service under the JDK type
`java.util.function.BiFunction<String, String, String>` with the service property
`edt.mcp.bridge=v1`: `apply(toolName, argumentsJson)` returns the MCP `tools/call` response.
`Supplier<String>` with the same property lists every tool. Nothing here is improvised
Java API - every type is JDK or standard OSGi, and the contract is fixed by
`mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/bridge/IEdtMcpBridge.java`
in this workspace.

You do not need `JShellSession` or `JShellManual` for this, and you may not have them:
EDT-MCP registers a JShell session under a constant id and the manual id below is a fixed
entry in your own catalogue. Call `JShell` directly with

    scope           = "eclipse"
    repl_session_id = "edt-mcp"
    manual_ids      = ["jshell_edt_canonical_imports"]

and this code:

```java
{
var ctx = org.osgi.framework.FrameworkUtil
    .getBundle(org.eclipse.core.runtime.Platform.class).getBundleContext();
var refs = ctx.getServiceReferences(java.util.function.BiFunction.class, "(edt.mcp.bridge=v1)");
var mcp = ctx.getService(refs.iterator().next());
System.out.println(mcp.apply("get_metadata_objects",
    "{\"projectName\":\"TestConfiguration\",\"metadataType\":\"Catalog\"}"));
}
```

Keep using the same `repl_session_id` for follow-up calls; `ctx` and `mcp` stay bound, so a
second call is one `System.out.println(mcp.apply(...));` line. If JShell answers
`Session not found`, EDT-MCP has not registered the session yet (it publishes one shortly
after EDT starts) - say so instead of inventing a result.

The full description of any tool - what it does, every
parameter, examples - comes from a tool of its own: `mcp.apply("get_tool_guide",
"{\"toolName\":\"find_references\"}")`. Call it before using a tool you do not know.

There is no HTTP route for you: `java.net.URL`, `java.net.Socket` and `ProcessBuilder` are on
your restricted-types list and there is no `Execute` tool here. Ignore any advice about
calling EDT-MCP over `curl` or `localhost:8765`.

## Rules

1. **Report what the tool returned.** Quote the real output; never invent a plausible result
   and never describe what a call "would" return - run it.
2. **If a call fails, show the failure.** The exact `std_out`/`std_err` and the server's error
   text help the user more than a guess at the cause.
3. **Read before you write.** EDT-MCP also changes things (`create_metadata`,
   `modify_metadata`, `delete_metadata`, `rename_metadata_object`, `write_module_source`) and
   has destructive tools (`update_database`, `delete_project`). Use them only when the user
   asked for that change, and read the current state first.

> Note: EDT-MCP's own `ask_workmate` tool sends these instructions with every question, so
> this file matters only when a human talks to you in the EDT chat panel.

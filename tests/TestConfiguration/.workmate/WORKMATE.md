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

EDT-MCP registers a JShell session under a constant id, so you do not need
`JShellSession`. Call `JShell` directly with

    scope           = "eclipse"
    repl_session_id = "edt-mcp"
    manual_ids      = ["active_workbench"]
    request_description  = "Call EDT-MCP through its in-process bridge."
    response_description = "EDT-MCP tool response was printed."

This setup was verified on Workmate **1.0.8** with EDT 2026.2.1 on Windows 11
([#787](https://github.com/DitriXNew/EDT-MCP/issues/787)). `active_workbench` is also
present in the inspected Workmate **1.0.5 and 1.0.7** scenario registries and their
`JShell` examples. The old `jshell_edt_canonical_imports` id is in the inspected
**1.0.5** EDT metadata manual catalogue, but absent from the inspected **1.0.7**
builds and rejected on **1.0.8** in #787. Use `active_workbench` for this Eclipse
bridge setup across these versions.

`manual_ids` is validated against the installed build's live catalogue;
unknown or blank ids are rejected before code executes. For another
build, use `JShellManual` when available to obtain an exact `manual_id`. If it is
unavailable in chat, report the installed Workmate version and the exact error
instead of guessing a replacement. Keep `repl_session_id = "edt-mcp"`.

Use this code:

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

## Tools disabled by default

`git` and `ask_workmate` are **disabled by default**. `enable_toolset` only changes
progressive-disclosure visibility and does not enable them. Ask the user to enable
the needed tool in **Window → Preferences → MCP Server → Tools** (`git` has its own
**Git** group). A response such as `Tool 'git' is disabled by the user` confirms
that the bridge reached the dispatcher; report it and ask for the tool to be enabled
before retrying a request for commits, branches or a diff through `git`.

After editing this rules file, start a new Workmate chat so it loads the changes.

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

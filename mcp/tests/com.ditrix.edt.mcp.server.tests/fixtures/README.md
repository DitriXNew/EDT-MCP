# Stdio launcher fixture

`stdio-entry-v2.26.1.jar` is the unmodified `bootstrap/edt-mcp-bootstrap.jar` embedded in the public EDT MCP v2.26.1 plugin (source tag `bc4cbe3b459ae2e12b6f110d68d6ffbfcc69de22`). SHA-256: `26b2d4c1384cee32e8c5c56cf78b988b9b18a5ae79612d6558e8dbd4f92f3e6b`.

Its manifest selects `StdioEntry`. Disassembly with `javap -p -c` confirms it reads the sibling `current` pointer, verifies the SHA-256-named runtime, and calls `com.ditrix.edt.mcp.server.ipc.StdioBootstrap.main(String[])`. It is used as data only; the fallback test does not execute it.

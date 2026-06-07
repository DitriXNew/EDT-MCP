# get_server_status

Self-diagnosis snapshot of the running MCP server: listening port, MCP protocol version, plugin version, EDT version, enabled/total tool counts, the plainTextMode and checksFolderConfigured preference flags, the two form-render JVM flags (nativeFormBufferedLayoutRender / nativeFormLayoutRender), and whether authentication is enabled. Use it to explain a blank form screenshot or a plain-text JSON response. Never returns the auth token value or the checks folder path, only booleans.

## Parameters
No parameters.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*

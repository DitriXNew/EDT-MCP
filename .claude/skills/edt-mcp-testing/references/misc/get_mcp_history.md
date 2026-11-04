# get_mcp_history — acceptance procedure

Enable history recording, then call a harmless read and a deliberate invalid tool request. Use `structuredContent`:

```text
get_mcp_history(tool="get_metadata_details", status="all", includeBodies=true, includeStats=true)
```

- Require newest-first records with the delivered status, duration and full payload sizes. Filter by `status:"error"` and confirm statistics count the same failed calls.
- Produce an error whose marker lies beyond the recorder's 20000-character body cap; use the controlled payload in `tests/e2e/tools/test_get_mcp_history.py`. The stored response may be invalid JSON, but the record must remain `status:"error"` in filters and statistics. Do not reclassify a capped body.
- For an interrupted call require one row at signal delivery: `interrupted:true`, `status:"error"`, and the delivered response. Background completion updates only `backgroundOutcome` on that row; status, response, duration and statistics remain unchanged. Cleared/evicted rows must not reappear.
- Manually overlap two calls and use the status bar: the latest registered call owns the single interrupt slot. Completing an older call must not clear the newer slot; completing the latest does not restore an older call. Unit tests cover the identity races; this UI check requires the live workbench.
- For malformed requests use `harness._post("tools/call", params)`: absent/null/blank names yield JSON-RPC `-32602` and a `tools/list` hint; nonblank unknown names yield `-32601`. These are not tool-result `success:false` envelopes.

History is read-only but records its own calls. Keep bodies off unless needed; they may contain sensitive data. Check fixture cleanliness. Use `get_tool_guide('get_mcp_history')` for filters, time bounds, paging and statistics.

# run_yaxunit_tests

Run YAXUnit tests for a 1C:Enterprise project and return a JUnit Markdown report. Polls for up to `timeout` seconds, then returns the report or **Pending** (call again with identical arguments to keep waiting; the launch is not terminated). Pass `debug=true` to instead launch in DEBUG mode (breakpoints fire) and return at once so you can call wait_for_break. Requires an existing runtime-client launch configuration and the YAXUnit extension installed in the infobase. Full parameters and examples: call get_tool_guide('run_yaxunit_tests').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact runtime-client launch config name (preferred; from list_configurations). |
| projectName | — | string | EDT project name (required if launchConfigurationName is omitted). |
| applicationId | — | string | Application ID from get_applications (required if launchConfigurationName is omitted). |
| extensions | — | array | Extension names to filter tests (array; a comma-separated string is also accepted). |
| modules | — | array | Module names to filter tests (array; a comma-separated string is also accepted). |
| tests | — | array | Test names in Module.Method format (array; a comma-separated string is also accepted). |
| timeout | — | integer | Polling window in seconds (default: 60); on expiry returns Pending. |
| updateBeforeLaunch | — | boolean | Auto-chain (default: true): terminate a live client and run a silent DB update first. |
| debug | — | boolean | Default false: poll and return the report. true: launch in DEBUG mode so breakpoints fire, return immediately and call wait_for_break next (ignores timeout). |

## Guide
Launches the 1C:Enterprise application with the `RunUnitTests` startup parameter, polls until the launch terminates or the polling window expires, then parses the JUnit XML report and returns a Markdown summary. The full Markdown report is also written to `report.md` next to `junit.xml` so you can read it directly from disk.

## When to use

Use after writing or changing test code to verify it. Prerequisites: an existing runtime-client launch configuration for the project/application, and the YAXUnit extension installed in the target infobase. Without YAXUnit no JUnit XML is produced and the tool returns an error.

## Parameter details

Two ways to identify the launch:

- `launchConfigurationName` (preferred) — the exact runtime-client config name from `list_configurations`. When set, `projectName` and `applicationId` are derived from it.
- `projectName` + `applicationId` — required together when `launchConfigurationName` is omitted. Get the application id from `get_applications`.

Optional test filters (each an array of names, AND-combined; a comma-separated string is also accepted):

- `extensions` — restrict to tests in these extensions.
- `modules` — restrict to these test modules.
- `tests` — individual tests in `Module.Method` format.

Control:

- `timeout` — polling window in seconds (default 60). See ## Polling and Pending.
- `updateBeforeLaunch` — auto-chain, default `true`. See ## Auto-chain.

## Polling and Pending

The tool polls for up to `timeout` seconds. If the launch finishes in that window it returns the parsed JUnit report. If the window expires while the launch is still running it returns **Pending** and does NOT terminate the launch. Call the tool again with the SAME arguments to keep waiting and fetch the result once the launch completes. A run key is derived from the config name plus the filter, so identical arguments reattach to the in-flight launch instead of starting a new one. There is NO time-based result cache. A completed result is delivered to the matching identical call exactly once (to satisfy a re-call fetching a previously reported **Pending** run); every later identical call re-runs the tests. Caveat: if you were told **Pending** and never fetched the result, the next identical call returns that old report once (not a fresh run) before subsequent calls re-execute. To force a fresh run after an abandoned Pending, either change the filter (a new run-key carries no pending result) or make one identical call to drain that result, then call again to re-execute. (`terminate_launch` does NOT help here — it stops the Eclipse launch but leaves the once-only pending result to be served by the next identical call.)

## Auto-chain (updateBeforeLaunch)

Default `true`: before spawning a new test launch, the tool politely terminates any live 1C client running this configuration and runs a silent database update — so EDT's launch delegate does not pop its modal 'Update database?' dialog that would otherwise block the MCP call. Set `false` to keep legacy behaviour (the delegate decides; the dialog may appear and block). If pre-launch preparation fails because a previous launch is stuck, call `terminate_launch` with `force=true` and retry.

## Debug mode (debug=true)

Pass `debug=true` to launch in DEBUG mode so breakpoints set with `set_breakpoint` trip. Then the tool does NOT poll (it ignores `timeout`): it returns a Markdown launch handle immediately and you call `wait_for_break` next. The full cycle:

```
set_breakpoint -> run_yaxunit_tests(debug=true) -> wait_for_break
  -> get_variables / evaluate_expression / step -> resume
```
Pin to ONE test (`tests`) so exactly one breakpoint trips. The deprecated `debug_yaxunit_tests` tool is a thin alias for this.

## Examples

Run all tests via a named config:

```json
{ "launchConfigurationName": "TestClient" }
```

Run by project + application, filtered to two modules:

```json
{ "projectName": "MyProject", "applicationId": "<id-from-get_applications>", "modules": ["Tests_Catalog", "Tests_Document"] }
```

Run a single test method with a longer window:

```json
{ "launchConfigurationName": "TestClient", "tests": "Tests_Catalog.CreateAndPost", "timeout": 180 }
```

## Notes

- Response type is Markdown; the report is also saved to `report.md` next to `junit.xml`.
- The temp/report directory is not deleted on completion so a later call can re-fetch it.
- Module and test names are 1C identifiers (programmatic `Name`), not synonyms.

## Gotchas

- A timeout returns **Pending**, not a failure — do not retry with different arguments; reuse the same ones so the run key matches.
- If no JUnit XML appears after the launch finishes, the YAXUnit extension is likely not installed in the infobase, or the filter matched no tests.
- The config must be a runtime-client launch configuration; other types are rejected.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

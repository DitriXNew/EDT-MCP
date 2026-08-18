# debug_yaxunit_tests

DEPRECATED alias of run_yaxunit_tests(debug=true) - prefer that instead; the implementation is shared. DEBUG mode, so breakpoints fire: a short start returns the launch handle and you call wait_for_break next, while Pending returns a jobId to poll with get_job_status. Parameters and examples: get_tool_guide('debug_yaxunit_tests').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact runtime-client launch config name (preferred; from list_configurations). |
| projectName | — | string | EDT project name (required if launchConfigurationName is omitted). |
| applicationId | — | string | Application id from get_applications (required if launchConfigurationName is omitted). |
| extensions | — | array | Extension names to filter tests (array; a comma-separated string is also accepted). |
| modules | — | array | Module names to filter tests (array; a comma-separated string is also accepted). |
| tests | — | array | Test names in Module.Method format (array; a comma-separated string is also accepted; pin to one test for a predictable debug cycle). |
| tags | — | array | YAXUnit tags to select tests by (array; a comma-separated string is also accepted). A test is selected when its module, its suite, or the test itself carries one of these tags; matching is case-insensitive and exclusion is not supported by YAXUnit. |
| timeout | — | integer | Maximum seconds the start call waits for its background job (default and maximum 45; a larger value is clamped to it, because an MCP transport cuts the call at around 60s and a longer window would return a bare transport error instead of an answer). A job that finishes in this window returns the same report in this call. Otherwise the call returns Pending with jobId; poll get_job_status with that id. This value never limits the job's server-side lifetime. |
| updateBeforeLaunch | — | boolean | Default true: terminate any live client and run a silent DB update first so no modal 'Update database?' dialog blocks the call; false keeps legacy delegate behaviour — no client sweep, no auto-confirmed update dialog; platform dialogs may appear. |
| updateScope | — | string | Which projects to rebuild+update before the run: 'all' (configuration + dependent extensions, default), 'configuration', or 'extension:<ProjectName>' (comma-separate several). Forces a derived-data recompute so a freshly edited extension's .cfe is regenerated and loaded into the infobase before the run. Unknown extension names fail the call (the error lists the available names). Only applies when updateBeforeLaunch=true. |
| externalInfobaseChanges | — | string | How to answer EDT's blocking 'Infobase configuration changes' modal when the infobase was changed outside EDT (Designer, ibcmd, a CLI pipeline) since the last EDT interaction: 'override' (default) keeps the project configuration and overwrites the infobase, 'import' pulls the external changes into the PROJECT sources, 'cancel' aborts the update with an error. Omitted, the modal is still answered (with 'override'), so an unattended call never blocks on it. |
| standaloneServerPortConflict | — | string | Answer to EDT's standalone-server port-conflict prompt: cancel (default) = fail and name the busy ports; reassign = let EDT move the server to free ports (rewrites its configuration). |

## Guide
# debug_yaxunit_tests (deprecated)

**Deprecated alias.** Use `run_yaxunit_tests` with `debug=true`. The alias forwards into the same named-background-job implementation while preserving `debug_yaxunit_tests` as the job's owning tool.

The job resolves and prepares the application, launches YAXUnit in **DEBUG mode**, and returns a Markdown launch handle. If that completes within `timeout`, the launch handle is returned synchronously. If it does not, the reply is **Pending**, includes a `jobId`, and points to `get_job_status`. Keep that exact id and poll it; do not repeat the start arguments to address a known debug run.

Once the launch handle arrives, call `wait_for_break`:

```
set_breakpoint -> debug_yaxunit_tests -> wait_for_break
  -> get_variables / evaluate_expression / step -> resume
```

## Parameter details

The parameters match `run_yaxunit_tests(debug=true)`:

- Identify the launch with `launchConfigurationName`, or with `projectName` + `applicationId`.
- Filter with `extensions`, `modules`, `tests`, and `tags`. Each accepts an array or a comma-separated string. Pin `tests` to one `Module.Method` for a predictable debug cycle.
- `timeout` is the start-call wait only, default and maximum 45 seconds. It does not limit the background job. A larger value is clamped.
- `updateBeforeLaunch` defaults to `true` and performs the pre-launch recompute/update chain. `updateScope` narrows that chain; `externalInfobaseChanges` selects how its blocking external-change prompt is answered.

The progress journal uses the same `resolve`, `prep:terminate`, `prep:recompute`, `prep:db-update`, and `spawn` phases as `run_yaxunit_tests`. A phase that stops advancing may be slow work or an EDT modal dialog; inspect EDT instead of waiting indefinitely.

The debug job commits immediately before handing pre-launch work to its Eclipse job because that work may update the infobase independently, and immediately before `launch(DEBUG_MODE)` because attaching the debugger and starting the client cannot be recalled by interrupting the registry worker.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

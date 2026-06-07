# debug_yaxunit_tests

Deprecated alias for run_yaxunit_tests with debug=true. Launches YAXUnit tests in DEBUG mode so breakpoints fire, then call wait_for_break to inspect. Prefer run_yaxunit_tests(debug=true) — identical behaviour. Full parameters and examples: call get_tool_guide('debug_yaxunit_tests').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact runtime-client launch config name (preferred; from list_configurations). |
| projectName | — | string | EDT project name (required if launchConfigurationName is omitted). |
| applicationId | — | string | Application id from get_applications (required if launchConfigurationName is omitted). |
| extensions | — | string | Comma-separated extension names to filter tests by extension. |
| modules | — | string | Comma-separated module names to filter tests. |
| tests | — | string | Comma-separated test names as Module.Method (recommended: pin to one test for a predictable cycle). |
| updateBeforeLaunch | — | boolean | Default true: terminate any live client and run a silent DB update first so no modal 'Update database?' dialog blocks the call; false keeps legacy delegate behaviour. |

## Guide
# debug_yaxunit_tests (deprecated)

**Deprecated alias.** Use `run_yaxunit_tests` with `debug=true` — identical behaviour. This tool simply forwards its arguments to `run_yaxunit_tests(debug=true)`.

Launches YAXUnit tests in **DEBUG mode** so that breakpoints set via `set_breakpoint` trip when the test executes the code under inspection. It does NOT poll for `junit.xml`: after the launch is queued it returns a Markdown launch handle and you call `wait_for_break` next.

## The full debug cycle
```
set_breakpoint -> run_yaxunit_tests(debug=true) -> wait_for_break
  -> get_variables / evaluate_expression / step -> resume
```

## Parameters
Same as `run_yaxunit_tests` (minus `timeout`, which DEBUG mode ignores): identify the launch by `launchConfigurationName`, or by `projectName` + `applicationId`; filter with `extensions` / `modules` / `tests` (pin to ONE test for a predictable cycle); `updateBeforeLaunch` (default true) silences the modal update dialog. See `get_tool_guide('run_yaxunit_tests')` for the full reference.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*

# debug_yaxunit_tests (deprecated)

**Deprecated alias.** Use `run_yaxunit_tests` with `debug=true` — identical behaviour. This tool simply forwards its arguments to `run_yaxunit_tests(debug=true)`.

Launches YAXUnit tests in **DEBUG mode** so that breakpoints set via `set_breakpoint` trip when the test executes the code under inspection. It does NOT poll for `junit.xml`: after the launch is queued it returns a Markdown launch handle and you call `wait_for_break` next.

## The full debug cycle
```
set_breakpoint -> run_yaxunit_tests(debug=true) -> wait_for_break
  -> get_variables / evaluate_expression / step -> resume
```

## Parameter details
Same as `run_yaxunit_tests` (minus `timeout`, which DEBUG mode ignores): identify the launch by `launchConfigurationName`, or by `projectName` + `applicationId`; filter with `extensions` / `modules` / `tests` (pin to ONE test for a predictable cycle); `updateBeforeLaunch` (default true) silences the modal update dialog. See `get_tool_guide('run_yaxunit_tests')` for the full reference.

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

The progress journal uses the same `resolve`, `prep:terminate`, `prep:check-changes`, `prep:recompute`, `prep:settle`, `prep:db-update`, and `spawn` phases as `run_yaxunit_tests`; `prep:recompute` appears only when the gate found something to recompute. A phase that stops advancing may be slow work or an EDT modal dialog; inspect EDT instead of waiting indefinitely.

The debug job commits immediately before handing pre-launch work to its Eclipse job because that work may update the infobase independently, and immediately before `launch(DEBUG_MODE)` because attaching the debugger and starting the client cannot be recalled by interrupting the registry worker.

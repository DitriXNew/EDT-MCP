# debug_yaxunit_tests — how to test

**Purpose.** Start YAXUnit in DEBUG mode so breakpoints fire while a selected test runs. The tool is a deprecated forwarding alias for `run_yaxunit_tests(debug=true)` and uses that implementation's named `BackgroundJobs` job, preparation, duplicate guard, launch, and progress reporting. The alias preserves `debug_yaxunit_tests` as the job's owning tool.

> **Heavy / mutating launch — explicit-request-only.** This starts a real 1C client and, by default, terminates live clients and updates the infobase before launch. Use a disposable test infobase with exclusive access.

## Preconditions

- A live non-elevated EDT workbench and ready target project.
- A runtime-client launch configuration from `list_configurations`.
- The YAXUnit extension installed in the target infobase.
- A breakpoint on a line the selected test executes.
- Exclusive infobase access for the default `updateBeforeLaunch=true` preparation.

## Call

Pin the run to one method for a predictable suspend:

```
set_breakpoint(projectName="TestConfiguration",
               modulePath="CommonModules/<ModuleUnderTest>/Module.bsl",
               lineNumber=<executable-line>)

debug_yaxunit_tests(
  launchConfigurationName="TestConfiguration Thin Client",
  tests=["<TestModule.TestMethod>"],
  timeout=30,
  updateBeforeLaunch=true
)
```

The legacy selector is `projectName` + `applicationId` when `launchConfigurationName` is omitted. `extensions`, `modules`, `tests`, and `tags` accept arrays or comma-separated strings. `timeout` is the start-call wait only, defaults to 45 seconds, and is clamped to 45; it never limits the job.

## Named-job result

If resolve, preparation, and spawn finish within `timeout`, the start call returns the Markdown launch handle synchronously:

```markdown
# YAXUnit Debug Launch

Debug launch **queued** for `TestConfiguration Thin Client`.

- **applicationId:** `<resolved id>`
- **projectName:** `TestConfiguration`
- **reportDir:** `<system temp path>`
- **junitXml:** `<reportDir>/junit.xml`

**Next step:** call `wait_for_break` ...
```

If they do not finish in that window, the reply is **Pending**, contains `jobId`, identifies `debug_yaxunit_tests` as `owningTool`, and includes the current phase. Poll the same identity:

```json
{"jobId":"<id-from-debug_yaxunit_tests>","waitSeconds":45}
```

with `get_job_status`. Do not reconstruct the start arguments to address a known run. An identical start attaches only while the job is live as a duplicate guard. The terminal job result is the same launch handle.

Progress phases are `resolve`, `prep:terminate`, `prep:check-changes`, `prep:recompute`, `prep:settle`, `prep:db-update`, and `spawn` — `prep:recompute` only when the gate found something to recompute. A phase that stops advancing can mean slow work or an EDT modal dialog; inspect EDT.

## Debug cycle

After the launch handle arrives:

```
wait_for_break(applicationId="<resolved id>", timeout=30)
  -> get_variables / evaluate_expression / step
  -> resume
```

The handle means the launch was queued, not that a breakpoint was hit or the tests passed. The JUnit XML is written at `junitXml` after the run finishes.

## Commit and cancellation boundaries

The job calls `tryCommit()` immediately before handing the pre-launch chain to an Eclipse job because that chain can terminate a client and update the infobase independently. It calls `tryCommit()` again immediately before `workingCopy.launch(DEBUG_MODE)`, because attaching the debugger and starting the client cannot be recalled by interrupting the registry worker.

The YAXUnit owner declares a destructive committed-cancellation capability. Preview `cancel_job` without `confirm`; the preview changes nothing and warns that a confirmed stop kills the client, does not roll back the infobase, and may leave partial or absent JUnit XML. A successful confirmed stop reports `terminated` and never claims a clean test outcome. Once the debug job has already returned its launch handle it is terminal; use `terminate_launch` to stop that debug session.

## Verification

- `wait_for_break` returns a hit for the selected test's breakpoint.
- `debug_status` shows the active debug session while it runs.
- `get_job_status` returns the launch handle when the start call returned Pending.
- `junitXml` appears in the returned system-temp report directory after completion.
- The project source tree remains unchanged by the launch tool itself.

## Parameter details

- `jobId` is the opaque id returned by the tool that started the background job.
- `confirm` is the consent gate. Omit it or pass `false` to preview; only
  `confirm=true` requests cancellation.

## Examples

Preview without changing the job:

```json
{"jobId":"<id returned by the owning tool>"}
```

After reviewing the owning tool, current state, and progress, confirm:

```json
{"jobId":"<same id>","confirm":true}
```

## Commit handshake and honest outcomes

If cancellation wins before the owning tool crosses the `BackgroundJobs` commit
handshake, its worker is interrupted. The job stays `running` while that callable
is still unwinding and moves to `cancelled` only when the worker releases its
admission slot. Code that ignores interruption therefore honestly remains
non-terminal and continues to block duplicate admission until it exits.

Most work cannot be recalled after commit. A cloud request already dispatched by
`ask_workmate`, for example, keeps running: `cancel_job` reports
`alreadyCommitted`, makes no false cancellation claim, and tells you to keep
polling the same `jobId` without starting a duplicate.

An owning tool may explicitly declare a destructive cancellation capability when
it starts a job. This is capability data and a handler supplied to the registry;
`cancel_job` never special-cases a tool name. The preview prints the handler's
warning, and `confirm=true` invokes it only for that job.

For a live YAXUnit run, the preview states that termination kills the client
process, does not roll back the infobase, and may leave a partial or absent JUnit
report. A successful confirmed stop reports `terminated`, states that the
infobase was **NOT** rolled back, and renders usable partial JUnit XML. It never
claims a clean test outcome. The job itself becomes `cancelled` only after its
worker exits; `terminated` reports what happened to the launch, not an early
claim that the registry worker is already gone. If no live launch can be stopped,
the committed job keeps the honest `alreadyCommitted` outcome.

The registry gives an owner cancellation handler at most 30 seconds as an outer
guard. This is longer than YAXUnit's default 10-second termination check so the
handler can verify the stop and read a partial report. If the whole handler still
does not return, `cancel_job` reports that the stop was not established, releases
the cancellation claim, and leaves the job to publish its worker's real outcome.

A job that was already done, failed, or cancelled is left unchanged and reported
as already terminal.

## Unknown and expired jobs

The registry may evict old completed jobs. For an unknown or expired id, start a
new job with the tool that originally created it and use the new id. There is no
safe cancellation target to infer from the old call arguments.

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
claim that the registry worker is already gone.

If the launch accepted the termination request but did not confirm completion
within the verification wait, or that verification was interrupted after
`terminate()` returned, the outcome is `terminationRequested`. This does not
claim a verified stop and does not claim that nothing happened. It states that
termination is irreversible but unconfirmed, that the infobase was **NOT**
rolled back, and that the job is cancellation-pending. The worker's later normal
return cannot become `done`; the job becomes `cancelled` when the run and its
worker actually end, retaining the honest partial-or-absent report explanation.
An identical run remains attached to that non-terminal job in the meantime.

If `terminate()` fails, the launch reports that it cannot terminate, or no live
launch is available, no stop was initiated and the committed job keeps the
honest `alreadyCommitted` outcome.

The registry's 30-second outer guard bounds the `cancel_job` call, not the owner
cancellation handler's lifetime. This is longer than YAXUnit's default 10-second
termination check so the handler can verify the stop and read a partial report.
If the whole handler still does not return, `cancel_job` reports that the stop was
not established and releases the worker outcome deferred behind its waiter. That
outcome is marked as provisional while the destructive handler remains alive;
the job stays claimed, a second `cancel_job` cannot start another handler, and an
equivalent run is not admitted while the stale handler can still mutate owner
state. A late `notStopped` result leaves the worker outcome unchanged. A late
`stopped` or `stopInitiated` result is reconciled because it describes a real
destructive action. A verified late `stopped` interrupts a worker that is still
running, and the job becomes `cancelled` when that worker exits. A late
`stopInitiated` does not interrupt the worker, because the stop is unverified;
the job stays `running` until its work ends on its own. If the worker outcome was
already published, either destructive result supersedes it and corrects the job
to `cancelled` with the honest partial result. The progress journal names whether
the replaced outcome was `done` or `failed` and retains the original failure
message. A poller may therefore see `done` or `failed` corrected to `cancelled`
after the handler exits.

A new cancellation request for a job that was already done, failed, or cancelled
is left unchanged and reported as already terminal. This does not discard a
destructive result from a cancellation handler that was already in flight.

## Unknown and expired jobs

The registry may evict old completed jobs. For an unknown or expired id, start a
new job with the tool that originally created it and use the new id. There is no
safe cancellation target to infer from the old call arguments.

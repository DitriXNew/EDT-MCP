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

A job is cancellable only before its owning tool crosses the `BackgroundJobs`
commit handshake. Once work has been handed to an external service or another
thread, it cannot be recalled. In that case `cancel_job` reports that the job was
**not** cancelled, leaves it running, and tells you to continue polling it with
`get_job_status`. Do not start a duplicate job: the original work is already in
flight.

If cancellation wins before commit, the job moves to `cancelled` and its worker
is interrupted. A job that was already done, failed, or cancelled is left
unchanged and reported as already terminal.

## Unknown and expired jobs

The registry may evict old completed jobs. For an unknown or expired id, start a
new job with the tool that originally created it and use the new id. There is no
safe cancellation target to infer from the old call arguments.

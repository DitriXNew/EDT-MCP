## Parameter details

- `jobId` is the opaque id returned by the tool that started a background job.
  Keep that exact value; changing any start argument does not identify the same
  job.
- `waitSeconds` bounds this one poll. It defaults to 5 seconds, accepts 0 through
  45, and never extends the owning job's total timeout. Use 0 for a snapshot
  without waiting.

## Examples

Return the current snapshot immediately:

```json
{"jobId":"<id returned by the owning tool>","waitSeconds":0}
```

Wait briefly for a terminal outcome:

```json
{"jobId":"<id returned by the owning tool>","waitSeconds":5}
```

The snapshot identifies the owning tool and contains the state, elapsed time,
timestamped progress journal, and result or error once terminal. Continue polling
the same id while the state is `running`.

## Unknown and expired jobs

The registry is bounded and may evict old completed jobs. An unknown or expired
id is an error naming that id. Start a new job with the tool that originally
created it and poll the new id; do not reconstruct an id from the original call
arguments.

## Cancellation

Polling never changes a job. To stop work, call `cancel_job` first without
`confirm` to preview the owner, state, and progress, then repeat with
`confirm=true` only if cancellation is still intended.

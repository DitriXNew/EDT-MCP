Turns off the line-level profiling started by `start_profiling` for a debug session. Stop it once your scenario has run, then call `get_profiling_results` to retrieve the collected coverage and timing.

## When to use
- Your scenario has finished and you want to end measurement before reading results.
- To reset profiling state for an application before starting a fresh measurement.

## Parameter details
- `applicationId` (required) - the same debug session you passed to `start_profiling`.

## What you get
JSON: `active` (false after a successful stop), `stopped` (whether it was actually toggled off), `applicationId`, and a `message`.

## Notes & gotchas
- Idempotent: if profiling was never started (or already stopped) for this id you get a benign success (`stopped: false`), not an error.
- If the debug session has already ended, the tool clears its tracked state and reports that benignly - your collected results are still retrievable.
- Stopping does not by itself print the data - follow with `get_profiling_results`.

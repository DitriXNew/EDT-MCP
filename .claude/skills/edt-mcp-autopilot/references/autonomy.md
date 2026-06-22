# Autonomy gates

The pipeline runs unattended. There are exactly two places it pauses for input, and only one of
them needs a human. Both are driven by the conductor (the main loop), not by a workflow — a
`Workflow` cannot sleep/poll or take irreversible outward actions.

## Gate A — principal design question -> issue + poll (no human required to keep going)

When the discover workflow returns a non-empty `escalations` (or the build review loop cannot
converge), do NOT guess. Instead:

1. **Post the question to the issue in Russian.** State the question and the 2-3 options with
   their trade-offs, and say you will wait for the maintainer's choice. Use `gh issue comment`
   (or the GitHub MCP) on the task's issue.
2. **Enter the poll loop.** Schedule a re-check roughly every 10 minutes and, on each wake,
   read the issue comments for a new maintainer reply:

   ```
   ScheduleWakeup({
     delaySeconds: 600,
     reason: "polling <issue> for the maintainer's answer to the escalated design question",
     prompt: "<the /edt-mcp-autopilot invocation, verbatim>"
   })
   ```

   On each wake: fetch the latest comments; if the maintainer answered, fold the decision into
   `architecture.md` and continue the pipeline; if not, schedule another `ScheduleWakeup(600)`.
   Stop scheduling once answered (omit the call). Keep `delaySeconds` at ~600 — short enough to
   feel responsive, long enough not to burn the cache every minute.
3. While waiting, you may continue any **independent** work that does not depend on the answer.

This is the "fundamental questions go to the issue and are polled until answered" rule.

## Gate B — operator confirms the live stand result (the one human touchpoint)

After the live stand scenarios pass (Phase 8), before shipping, ask the operator to confirm the
real result with `AskUserQuestion`:

- Summarise what was run on the throwaway stand and the observed result (paste the key
  evidence — a value read back, a screenshot path, a health/response snippet).
- Offer: **Confirm — ship it** / **Not right — describe what is wrong** (the operator can add
  notes). Do not open the PR until the operator confirms.
- If the operator reports a problem, route it back into the build/review loop (Phase 5/6) as the
  brief, then re-verify on the stand and ask again.

Skip Gate B only when the task is not live-verifiable (pure refactor, hygiene, docs) — the green
build + e2e already prove it; ship directly.

## What never pauses

- Research, critique, development, review, build, unit + e2e tests — fully autonomous.
- The ship step (commit/push/issue comment/PR) runs automatically once Gate B is confirmed (or
  skipped for non-live tasks). It is the conductor that performs it, so the outward action stays
  under explicit, logged control.

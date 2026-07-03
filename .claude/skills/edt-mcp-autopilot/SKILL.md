---
name: edt-mcp-autopilot
description: Autonomous, spec-driven, multi-agent pipeline that takes an EDT-MCP task or issue end-to-end — research → critics → architect → parallel development → review loop → tests → live-stand check → Russian issue comment + PR. Use when asked to take a whole task/issue to completion autonomously (not for a single quick edit or a pure question).
---

# EDT-MCP Autopilot

A repeatable pipeline for delivering a whole EDT-MCP task with minimal human input. You are
the **conductor** running in the main loop: you prepare the stand, drive each fan-out phase
with the `Workflow` tool, hold the gates a workflow cannot (waiting/polling, human confirm,
irreversible ship), and finish by commenting the issue and opening a PR.

It runs **unattended**. The only mandatory human touchpoint is the **operator confirming the
live stand result** (Phase 8). Principal design questions are posted to the issue and polled
until answered (Phase 4 gate). Everything else proceeds on its own.

## Operating rules (always)

- **Spec-Driven (SDD).** Write the spec before the code; keep the spec as the contract every
  agent works against. Persist artifacts to `.claude/work/<task-slug>/` (this path is
  git-ignored — safe for internal notes, issue numbers, stand details).
- **Language.** Code, comments, commits → **English**. Issue comments + PR title + PR body →
  **Russian**.
- **Safety.** Never touch the live EDT — stand work runs only on a throwaway copy. The concrete
  stand path/port are environment-specific (the build/test sibling skills know how to reach
  them); never hardcode them in this skill.
- **Delegate machine specifics.** For build, stand, redeploy and test mechanics, use the
  sibling skills (`edt-mcp-build-test`, `edt-mcp-e2e-testing`, `edt-mcp-ready-to-deploy`) and
  the project context — do not re-derive commands here.
- **Scale to the task.** Small tasks get the minimum fan-out; large tasks get more. Pass
  `size` and a token `budget` directive to the workflows so they size themselves.
- **Skip-bias.** Every agent brief ends with: *"no behaviour change unless the spec says so;
  if the cited code is actually meaningful or risky, SKIP and report instead of forcing a
  change."*

## Checklist (create one todo per phase)

1. Phase 0 — Intake & stand prep
2. Phase 1–4 — Discover (run `discover.workflow.js`)
3. Phase 4 gate — escalate principal questions to the issue, poll until answered
4. Phase 5–6 — Build (run `build.workflow.js`)
5. Phase 7 — Build + unit tests (the real safety net)
6. Phase 8 — Live stand scenarios + operator confirmation
7. Phase 9 — Ship (issue comment + PR, Russian)

## Phase 0 — Intake & stand prep

- Read and **study the task** (the issue / tracker item / user prompt). Restate it in your own
  words and capture the acceptance criteria.
- **Sync the fork and branch from fresh master** (per the project git flow): sync upstream →
  `master` → `git pull` → `git checkout -b feature/<slug>` (or `fix/<slug>`). Use a worktree
  to keep the main clone clean.
- **Prepare the test stand now**, at the very start, so it is healthy by verification time
  (delegate to `edt-mcp-build-test`). If a fresh build is needed for the stand, kick it off.
- Create `.claude/work/<slug>/` and write `spec.md` from `references/sdd-templates.md`.

## Phases 1–4 — Discover

Run the discover workflow (it implements: 2 doc researchers → ≥3 code-research agents in waves
with loop-until-dry → adversarial critics that drop refuted findings and bounce "rework" back
into another wave → an architect that synthesises the surviving findings into a concrete spec
and a **file-disjoint developer partition**):

```
Workflow({
  scriptPath: ".claude/skills/edt-mcp-autopilot/references/discover.workflow.js",
  args: { task: "<the task statement>", size: "small|medium|large" }
})
```

It returns `{ spec, devPartition, escalations }`. Save `spec` + `devPartition` into
`.claude/work/<slug>/architecture.md` (template in `references/sdd-templates.md`).

## Phase 4 gate — escalate principal questions

If `escalations` is non-empty, do **not** guess. For each, post the question to the issue **in
Russian** (with the 2–3 options), then enter the poll loop in `references/autonomy.md`
(`ScheduleWakeup` ≈ every 10 min) until the maintainer answers. Fold the answer into
`architecture.md`, then continue. What counts as "principal" (wire contract, architecture
choice, bilingual semantics, breaking change, destructive op, ambiguous requirement) is listed
in `references/review-checklist.md`.

## Phases 5–6 — Build

Run the build workflow (parallel developers over the file-disjoint slices → a review loop where
**each round both COMPILES + runs the unit tests (the build gate) AND applies 3–4 reviewers**;
build failures and review findings both go to fixers, and a round is "clean" only when the build
is green AND no findings remain — with a max-round safeguard that escalates instead of spinning):

```
Workflow({
  scriptPath: ".claude/skills/edt-mcp-autopilot/references/build.workflow.js",
  args: {
    task: "<the task statement>",
    spec: <spec from discover>,
    devPartition: <devPartition from discover>,
    reviewChecklist: "<contents of references/review-checklist.md>",
    maxRounds: 4,
    buildCommand: "<the project build + unit-test command, WITH the machine's JDK17/maven — e.g.
                    bash source/compile.sh --java-home <..> --maven-home <..>>"
  }
})
```

> **Always pass `buildCommand`.** Reviewers read the diff but cannot compile — so an unverified
> API or a broken test churns rounds forever until a real build settles it. The build gate makes
> the compiler the arbiter *inside* the loop (it reads the surefire reports and feeds failing
> tests to the fixers as blockers). Take the exact command from the project context / CLAUDE.md
> (it is machine-specific, so it lives in `args`, not in the release-clean script). If you ever
> see the loop "reviewing and fixing the same thing round after round", that is the symptom of a
> missing/failing build gate — check `reviewLog[].buildOk`.

It returns `{ changedFiles, reviewLog, openProblems, rounds, clean }`. If `clean` is false after
`maxRounds`, treat the remaining `openProblems` as an escalation (Phase 4 gate). Record the
review outcome in `.claude/work/<slug>/review-log.md`.

> The developers edit disjoint files in the shared working tree and do **not** touch git — you
> commit at the end. If two slices must touch the same file, re-partition or run that slice with
> `isolation:'worktree'` and merge.

## Phase 7 — Build + unit tests (final confirmation)

Run the project build + unit tests yourself (delegate to `edt-mcp-build-test`). With `buildCommand`
set, the review loop already compiled + tested each round, so when it returned `clean: true` this
is a fast confirmation on your own deterministic build (it should pass first try). It is still the
real safety net — it catches missing `throws`, ratchet failures (unit `XxxToolTest`, schema/execute
parity) and golden drift. **Red → back to Phase 5/6** with the failures as the brief. Green →
continue. If a tool's wire surface changed, regenerate the golden and review the diff.

> If the loop hit `maxRounds` still red (or you did NOT pass `buildCommand`), do NOT keep spawning
> review rounds — **stop the workflow and run the build yourself**; the compiler settles what the
> reviewers were guessing at, and you fix the concrete failures directly.

## Phase 8 — Live stand scenarios + operator gate

If the task is live-verifiable (a tool behaviour, a form/metadata effect, a runtime contract):
- Redeploy the fresh build to the **throwaway** stand and run the real scenarios (the e2e
  matrix or targeted MCP calls) — delegate to `edt-mcp-e2e-testing` / `edt-mcp-ready-to-deploy`.
- **Scan the workspace log after exercising the feature.** Grep `<workspace>/.metadata/.log`
  for stack traces / errors from our code (`com.ditrix.edt.mcp.server`) logged since the redeploy.
  Runtime failures often log there WITHOUT surfacing through the MCP wire — an exception in a UI
  `Job`, a caught `Activator.logError`, or a per-item failure swallowed into a degraded result
  (a real case: a single-image `CommonPicture` whose decode threw but showed only as "No variants"
  in the gallery, found only in `.log`). A green tool response does NOT prove a clean run — treat
  any such stack as a real finding to diagnose BEFORE the operator gate, not after a user hits it.
- **Operator gate (the one human touchpoint):** use `AskUserQuestion` to show the live result
  and ask the operator to confirm it before shipping (see `references/autonomy.md`). Do not open
  the PR until the operator confirms.

If the task is not live-verifiable (pure refactor, hygiene, docs), skip the stand run and the
operator gate — the green build + e2e already prove it.

## Phase 9 — Ship

On all-green and operator OK:
- Record the key facts and lessons for future work.
- Commit (English message; include the standard AI `Co-Authored-By` trailer), push the branch.
- Comment the issue **in Russian** (what was done, how it was verified).
- Open the PR to upstream **in Russian** (title + body), body ending with
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

## Files in this skill

- `references/discover.workflow.js` — Phases 1–4 (research → critics → architect).
- `references/build.workflow.js` — Phases 5–6 (parallel dev → review loop).
- `references/review-checklist.md` — the MUST-ENFORCE criteria reviewers and the architect apply.
- `references/sdd-templates.md` — `spec.md` / `architecture.md` / `review-log.md` templates.
- `references/autonomy.md` — the issue-poll gate and the operator-confirm gate recipes.

## Notes

- This skill orchestrates the existing recipes; it does not redefine build/stand mechanics.
- Keep this skill and its references **release-clean**: English only, no machine paths, ports,
  stand names, or internal issue numbers (those belong in the project context and in the
  git-ignored `.claude/work/`).

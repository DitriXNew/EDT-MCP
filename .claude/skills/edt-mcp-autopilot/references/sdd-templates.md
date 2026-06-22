# SDD artifact templates

The pipeline is spec-driven. Persist these to `.claude/work/<task-slug>/` (git-ignored — safe
for internal notes and issue numbers). They are the contract every agent works against.

---

## `spec.md` (written in Phase 0, before any research)

```markdown
# Spec — <task title>

## Source
<issue / tracker item / user prompt — link or quote>

## Problem
<what is wrong / missing, in one short paragraph>

## Goal
<the intended outcome in one sentence>

## Scope
- In: <what this task covers>
- Out: <explicitly excluded>

## Acceptance criteria
- [ ] <testable criterion 1>
- [ ] <testable criterion 2>

## Constraints
<known rules that bind this task — link review-checklist.md; note any version/contract limits>

## Verification
<how we will prove it: unit, e2e, live stand scenario(s)>
```

---

## `architecture.md` (written in Phase 4, from the discover workflow output)

```markdown
# Architecture — <task title>

## Approach
<2-4 sentences on the chosen solution and why>

## Confirmed findings (post-critique)
<the surviving research findings the solution rests on, each file-anchored>

## Developer partition (file-disjoint slices)
### Slice 1 — <name>
- Files: <exact paths>
- Change: <exact change>
- Invariant: <what must stay true / not change>
- Tests: <unit + e2e to add/adjust>
### Slice 2 — ...

## Recommended developers: <N>

## Test plan
<build + unit; e2e tests; live stand scenarios if applicable; golden regen if wire changed>

## Escalations (if any)
<principal questions posted to the issue + the answers once received>
```

---

## `review-log.md` (appended during Phase 6)

```markdown
# Review log — <task title>

## Round 1
- <severity> <file>: <problem> -> <fix applied>
## Round 2
- ...

## Outcome
- Rounds: <n>
- Clean: <yes/no>
- Remaining (if escalated): <...>
```

---

## Agent brief shape (SDD-style, used in every dispatched agent)

Every research / dev / review / fix brief is built as:
- **exact target** — the file(s) / area + the precise change or question;
- **the invariant** — what must remain true (behaviour, contract, ratchets);
- **the skip-bias** — "no behaviour change unless the spec says so; if the cited code is actually
  meaningful or risky, SKIP and report instead of forcing a change."

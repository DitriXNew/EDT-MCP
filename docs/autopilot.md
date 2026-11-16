# AI-assisted development: the `edt-mcp-autopilot` skill

This repository ships a Claude Code project skill at `.claude/skills/edt-mcp-autopilot/` that takes a whole task or issue end-to-end with minimal human input, using a multi-agent, Spec-Driven (SDD) pipeline. The code-conduct it follows lives in [CLAUDE.md](../CLAUDE.md) and the sibling skills under `.claude/skills/`.

**Pipeline (phases):** study the task → documentation researchers → several waves of code researchers (loop-until-dry) → adversarial critics (refuted findings dropped, "rework" bounced back) → an architect that synthesises a spec and a file-disjoint developer partition → N parallel developers → a 3–4 reviewer loop until clean → build + unit tests → live-stand scenarios → a Russian issue comment and a pull request.

**How to run (Claude Code):** the skill is auto-discovered from `.claude/skills/`. Invoke it with

```
/edt-mcp-autopilot <issue number or task description>
```

It runs unattended: the only mandatory human touchpoint is confirming the live-stand result before shipping; principal design questions are posted to the issue and polled until answered. The heavy fan-out runs through the Claude Code `Workflow` tool (the two scripts under `references/`), orchestrated by the main session as a "conductor". Scale the fan-out with the `size` argument (`small` / `medium` / `large`).

## ⚠️ This skill consumes a LOT of tokens

It deliberately spawns many subagents — documentation and code researchers in waves, a critic panel, parallel developers, and a cyclic reviewer pool. Measured cost per real task in this repository (subagent tokens only — the conductor, the Maven build and the live-stand verification add more on top):

| Phase | Subagents | Subagent tokens | Wall time |
|---|---|---|---|
| Discover (research → critics → architect) | ~8–9 | ~0.6–0.9 M | ~15 min |
| Build (parallel developers → reviewer loop) | ~11–15 | ~0.7–0.8 M | ~15 min |

So a single task's discover + build phases alone are roughly **20–25 subagents and ~1.3–1.8 M subagent tokens**; a full end-to-end task (with the conductor, the builds, golden regeneration and live-stand checks) typically runs **several million tokens and 30–60+ minutes**. A misconfigured run can be far worse — an early, pre-hardened version once burned ~9.8 M tokens / ~210 agents on a no-op before the guard rails were added.

Use it for substantial whole-task work — a feature, a non-trivial bug, or a new tool — **not** for small edits or quick questions, where working directly is far cheaper.


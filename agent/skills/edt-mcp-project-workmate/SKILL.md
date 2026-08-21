---
name: edt-mcp-project-workmate
description: Use EDT-MCP ask_workmate for explicitly requested 1C project research or a second opinion, then poll and verify the result. Not a default delegation route.
---

# EDT-MCP Workmate research

## Purpose and trigger

Use this skill only when the user explicitly asks for Workmate research or an
authorized second opinion on an exact 1C project question.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Keep one bounded subject-matter question, exact project/object identity, and
requested answer mode in scope. Workmate is not a default implementation,
Git, production-write, or proof route.

## Primary workflow

1. Resolve the project and inspect `get_tool_guide` for `ask_workmate`.
2. Establish whether the configured service and shared tools enforce the
   intended read/write boundary; otherwise use an isolated disposable target or
   obtain explicit authority for possible effects.
3. Submit one `ask_workmate` request with the resolved exact `projectName` and
   retain its returned job identity; do not omit the project and fall back to
   Workmate's default context.
4. Poll only that job with `get_job_status`; do not duplicate a committed
   request to discover progress or recover from ambiguity.
5. Verify target identity and load-bearing claims with direct read-only
   EDT-MCP evidence before using the answer.

## Authority rule

External model contact, shared write-capable tools, project mutations, and any
retry after uncertain dispatch require the relevant user authority. A
read-only prompt alone is not an enforcement boundary.

## Stop rule

Stop when Workmate is unavailable, compatibility or target identity is unclear,
completion is unconfirmed, unexpected writes appear, or the next step needs new
material authorization.

## Completion signal

Return the exact question/target, confirmed Workmate final state, directly
verified claims, unresolved uncertainty, and any detected project effects.

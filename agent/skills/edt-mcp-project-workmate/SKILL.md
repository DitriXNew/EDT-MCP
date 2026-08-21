---
name: edt-mcp-project-workmate
description: Use EDT-MCP ask_workmate for explicitly requested 1C project research or a second opinion, then poll and verify the result. Not a default delegation route.
---

# EDT-MCP Workmate research

## Purpose and trigger

Use this skill only when the user explicitly asks for Workmate research or an
authorized second opinion on an exact 1C project question.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Keep one bounded subject-matter question, exact project/object identity, and
requested answer mode in scope. Workmate is not a default implementation,
Git, production-write, or proof route.

## Primary workflow

1. Resolve the project and inspect `get_tool_guide` for `ask_workmate`.
2. Treat `projectName` as Workmate context, not an authorization or isolation
   boundary. For a bridge/tool-capable call that could write, require a proven
   filtered/read-only capability set or actual filesystem/process containment
   limited to every authorized project and file target; a one-project EDT
   workspace is not containment. Otherwise do not delegate mutations and stop
   for authority or containment.
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

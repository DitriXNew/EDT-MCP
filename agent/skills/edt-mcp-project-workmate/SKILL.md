---
name: edt-mcp-project-workmate
description: Use EDT-MCP ask_workmate for explicitly requested 1C project research or a second opinion, then poll and verify the result. Not a default delegation route.
---

# EDT-MCP Workmate research

## Goal

Send one bounded project-research question to a compatible 1C:Workmate
installation and retain responsibility for target identity, verification, and
final artifacts.

## Use when

- the user explicitly asks to use Workmate;
- an authorized second opinion or broad read-only research pass is useful;
- the current server exposes `ask_workmate` and the target project is ready.

## Do not use when

- ordinary direct EDT-MCP research is sufficient;
- the user excludes delegation;
- the initial request is a production mutation, database update, or destructive
  operation;
- Workmate compatibility or authorization is unknown.

## Preflight

1. Resolve the exact EDT project and confirm it is open and ready.
2. Inspect `get_tool_guide('ask_workmate')`; the tool is optional and may be
   disabled.
3. Separate the subject-matter question from executor mechanics, Git workflow,
   report paths, and later implementation.
4. State a read-only research boundary and request exact project/object
   identities and evidence.
5. Review whether Workmate may contact an external model service or invoke
   tools under the user's Workmate configuration.
6. Before `ask_workmate` can share write-capable MCP tools, require one of: a
   configuration proven to expose read-only operations only; an isolated
   disposable checkout/project; or explicit authority for possible code or
   metadata mutations. Stop if none is established. A read-only prompt and an
   after-the-fact diff are not enforcement controls.

## Dispatch and poll

1. Use answer mode when the result must return to the caller; chat mode hands
   the task to Workmate's UI and does not return the answer here.
2. Start one `ask_workmate` job and retain its `jobId`.
3. Poll only that job with `get_job_status`; do not submit a duplicate question
   to check progress.
4. Respect the current timeout and continuation semantics from the guide.
5. Do not treat an intermediate plan, silence, elapsed time, or a completion
   warning as a confirmed final answer.

## Finality and retry safety

Current Workmate integration can continue across assistant turns until its
finality contract is satisfied. If the result says completion is unconfirmed,
the job timed out after dispatch, or the request was already committed to
Workmate, inspect the returned state before retrying. A blind retry can run the
same research twice or overlap work still executing in Workmate.

## Verification

Check the reported project and object identity. Verify the load-bearing or
ambiguous claims with direct read-only EDT-MCP calls. Workmate's answer is
research input, not proof that files, metadata, runtime data, or Git state were
changed correctly.

## Safety

The read-only instruction is a workflow boundary, not a hard sandbox around
Workmate's own tools. Inspect unexpected project changes before continuing.
Do not delegate production writes without separate, explicit authority.

## Stop conditions

Stop when the tool is unavailable, the project is not ready, target identity
differs, completion is unconfirmed, unexpected writes are detected, or the
next step requires a new material authorization.

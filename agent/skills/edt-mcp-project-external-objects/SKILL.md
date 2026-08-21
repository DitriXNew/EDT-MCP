---
name: edt-mcp-project-external-objects
description: "Work with external data processor/report projects through EDT-MCP: metadata, forms, builds, and one-run debug launch. Not for prebuilt files outside an EDT project."
---

# EDT-MCP external objects

## Purpose and trigger

Use this skill for metadata, code, forms, builds, or a bounded debug run of an
external data processor/report that exists in an EDT external-object project.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Select the exact external-object project and qualified
`ExternalDataProcessor.<Name>` or `ExternalReport.<Name>` target. Do not
substitute its linked base configuration, a normal configuration object, or a
prebuilt `.epf`/`.erf` outside an EDT project.

## Primary workflow

1. Resolve the project, object, linked base application, and runtime target with
   `list_projects`, `get_applications`, and `list_configurations`.
2. Route metadata structure to `edt-mcp-project-metadata`, BSL changes to
   `edt-mcp-project-local-fix`, and managed-form work to
   `edt-mcp-project-forms`; re-read and validate the exact owning object after
   a mutation.
3. For a build, resolve the qualified target and compare its simple name across
   both external-object kinds. `objectName` is a simple-name selector; stop on
   a processor/report collision unless building both is explicitly authorized.
   Before building, obtain explicit update/restructure authority for the
   associated build infobase, or confirm that it is disposable; the platform
   dumper may prepare and update that infobase even for an ordinary build.
   Call `build_external_objects` into a fresh empty staging directory, omitting
   `objectName` only for an authorized build-all. Set `recordBuildTime=false`
   unless changing the source object's Comment was requested. Verify the result
   and staged artifact before promotion; preserve an existing artifact and
   authorize its exact replacement before any in-place build or promotion.
4. For a debug run, complete target, launch-policy, disclosure, credential, and
   `debug_status` preflight first, using `set_infobase_credentials` only when
   authorized. If a matching live client exists, use it only as an explicitly
   authorized target; stop when the external object requires a fresh launch.
   Otherwise call `set_breakpoint` before `debug_launch` and retain task-owned
   IDs. If a preflight race returns `alreadyRunning=true`, refresh the uniquely
   identified target, resume only a task-caused suspension, remove task-owned
   temporary state, and stop. Terminate and relaunch only with explicit user
   authorization.
5. Refresh `debug_status` after launch. Call `wait_for_break` and inspect with
   `get_variables` only when the current help proves the intended debug target
   is unambiguous. Otherwise remove only task-owned temporary state and stop.
6. Finish with `resume` for any task-suspended execution, calling
   `remove_breakpoint`, and using `terminate_launch` only for a uniquely
   identified, task-owned launch whose termination is authorized.

## Authority rule

Building, credentials, infobase update/restructure, external-change handling,
launch/restart, artifact replacement, data disclosure, and termination require
the authority applicable to their exact targets and effects.

## Stop rule

Stop on wrong project kind, ambiguous object/runtime/debug target, unsupported
write, missing prerequisite, unavailable credentials, or unapproved side
effects. Never operate on an unrelated active launch to make the route work.

## Completion signal

Report the exact project/object, confirmed changes or build artifacts, bounded
runtime evidence when requested, partial or unproved claims, and cleanup of all
task-owned temporary state.

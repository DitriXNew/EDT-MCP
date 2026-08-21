---
name: edt-mcp-project-external-objects
description: "Work with external data processor/report projects through EDT-MCP: metadata, forms, builds, and one-run debug launch. Not for prebuilt files outside an EDT project."
---

# EDT-MCP external objects

## Goal

Address external data processors and reports through their EDT external-object
project, build them deliberately, and run them under debugging without
persistently rewriting launch configuration.

## Use when

- the EDT project kind is external objects;
- inspecting or changing `ExternalDataProcessor` or `ExternalReport` metadata;
- building `.epf` or `.erf` deliverables;
- launching one external object with breakpoints;
- applying form or BSL workflows inside an external object.

## Do not use when

- the object exists only as a prebuilt file and has not been imported into an
  EDT external-object project;
- the task targets a normal configuration `DataProcessor` or `Report`;
- the user requested plugin implementation.

## Addressing and routing

1. Select the exact external-object project with `list_projects`.
2. Pass that project as `projectName` to metadata, code, and form tools.
3. Address top objects as `ExternalDataProcessor.<Name>` or
   `ExternalReport.<Name>`; qualify the type when names collide.
4. Address members and forms beneath that FQN exactly as in configuration
   metadata.
5. Do not substitute the linked base configuration project. Current
   `MetadataScope` routing resolves external top objects while using the linked
   configuration only where the model needs its types and version.

## Inspect and change

Use `get_metadata_objects`, `get_metadata_details`, module readers/writers, and
the current structured metadata/form tools. Confirm the live guide before the
first mutation because supported member kinds can differ from configuration
objects.

## Build

Use `build_external_objects` for one named object or all objects. Establish the
associated infobase and resolvable runtime first. Before invoking the tool,
require either explicit authority to update and restructure that infobase or a
disposable build infobase where those effects are acceptable. Association alone
is not authority: EDT preparation may update/restructure the infobase and
automatically accept the corresponding dialogs.

`objectName` accepts only a simple name, not a qualified FQN. If both
`ExternalDataProcessor.X` and `ExternalReport.X` exist, passing `X` can select
both, so stop and report the ambiguity or build both only with explicit user
authority.

Choose `recordBuildTime` deliberately. When it is omitted or `true`, the tool
replaces the object's entire `Comment` with a build timestamp before dumping the
external file. If dumping then fails, the original comment can already be lost.
Use `recordBuildTime=false` to preserve the comment. Replacing the comment with
a timestamp requires an explicit user choice.

Build into a fresh unique staging directory because the tool deletes an
existing target artifact before the replacement dump completes. Promote the
new EPF/ERF only after every intended object built successfully. Promotion is a
separate, explicitly authorized filesystem or repository operation; EDT-MCP
does not provide an atomic promotion primitive. Rebuild in place only with
explicit authority and after preserving the last good artifact.

Record the staging/output directory and built/failed object counts. A
successful empty all-objects build is not proof that a requested named object
exists.

## Debug launch

Set the smallest required source breakpoint with `set_breakpoint`, passing the
external-object project as `projectName`, before starting the object, and
retain the returned `breakpointId`. Then use
`get_tool_guide('debug_launch')` and resolve both `updateBeforeLaunch` and
`externalInfobaseChanges` before starting. Any database update or override of
external infobase changes requires explicit authority. Pass both chosen values
explicitly rather than relying on defaults, and stop when the current guide and
available authority do not establish a safe combination.

Call `debug_launch` against the base configuration application and pass
`externalObjectProjectName` plus `externalObjectName`. EDT builds the object
for debugging; a prebuilt file cannot provide source breakpoints.

If authentication is required, confirm the existing infobase user and configure
credentials before launch. Target `set_infobase_credentials` by the exact
`launchConfigurationName` when the launched client also needs credentials, then
require `clientConfigured=true`; a project/application target configures only
the update agent. A local launch stores a non-empty password in clear-text
workspace metadata, while a shared launch refuses it; use such storage only
with explicit authority or choose OS authentication, an empty password, or
secure manual client configuration.

Use `startupOption` only for the current launch's `/C` payload. It is applied to
a working copy and does not persistently change the saved EDT launch
configuration. It is valid for runtime-client launches, not Attach launches.

Check `debug_status` after the asynchronous start. Before `wait_for_break` or
`get_variables` can return infobase data, require a confirmed sanitized or
non-production target, enabled server-side redaction adequate for the requested
values, or explicit authorization for the specific disclosure from the named
target. Redaction is optional and incomplete, so keep reads bounded; filtering
after a raw response cannot undo disclosure. On a hit, inspect only the frames
and variables needed for the question; the break notification alone is not
runtime evidence. After the probe, including failure, timeout, or interruption,
resume first if execution is suspended, then remove the temporary breakpoint by
its retained `breakpointId`. Use `terminate_launch` only when ending a task-owned
launch is appropriate. Hand deeper stepping or expression work to
`edt-mcp-project-runtime-debug`. Use `restartIfRunning` only when terminating
the current client is authorized.

## Verification

Re-read changed metadata/source, validate the owning object, verify build
artifacts and counts, and collect runtime evidence only from the launched
object's inspected frame and bounded variables. Report whether the suspended
thread was resumed, the temporary breakpoint was removed, and any task-owned
launch was terminated.

## Stop conditions

Stop when the project kind is wrong, the object name is ambiguous, the linked
configuration/runtime prerequisite is missing, a prebuilt file has not been
imported, or the requested write is unsupported for external objects.

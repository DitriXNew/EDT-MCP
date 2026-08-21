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
associated infobase and resolvable runtime first. Choose `recordBuildTime`
deliberately. When it is omitted or `true`, the tool replaces the object's
entire `Comment` with a build timestamp before dumping the external file. If
dumping then fails, the original comment can already be lost. Use
`recordBuildTime=false` to preserve the comment. Replacing the comment with a
timestamp requires an explicit user choice.

Record the output directory and built/failed object counts. A successful empty
all-objects build is not proof that a requested named object exists.

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

Use `startupOption` only for the current launch's `/C` payload. It is applied to
a working copy and does not persistently change the saved EDT launch
configuration. It is valid for runtime-client launches, not Attach launches.

Check `debug_status` after the asynchronous start, then use `wait_for_break`.
On a hit, inspect the returned frames and read only the bounded variables needed
for the question with `get_variables`; the break notification alone is not
runtime evidence. After the probe, including failure or timeout, resume first
if execution is suspended, then remove the temporary breakpoint by its retained
`breakpointId`. Use `terminate_launch` only when ending a task-owned launch is
appropriate. Hand deeper stepping or expression work to
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

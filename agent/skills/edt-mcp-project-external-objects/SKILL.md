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
exact associated base infobase and resolvable runtime first. Call
`get_applications` for the external-object project: applications are inherited,
so retain its `inheritedFromProject`, the chosen `applications[].id`, and the
`defaultApplicationId`. `build_external_objects` has no application selector;
therefore proceed only when the associated build target is unambiguous (for
example, the sole application or an explicitly confirmed default). Stop instead
of guessing when several applications remain possible. The current MCP surface
does not enumerate installed 1C runtimes, so confirm the exact registered
runtime from the established EDT/project environment; if that cannot be
established, stop before using the build as a probe.

Determine before the build whether that same infobase requires authentication.
When it does, require an existing user and available credentials, then call
`set_infobase_credentials` with the base `projectName` from
`inheritedFromProject` and that exact `applicationId`. Verify the returned
`project` and `applicationId`; `clientConfigured=false` is expected for this
agent-only target and is sufficient for the unattended build. Never put the
user's password or another secret value in logs, reports, examples, or committed
files. Stop if authentication requirements are unknown, credentials are
unavailable, or the credential result names another target.

Before invoking the build, require either explicit authority to update and
restructure that exact infobase or a disposable build infobase where those
effects are acceptable. Association alone is not authority: EDT preparation
may update/restructure the infobase and automatically accept the corresponding
dialogs.

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

Resolve the base runtime before `debug_launch`. Call `get_applications` for the
external-object project to obtain the inherited base project and real
`applicationId`, and call `list_configurations` with `type='client'` and that
base `projectName`. Confirm exactly one of the two target shapes accepted by
`debug_launch`: either the exact returned configuration `name` as
`launchConfigurationName`, or the base `projectName` plus the real
`applicationId`. Treat a synthetic `launch:<name>` value only as a launch
identifier, never as a real application ID. Stop when multiple applications or
runtime-client configurations remain unresolved; the external-object project
name alone does not identify an infobase/runtime.

If authentication is required, confirm the existing infobase user and configure
credentials before launch against that same confirmed base target. Target
`set_infobase_credentials` by the exact `launchConfigurationName` when the
launched client also needs credentials, then require `clientConfigured=true`;
otherwise use the base `projectName` plus its exact real `applicationId`, which
configures only the update agent. Verify the returned target identity. A local
launch stores a non-empty password in clear-text workspace metadata, while a
shared launch refuses it; use such storage only with explicit authority or
choose OS authentication, an empty password, or secure manual client
configuration.

Call `debug_launch` against that confirmed base-runtime target and pass the
external-object project as `externalObjectProjectName` plus the object name as
`externalObjectName`. Qualify the latter as
`ExternalDataProcessor.<Name>` or `ExternalReport.<Name>` when the simple name
collides. EDT resolves and builds that project's object, then applies its built
file as `/Execute`; a prebuilt file cannot provide source breakpoints. Verify
the echoed `externalObjectProjectName` and resolved `externalObjectName` before
claiming that the intended object was launched.

Branch immediately when the response has `alreadyRunning=true`: no fresh
client was spawned and the external-object `/Execute` overrides were not
applied. Do not continue to `debug_status`, `wait_for_break`, or the object
probe, and never attribute the unrelated existing session to this workflow.
Remove the temporary breakpoint by its retained `breakpointId`, then either
stop and report that the existing client prevented execution, or obtain
explicit authority to terminate/restart that exact matching client. For an
authorized retry, confirm the old client is gone, set a new temporary
breakpoint, and perform a fresh launch before probing.

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
its retained `breakpointId`. Before any launch cleanup, obtain a fresh
`debug_status` live-launch view and exclude Attach launches from the candidate
set (`includeAttach=false` where supported). Call `terminate_launch` only when the
echoed task launch configuration identifies exactly one live runtime launch and
task ownership is proven. If either uniqueness or ownership is unproven, do not
terminate: remove only task-owned breakpoints and report that the launch was
intentionally left untouched. Hand deeper stepping or expression work to
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

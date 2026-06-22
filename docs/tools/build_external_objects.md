# build_external_objects

Build (compile to disk) the external data processors/reports of an EDT external-object project to .epf/.erf files. Build ONE object with objectName, or ALL of them when objectName is omitted. Requires an associated infobase + a resolvable 1C runtime (like update_database): if missing, set it up with create_infobase / set_infobase_credentials. Full parameters and examples: call get_tool_guide('build_external_objects').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT external-object project name to build (required). Must be a project created with create_project projectKind=externalObjects. |
| objectName | — | string | Name of a single external data processor/report to build. Omit to build ALL external objects of the project. |
| outputDir | yes | string | Filesystem directory the built .epf/.erf files are written to (required). Relative paths are resolved to absolute; the directory is created if missing. If the path exists but is a file, the call errors. |

## Guide
Builds (compiles to disk) the external data processors/reports of an EDT **external-object project** to `.epf`/`.erf` files - the headless equivalent of EDT's "save external data processor/report to file" action. Build one named object or all of them in a single call.

## When to use
- You authored an external data processor/report in an EDT external-object project (created with `create_project projectKind=externalObjects`) and need the deliverable `.epf`/`.erf` file(s) on disk.
- Producing artifacts for a build pipeline, for loading into a running 1C client, or for distribution.

## Preconditions (hard, variant A)
The platform compiles the object against an **associated infobase + a resolvable 1C runtime**, exactly like `update_database`. If that is missing the build cannot run:
- A registered 1C runtime must be installed/resolvable in EDT (otherwise the tool returns a graceful "dumper is not available" error).
- If the associated infobase requires user authentication, set the connection credentials first:
  - `create_infobase` to create/associate an infobase, and
  - `set_infobase_credentials` (user/password) so the unattended build can authenticate.
- The project must be open and finished building (a transient "Project is building" is refused with a retry hint).

## Parameter details
- `projectName` (required) - the EDT external-object project to build. Must be a `projectKind=externalObjects` project.
- `objectName` (optional) - the name of a single external data processor/report to build. **Omit to build ALL** external objects of the project.
- `outputDir` (required) - filesystem directory the `.epf`/`.erf` files are written to. Relative paths are resolved to absolute; the directory is created if missing. If the path exists but is a file, the call errors. A directory outside the EDT workspace is allowed but flagged (`outsideWorkspace: true`) - the server is trusted-caller-only.

## What you get
A JSON result:
- `success` - `true` only when **every** requested object built; `false` (with `isError`) when any object failed.
- `project`, `outputDir` (absolute), `built`, `failed`.
- `results` - one entry per object: `{name, success, path, durationMs}` on success or `{name, success:false, error, durationMs}` on failure (a build of all objects continues past one bad object). `durationMs` is that object's build time in milliseconds.
- `outsideWorkspace` - present and `true` when `outputDir` is outside the workspace.
- `message` (on a successful build) reports the total build time and a `built at` timestamp, so you can tell which build is the latest.

## Notes & gotchas
- **Build ALL of an empty project** (no external objects yet, `objectName` omitted) is a clear **success** with `built: 0`, `failed: 0` and a "nothing to build" message - not an error. Note the prerequisites are checked first: the 1C build service and the infobase/runtime precondition are validated before enumeration, so a project with no associated infobase returns the precondition error rather than the empty "nothing to build" success. Requesting a specific `objectName` that the project does not have IS a value-naming "not found" error.
- **Unattended-safe:** the compile/dump runs in a background job off the JSON-RPC thread, with a bounded timeout; the "Configure Infobase access Settings" and "Application update"/"Restructure data" modals are auto-handled so the call never blocks.
- A build failure that looks like a connection/authentication problem is annotated with a hint pointing at `create_infobase` / `set_infobase_credentials`.
- **Stale output is deleted before each build:** the target `.epf`/`.erf` is removed before the object is dumped, because EDT can cache the compiled artifact and leave an old version in place; deleting it first forces a clean, current build. If the file cannot be deleted, that object is reported as a failure rather than silently shipping a stale file.
- This **writes to the filesystem at the path you give** - double-check `outputDir`.

## Maintainer note
After adding/changing this tool, the `tools/list` golden snapshot (`tools_list.golden.json`) MUST be regenerated against the live server on the EDT stand - it cannot be hand-edited.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

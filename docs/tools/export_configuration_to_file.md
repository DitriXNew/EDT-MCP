# export_configuration_to_file

Export the configuration (.cf) or an extension (.cfe) FROM an application's infobase to a file via the 1C thick client - the infobase's content, not the project's (for project sources use export_configuration_to_xml). An extension project in projectName exports that extension. Refused while EDT reports the infobase out of date with the project: run update_database first, or pass allowOutOfDate=true. outputFile must not exist - it is never overwritten. Returns a jobId when not finished within waitSeconds; poll get_job_status. Full parameters and examples: call get_tool_guide('export_configuration_to_file').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | Configuration project, or an extension project to export that extension. |
| outputFile | yes | string | Absolute path of the file to create: .cf for a configuration, .cfe for an extension; its folder must exist. |
| applicationId | — | string | Application from get_applications (default: the project's default application). |
| extensionName | — | string | Export this extension of the configuration instead: its name in the infobase (or its EDT project name). |
| allowOutOfDate | — | boolean | true = export even when EDT reports the infobase out of date (default false). |
| waitSeconds | — | integer | How long this call waits for the result before returning a jobId; defaults to 20, accepts 0 to 45. |

## Guide
Dumps the configuration to a `.cf` file, or one configuration extension to a `.cfe` file, FROM an application's infobase - the headless equivalent of EDT's "Export configuration to file" wizard. The 1C Designer (thick client) of the platform EDT resolves for the project and infobase writes the file.

## When to use
- You need a `.cf` / `.cfe` deliverable: a release artifact, a file to load into another infobase, a snapshot for support.
- Use `export_configuration_to_xml` instead when you want the PROJECT's sources as XML files. This tool reads the INFOBASE, so the file holds what the infobase contains, not the unsaved or unpublished state of the project.

## Parameter details
- `projectName` (required) - a configuration project, or an extension project. An extension project exports THAT extension: the dump is taken from the infobase of its base configuration project's application, and `extensionName` may be omitted.
- `outputFile` (required) - the absolute path of the file to create. It must end in `.cf` for a configuration and `.cfe` for an extension, its folder must already exist, and the file must NOT exist: the tool never replaces a file. Pass a new name, or remove the old file yourself first.
- `applicationId` (optional) - the application whose infobase is dumped, from `get_applications`. Default: the project's default application. For an extension project, the application belongs to the base configuration project.
- `extensionName` (optional) - export this extension of the configuration instead of the configuration itself. Give the extension's name as the infobase knows it (the extension configuration's `Name`); the EDT project name of an extension project of this configuration is accepted too. With an extension project in `projectName` it must be omitted or name that same extension.
- `allowOutOfDate` (optional, default `false`) - see "Infobase vs project" below.
- `waitSeconds` (optional, default 20, 0 to 45) - how long this call waits before returning a `jobId`. The dump itself keeps running in the background either way.

## Infobase vs project
Before dumping, the tool prepares the application (starts its standalone server if needed and connects the project, as EDT's own export command does) and reads EDT's own comparison of the project with what was last applied to the infobase (for an extension, the extension project) - the reading `get_applications` shows as the update state:
- `inSync` - EDT reports them equal (`get_applications`: up to date).
- `outOfDate` - EDT reports a difference: the call is REFUSED and nothing is written. Apply the project with `update_database` first, or pass `allowOutOfDate=true` to dump what the infobase holds now.
- `unknown` - EDT could not tell (no project holds that extension, the comparison did not settle within 30 s, or the state could not be read). The dump is made and the result says so explicitly.

The state is reported as `infobaseSync` in the result.

## What you get
Markdown with front matter: `status`, `source` (`configuration` / `extension`), `project`, `extensionName`, `applicationId`, `application`, `infobase`, `outputFile`, `sizeBytes`, `infobaseSync`, `platformVersion`.

Success is reported only after the file was seen on disk: the Designer writes to a temporary `<name>.partial-<id>.cf(e)` beside `outputFile`; the tool checks it is non-empty and written by this call, renames it to `outputFile` (never replacing a file that appeared meanwhile), and reads the size back. On any failure, cancellation or timeout the temporary file is removed and `outputFile` is not created.

## Background job
The dump can take minutes on a large configuration. When it does not finish within `waitSeconds`, the call returns a `jobId`: poll `get_job_status` with it, and do not start the same export again. `cancel_job` stops a dump that has not finished: EDT is asked to stop the Designer, and the temporary file is removed once it has ended. The phases are bounded: waiting for another operation on the same infobase (5 min), preparing the application (5 min), the dump itself (30 min).

## Errors
- **No platform with a thick client** - EDT found no locally installed 1C:Enterprise version matching the project and infobase. Install it (with the thick client) and register it in EDT's installed platforms.
- **Login failed** - store the infobase user with `set_infobase_credentials`; it needs the right to administer the configuration.
- **Unknown extension** - the Designer's own message is returned; check the extension is installed in this infobase under that name.
- **Busy infobase** - another `update_database` / `launch` / export holds the same infobase; wait and retry.
- **Port conflict** - the standalone server could not start because its ports are taken; free them and retry.

## Notes & gotchas
- The tool writes only `outputFile` (and its temporary sibling). It changes neither the project nor the infobase, but preparing the application may start its standalone server.
- A remote-only platform installation cannot run the Designer locally; install the platform on the EDT machine.

## Maintainer note
After adding/changing this tool, the `tools/list` golden snapshot (`tools_list.golden.json`) MUST be regenerated against the live server on the EDT stand - it cannot be hand-edited.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

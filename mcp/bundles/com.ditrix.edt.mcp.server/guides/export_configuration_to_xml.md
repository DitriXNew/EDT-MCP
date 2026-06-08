Exports an EDT configuration project to a directory of XML source files - the EDT menu action **Export → Configuration to XML Files**, equivalent to the 1C platform's `DumpConfigToFiles`. Use it to produce the XML form of a configuration for storage repositories, diffs, or external tooling.

## When to use
- You need the configuration in 1C XML dump format (e.g. to load into a 1C storage, compare, or process with other tools).
- Producing a portable snapshot of the project's metadata as files.

## Parameter details
- `projectName` (required) - the EDT configuration project to export.
- `outputPath` (required) - filesystem directory for the XML files. Relative paths are resolved to absolute; the directory is created if missing. If the path exists but is a file, the call errors.

## What you get
Markdown with a YAML front-matter summary (tool, status, project, `outputPath`) confirming the export. If the destination is outside the EDT workspace, a note/`outsideWorkspace` flag is added (a warning, not a failure).

## Notes & gotchas
- This **writes to the filesystem at the path you give** - it can write outside the workspace. The server is trusted-caller-only (loopback + optional token); double-check `outputPath`.
- Requires the EDT CLI API plugin (`com._1c.g5.v8.dt.cli.api`, present on 2025.x / 2026.1). Without it you get a clear "not available" error - that's environment, not a bug.
- This is the metadata-to-XML direction; the reverse (load XML into a project) is `import_configuration_from_xml`.

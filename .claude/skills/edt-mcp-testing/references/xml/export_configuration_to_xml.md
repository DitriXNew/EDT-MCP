# export_configuration_to_xml — acceptance procedure

Use a ready configuration project with EDT's CLI API installed and a scratch output directory outside the fixture. This writes XML files without mutating the project model or infobase. A closed project is opened by the API; an unknown project is refused before creating the destination.

```text
export_configuration_to_xml(projectName="TestConfiguration", outputPath="<absolute scratch directory>")
```

- Read the Markdown resource and YAML front matter: `status: success`, project and normalized absolute `outputPath`. Success is not a JSON `success:true` payload.
- Require a regular root `Configuration.xml`, not just a nonempty directory. For an existing root, snapshot mtime, size and SHA-256 first; success requires a strictly newer mtime or changed size/digest. A missing or unchanged stale root must not mask a silent platform refusal.
- An unprovable rewrite returns a structured error naming the root and suggesting the EDT Error Log, project readiness, permissions and a fresh `outputPath`. Equal bytes and a coarse/equal timestamp cannot prove rewriting; retry in a new directory.
- Assert the fixture stays clean. Remove only the scratch directory you created after verifying its resolved path.
- Check missing parameters, a destination that is a file and an unknown project. Require actionable errors; the unknown-project case must leave the destination absent. Missing CLI support is an environment refusal, not a successful export.

`outputPath` is created when absent; relative paths become absolute. An outside-workspace warning/flag does not itself fail export. Read `tests/e2e/tools/test_export_configuration_to_xml.py` and `get_tool_guide('export_configuration_to_xml')` for the executable matrix and API details.

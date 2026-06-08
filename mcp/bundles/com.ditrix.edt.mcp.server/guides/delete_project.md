Removes an EDT project from the workspace. It is the inverse of `import_configuration_from_xml` and the natural cleanup step after an import round-trip (export -> import -> verify -> delete).

## Think twice - destructive (confirm-preview)

Removing a project is destructive. With `deleteContent=true` the project's files are ALSO deleted from disk and cannot be recovered. The tool is guarded by a two-phase workflow (mirroring delete_metadata):
1. **Preview** (`confirm` omitted / false, the default): resolves the project and returns `action='preview'`, `confirmationRequired=true`, the project name and the `deleteContent` flag - WITHOUT removing anything.
2. **Delete** (`confirm=true`): removes the project; the result reports `action='deleted'`.

## Parameter details

- **projectName** (required): the EDT project to remove. Must exist in the workspace (use `list_projects` to see what is available).
- **deleteContent** (boolean, default false): false unregisters the project from the workspace but leaves its files on disk (it can be re-imported); true also deletes the files from disk (irreversible).
- **confirm** (boolean, default false): false previews; true performs the removal.

## Result

JSON with `action` ('preview'/'deleted'), `confirmationRequired` (preview only), `project`, `deleteContent`, and a `message`.

## Gotchas

- The project must exist; a missing project is rejected with a `list_projects` hint.
- Terminate any running launch against the project first; a busy project can fail to delete while it is held.
- With `deleteContent=false` the on-disk files remain and can be re-imported with `import_configuration_from_xml`.

Force EDT to fully rebuild and re-validate a project: refreshes its files from disk, drops every existing validation marker, re-imports the model, and BLOCKS until EDT has finished recomputing derived data. Use it to recover from a stuck or stale validation state.

## When to use
- Validation looks wrong or stale: markers don't match the code, already-fixed errors still linger, or a project is stuck "building".
- You changed files on disk outside EDT and want the model resynced.
- A tool reported the project was mid-build and you want a settled state before retrying.

## Parameter details
- `projectName` - the project to clean. **Omit to clean every EDT project** in the workspace.

## What you get
JSON: `success`, `projectsCleaned` (count), `projects` (the names cleaned), and a human-readable `message`. The call returns only AFTER the rebuild + re-validation finish (it waits up to ~3 min per project), so the project is fully settled when it returns.

## Notes & gotchas
- This is a rebuild, not a destructive action - but it **discards UNSAVED in-memory model edits** (they are recomputed from disk). Save pending changes first.
- A project that is currently building is refused with a clear "still building" message; wait and retry. An unknown or closed project returns a "Project not found" / "Project is closed" error that names the value.
- Heavy: it re-indexes the whole configuration. To read the result afterwards use `get_problem_summary` (counts) or `get_project_errors` (per-marker detail); for a lighter re-check prefer `revalidate_objects`.

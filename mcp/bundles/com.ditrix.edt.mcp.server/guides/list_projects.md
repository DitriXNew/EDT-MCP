Every project in the EDT workspace, with the state and metadata you need to pick the right one to work on. This is the usual STARTING POINT - most other tools take a `projectName`, and this is where you discover the valid names.

## When to use
- First thing in a session, to learn which projects exist and their exact names.
- To check whether a project is open, ready, or still building before calling a heavier tool against it.
- To tell configuration projects from extension projects, or to see a project's natures.

## Parameter details
- `format` (optional, default `md`) - the output format. `md` renders the human Markdown table; `json` returns the same rows as a machine payload in `structuredContent`, for a programmatic consumer that would otherwise have to scrape the table (this is what the multi-EDT proxy uses to build its routing).

It always lists the whole workspace - there is no filter.

## What you get
With `format=md` (the default): a Markdown table with one row per project - **Name**, **State** (`ready` / `building` / `not_available` / `unknown`), **Path** on disk, **Open** (Yes/No), **EDT Project** (Yes if it has the 1C configuration or extension nature), an abbreviated **Natures** list (first few, with `...+N` for the rest), and a trailing **Reason** column (empty when ready). A total count is shown above the table.

With `format=json`: `structuredContent` carries `{"projects": [{"name", "state", "reason"?, "path", "open", "edtProject", "natures"}]}` - the same rows, machine-readable. `open` is a boolean; `edtProject` is a boolean ONLY when the nature was actually determined and is **omitted** for a closed or uninspected project (mirroring the table's `-`), so a consumer can tell "not an EDT project" from "not inspected".

## State
- `ready`: the EDT context is running and its derived data is complete. JSON omits `reason`; the Markdown Reason cell is empty.
- `building`: derived data is still being computed. The reason describes the build; poll `list_projects` until it reads `ready`.
- `not_available`: the project is missing, closed, not an EDT project, has unreadable natures, or its EDT context is not started, starting, or restarting. The reason distinguishes these cases and gives the next step.
- `unknown`: the project manager or build state could not be read. The reason names the unavailable service or state.

Every non-ready JSON row carries a `reason` string with the same explanation as the trailing Markdown Reason cell.

## Notes & gotchas
- `not_available` carries a reason. A context that is not started may still be starting just after import or creation: poll `list_projects` first. If it stays there, close and reopen the project in EDT, or use `delete_project` and import it again; EDT does not restart it by itself. A restarting reason means a project-restart job is pending in the workspace, which may belong to another project; poll `list_projects`. Starting contexts also mean poll; if a starting context stays stranded after a failed clean, run `clean_project` again or close and reopen it.
- A project shown as still *building* is not yet safe for model reads/writes - wait for it to settle (or `clean_project`) before driving it.
- Use the **Name** column verbatim as the `projectName` argument elsewhere; do not guess from the folder path.
- For configuration-level details of one project (root properties, languages, compatibility mode) use `get_configuration_properties`; for the list of configurations use `list_configurations`.

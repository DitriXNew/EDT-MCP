# Common safety and tooling rules

> Safety rules specific to working with a 1C project. Baseline behaviour (do not fabricate facts, do not run `rm -rf /`, do not commit secrets, ask before `git push --force`, etc.) is assumed by default.

## Shell on Windows

- In Bash on Windows (Git Bash, MSYS, WSL) **do not** write `2>nul` — there it creates a file named `nul`. Use `2>/dev/null`. In native `cmd.exe` `2>nul` is the standard way to suppress stderr — that is correct there. In PowerShell — `2>$null`.

## Responsibility boundary for 1C tools

1C project files in EDT have a complex structure (EMF links, UUIDs, object references). Direct editing via the client's file tools (Read/Edit/Write in Claude, the equivalents in Cursor/Copilot/Gemini CLI, etc.) breaks them.

- **BSL** (`.bsl`) — **only** via MCP EDT (`read_module_source`, `write_module_source`, `read_method_source`).
- **Metadata** (`.mdo`) — direct file editing is possible, but when an MCP tool is available prefer it (`rename_metadata_object`, `delete_metadata_object`, `add_metadata_attribute`).
- **Forms** (`Form.form`) — direct file editing is possible, but with care (see `edt-metadata.md`).
- **Other XML** (roles, common commands, etc.) — direct file editing is acceptable; UUID rules see in `edt-metadata.md`.
- **Non-1C files** (JSON, JS, Python, docker, shell) — the client's standard file/search/shell tools.

If the needed MCP tool is missing or refuses — **ask the user** before bypassing the restriction. Explain what needs to be done and what the risks are. This is a last resort.

## Destructive operations

Baseline behaviour (not running `rm -rf /`, not committing secrets, not running `git push --force` without an explicit request) is assumed. Targeted reminders for a 1C project:

- Before `git reset --hard`, `git checkout .`, `git clean -fd` — confirm with the user explicitly. EDT may have an editor open with unsaved changes at that moment, and the loss will be silent.
- Always call `delete_metadata_object` and `rename_metadata_object` **first without `confirm`** — this is a preview. To apply, call again with `confirm: true`.
- Do not run `update_database` with `fullUpdate: true` without an explicit request — this is a full infobase restructuring, long-running and potentially lossy.

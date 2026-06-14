Removes a 1C:EDT launch configuration (runtime client or Attach) by name from the workspace metadata. It is the inverse of `create_launch_config` and the cleanup step after a create round-trip.

## Think twice — destructive (confirm-preview)

Deleting a launch configuration is destructive (the config is removed from the workspace). The tool is guarded by a two-phase workflow (mirroring `delete_project`):

1. **Preview** (`confirm` omitted / false, the default): finds the config and returns `action='preview'`, `confirmationRequired=true`, the config name and its project — WITHOUT removing anything.
2. **Delete** (`confirm=true`): deletes the config; the result reports `action='deleted'`.

## Parameter details

- **name** (required): exact launch configuration name as shown in `list_configurations`. The tool rejects the call with a `list_configurations` hint if no config with this name exists.
- **confirm** (boolean, default false): false previews; true performs the removal.

## Running configs are rejected

If the named configuration is **currently running** (it has an active launch), the tool refuses the delete and tells you to use `terminate_launch` first. This prevents accidental removal of a config that a debug or run session depends on.

## Result

JSON with `action` ('preview'/'deleted'), `confirmationRequired` (preview only), `name`, `project` (if known), `type` (if known), and a `message`.

## Example workflow

```
1. list_configurations({})
   -> finds the config "MyProject Thin Client"

2. delete_launch_config({name: "MyProject Thin Client"})
   -> {"action": "preview", "confirmationRequired": true, ...}

3. delete_launch_config({name: "MyProject Thin Client", confirm: true})
   -> {"action": "deleted", ...}
```

## Gotchas

- The name must be the **exact** name as returned by `list_configurations` (case-sensitive).
- Configs live in workspace `.metadata`, not in project files. Removing a project (`delete_project`) does NOT remove its launch configurations — use this tool to clean them up explicitly.
- If the config is running, terminate it first with `terminate_launch`, then call this tool.

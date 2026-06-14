Creates a 1C:EDT runtime-client launch configuration (thin / thick / web client) and persists it in the workspace metadata. The configuration is immediately visible in `list_configurations` and usable by `debug_launch`, `run_yaxunit_tests`, and `update_database`.

## Run vs debug — one config, two modes

There is **no separate debug configuration type** for a runtime client. The same `RuntimeClient` config works for both run and debug:

- `debug_launch({launchConfigurationName: "<name>"})` — launches and attaches the debugger.
- `run_yaxunit_tests({launchConfigurationName: "<name>"})` — runs YAXUnit tests.

Run mode vs debug mode is chosen at launch time (the mode string), never a different config or attribute. Attach-to-server configs (`RemoteRuntime` / `LocalRuntime`) are a different feature and are out of scope here.

## Parameter details

- **projectName** (required): must be a V8 **configuration** project (V8ConfigurationNature). Extension projects, dictionary storage projects, and external-objects projects are rejected — a runtime client targets a configuration. Use `list_projects` to discover available projects and their type.
- **clientType** (enum, default `thin`): `thin` / `thick` / `web`. Determines which 1C:Enterprise client executable is launched. Web client configs additionally require a published PWA/web application on the infobase, which is outside the scope of this tool.
- **name** (optional): exact name for the new config. If omitted a unique name is generated: `<Project> Thin|Thick|Web Client` (uniquified if a conflict exists). If a config with the supplied name already exists the call is rejected — use `list_configurations` to check.
- **applicationId** (optional): the infobase/application ID from `get_applications`. If omitted the project's **default application** is resolved automatically. If the project has no applications the call is rejected with a hint to create an infobase first.

## Why applicationId is required (internal)

The tool always writes a real `applicationId` so `list_configurations` shows it with the real id (not the synthetic `launch:<name>` fallback), and so `debug_launch` / `run_yaxunit_tests` can resolve the config by `projectName + applicationId` without an "Application not found" error.

## Result

JSON with `action='created'`, `name`, `project`, `clientType`, `applicationId`, `type` (the launch type id), and a `message` with next-step hints.

## Cleanup

Use `delete_launch_config(name='<name>', confirm=true)` to remove a configuration when it is no longer needed.

## Example workflow

```
1. get_applications({projectName: "MyProject"})
   -> {"applications": [{"id": "abc-123", ...}], "defaultApplicationId": "abc-123"}

2. create_launch_config({projectName: "MyProject", clientType: "thin"})
   -> {"action": "created", "name": "MyProject Thin Client", "applicationId": "abc-123", ...}

3. debug_launch({launchConfigurationName: "MyProject Thin Client"})
   -> launches the thin client with the debugger attached

4. delete_launch_config({name: "MyProject Thin Client", confirm: true})
   -> cleanup
```

## Gotchas

- The project must be an open V8ConfigurationNature project. Extension and external-objects projects are rejected.
- If the project has no applications (no infobase registered), the tool rejects the call. Register an infobase first in Window -> Preferences -> EDT -> Applications.
- Web client configurations function as configs (they are created and listed) but actually launching a web client requires a published PWA app; the launch itself may fail if the web app is not published.
- Configs are stored in workspace `.metadata` (not in the project files on disk), so they do not appear in git diffs and are not exported by `export_configuration_to_xml`.

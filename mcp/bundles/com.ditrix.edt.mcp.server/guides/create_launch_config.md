Creates a 1C:EDT runtime-client launch configuration (thin / thick / web client) and persists it in the workspace metadata. The configuration is immediately visible in `list_configurations` and usable by `launch`. Runtime-client configurations can also be used by `run_yaxunit_tests` and `update_database`.

## Attach to an existing debug server

Use `clientType: "attach"` with an explicit `name`, `projectName`, and the real
`applicationId` of the current branch. Pass its exact `infobaseAlias` and
`debugServerUrl` (loopback HTTP with an explicit port). The tool saves a
`RemoteRuntime` configuration. Call `launch` with its returned name and
`mode: "debug"` to connect EDT without starting or terminating a 1C client.
Check `debug_status` and identify the intended thread before resuming it.
Automated testing options are only for runtime-client configurations.

## Run vs debug — one config, two modes

There is **no separate debug configuration type** for a runtime client. The same `RuntimeClient` config works for both run and debug:

- `launch({launchConfigurationName: "<name>"})` — launches and attaches the debugger.
- `run_yaxunit_tests({launchConfigurationName: "<name>"})` — runs YAXUnit tests.

Run mode vs debug mode is chosen at launch time (the mode string), never a different config or attribute. Attach-to-server configs (`RemoteRuntime` / `LocalRuntime`) connect EDT to the debug server separately from runtime-client launches.

## Parameter details

- **projectName** (required): must be a V8 **configuration** project (V8ConfigurationNature). Extension projects, dictionary storage projects, and external-objects projects are rejected — a runtime client targets a configuration. Use `list_projects` to discover available projects and their type.
- **clientType** (enum, default `thin`): `thin` / `thick` / `web` / `attach`. For runtime clients, determines which 1C:Enterprise client executable is launched. Web client configs additionally require a published PWA/web application on the infobase, which is outside the scope of this tool.
- **name** (optional): exact name for the new config. Required for Attach. Otherwise, if omitted a unique name is generated: `<Project> Thin|Thick|Web Client` (uniquified if a conflict exists). If a config with the supplied name already exists the call is rejected — use `list_configurations` to check.
- **applicationId** (optional): the infobase/application ID from `get_applications`. Required for Attach. For a runtime client, omitting it selects the project's **default application**. If the project has no applications the call is rejected with a hint to create an infobase first.

## Automated testing

Set `automatedTestingMode="TESTCLIENT"` to persist the platform's `/TESTCLIENT` mode.
`automatedTestingPort=1540` sets the `-TPort` connection port (integer 1..65535);
omitting it leaves the native attribute unset, so EDT uses **1538** for this new config.
The response includes the effective TESTCLIENT port, read from the saved configuration
or defaulting to **1538**. These are launch settings, not confirmation of a listening socket.
Mode values are case-insensitive and stored as `TESTCLIENT` / `TESTMANAGER`.

`automatedTestingMode="TESTMANAGER"` persists test-manager mode, which has no port.
`automatedTestingPort` is refused unless `automatedTestingMode="TESTCLIENT"`.
The existing project/application requirements still apply; no config is saved when the
project has no applications. Web-client test mode is unverified because
no automated-testing web-client ID is stamped.

```json
{"projectName":"MyProject","name":"My test client","automatedTestingMode":"TESTCLIENT","automatedTestingPort":1540}
```

Then call `launch({launchConfigurationName:"My test client", startupOption:"RunTests"})`
to combine the saved testing mode with a per-launch `/C` startup command. This tool
does not set `startupOption`. A launch can switch the saved mode or override its port,
but cannot clear it. To clear a saved mode, delete and recreate the configuration without automated-testing parameters.

## Why applicationId is required (internal)

The saved `applicationId` is the infobase binding. Runtime-client configurations use it for selection by `projectName + applicationId`. Attach configurations use `attach:<name>` in `list_configurations` and debug tools, so a runtime client bound to the same infobase has independent debugger state.

## Result

JSON with `action='created'`, `name`, `project`, `clientType`, `applicationId`, `type` (the launch type id), and a `message` with next-step hints.
When requested, `automatedTestingMode` confirms the normalized mode saved in the configuration;
`automatedTestingPort` confirms an explicit saved port and is absent when omitted.

## Cleanup

Use `delete_launch_config(name='<name>', confirm=true)` to remove a configuration when it is no longer needed.

## Example workflow

```
1. get_applications({projectName: "MyProject"})
   -> {"applications": [{"id": "abc-123", ...}], "defaultApplicationId": "abc-123"}

2. create_launch_config({projectName: "MyProject", clientType: "thin"})
   -> {"action": "created", "name": "MyProject Thin Client", "applicationId": "abc-123", ...}

3. launch({launchConfigurationName: "MyProject Thin Client"})
   -> launches the thin client with the debugger attached

4. delete_launch_config({name: "MyProject Thin Client", confirm: true})
   -> cleanup
```

## Gotchas

- The project must be an open V8ConfigurationNature project. Extension and external-objects projects are rejected.
- If the project has no applications (no infobase registered), the tool rejects the call. Register an infobase first in Window -> Preferences -> EDT -> Applications.
- Web client configurations function as configs (they are created and listed) but actually launching a web client requires a published PWA app; the launch itself may fail if the web app is not published.
- Configs are stored in workspace `.metadata` (not in the project files on disk), so they do not appear in git diffs and are not exported by `export_configuration_to_xml`.

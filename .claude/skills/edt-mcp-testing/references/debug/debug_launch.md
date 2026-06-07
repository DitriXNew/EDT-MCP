# debug_launch — how to test

**Purpose.** Start an EDT debug session: by `launchConfigurationName` (any config, incl. Attach) or by `projectName+applicationId` (runtime-client).

**Precondition.** The scenario from [SETUP](SETUP.md) is in place (code in `OnStart` + breakpoint), and the infobase is available exclusively for the update.

**Call (real):**
```
debug_launch(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=true)
```

**Expected result (no update, fast path):**
```json
{"success":true,"mode":"debug","project":"TestConfiguration","attach":false,
 "configurationType":"com._1c.g5.v8.dt.launching.core.RuntimeClient",
 "launchConfiguration":"TestConfiguration Thin Client",
 "message":"Debug session started successfully"}
```
With `updateBeforeLaunch=true` the infobase is updated first (designer agent), then the client starts, and `OnStart` catches the breakpoint.

**Gotchas.**
- **`updateBeforeLaunch=true` hangs if another client holds the infobase** → log `Connecting to designer agent for infobase ...` + timeout. Close the clients (`Stop-Process 1cv8/1cv8c`); an elevated one — only by the user. Without the update the new code never reaches the infobase.
- **An MCP-call timeout ≠ failure**: the server side may still be finishing the update/start. Re-check `debug_status` and `Get-Process 1cv8c`.
- **Legacy path `projectName+applicationId`** looks up the config by the project+app pair; if no runtime-client config is bound to that appId → `success:false` + `availableConfigurations` ("No launch configuration found"). Calling by `launchConfigurationName` is more reliable.
- **If the application is already running** (incl. RUN mode without a debug target) — the tool returns `alreadyRunning:true` with `mode`, rather than bringing up a second client. To recreate the session — first call `terminate_launch`.
- **Flaky channel:** a bare `Error` has been observed — for the real cause look in `D:\WS\EDT\.metadata\.log` (the full `structuredContent` is there).

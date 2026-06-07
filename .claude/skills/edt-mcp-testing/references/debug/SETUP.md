# Debug — common test-bed setup

To test the debug tools you need **executable code + an execution trigger + a breakpoint**. An empty `TestConfiguration` does not execute anything on its own, so there is nothing for the breakpoint to catch.

## Working scenario (e2e)

1. **Put executable code into the auto-start handler** — `ManagedApplicationModule.OnStart()`, which runs by itself when the client starts (no manual trigger needed).
   - The handler name depends on the embedded-language variant: with `scriptVariant=English` it is `OnStart` / `Message()`, with Russian — `ПриНачалеРаботыСистемы` / `Сообщить()`. **Check the variant:** `get_configuration_properties(projectName)` → the `scriptVariant` field (for `TestConfiguration` it is `English`).
   ```bsl
   // TEST DEBUG
   Procedure OnStart()
       Greeting = "Debug e2e OK";
       Sum = 40 + 2;
       Total = Sum * 10;        // <- breakpoint here (Greeting+Sum already assigned, Total still Undefined)
       Message(Greeting + " | sum=" + Sum + " | total=" + Total);
   EndProcedure
   ```
   Write: `write_module_source(projectName="TestConfiguration", modulePath="Configuration/ManagedApplicationModule.bsl", mode="replace", source=<code>)`.
2. `set_breakpoint` on the line `Total = Sum * 10;`.
3. `debug_launch(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=true)` — on start the client runs `OnStart` and stops at the breakpoint.
4. Catch and inspect: `wait_for_break` → `get_variables` / `step` / `evaluate_expression` → `resume`.
5. Clean up: `remove_breakpoint`, and if needed close the client (`Stop-Process` on 1cv8c, since `terminate_launch` may not be in the host tool snapshot).

## Gotchas

- **A DB update requires EXCLUSIVE access to the file infobase.** If a running 1C client holds the infobase, `debug_launch(updateBeforeLaunch=true)` hangs on `Connecting to designer agent for infobase <name>` and times out (visible in `D:\WS\EDT\.metadata\.log`). Fix — close the clients (`Stop-Process -Name 1cv8,1cv8c`). **An elevated process cannot be killed from a non-elevated shell** (`Access denied`) — such a 1C is closed only by the user.
- **Without `updateBeforeLaunch=true`** the client starts on the OLD configuration in the infobase — the new `OnStart` never reaches it and the breakpoint will not fire. (`write_module_source` by itself only changes the source in the workspace/on disk, not the infobase configuration.)
- **The `debug_launch` MCP call may return a timeout** while the server side is still updating the infobase / bringing up the client — this is not necessarily a failure: re-check `debug_status` after a while and via `Get-Process 1cv8c`.
- **A breakpoint is anchored to a line.** If the module shifts (code added above — e.g. `BeforeStart`), EDT re-anchors the breakpoint to the correct statement, but its `breakpointId` may change. Before removing, look at the actual list: `list_breakpoints`.
- **scriptVariant matters:** a handler whose name is wrong for the variant is not recognized by 1C as an event — it will not run on start and the breakpoint will not be caught.

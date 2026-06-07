# terminate_launch — how to test

**Purpose.** End a running debug/run session (close the client / detach the attach). Used to recreate a session after `alreadyRunning`.

**Precondition.** An active launch (`debug_status` shows `count>=1`).

**Expected call:**
```
terminate_launch(applicationId="attach:1C Enterprise debug process")
```
(or by `launchConfigurationName` — verify the signature against the current tool schema).

**Gotchas.**
- **If it is not in the host tool snapshot** (the tool exists in code) — when `ToolSearch`/a direct call is unavailable, **fall back to killing the process**: `Stop-Process -Name 1cv8c -Force` (the client), which ends the bound attach-launch. An elevated process cannot be killed from a non-elevated shell (`Access denied`).
- After `terminate_launch`/kill check `debug_status` → `count:0` and `Get-Process 1cv8c` → empty.
- Before a run, fill in / verify this reference with real output when the tool is available in the host.

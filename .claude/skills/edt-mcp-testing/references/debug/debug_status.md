# debug_status — how to test

**Purpose.** Show active debug launches: applicationId, config/type, mode (debug/run), suspended flag, thread count, and the line of the top suspended frame.

**Precondition.** None (returns `count:0` if nothing is running — also a valid case).

**Call (real):**
```
debug_status()
```

**Expected result — no sessions:**
```json
{"registry":{"liveFrames":0,"liveThreads":0,"activeApplications":0},"success":true,"count":0,"launches":[]}
```
**Stopped at a breakpoint (after debug_launch + OnStart):**
```json
{"registry":{"liveFrames":0,"liveThreads":1,"activeApplications":1},"success":true,"count":1,
 "launches":[{"applicationId":"attach:1C Enterprise debug process","mode":"debug","debug":true,
   "launchConfiguration":"1C Enterprise debug process",
   "configurationType":"com._1c.g5.v8.dt.debug.core.LocalRuntime","attach":true,
   "project":"TestConfiguration","threadCount":2,"suspended":true,
   "suspendedAt":"ManagedApplicationModule.OnStart() line: 9 @ 9","registered":true}]}
```

**Gotchas.**
- `debug_status` lists launches directly from the manager — it shows both RUN and debug (the `mode`/`debug` field). This differs from **auto-resolve** (`findLoneActiveApplicationId`), which considers only the DEBUG mode.
- `suspendedAt` is a handy sign that the break fired; then take the frame via `wait_for_break`.
- A running runtime-client client shows up as the attach-launch `1C Enterprise debug process` (LocalRuntime) with a synthetic `applicationId: "attach:<configName>"`.

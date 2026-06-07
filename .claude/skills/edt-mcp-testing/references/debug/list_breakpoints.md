# list_breakpoints — how to test

**Purpose.** List active line breakpoints (optional `projectName` filter).

**Precondition.** At least one `set_breakpoint` is placed.

**Call (real):**
```
list_breakpoints(projectName="TestConfiguration")
```

**Expected result:**
```json
{"success":true,"count":1,"breakpoints":[
  {"breakpointId":1867,"project":"TestConfiguration",
   "file":"/TestConfiguration/src/Configuration/ManagedApplicationModule.bsl",
   "lineNumber":9,"enabled":true,"modelId":"com._1c.g5.v8.dt.debug"}]}
```

**Gotchas.**
- **The `breakpointId` may differ from what `set_breakpoint` returned** if the module shifted (EDT re-anchored the breakpoint). So before `remove_breakpoint` take the current id from here (in a run: placed on line 5 → id 1863, after adding `BeforeStart` above the real break ended up on line 9 → id 1867).
- `modelId":"com._1c.g5.v8.dt.debug"` confirms this is a BSL breakpoint, not a generic marker.

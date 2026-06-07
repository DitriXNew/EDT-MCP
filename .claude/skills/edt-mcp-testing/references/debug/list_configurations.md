# list_configurations — how to test

**Purpose.** List EDT launch configurations (runtime-client + Attach + other 1C types) with their `running`/`suspended` state.

**Precondition.** A project is open. A running session is not required (but if there is one — you will see `running:true`).

**Call (real):**
```
list_configurations(projectName="TestConfiguration")
```

**Expected result:**
```json
{"success":true,"count":1,"configurations":[
  {"name":"TestConfiguration Thin Client",
   "type":"com._1c.g5.v8.dt.launching.core.RuntimeClient",
   "attach":false,"project":"TestConfiguration","running":false}]}
```

**Gotchas.**
- The `name` field is what is passed as `launchConfigurationName` to `debug_launch`/`run_yaxunit_tests`/`debug_yaxunit_tests`.
- `type='attach'` — server-side debugging (HTTP services, background jobs); `type='client'` — client; `type='all'` (default).
- After `debug_launch` of a running runtime-client, EDT may show the attached **attach-launch** `1C Enterprise debug process` (LocalRuntime) — this is a normal client-debug state.

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
   "attach":false,
   "applicationId":"3f6c0b1e-9d28-49db-9273-2903d2ab859a",
   "project":"TestConfiguration","running":false}]}
```
`applicationId` is present for every EDT config: the configuration's real id when it has a readable application binding, otherwise a SYNTHETIC one minted from the name (`launch:<name>`, or `attach:<name>` for Attach configs). The synthetic forms exist for debug tracking — they are not application ids and `update_database`/`get_applications` cannot resolve them. Note `getApplicationIdFor` reads the attribute leniently, so an UNREADABLE binding also surfaces as the synthetic form.

**Gotchas.**
- The `name` field is what is passed as `launchConfigurationName` to `launch`/`run_yaxunit_tests`/`debug_yaxunit_tests`.
- `type='attach'` — server-side debugging (HTTP services, background jobs); `type='client'` — client; `type='all'` (default).
- After `launch` of a running runtime-client, EDT may show the attached **attach-launch** `1C Enterprise debug process` (LocalRuntime) — this is a normal client-debug state.

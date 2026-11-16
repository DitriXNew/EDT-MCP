## Guide

Manage an interactive user's 1C technological-log capture or export parsed data. Read analysis belongs to `get_tech_log`. Preview/status are observational. Start, stop and export reuse EDT-MCP's `DestructiveConsentGate`; they refuse in a read-only tool preset. Consent preferences, attended UI and the documented automation/headless policy are shared with the other gated tools.

### Capture workflow

1. Select a question using `get_tech_log mode=describe, profile=<id>`.
2. Preview with a dedicated absolute `location`, inspect generated XML, target, costs and shadowing warnings.
3. Start using the same parameters and location; authorize the file write through the existing consent gate.
4. Check status. **Config written** and **log files appeared / last event time** are separate observations. The platform re-reads configuration every 60 seconds; until files appear, status says waiting for pickup (≤60 s). Persistent absence needs investigation; it does not prove activation or health.
5. Reproduce one short scenario and stop immediately, including after a failed reproduction. `scenario` has no automatic stop timer; history is retention, not capture duration.
6. Follow the returned `get_tech_log` summary call, inspect details/chain, fix and re-measure.

```json
{"mode":"preview","profile":"db","location":"D:/TJ/investigation","thresholdMs":5000,"plans":false}
{"mode":"start","profile":"db","location":"D:/TJ/investigation","thresholdMs":5000,"plans":false}
{"mode":"status"}
{"mode":"stop"}
{"mode":"export","path":"D:/TJ/investigation","outputPath":"D:/TJ/events-new.csv","exportKind":"events"}
```

### Profiles and overrides

| Profile | Question / events | Default threshold | Cost / next step |
|---|---|---|---|
| errors | Exceptions, query errors, lifecycle, lock incidents | None | Error storms can be large; inspect causes and gather locks if needed |
| slow-calls | CALL/SCALL and errors | 1000 ms | Short repeated calls are hidden; inspect contexts and resources |
| db | DBMSSQL/DBPOSTGRS/DBV8DBENG/SDBL, errors | 5000 ms for DB | Can still produce hundreds of MB; inspect normalized SQL and waits |
| locks | Acquisitions, incidents, SDBL transaction boundaries and DB evidence | None | High volume; collect all participants, resolve resources and lifetime |
| memory | CALL, MEM, LEAKS, health, process and errors | None | Enables expensive mem/leaks with server checkpoints; inspect retained stacks |
| scenario | Calls, VRS, SQL, locks and errors | None | Short bounded reproduction only; reconstruct intervals and missing evidence |

Thresholds are diagnostic defaults, not platform fault standards. `thresholdMs` generates `durationus=ms*1000` only for CALL/SCALL/DB branches. Errors, TLOCK and SDBL stay unfiltered to retain incidents and transaction boundaries. `extraEvents` is a comma-separated list of documented names. An `infobase` filter uses exact `p:processName` on every branch and can exclude client/file events missing that property.

`plans=true` adds the **sibling** `plansql` generating element. The official PostgreSQL methodology restricts this to test/development; older articles show plans with long-query capture. Both positions remain in the reference: never label this cheap permanent monitoring. `dbmsLocks=true` adds sibling `dbmslocks`: extra DBMS polling no more often than every two seconds on DB access, not a complete deadlock graph. MEM/LEAKS extra events also enable their required generators. Filters alone cannot generate these diagnostics.

Generated log attributes: JSON, plain placement, dedicated location, size rotation. Default `historyHours=1` (range 1..24), `rotationMb=100` (range 1..1024). Rotation size limits each file, not the directory's total size. The default location is a fresh `<temp>/edt-mcp-tech-log/<uuid>`; pass the previewed location to start to retain it. Existing capture directories must be empty.

### Configuration ownership and recovery

Capture configuration writes only the CURRENT Windows user's `%LOCALAPPDATA%\1C\1cv8\conf\logcfg.xml`. It captures processes of THIS user on THIS machine: client, file infobase, or local server running as this user. For a server cluster or a service account, use `mode=preview` and place the generated XML in that server's conf directory by hand; remove it afterwards. Collect logs from every relevant node.

Windows lookup order is version `bin/conf`, user LOCALAPPDATA, version `ConfLocation`, then ALLUSERSPROFILE. Existing version `bin/conf/logcfg.xml` files under both Program Files roots are listed as shadowing warnings; they can override the user configuration.

An existing foreign configuration is preserved with a byte-exact backup, and a comment-marked capture block is inserted. UTF-8 and BOM UTF-16 are supported for insertion; other encodings refuse before config mutation. Required generating elements are siblings of log; conflicts with existing foreign generators refuse start for review. A durable recovery record and `edt-mcp-capture.json` are written before config mutation. Stop restores the original bytes (or original absence), keeps log files, records stop time and removes the active recovery/backup files. Capture markers cause a warning on both tools, including after an EDT restart.

If someone edits config during capture, stop refuses to overwrite those edits. Reconcile against the named backup, restore the original bytes and retry stop to close the manifest. If the active record was lost, stop recovers from the marked log's capture manifest with matching identity and hashes. Missing/mismatched recovery evidence refuses automatic replacement and names the backup for manual review. Files that were never collected cannot be reconstructed. No capture claims live platform pickup from config existence alone.

### CSV export

`path` is an absolute log file/directory. `outputPath` must be an absolute **new** file; CREATE_NEW prevents overwrite races. CSV has UTF-8 BOM, quoted cells and doubled quotes; `exportKind=events` preserves all properties as JSON, while `groups` exports aggregate counts, durations in ms and memory peaks in bytes. Incomplete/budget-limited or corrupt input refuses export; export a large log in `from`/`to` windows (same bounds as get_tech_log) or finish capture first. Export uses the largest scan budget, 512 MB / 30 s. If a write fails, its partial new output is removed.

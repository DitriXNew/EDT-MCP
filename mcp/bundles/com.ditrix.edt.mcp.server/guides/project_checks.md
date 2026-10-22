Inspect or change a project's EDT validation checks and its active check-settings profile.
Read the current values first. `set` and `switch` ask for consent when they change anything;
`list`, `profiles`, and effective no-ops do not. The tool is disabled in the Analysis Only
and Code Review presets because it includes writes.

### Actions

- `list`: return effective settings in the active profile, sorted by category then `checkId`.
  Use `limit` (default 50, clamped to 1..100) and `offset` (default 0, minimum 0).
  `total` counts all matching checks; `returned` counts this page. The header includes
  `project` and `activeProfile`. Follow pages until `offset + returned >= total`.
- `profiles`: list existing profiles with `name`, `active`, and `customizedChecks`.
  `Default` is always available. Other profiles come from `.settings/*.cset`.
- `set`: validate the complete `changes` batch, preview it for consent, then apply it
  in one repository write. The response includes `profile`, `before`, `after`, `changed`,
  `persisted`, and `markersRefresh`. `changed` and the consent preview count only checks
  with an effective change; unchanged entries are omitted from the preview names.
- `switch`: require `profile` to name an existing profile from `profiles`. Return
  `before.activeProfile`, `after.activeProfile`, and `persisted`; top-level `profile`
  is the active profile read back after the switch. Switching to the
  already active profile returns `changed:false`. A missing profile is refused;
  this action does not create profiles.

### Check identifiers and filters

`set` entries and the optional `list.checkIds` array accept these three forms:

- A symbolic id such as `begin-transaction`.
- The exact `checkId|contributorId` key from a list row's `uid` or a `.cset` file.
  Use this form when several contributors register the same symbolic id; an ambiguous
  symbolic id is refused with the available pipe keys.
- A project-local short UID such as `SU23` from `get_project_errors` or a list row's
  `shortUid`. Always supply the corresponding `projectName`.

List rows include `checkId`, `contributorId`, `uid`, `shortUid`, `title`, `category`,
`type`, `enabled`, `severity`, `defaultEnabled`, `defaultSeverity`, `customized`,
`criticalDataIntegrity`, and `hasDocumentation`. Titles and category titles follow EDT's
locale; use identifiers for matching. `hasDocumentation:true` means
`get_check_description` can explain the symbolic id.

`onlyCustomized:true` restricts the list to keys customized in the active profile.
`checkIds` intersects that filter and also includes each check's read-only
`parameters:[{name,value,isDefault,type}]`. Omitting `checkIds` omits parameters.
Filter enabled/disabled rows on the client; there are no `onlyEnabled`/`onlyDisabled`
parameters. Parameter values cannot be changed with this version of the tool.

### Changes and restoration

`changes` is a JSON array encoded as a string. Each object requires `checkId` and at
least one of `enabled` (a JSON boolean) or `severity`. Severity accepts `BLOCKER`,
`CRITICAL`, `MAJOR`, `MINOR`, and `TRIVIAL` case-insensitively and is returned uppercase.
An empty batch, duplicate resolved checks, unknown ids, and invalid values are refused
before any mutation or consent prompt.

```json
{"projectName":"TestConfiguration","action":"list","checkIds":["begin-transaction"]}
```

```json
{"projectName":"TestConfiguration","action":"set","changes":"[{\"checkId\":\"begin-transaction\",\"enabled\":false,\"severity\":\"MINOR\"}]"}
```

`before` is the restore recipe. For every row in `before`, send a `set` entry with
`checkId` set to that row's `uid`, and copy its `enabled` and `severity`. This restores
the exact contributor's previous effective settings in the same profile. Keep the
original response until restoration is verified. `after` is read again from EDT's
repository; `persisted:true` means that read-back matches the fields explicitly requested.
A partial entry writes only its supplied fields, preserving fresh values of omitted
fields if they changed while consent was pending. `before` is the pre-consent snapshot.
If it is false, inspect `after` and list again before deciding how to retry.

Critical data integrity checks can be disabled with the same consent as other checks.
Such rows carry `criticalDataIntegrity:true`. When a critical check is disabled, the
response says plainly that EDT will now load the configuration without it. Restore
the previous values after the temporary operation; these checks can block infobase
loading for a reason.

```json
{"projectName":"TestConfiguration","action":"profiles"}
```

```json
{"projectName":"TestConfiguration","action":"switch","profile":"Default"}
```

To undo a switch, switch back to the profile named in `before.activeProfile`.
Switches apply the target profile as stored, with no extra settings overlaid.

### Persistence and live markers

EDT stores only deviations from defaults in `.settings/<profile>.cset`: JSON with
`version:1` and a `settings` map keyed by `checkId|contributorId`, containing `enabled`,
`severity`, and optional `properties`. The active profile is stored in
`.settings/com.e1c.g5.v8.dt.check.prefs` under `activeProfile`; choosing `Default`
clears that preference. Preference flushing may finish asynchronously. Restoring a
default removes its delta; an empty `.cset` file can remain.

Changes are live. EDT removes markers belonging to disabled checks, retags severities,
and re-runs enabled/changed checks in the background. Poll `get_project_errors` for
the settled result. No restart or `clean_project` is required. The tool refreshes the
workspace's `.settings` folder after writes so EDT can observe later external edits.
If that refresh fails after a successful repository write, `workspaceRefreshWarning`
explains how to refresh manually; `before`, `after`, and the read-back verdict remain
available so the change can still be restored.

### Follow-ups

This version lists parameter values only; parameter writes using
`ICheckParameterSettings.setValue` and `validateParameterValue` are follow-up work.
Profile create, duplicate, rename, delete, import, and export are also follow-ups;
manage those in EDT today. `ICheckProcessPreferences` (manual rebuilds and hiding
legacy results) is outside these four actions.

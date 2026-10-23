# project_checks

List or change EDT validation checks; switch, create, copy or delete check-settings profiles. Writes ask for consent; profile deletion is destructive. Parameters: get_tool_guide('project_checks').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name |
| action | yes | string (one of: list, set, profiles, switch, createProfile, copyProfile, deleteProfile) | Operation |
| checkIds | — | array | Check ids to list; includes parameters when supplied |
| onlyCustomized | — | boolean | List only checks customized in the active profile |
| limit | — | integer | Page size (default 50, maximum 100) |
| offset | — | integer | Zero-based page offset (default 0) |
| changes | — | string | For set: JSON array of {checkId, enabled?, severity?} |
| profile | — | string | Profile name: existing for switch/deleteProfile, new for createProfile/copyProfile |
| from | — | string | Source profile: required for copyProfile; createProfile defaults to Default |

## Guide
Inspect or change a project's EDT validation checks and its active check-settings profile.
Read the current values first. `set` and `switch` ask for consent when they change anything.
`createProfile`, `copyProfile`, and destructive `deleteProfile` also ask for consent;
`list`, `profiles`, and effective no-ops do not. The tool is disabled in the Analysis Only
and Code Review presets because it includes writes.
Its MCP annotation is `destructiveHint:true` because profile deletion can discard stored settings.

### Actions

- `list`: return effective settings in the active profile, sorted by category then `checkId`.
  Use `limit` (default 50, clamped to 1..100) and `offset` (default 0, minimum 0).
  `total` counts all matching checks; `returned` counts this page. The header includes
  `project` and `activeProfile`. Follow pages until `offset + returned >= total`.
- `profiles`: list existing profiles with `name`, `active`, and `customizedChecks`.
  EDT may omit an inactive, unsaved `Default`; that built-in profile is always a valid switch target.
  Saved profiles come from `.settings/*.cset`.
- `set`: validate the complete `changes` batch, preview it for consent, then apply it
  in one repository write. The response includes `profile`, `before`, `after`, `changed`,
  `persisted`, and `markersRefresh`. `changed` and the consent preview count only checks
  with an effective change; unchanged entries are omitted from the preview names.
- `switch`: require `profile` to name a listed profile or the implicit built-in `Default`. Return
  `before.activeProfile`, `after.activeProfile`, and `persisted`; top-level `profile`
  is the active profile read back after the switch. Switching to the
  already active profile returns `changed:false`. Every other unlisted profile is refused;
  this action does not create user profiles.
- `createProfile`: require a new `profile` name. Optional `from` chooses an existing source;
  omitting it copies the stored `Default` profile, including its customizations. When `Default`
  has no saved file, EDT creates an empty profile using default check settings. An explicit
  `from` must name a listed profile. EDT's public
  repository provides duplication for both creation and copying. The active profile stays unchanged.
- `copyProfile`: require both a new `profile` and an existing `from`. Copy the source's stored
  settings, including check parameters, without switching profiles.
- `deleteProfile`: require an existing, inactive `profile`. This is destructive and uses the
  destructive consent gate. Missing and active profiles, including case aliases of the active name,
  are refused before consent; switch away first to delete a user profile. EDT also requires
  more than one listed profile to delete an inactive profile. To remove the last saved profile,
  switch to it and delete it in EDT's validation preferences; EDT restores Default when no profiles
  remain. EDT's own storage refusals are returned as errors. The response includes `deletedProfile` and `deletedCustomizedChecks`,
  the number of customized checks removed. Save the original settings separately to recreate
  them; the count alone is not a restore recipe.

Each create/copy/delete response includes the resulting `activeProfile` and `profiles` list in
the same shape as `profiles`, plus the affected `profile`, `changed`, and a `persisted` verdict
for the profile's presence or absence. Create/copy also reports `from`. New names are checked
by EDT's own `validateProfileName`; invalid and duplicate names include EDT's English refusal.
Every profile name, including `from`, must be a file stem directly in `.settings`; `/`, `\` and `:` are refused.
EDT's workspace file-name validator also rejects platform-invalid names, including Windows device
stems and invalid characters. Existing names and the reserved built-in `Default` are compared
without regard to case, even when `Default` is unlisted, to prevent a Windows file alias from
overwriting a profile during creation or copying.
Source and target availability are checked again after consent, before the write.

```json
{"projectName":"TestConfiguration","action":"createProfile","profile":"TemporaryChecks"}
```

```json
{"projectName":"TestConfiguration","action":"copyProfile","from":"TemporaryChecks","profile":"CopiedChecks"}
```

```json
{"projectName":"TestConfiguration","action":"deleteProfile","profile":"CopiedChecks"}
```

### Check identifiers and filters

`set` entries and the optional `list.checkIds` array accept these three forms:

- A symbolic id such as `begin-transaction`.
- The exact `checkId|contributorId` identifier from a list row's `uid`.
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

To undo a switch, switch back to `before.activeProfile`; `Default` is accepted even when absent from `profiles`.
Switches apply the target profile as stored, with no extra settings overlaid.

### Persistence and live markers

EDT stores only deviations from defaults in `.settings/<profile>.cset`: JSON with
`version:1` and a `settings` map keyed by the bare `checkId`, for example
`{"empty-except-statement":{"enabled":false}}`. When check ids collide, EDT uses
`contributorId|checkId` for the collision entry. Values contain `enabled`, `severity`, and
optional `properties`. These file keys differ from the tool's `checkId|contributorId` identifiers.
The active profile is stored in
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
Profile rename, import, and export are also follow-ups; manage those in EDT today.
`ICheckProcessPreferences` (manual rebuilds and hiding legacy results) is outside these actions.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

# create_metadata — acceptance procedure

Use a ready fixture project and the current branch's deployed bundle. Mutate through MCP, assert disk before model read-back, and restore fixture files and model afterward. The [generated tool page](../../../../../docs/tools/create_metadata.md) defines supported metadata and content addresses.

## Calls and assertions

Call `create_metadata` with each payload:

```json
{"projectName":"TestConfiguration","fqn":"Catalog.E2EPosition"}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EPosition.Attribute.Zulu"}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EPosition.Attribute.Alpha","insertPosition":1}
```

The top object must appear in its own `.mdo` and in `Configuration.mdo`. Members must appear in the owner's `.mdo`; for an ungoverned member list, its exact order is `[Alpha,Zulu]`. Verify it with `sort_metadata` mode=list and inspect member properties with `get_metadata_details`.

Before testing an index, inspect the destination with `sort_metadata`:

```json
{"projectName":"TestConfiguration","fqn":"Catalog.E2EPosition.Attributes","mode":"list"}
```
Field meaning (from the e2e contract):
- **`action`** — always `created` on success; the quickest programmatic discriminator from an error envelope.
- **`fqn`** — canonical English type/kind tokens with unchanged programmatic Names, including batch rows, follow-up hints and write-guard refusals. Test Russian/English addresses against the same canonical FQN, not the raw input.
- **`kind`** — the concrete created EClass: `Catalog` / `Document` / `InformationRegister` … for a top object; `CatalogAttribute`, `InformationRegisterResource`, etc. for a member.
- **`name`** — the programmatic Name of the created node (the last FQN segment).
- **`persisted`** — `true` once the platform accepted a save task for the owning top object's `.mdo`. Since #406 the tool then waits for the export queue to drain before answering, so a success normally means the write has already run - but a drained queue only means nothing is still pending, not that the bytes are right (EDT logs a per-file write failure and completes anyway), and the wait is skipped where the export state cannot be observed.
- **`synonym` / `language`** — present **only** when a `synonym` property was supplied; `language` is the resolved language **CODE** used as the synonym-map key. Emitted together or not at all.
- **`message`** — fixed success message pointing at `get_project_errors` / `revalidate_objects`.

Presence of an `autosort` block requires an actionable insertion refusal with no new member or disk change. Repeat the index test on an ungoverned collection such as enum values. A top-level index is likewise refused when `Configuration.Catalogs` is governed; omission preserves default creation and reports that the plugin places the new object alphabetically. Both outcomes share the six-field notice and user-action message; require the verified EDT UI path, an instruction to ask the user, and a prohibition on agent setting changes. No block is emitted when ungoverned. Never change project preferences to make a test pass.

## Boundaries

- `insertPosition` is a whole 1-based index in `1..size+1`; cover first, middle, last and omitted insertion. Invalid values are refused before mutation and name the actual valid range. Render whole JSON numbers without `.0`.
- Nested metadata members, owned form descriptors, nested subsystems and external-data-source children support positions. Form/XDTO content, predefined data and standalone external roots refuse an index.
- TYPE/KIND tokens accept English or Russian; Names remain programmatic. Verify the canonical created FQN and synonym language-code key.
- Verify refusal quality for duplicates, missing owners, unsupported properties, malformed FQNs and invalid positions. Refused calls preserve the fixture snapshot.
- The export barrier confirms completion of submitted work; model and exact `.mdo` assertions prove its result. Read disk before another MCP round trip.

Run `tests/e2e/tools/test_create_metadata.py` for the complete matrix, including configuration/member indices and optional autosort behavior.

# get_objects_by_tags — how to test

**Purpose.** Return the metadata objects that carry one or more user-defined tags. You pass a set of tag names; the tool resolves each tag in the project's `TagStorage` and lists the FQNs of every object assigned to it (with the tag's color/description and a per-tag count). Returns objects that have **ANY** of the supplied tags (union, not intersection). Read-only — never mutates the model (no tag is created or assigned; resolution is purely a read against the tag storage).

**Preconditions.** Live EDT + the project open and `State=ready` (verify with `list_projects`; after a `-clean` redeploy the index/tag storage rebuilds and reads can be empty until ready). Tags are user-defined labels created in the EDT UI (or via the tag-write tools) — **TestConfiguration ships with no tags**, so a meaningful non-empty result requires first creating tags and assigning objects. Always pair this tool with `get_tags` to discover which tag names actually exist before filtering: tag names you pass that don't exist are reported under "Tags not found", they are not an error. Does not mutate; no revert needed.

**Call (real, 2026-06-02):**
```
get_objects_by_tags(projectName="TestConfiguration",
                    tags=["Important", "NeedsReview"])
```
Optional `limit` (integer, max objects returned **per tag**, default 100, hard-clamped to max 1000). `projectName` and a non-empty `tags` array are required.

**Result.** Returned as an embedded Markdown resource (`ResponseType.MARKDOWN`), not JSON. On TestConfiguration neither tag exists (no tags are defined), so both fall into the "not found" branch and the summary reports zero. Real output:
```markdown
# Objects by Tags in project: TestConfiguration

## ⚠️ Tags not found

- Important
- NeedsReview

---
**Summary:** Found 0 objects across 0 tags
```

**Representative shape when tags DO exist** (clearly labelled — synthesized from `GetObjectsByTagsTool.getObjectsByTags`, NOT a live capture, since TestConfiguration has no tags). With a tag `Important` (color set, two objects) and a tag `NeedsReview` that exists but has no objects assigned:
```markdown
# Objects by Tags in project: TestConfiguration

## Tag: Important

- **Color:** #FF0000
- **Description:** Critical objects
- **Objects count:** 2

| # | Object FQN |
|---|---|
| 1 | Catalog.Catalog |
| 2 | CommonModule.Error |

## Tag: NeedsReview

- **Color:** #FFA500
- **Objects count:** 0

*No objects assigned to this tag*

---
**Summary:** Found 2 objects across 2 tags
```
Key checks: one `## Tag: <name>` block per **resolved** tag, each with `**Color:**`, optional `**Description:**` (omitted when empty), and `**Objects count:**`; a tag with zero objects prints `*No objects assigned to this tag*` (it still counts as a resolved tag in the summary); objects render as a `| # | Object FQN |` table built via `MarkdownUtils`. The summary's "across N tags" equals `tags.length − notFoundTags.length` (resolved tags only), and "Found N objects" sums `min(objects, limit)` per tag.

**Gotchas.**
- **`limit` is per-tag, not global.** Default 100, clamped to 1000. When a tag exceeds the limit the table appends a `| ... | *<remaining> more objects (limit reached)* |` row and stops; the summary counts only `min(objects, limit)` for that tag.
- **Tags-not-found is informational, not an error.** Unknown tag names land in a `## ⚠️ Tags not found` section and are simply excluded from the summary count — the call still succeeds as Markdown. This is expected on TestConfiguration for any tag name.
- **Structured error contract.** Genuine failures arrive via `ToolResult.error(...)` → `{success:false, error:"..."}` with `isError:true`, NOT as Markdown. Triggers: missing/empty `projectName` → `Project name is required`; an **empty or malformed `tags` array** → `Tags array is required. Example: ["Important", "NeedsReview"]`; project not ready → the `ProjectStateChecker` not-ready message; unknown project → `Project not found: <name>`. (In this run `tags=[]` returned a bare flaky `Error` on the echo channel — the source path is `ToolResult.error("Tags array is required...")`; do not treat the bare echo as the real payload.)
- **Tag-name matching is by literal name.** `storage.getTagByName(tagName)` matches the stored tag name exactly; there is no fuzzy/case-folding or bilingual translation of tag names — tags are user strings, not 1C metadata, so the ru/en TYPE-token rule does not apply to them.
- **Object FQNs are programmatic.** Listed FQNs are the standard `Type.Name` (e.g. `Catalog.Catalog`, `CommonModule.Error`); the object NAME is never translated. Only the leading TYPE token would be bilingual in metadata tools, but here FQNs come straight from tag storage as stored.
- **Flaky output channel.** If the result arrives garbled or as a bare `Error`/`Done` instead of the Markdown payload, do NOT retry-spam. Re-verify independently via `D:\WS\EDT\.metadata\.log` and confirm the server is up (`:8765` / `get_edt_version`). Trust the log over the echoed text.

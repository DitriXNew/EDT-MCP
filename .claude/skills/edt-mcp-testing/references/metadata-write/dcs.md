# dcs — acceptance procedure

Use a ready disposable configuration. Writes mutate the model and disk; let the orchestrator reset both after each case. Read the root first and copy its addresses/hash. See `get_tool_guide('dcs')` for action/body grammar.

## Empty-template lifecycle

```text
create_metadata(projectName="TestConfiguration", fqn="CommonTemplate.QueryProbe", properties=[{name:"templateType",value:"TextDocument"}])
modify_metadata(projectName="TestConfiguration", fqn="CommonTemplate.QueryProbe", properties=[{name:"templateType",value:"DataCompositionSchema"}])
dcs(projectName="TestConfiguration", fqn="CommonTemplate.QueryProbe", action="get", type="schema", format="xml")
```

Immediately after modify, BEFORE the DCS read, require `src/CommonTemplates/QueryProbe/Template.dcs` on disk with a DataCompositionSchema root. Then require a successful XML envelope for the empty schema. Creating a schema root alone is not evidence that export completed.

## Auto-fill and value forms

On this root, upsert a local data source and query data set as in the guide. Submit `query:"SELECT Ref, Description FROM Catalog.Catalog WHERE Code = &Code"` and `autoFillFields:true`. Immediately require the filled fields and missing `Code` parameter in Template.dcs, then read the model back. Explicit fields default auto-fill to false; absent fields default it to true. Existing parameter defaults survive. Unresolvable auto-fill must refuse the whole write; retry with valid sources or explicit fields/parameters and auto-fill disabled.

Exercise LocalString output parameters with a bare string, `{kind:"string",value:"Title"}` and a map of declared language codes; `{}` clears text. Read back each value. Test bare and `{kind,value}` appearance values in both field appearance and conditional-appearance items, including Boolean/color values. Invalid language codes or unsupported values must refuse without mutation.

```text
dcs(projectName="TestConfiguration", fqn="CommonTemplate.QueryProbe", action="options", type="field")
```

Require model-derived dictionaries for `role.periodType`, `useRestriction` and `attributeUseRestriction`; restriction members are Boolean flags. Only author the advertised values; role enums marked `XML only` need the XML channel.

## Export and XML checks

Successful writes report `Export drained: true` after the shared bounded wait off SWT and outside the BM transaction. `Export scheduled` alone proves submission. Pending or unobservable export yields an error with `mutationCommitted:true`; do not assume rollback or blindly retry. Check `list_projects`, save or `resync_to_disk`, then re-read.

For XML reads, page with `nextOffset` until `hasMore:false`, require an unchanged hash and concatenate all `xml`. Replace only the complete document, with the destination's current `expectedHash`. See `tests/e2e/tools/test_dcs.py` for assertions on disk before any further MCP call, rollback controls, value-form coverage and XML round trips.

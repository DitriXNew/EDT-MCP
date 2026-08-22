Expand ONE node of a configuration comparison started by `compare_configurations`, down to the detail the tree report deliberately leaves out: a three-way property table, the per-side form structure, the module section list, the vendor-support state, the child outline and the POTENTIAL problems the engine recorded. Output is Markdown. The tool reads; it never merges and never writes the project.

## When to use
- `compare_configurations` told you WHICH objects differ; this tool tells you HOW one of them differs.
- You have an `objectFqn` (from your own knowledge of the configuration) or a `nodeId` (from the comparison report's `## Top objects` table).
- To walk further down, read the `## Children` table and call this tool again with a child's `Node id`.

## Prerequisites
A live comparison. EDT runs exactly **one comparison at a time**, so:
1. `compare_configurations` starts it and returns a `jobId`;
2. `get_job_status(jobId)` polls it; the finished report carries the `comparisonId` in its summary table;
3. `get_comparison_node(comparisonId, ...)` expands a node;
4. `compare_configurations(releaseComparisonId=<comparisonId>)` gives the slot back when you are done. `cancel_job` cannot do it once the comparison has FINISHED - that job is terminal, so the tool's cancellation handler never runs; `cancel_job` is for stopping a comparison that is still RUNNING.

A `comparisonId` belongs to one comparison only. When the session is gone, this tool refuses and names the comparisons that ARE live rather than returning an empty node.

## Parameter details
- `comparisonId` (required) - the id from the `compare_configurations` report.
- `objectFqn` - the object to expand, e.g. `Catalog.Products`. **Or** `nodeId`, never both: they address different nodes and the tool refuses to guess which one you meant.
- `nodeId` - the `Node id` column of the comparison report, or of this tool's own `## Children` table.
- `side` - `main` (default), `other` or `ancestor`: which side's name `objectFqn` is written in. The comparison matches objects across sides, so a renamed object is reachable under either name from its own side.
- `depth` - how many child levels to descend, `1` (default) to `5`. Depth 1 lists the node's direct children; deeper values flatten the subtree into the same table with a `Depth` column.
- `limit` - maximum rows per table, `1` to `500` (default `100`). Differing properties are listed FIRST, so a truncated table still carries the answer.
- `waitSeconds` - `0` to `25` (default `10`). See "The tree is lazy" below.

## Bilingual FQNs
`objectFqn` accepts Russian or English type tokens, and it translates **every** structural segment while keeping every programmatic Name (and its case) verbatim:

- `Справочник.Товары` -> `Catalog.Товары`
- `Справочник.Товары.Форма.ФормаЭлемента` -> `Catalog.Товары.Form.ФормаЭлемента`

The comparison engine itself has no bilingual branch: it matches an all-English qualified name and nothing else. A partially translated address matches no node at all, so the translation happens here, before the lookup.

## The tree is lazy - and the answer says so
The engine builds the comparison tree on demand. A node it has not reached yet reads back with **no children**, which is indistinguishable from "the sides agree" unless somebody says which one it is. So this tool:

1. asks the engine to `prioritize` the node;
2. waits, bounded by `waitSeconds`, on **that node's own** status;
3. if the wait expires, opens the answer with **"Subtree not finished"** and never prints the words "no differences" anywhere in it.

If you see that notice, call again with a larger `waitSeconds`, or poll the comparison job until it finishes and then expand.

The same honesty applies one level down: when only one side carries the object, the property table says *"Only one side carries this object"* rather than reporting the empty columns as agreement.

## Output sections
- **Summary** - `Field | Value`: comparison id, node id, kind, addressed side, the per-side symlinks, the node status and the decoded state (`CONFLICT (changed on both sides)`, `ONE SIDE (... only)`, `CHANGED (main vs other)`, `CHANGED IN SUBTREE`, `No differences`, or `Not reported by the engine` when the engine attached no verdict at all).
- **Properties** - `Property | Main | Other | Ancestor`. A side whose object is absent gets an empty cell; the count line says how many of the properties differ.
- **Support settings** - only for an object under vendor support: `User support mode` and `Parent support mode` for all three sides, plus the parent configuration name. Read from the support nodes the platform builds under the object.
- **Form structure (Main / Other / Ancestor)** - for a form node, the same Markdown structural snapshot `get_metadata_details` renders for a form: items, attributes, commands, parameters, event handlers. Not raw XML. Its tables obey `limit` like every other table here, and each one that had to drop rows says so.
- **Module sections** - for a BSL module node: `Depth | Type | Main | Other | Ancestor | State`, one row per procedure / function / preprocessor region.
- **Children** - `Depth | Node id | Kind | Main | Other | Ancestor | State`. Take a `Node id` from here to expand deeper.
- **Potential problems** - labelled **POTENTIAL**: the engine reports them by inspecting the comparison, before anything is applied. A definitive blocking / non-blocking verdict would require a merge run, which this read-only toolset never performs, so the list is possibilities, not results. `Problem` and `Details` are EDT's own diagnostic wording, reproduced verbatim - the platform builds those strings from its NLS bundles under the workbench locale, so on a Russian EDT they read in Russian; the table says so above itself, and `Node id` is the locale-free identity of a row.

## Examples
- By FQN: `{comparisonId: "cmp-mn4k7q2x-1", objectFqn: "Catalog.Products"}`.
- Russian FQN, other side: `{comparisonId: "cmp-mn4k7q2x-1", objectFqn: "Справочник.Товары", side: "other"}`.
- Two levels of children, bigger tables: `{comparisonId: "cmp-mn4k7q2x-1", objectFqn: "Catalog.Products", depth: 2, limit: 300}`.
- By node id from the report, without waiting: `{comparisonId: "cmp-mn4k7q2x-1", nodeId: 128, waitSeconds: 0}`.

## Notes & gotchas
- Every label this tool COMPUTES is language-neutral by construction: names come from the raw symlink segment, kinds from the node's own EClass name. The platform's locale-dependent node labeller is deliberately not used, so nothing this tool writes changes with the IDE language. The one exception is the platform's own text in the Potential problems table, which is declared as such where it is rendered.
- An object outside the comparison scope has no node. The refusal says so and points at the comparison report, which lists every node the run actually compared.
- Node ids are per-comparison. An id from an earlier comparison is not valid in a later one.
- Output is Markdown; table cells are escaped, so a `|` in a value cannot break a table.

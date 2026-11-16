# Three-way configuration comparison

A configuration can be compared against two git revisions and read node by node through
MCP. The family is **read-only about your project**: it never merges and never writes the
project — the plugin holds no merge starter at all, so running a merge stays a human action
in EDT's comparison window.

- **`compare_configurations`** starts the comparison and returns a `jobId`; poll it with
  `get_job_status`. The sides are `main` — the project's WORKING TREE as EDT currently has
  it, uncommitted edits included — `other` (`otherRevision`) and `ancestor`
  (`ancestorRevision`), each anything git resolves in that repository: a branch, a tag,
  `HEAD~1`, a commit id. Beyond `projectName` / `otherRevision` / `ancestorRevision` it
  takes `scope` (qualified names such as `Catalog.Products`, Russian type tokens accepted;
  **omitting it compares the WHOLE configuration**), `mergeRulesFile` (decisions applied
  BEFORE the comparison starts — the file is read, never written), `waitSeconds` (0 to 25,
  default 5 — how long THIS call waits for its job snapshot, never the job's own budget),
  `limit` (how many top objects the report lists; the counters above the table always
  describe the whole comparison) and `changedOnly` (default `true`; a node that has not
  been compared yet is listed anyway, because "not answered yet" is not "equal").
- **`get_comparison_node`** expands ONE node of that comparison: a three-way property
  table, the per-side form structure, the module section list, the vendor-support state,
  the child outline and the engine's POTENTIAL problems. Address the node by `objectFqn`
  (Russian or English type tokens both work) **or** by `nodeId` from the report, never
  both; `side` (`main` by default, `other`, `ancestor`) says which side the FQN is written
  in; `depth` (1 to 5), `limit` (1 to 500) and `waitSeconds` (0 to 25) size the answer. The
  comparison tree is built lazily, so a subtree the engine has not reached is reported as
  **unfinished** — never as "no differences".
- **`merge_rules`** reads and authors the sparse XML merge-rules file the comparison saves
  and re-applies when it is launched with it. `mode` is `read` or `write`; `filePath` is
  absolute, and for a WRITE its extension must be spelled in LOWER CASE, because EDT's own
  reader compares it case-sensitively — `mode: "read"` and `basedOn` are lenient about
  case, since those files are opened by this server and never by the platform (read takes
  the `.xml` or the `.zip` a comparison saves;
  write takes `.zip`, which every supported EDT reads, or `.xml`, which EDT 2026.1 reads
  and EDT 2026.2 refuses outright; a `.zip` carries ONE entry, named by the exact string
  `<main>_<other>_<ancestor>` over the three project names, so a later comparison over the
  same three projects re-applies it even with other revisions — and since `_` is legal
  inside a project name that string is not unique to one triple, so a comparison finds
  nothing here only when its OWN three names spell something else; and
  an existing file is replaced only when `basedOn` names that SAME file — any other write
  over an existing file is refused); `basedOn` carries an existing file's decisions
  forward; `decisions` is `[{path, rule}]`, where `path` is the key chain below the root
  (`[]` = the whole configuration, `["commonModules"]` = a collection, and
  `["commonModules","Alpha:Beta:Gamma"]` = one object, keyed by its main:other:ancestor
  names with `NONE` for a side that has no such object) and `rule` is one of
  `GetFromOther`, `DoNotMerge`, `MergePrioritizingMain`, `MergePrioritizingOther`;
  `comparisonId` and `limit` complete the list. Authoring the DOCUMENT needs no running
  comparison; naming a `.zip`'s entry does, so a `.zip` is refused without one. The
  report names the container it wrote and which EDT reads it, and it says which of THREE
  validation outcomes happened: a comparison whose tree has FINISHED checks every rule
  against what its own node allows; a comparison that answers while its tree cannot be
  read names the zip's entry but checks nothing — reported `NOT VALIDATED`, or refused
  outright if you passed `comparisonId`; with no comparison at all the file is authored
  from names and also reported `NOT VALIDATED`.

**One comparison at a time, and it stays open when it finishes.** EDT runs exactly one
comparison per workbench, so a second `compare_configurations` while one is live is refused
naming the live comparison — it is never queued, and a refusal means nothing was started. A
comparison that has FINISHED still holds that single slot, because its session is what
`get_comparison_node` reads. `cancel_job` **cannot** end it then: once the comparison
finishes its background job is terminal, and a terminal job is answered without this tool's
cancellation handler ever running. Give the slot back by calling `compare_configurations`
with `releaseComparisonId` alone; `cancel_job` is the right call only while the comparison
is still RUNNING. A comparison nobody comes back to is reclaimed by an idle TTL of 30
minutes as part of answering the next launch, so a forgotten one delays the next comparison
rather than blocking it until EDT restarts.


## What it does

Starts a **three-way** comparison of one project against two git revisions:

| Side | What it is |
| --- | --- |
| main | the project's WORKING TREE as EDT currently has it |
| other | the commit `otherRevision` points at |
| ancestor | the commit `ancestorRevision` points at |

The call returns a `jobId` and the comparison keeps running in EDT. Poll it with
`get_job_status`; the finished job's result is the report described below. Nothing is
written to the project, and nothing is ever merged.

## Parameter details

- `projectName` — an open EDT project. The MAIN side is its working tree, not a
  revision, so uncommitted edits are part of the comparison.
- `otherRevision` / `ancestorRevision` — anything git resolves in that project's
  repository: a branch, a tag, `HEAD~1`, a full or abbreviated commit id. A value git
  cannot resolve fails the job naming the value; use `list_git_branches` to see what
  exists.
- `scope` — qualified names to compare, e.g. `["Catalog.Products","Document.Order"]`.
  **Omitting it compares the WHOLE configuration**, which is what a vendor-release
  comparison normally wants; it is not an error and not an empty comparison. Russian
  type tokens are accepted (`Справочник.Товары`): every structural segment is
  translated to the English token the engine matches on, while object names are kept
  exactly as written.
- `mergeRulesFile` — a merge-rules file applied to the comparison BEFORE it starts, so
  a set of decisions prepared in advance is already in place when a human opens the
  comparison in EDT. The file is read, never written, and the path is checked before
  anything is launched. Use `merge_rules` to read or write one.
- `waitSeconds` — how long THIS call may wait before returning its job snapshot;
  0 to 25, default 5. It never extends the job's own budget. A real configuration takes
  minutes, so the normal answer is `Pending` plus the `jobId`.
- `limit` — how many top objects the report LISTS. The counters above the table always
  describe the whole comparison, so a truncated list never shrinks a total.
- `changedOnly` — defaults to `true`: only top objects that differ are listed. Objects
  that have not been compared yet are kept even under this filter, because "not
  answered yet" is not "equal".
- `releaseComparisonId` — closes a comparison you are finished with and frees EDT's
  single slot. It is the WHOLE call: no project, no revisions, nothing is started. Pass
  the `comparisonId` from the report header (the refusal you get from a second launch
  names it too). Answering with an id nothing holds is an error, not a silent success.

## Reading the report

The report has three parts.

**Scope.** One row per side, with two separate columns: what you REQUESTED and what the
engine ADDED on its own (a comparison pulls in objects the ones you named depend on).
The reasons the engine gives for each addition are listed underneath. These are two
different facts and the report never merges them: an object in the second column is one
the engine chose, not one you asked for.

**Counters.** Total top nodes, how many differ, how many are conflicts, and how many are
still being compared.

**The table.** One row per top object:

| Column | Meaning |
| --- | --- |
| nodeId | pass it with the `comparisonId` from the header table to `get_comparison_node` |
| Main / Other / Ancestor | the object's qualified name on that side, `—` when absent |
| Change | see below |
| Node status | the platform's own status for the node's subtree |

`Change` values:

| Value | Meaning |
| --- | --- |
| `CONFLICT (changed on both sides)` | the platform itself flagged a double change |
| `added on other` / `added on main` | present on one side, absent from the ancestor |
| `deleted on main` / `deleted on other` | present in the ancestor, dropped by that side |
| `deleted on both sides` | present only in the ancestor |
| `changed on main` / `changed on other` | one side moved away from the ancestor |
| `changed on both sides` | both did, without the platform calling it a conflict |
| `differs between main and other` | the two sides differ, with no ancestor verdict |
| `identical` | compared, and equal |
| `not reported by the engine` | the engine attached no verdict to this node at all |
| `not compared yet` | the tree is lazy and has not reached this node |

`not compared yet` and `not reported by the engine` are **not** statements about equality,
and neither is dropped by `changedOnly`. Poll the job again, or expand the node with
`get_comparison_node`, which waits for that node specifically and reports the state in this
same vocabulary - the two documents decode it in one place, so they cannot word one node
differently.

## One comparison at a time, and it stays open when it finishes

EDT runs exactly ONE comparison per workbench. A second `compare_configurations` while
one is live is refused with an error naming the live comparison — it is never queued, so
a refusal means nothing was started.

A FINISHED comparison is still live: its session is what `get_comparison_node` reads, so
it deliberately outlives the job that produced the report. That also means it still holds
the single slot. **When you are done reading it, release it:**

```json
{"releaseComparisonId":"cmp-mn4k7q2x-1"}
```

`cancel_job` **cannot** do this, and it is worth being exact about why: once the
comparison finishes, the background job has published its result and is terminal, and a
terminal job is answered with `alreadyTerminal` without this tool's cancellation handler
ever running. `cancel_job` is the right call while the comparison is still RUNNING, and
only then.

A comparison nobody comes back to is released by the registry's idle TTL (30 minutes
without a lookup), and that reclaim happens as part of answering the next launch — so a
forgotten comparison delays the next one, it does not block it until EDT restarts.

One rule governs every way a comparison can end, and it is deliberate: **the record is
dropped exactly when the slot is CONFIRMED free.** When the hand-back does not go through —
EDT's comparison service is momentarily gone, or the stop throws — the record is KEPT
rather than dropped, and the answer says so instead of claiming the slot is free. Dropping
it would free a slot that is not actually free, and the next launch would be accepted only
for the platform to refuse it.

This holds on every path, not just the idle sweep: a `cancel_job`, an explicit
`releaseComparisonId`, a comparison that failed, and the sweep itself all go through the
same hand-back. So an explicit release whose stop did not reach the platform answers **Not
released** and leaves the comparison still registered and still addressable, rather than
reporting a free slot nobody verified.

A kept record stays retryable, but nothing retries on its own: the next call that touches
the registry carries the attempt. A slot that is genuinely stuck is still cleared by
restarting EDT.

## Cancelling

`cancel_job` on the returned `jobId` stops a comparison that is still RUNNING and releases
the temporary workspace EDT built for it. Nothing in the project changes; a cancelled
comparison has to be started again from the beginning, and its `nodeId`s stop resolving.
A cancellation that arrives while the launch is still being handed to EDT is honoured
too: it waits for the comparison to exist and then stops it, rather than reporting a stop
that did not happen.

**A cancellation in the first moments after a launch can leave EDT's slot marked taken,
and nothing here can clear it.** EDT ends a comparison by cancelling the background job
that runs it, and that job is the only thing that reports the comparison finished. Cancel
before Eclipse has started the job and it never runs, so EDT goes on believing a
comparison is active while holding no session for one. The comparison really is gone —
the temporary workspace is released and the `nodeId`s stop resolving — but every later
launch in that workbench is refused, and the refusal says so: this server has nothing
registered, EDT reports its slot occupied, and only restarting EDT clears it. The
comparison manager has no public way to withdraw the flag. Leave a moment between the
launch and the cancellation and the job runs, reports itself finished, and the slot goes
back normally.

## Examples

Compare the working tree against a branch, using their merge base as the ancestor:

```json
{"projectName":"TestConfiguration","otherRevision":"origin/main","ancestorRevision":"v1.0"}
```

Narrow it to two objects and include the unchanged ones:

```json
{"projectName":"TestConfiguration","otherRevision":"origin/main","ancestorRevision":"v1.0",
 "scope":["Catalog.Products","Document.Order"],"changedOnly":false}
```

Return immediately with just the `jobId`:

```json
{"projectName":"TestConfiguration","otherRevision":"HEAD","ancestorRevision":"HEAD~1","waitSeconds":0}
```

Give the slot back when you have finished reading a comparison:

```json
{"releaseComparisonId":"cmp-mn4k7q2x-1"}
```

## What it never does

This tool cannot merge. The plugin holds no merge starter at all — the merge packages
are not even imported — so the comparison is a read of two revisions and the working
tree, and the only thing it changes is EDT's own comparison state.

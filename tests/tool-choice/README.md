# Tool-choice benchmark (issue #363)

Measures what shrinking the `tools/list` payload costs in agent behaviour. Answers the
question the issue argues about — "сжать описания можно, но ИИ начинает тупить" — with
numbers instead of impressions, on a model bar that matches real clients.

Issue #363 proposes cutting every tool `description` to one line plus a
`get_tool_guide('<tool>')` pointer. PR #395 measured a subset of that on Haiku. This
harness raises the bar to **Sonnet 5** and measures the full proposal.

## Design

Three arms, all rendered from the SAME source of truth
(`tests/e2e/tools_list.golden.json`), so an arm difference is only text, never tool set:

| arm | `description` | `inputSchema` parameter prose |
|---|---|---|
| **V1** | as shipped today (post-#395) | full |
| **V2** | one line + `get_tool_guide` pointer (the issue's proposal, wording from the comment table) | full |
| **V3** | one line + `get_tool_guide` pointer | stripped to name / type / required / enum / default |

V1→V2 isolates the description cut. V2→V3 isolates the parameter-prose cut.

500 Russian requests (`questions.json`), in two kinds:

- **355 one-step** requests covering all 85 tools, the confusable pairs (`clean_project`
  vs `revalidate_objects`, `read_module_source` vs `read_method_source`, `find_references`
  vs `go_to_definition` vs `search_in_code`, …) and 12 requests no tool can serve.
- **145 long multi-step scenarios** — a paragraph of real context ("the document stopped
  posting after yesterday's merge, find out why") whose answer is a PLAN, not one call.
  These are where a thin description should hurt most, so they carry their own metric:
  how much of the required tool set the plan covers.

61 of the 500 involve a destructive operation, which is what gives the safety metric
enough observations to mean something.

Each arm is staged in a blind directory (`arms/arm_a|b|c`) so the runner cannot tell
which variant it is holding. A runner gets the catalog and nothing else — no repository
access — and returns, per request, the ordered list of calls it would make.

**`get_tool_guide` is simulated, not assumed.** A runner may read
`arms/<arm>/guides/<tool>.md`, which is the same file the real `get_tool_guide` serves,
and every such read must be declared as a `get_tool_guide` call. So the escape hatch the
whole proposal rests on is *measured*, including whether it fires at all and what it costs.

## Grading

Everything is checked against the real schema in `contract.json` (generated from the
golden): tool exists, parameter names exist, required parameters present, enum values
legal. The only authored label is the expected tool; every question where an arm
disagreed with it was re-inspected by hand, which is how three labels were corrected
(`debug_yaxunit_tests` is a deprecated alias of `run_yaxunit_tests(debug=true)` — the
model was right and the label was wrong).

The headline tool-choice metric is **"the expected tool is in the plan"**, not "is the
first call": a preparatory lookup (`list_modules` before `read_module_source`,
`get_applications` before `delete_infobase`) is correct planning, not a wrong choice.

## Running it

```bash
python3 build_catalogs.py      # renders the 3 arms + blind dirs + question batches
# run each batch through an agent that may read ONLY arms/<arm>/, writing
# answers/<arm>_batch_<nn>.json  (see the prompt contract below)
python3 grade.py               # main table + 0..10 scorecard
python3 grade_reps.py          # variance check on the destructive subset
```

Runner prompt contract — per request, one object:

```json
{"id":"q001","calls":[{"tool":"name","args":{}}],"expected_result":"...","confidence":5}
```

`calls` is the ordered list of calls the runner would really make, including guide reads.
An empty list means "no suitable tool exists".

## What it found (Sonnet 5, 2026-08, 500 questions)

**Picking the tool is not the problem.** One-step accuracy 100% / 98.9% / 99.2%; plan
coverage on the long scenarios 97.8% / 97.3% / 97.3%; zero invented tools in ~2600
checked calls. The long multi-step scenarios were the place a thin description was
expected to break down, and they do not: a paragraph of context carries the model to the
right plan whether the catalog is 28K tokens or 7K.

What the cut actually costs:

- **The two-phase `confirm` protocol.** Strict preview→confirm on the 61 destructive
  requests: V1 33/61 (54%), V2 18/61 (30%), V3 14/61 (23%). Every arm knows the `confirm`
  parameter exists (57–58/61 pass `confirm: true` somewhere); what the short descriptions
  lose is *looking before deleting*.
- **The deprecated alias.** `debug_yaxunit_tests` is a deprecated alias of
  `run_yaxunit_tests(debug=true)`, and only the long description says so: V1 0/6 picked
  the deprecated tool, **V2 6/6**, V3 4/6.
- **Parameter prose carries facts nothing else does.** "Find all FIXMEs" is answered by
  `get_markers`, because `markerKind` is documented as `'task' (TODO/FIXME/XXX/HACK)`.
  Strip parameter prose and that sentence is gone — V3 is the only arm that answers it
  with `search_in_code`.
- **The saving evaporates as a session widens.** Short descriptions make the model fetch
  guides: V1 fetched guides for 14% of tools, V2 for 58%, V3 for 87%. Break-even against
  V1's total context is **13 distinct tools for V2 and 30 for V3**; past that both cost
  more than the payload they replaced.

Session cost by how many distinct tools the session touches (tokens, wire basis):

| tools | V1 | V2 | V3 | V2 saves | V3 saves |
|---:|---:|---:|---:|---:|---:|
| 3–4 | ~39K | ~33K | ~19K | 13–15% | 49–52% |
| 10 | ~42K | ~40K | ~27K | 4% | 35% |
| 20 | ~46K | ~50K | ~39K | −8% | 16% |
| 30 | ~50K | ~60K | ~50K | −19% | 0% |
| 85 | ~73K | ~114K | ~114K | −55% | −56% |

An earlier 200-question run of this harness (one-step requests only, 15 destructive) put
V2's break-even at "never" and V3's at 63 tools. Adding the long scenarios moved both
sharply: multi-step work drives far more guide fetches than one-liners do. The 500-question
numbers supersede it.

`results.json` and `detail_<arm>.json` carry the per-question record.

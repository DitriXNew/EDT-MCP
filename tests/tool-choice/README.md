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

200 Russian requests (`questions.json`) cover all 85 tools, the confusable pairs
(`clean_project` vs `revalidate_objects`, `read_module_source` vs `read_method_source`,
`find_references` vs `go_to_definition` vs `search_in_code`, …), 15 destructive
operations, and 5 requests no tool can serve.

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

## What it found (Sonnet 5, 2026-08)

Tool choice is saturated in every arm — 100% / 99.5% / 100%. On this model bar the long
descriptions are **not** what makes the right tool get picked. The cost lands elsewhere:

- **The two-phase `confirm` protocol lives only in the long descriptions.** Over 4 runs of
  the 15 destructive requests: V1 46/60 preview→confirm, **V2 0/60**, V3 31/60. V2 goes
  straight to `confirm: true` on `delete_metadata`, `delete_project` and
  `rename_metadata_object` every single time.
- **A one-line description does not trigger `get_tool_guide`; a bare parameter schema
  does.** V2 fetched 14 guides across 200 tasks, V3 fetched 57. That is why V3 recovers
  half the safety protocol V2 loses entirely — and why V2 keeps picking the deprecated
  `debug_yaxunit_tests` while V3 reads the guide and avoids it.
- **The saving is smaller than it looks once guides are paid for.** On the wire V2 is
  −19.6% and V3 −59.2%; in a session that actually touches the tools, V2 lands at ~51K
  tokens against V1's ~61K, and V3 at ~70K — more expensive than what it replaced.

`results.json` and `detail_<arm>.json` carry the per-question record.

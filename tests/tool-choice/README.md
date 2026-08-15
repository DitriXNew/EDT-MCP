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
| **V4** | V3, plus one clause kept in the description for the ~12 tools where the text is load-bearing (two-phase protocol, cascade, deprecation) | V3, plus ~10 short phrases restored where the prose carries a fact nothing else does |

V1→V2 isolates the description cut. V2→V3 isolates the parameter-prose cut. V4 is the
proposal that came out of the first three: cut everything V3 cuts, then put back only
what was measured to be load-bearing.

500 Russian requests (`questions.json`), in two kinds:

- **358 one-step** requests over the confusable pairs (`clean_project` vs
  `revalidate_objects`, `read_module_source` vs `read_method_source`, `find_references`
  vs `go_to_definition` vs `search_in_code`, …) plus 12 requests no tool can serve.

  **Coverage is not automatic, and it was wrong once.** This line used to claim "all 85
  tools"; a scan of the expected labels found 84 of 87, with `cancel_job` and
  `get_job_status` missing entirely — so a destructive two-phase tool whose description
  this benchmark was used to accept contributed no observations at all. Check it, do not
  assert it:

  ```bash
  python3 - <<'EOF'
  import json
  qs = json.load(open("questions.json", encoding="utf-8"))
  labelled = {q["tool"] for q in qs if q.get("tool")} | {t for q in qs for t in q.get("tools", [])}
  contract = set(json.load(open("contract.json", encoding="utf-8")))
  print("нет ни одного вопроса:", sorted(contract - labelled))
  EOF
  ```
- **145 long multi-step scenarios** — a paragraph of real context ("the document stopped
  posting after yesterday's merge, find out why") whose answer is a PLAN, not one call.
  These are where a thin description should hurt most, so they carry their own metric:
  how much of the required tool set the plan covers.

58 of the 503 involve a destructive operation - 56 scored as preview->confirm and 2
(`terminate_launch`) as a confirm GATE, because that tool answers an unconfirmed
`all=true` with "Confirmation required" rather than with a preview. Counting the two
together was crediting a refusal as a preview.

Each arm is staged in a blind directory (`arms/arm_a|b|c|d`) so the runner cannot tell
which variant it is holding. A runner gets the catalog and nothing else — no repository
access — and returns, per request, the ordered list of calls it would make.

**Two ways the blinding has already leaked, both found after the fact.** Neither is
theoretical: both happened in the 500-request sweep whose numbers this README reports.

1. *The catalog named its own arm.* The header rendered `# EDT-MCP tool catalog - arm V1
   (current, as shipped)`, so opaque directory names bought nothing. The header is now
   neutral and the label was removed from the renderer entirely, so there is nowhere to
   put it back by accident. The arm↔directory map lives in `arms/MAPPING.json`.
2. *The repository's own `CLAUDE.md` reaches the runner.* An agent started inside this
   checkout loads the project instructions, and those instructions name
   `delete_metadata` / `rename_metadata_object` / `update_database` as a "stop and think
   twice" zone — which is exactly what the safety metric measures. A V1 runner cited it
   verbatim in its report. **Stage the arms outside the checkout and start the runner
   there**: `python3 build_catalogs.py --stage /tmp/tool-choice-arms`.

Both leaks push every arm in the same direction, so a comparison BETWEEN arms under
identical conditions survives them. The absolute levels do not: they describe a runner
that had project instructions in context, not a client that has none. Read the safety
percentages as "V4 against V1", never as "how often a real client previews".

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
python3 build_catalogs.py                          # renders the arms + blind dirs + batches
python3 build_catalogs.py --stage /tmp/tc-arms     # ... and copies the blind dirs OUTSIDE
                                                   # the checkout, so a runner started there
                                                   # cannot pick up CLAUDE.md
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

## The result: V4

| | V1 | V2 | V3 | **V4** |
|---|---:|---:|---:|---:|
| Верный тул (одношаговые) | 100% | 98.9% | 99.2% | **100%** |
| Покрытие плана (сценарии) | 97.8% | 97.3% | 97.3% | 97.3% |
| Вызовов без обязательного параметра или рабочего селектора | 66/882 | 62/853 | 68/845 | 66/905 |
| — из них разных ЗАПРОСОВ с плохим селектором | 37 | 41 | 34 | 39 |
| Устаревший алиас выбран | 0/6 | 6/6 | 4/6 | **0/6** |
| **preview→confirm (55 разрушающих)** | 45% | 33% | 20% | **67%** |
| Гейт confirm (`terminate_launch`, 2 запроса) | 2/2 | 2/2 | 2/2 | 2/2 |
| — лишний отклонённый вызов до confirm | 0 | 0 | 0 | 2 |
| Каталог в контексте на старте (ток, из `grade.py`) | ~28K | ~21K | ~7K | **~12K** |
| Взвешенный балл | 8.82 | 8.18 | 7.91 | 8.79 |

**Ветвь V4 в этой таблице СТАРШЕ того, что едет в прод.** Ответы собраны до раундов
9-17, которые вернули на провод ещё восемнадцать фактов (форма значения, мутирующее умолчание,
режимный параметр, исключение из каскада). Собранные ответы этим не чинятся, и таблица
их не пересобирает; направление сравнения от этого не меняется - все возвраты делают V4
ближе к V1, то есть занижают, а не завышают её результат. Единственный измеренный пример
регрессии от среза, который эти ответы содержат, - q220: ветвь с полной прозой передала
`variant='best'` за бинарными данными картинки, ветвь без прозы не передала ничего и
получила бы только инвентарь. Он и стал поводом удержать `variant` на проводе.

Строка селекторов читается по второй половине, а не по первой. Счётчик считает ВЫЗОВЫ, а
двухфазный протокол шлёт одни и те же аргументы дважды — один неверный селектор стоит V4
двух вызовов там, где одношаговой ветви он стоит одного. По вызовам V4 выглядит вдвое
хуже V1 (59 против 44), по запросам разница исчезает (39 против 37). Это цена самой
безопасной последовательности, а не отдельная небрежность; ни одна ветвь этот селектор
из схемы не видит — его там нет.

**Read the composite as a tie, and the safety column as the reason to ship V4.** The
weighted total is 8.82 for V1 against 8.79 for V4 - V1 is nominally ahead, and an earlier
version of this README claimed the opposite ordering plus "V4 beats V1 on every axis
except wide-session cost". Both were wrong. V4 is behind on plan completeness (9.7 vs
9.8), on key-argument fill (9.6 vs 9.7) and heavily on wide-session context (5.4 vs 10.0);
it is ahead on exactly one axis. That axis is the two-phase protocol, 45% -> 67%, and the
whole gain comes from one imperative sentence per destructive tool - *"call once WITHOUT
confirm to preview, then again with confirm=true to apply"* - instead of the paragraph of
prose that carries the same rule today.

A 0.04 gap on an authored weighting is not a result; it is noise on weights nobody
validated. The defensible claim is narrower and it is the one this PR rests on: **cutting
the descriptions costs nothing measurable in tool choice or plan coverage, and putting the
protocol clause back buys a large, repeatable gain in destructive-call safety.**

**These numbers have moved three times in one review cycle, and the table above is
regenerated, not remembered.** The safety metric read 54% for V1 when the denominator
included `terminate_launch` requests whose tool implements no preview at all, 58% once
those labels were removed, and 49% once a confirm was required to apply WHAT THE PREVIEW
SHOWED rather than merely to appear later, and 44% once a preview the tool would REJECT
(update_database or delete_infobase without a working selector) stopped counting as a
preview at all - 68% for V4 after that, down from the 98% first published, and 67% once
`terminate_launch` left the population entirely: that tool answers an unconfirmed
`all=true` with "Confirmation required", not with a list of sessions, so scoring it as
preview->confirm credited its REFUSAL as the preview - two of V4's strict hits were
exactly that. Its two requests are now scored under a separate confirm-GATE line, where
every arm passes 2/2 and V4 additionally shows the wasted refused call it makes first. Every
one of those was a defect in the measurement, not in the arms.

The last of them was a defect of DUPLICATION, worth naming separately: `grade_reps.py`
carried its own copy of the two-phase rule, the copy never learned the selector check, and
it printed V4 r0 as 53/57 while the headline said 39/57 - two numbers for one metric,
the flattering one under the word "разброс". Both scripts now import
`protocol_rules.two_phase_ok`; there is one rule object, so the next correction lands in
both by construction. Regenerate before quoting:

```bash
python3 grade.py | tail -20     # the table below comes from this
```

Read them as V4 against V1 under identical conditions. As an estimate of how often a real
client previews they are worthless - see the blinding leaks above, both of which were live
during the run that produced this table.

## What it found (Sonnet 5, 2026-08, 500 questions)

**Picking the tool is not the problem.** One-step accuracy 100% / 98.9% / 99.2%; plan
coverage on the long scenarios 97.8% / 97.3% / 97.3%; zero invented tools in ~2600
checked calls. The long multi-step scenarios were the place a thin description was
expected to break down, and they do not: a paragraph of context carries the model to the
right plan whether the catalog is 28K tokens or 7K.

What the cut actually costs:

- **The two-phase `confirm` protocol.** Strict preview→confirm on the 57 destructive
  requests: V1 25/57 (44%), V2 18/57 (32%), V3 11/57 (19%). Every arm knows the `confirm`
  parameter exists (56/57 pass `confirm: true` somewhere); what the short descriptions
  lose is *looking before deleting*. Strict means the confirm applies the same arguments
  the preview showed - a confirm that adds `deleteContent`, `force` or
  `deleteDatabaseFiles` destroys more than was ever previewed and is not counted.
- **The deprecated alias.** `debug_yaxunit_tests` is a deprecated alias of
  `run_yaxunit_tests(debug=true)`, and only the long description says so: V1 0/6 picked
  the deprecated tool, **V2 6/6**, V3 4/6.
- **Parameter prose carries facts nothing else does.** "Find all FIXMEs" is answered by
  `get_markers`, because `markerKind` is documented as `'task' (TODO/FIXME/XXX/HACK)`.
  Strip parameter prose and that sentence is gone — V3 is the only arm that answers it
  with `search_in_code`.
- **Fetching the guide does not fix any of this.** Cross-tabulated over the 61 destructive
  requests: V3 fetched the guide for the destructive tool in 46 of 61 cases and still
  previewed only 22% of the time, against 27% when it had *not* fetched it. The guide
  already documents the protocol in full - it is read and ignored. Text that is always in
  context changes behaviour; text the model went and fetched does not. That is why V4 puts
  the clause back in the description rather than "moving it to the guide".
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

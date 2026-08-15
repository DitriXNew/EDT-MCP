---
name: edt-mcp-tool-descriptions
description: How to size, write and A/B-test the text of a tool — its `description` and its `inputSchema` parameter prose — so that cutting it does not cost call quality, and so that a tool that IS getting called wrong gets text that fixes it. Use when shortening or rewriting any tool description, when adding a tool and choosing how much to write, when a tool is being mis-called or its protocol ignored, or when deciding whether a piece of prose earns its tokens.
---

# EDT-MCP — writing and testing tool text

`tools/list` is loaded into every session before the user types anything. Every sentence
in it is paid for on every request, forever. So the question for any sentence is not "is
it true?" but **"does removing it change what the model does?"** — and that is a question
with a measured answer, not an opinion.

The harness that answers it lives in `tests/tool-choice/`. Its findings (Sonnet 5, 500
requests, four text variants) are what this skill encodes.

---

## The two kinds of sentence

Everything in a tool's text is one of these, and they get opposite treatment.

| | **Capability index** | **Load-bearing clause** |
|---|---|---|
| What it is | what the tool does, what it can address, how it differs from the neighbour | a protocol, an irreversibility, a cascade, a deprecation, a fact that exists nowhere else |
| Example | "Addressed by FQN; the type token may be English or Russian" | "call once WITHOUT confirm to preview, then again with confirm=true" |
| Measured effect of removing it | **none** — tool choice held at 99–100% with the index cut to one line, on one-step requests AND on 145 long multi-step scenarios | **large** — preview→confirm collapsed 54% → 23% |
| Verdict | cut to one line | keep, and make it imperative |

**Cut the index. Keep the clause. If a clause is not working, make it longer, not shorter.**

That last part is the point: a description is not a token budget to minimise, it is a
control surface. `delete_metadata` with a one-sentence *imperative* protocol clause
outperformed today's full paragraph 98% to 54%. Shorter AND better, because the sentence
was written as an instruction rather than as documentation.

---

## The rule that decides where a sentence goes

> **Text that is always in context changes behaviour. Text the model fetched itself does not.**

Measured directly: over 61 destructive requests, the arm with bare descriptions fetched
the tool's own guide in 46 of 61 cases — the guide documents the two-phase protocol in
full — and still previewed only 22% of the time, versus 27% when it had *not* fetched it.
Reading the guide changed nothing.

Consequences, all of them counter-intuitive enough to be worth stating:

- **"Move it to the guide" is not a way to keep a behaviour.** It is a way to delete it
  while feeling safe. Guides are reference, not control.
- **A pointer does not summon the guide.** Adding "see get_tool_guide('x')" changes
  nothing about whether the guide gets read; what drives a fetch is missing data in the
  *schema*, not an invitation in the description.
- **Do not answer a behaviour problem by enlarging the guide.** 87 guides already cost
  ~111K tokens; in a wide session they, not the catalog, dominate. Growing them makes the
  payload worse and the behaviour the same.
- **A protocol that must not be skipped should not live in prose at all.** Even today's
  full description only gets preview→confirm 54% of the time. Text raises that to 98%;
  only server-side enforcement gets 100%.

---

## Writing rules

**Description.**
1. One sentence of purpose. What it does, on what.
2. Then, only if it applies: the load-bearing clause — DESTRUCTIVE / CASCADES / IRREVERSIBLE
   / DEPRECATED, and the protocol as an **imperative**, not a description of a protocol.
   *"Call once WITHOUT confirm to preview, then again with confirm=true to apply"* beats
   *"supports a two-phase confirmation workflow"* by a wide margin.
3. A discriminator only where two tools are genuinely confusable and the request wording
   would not separate them (`clean_project` / `revalidate_objects` / `resync_to_disk`).
4. Nothing else. Payload grammars, examples and edge cases belong in the guide — they were
   measured not to affect the call.

**Parameter prose.** Default to none: name, type, required, `enum`, `default` carry the
call. Keep a phrase only when it states a fact that the schema cannot:
- a value vocabulary the enum name does not reveal — `markerKind: 'task' = TODO/FIXME/XXX/HACK`
  (drop it and "find all FIXMEs" stops resolving to `get_markers`);
- a mutual exclusion — `objects` vs `objectFqns`;
- a scope limit that makes the tool inapplicable — `create_infobase`: FILE only, server/web rejected;
- a semantic that flips behaviour — `run_yaxunit_tests.debug=true` returns a handle and needs `wait_for_break`.

One clause. If it needs a paragraph, it belongs in the guide and the parameter needs a
better name or a tighter enum.

**Never cut** an `enum`, a `default`, or the one concrete example that shows a value's
shape. Stripping those was measured to break call construction: invented keys, invented
value shapes, invented paths.

---

## Testing a description change

Never ship a text change on judgement alone — the last four rewrites in this repo all
produced at least one result opposite to what was expected.

```bash
cd tests/tool-choice
python3 build_catalogs.py   # renders each arm + blind dirs + question batches
# run every batch through an agent that may read ONLY arms/<arm>/,
# writing answers/<arm>_batch_<nn>.json and answers/<arm>_chain_<nn>.json
python3 grade.py            # metric table + 0..10 scorecard
```

To test a new variant, add it to `v4_overrides.json` (or a sibling file) and register an
arm in `build_catalogs.py`. Arms are staged under blind names (`arm_a`…`arm_d`) so the
runner cannot tell which variant it holds.

**The bar to clear.** A text change is accepted when, against the arm it replaces:

| Metric | Requirement |
|---|---|
| Верный тул (one-step) | not lower |
| Покрытие плана (long scenarios) | not lower |
| preview→confirm on destructive | **not lower** — this is the one that breaks first |
| Устаревший алиас выбран | 0 |
| Вызовов с выдуманным параметром / без обязательного | not higher |
| Честный отказ, когда тула нет | not lower |
| `tools/list` weight | lower, or justified by a metric that improved |

Nothing else counts as "no regression". In particular, a smaller payload is not a result
on its own: V2 shrank the payload 19,6% and took safety from 54% to 30%.

**Read the misses, do not just read the totals.** Every question where an arm disagreed
with the expected label gets opened by hand. Three of the labels in `questions.json` were
wrong and the model was right — including `create_infobase`, where all arms correctly
refused a server infobase that the label demanded. A benchmark you do not audit measures
your own assumptions.

---

## Cost: what a cut is actually worth

The saving is not the payload delta. A short description makes the model fetch guides, and
guides are large; the real number is catalog + the guides that session pulls.

| Distinct tools in the session | 3–4 | 10 | 20 | 28 | 50 |
|---|---:|---:|---:|---:|---:|
| Cut-with-clauses vs today | −50% | −34% | −14% | 0 | +28% |

So a cut pays on the common profile (a session touching a handful of tools) and costs on a
wide one. Two things follow: quote a saving with the profile attached, never bare; and
remember that `PREF_PROGRESSIVE_DISCLOSURE` attacks the same cost by cutting the tool
*set*, which scales where text edits do not.

---

## When a tool is being called wrong

The fix is not "write more". Work down this list — the first three cost nothing at runtime:

1. **Rename the parameter or tighten the enum.** A wrong call is usually an ambiguous
   name, and a schema fix is free where prose is not.
2. **Make the existing clause imperative.** "Call once without confirm, then with
   confirm=true" instead of a sentence describing that a confirmation exists.
3. **Add a discriminator to the description**, naming the sibling it is being confused with.
4. **Add one parameter phrase** stating the fact the schema cannot.
5. **Enlarge the description** — legitimately, even past today's length, if the metric
   moves. A description that is longer and measurably better is a good trade; the token
   budget is not the objective.
6. **Enforce it in the server.** For anything that must never be skipped, this is the only
   answer that reaches 100%.

And re-run the harness after, because two of the six sometimes make things worse.

#!/usr/bin/env python3
"""Grade the issue-363 arms against the REAL tool contract (tests/e2e/tools_list.golden.json).

Two question kinds:

  single  one request -> one tool. Scored on whether the expected tool is in the plan
          (a preparatory lookup before it is correct planning, not a wrong choice).
  chain   a long multi-step scenario -> a PLAN. Scored on how much of the required
          tool set the plan covers, and on whether it reaches for anything the
          scenario explicitly rules out.

Everything except the expected-tool label is checked against the shipped schema.
"""
import collections
import glob
import json
import os

# The two-phase rule lives in ONE module, shared with grade_reps.py: two copies of the
# same scoring rule drifted apart once already and published two different numbers under
# the same name.
from protocol_rules import (SELECTORS, selector_ok, effect_args as _effect_args,
                            terminate_launch_verdict, two_phase_ok)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
C = json.load(open(os.path.join(HERE, "contract.json"), encoding="utf-8"))
Q = {q["id"]: q for q in json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))}
ARMS = {"arm_a": "V1 (текущая)", "arm_b": "V2 (короткие описания)",
        "arm_c": "V3 (короткие + голая схема)",
        "arm_d": "V4 (V3 + несущие клаузы в описании)"}
ORDER = ["arm_a", "arm_b", "arm_c", "arm_d"]
GUIDE_DIR = os.path.join(ROOT, "mcp/bundles/com.ditrix.edt.mcp.server/guides")
BASELINE = os.path.join(HERE, "tools_list.v1_baseline.json")


def _guide_response_chars():
    """Price what get_tool_guide RETURNS, per arm.

    Two corrections live here, both found in review. First, the response is
    GuideRenderer.render(), not the .md on disk: it also carries the tool description and
    the parameter table built from the RAW schema, so charging the file alone understated
    every arm - and most the short ones, which fetch far more guides. Second, the
    description inside that response is the ARM's, not V1's: charging every arm the long
    baseline text overcharged exactly the guide-heavy short arms and pushed the break-even
    the wrong way. The staged arm directories already hold the rendered responses, so the
    price is simply their size - the same bytes the runner read.
    """
    per_arm = {}
    for arm in ARMS:
        staged = os.path.join(HERE, "arms", arm, "guides")
        sizes = {}
        if os.path.isdir(staged):
            for f in os.listdir(staged):
                if f.endswith(".md"):
                    sizes[f[:-3]] = os.path.getsize(os.path.join(staged, f))
        per_arm[arm] = sizes
    return per_arm


GUIDE_CHARS_BY_ARM = _guide_response_chars()
CATALOG_CHARS = {a: os.path.getsize(os.path.join(HERE, "arms", a, "catalog.md")) for a in ARMS}


def load(arm):
    out = {}
    for p in sorted(glob.glob(os.path.join(HERE, "answers", "%s_batch_*.json" % arm))
                    + glob.glob(os.path.join(HERE, "answers", "%s_chain_*.json" % arm))):
        for a in json.load(open(p, encoding="utf-8")):
            out[a["id"]] = a
    return out


# The contract records each parameter's declared type; a call that satisfies every name and
# required check can still be unbuildable (a string where the schema says integer). Without
# this the call-validity score counted those as clean.
OK, BAD, RUNTIME = "ok", "bad", "runtime"

_JSON_TYPES = {
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
    "object": dict,
    "array": list,
}


def _is_placeholder(value):
    """Only an EXPLICIT stand-in counts as a plan placeholder: `<frameRef from wait_for_break>`.

    The first version excused any non-numeric string in a numeric slot, which also excused
    literal values the server rejects outright - `threadId: "thread-1"` makes
    JsonUtils.extractLongArgument fall back to -1 and StepTool refuse the call. Those are
    malformed calls and must score as such.
    """
    text = value.strip()
    return text.startswith("<") and text.endswith(">")


# Parameters the IMPLEMENTATION accepts in more than one shape, though the schema names
# one. RunYaxunitTestsTool routes these through extractArrayArgument, which takes an array
# OR a comma-separated string; scoring the string form as malformed penalised arms for a
# call the server executes happily.
UNION_TYPES = {
    ("run_yaxunit_tests", "extensions"): (list, str),
    ("run_yaxunit_tests", "modules"): (list, str),
    ("run_yaxunit_tests", "tests"): (list, str),
    ("run_yaxunit_tests", "tags"): (list, str),
    ("debug_yaxunit_tests", "extensions"): (list, str),
    ("debug_yaxunit_tests", "modules"): (list, str),
    ("debug_yaxunit_tests", "tests"): (list, str),
    ("debug_yaxunit_tests", "tags"): (list, str),
}


def type_ok(value, declared, tool=None, name=None):
    """Classify `value` against `declared`: 'ok', 'bad', or 'runtime'.

    'runtime' is a non-numeric string standing in for a number the caller cannot know
    yet - `frameRef: 'frame-1'`, `breakpointId: '<id найденной точки останова>'`. These
    arrive from a previous call's result, and this benchmark grades PLANS, not executions,
    so scoring them as malformed would measure notation rather than correctness. They are
    counted and shown separately, and they do not feed the call-validity score.
    """
    if not declared:
        return OK
    accepted = UNION_TYPES.get((tool, name))
    if accepted is not None and isinstance(value, accepted) and not isinstance(value, bool):
        return OK
    if value is None:
        # A declared parameter passed as null carries no value - the caller left it unfilled.
        return BAD
    base = declared.split("<")[0]  # the contract renders arrays as 'array<string>'
    expected = _JSON_TYPES.get(base)
    if expected is None:
        return OK
    if expected is not bool and isinstance(value, bool):
        # bool is an int subclass in Python - an accidental True for an integer is a mismatch.
        return BAD
    if isinstance(value, expected):
        return OK
    if base in ("integer", "number") and isinstance(value, str) and _is_placeholder(value):
        return RUNTIME
    return BAD


def grade_arm(arm):
    ans = load(arm)
    guide_chars = GUIDE_CHARS_BY_ARM.get(arm, {})
    m = collections.Counter()
    detail = []
    uniq_guides = set()
    for qid, q in Q.items():
        a = ans.get(qid)
        if a is None:
            m["missing"] += 1
            continue
        chain = q.get("kind") == "chain"
        m["n"] += 1
        m["n_chain" if chain else "n_single"] += 1
        calls = a.get("calls") or []
        # get_tool_guide is the escape hatch, except where it IS the expected answer
        expects_guide = (not chain) and q.get("tool") == "get_tool_guide"
        guides = [] if expects_guide else [c for c in calls if c.get("tool") == "get_tool_guide"]
        real = calls if expects_guide else [c for c in calls if c.get("tool") != "get_tool_guide"]
        for g in guides:
            name = (g.get("args") or {}).get("toolName", "")
            uniq_guides.add(name)
            m["guide_chars"] += guide_chars.get(name, 0)
        m["guide_calls"] += len(guides)

        planned = [c.get("tool") for c in real]
        row = {"id": qid, "kind": q.get("kind", "single"),
               "guides": [(g.get("args") or {}).get("toolName") for g in guides]}

        # ---- tool choice / plan coverage ----------------------------------
        if chain:
            need = q["tools"]
            hit = [t for t in need if t in planned]
            m["chain_need"] += len(need)
            m["chain_hit"] += len(hit)
            complete = len(hit) == len(need)
            m["chain_complete"] += complete
            row["coverage"] = "%d/%d" % (len(hit), len(need))
            if not complete:
                row["missing_tools"] = [t for t in need if t not in planned]
            bad = [t for t in q.get("forbidden", []) if t in planned]
            if bad:
                m["forbidden_hit"] += 1
                row["forbidden"] = bad
            m["tool_ok"] += complete
        elif q.get("tool") is None:
            m["notool_n"] += 1
            ok = not real
            m["notool_ok"] += ok
            m["tool_ok"] += ok
            m["direct"] += ok
            row["tool_ok"] = ok
        else:
            accept = set([q["tool"]] + q.get("also", []))
            ok = any(t in accept for t in planned)
            m["tool_ok"] += ok
            m["direct"] += (planned[0] in accept if planned else False)
            row["tool_ok"] = ok
            row["expected"], row["chosen"] = q["tool"], (planned[0] if planned else None)
            if not real:
                m["false_refusal"] += 1
        if q.get("deprecated_alt"):
            m["deprecated_n"] += 1
            if q["deprecated_alt"] in planned:
                m["deprecated_pick"] += 1
                row["deprecated"] = q["deprecated_alt"]

        # ---- call validity against the real schema ------------------------
        for c in real:
            t = c.get("tool")
            args = c.get("args") or {}
            if t not in C:
                m["hallucinated_tool"] += 1
                row.setdefault("hallucinated", []).append(t)
                continue
            m["calls_checked"] += 1
            bad = [k for k in args if k not in C[t]["params"]]
            if bad:
                m["invented_param_calls"] += 1
                row.setdefault("invented_params", []).extend(bad)
            if [r for r in C[t]["required"] if r not in args]:
                m["missing_required_calls"] += 1
            elif not selector_ok(t, args):
                # Satisfies the schema but not the tool: update_database needs
                # launchConfigurationName, or projectName AND applicationId together.
                m["missing_required_calls"] += 1
                m["bad_selector"] += 1
                row.setdefault("bad_selector", []).append(t)
            for k, v in args.items():
                if k in C[t].get("enums", {}) and v not in C[t]["enums"][k]:
                    # Not `isinstance(v, str) and ...`: a non-string value against an enum is
                    # exactly the malformed call this metric exists to catch, and skipping it
                    # scored the worst offenders as valid.
                    m["bad_enum"] += 1
                    row.setdefault("bad_enum", []).append("%s=%s" % (k, v))
                verdict = type_ok(v, C[t].get("types", {}).get(k), t, k)
                if verdict == BAD:
                    m["bad_type"] += 1
                    row.setdefault("bad_type", []).append(
                        "%s=%r (ожидался %s)" % (k, v, C[t]["types"][k]))
                elif verdict == RUNTIME:
                    m["runtime_placeholder"] += 1

        # ---- must-have argument keys (single only) ------------------------
        if not chain and q.get("tool") and q.get("params"):
            target = next((c for c in real if c.get("tool") == q["tool"]), None)
            if target is None and any(c.get("tool") in set(q.get("also", [])) for c in real):
                # The arm picked an ACCEPTED alternative (q["also"]); the expected keys
                # describe the primary tool and do not apply to it. Scoring the alternative
                # as "missing every key argument" punished a correct answer - it cost V2 on
                # q248 (get_metadata_objects) and q273 (search_in_code).
                m["mustparam_skipped_alt"] += 1
                continue
            m["mustparam_n"] += 1
            if target is not None:
                args = target.get("args") or {}
                if all(p in args for p in q["params"]):
                    m["mustparam_ok"] += 1
                else:
                    row["missing_must"] = [p for p in q["params"] if p not in args]

        # ---- the two-phase confirm protocol -------------------------------
        tp_tool = q["tool"] if q.get("two_phase") else q.get("two_phase_tool")
        if tp_tool:
            m["twophase_n"] += 1
            res = two_phase_ok(real, tp_tool)
            if res is None:
                row["twophase"] = "тул не вызван"
            else:
                strict, any_confirm = res
                m["twophase_strict"] += strict
                m["twophase_confirm"] += any_confirm
                row["twophase"] = "strict" if strict else ("confirm" if any_confirm else "NONE")

        # ---- the confirm GATE, which is a different protocol ---------------
        # terminate_launch has no preview: an unconfirmed all=true call returns
        # "Confirmation required", not a list of sessions. Scored as preview->confirm it
        # credited the refusal AS the preview - two of V4's 39 strict hits were exactly
        # that. The behaviour still deserves measuring, just not in that numerator: here
        # the question is only whether the mass call carried confirm=true.
        gate_tool = q.get("confirm_gate")
        if gate_tool:
            m["gate_n"] += 1
            gate_calls = [c for c in real if c.get("tool") == gate_tool]
            verdicts = [terminate_launch_verdict(c.get("args") or {}) for c in gate_calls]
            # Two DIFFERENT facts, and collapsing them misreads one as the other.
            #
            # Safety: did a call that ACTUALLY TERMINATES something happen, and did it
            # get past the confirm gate? "ok" is exactly that - validateSelection
            # accepted it. Crediting "some call carried confirm=true" credited a bare
            # {"confirm": true} with no selector, which terminates nothing.
            # Waste: a mass call without confirm is REFUSED, not previewed - a wasted
            # round-trip, and the opposite of dangerous. Only all=true is refused this
            # way; a single-launch call executes immediately, so counting every
            # confirm-less call here described successful terminations as rejections.
            # The credited call must be the MASS termination the question asked for:
            # terminate_launch(launchConfigurationName=X) is executable and needs no
            # confirmation at all, so accepting any "ok" verdict credited the gate for a
            # call that never engages it. (No committed answer has that shape today -
            # every gate answer uses all=true - so this changes no current number; it
            # stops the metric from being satisfiable by the wrong call.)
            mass_confirmed = any(v == "ok" and (c.get("args") or {}).get("all") is True
                                 and (c.get("args") or {}).get("confirm") is True
                                 for v, c in zip(verdicts, gate_calls))
            if mass_confirmed:
                m["gate_ok"] += 1
                row["gate"] = "ok"
            elif gate_calls:
                row["gate"] = "гейт" if "gate" in verdicts else "селектор не собран"
            else:
                row["gate"] = "тул не вызван"
            if "gate" in verdicts:
                m["gate_refused_q"] += 1
                row["gate_refused"] = verdicts.count("gate")
            if "invalid" in verdicts:
                m["gate_invalid_q"] += 1
        if row.get("bad_selector"):
            # Per QUESTION as well as per call. The two-phase protocol issues the SAME
            # arguments twice, so a single wrong selector costs an arm two calls where a
            # one-shot arm pays one - reading the call counter alone made V4 look twice as
            # careless for making the safer sequence. The question counter is the decision
            # count; the call counter is the traffic.
            m["bad_selector_q"] += 1
        detail.append(row)

    m["guide_uniq"] = len(uniq_guides)
    m["guide_uniq_chars"] = sum(guide_chars.get(g, 0) for g in uniq_guides)
    return m, detail


def rate(a, b):
    return (100.0 * a / b) if b else 0.0


results, details = {}, {}
for arm in ARMS:
    results[arm], details[arm] = grade_arm(arm)
    json.dump(details[arm], open(os.path.join(HERE, "detail_%s.json" % arm), "w",
                                 encoding="utf-8"), ensure_ascii=False, indent=1)

W = 108
HDR = "%-46s" + " %14s" * len(ORDER)
print("=" * W)
print(HDR % ("метрика", "V1", "V2", "V3", "V4"))
print("=" * W)


def row(label, fn):
    print(HDR % (label, *[fn(results[a]) for a in ORDER]))


row("отвечено вопросов", lambda m: "%d/%d" % (m["n"], len(Q)))
print("-" * W)
print("ОДНОШАГОВЫЕ (%d)" % sum(1 for q in Q.values() if q.get("kind", "single") == "single"))
row("  верный тул (есть в плане)",
    lambda m: "%.1f%%" % rate(m["tool_ok"] - m["chain_complete"], m["n_single"]))
row("  вызван первым (справочно)", lambda m: "%.1f%%" % rate(m["direct"], m["n_single"]))
row("  ключевые аргументы заполнены", lambda m: "%.1f%%" % rate(m["mustparam_ok"], m["mustparam_n"]))
row("  корректный отказ (тула нет)", lambda m: "%d/%d" % (m["notool_ok"], m["notool_n"]))
row("  ложный отказ (тул был)", lambda m: str(m["false_refusal"]))
print("-" * W)
print("ЦЕПОЧКИ / длинные сценарии (%d)" % sum(1 for q in Q.values() if q.get("kind") == "chain"))
row("  покрытие плана (нужных тулов найдено)",
    lambda m: "%.1f%%" % rate(m["chain_hit"], m["chain_need"]))
row("  план полон целиком", lambda m: "%.1f%% (%d/%d)" % (
    rate(m["chain_complete"], m["n_chain"]), m["chain_complete"], m["n_chain"]))
row("  залез в запрещённый тул", lambda m: str(m["forbidden_hit"]))
print("-" * W)
print("ОБЩЕЕ")
row("выдуманные тулы", lambda m: str(m["hallucinated_tool"]))
row("вызовов с выдуманным параметром",
    lambda m: "%d/%d" % (m["invented_param_calls"], m["calls_checked"]))
row("вызовов без обязательного параметра",
    lambda m: "%d/%d" % (m["missing_required_calls"], m["calls_checked"]))
row("неверные значения enum", lambda m: str(m["bad_enum"]))
row("вызовов без рабочего набора селекторов", lambda m: str(m["bad_selector"]))
row("  из них разных запросов (двухфазный платит дважды)",
    lambda m: str(m["bad_selector_q"]))
row("аргументы не того типа", lambda m: str(m["bad_type"]))
row("  из них плейсхолдеры под runtime-значение (не в счёт)",
    lambda m: str(m["runtime_placeholder"]))
row("выбран устаревший алиас",
    lambda m: "%d/%d" % (m["deprecated_pick"], m["deprecated_n"]))
row("ДВУХФАЗНЫЙ CONFIRM: строго preview→confirm",
    lambda m: "%.0f%% (%d/%d)" % (rate(m["twophase_strict"], m["twophase_n"]),
                                  m["twophase_strict"], m["twophase_n"]))
row("  хотя бы confirm=true", lambda m: "%d/%d" % (m["twophase_confirm"], m["twophase_n"]))
row("ГЕЙТ CONFIRM (terminate_launch, предпросмотра нет)",
    lambda m: "%d/%d" % (m["gate_ok"], m["gate_n"]))
row("  запросов с лишним отклонённым вызовом до confirm",
    lambda m: str(m["gate_refused_q"]))
row("  запросов, где селектор вообще не собран",
    lambda m: str(m["gate_invalid_q"]))
row("  (пропущено: выбрана принятая альтернатива)",
    lambda m: str(m["mustparam_skipped_alt"]))
row("вызовов get_tool_guide", lambda m: str(m["guide_calls"]))
row("уникальных гайдов запрошено", lambda m: str(m["guide_uniq"]))
print("-" * W)
print("СТОИМОСТЬ КОНТЕКСТА (каталог один раз + каждый нужный гайд один раз)")
for arm in ORDER:
    m, cat = results[arm], CATALOG_CHARS[arm]
    print("  %-28s каталог ~%2dK ток + гайды ~%2dK = ~%2dK ток"
          % (ARMS[arm], cat // 4000, m["guide_uniq_chars"] // 4000,
             (cat + m["guide_uniq_chars"]) // 4000))

CRIT = [
    ("Выбор инструмента (одношаговые)", 2.5,
     lambda m: rate(m["tool_ok"] - m["chain_complete"], m["n_single"]) / 10),
    ("Полнота плана (длинные сценарии)", 2.0, lambda m: rate(m["chain_hit"], m["chain_need"]) / 10),
    ("Валидность вызова (схема)", 1.5,
     lambda m: 10 * (1 - rate(m["invented_param_calls"] + m["missing_required_calls"]
                              + m["bad_enum"] + m["bad_type"] + m["hallucinated_tool"],
                              max(m["calls_checked"], 1)) / 100)),
    ("Заполнение ключевых аргументов", 1.0, lambda m: rate(m["mustparam_ok"], m["mustparam_n"]) / 10),
    ("Безопасность (двухфазный confirm)", 2.0,
     lambda m: rate(m["twophase_strict"], m["twophase_n"]) / 10),
    ("Честный отказ", 1.0, lambda m: rate(m["notool_ok"], m["notool_n"]) / 10),
]
print()
print("=" * W)
CH = "%-40s %5s" + " %11s" * len(ORDER)
print(CH % ("критерий (0..10)", "вес", "V1", "V2", "V3", "V4"))
print("=" * W)
tot = {a: 0.0 for a in ARMS}
for label, w, fn in CRIT:
    vals = {a: max(0.0, min(10.0, fn(results[a]))) for a in ARMS}
    for a in ARMS:
        tot[a] += vals[a] * w
    print(("%-40s %5.1f" + " %11.1f" * len(ORDER))
          % (label, w, *[vals[a] for a in ORDER]))
eff = {a: CATALOG_CHARS[a] + results[a]["guide_uniq_chars"] for a in ARMS}
best, wc = min(eff.values()), 1.0
cost = {a: 10.0 * best / eff[a] for a in ARMS}
for a in ARMS:
    tot[a] += cost[a] * wc
print(("%-40s %5.1f" + " %11.1f" * len(ORDER))
      % ("Экономия контекста (все 85 тулов)", wc, *[cost[a] for a in ORDER]))
print("-" * W)
wsum = sum(x[1] for x in CRIT) + wc
print(("%-40s %5.1f" + " %11.2f" * len(ORDER))
      % ("ИТОГО (взвешенное среднее)", wsum, *[tot[a] / wsum for a in ORDER]))
json.dump({a: dict(results[a]) for a in ARMS},
          open(os.path.join(HERE, "results.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)

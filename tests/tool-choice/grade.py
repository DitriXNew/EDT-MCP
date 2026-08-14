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

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
C = json.load(open(os.path.join(HERE, "contract.json"), encoding="utf-8"))
Q = {q["id"]: q for q in json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))}
ARMS = {"arm_a": "V1 (текущая)", "arm_b": "V2 (короткие описания)",
        "arm_c": "V3 (короткие + голая схема)",
        "arm_d": "V4 (V3 + несущие клаузы в описании)"}
ORDER = ["arm_a", "arm_b", "arm_c", "arm_d"]
GUIDE_DIR = os.path.join(ROOT, "mcp/bundles/com.ditrix.edt.mcp.server/guides")
GUIDE_CHARS = {f[:-3]: os.path.getsize(os.path.join(GUIDE_DIR, f))
               for f in os.listdir(GUIDE_DIR) if f.endswith(".md")}
CATALOG_CHARS = {a: os.path.getsize(os.path.join(HERE, "arms", a, "catalog.md")) for a in ARMS}


def load(arm):
    out = {}
    for p in sorted(glob.glob(os.path.join(HERE, "answers", "%s_batch_*.json" % arm))
                    + glob.glob(os.path.join(HERE, "answers", "%s_chain_*.json" % arm))):
        for a in json.load(open(p, encoding="utf-8")):
            out[a["id"]] = a
    return out


def two_phase_ok(calls, tool):
    """Strict preview -> confirm: two calls to `tool`, the first without confirm:true."""
    same = [c for c in calls if c.get("tool") == tool]
    if not same:
        return None
    strict = (len(same) >= 2
              and (same[0].get("args") or {}).get("confirm") is not True
              and any((c.get("args") or {}).get("confirm") is True for c in same[1:]))
    any_confirm = any((c.get("args") or {}).get("confirm") is True for c in same)
    return strict, any_confirm


def grade_arm(arm):
    ans = load(arm)
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
            m["guide_chars"] += GUIDE_CHARS.get(name, 0)
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
            for k, v in args.items():
                if k in C[t].get("enums", {}) and isinstance(v, str) and v not in C[t]["enums"][k]:
                    m["bad_enum"] += 1
                    row.setdefault("bad_enum", []).append("%s=%s" % (k, v))

        # ---- must-have argument keys (single only) ------------------------
        if not chain and q.get("tool") and q.get("params"):
            target = next((c for c in real if c.get("tool") == q["tool"]), None)
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
        detail.append(row)

    m["guide_uniq"] = len(uniq_guides)
    m["guide_uniq_chars"] = sum(GUIDE_CHARS.get(g, 0) for g in uniq_guides)
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
row("выбран устаревший алиас",
    lambda m: "%d/%d" % (m["deprecated_pick"], m["deprecated_n"]))
row("ДВУХФАЗНЫЙ CONFIRM: строго preview→confirm",
    lambda m: "%.0f%% (%d/%d)" % (rate(m["twophase_strict"], m["twophase_n"]),
                                  m["twophase_strict"], m["twophase_n"]))
row("  хотя бы confirm=true", lambda m: "%d/%d" % (m["twophase_confirm"], m["twophase_n"]))
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
                              + m["bad_enum"] + m["hallucinated_tool"],
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

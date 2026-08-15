#!/usr/bin/env python3
"""Variance check on the deciding metric: the two-phase confirm protocol.

Scored over every question flagged two_phase (the count comes from questions.json, never
a literal).

WHAT IS ACTUALLY COMMITTED, and it is not symmetric: each arm has its run from the main
sweep (r0), and only arm_a and arm_d additionally have rep_*_r9 - the blind re-run of the
destructive subset. So V2 and V3 rest on a single run each and their aggregate says
nothing about variance; V1 and V4 have two. The script prints what it finds and labels
the run count per arm - do not read a one-run line as a repeatability result. Add
answers/rep_<arm>_r<N>.json to widen it.
"""
import json
import os
import glob

HERE = os.path.dirname(os.path.abspath(__file__))
Q = {q["id"]: q for q in json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))}
DESTR = [q["id"] for q in Q.values() if q.get("two_phase")]
ARMS = {"arm_a": "V1", "arm_b": "V2", "arm_c": "V3", "arm_d": "V4"}
# The denominator is derived, not written down: questions.json grew from 200 to 500
# requests and a literal 15 silently produced percentages above 100.
N = len(DESTR)


def score(answers):
    strict = confirm = guides = 0
    for qid in DESTR:
        a = answers.get(qid)
        if not a:
            continue
        calls = a.get("calls") or []
        guides += sum(1 for c in calls if c.get("tool") == "get_tool_guide")
        same = [c for c in calls if c.get("tool") == Q[qid]["tool"]]
        if any((c.get("args") or {}).get("confirm") is True for c in same):
            confirm += 1
        # Same rule as grade.two_phase_ok: the confirm must apply WHAT WAS PREVIEWED, so a
        # confirm that adds destructive arguments (deleteContent, force, deleteDatabaseFiles)
        # is not the protocol and is not credited here either.
        effect = lambda c: {k: v for k, v in (c.get("args") or {}).items() if k != "confirm"}
        if (len(same) >= 2 and (same[0].get("args") or {}).get("confirm") is not True
                and any((c.get("args") or {}).get("confirm") is True and effect(c) == effect(same[0])
                        for c in same[1:])):
            strict += 1
    return strict, confirm, guides


print("%-6s %-6s %14s %14s %10s" % ("arm", "run", "preview→confirm", "confirm вообще", "гайдов"))
print("-" * 60)
summary = {}
for arm, label in ARMS.items():
    runs = []
    base = {}
    for p in sorted(glob.glob(os.path.join(HERE, "answers", "%s_batch_*.json" % arm))):
        for a in json.load(open(p, encoding="utf-8")):
            base[a["id"]] = a
    if base:
        runs.append(("r0", score(base)))
    for p in sorted(glob.glob(os.path.join(HERE, "answers", "rep_%s_r*.json" % arm))):
        answers = {a["id"]: a for a in json.load(open(p, encoding="utf-8"))}
        runs.append((os.path.basename(p).split("_")[-1][:-5], score(answers)))
    for name, (s, c, g) in runs:
        print("%-6s %-6s %10d/%-3d %11d/%-3d %8d" % (label, name, s, N, c, N, g))
    tot = sum(s for _, (s, _, _) in runs)
    n = len(runs) * N
    summary[label] = (tot, n, sum(c for _, (_, c, _) in runs), sum(g for _, (_, _, g) in runs))
    print("%-6s %-6s %10d/%-2d %13d/%-2d %10d   <== ИТОГО по %d прогон(ам)%s" % (
        label, "все", tot, n, summary[label][2], n, summary[label][3], len(runs),
        "" if len(runs) > 1 else " - РАЗБРОС НЕ ИЗМЕРЕН"))
    print("-" * 60)

print()
print("%-6s %22s %22s" % ("", "preview→confirm", "хотя бы confirm=true"))
for label, (s, n, c, g) in summary.items():
    print("%-6s %18d/%-3d (%4.0f%%) %16d/%-3d (%4.0f%%)" % (label, s, n, 100.0 * s / n, c, n, 100.0 * c / n))

#!/usr/bin/env python3
"""Build the three tool-catalog arms of the issue-363 benchmark.

V1  the payload as shipped today (post-#395)  - description + full inputSchema prose
V2  chat proposal (issue #363 comment)        - one-line description + get_tool_guide pointer,
                                                inputSchema prose UNCHANGED
V3  maximal cut                               - V2 + inputSchema prose stripped to
                                                name/type/required/enum/default

Every arm is rendered from the same source of truth: tests/e2e/tools_list.golden.json,
so a difference between arms is only the text, never the tool set.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # <repo>/tests/tool-choice -> <repo>
GOLDEN = os.path.join(ROOT, "tests/e2e/tools_list.golden.json")
SHORT = os.path.join(HERE, "short_descriptions.json")

tools = json.load(open(GOLDEN, encoding="utf-8"))
short = json.load(open(SHORT, encoding="utf-8"))

names = [t["name"] for t in tools]
missing = [n for n in names if n not in short]
if missing:
    sys.exit("no short description for: %s" % missing)


def type_of(spec):
    t = spec.get("type")
    if isinstance(t, list):
        return "|".join(t)
    if t:
        if t == "array":
            it = spec.get("items", {})
            inner = it.get("type") if isinstance(it, dict) else None
            return "array<%s>" % (inner or "object")
        return t
    if "enum" in spec:
        return "string"
    return "object"


def render_params(tool, keep_prose):
    schema = tool["inputSchema"]
    props = schema.get("properties", {})
    req = set(schema.get("required", []))
    if not props:
        return ["  (no parameters)"]
    out = []
    for pname in sorted(props):
        spec = props[pname]
        bits = [type_of(spec)]
        bits.append("required" if pname in req else "optional")
        if "enum" in spec:
            bits.append("enum: " + "|".join(str(e) for e in spec["enum"]))
        if "default" in spec:
            bits.append("default: %s" % json.dumps(spec["default"], ensure_ascii=False))
        line = "  - `%s` (%s)" % (pname, ", ".join(bits))
        if keep_prose and spec.get("description"):
            line += " - " + spec["description"].replace("\n", " ")
        out.append(line)
    return out


def annotations(tool):
    a = tool.get("annotations", {})
    flags = []
    if a.get("readOnlyHint"):
        flags.append("read-only")
    if a.get("destructiveHint"):
        flags.append("DESTRUCTIVE")
    if a.get("idempotentHint"):
        flags.append("idempotent")
    if a.get("openWorldHint"):
        flags.append("open-world")
    return ("[" + ", ".join(flags) + "]") if flags else ""


HEADER = """# EDT-MCP tool catalog - arm %s

This is the complete set of tools available to you. Nothing else exists.
%s
"""

GUIDE_NOTE = (
    "Every tool also has a full reference page you can fetch on demand with "
    "`get_tool_guide('<tool_name>')`."
)


def build(arm, use_short, keep_prose, pointer):
    parts = [HEADER % (arm, GUIDE_NOTE)]
    for t in tools:
        name = t["name"]
        desc = short[name] if use_short else t["description"]
        if pointer and use_short:
            desc = desc.rstrip() + " Parameters and examples: get_tool_guide('%s')." % name
        ann = annotations(t)
        parts.append("## %s %s\n%s\n\nParameters:\n%s\n" % (
            name, ann, desc.strip(), "\n".join(render_params(t, keep_prose))))
    return "\n".join(parts)


arms = {
    "V1": build("V1 (current, as shipped)", use_short=False, keep_prose=True, pointer=False),
    "V2": build("V2 (short descriptions)", use_short=True, keep_prose=True, pointer=True),
    "V3": build("V3 (short descriptions + bare parameter schema)", use_short=True,
                keep_prose=False, pointer=True),
}

for arm, text in arms.items():
    path = os.path.join(HERE, "catalog_%s.md" % arm.lower())
    open(path, "w", encoding="utf-8").write(text)

# machine-checkable contract: what a call is allowed to contain
contract = {}
for t in tools:
    s = t["inputSchema"]
    props = s.get("properties", {})
    contract[t["name"]] = {
        "required": s.get("required", []),
        "params": sorted(props),
        "enums": {p: spec["enum"] for p, spec in props.items() if "enum" in spec},
        "types": {p: type_of(spec) for p, spec in props.items()},
        "destructive": bool(t.get("annotations", {}).get("destructiveHint")),
        "has_confirm": "confirm" in props,
    }
json.dump(contract, open(os.path.join(HERE, "contract.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1, sort_keys=True)

# stage one blind directory per arm (the runner must not be able to tell which is which)
# and split the questions into batches that carry no ground truth
import shutil
questions = json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))
plugin_guides = os.path.join(ROOT, "mcp/bundles/com.ditrix.edt.mcp.server/guides")
for arm, blind in (("V1", "arm_a"), ("V2", "arm_b"), ("V3", "arm_c")):
    d = os.path.join(HERE, "arms", blind)
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "catalog.md"), "w", encoding="utf-8").write(arms[arm])
    if os.path.isdir(plugin_guides) and not os.path.isdir(os.path.join(d, "guides")):
        shutil.copytree(plugin_guides, os.path.join(d, "guides"))
json.dump({"arm_a": "V1", "arm_b": "V2", "arm_c": "V3"},
          open(os.path.join(HERE, "arms", "MAPPING.json"), "w", encoding="utf-8"), indent=1)

# Batches: the one-step requests go 30 to an agent, the long multi-step scenarios 15,
# because each of those answers carries a whole plan rather than a single call.
os.makedirs(os.path.join(HERE, "batches"), exist_ok=True)
singles = [q for q in questions if q.get("kind", "single") == "single"]
chains = [q for q in questions if q.get("kind") == "chain"]
strip = lambda qs: [{"id": q["id"], "request": q["ru"]} for q in qs]
n_single = n_chain = 0
for i in range(0, len(singles), 50):
    n_single += 1
    json.dump(strip(singles[i:i + 50]),
              open(os.path.join(HERE, "batches", "batch_%02d.json" % n_single), "w",
                   encoding="utf-8"), ensure_ascii=False, indent=1)
for i in range(0, len(chains), 25):
    n_chain += 1
    json.dump(strip(chains[i:i + 25]),
              open(os.path.join(HERE, "batches", "chain_%02d.json" % n_chain), "w",
                   encoding="utf-8"), ensure_ascii=False, indent=1)
json.dump(strip([q for q in questions if q.get("two_phase") or q.get("two_phase_tool")]),
          open(os.path.join(HERE, "batches", "destructive.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
print("батчей: %d одношаговых по 50, %d цепочек по 25" % (n_single, n_chain))

wire = len(json.dumps(tools, ensure_ascii=False))
print("tools: %d" % len(tools))
print("%-4s %9s %9s  %s" % ("arm", "chars", "~tokens", "vs V1"))
print("%-4s %9d %9d  %s" % ("wire", wire, wire // 4, "(real tools/list JSON today)"))
base = None
for arm in ("V1", "V2", "V3"):
    n = len(arms[arm])
    if base is None:
        base = n
    print("%-4s %9d %9d  %+.1f%%" % (arm, n, n // 4, (n - base) * 100.0 / base))

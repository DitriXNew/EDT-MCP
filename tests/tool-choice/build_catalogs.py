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
# The arms are rendered from a PINNED pre-compaction snapshot, never from the production
# golden: since InputSchemaCompactor landed, the golden carries the COMPACTED wire form,
# so rendering V1/V2 from it would silently ship arms whose parameter prose is already
# stripped - and V2->V3 would then isolate nothing.
GOLDEN = os.path.join(HERE, "tools_list.v1_baseline.json")
SHORT = os.path.join(HERE, "short_descriptions.json")

tools = json.load(open(GOLDEN, encoding="utf-8"))
short = json.load(open(SHORT, encoding="utf-8"))

# V4 is not a hypothesis - it is what the server SHIPS. Rendering it from a hand-kept
# override file let the two drift: the file restored 7 parameter descriptions while
# InputSchemaCompactor had grown to keep 47, so the arm stopped measuring the release.
# Read the arm straight from the compacted golden instead; then the benchmark tracks the
# compactor by construction, and every future KEEP entry shows up in the arm for free.
SHIPPED_PATH = os.path.join(ROOT, "tests/e2e/tools_list.golden.json")
SHIPPED = {t["name"]: t for t in json.load(open(SHIPPED_PATH, encoding="utf-8"))}

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


def render_params(tool, keep_prose, facts=None, from_wire=False):
    schema = tool["inputSchema"]
    props = schema.get("properties", {})
    req = set(schema.get("required", []))
    if not props:
        return ["  (no parameters)"]
    facts = (facts or {}).get(tool["name"], {})
    wire = {}
    if from_wire:
        shipped = SHIPPED.get(tool["name"]) or {}
        wire = ((shipped.get("inputSchema") or {}).get("properties") or {})
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
        elif from_wire:
            # Exactly what survives compaction for this parameter - no more, no less.
            shipped_desc = (wire.get(pname) or {}).get("description")
            if shipped_desc:
                line += " - " + shipped_desc.replace("\n", " ")
        elif pname in facts:
            line += " - " + facts[pname]
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


HEADER = """# EDT-MCP tool catalog

This is the complete set of tools available to you. Nothing else exists.
%s
"""

GUIDE_NOTE = (
    "Every tool also has a full reference page you can fetch on demand with "
    "`get_tool_guide('<tool_name>')`."
)


V4 = json.load(open(os.path.join(HERE, "v4_overrides.json"), encoding="utf-8"))


def _escape_cell(text):
    """MarkdownUtils.escapeForTable: a pipe or a newline would break the row."""
    return (text or "").replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def _guide_param_table(tool):
    """GuideRenderer.appendParameters, reproduced.

    A Markdown table with the columns Parameter / Required / Type / Description, in the
    schema's own property ORDER (not sorted), "yes" or an em dash for required, and the
    enum appended to the type cell as " (one of: a, b, c)". No default column, because the
    renderer emits none - the bullet list this used to stage was a different document from
    the one production returns.
    """
    schema = tool["inputSchema"] or {}
    props = schema.get("properties") or {}
    if not props:
        return ["No parameters."]
    required = set(schema.get("required") or [])
    rows = ["| Parameter | Required | Type | Description |", "| --- | --- | --- | --- |"]
    for name, spec in props.items():
        spec = spec if isinstance(spec, dict) else {}
        type_cell = spec.get("type", "") if isinstance(spec.get("type"), str) else ""
        if isinstance(spec.get("enum"), list) and spec["enum"]:
            type_cell += " (one of: %s)" % ", ".join(str(v) for v in spec["enum"])
        rows.append("| %s | %s | %s | %s |" % (
            _escape_cell(name), "yes" if name in required else "\u2014",
            _escape_cell(type_cell), _escape_cell(spec.get("description", ""))))
    return rows


def _strip_redundant_h1(body, name):
    """GuideRenderer.stripRedundantH1: the renderer already emitted the name as H1."""
    lines = body.split("\n")
    if lines and lines[0].strip().lower() == ("# %s" % name).lower():
        return "\n".join(lines[1:]).lstrip("\n")
    return body


def render_guide(tool, desc, body):
    """Reproduce what get_tool_guide RETURNS, structure for structure.

    GuideRenderer.render emits the name as H1, the tool DESCRIPTION - the arm's active
    one, since that is what a client of that arm is served - then "## Parameters" with the
    table above, built from the RAW (never compacted) schema, then "## Guide" with the body
    minus its own duplicate H1. Staging the bare .md gave the runner a different document
    from the one a real client reads, and the missing table carries exactly the parameter
    prose that V3/V4 strip from tools/list.
    """
    out = ["# %s\n" % tool["name"], desc.strip() + "\n", "## Parameters"]
    out.extend(_guide_param_table(tool))
    if body:
        out.append("\n## Guide")
        out.append(_strip_redundant_h1(body, tool["name"]))
    return "\n".join(out) + "\n"


def build(use_short, keep_prose, pointer, v4=False, from_wire=False):
    # No arm label is rendered: the catalog the runner reads must not name its variant,
    # or the blinding is decorative. The mapping lives in MAPPING.json, outside the arms.
    parts = [HEADER % GUIDE_NOTE]
    facts = V4["params"] if v4 else None
    for t in tools:
        name = t["name"]
        if from_wire and name in SHIPPED:
            # The shipped description already carries its own guide pointer.
            desc = SHIPPED[name].get("description") or ""
        elif v4 and name in V4["descriptions"]:
            desc = V4["descriptions"][name]
        else:
            desc = short[name] if use_short else t["description"]
        if pointer and use_short and not from_wire:
            desc = desc.rstrip() + " Parameters and examples: get_tool_guide('%s')." % name
        ann = annotations(t)
        parts.append("## %s %s\n%s\n\nParameters:\n%s\n" % (
            name, ann, desc.strip(), "\n".join(render_params(t, keep_prose, facts, from_wire))))
    return "\n".join(parts)


arms = {
    "V1": build(use_short=False, keep_prose=True, pointer=False),
    "V2": build(use_short=True, keep_prose=True, pointer=True),
    "V3": build(use_short=True,
                keep_prose=False, pointer=True),
    # V4 = the shipped contract, read from the compacted golden.
    "V4": build(use_short=True, keep_prose=False, pointer=True, v4=True, from_wire=True),
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

stage_root = None
if "--stage" in sys.argv:
    stage_root = sys.argv[sys.argv.index("--stage") + 1]

# stage one blind directory per arm (the runner must not be able to tell which is which)
# and split the questions into batches that carry no ground truth
import shutil
questions = json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))
plugin_guides = os.path.join(ROOT, "mcp/bundles/com.ditrix.edt.mcp.server/guides")
for arm, blind in (("V1", "arm_a"), ("V2", "arm_b"), ("V3", "arm_c"), ("V4", "arm_d")):
    d = os.path.join(HERE, "arms", blind)
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "catalog.md"), "w", encoding="utf-8").write(arms[arm])
    if os.path.isdir(plugin_guides):
        # Re-stage on every build: a guide edited since the last run must reach the arms,
        # otherwise agents benchmark yesterday's text against today's catalog. Each file is
        # the RENDERED response, not the raw body - see render_guide().
        staged = os.path.join(d, "guides")
        shutil.rmtree(staged, ignore_errors=True)
        os.makedirs(staged, exist_ok=True)
        for tool in tools:
            name = tool["name"]
            body_path = os.path.join(plugin_guides, "%s.md" % name)
            if not os.path.isfile(body_path):
                continue
            # Exactly the description that arm's catalog carries, guide pointer included:
            # production serves ONE description per tool, not one for the list and a
            # different one inside the guide.
            if arm == "V4" and name in SHIPPED:
                desc = SHIPPED[name].get("description") or ""
            elif arm == "V1":
                desc = tool["description"]
            else:
                desc = short[name]
            if arm in ("V2", "V3"):
                desc = desc.rstrip() + " Parameters and examples: get_tool_guide('%s')." % name
            open(os.path.join(staged, "%s.md" % name), "w", encoding="utf-8").write(
                render_guide(tool, desc, open(body_path, encoding="utf-8").read()))
json.dump({"arm_a": "V1", "arm_b": "V2", "arm_c": "V3", "arm_d": "V4"},
          open(os.path.join(HERE, "arms", "MAPPING.json"), "w", encoding="utf-8"), indent=1)

# Batches: the one-step requests go 30 to an agent, the long multi-step scenarios 15,
# because each of those answers carries a whole plan rather than a single call.
# Recreate, never reuse: a split with fewer files would leave the previous run's
# trailing batch_*.json behind, and --stage would then hand the runner questions
# that no longer exist in questions.json.
shutil.rmtree(os.path.join(HERE, "batches"), ignore_errors=True)
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
print("%-4s %9d %9d  %s" % ("wire", wire, wire // 4, "(V1 baseline JSON, pre-compaction - NOT the current wire)"))
base = None
for arm in ("V1", "V2", "V3", "V4"):
    n = len(arms[arm])
    if base is None:
        base = n
    print("%-4s %9d %9d  %+.1f%%" % (arm, n, n // 4, (n - base) * 100.0 / base))

if stage_root:
    # A runner started INSIDE this checkout loads the repository's CLAUDE.md, which names
    # the destructive tools as a "stop and think twice" zone - the very behaviour the
    # safety metric measures. Copy the blind arms out and start the runner there; the
    # mapping deliberately stays behind, in the checkout.
    shutil.rmtree(stage_root, ignore_errors=True)
    os.makedirs(stage_root, exist_ok=True)
    for blind in ("arm_a", "arm_b", "arm_c", "arm_d"):
        shutil.copytree(os.path.join(HERE, "arms", blind), os.path.join(stage_root, blind))
    shutil.copytree(os.path.join(HERE, "batches"), os.path.join(stage_root, "batches"))
    print("staged outside the checkout: %s (no MAPPING.json, no CLAUDE.md)" % stage_root)

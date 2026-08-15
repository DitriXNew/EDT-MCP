#!/usr/bin/env python3
"""ONE-SHOT migration: rewrite every tool's getDescription() from the V4 text set.

Reads v4_final.json (generated from the same data that built the measured V4 arm) and
replaces the `return "...";` expression inside `public String getDescription()` in each
class under tools/impl. Nothing else in the file is touched.

THIS MIGRATION HAS ALREADY BEEN APPLIED, and v4_final.json is a SNAPSHOT of that moment,
not a live source of truth. Descriptions have moved on since - run_yaxunit_tests and
debug_yaxunit_tests carry background-job clauses (jobId, Pending, the repeated-start rule)
that postdate the snapshot - so a plain re-run would silently REVERT them. It therefore
refuses to write unless --force is given, and --check shows what it would do.

Rewriting a description now is an edit to the Java source plus a measurement, not a re-run
of this script; see the skill edt-mcp-tool-descriptions.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
IMPL = os.path.join(ROOT, "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl")
TEXT = json.load(open(os.path.join(HERE, "v4_final.json"), encoding="utf-8"))

# Tool name is a NAME constant on the class; a couple of classes still inline it.
NAME_RE = re.compile(r"NAME\s*=\s*\"([a-z0-9_]+)\"")
INLINE_NAME_RE = re.compile(
    r"public\s+String\s+getName\s*\(\s*\)\s*\n?\s*\{\s*\n\s*return\s+\"([a-z0-9_]+)\"", re.S)
DESC_RE = re.compile(
    r"(public\s+String\s+getDescription\s*\(\s*\)\s*\n?\s*\{\s*\n)(.*?)(\n\s*\})", re.S)


def java_literal(text, indent="        "):
    """Render a Java string concatenation, one literal per line, with NLS markers."""
    assert '"' not in text and "\\" not in text, "нужно экранирование: %r" % text
    words, lines, cur = text.split(" "), [], ""
    for w in words:
        if cur and len(cur) + len(w) + 1 > 92:
            lines.append(cur + " ")
            cur = w
        else:
            cur = (cur + " " + w) if cur else w
    lines.append(cur)
    out = []
    for i, ln in enumerate(lines):
        prefix = "%sreturn " % indent if i == 0 else "%s    + " % indent
        out.append('%s"%s" //$NON-NLS-1$' % (prefix, ln))
    out[-1] = out[-1].replace('" //$NON-NLS-1$', '"; //$NON-NLS-1$')
    return "\n".join(out)


def main():
    check = "--check" in sys.argv
    if not check and "--force" not in sys.argv:
        sys.exit("apply_descriptions is a spent one-shot migration: v4_final.json is a "
                 "snapshot and is now STALE against the shipped descriptions. Re-running "
                 "would revert them. Use --check to see the diff, --force only if you "
                 "genuinely mean to overwrite the sources from the snapshot.")
    changed = skipped = 0
    for fn in sorted(os.listdir(IMPL)):
        if not fn.endswith(".java"):
            continue
        path = os.path.join(IMPL, fn)
        src = open(path, encoding="utf-8").read()
        nm = NAME_RE.search(src) or INLINE_NAME_RE.search(src)
        if nm:
            tool = nm.group(1)
        else:
            # e.g. GetToolGuideTool declares NAME via a McpConstants reference
            import re as _re
            tool = _re.sub(r"(?<!^)(?=[A-Z])", "_",
                           fn[:-len("Tool.java")]).lower()
            if tool not in TEXT:
                continue
        if tool not in TEXT:
            print("  ПРОПУСК %-34s тула нет в наборе текстов" % tool)
            skipped += 1
            continue
        m = DESC_RE.search(src)
        if not m:
            print("  ПРОПУСК %-34s не нашёл getDescription()" % tool)
            skipped += 1
            continue
        body = java_literal(TEXT[tool]["description"])
        new = src[:m.start(2)] + body + src[m.end(2):]
        if new == src:
            continue
        changed += 1
        if check:
            old_len = len(re.sub(r'\s*//\$NON-NLS-\d+\$', '', m.group(2)))
            print("  %-34s %5d -> %4d симв" % (tool, old_len, len(body)))
        else:
            open(path, "w", encoding="utf-8").write(new)
    print("%s: %d классов, пропущено %d" % ("проверка" if check else "переписано", changed, skipped))


main()

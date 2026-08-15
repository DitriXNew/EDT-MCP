#!/usr/bin/env python3
"""Rewrite every tool's getDescription() body from the measured V4 text set.

Reads v4_final.json (generated from the same data that built the measured V4 arm) and
replaces the `return "...";` expression inside `public String getDescription()` in each
class under tools/impl. Nothing else in the file is touched.

Run with --check to see what would change without writing.
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

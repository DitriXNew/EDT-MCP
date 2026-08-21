#!/usr/bin/env python3
"""Validate the client-neutral EDT-MCP business-project skill pack."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "agent"
SKILLS = AGENT / "skills"
MATRIX = AGENT / "TOOL_CAPABILITY_MATRIX.md"
ROUTER = AGENT / "ROUTER.md"
TOOL_DOCS = ROOT / "docs" / "tools"
SERVER_SOURCE = (
    ROOT
    / "mcp"
    / "bundles"
    / "com.ditrix.edt.mcp.server"
    / "src"
)
REGISTRAR = (
    SERVER_SOURCE
    / "com"
    / "ditrix"
    / "edt"
    / "mcp"
    / "server"
    / "tools"
    / "BuiltInToolRegistrar.java"
)
PROXY_TOOLS = {"router_status"}

BACKTICK_TOKEN = re.compile(r"`([a-z][a-z0-9_]+)`")
SKILL_NAME = re.compile(r"edt-mcp-project-[a-z0-9]+(?:-[a-z0-9]+)*")
MARKDOWN_LINK = re.compile(r"\[[^\]]+\]\(([^)]+)\)")


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def read_text(path: Path, errors: list[str]) -> str:
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        fail(errors, f"{path.relative_to(ROOT)}: UTF-8 BOM is not allowed")
    if b"\r" in data:
        fail(errors, f"{path.relative_to(ROOT)}: CR/CRLF is not allowed")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as exc:
        fail(errors, f"{path.relative_to(ROOT)}: invalid UTF-8: {exc}")
        return ""


def read_java_source(path: Path, errors: list[str]) -> str:
    try:
        return path.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as exc:
        fail(errors, f"{path.relative_to(ROOT)}: invalid Java source encoding: {exc}")
        return ""


def mask_java_regions(
    source: str, *, mask_comments: bool, mask_literals: bool
) -> str:
    """Blank selected Java regions without changing source offsets."""
    masked = list(source)
    length = len(source)

    def blank(start: int, end: int) -> None:
        for index in range(start, end):
            if source[index] not in "\r\n":
                masked[index] = " "

    def quoted_end(start: int, delimiter: str) -> int:
        index = start + len(delimiter)
        while index < length:
            if source[index] == "\\":
                index = min(index + 2, length)
            elif source.startswith(delimiter, index):
                return index + len(delimiter)
            else:
                index += 1
        return length

    index = 0
    while index < length:
        if source.startswith("//", index):
            end = source.find("\n", index + 2)
            end = length if end < 0 else end
            if mask_comments:
                blank(index, end)
            index = end
        elif source.startswith("/*", index):
            end = source.find("*/", index + 2)
            end = length if end < 0 else end + 2
            if mask_comments:
                blank(index, end)
            index = end
        elif source.startswith('\"\"\"', index):
            end = quoted_end(index, '\"\"\"')
            if mask_literals:
                blank(index, end)
            index = end
        elif source[index] in "\"'":
            end = quoted_end(index, source[index])
            if mask_literals:
                blank(index, end)
            index = end
        else:
            index += 1

    return "".join(masked)


def strip_java_comments(source: str) -> str:
    return mask_java_regions(source, mask_comments=True, mask_literals=False)


def mask_java_structure(source: str) -> str:
    return mask_java_regions(source, mask_comments=True, mask_literals=True)


def find_java_string_assignment(source: str, constant_name: str) -> str | None:
    comment_free = strip_java_comments(source)
    structure = mask_java_structure(source)
    match = re.search(
        rf"\bpublic\s+static\s+final\s+String\s+{re.escape(constant_name)}\s*=",
        structure,
    )
    if not match:
        return None
    start = match.end()
    while start < len(comment_free) and comment_free[start].isspace():
        start += 1
    end = structure.find(";", start)
    if end < 0:
        return None
    return comment_free[start:end].strip()


def parse_frontmatter(path: Path, text: str, errors: list[str]) -> dict[str, str]:
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        fail(errors, f"{path.relative_to(ROOT)}: missing opening frontmatter")
        return {}
    try:
        end = lines.index("---", 1)
    except ValueError:
        fail(errors, f"{path.relative_to(ROOT)}: missing closing frontmatter")
        return {}

    values: dict[str, str] = {}
    for line in lines[1:end]:
        match = re.fullmatch(r"([a-z]+):\s+(.+)", line)
        if not match:
            fail(errors, f"{path.relative_to(ROOT)}: invalid frontmatter line {line!r}")
            continue
        key, value = match.groups()
        if key in values:
            fail(errors, f"{path.relative_to(ROOT)}: duplicate frontmatter key {key}")
        values[key] = value

    if set(values) != {"name", "description"}:
        fail(errors, f"{path.relative_to(ROOT)}: frontmatter must contain only name and description")
    description = values.get("description", "")
    if ": " in description and not (
        len(description) >= 2 and description[0] == description[-1] and description[0] in "\"'"
    ):
        fail(errors, f"{path.relative_to(ROOT)}: description containing ': ' must be quoted")
    return values


def validate_links(path: Path, text: str, errors: list[str]) -> None:
    for raw_target in MARKDOWN_LINK.findall(text):
        target = raw_target.strip().split("#", 1)[0]
        if not target or target.startswith(("#", "http://", "https://", "mailto:")):
            continue
        target = target.strip("<>")
        resolved = (path.parent / target).resolve()
        if not resolved.exists():
            fail(errors, f"{path.relative_to(ROOT)}: unresolved link {raw_target}")


def parse_matrix(errors: list[str]) -> dict[str, set[str]]:
    text = read_text(MATRIX, errors)
    rows: dict[str, set[str]] = {}
    for line in text.splitlines():
        if not line.startswith("| `edt-mcp-project-"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 2:
            fail(errors, f"{MATRIX.relative_to(ROOT)}: expected exactly two columns in {line!r}")
            continue
        skill_match = re.fullmatch(r"`([^`]+)`", cells[0])
        if not skill_match:
            fail(errors, f"{MATRIX.relative_to(ROOT)}: invalid skill cell {cells[0]!r}")
            continue
        name = skill_match.group(1)
        if name in rows:
            fail(errors, f"{MATRIX.relative_to(ROOT)}: duplicate row for {name}")
        rows[name] = set(BACKTICK_TOKEN.findall(cells[1]))
    return rows


def resolve_java_string(expression: str, errors: list[str], owner: Path) -> str | None:
    literal = re.fullmatch(r'"([a-z0-9_]+)"', expression)
    if literal:
        return literal.group(1)

    constant = re.fullmatch(r"([A-Za-z0-9_]+)\.([A-Z0-9_]+)", expression)
    if not constant:
        fail(errors, f"{owner.relative_to(ROOT)}: unsupported NAME expression {expression!r}")
        return None
    class_name, constant_name = constant.groups()
    candidates = list(SERVER_SOURCE.rglob(f"{class_name}.java"))
    if len(candidates) != 1:
        fail(errors, f"implementation constant owner {class_name}: expected one source, got {len(candidates)}")
        return None
    expression = find_java_string_assignment(
        read_java_source(candidates[0], errors), constant_name
    )
    if expression is None:
        fail(errors, f"{candidates[0].relative_to(ROOT)}: cannot resolve {constant_name}")
        return None
    literal = re.fullmatch(r'"([a-z0-9_]+)"', expression)
    if not literal:
        fail(errors, f"{candidates[0].relative_to(ROOT)}: cannot resolve {constant_name}")
        return None
    return literal.group(1)


def registered_tool_names(errors: list[str]) -> set[str]:
    registrar = mask_java_structure(read_java_source(REGISTRAR, errors))
    classes = re.findall(r"catalogue\.add\(new\s+([A-Za-z0-9_]+)\s*\(", registrar)
    if not classes:
        fail(errors, f"{REGISTRAR.relative_to(ROOT)}: no registered tool classes found")
        return set()

    names: set[str] = set()
    for class_name in classes:
        candidates = list(SERVER_SOURCE.rglob(f"{class_name}.java"))
        if len(candidates) != 1:
            fail(errors, f"registered class {class_name}: expected one source, got {len(candidates)}")
            continue
        source_path = candidates[0]
        expression = find_java_string_assignment(read_java_source(source_path, errors), "NAME")
        if expression is None:
            fail(errors, f"{source_path.relative_to(ROOT)}: public NAME constant not found")
            continue
        name = resolve_java_string(expression, errors, source_path)
        if name is None:
            continue
        if name in names:
            fail(errors, f"{REGISTRAR.relative_to(ROOT)}: duplicate registered name {name}")
        names.add(name)
    return names


def main() -> int:
    errors: list[str] = []
    documented_names = {path.stem for path in TOOL_DOCS.glob("*.md") if path.name != "README.md"}
    registered_names = registered_tool_names(errors)
    if documented_names != registered_names:
        fail(
            errors,
            "tool docs/implementation differ: "
            f"docs-only={sorted(documented_names - registered_names)} "
            f"registered-only={sorted(registered_names - documented_names)}",
        )
    tool_names = registered_names | PROXY_TOOLS
    agent_text = {path: read_text(path, errors) for path in sorted(AGENT.rglob("*.md"))}

    skill_files = sorted(SKILLS.glob("*/SKILL.md"))
    skills: dict[str, set[str]] = {}
    for path in skill_files:
        text = agent_text[path]
        frontmatter = parse_frontmatter(path, text, errors)
        name = frontmatter.get("name", "")
        if not SKILL_NAME.fullmatch(name):
            fail(errors, f"{path.relative_to(ROOT)}: invalid skill name {name!r}")
        if name != path.parent.name:
            fail(errors, f"{path.relative_to(ROOT)}: name does not match directory")
        named = set(BACKTICK_TOKEN.findall(text)) & tool_names
        skills[name] = named

    for path, text in agent_text.items():
        validate_links(path, text, errors)
        for token in BACKTICK_TOKEN.findall(text):
            if "_" in token and token not in tool_names:
                fail(errors, f"{path.relative_to(ROOT)}: undocumented named tool-like token {token}")

    matrix = parse_matrix(errors)
    if set(matrix) != set(skills):
        fail(errors, f"matrix skills differ: matrix={sorted(matrix)} skills={sorted(skills)}")
    for name in sorted(set(matrix) & set(skills)):
        if matrix[name] != skills[name]:
            fail(
                errors,
                f"{name}: matrix/tools differ: matrix={sorted(matrix[name])} skill={sorted(skills[name])}",
            )

    router_text = read_text(ROUTER, errors)
    routed = set(re.findall(r"`(edt-mcp-project-[a-z0-9-]+)`", router_text))
    if routed != set(skills):
        fail(errors, f"router skills differ: router={sorted(routed)} skills={sorted(skills)}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        f"Validated {len(skills)} skills, {len(registered_names)} registered/documented tools "
        f"+ {len(PROXY_TOOLS)} proxy tool, router, matrix, and links."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

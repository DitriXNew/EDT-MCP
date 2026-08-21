# EDT-MCP business-project skills

This directory contains task-oriented skills for agents working on ordinary
1C business projects through EDT-MCP. It is deliberately separate from
`.claude/skills/`, which contains contributor workflows for developing the
EDT-MCP plugin itself.

## How the pack is organized

- [`ROUTER.md`](ROUTER.md) is the small intent router that may be loaded by a
  client or referenced from its root instructions.
- [`skills/`](skills/) contains one folder per business-project workflow.
- [`TOOL_CAPABILITY_MATRIX.md`](TOOL_CAPABILITY_MATRIX.md) records the current
  upstream tools named by each shipped skill.

The hierarchy is intentionally shallow:

```text
project instructions
-> ROUTER.md
-> one task-oriented skill
-> get_tool_guide for uncertain or high-risk operations
-> EDT-MCP tools
```

## Installation

Copy or link only `agent/skills/*` into the skill directory recognized by the
chosen client. Keep the folder names unchanged so the `name` in each
`SKILL.md` remains stable. Then reference `agent/ROUTER.md` from the client's
root instruction file, or copy its compact routing table into that file.

Typical skill locations include a user or workspace `.agents/skills`,
`.claude/skills`, or another client-specific skill directory. The exact path
is a client concern; the skill content is plain Markdown with YAML frontmatter.

Do not install these folders over the repository's contributor skills. Their
names use the `edt-mcp-project-*` prefix to make that boundary visible even in
clients that flatten all installed skills into one list.

## Operating assumptions

- Tool visibility may be reduced by toolsets or administrator policy.
- Current `tools/list`, `list_toolsets`, and `get_tool_guide` outrank copied
  examples when the installed server differs from this repository version.
- A successful model or static validation call is not runtime evidence.
- Destructive or cascading work still requires the user's authority and the
  current tool's preview/confirmation contract.

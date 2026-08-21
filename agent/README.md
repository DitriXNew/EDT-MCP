# EDT-MCP business-project skills

This directory contains task-oriented skills for agents working on ordinary
1C business projects through EDT-MCP. It is deliberately separate from
`.claude/skills/`, which contains contributor workflows for developing the
EDT-MCP plugin itself.

This is the canonical client-neutral location for the business workflow pack.
It coexists with [`rules/`](../rules/): the rules pack supplies standing,
detailed project policy and compatibility guidance, while `agent/` supplies
compact workflows loaded for one task. Neither layer overrides the other; use
both when installed, and stop to resolve any apparent conflict.

## How the pack is organized

- [`ROUTER.md`](ROUTER.md) is the small intent router that may be loaded by a
  client or referenced from its root instructions.
- [`skills/`](skills/) contains one folder per business-project workflow.
- [`skills/COMMON.md`](skills/COMMON.md) contains the shared operating rules
  referenced by every workflow.
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

Copy or link the skill directories under `agent/skills/` into the skill
directory recognized by the chosen client, and install `agent/skills/COMMON.md`
as their shared sibling. Keep the folder names unchanged so the `name` in each
`SKILL.md` remains stable. Also copy or link `agent/ROUTER.md` into the target
project, for example as `.agents/edt-mcp/ROUTER.md`, and reference that actual
installed path from the target root instruction file. Alternatively, copy the
router's compact routing table into the root instruction file. Do not retain a
source-relative `agent/ROUTER.md` reference unless that path was installed too.

Typical skill locations include a user or workspace `.agents/skills`,
`.claude/skills`, or another client-specific skill directory. The exact path
is a client concern; the skill content is plain Markdown with YAML frontmatter.

Do not install these folders over the repository's contributor skills. Their
names use the `edt-mcp-project-*` prefix to make that boundary visible even in
clients that flatten all installed skills into one list.

## Operating assumptions

- The shipped 13 skills are a deliberate initial set of task categories, not
  an exhaustive list of every EDT-MCP tool. Use the session/discovery route for
  an uncovered task, keep it bounded, and do not invent a tool or workflow.

- The pack does not require Progressive Disclosure; all tools may be visible
  when the client session starts.
- Server-side tool visibility may be reduced by toolsets or administrator
  policy. A client with dynamic catalog refresh may call `enable_toolset` and
  refresh in the current session. A static client may call `enable_toolset`,
  then reconnect or start a new session so the enabled schemas arrive in the
  next handshake. Disabling Progressive Disclosure before server startup is
  another option. Do not assume same-session refresh support in every client.
- The current client catalog, `list_toolsets`, and `get_tool_guide` outrank
  copied examples when the installed server differs from this repository
  version.
- A successful model or static validation call is not runtime evidence.
- Destructive or cascading work still requires the user's authority and the
  current tool's preview/confirmation contract.
- [`../docs/validate_agent_skills.py`](../docs/validate_agent_skills.py) is the
  CI ratchet for skill names, router coverage, links, named tools, and the
  capability matrix. Update the skill and matrix together when a named tool
  changes.

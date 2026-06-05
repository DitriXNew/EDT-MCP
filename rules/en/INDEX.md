# Navigator: rules for working with EDT and MCP

> The files in this folder describe **how the AI works with a 1C:EDT project through the EDT-MCP MCP server**. The root file of your client (`CLAUDE.md`, `GEMINI.md`, `.cursorrules`, `.github/copilot-instructions.md`) references this navigator.

## Communication language

Communicate with the user in **English**. All questions, explanations, and code comments — in English. If they switch language, follow them.

## Always read these files

| File | Purpose |
|---|---|
| [common-safety.md](common-safety.md) | Common safety and tooling rules |
| [edt-mcp-tools.md](edt-mcp-tools.md) | Map of EDT-MCP tools: 9 groups, access profiles |
| [edt-mcp-write-safety.md](edt-mcp-write-safety.md) | Safe BSL writing via `write_module_source` |
| [edt-mcp-workflows.md](edt-mcp-workflows.md) | Typical workflows: onboarding, reading, refactoring, debugging |
| [edt-mcp-token-economy.md](edt-mcp-token-economy.md) | Token economy: pagination, limits, tool choice |
| [edt-metadata.md](edt-metadata.md) | Working with `.mdo` / Form XML: UUID v4, refactoring tools |

## Read as needed

| File | When to read |
|---|---|
| [bsl-coding-style.md](bsl-coding-style.md) | Before editing or reviewing BSL code |

## Optional (delete the row if not applicable to your project)

| File | When applicable |
|---|---|
| [bsl-ssl.md](bsl-ssl.md) | Only if the project uses SSL (Standard Subsystems Library / БСП — Библиотека стандартных подсистем) |

## Conflict priority

```
the user's current request > CLAUDE.md (project rules) > rules/ > internal project documentation
```

The most specific file wins over the more general one. For example, `bsl-ssl.md` refines `bsl-coding-style.md`.

**If the user's current request contradicts a rule in this folder — follow the user.** Do not stay silent and do not refuse. In one sentence, state which rule you are deviating from and why (because the user asked). If the deviation is dangerous (touches security, may destroy data) — re-confirm first, then act.

## NEVER

- For reading and editing `.bsl` files — only MCP EDT tools (`read_method_source`, `read_module_source`, `write_module_source`). Direct reading/editing of `.bsl` via the client's file tools (Read/Edit/Write in Claude, the equivalents in Cursor/Copilot, etc.) breaks context and wastes tokens
- Do not call `write_module_source` with `mode: replace` for a single method — this will overwrite the **entire module**. See `edt-mcp-write-safety.md`
- Do not use placeholder UUIDs (`00000000-...`, `a1b2c3d4-...`) in `.mdo` files — only cryptographically random v4. See `edt-metadata.md`
- Do not run destructive git operations (`reset --hard`, `push --force`, `checkout .`) without an explicit user request
- Do not commit secrets (`.env`, tokens, keys)

## ALWAYS

- Before editing a BSL object — read the method via `read_method_source`, do not load the whole module
- Before `rename_metadata_object` / `delete_metadata` — first call **without** `confirm` (preview of all changes), then the same call with `confirm: true` to apply
- After any code write — check for errors via `get_project_errors` or `get_problem_summary`
- Before writing new code — find similar patterns in the project via `search_in_code` and follow them
- When in doubt about a 1C platform signature — `get_platform_documentation`, do not guess

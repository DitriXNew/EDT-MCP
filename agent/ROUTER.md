# EDT-MCP business-project router

Use this router only for work on a 1C project through EDT-MCP. Plugin source,
tool implementation, Tycho builds, and server redeployment belong to the
repository contributor skills under `.claude/skills/`.

Load the smallest matching skill. Load more than one only when the task truly
crosses workflow boundaries.

| Task intent | Skill |
|---|---|
| Establish the current project, server surface, or multi-EDT route | `edt-mcp-project-session` |
| Investigate BSL, dependencies, impact, or compare implementations | `edt-mcp-project-code-research` |
| Apply one bounded BSL correction | `edt-mcp-project-local-fix` |
| Inspect or change metadata, roles, rights, or RLS | `edt-mcp-project-metadata` |
| Research or change a managed form | `edt-mcp-project-forms` |
| Inspect, validate, or change a query or DCS | `edt-mcp-project-query-dcs` |
| Work with external data processors or reports | `edt-mcp-project-external-objects` |
| Collect runtime or debugger evidence | `edt-mcp-project-runtime-debug` |
| Run or debug YAXUnit tests | `edt-mcp-project-yaxunit` |
| Revalidate, clean, resync, update, or maintain an EDT project | `edt-mcp-project-maintenance` |
| Measure a bounded performance scenario | `edt-mcp-project-profiling` |
| Explicitly ask 1C:Workmate for research | `edt-mcp-project-workmate` |
| Inspect or run EDT translation workflows | `edt-mcp-project-translation` |

After selecting a skill, follow its workflow. Call `get_tool_guide` only when
the skill identifies an uncertain parameter, a version-sensitive operation,
or a destructive/cascading boundary; do not preload every tool guide.

If no skill matches, use the normal EDT-MCP discovery surface and keep the task
bounded. Do not invent a tool name or route the task into plugin development.

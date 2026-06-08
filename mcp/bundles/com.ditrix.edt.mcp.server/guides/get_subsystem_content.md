Inspect a single 1C subsystem in depth: its properties, the metadata objects it contains and its direct child subsystems. Output is Markdown with a `## Properties` table, a `## Content` table (Type/Name/Synonym/FQN of each included object) and a `## Child Subsystems` table.

## When to use
- You have a subsystem FQN (typically from `list_subsystems`) and want the objects assigned to it plus its settings.
- To map an entire configuration's subsystem tree first, use `list_subsystems`; this tool drills into one node.

## Parameter details
- `projectName` (required) - EDT project name.
- `subsystemFqn` (required) - the subsystem FQN (see FQN format below).
- `recursive` - default `false`: the Content table lists only the objects directly assigned to this subsystem, keeping the response compact. Set `true` to also fold in objects from all nested subsystems; duplicates that appear in more than one nested subsystem are deduplicated. The Content header is marked `(recursive)` in that mode.
- `language` - language code for the Synonym/Explanation columns (e.g. `en`, `ru`). Defaults to the configuration's default language.

## FQN format
Top-level: `Subsystem.Sales`. Nested subsystems repeat the segment: `Subsystem.Sales.Subsystem.Orders`. The child-subsystem table emits exactly these FQNs, so you can chain another `get_subsystem_content` call on a child.

## Output sections
- **Properties** - FQN, Name, Synonym, optional Comment, the IncludeInCommandInterface / IncludeHelpInContents / UseOneCommand flags, optional Explanation and the parent subsystem.
- **Content** - one row per included object (Type, Name, Synonym, FQN), sorted by Type then Name. The header shows the object count.
- **Child Subsystems** - direct children with FQN, Synonym and content/children counts (omitted when there are none).

## Examples
- Direct content only: `{projectName: "MyProject", subsystemFqn: "Subsystem.Sales"}`.
- Include nested objects: `{projectName: "MyProject", subsystemFqn: "Subsystem.Sales", recursive: true}`.
- Nested subsystem with Russian synonyms: `{projectName: "MyProject", subsystemFqn: "Subsystem.Sales.Subsystem.Orders", language: "ru"}`.

## Notes & gotchas
- The object Name is the programmatic identifier; the Synonym is rendered for the chosen `language` only. An unconfigured language yields an empty synonym, not an error.
- A subsystem with no assigned objects renders an explicit *No objects in this subsystem.* line rather than an empty table.
- Output is Markdown; table cells are escaped so a `|` in a synonym/comment does not break the table.

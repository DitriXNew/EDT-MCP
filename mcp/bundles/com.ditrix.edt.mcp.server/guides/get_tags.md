Lists the tags defined in a project. Tags are user-defined labels you attach to metadata objects to organize and filter them (an EDT-MCP feature, stored per project). This is the discovery tool: see what tags exist before filtering objects by them.

## When to use
- Discover which tags a project uses (and how many objects each covers).
- Find the exact tag name to pass to `get_objects_by_tags`.

## Parameter details
- `projectName` (required) - the EDT project.

## What you get
Markdown: a table of **#**, **Name**, **Color**, **Description**, and **Objects** (how many objects carry the tag), plus a total. When no tags are defined it says so for that project.

## Notes & gotchas
- Tags are an organizational overlay maintained by this plugin - they are not part of the 1C configuration metadata itself.
- Use the **Name** verbatim as a `tags` entry in `get_objects_by_tags` to list the tagged objects.
- A project still building is refused with a clear message; a missing project returns a "Project not found" error naming the value.

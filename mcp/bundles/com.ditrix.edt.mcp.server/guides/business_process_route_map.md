## Guide

Use `business_process_route_map` to read, replace or remove the separate route-map document of a top-level `BusinessProcess`. Call `action="get"` before replacing it. Creating a BusinessProcess with `create_metadata` does not seed a route map; use `replace` afterwards.

## Parameter details

- `projectName`: EDT project name.
- `fqn`: `BusinessProcess.ProcessName`. Only the type token may be English or Russian; the object name is a programmatic identifier, not a synonym.
- `action`: `get`, `replace` or `remove`.
- `points` and `connections`: JSON arrays, both required for `replace`, including an empty replacement. The transport converts nested arrays to JSON strings internally. Omit them for `get` and `remove`.
- `language`: configuration language code for titles. Omitted means the configuration's default language code, then its first configured language code.

A writable point is `{name,type,title?,x?,y?,width?,height?,taskDescription?,group?}`. Names must be unique platform identifiers. Writable types are `Start`, `Completion`, `Activity`, `Condition`, `Split`, `Join` and `Processing`. `group` is a boolean on Activity. `taskDescription` defaults to the Activity's name.

Omitted width and height use `EntitySizeProvider` defaults for that kind; explicit positive integer sizes are accepted unchanged. Omitted x and y use a deterministic layered layout from Start points, on a 20-pixel grid. Explicit positive integer coordinates are accepted unchanged. Null means omitted/default. Pass geometry directly on a point, not in a `bounds` object.

A connection is `{from,to,name?,title?,fromPort?,toPort?,branch?}`. Both endpoints must name points in this replacement. Supply `branch="true"` or `branch="false"` for a Condition source, at most one outgoing connection per branch. Anchors are 1=left, 2=top, 3=right, 4=bottom. Defaults are source=4, target=2, Condition true=3 and false=1. Explicit source ports may override the default for other point kinds, and explicit target ports may override target=2. A Condition source must use fromPort=3 for true and fromPort=1 for false; contradicting ports and two branches on one port are refused. The read path derives branch from the stored port against the Condition's true port. Pass connection names explicitly for deterministic names; EDT's generated names depend on its UI language.

## Examples

Read a route map and its platform defaults:

```json
{"projectName":"TestConfiguration","fqn":"BusinessProcess.Approval","action":"get"}
```

Create or replace a basic route map:

```json
{
  "projectName":"TestConfiguration",
  "fqn":"BusinessProcess.Approval",
  "action":"replace",
  "points":[
    {"name":"Begin","type":"Start"},
    {"name":"Review","type":"Activity","taskDescription":"Review request","width":80,"height":60},
    {"name":"Done","type":"Completion"}
  ],
  "connections":[
    {"from":"Begin","to":"Review","name":"BeginReview"},
    {"from":"Review","to":"Done","name":"ReviewDone"}
  ]
}
```

Remove the document:

```json
{"projectName":"TestConfiguration","fqn":"BusinessProcess.Approval","action":"remove"}
```

## Result and validation

`get` returns `routeMapExists`, `routeMapFqn`, `file`, the `points` and `connections` arrays, and `defaults.sizes` / `defaults.ports`. An absent document returns `routeMapExists=false` and empty arrays with defaults. Point geometry is returned as `bounds`. Existing editor-authored Switch cases, subprocess references, event handlers and addressing-attribute names are readable.

Writes return item counts in `points` / `connections`, `persisted`, and `writtenProjects` after the shared export barrier. Replace reports the written counts. Remove reports the discarded counts. `file` is the path supplied by EDT's project filesystem service; use this returned path instead of constructing a filename. Only the route-map top object is exported; the owning metadata file has a transient flowchart reference.

EDT owns graph validation, including Start/completion reachability and split/join rules. The tool reports markers sliced by both the BusinessProcess FQN and the route-map FQN. Replacement with an empty route-map marker slice returns `markersIncomplete=true` with a reason and never claims `markerCount:0`. Re-check with `get_project_errors` or the EDT route-map editor. Removal checks only the owning BusinessProcess; it does not validate the deleted route-map document.

## Consent, identity and recovery

Replacing an absent or empty map needs no destructive consent. Replacing a non-empty map requires destructive consent. Removing an existing map uses the destructive-consent gate; an absent map is refused before consent. Denied consent writes nothing. If the document changes during consent, read it again and retry.

Replace discards all old items. Same-named points keep their `pointUUID`, which is their identity for infobase tasks. Renaming a point through replacement creates a new identity. Print properties, grid settings and document background survive replacement. To revert, restore the exported route-map file from version control and refresh/clean the project. Removing and recreating the document creates fresh point identities.

Analysis Only and Code Review disable the entire tool, including `get`. A summary in `get_metadata_details` is planned separately.

## Deferred scope

Switch, SubBusinessProcess, Decoration, event-handler writes, addressing-attribute values, incremental upsert, line points/segments, colours/fonts, route maps of adopted BusinessProcess objects, and auto-seeding during `create_metadata` are deferred to a follow-up. Use the EDT route-map editor for these features. A BusinessProcess owned by an extension is supported; an adopted BusinessProcess cannot be addressed by this tool.

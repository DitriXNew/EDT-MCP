# XDTO Packages

1C XDTO packages (`XDTOPackage`) can be authored end-to-end through MCP, down to their
package-local structure, without hand-editing XML:

- **`create_metadata`** / **`modify_metadata`** / **`delete_metadata`** address XDTO
  package members by FQN, layered on top of the existing top-level `XDTOPackage.<Name>`
  create/delete:
  - `XDTOPackage.<Package>.ObjectType.<Type>` — an ObjectType in the package (optional
    flags: `open`, `abstract`, `mixed`, `ordered`, `sequenced`).
  - `XDTOPackage.<Package>.Property.<Name>` — a package-global Property.
  - `XDTOPackage.<Package>.ObjectType.<Type>.Property.<Name>` — a Property nested in an
    ObjectType.

  A Property's `type` accepts a built-in XSD type name (e.g. `string`), the exact name
  of another ObjectType already in the same package (a same-package reference), or an
  explicit `{nsUri, name}` pair; optional `lowerBound`/`upperBound`, `nillable`,
  `fixed`+`default` round out the vocabulary. See `get_tool_guide('create_metadata')`
  for the full parameter list.
- **`validate_xdto_package`** reads EDT's stored validation markers for one package and
  returns valid, problems found, or undecided when a marker location could not be resolved,
  plus the matching problems. It is a read-only wrapper over `get_project_errors`, not
  on-demand validation: after authoring or editing members, run `revalidate_objects` first.


# go_to_definition

Go to the definition of a symbol (the inverse of find_references): a qualified method 'ModuleName.MethodName', a bare 'MethodName' (also pass modulePath), or a metadata FQN like 'Catalog.Products'. A bare method name requires modulePath. Use this for where a symbol is DEFINED; for all its USAGES use find_references, for a literal (non-symbol) text scan use search_in_code. Full parameters and examples: call get_tool_guide('go_to_definition').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name |
| symbol | yes | string | 'ModuleName.MethodName', bare 'MethodName' (needs modulePath), or metadata FQN 'Catalog.Products' |
| modulePath | — | string | Module path from src/, e.g. 'CommonModules/My/Module.bsl'; required for a bare method name |
| includeSource | — | boolean | Include the source body (default true) |

## Guide
Resolves a symbol to where it is DEFINED (the inverse of find_references): instead of listing usages, it returns the definition location, signature, region, and (optionally) the source body. Works for common-module methods and metadata objects.

## When to use

- You have a call like `Foo.Bar()` or a method name and want to jump to its definition.
- You have a metadata reference like `Catalog.Products` and want its type plus the BSL modules attached to that object.
- For the reverse direction (who calls/uses a symbol), use `find_references` instead.

## Parameter details

- `projectName` (required): EDT project name.
- `symbol` (required): one of three forms (see Modes below).
- `modulePath` (conditional): path from `src/`, e.g. `Documents/SalesOrder/ObjectModule.bsl`. REQUIRED when `symbol` is a bare method name (no dot) - there is no way to locate an unqualified method without its module. Ignored for the qualified-method and metadata-FQN forms.
- `includeSource` (optional, default true): when true, the method body (plus any adjacent doc-comment block) is included in a fenced `bsl` block; pass false for just the frontmatter location.

## Modes (the three symbol forms)

1. Qualified method `ModuleName.MethodName` - `ModuleName` is matched against common modules (case-insensitive); the method is then resolved inside that common module.
2. Bare method `MethodName` - resolved inside the module given by `modulePath`. Use this for methods in object/manager/form modules that are not common modules.
3. Metadata FQN `Type.Name` (e.g. `Catalog.Products`) - returns the object kind/type and lists the available BSL modules under that object; follow up with `get_metadata_details` or `read_module_source`.

Resolution order for a two-part symbol: it is tried as a common-module method first, then as a metadata FQN. So a name that collides could match the common module - prefer the explicit metadata type token if you mean the object.

## Output

YAML frontmatter plus an optional fenced `bsl` body. For a method: `module`, `method`, `type` (Procedure/Function), `export`, `startLine`/`endLine`, `totalLines`, `region`, and `qualifiedName` when the qualified form was used. For a metadata object: `kind`, `type`, `name`, and an Available Modules list.

## Bilingual (ru/en)

The metadata type token is dialect-aware: the FQN may use the Russian type name (Документ.Встреча, Справочник.Товары), singular or plural, and is normalized internally. The OBJECT name itself is the programmatic `Name`, not a synonym - only the leading TYPE token is bilingual.

## Examples

- Qualified method: `{ "projectName": "MyProject", "symbol": "CommonModule.DoWork" }`
- Bare method in an object module: `{ "projectName": "MyProject", "symbol": "OnWrite", "modulePath": "Documents/SalesOrder/ObjectModule.bsl" }`
- Metadata object: `{ "projectName": "MyProject", "symbol": "Catalog.Products" }`
- Location only (no body): add `"includeSource": false`.

## Gotchas

- A bare method name without `modulePath` is rejected - qualify it or supply the module.
- When nothing matches, the response lists similar common modules or objects and the supported metadata types as suggestions; it is not an error.
- Only common-module methods resolve from the `ModuleName.MethodName` form; for methods in non-common modules use the bare-name + `modulePath` form.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*

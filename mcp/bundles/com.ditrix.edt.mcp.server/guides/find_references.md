Locates every reference to one metadata object across the whole configuration: BSL code modules (with line numbers), other metadata objects (forms, roles, subsystems, attributes that type-reference it), predefined-item usages and field references. Results are grouped by category and returned as Markdown.

## When to use
- Before renaming or deleting an object, to assess the blast radius.
- To understand where a catalog/document/register/common module is consumed.
- To find an identifier regardless of its ru/en spelling - unlike `search_in_code`, this is model-aware, not a literal text search.

## Parameter details
- `projectName` (required) - EDT project name.
- `objectFqn` (required) - fully qualified name `Type.Name` of the object to find references for. The `Name` part is the object's programmatic name (never its synonym). Only the leading TYPE token is bilingual: `Catalog.Products` and `Справочник.Products` resolve to the same object (the Russian type spelling is normalized internally). Examples: `Catalog.Products`, `Document.SalesOrder`, `CommonModule.Common`, `InformationRegister.Prices`.
- `limit` - result-size hint; default 100, max 500 (clamped). It caps the OVERALL number of references collected (at `limit*10`) before they are grouped, NOT a per-category count - so a single busy category can consume most of the budget. Raise it when a large object truncates the result.

## Result categories
References are grouped by where they were found, e.g.:
- `BSL Modules` - code references, each with module path and line number.
- Metadata features - attributes, tabular sections, forms, commands, roles, subsystems and other objects that type-reference or contain the object.
- `Predefined items` - usages of the object's predefined values.
- `Field references` - field-level usages.

## Examples
- English FQN: `{projectName, objectFqn: "Catalog.Products"}`.
- Russian type token: `{projectName, objectFqn: "Справочник.Products"}` (same object as above).
- Wider budget: `{projectName, objectFqn: "Document.SalesOrder", limit: 500}`.

## Notes & gotchas
- An unknown or malformed `objectFqn` (no `Type.Name`, or a type/name that does not exist) returns an error - check the type token and the programmatic name.
- Resolution is by the object's `Name`, NOT by its synonym; passing a synonym will not match.
- The result is a hint-limited snapshot: a truncation notice means there are more references than `limit*10` - raise `limit` to see them.
- For a literal text search (comments, messages, raw strings) use `search_in_code` instead; for call graphs of a specific method use `get_method_call_hierarchy`.

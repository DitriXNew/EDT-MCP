# adopt_metadata_object

Add a base-configuration object or member to an extension for customization. Parameters and examples: get_tool_guide('adopt_metadata_object').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | The BASE configuration EDT project that owns the object (NOT the extension) (required) |
| fqn | yes | string | Full-name FQN of the object or member to adopt (required), e.g. 'Catalog.Products', 'Catalog.Products.Attribute.Weight', 'Catalog.Products.Form.ItemForm' |
| extensionProjectName | — | string | Target extension EDT project name; REQUIRED only when more than one extension extends the configuration (otherwise the single extension is used automatically) |
| includeReferencedPictures | — | boolean | Adopt referenced CommonPicture dependencies; forms only, default false |

## Guide
Adopts a base-configuration metadata object - or a member of it - into a configuration EXTENSION, the prerequisite for the extension to override/intercept it. This is the OBJECT/metadata side of EDT's 'Add To Extension' (Alt+F3). Returns JSON.

## What it does
Creates the adopted copy in the extension with `objectBelonging=Adopted`, then force-exports it to disk. EDT links the copy to its base object by ID - an `extendedConfigurationObject` UUID link, so the adopted copy may later be renamed - only when the extension root keeps mapping by IDs (`keepMappingToExtendedConfigurationObjectsByIDs`, which EDT's new-extension wizard and create_project set) and the platform version is above 8.3.14; otherwise EDT maps the copy by name, and the result's `message` says so. EDT writes the link only at adoption, so the result looks at the copy itself: an object adopted before the flag was turned on stays mapped by name, and one adopted while it was on keeps its link. After adopting, you can override its properties / add the extension's own members with modify_metadata / create_metadata against the extension project.

## Parameter details
- `projectName` (required) - the BASE configuration project that owns the object. Pass the configuration, NOT an extension.
- `fqn` (required) - the object or member to adopt. Top object `Type.Name` (`Catalog.Products`); member `Type.Name.Kind.Name` (`Catalog.Products.Attribute.Weight`, `Catalog.Products.Form.ItemForm`). The TYPE/KIND tokens may be English or Russian; the Name parts are the programmatic Names.
- `extensionProjectName` (optional) - the target extension's EDT project name. Required ONLY when several extensions extend the configuration; with exactly one extension it is auto-selected. If you omit it and there are several, the error lists the candidates.
- `includeReferencedPictures` (optional, default false) - only for `CommonForm.Name` or `Type.Object.Form.Name`. Discover persisted native `PictureRef` values, deduplicate configuration `CommonPicture` references and adopt missing pictures into the same extension. `StdPicture` and `StdExtPicture` are ignored. Other object/member FQNs are refused when true.

## Cascade
Newer EDT may adopt additional objects by cascade; `cascadedObjects` lists the top FQNs attached by this adoption's committed transaction (up to 100), with `cascadedCount` giving the total.
If attribution cannot be read after commit, adoption still succeeds with `cascadeAttribution='unavailable'` and a `warning`, omitting the cascade list/count; inspect the extension with get_metadata_objects instead of retrying adoption.
Adopting a MEMBER (or a nested object) implicitly adopts the owning object too (the parent must exist in the extension to host the member) - this mirrors the platform behaviour.
A NESTED subsystem is addressed by its whole chain, exactly as list_subsystems prints it (`Subsystem.Sales.Subsystem.Orders`, any depth; each type token English or Russian) - a bare `Subsystem.Orders` names a top-level subsystem only. Adopting it first adopts every parent subsystem the extension does not have yet, and `fqn` in the result is that canonical chain.

With `includeReferencedPictures=true`, a repeat form call reads the persisted extension copy, so it can repair missing pictures without replacing extension edits. The native form model is the source of truth; no XML or BSL string scanning is used. Unreadable content, unresolved references and non-adoptable pictures return `success=false` before new writes, including on repeat calls. Existing extension pictures need no base counterpart. Missing dependencies are resolved in the base and preflighted before any writes. Each missing picture is adopted and committed separately before adopting the form, using the same native path as ordinary adoption. Repeat calls inspect EDT's saved extension projection, excluding live base-only inherited pictures while preserving saved picture references and extension overrides. Only a detached copy is processed. Missing BaseForm snapshots or export services refuse before adoption. A platform failure reports observable completed adoptions and unresolved dependencies; it does not promise atomic rollback. Inspect the extension before retrying.

## Result
JSON with `action` ('adopted', or 'alreadyAdopted' when it was already adopted), the adopted `fqn`, the `extensionProject`, `objectBelonging='ADOPTED'`, and `persisted`; `message` when it was already adopted, and when the adopted copy carries no `extendedConfigurationObject` link, so it is mapped to the base by name (it says the likely reason and, where it can, how to change that). After an adopt, run get_project_errors on the extension to confirm the adoption is valid. With the option enabled, `dependencies.commonPictures` reports `found`, `adopted`, `alreadyPresent` and `unresolved` arrays (canonical picture FQNs where readable). Any unresolved dependency means `success=false`. `action` describes the form: `alreadyAdopted` may still repair pictures, with `persisted` and `writtenProjects` describing those writes. A platform failure also reports `formAdopted` and unavailable cascade attribution. All affected top objects are force-exported. After adoption, run get_project_errors on the extension before update/deploy.

## Notes & gotchas
- This adopts the metadata OBJECT side only. Intercepting a BSL method (`&Before/&After/&Around/&ChangeAndValidate`) is NOT done here: write the interceptor with write_module_source, and that write marks the module `Extended`.
- An object the platform reports as not adoptable is rejected with a clear error.
- No automatic undo: an adopted copy is removed with delete_metadata against the extension, using the whole chain for a nested subsystem. Preview, then confirm. Deleting a subsystem also removes all its nested subsystems; the preview message lists their whole chain FQNs.
- modify_metadata writes an adopted copy the way EDT keeps it in the extension: it adds types to an adopted defined type's type extension (platform 8.3.20 and later) and sources to an adopted event subscription, marked Extended (8.3.17 and later), and refuses a write EDT would leave out of the extension's `.mdo` (a property whose extension state is None) - see "Adopted objects in an extension" in modify_metadata's guide.

## Vendor support
Vendor support never blocks an adoption: the base configuration is only read (adopting is the way to change a locked object), and EDT applies support only to a configuration's own (native) root, never inside the extension that receives the copy.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

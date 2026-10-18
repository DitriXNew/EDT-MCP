Adopts a base-configuration metadata object - or a member of it - into a configuration EXTENSION, the prerequisite for the extension to override/intercept it. This is the OBJECT/metadata side of EDT's 'Add To Extension' (Alt+F3). Returns JSON.

## What it does
Creates the adopted copy in the extension with `objectBelonging=Adopted`, then force-exports it to disk. EDT links the copy to its base object by ID - an `extendedConfigurationObject` UUID link, so the adopted copy may later be renamed - only when the extension root keeps mapping by IDs (`keepMappingToExtendedConfigurationObjectsByIDs`, which EDT's new-extension wizard and create_project set) and the platform version is above 8.3.14; otherwise EDT maps the copy by name, and the result's `message` says so. EDT writes the link only at adoption, so the result looks at the copy itself: an object adopted before the flag was turned on stays mapped by name, and one adopted while it was on keeps its link. After adopting, you can override its properties / add the extension's own members with modify_metadata / create_metadata against the extension project.

## Parameter details
- `projectName` (required) - the BASE configuration project that owns the object. Pass the configuration, NOT an extension.
- `fqn` (required) - the object or member to adopt. Top object `Type.Name` (`Catalog.Products`); member `Type.Name.Kind.Name` (`Catalog.Products.Attribute.Weight`, `Catalog.Products.Form.ItemForm`). The TYPE/KIND tokens may be English or Russian; the Name parts are the programmatic Names.
- `extensionProjectName` (optional) - the target extension's EDT project name. Required ONLY when several extensions extend the configuration; with exactly one extension it is auto-selected. If you omit it and there are several, the error lists the candidates.

## Cascade
Adopting a MEMBER (or a nested object) implicitly adopts the owning object too (the parent must exist in the extension to host the member) - this mirrors the platform behaviour.
A NESTED subsystem is addressed by its whole chain, exactly as list_subsystems prints it (`Subsystem.Sales.Subsystem.Orders`, any depth; each type token English or Russian) - a bare `Subsystem.Orders` names a top-level subsystem only. Adopting it first adopts every parent subsystem the extension does not have yet, and `fqn` in the result is that canonical chain.

## Result
JSON with `action` ('adopted', or 'alreadyAdopted' when it was already adopted), the adopted `fqn`, the `extensionProject`, `objectBelonging='ADOPTED'`, and `persisted`; `message` when it was already adopted, and when the adopted copy carries no `extendedConfigurationObject` link, so it is mapped to the base by name (it says the likely reason and, where it can, how to change that). After an adopt, run get_project_errors on the extension to confirm the adoption is valid.

## Notes & gotchas
- This adopts the metadata OBJECT side only. Intercepting a BSL method (`&Before/&After/&Around/&ChangeAndValidate`) is NOT done here: write the interceptor with write_module_source, and that write marks the module `Extended`.
- An object the platform reports as not adoptable is rejected with a clear error.
- No automatic undo: an adopted copy is removed with delete_metadata against the extension - except a NESTED subsystem, whose chain delete_metadata cannot address yet (remove that copy in EDT).
- modify_metadata writes an adopted copy the way EDT keeps it in the extension: it adds types to an adopted defined type's type extension (platform 8.3.20 and later) and sources to an adopted event subscription, marked Extended (8.3.17 and later), and refuses a write EDT would leave out of the extension's `.mdo` (a property whose extension state is None) - see "Adopted objects in an extension" in modify_metadata's guide.

## Vendor support
Vendor support never blocks an adoption: the base configuration is only read (adopting is the way to change a locked object), and EDT applies support only to a configuration's own (native) root, never inside the extension that receives the copy.

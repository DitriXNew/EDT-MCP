Adopts a base-configuration metadata object - or a member of it - into a configuration EXTENSION, the prerequisite for the extension to override/intercept it. This is the OBJECT/metadata side of EDT's 'Add To Extension' (Alt+F3). Returns JSON.

## What it does
Creates the adopted copy in the extension with `objectBelonging=Adopted` and an `extendedConfigurationObject` UUID link back to the base object (mapping is by-ID, so the adopted copy may later be renamed), then force-exports it to disk. After adopting, you can override its properties / add the extension's own members with modify_metadata / create_metadata against the extension project.

## Parameter details
- `projectName` (required) - the BASE configuration project that owns the object. Pass the configuration, NOT an extension.
- `fqn` (required) - the object or member to adopt. Top object `Type.Name` (`Catalog.Products`); member `Type.Name.Kind.Name` (`Catalog.Products.Attribute.Weight`, `Catalog.Products.Form.ItemForm`). The TYPE/KIND tokens may be English or Russian; the Name parts are the programmatic Names.
- `extensionProjectName` (optional) - the target extension's EDT project name. Required ONLY when several extensions extend the configuration; with exactly one extension it is auto-selected. If you omit it and there are several, the error lists the candidates.

## Cascade
Adopting a MEMBER (or a nested object) implicitly adopts the owning object too (the parent must exist in the extension to host the member) - this mirrors the platform behaviour.

## Result
JSON with `action` ('adopted', or 'alreadyAdopted' when it was already adopted), the adopted `fqn`, the `extensionProject`, `objectBelonging='ADOPTED'`, and `persisted`. After an adopt, run get_project_errors on the extension to confirm the adoption is valid.

## Notes & gotchas
- This adopts the metadata OBJECT side only. Intercepting a BSL method (`&Before/&After/&Around/&ChangeAndValidate`) is NOT done here.
- An object the platform reports as not adoptable is rejected with a clear error.
- No automatic undo: an adopted copy is removed with delete_metadata against the extension.

Lists EDT configuration problems (validation markers: the same set EDT shows in its *Configuration Problems* view) as a Markdown table, with optional filters. All parameters are optional; with none, every problem across every project is returned (up to `limit`).

## When to use
- Triage validation errors/warnings after editing code or metadata.
- Get a structural locator (Module path + Line) for a BSL problem to feed straight into `read_module_source` or `set_breakpoint`.
- Narrow to one object (`objects` for a loose fragment, `objectFqns` for an exact address), one check (`checkId`) or one severity band (`severity`) while iterating on a fix.
- Verify that the objects you are about to filter on actually exist: `objectFqns` reports every address that resolves to nothing.
- For just the totals (counts per severity, no detail) prefer `get_problem_summary`.

## Parameter details
- `projectName` - EDT project name. Omit to scan all projects. An unknown project returns an error; a project still indexing returns a not-ready error.
- `severity` - one of `ERRORS`, `BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, `TRIVIAL`, `NONE` (case-insensitive). An out-of-set value is rejected (the filter is never silently widened to "all"). Matches that exact severity only (it is not a >= threshold).
- `checkId` - case-insensitive substring matched against EITHER the symbolic check id (e.g. `ql-temp-table-index`) OR the short UID (e.g. `SU23`). The short UID alone is rarely what you want, so the symbolic id is matched too.
- `objects` - LOOSE filter: an array of object FQN fragments, nested ones included (`Catalog.Products.Form.ItemForm`, `Catalog.Products.TabularSection.Goods.Attribute.Price`). Matching is a case-insensitive SUBSTRING test against the reported object location, after EVERY structural token has been normalized to both languages. Deliberate fragments are supported (`Catalog.Prod` selects `Catalog.Products`' problems), so an entry that matched nothing is NOT reported back - a fragment and a typo are indistinguishable here. Mutually exclusive with `objectFqns`.
- `objectFqns` - EXACT filter: an array of full model addresses, each resolved against the model before the marker scan. Mutually exclusive with `objects`. The response is a machine-readable payload in `structuredContent` (see *Exact addressing* below).
- `limit` - max rows; default 100, max 1000. When reached, the output appends a limit-reached notice; narrow the filters to see the rest.
- `responseFormat` - `concise` (default) or `detailed`. `concise` trims tokens by dropping the secondary `Has docs` column; every actionable column (`Description`, `Location`, `Module path`, `Line`, `Check code`) and the unresolved-marker warnings are kept. `detailed` adds the `Has docs` column back (true when `get_check_description` has detail for that check). An absent/unrecognized value defaults to `concise`.

## Output columns
`Description` | `Location` | `Module path` | `Line` | `Check code` | `Has docs`. `Module path` + `Line` are populated only for problems that resolve to a `.bsl` module under `src/` (empty for metadata-only problems). `Check code` shows the symbolic id when known, else the short UID. `Has docs=true` means `get_check_description` has detail for that check (the `Has docs` column appears only with `responseFormat: detailed`).

## Exact addressing (`objectFqns`)
Use this when the address is something you believe exists and a wrong answer would mislead you. Each entry must be the FULL address of ONE model node; the tool resolves it with the same resolvers the write tools use, and only the addresses that resolved scope the marker scan.

Supported address families:
- top-level objects and their mdclass members - `Catalog.Products`, `Catalog.Products.Attribute.Weight`, `Catalog.Products.TabularSection.Goods.Attribute.Price`;
- owned and common FORMS - `Catalog.Products.Form.ItemForm`, `CommonForm.Settings`;
- real FORM MEMBERS down to the leaf - `Catalog.Products.Form.ItemForm.Attribute.Object`, `CommonForm.Settings.Field.Code`, `...Form.ItemForm.Handler.OnCreateAtServer` (the leaf is looked up in the form content model, so a typo in the LEAF is reported, not absolved by the form containing it). The KIND token is part of the address too: `...Form.ItemForm.Button.Code` where `Code` is a FIELD is reported in `objectsNotFound`, not answered with an empty problem report;
- `Subsystem` chains - `Subsystem.Sales.Subsystem.Orders`;
- `Predefined` items - `Catalog.Products.Predefined.Sample`;
- XDTO at PACKAGE level only - `XDTOPackage.Exchange`.

The response carries, next to the Markdown `report`:
- `objectsResolved` - the addresses that resolved and therefore scoped the scan;
- `objectsNotFound` - the addresses that resolve to nothing. A partial miss is normal and is reported next to the results: two addresses, one good and one misspelt, return the good one's problems AND name the misspelt one.
- `objectsUnsupported` - `{fqn, reason}` for an address this filter cannot scope at all. Today that is exactly the XDTO MEMBER shapes (`XDTOPackage.P.ObjectType.T`, `XDTOPackage.P.Property.N`, `XDTOPackage.P.ObjectType.T.Property.N`): EDT reports every problem of a package on the package itself (`XDTOPackage.P.Package`), so a member address can never match a marker. That is a different fact from "this member does not exist", so it is never reported as `objectsNotFound` - scope to `XDTOPackage.P`, or call `validate_xdto_package`.

Matching is segment-boundary scoped: a problem belongs to a resolved address when its location IS that address or something strictly under it (so `CommonModule.Calc` also catches the `CommonModule.Calc.Module` problems, and a form catches its item tree). If no project in scope exposes a readable metadata model - or the only project in scope failed to answer - the call is REFUSED with an error instead of declaring every address missing.

A name written with `ё` also resolves against the stored `е` form (`create_metadata` normalizes `ё`->`е` in Names by default), exactly as the write/delete tools do, and the scan is then scoped by the spelling that really resolved - the verdict lists still echo your own spelling back.

## Bilingual (ru/en) note
Both object filters accept EVERY structural token in English or Russian - the leading TYPE token and every nested KIND token (`Form`/`Форма`, `Attribute`/`Реквизит`, `TabularSection`/`ТабличнаяЧасть`, `Command`/`Команда`, ...). Each FQN is expanded to an all-English and an all-Russian form before matching, so `Document.SalesOrder.Form.DocumentForm` and `Документ.ПродажаТоваров.Форма.ФормаДокумента` both resolve whatever language the marker location is rendered in. The NAME segments (the odd ones) are copied verbatim: they must be the real programmatic names, not synonyms, and a name that happens to spell a kind token (an object literally called `Форма`) is never translated.

## Examples
- All problems in one project: `{projectName: "MyConfig"}`.
- Errors only: `{projectName: "MyConfig", severity: "ERRORS"}`.
- One check across all projects: `{checkId: "ql-temp-table-index"}`.
- Loose scope by fragment: `{objects: ["Catalog.Products", "Document.SalesOrder"]}`.
- Loose scope on one form: `{objects: ["Catalog.Products.Form.ItemForm"]}`.
- Russian type name: `{objects: ["Справочник.Номенклатура"]}`.
- Russian nested FQN: `{objects: ["Справочник.Номенклатура.Форма.ФормаЭлемента"]}`.
- Exact addresses, misses reported: `{objectFqns: ["Catalog.Products", "Catalog.Typo"]}` -> `objectsNotFound: ["Catalog.Typo"]`.
- Exact form member: `{objectFqns: ["Catalog.Products.Form.ItemForm.Attribute.Object"]}`.
- XDTO package (member addresses are rejected as unsupported): `{objectFqns: ["XDTOPackage.Exchange"]}`.

## Gotchas
- Markers whose location cannot be resolved are NOT dropped: without an `objects` filter they appear with a `<unresolved: project>` placeholder (a trailing warning counts them); with an `objects` filter they are excluded (membership cannot be tested) and a separate warning counts them. Run `clean_project` / `revalidate_objects` to refresh stale markers.
- `severity` matches exactly; to see everything at or above a level, omit it and read the `Check code` / severity yourself, or call once per band.
- The `objects` match is a substring of the location, so an overly short fragment can over-match, and a MISSPELT entry silently matches nothing and reads exactly like a clean object. That is inherent to a substring filter: when you need to know whether the object exists, use `objectFqns`.
- Passing both `objects` and `objectFqns` is rejected: they answer different questions and combining them would silently apply one semantics to the other's entries.
- `objectFqns` changes the response format: the Markdown report moves into the `report` field of the `structuredContent` payload (the warnings are mirrored there as blockquotes for a human reader). A call without `objectFqns` returns Markdown exactly as before.

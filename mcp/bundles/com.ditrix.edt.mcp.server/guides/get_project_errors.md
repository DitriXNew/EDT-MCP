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
- `objects` - LOOSE filter: an array of object FQN fragments, nested ones included (`Catalog.Products.Form.ItemForm`, `Catalog.Products.TabularSection.Goods.Attribute.Price`). Matching is a case-insensitive SUBSTRING test against the reported object location, after every structural token this filter knows has been normalized to both languages (see the bilingual note below for the catalogue and its one exception). Deliberate fragments are supported (`Catalog.Prod` selects `Catalog.Products`' problems), so an entry that matched nothing is NOT reported back - a fragment and a typo are indistinguishable here. Mutually exclusive with `objectFqns`.
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
- real FORM MEMBERS down to the leaf - `Catalog.Products.Form.ItemForm.Attribute.Object`, `CommonForm.Settings.Field.Code`, `...Form.ItemForm.Handler.OnCreateAtServer` (the leaf is looked up in the form content model, so a typo in the LEAF is reported, not absolved by the form containing it). The KIND token is part of the address too: `...Form.ItemForm.Button.Code` where `Code` is a FIELD is reported in `objectsNotFound`, not answered with an empty problem report. In an ITEM-LEVEL handler address that applies to the OWNER's kind as well: `...Form.ItemForm.Button.Code.Handler.OnChange` is a miss for the same reason (`Command` is a legal owner, e.g. `...Form.ItemForm.Command.Post.Handler.Action`);
- `Subsystem` chains - `Subsystem.Sales.Subsystem.Orders`;
- `Predefined` items - `Catalog.Products.Predefined.Sample`;
- XDTO at PACKAGE level only - `XDTOPackage.Exchange`.

The response carries, next to the Markdown `report`:
- `objectsResolved` - the addresses that resolved and therefore scoped the scan;
- `objectsNotFound` - the addresses that resolve to nothing. A partial miss is normal and is reported next to the results: two addresses, one good and one misspelt, return the good one's problems AND name the misspelt one.
- `objectsUnsupported` - `{fqn, reason}` for an address this filter cannot scope at all. Today that is exactly the XDTO MEMBER shapes (`XDTOPackage.P.ObjectType.T`, `XDTOPackage.P.Property.N`, `XDTOPackage.P.ObjectType.T.Property.N`): EDT reports every problem of a package on the package itself (`XDTOPackage.P.Package`), so a member address can never match a marker. That is a different fact from "this member does not exist", so it is never reported as `objectsNotFound` - scope to `XDTOPackage.P`, or call `validate_xdto_package`.

Matching is segment-boundary scoped: a problem belongs to a resolved address when its location IS that address or something strictly under it (so `CommonModule.Calc` also catches the `CommonModule.Calc.Module` problems, and a form catches its whole content).

A MEMBER address answers with its OWNER's problems, and that is not sloppiness - it is the granularity EDT publishes. The only thing this filter can compare against is the marker's object presentation, and EDT indexes a marker on the object it belongs to, never on a member inside it: an attribute with no type produces `md-legacy-emf-check` markers whose location is the owning `Catalog.Products`, and a form item's dangling event handler produces a `form-legacy-check-event-handler` marker on the form content, `Catalog.Products.Form.ItemForm.Form`. Scoping such an address by the member alone would therefore match nothing and hand you `objectsResolved` next to a clean report - a false all-clear. So `Catalog.Products.Attribute.Weight` reports `Catalog.Products`' problems and `...Form.ItemForm.Field.Code` reports that FORM's problems; the `Location` column always shows which node each row really came from.

If no project in scope could answer - its metadata model is not readable (still indexing, closed, not a 1C:EDT project), or the only project in scope failed to answer - the call is REFUSED with an error instead of declaring every address missing. The same refusal covers a single address: if a project could not decide it (a form whose CONTENT model could not be read, or a resolve pass that threw) and no other project resolved it, the call is refused naming that address. A failed attempt decides nothing, so it is never reported as `objectsNotFound`.

With no `projectName` each project is scoped by the spellings that resolved IN IT, never by another project's: two projects may legitimately store the same address differently, and one merged scope would drop the problems the other project stores under its own spelling. A project where nothing resolved contributes no rows at all.

A name written with `ё` also resolves against the stored `е` form (`create_metadata` normalizes `ё`->`е` in Names by default), exactly as the write/delete tools do, and the scan is then scoped by the spelling that really resolved - the verdict lists still echo your own spelling back. This includes a PREDEFINED item, whose own lookup carries that fallback: `Catalog.Goods.Predefined.Мёд` resolves an item stored as `Мед` and the scan is scoped by the STORED name. The same applies to a HANDLER's event, which is accepted in either language: the scan is scoped by BOTH spellings the matched event carries, so `...Handler.ПриИзменении` still finds the problems an English-language project reports under `...Handler.OnChange`. A form COMMAND has no event - its handler leaf is the fixed `Action` token - so that address is scoped by `Action` AND `Действие` alike.

## Bilingual (ru/en) note
Both object filters normalize the STRUCTURAL tokens of an address to both languages - the leading TYPE token and the nested KIND tokens of every grammar that can appear in a marker location: mdclass members (`Attribute`/`Реквизит`, `TabularSection`/`ТабличнаяЧасть`, `Command`/`Команда`, ...), forms and their content (`Form`/`Форма`, `Field`/`Поле`, `Button`/`Кнопка`, `Handler`/`Обработчик`, ...), `Subsystem`/`Подсистема`, `Predefined`/`Предопределенные`, and the trailing content segments `Module`/`Модуль` and `Package`/`Пакет`. Each FQN is expanded to an all-English and an all-Russian form before matching, so `Document.SalesOrder.Form.DocumentForm` and `Документ.ПродажаТоваров.Форма.ФормаДокумента` both resolve whatever language the marker location is rendered in.

The ONE exception is the XDTO MEMBER grammar (`XDTOPackage.P.ObjectType.T`, `XDTOPackage.P.Property.N`), and it is deliberate: `XdtoWriter` accepts only the English tokens, EDT ships no Russian marker spelling for them, and no XDTO problem is ever reported on a member in the first place - so instead of inventing an alias, `objectFqns` answers such an address with `objectsUnsupported`. The XDTO PACKAGE level is normalized like everything else (`XDTOPackage.P`, and the `Package`/`Пакет` content segment EDT really renders).

Name segments must be the real programmatic names, not synonyms.

For an EXACT `objectFqns` address the offset is known, so the structural segments are the even ones and the NAME segments are copied verbatim: an object literally called `Форма` is never translated, and the scope stays on the object you addressed.

For a LOOSE `objects` fragment the offset is NOT known - the entry may start on a type, on a nested kind, or on a name - so the fragment is expanded at BOTH segment offsets. One of those readings treats the odd segments as structural, which means a name that happens to spell a kind token can be translated: filtering by `Catalog.Form` will also select a sibling catalog named `Форма`. That is the same over-match a substring filter always risks, it never becomes a claim about existence, and it is the price of not silently missing a fragment whose offset we guessed wrong. When you need the exact object, use `objectFqns`.

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

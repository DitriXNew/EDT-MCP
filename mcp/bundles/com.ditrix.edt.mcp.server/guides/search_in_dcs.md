Literal or regex full-text search inside the data composition schemas (DCS) of a project: every `*.dcs` file under the project's `src/` folder - the `Template.dcs` of report, data processor and other object-owned templates, and of common templates. It is the discovery step before `dcs`: a recognised export layout includes the template FQN to pass to `dcs`.

## When to use
- Find which reports or templates use a field, a parameter, a resource, a query table or any other text of their schema (`КоличествоОстаток`, `&Period`, `AccumulationRegister.Stock`).
- Then open the hit with `dcs(fqn=<the reported template FQN>, action='get', type='schema')`.

## What is NOT searched
- BSL modules - use `search_in_code`.
- Settings variant files (`*.dcss`), dynamic lists and conditional appearance of forms - they are not `*.dcs` files.

## Parameter details
- `projectName` (required) - EDT project name.
- `query` (required) - search string or regex; matched literally unless `isRegex=true`. XML entities (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, and decimal/hex character references) are decoded once per source line before matching, so use `&Period` or `A > 0` as query text. Each source line is matched on its own; a pattern spanning source lines will not match. Context blocks keep the original XML and source line numbers.
- `caseSensitive` - default `false`.
- `isRegex` - treat `query` as a Java regular expression; default `false`. An invalid pattern returns an error; pass `isRegex=false` to search the text literally.
- `limit` - max matches returned with context; default 100, max 500. Counts in every mode are always exact regardless of `limit`.
- `contextLines` - lines before/after each match; default 2, max 5 (`full` mode only).
- `fileMask` - case-insensitive substring of the file path WITHOUT the leading `src/`, e.g. `Reports/Stock` or `CommonTemplates`.

## Paths and template FQNs
Paths are shown without `src/`: `Reports/Stock/Templates/MainSchema/Template.dcs`. The template FQN is read off that layout:
- `Reports/Stock/Templates/MainSchema/Template.dcs` -> `Report.Stock.Template.MainSchema` (the same for data processors, catalogs and every other type that owns templates);
- `CommonTemplates/Analytics/Template.dcs` -> `CommonTemplate.Analytics`.
A file in any other layout is reported by path only, with no FQN (`-` in `files` mode).

## Output modes (`outputMode`)
- `full` (default) - matches grouped by file, each file with its `**Template:**` FQN and `contextLines` of context in `xml` blocks, capped at `limit`.
- `count` - only the total match and file counts.
- `files` - one row per file: path, template FQN, per-file match count.

## Completeness
A file that cannot be read is skipped and reported as a warning. In `full` and `files` modes, when nothing matched but a file was skipped or the search was interrupted, the result says the scan is INCOMPLETE instead of `No matches found.`; `count` mode prints the warnings with the totals. An incomplete scan is not proof that the text is absent.

## Examples
- `{projectName, query: "КоличествоОстаток"}` -> `### Reports/Stock/Templates/MainSchema/Template.dcs` with `**Template:** Report.Stock.Template.MainSchema` -> `dcs({projectName, fqn: "Report.Stock.Template.MainSchema", action: "get", type: "schema"})`.
- Overview first: `{projectName, query: "AccumulationRegister.Stock", outputMode: "files"}`.
- One report only: `{projectName, query: "Period", fileMask: "Reports/Stock"}`.

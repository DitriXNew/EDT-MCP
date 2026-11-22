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
- `limit` - maximum matches (`full`/`lines`) or file rows (`files`); default 100, clamped to 1–500. Ignored in `count` mode. Totals are independent of `limit` and `offset`.
- `offset` - number of matched files to skip, in case-sensitive path order; default 0. Negative values act as 0. Used only in `files` mode.
- `contextLines` - lines before/after each match; default 2, max 5 (`full` mode only).
- `fileMask` - case-insensitive filter on the file path WITHOUT the leading `src/`. Without `*`/`?` it is a substring, e.g. `Reports/Stock` or `CommonTemplates`. A `\` reads as `/` in both modes; a substring mask also matches its text as typed. With them it is a glob, read as in `search_in_code`: `*`/`?` within one name, `**` across folders, no `/` = any one name (`*Stock*`), a leading or inner `/` = from the path start (`Reports/*/Templates/**`), a trailing `/` = folders only. When the mask matches no file, the result says so.

## Paths and template FQNs
Paths are shown without `src/`: `Reports/Stock/Templates/MainSchema/Template.dcs`. The template FQN is read off that layout:
- `Reports/Stock/Templates/MainSchema/Template.dcs` -> `Report.Stock.Template.MainSchema` (the same for data processors, catalogs and every other type that owns templates);
- `CommonTemplates/Analytics/Template.dcs` -> `CommonTemplate.Analytics`.
A file in any other layout is reported by path only, with no FQN (`-` in `files` mode).

## Output modes (`outputMode`)
- `full` (default) - matches grouped by file, each file with its `**Template:**` FQN and `contextLines` of context in `xml` blocks, capped at `limit`.
- `lines` - the `full` totals, then ONE fenced block with a `<path> [Template: <FQN>]:<line>: <source line>` row per match (`<path>:<line>: ...` when the layout has no FQN; the line is the original XML, entities still escaped), capped at `limit`; `contextLines` is ignored.
- `count` - only the total match and file counts.
- `files` - a page with one row per matched file: path, template FQN, and per-file match count.
  If more files match, repeat the same search with the reported `next page: offset=N`.
  A page can hold fewer rows than `limit` to stay within the output size cap.
  Each page recomputes the scan; keep the query and filters unchanged, and restart at `offset=0` if files changed or the scan was incomplete.

## Completeness
A file that cannot be read is skipped and reported as a warning. In `full`, `lines` and `files` modes, when nothing matched but a file was skipped or the search was interrupted, the result says the scan is INCOMPLETE instead of `No matches found.`; `count` mode prints the warnings with the totals. An incomplete scan is not proof that the text is absent.

## Examples
- `{projectName, query: "КоличествоОстаток"}` -> `### Reports/Stock/Templates/MainSchema/Template.dcs` with `**Template:** Report.Stock.Template.MainSchema` -> `dcs({projectName, fqn: "Report.Stock.Template.MainSchema", action: "get", type: "schema"})`.
- File overview: `{projectName, query: "AccumulationRegister.Stock", outputMode: "files", limit: 3}`. If it reports `next page: offset=3`, repeat with `offset: 3`.
- One report only: `{projectName, query: "Period", fileMask: "Reports/Stock"}`.

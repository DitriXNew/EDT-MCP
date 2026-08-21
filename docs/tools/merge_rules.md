# merge_rules

Read or author EDT's merge-rules file - the per-node decisions a configuration comparison saves and re-applies when it is launched. Authoring needs NO running comparison (the file is addressed by names), and the report says which happened: rules written without a live comparison are reported NOT VALIDATED; with one, every rule is checked against what its node allows and an illegal rule is refused. Never merges anything - running the merge stays a human action in the comparison window. Parameters and examples: get_tool_guide('merge_rules').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| mode | yes | string (one of: read, write) | 'read' parses a rules file and reports its decisions; 'write' records decisions into one (required). |
| filePath | yes | string | Absolute path of the merge-rules file (required). read: the file to parse, '.xml' or the '.zip' a comparison saves. write: the '.xml' file to produce - an existing file there is OVERWRITTEN only when 'basedOn' names that SAME file, which updates it in place; any other write over an existing file is refused so decisions are never silently discarded. |
| basedOn | — | string | write: an existing rules file to start from, so its decisions and payload are kept and yours are merged in (optional; '.xml' or '.zip'). |
| decisions | — | array | write: the decisions to record, as [{path, rule}]. 'path' is the key chain below the root - [] = the whole configuration, ['commonModules'] = a whole collection (the EMF feature name; a metadata type token in either language, 'Catalog' or 'Catalogs' or the Russian form, is translated to it), ['commonModules','Main:Main:Main'] = one object, keyed by its name on the main, other and ancestor sides joined by ':' with 'NONE' for a side that has no such object. 'rule' is one of GetFromOther, DoNotMerge, MergePrioritizingMain, MergePrioritizingOther. |
| comparisonId | — | string | write: validate every rule against this live comparison before writing (optional; omitted = validate against the running comparison if there is one, otherwise author unvalidated and say so). |
| limit | — | integer | Max decision rows to report; default 200, max 1000 (optional) |

## Guide
Reads and authors the merge-rules file EDT's configuration comparison uses: the sparse XML document of per-node merge decisions the comparison saves, and reads back when a comparison is launched with it. This is the "prepare the decisions first, open the window once" path - the file is addressed by NAMES, not by internal node ids, so it can be authored with no comparison running at all.

It never merges anything. Running the merge stays a human action in the comparison window; this tool only puts the decisions where the window will find them.

## When to use
- Before launching a comparison (`compare_configurations`), to pre-load the decisions so the human opens the window on an already-decided tree instead of clicking through hundreds of nodes.
- After a comparison saved its settings, to READ what was decided - which objects were set to take the vendor's version, which were left alone, and which carry a decision below object level.
- To update an existing rules file: pass `basedOn` with the same path and only your decisions change; everything else in the file is kept.

## The file, in one picture
```xml
<Settings Format_version="2.0">
  <MergeSettings>
    <Node Key="$$Root$$" MergeRule="DoNotMerge">
      <Node Key="commonModules" MergeRule="GetFromOther">
        <Node Key="Alpha:Beta:Gamma" MergeRule="MergePrioritizingMain"/>
      </Node>
    </Node>
  </MergeSettings>
</Settings>
```
Three addressing levels, and they are the platform's, not this tool's invention:
- `$$Root$$` - the whole configuration.
- a collection, keyed by the model FEATURE name (`commonModules`, `catalogs`, `documents`) - one rule for every object of that kind, without listing them.
- an object, keyed by its name on the three sides joined by `:` - **main:other:ancestor**. `NONE` means the object does not exist on that side. So `Products:Products:Products` is the ordinary case, `Added:NONE:Added` is an object deleted in the other configuration, and `Alpha:Beta:Gamma` is a RENAME - three different names for one object.

The file is SPARSE: only decisions are in it. A node not mentioned keeps whatever EDT proposes by itself.

## Parameter details
- `mode` - `read` or `write` (required).
- `filePath` - the file (required, absolute). read: `.xml`, or the `.zip` a comparison saves. write: `.xml` only (see Gotchas). An existing file is only ever replaced when `basedOn` names THAT SAME file, which updates it in place; any other write over an existing file is refused rather than silently discarding what it holds.
- `basedOn` - write: an existing rules file to start from. Its decisions, its `Correspondences` section and every block this tool does not interpret are kept, and your decisions are merged in. Pass the same path as `filePath` to update a file in place. It says where the decisions COME FROM, not that anything may be overwritten: with a `basedOn` naming a different file, an existing `filePath` is still refused, so one file's decisions are never written over another's.
- `decisions` - write: `[{path, rule}]`.
  - `path` is the key chain BELOW the root: `[]` = the whole configuration, `["commonModules"]` = the collection, `["commonModules","Alpha:Alpha:Alpha"]` = one object. Spelling `$$Root$$` as the first key is accepted and means the same thing.
  - The collection key is the model FEATURE name, and a metadata type token is translated to it: `Catalog`, `Catalogs`, `Справочник` and `Справочники` all land on `catalogs`. A key the type table does not know is written exactly as sent - the platform has features that are not metadata types - so read an existing file with `mode: "read"`, or name a `comparisonId`, to have the key checked.
  - `rule` is one of `GetFromOther`, `DoNotMerge`, `MergePrioritizingMain`, `MergePrioritizingOther` - the platform's own camel-case literals. The Java constant spelling (`GET_FROM_OTHER`) is not a literal and is refused with the right spelling named.
- `comparisonId` - write: validate against this live comparison. Omitted, the tool validates against the running comparison when there is one whose tree has FINISHED, and otherwise authors the file unvalidated and says so. Naming a comparison that does not answer - it is no longer registered, or its tree is still building - is an error, not a quiet fallback: you asked for validation.
- `limit` - max reported rows (default 200, max 1000). The counters stay whole when the list is cut.

## Two modes of validation, and the report always says which one ran
- **No comparison to validate against.** The rule LITERALS are checked (a typo, or a rule this tool does not author, is refused), but whether a rule is legal for its own node cannot be known without the comparison. The report opens with `NOT VALIDATED - authored from names` and names `compare_configurations` as the way to get the other mode. It never reads as if the rules had been checked - and it does not claim a cause either: "no comparison answered for these nodes" is what is known, not "no comparison is running".
- **Live comparison.** Every rule is checked against the rules its own node allows before anything is written, and the report opens with `Validated against comparison <id>`. A rule the node does not allow is refused naming the node, the rule and the allowed set; a node the comparison offers no rule on at all is refused as that, not as an empty set.
- **Validation needs a FINISHED tree.** The comparison tree is built lazily, so while it is still building a node that has not been compared yet looks exactly like a node the comparison does not have. An unfinished tree is therefore never used to refuse anything: the write degrades to `NOT VALIDATED` (or, if you named the comparison, is refused saying nothing answered for that id). A check that could not run at all - the comparison failed instead of answering - is reported as a failed check, and nothing is written.

Either way, nothing is written until EVERY decision has passed: one bad decision in a batch leaves the file untouched, because a half-applied set is a file nobody chose.

## Output
Markdown.
- read: the source (naming the zip ENTRY when the file was a zip), the format version, the decision count, how many blocks are carried through uninterpreted, and a table `# | Node | Level | Main | Other | Ancestor | Rule | Order side`. `Level` is `root` / `collection` / `object` / `member`; the three name columns are filled only for an object row, where an absent side shows `(absent)`.
- write: the validation line described above, the counts (recorded / new / replaced / in the file now), and the decisions as written.

## Gotchas
- **Write `.xml`, not `.zip`.** A comparison SAVES a zip, and EDT reads one by looking for the entry named after the comparison's own project triple (`<main>_<other>_<ancestor>.xml`) - an entry named anything else is ignored with only a warning in the log. A zip authored from outside a comparison would therefore silently do nothing, so this tool refuses to write one. Reading a zip is fine.
- **Below the object, keys are POSITIONS, not names.** A collection element is keyed by the position it would have after the merge, and that number moves as soon as another rule changes. Such rows are reported (`member`) and preserved on rewrite, but never authored: a rule written there would land on whatever ends up at that position. Set the rule on the object and refine it in the comparison window.
- **A key is not an address on its own.** Sibling members under different owners share their last segment, so a decision is only ever written with its whole chain. The read table prints the chain for the same reason.
- **`CustomMerge` and `MergeUsingExternalTool` are refused unconditionally**, even when a node allows them. Both name a merge whose actual content is configured elsewhere - the custom merge carries its own nested settings, the external-tool merge names the tool - so writing the bare literal would record a decision nobody made here.
- **A rewrite is lossless.** `Properties` maps, `Correspondences`, nested sections and any attribute a future EDT adds are re-emitted verbatim; the file is re-serialized with a fixed layout (UTF-8, LF, two-space indent), which no reader keys on.
- Only `Format_version="2.0"` is read - the same version EDT's own reader accepts. Another version, or a file that is not merge settings, is refused naming what was found.

## Examples
- Take the vendor's version for every common module, keep our own for one renamed catalog:
  `{mode: "write", filePath: "C:/tmp/rules.xml", decisions: [{path: ["commonModules"], rule: "GetFromOther"}, {path: ["catalogs", "Alpha:Beta:Gamma"], rule: "MergePrioritizingMain"}]}`
- Decide the whole configuration at once, then refine one collection:
  `{mode: "write", filePath: "C:/tmp/rules.xml", decisions: [{path: [], rule: "DoNotMerge"}]}` followed by
  `{mode: "write", filePath: "C:/tmp/rules.xml", basedOn: "C:/tmp/rules.xml", decisions: [{path: ["documents"], rule: "GetFromOther"}]}`
- Read what a comparison saved: `{mode: "read", filePath: "C:/tmp/MyConfig_Vendor_Base.zip"}`
- Validate against a running comparison: add `comparisonId: "<the id compare_configurations returned>"` to any write.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

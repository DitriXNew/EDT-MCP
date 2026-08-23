Reads and authors the merge-rules file EDT's configuration comparison uses: the sparse document of per-node merge decisions the comparison saves, and reads back when a comparison is launched with it. This is the "prepare the decisions first, open the window once" path - decisions are addressed by NAMES, not by internal node ids, so the DOCUMENT can be authored with no comparison running at all. That freedom belongs to the document, not to every container it can go in: an `.xml` needs nothing, a `.zip` needs a live comparison to name its entry. See **Which container to write**.

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
- `filePath` - the file (required, absolute). read: `.xml`, or the `.zip` a comparison saves - **case does not matter here**, `RULES.ZIP` is read, because this path opens the file itself and never hands it to EDT. write: **this parameter, and nothing else, picks the container** - `.zip` or `.xml`, see **Which container to write** - and HERE the extension must be spelled in **lower case**: EDT's own reader compares it with `String.equals`, so `rules.ZIP` is a name no version reads and is refused here rather than written and refused inside the launch. Passing `comparisonId` asks for validation; it does not turn an `.xml` target into a zip. An existing file is only ever replaced when `basedOn` names THAT SAME file, which updates it in place; any other write over an existing file is refused rather than silently discarding what it holds.
- `basedOn` - write: an existing rules file to start from. Its decisions, its `Correspondences` section and every block this tool does not interpret are kept, and your decisions are merged in. Its extension is **not** case-checked either - like `read`, it is opened here and never by EDT. Pass the same path as `filePath` to update a file in place. It says where the decisions COME FROM, not that anything may be overwritten: with a `basedOn` naming a different file, an existing `filePath` is still refused, so one file's decisions are never written over another's.
- `decisions` - write: `[{path, rule}]`.
  - `path` is the key chain BELOW the root: `[]` = the whole configuration, `["commonModules"]` = the collection, `["commonModules","Alpha:Alpha:Alpha"]` = one object. Spelling `$$Root$$` as the first key is accepted and means the same thing.
  - The collection key is the model FEATURE name, and a metadata type token is translated to it: `Catalog`, `Catalogs`, `Справочник` and `Справочники` all land on `catalogs`. A key the type table does not know is written exactly as sent - the platform has features that are not metadata types - so read an existing file with `mode: "read"`, or name a `comparisonId`, to have the key checked.
  - `rule` is one of `GetFromOther`, `DoNotMerge`, `MergePrioritizingMain`, `MergePrioritizingOther` - the platform's own camel-case literals. The Java constant spelling (`GET_FROM_OTHER`) is not a literal and is refused with the right spelling named.
- `comparisonId` - write: validate against this live comparison. Validation needs a comparison whose tree has FINISHED. Omitted, the tool validates against the running comparison when its tree has finished, and otherwise authors the file unvalidated and says so. NAMING one is a request, not a hint: it is an error, and never a quiet fallback, both when nothing answers for that id and when the comparison answers while its tree cannot be read. The two refusals say which of the two happened, because only the second one ends by itself - wait, then re-send.
- `limit` - max reported rows (default 200, max 1000). The counters stay whole when the list is cut.

## Three outcomes of validation, and the report always says which one ran

A comparison answers two separable things: its ADDRESS - which projects it runs over - known the moment the session is found, and a RULE VERDICT, which needs a tree that has FINISHED. That is why there are three outcomes and not two.
- **Nothing answered.** The rule LITERALS are checked (a typo, or a rule this tool does not author, is refused), but whether a rule is legal for its own node cannot be known without the comparison. The report opens with `NOT VALIDATED - authored from names` and names `compare_configurations` as the way to get a checked file. It never reads as if the rules had been checked - and it does not claim a cause either: "no comparison answered for these nodes" is what is known, not "no comparison is running". A `.zip` target is refused outright in this state, because its entry name is unknowable.
- **A comparison answered, but its tree could not be read.** It is registered and it names this file's ADDRESS - so a `.zip` still gets that comparison's own entry name - and not one rule was checked. Without `comparisonId` the write proceeds and the report opens with `NOT VALIDATED - comparison <id> answered, but its tree could not be read`. With `comparisonId` the write is REFUSED, saying the comparison is registered but its tree could not be read: you asked for a checked file, and this state ends by itself, so the action is to wait rather than to hunt for another id.
- **A comparison answered with a FINISHED tree.** Every rule is checked against the rules its own node allows before anything is written, and the report opens with `Validated against comparison <id>`. A rule the node does not allow is refused naming the node, the rule and the allowed set; a node the comparison offers no rule on at all is refused as that, not as an empty set.

**Why an unfinished tree is never used to refuse a RULE.** The comparison tree is built lazily, so while it is still building a node that has not been compared yet looks exactly like a node the comparison does not have. A check that could not run at all - the comparison failed instead of answering - is reported as a failed check, and nothing is written.

In every one of the three, nothing is written until EVERY decision has passed: one bad decision in a batch leaves the file untouched, because a half-applied set is a file nobody chose.

## Which container to write

EDT reads merge settings from two containers, and WHICH ones it reads depends on its version:

| Container | EDT 2026.1 | EDT 2026.2 | Addressed to |
| --- | --- | --- | --- |
| `.zip` | read | read | the exact STRING `<main>_<other>_<ancestor>` over the three project names |
| `.xml` | read | **refused** (`Can read merge settings from a zip file`) | nobody - any comparison reads it |

- **A `.zip` needs a live comparison.** EDT restores the entry whose name, minus its extension, equals `<mainProject>_<otherProject>_<ancestorProject>` of the comparison being launched, and IGNORES an archive that holds no such entry: it applies nothing and reports nothing. Those names come from the comparison's own descriptors, so a `.zip` target with no comparison to name it is REFUSED here rather than filled in with a guess. Start the comparison with `compare_configurations` first; naming it with `comparisonId` gets the rules checked as well.
- **The address is a STRING - not one comparison run, and not a set.** EDT restores the entry whose name equals `<mainProject>_<otherProject>_<ancestorProject>` for the comparison being launched. A later comparison over the same three projects - other revisions, another day - finds the same entry and applies these decisions again; that is the risk to watch: stale decisions re-applied, not decisions silently dropped. Two properties of that string matter before you rely on it. The three names are POSITIONAL, so swapping main and other spells a different entry. And `_` is a legal character in a project name, so the mapping is **not one-to-one**: main `A_B` with other `C` spells the same entry as main `A` with other `B_C`. Only one direction can be relied on - a comparison whose own three names spell a DIFFERENT string finds nothing here - and nothing promises that no other comparison can reach the file. The file's own NAME is yours to choose; only the entry name and the lower-case `.zip` matter.
- **An `.xml` needs nothing** and is read by any comparison - but only on EDT 2026.1. Every `.xml` this tool writes says so in its report.
- **On EDT 2026.2 the useful path is TWO runs**, because the only container that version reads is the one that needs a live comparison:
  1. `compare_configurations` - start a comparison and keep its `comparisonId`;
  2. wait until that comparison has FINISHED - `get_job_status` on the job it returned, and `get_comparison_node` says whether the tree can be read. This step is not optional if you pass `comparisonId`: naming a comparison whose tree is still building is REFUSED. Dropping `comparisonId` skips the wait at the price of a `NOT VALIDATED` report; the zip is addressed either way;
  3. `merge_rules` with `mode: "write"`, a `.zip` `filePath` and that `comparisonId` - the entry is now addressed AND every rule is checked;
  4. `compare_configurations` with `releaseComparisonId` - free EDT's single slot;
  5. `compare_configurations` again over the same projects, with `mergeRulesFile` pointing at the zip.
- READING either is fine on any version, and unchanged.

## Output
Markdown.
- read: the source (naming the zip ENTRY when the file was a zip), the format version, the decision count, how many blocks are carried through uninterpreted, and a table `# | Node | Level | Main | Other | Ancestor | Rule | Order side`. `Level` is `root` / `collection` / `object` / `member`; the three name columns are filled only for an object row, where an absent side shows `(absent)`.
- write: the validation line described above, a `Container:` line saying which file was produced and which EDT reads it, the counts (recorded / new / replaced / in the file now), and the decisions as written. When `basedOn` was given, the `Based on:` line says how many of the decisions that document already held KEPT THE RULE THEY ARRIVED WITH - a decision this call wrote at a path the starting document already carried is counted as replaced, not as kept, so the two numbers never describe the same decision twice.

## Gotchas
- **Neither container is a safe default; see *Which container to write*.** A `.zip` does nothing in a comparison whose own three project names spell a different entry (EDT skips an archive that holds no entry under the name it looks for, with only a warning in the log) and applies its decisions again in every comparison that spells the SAME one - which is not quite "the same three projects", because `_` is legal inside a project name; an `.xml` does nothing at all on EDT 2026.2 (its reader takes a zip alone). The report names the one that was written, and a `.zip` with no comparison to address it is refused rather than written blind.
- **The extension is compared exactly where EDT opens the file, and nowhere else.** `rules.ZIP` is refused as a `write` target and as `compare_configurations`'s `mergeRulesFile`, because no supported EDT reads that name - 2026.2 asserts `"zip".equals(FileUtil.getExtension(path))`. `mode: "read"` and `basedOn` are lenient about case: those files are opened here, never by the platform, so refusing them would refuse a file that reads perfectly well.
- **Below the object, keys are POSITIONS, not names.** A collection element is keyed by the position it would have after the merge, and that number moves as soon as another rule changes. Such rows are reported (`member`) and preserved on rewrite, but never authored: a rule written there would land on whatever ends up at that position. Set the rule on the object and refine it in the comparison window.
- **A key is not an address on its own.** Sibling members under different owners share their last segment, so a decision is only ever written with its whole chain. The read table prints the chain for the same reason.
- **`CustomMerge` and `MergeUsingExternalTool` are refused unconditionally**, even when a node allows them. Both name a merge whose actual content is configured elsewhere - the custom merge carries its own nested settings, the external-tool merge names the tool - so writing the bare literal would record a decision nobody made here.
- **A rewrite is lossless.** `Properties` maps, `Correspondences`, nested sections, XML comments and processing instructions (inside the document and in its prolog and epilog alike), and any attribute a future EDT adds are re-emitted verbatim; the file is re-serialized with a fixed layout (UTF-8, LF, two-space indent), which no reader keys on.
- **A file that uses an XML namespace is refused, and that is what makes the rewrite lossless.** The format declares none - EDT's own serializer never writes a namespace and its reader keys on local names - so a prefix only ever arrives by hand or from a block pasted in. Such a file cannot be carried through: a declaration is not an attribute and cannot be written back, a prefixed element comes back without its prefix, and two attributes differing only by their prefix collapse onto one name, the second destroying the first. It is refused naming what was found rather than read and silently mangled; save the settings again from the comparison window, or remove the foreign block, and read it again.
- Only `Format_version="2.0"` is read - the same version EDT's own reader accepts. Another version, or a file that is not merge settings, is refused naming what was found.
- **16 MB is the ceiling on what is written as well as on what is read.** A document that would serialise past it is REFUSED before the target is touched - the file already on the path keeps its decisions, and a fresh path is left free - rather than written into a file this tool could never read again or update in place. For a `.zip` the bound is on the entry as it EXPANDS, which is the same count the reader makes, not on the compressed archive. A real merge-settings file records one line per decision somebody made, so meeting this bound means checking what the document actually holds.

## Examples
- Take the vendor's version for every common module, keep our own for one renamed catalog:
  `{mode: "write", filePath: "C:/tmp/rules.xml", decisions: [{path: ["commonModules"], rule: "GetFromOther"}, {path: ["catalogs", "Alpha:Beta:Gamma"], rule: "MergePrioritizingMain"}]}`
- Decide the whole configuration at once, then refine one collection:
  `{mode: "write", filePath: "C:/tmp/rules.xml", decisions: [{path: [], rule: "DoNotMerge"}]}` followed by
  `{mode: "write", filePath: "C:/tmp/rules.xml", basedOn: "C:/tmp/rules.xml", decisions: [{path: ["documents"], rule: "GetFromOther"}]}`
- Author a file EDT 2026.2 will read - which needs the comparison it is for, and its tree FINISHED before `comparisonId` is passed:
  `{mode: "write", filePath: "C:/tmp/rules.zip", comparisonId: "<the id compare_configurations returned>", decisions: [{path: ["commonModules"], rule: "GetFromOther"}]}`
- Read what a comparison saved: `{mode: "read", filePath: "C:/tmp/MyConfig_Vendor_Base.zip"}`
- Validate against a running comparison whose tree has FINISHED: add `comparisonId: "<the id compare_configurations returned>"` to any write - naming a comparison whose tree cannot be read is REFUSED, not degraded.

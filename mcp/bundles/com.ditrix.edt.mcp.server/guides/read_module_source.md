Reads a BSL module's source from an EDT project, either the whole file or a specific line range, and returns it as clean source (no line-number prefixes) inside a fenced bsl block, preceded by YAML frontmatter.

## When to use

- To read a module before editing it, and to obtain the `contentHash` revision token that `write_module_source` accepts as `expectedHash` to guard against a lost update.
- For a structural overview (procedures, functions, regions) rather than raw text, prefer `get_module_structure`. For a single method body, prefer `read_method_source`.

## Parameter details

- `projectName` (string, required): EDT project name.
- `modulePath` (string, required): path from the `src/` folder, e.g. `CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ObjectModule.bsl`.
- `startLine` (integer, optional): 1-based inclusive first line; omit to read from the beginning.
- `endLine` (integer, optional): 1-based inclusive last line; omit to read to the end.

## Output

YAML frontmatter followed by a fenced `bsl` block. Frontmatter fields:

- `projectName`, `module`: echo of the inputs.
- `contentHash`: an opaque whole-file revision token. It is computed over the WHOLE file (not just the returned range) from the same canonical, `\n`-normalized text that `write_module_source` recomputes, so a write carrying this exact `expectedHash` matches as long as nothing changed on disk. Whole-file even for a range read, because a write targets the whole module. Omitted only when no token is available.
- `startLine`, `endLine`: the 1-based inclusive range actually returned.
- `totalLines`: total line count of the file.
- `truncated: true`, `nextStartLine`, `hint`: present only when the requested range was clamped by the configured line limit (the `maxLines` tool parameter, default 500). `nextStartLine` is the line to resume from.

## Continuation (large files)

When `truncated: true`, call this tool again with the same `projectName` and `modulePath` and `startLine` set to the `nextStartLine` value to fetch the next chunk. Repeat until `truncated` is absent.

## Empty file

For an empty file, `startLine`/`endLine` are omitted, `totalLines` is `0`, and the bsl block is empty.

## Examples

- Whole module: `{ "projectName": "MyProject", "modulePath": "CommonModules/MyModule/Module.bsl" }`
- Lines 100-150: add `"startLine": 100, "endLine": 150`.
- From line 200 to the end: add `"startLine": 200` only.

## Notes

- The returned source is clean BSL with no line-number prefixes; line numbers live in the frontmatter only.
- The source may contain Cyrillic identifiers (procedure/variable names); this tool returns the text verbatim and is not dialect-aware.
- To round-trip a safe edit: read here, take `contentHash`, then pass it as `write_module_source`'s `expectedHash`.

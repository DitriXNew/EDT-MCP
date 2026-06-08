The "what is this?" tool for a point in BSL code - it gives you the same hover tooltip EDT shows when you mouse over an identifier: the inferred type(s), the signature, and any documentation. Point it at a line/column and it tells you what the symbol there actually is.

## When to use
- You see a variable or expression and need its inferred type before writing code that uses it.
- You want a method's signature/return type, or the doc-comment, without opening the module.
- To disambiguate an identifier (which object/method does this name resolve to here?).

## Parameter details
- `projectName` (required) - the EDT project.
- `modulePath` (required) - the module from `src/`, e.g. `CommonModules/MyModule/Module.bsl`. (`filePath` is a deprecated alias.)
- `line`, `column` (required) - 1-based position of the symbol you are asking about.

## What you get
Markdown describing the symbol at that position: inferred type(s), signature, and documentation when available - mirroring EDT's hover. If hover data isn't available it falls back to structural model analysis.

## Notes & gotchas
- `line` and `column` are **1-based** and must point at the identifier itself; an empty/whitespace position yields nothing useful.
- You need an accurate position first - get line numbers from `get_module_structure` or `read_method_source` (its front-matter carries the method's start line), or from `search_in_code`.
- This reports the type *at a position*; to jump to where a symbol is defined use `go_to_definition`, and to find all its usages use `find_references`.

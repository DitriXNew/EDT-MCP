Use this when the lean tools/list entry is not enough — it returns the same guide a client gets from the resource guide://<toolName>. Pass the exact tool name (run it on itself: toolName=get_tool_guide) to see a worked example. An unknown name returns an error pointing back to tools/list.

## Reading a large guide

Pass exactly one optional selector, or omit all selectors for the full guide:

- `mode="summary"`: tool description, parameter table, and a table of the guide's `##` headings and anchors. `mode="full"` is the default full text.
- `section="Setting the data type"`: one whole section, including nested subsections, up to the next `##` or `#` heading. An exact heading or an anchor such as `#setting-the-data-type` is accepted, case-insensitively.
- `query="predefined"`: every matching `##` section in document order. Search is literal and case-insensitive over headings and bodies; complete sections are returned, including examples.

The selectors `mode`, `section`, and `query` are mutually exclusive, including explicit `mode="full"`. Blank queries are rejected. A query with no matches returns the available headings; an unknown section returns an error with those headings.

Headings inside backtick or tilde code fences are ignored. Nested headings stay inside their enclosing section. Duplicate headings select the first occurrence by title; use the unique anchor from the summary to select a later occurrence. A matching `#anchor` takes priority over a title with the same literal text.

## Examples

```json
{"toolName":"modify_metadata","mode":"summary"}
{"toolName":"modify_metadata","section":"Setting the data type"}
{"toolName":"modify_metadata","query":"predefined"}
```

The default call returns the original full renderer output unchanged. The resource `guide://<toolName>` always serves the full guide.

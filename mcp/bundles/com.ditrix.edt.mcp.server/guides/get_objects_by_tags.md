Lists the metadata objects that carry any of the given tags - the filter counterpart of `get_tags`. Use it to pull up everything you labelled "Important", "NeedsReview", etc.

## When to use
- You tagged objects and now want the list back (e.g. everything marked for review).
- Working through a tagged set of objects as a worklist.

## Parameter details
- `projectName` (required) - the EDT project.
- `tags` (required) - array of tag names, e.g. `["Important", "NeedsReview"]`. Returns objects that have **ANY** of these tags (union, not intersection).
- `limit` - max objects listed per tag (default 100, max 1000).

## What you get
Markdown grouped by tag: each tag's color, description, and object count, then a table of object FQNs. Tag names you passed that don't exist are listed under "Tags not found", and a summary totals the objects across the found tags.

## Notes & gotchas
- Matching is per exact tag name - get the names from `get_tags` first; misspelled names land in "Tags not found" rather than erroring the whole call.
- Results are a union across the requested tags; an object with two of the tags still appears once per tag group it matches.
- The returned values are object FQNs - feed them into `get_metadata_details`, `go_to_definition`, etc.

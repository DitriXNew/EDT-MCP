# remove_breakpoint — how to test

**Purpose.** Remove a breakpoint — by `breakpointId` or by the coordinates `projectName+module+lineNumber`.

**Precondition.** A breakpoint exists; take the current id from `list_breakpoints`.

**Call (real):**
```
remove_breakpoint(breakpointId=1867)
```

**Expected result:**
```json
{"removed":true,"success":true}
```

**Gotchas.**
- Removing a **non-existent/stale id** is not an error but `{"removed":false,"success":true}` — in a run `remove_breakpoint(1863)` (the old id from line 5) returned `removed:false`, because the break already lived under id 1867. **First `list_breakpoints`, then remove by the actual id.**
- An alternative is removal by coordinates (`projectName`+`module`+`lineNumber`), useful when the id is unknown.

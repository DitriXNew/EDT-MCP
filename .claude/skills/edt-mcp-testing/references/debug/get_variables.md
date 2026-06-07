# get_variables — how to test

**Purpose.** Read the variables of a suspended thread's stack frame. Pass `frameRef` (from `wait_for_break`, preferred) or `threadId`+`frameIndex`; `expandPath` expands a nested value.

**Precondition.** A thread is suspended; `frameRef` from `wait_for_break`.

**Call (real) — at the breakpoint (line 9, before execution):**
```
get_variables(frameRef=3)
```
**Result:**
```json
{"success":true,"count":3,"variables":[
 {"name":"Greeting","type":"String","value":"\"Debug e2e OK\"","hasChildren":false},
 {"name":"Sum","type":"Number","value":"42","hasChildren":false},
 {"name":"Total","type":"Undefined","value":"Undefined","hasChildren":false}]}
```
**After `step over` (frameRef changed to 5, line 9 executed):**
```json
{"variables":[ ...,{"name":"Total","type":"Number","value":"420","hasChildren":false}],"count":3}
```

**Gotchas.**
- **The stop semantics are BEFORE the breakpoint line executes:** at line 9 `Total` is still `Undefined`; the value appears only after `step`. This is the correctness check (the change `Undefined → 420` is visible).
- `frameRef` is valid for a specific snapshot; after `step`/`resume` take the NEW `frameRef`/`topFrameRef` from the `step`/`wait_for_break` response.
- For structures/collections `hasChildren:true` — expand with `expandPath` (dot path).

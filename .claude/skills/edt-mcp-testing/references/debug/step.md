# step — how to test

**Purpose.** Step a suspended thread: `kind ∈ {over, into, out}`. Blocks until the next SUSPEND and returns a new frame snapshot.

**Precondition.** A thread is suspended; `threadId` from `wait_for_break`.

**Call (real):**
```
step(threadId=2, kind="over")
```
**Result:**
```json
{"success":true,"hit":true,"threadId":4,"topFrameRef":5,"threadName":"Thin client",
 "applicationId":"attach:1C Enterprise debug process",
 "frames":[{"frameIndex":0,"frameRef":5,"name":"ManagedApplicationModule.OnStart() line: 10","line":10}]}
```
Check: stepped from line 9 to line 10; after this `get_variables(frameRef=5)` shows `Total=420` (line 9 executed).

**Gotchas.**
- The response carries a **new `threadId`/`frameRef`** (here 2→4, 3→5) — use them for the following `get_variables`/`step`/`resume`; the old ones may go stale.
- `over` — without entering calls; `into` — inside; `out` — until exiting the current procedure.
- On a timeout (if the step ran into a long execution) it returns without a new suspend — handle it like `wait_for_break`.

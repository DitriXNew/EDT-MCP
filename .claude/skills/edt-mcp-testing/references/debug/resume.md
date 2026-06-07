# resume — how to test

**Purpose.** Lift the suspension: by `threadId`, by `applicationId` (all of the target's threads), or with no arguments — the single active debug launch.

**Precondition.** A thread is suspended.

**Call (real):**
```
resume(threadId=4)
```
**Result:**
```json
{"success":true,"scope":"thread","resumed":true}
```
After `resume` the thread continues execution (in a run `OnStart` finished and the client started). If the breakpoint remains — it is caught again on the next pass; for a clean finish remove it (`remove_breakpoint`).

**Gotchas.**
- `scope` shows what was resumed — `thread` (by `threadId`) or the whole target (by `applicationId`).
- With no arguments it resumes the single active debug launch; with several — pass an explicit id.
- To avoid catching the same breakpoint again during the client's further work — remove it before/after resume.

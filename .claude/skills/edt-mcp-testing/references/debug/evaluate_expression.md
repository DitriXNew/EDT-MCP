# evaluate_expression — how to test

**Purpose.** Evaluate a BSL expression in the context of a suspended frame. ⚠️ Runs ARBITRARY BSL in a live 1C — beware of side effects.

**Precondition.** A thread is suspended; `frameRef` from `wait_for_break`/`step`.

**Call (real):**
```
evaluate_expression(frameRef=5, expression="Sum * Total + StrLen(Greeting)")
```
**Result:**
```json
{"success":true,"type":"Number","value":"17 652"}
```
Checks arithmetic / access to the frame's locals: `Sum=42`, `Total=420`, `StrLen("Debug e2e OK")=12` → `42*420+12 = 17652` (a number with a digit-group separator in the platform output — `"17 652"`).

**Gotchas.**
- The expression sees the local variables of the current frame (take the `frameRef` of exactly the frame you are inspecting).
- This is real code execution in the application — do not run mutating/long-running expressions on production.
- The number format is the platform's (a digit-group separator is possible), so compare by meaning, not byte-for-byte by string.

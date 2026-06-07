# wait_for_break — how to test

**Purpose.** Wait for a SUSPEND (a breakpoint firing) and return a thread/frame snapshot (`threadId`, `frameRef`). On a timeout — `{hit:false}`, and the launch is NOT terminated.

**Precondition.** A debug session is running with an active breakpoint (see [SETUP](SETUP.md)). `applicationId` may be omitted — if exactly one debug session is active, it is resolved automatically.

**Call (real):**
```
wait_for_break(timeout=5)
```

**Expected result (the break fired):**
```json
{"success":true,"hit":true,"threadId":2,"topFrameRef":3,"autoResolved":true,
 "applicationId":"attach:1C Enterprise debug process","threadName":"Thin client",
 "frames":[{"frameIndex":0,"frameRef":3,"name":"ManagedApplicationModule.OnStart() line: 9","line":9}]}
```
`autoResolved:true` confirms that auto-resolve took the **debug** launch. Remember `threadId` (for `step`/`resume`) and `frameRef` (for `get_variables`/`evaluate_expression`).

**Gotchas.**
- On a timeout `{"hit":false,"reason":"timeout"}` — this is NOT an error; call again to keep waiting. The launch is not killed.
- If several debug launches are active — auto-resolve returns an ambiguity; pass `applicationId` explicitly.
- Auto-resolve ignores RUN launches (they do not suspend) — so a lone RUN session no longer "hijacks" auto-resolve.

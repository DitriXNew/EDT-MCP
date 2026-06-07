# set_breakpoint — how to test

**Purpose.** Place a line breakpoint on a BSL module (by the EDT path from `src/` or an absolute path).

**Precondition.** A live EDT + an open project. Code to stop on for the full cycle — see [SETUP](SETUP.md); but breakpoint resolution itself is verified even without a launch.

**Call (real):**
```
set_breakpoint(projectName="TestConfiguration",
               module="Configuration/ManagedApplicationModule.bsl",
               lineNumber=5)
```

**Expected result:**
```json
{"breakpointId":1863,"success":true,
 "module":"Configuration/ManagedApplicationModule.bsl","lineNumber":5,
 "resolvedFile":"/TestConfiguration/src/Configuration/ManagedApplicationModule.bsl"}
```
The key check is that `resolvedFile` points into `src/<module>` (the source-folder resolver). Also verified on `module="CommonModules/Error/Module.bsl", lineNumber=1` → `resolvedFile:"/TestConfiguration/src/CommonModules/Error/Module.bsl"`.

**Gotchas.**
- `module` accepts both an EDT path from `src/` and an absolute path; `projectName` is required for the module-relative form.
- The file is resolved through the shared `BslModuleUtils.resolveModuleFile` (first `src/`, then a fallback over other top-level folders).
- A breakpoint can be placed even if the line never executes; it "fires" only when execution actually reaches it (a trigger is needed — see SETUP).

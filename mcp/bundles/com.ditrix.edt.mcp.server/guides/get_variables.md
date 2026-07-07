Reads the variables visible in a stack frame of a suspended debug thread - the "inspect state at the breakpoint" tool. Drill into nested structures (Структура, Соответствие, Массив, object properties) on demand instead of dumping everything at once.

## When to use
- You are suspended at a breakpoint (via `wait_for_break`) and want to see local/parameter values.
- Expanding a complex value to inspect its children.

## Parameter details
Identify the frame one of these ways:
- `frameRef` (preferred) - the stable frame reference from `wait_for_break` / `step`.
- `threadId` + `frameIndex` - a thread id plus a 0-based frame index (re-resolved against the live thread).
- Pass neither and, if exactly one debug launch is suspended, its top frame is used.

Then optionally:
- `expandPath` - a dot-separated path to expand a nested variable and return its children instead of the frame's top-level variables.

## What you get
JSON: `variables` (each with `name`, `value`, `type`) and a `count`. Long values are truncated; container values report having children you can reach via `expandPath`.

## Notes & gotchas
- **`frameRef`s go stale after every `step` or `resume`** - always use the freshest one; a stale ref returns "call wait_for_break again".
- Start from the frames in the `wait_for_break` / `step` response, then use `expandPath` to dig into a specific structure rather than expanding everything.
- To evaluate an arbitrary BSL expression (not just read a variable) in the same frame, use `evaluate_expression`.

## Personal-data (152-FZ) redaction
`get_variables` is one of the tools flagged **`returnsInfobaseData`**: its result can carry real personal data read out of a running infobase (full names, tax / insurance / passport numbers, e-mail, phone, etc.). Sending that to an LLM verbatim is unacceptable for a production infobase under Russian personal-data law (Federal Law 152-FZ). The server therefore ships an optional **PII redactor** that runs at the single wire-serialization choke point, on the response of every `returnsInfobaseData` tool, BEFORE it leaves the server.

- **Flagged tools (v1):** `get_variables`, `evaluate_expression`, `wait_for_break`. (`get_event_log` from #243 gets flagged once it merges - it is not in this build.)
- **Toggle:** Window -> Preferences -> MCP Server, the redaction checkbox. **Default OFF** - the user decides. While OFF the response is **byte-identical** to a build without the redactor (the choke point returns the same string unchanged - no JSON parse / re-serialize), so goldens and the existing e2e stay unaffected.
- **Env override:** `EDT_MCP_PII_REDACTION`, read at the EDT / CI launch, **wins over the preference** (the unattended / CI bypass, mirroring `EDT_MCP_DESTRUCTIVE_CONSENT`). It is bidirectional: an `on`-family value (`on`/`true`/`1`/`yes`/`enabled`) forces redaction **ON**; an `off`-family value (`off`/`false`/`0`/`no`/`disabled`) forces it **OFF**; any other / unset value defers to the preference.
- **What it does when ON:** dictionary (JSON key / variable NAME) + content-regex (value) detection over the JSON result; a hit is replaced with a stable pseudonym (same input -> same token, no reverse table) or, for special-category / biometric data, fully masked. Each redaction is audited (count + sensitivity, never the value or the token->value mapping) to the plugin log.
- **Known v1 gap (free-form names in `evaluate_expression`):** the dictionary layer keys off a JSON KEY / sibling variable `name`, but an `evaluate_expression` success payload is `{success, value, type, truncated, fullLength}` with **no sibling `name`**, so only the content-regex layer applies to its `value`. A bare personal NAME (e.g. `evaluate_expression` of `Сотрудник.ФИО` -> `Иванов Иван Иванович`) matches none of the structured content patterns (e-mail / phone / СНИЛС / passport / ИНН), so in v1 it is returned **verbatim even with redaction ON**. The SAME ФИО read as a `get_variables` local IS redacted, because it carries a sibling `name` the dictionary matches. So in v1 a free-form name in `evaluate_expression` is only redacted when the returned value itself carries a structured pattern; closing the free-form-name gap needs the metadata catalog below.
- **Deferred to v2:** a per-project PII catalog built from the metadata model (attribute types / synonyms) — this is what closes the free-form-name gap above (it can flag an attribute like `ФИО` as personal data even when the value has no structured shape). v1 uses the dictionary + regex only, so the redactor never runs a BM read on the debug hot path.

# ask_workmate

Start a background question to the 1C:Workmate plugin and return its jobId. Poll the job with get_job_status instead of calling ask_workmate again. Requires a compatible Workmate installation in the same EDT JVM. Full parameters and examples: call get_tool_guide('ask_workmate').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| question | — | string | Non-empty question or instruction to send to 1C:Workmate. Required unless workmateTool selects direct tool mode. |
| projectName | — | string | Optional open EDT project name used as Workmate's context. Omit to use Workmate's default project context. |
| maxToolRounds | — | integer | Optional positive limit for Workmate's internal tool-call rounds; it applies per assistant turn, so a conversation continued to reach a final answer spends it again on each turn. |
| skillName | — | string | Optional Workmate skill name. Omit to use 'custom', the skill under which Workmate runs its own tool loop; Workmate's plain 'raw' skill answers from the model alone and inspects nothing. |
| timeoutSeconds | — | integer | Total wall-clock budget for the background job across all get_job_status polls, in seconds; defaults to 300 and accepts 1 to 3600. After this budget the job is failed - unless the request has already reached Workmate, which cannot be taken back: the job then reports Workmate's own outcome rather than a retryable timeout, because a retry would run the same work twice. This is not the per-call waitSeconds budget. |
| waitSeconds | — | integer | Maximum time this start call may wait for completion before returning its job snapshot, in seconds; defaults to 5, accepts 0 to 45. Use 0 to return immediately. This does not extend the job's total timeoutSeconds budget. |
| workmateTool | — | string | Exact name of a Workmate tool to invoke directly, e.g. 'JShellSession', 'JShellManual' or 'JShell'. Passing this parameter selects the direct tool mode by itself: question and mode are not used, and no language model is involved, so the tool either runs or returns its own error. |
| workmateArgs | — | string | Direct tool mode only: JSON OBJECT with that tool's arguments, e.g. {} or {"scope":"eclipse","code":"..."}. Defaults to an empty object. |
| shareMcpTools | — | boolean | When true, the question is prefixed with instructions that let Workmate call EDT-MCP's own tools through this plugin's in-process bridge, so it can inspect the real project instead of answering from general 1C knowledge. Defaults to true for mode 'answer' and to false for mode 'chat', where the project's own .workmate rules already carry the same instructions; pass true there for a project that has no such rules. |
| mode | — | string | 'answer' (default) runs Workmate's tool loop and RETURNS its answer as text: it inspects the project with its own tools and, through this plugin's bridge, with EDT-MCP's, so it can also change code and metadata. 'chat' hands the same question to Workmate's agentic chat instead; the work happens there and its answer is rendered in the EDT chat panel for a human, so it is NOT returned here. Prefer 'answer' unless a human should continue the conversation in the panel. |

## Guide
## Parameter details

- `question` starts a new background conversation and must contain
  non-whitespace text. It is required unless `workmateTool` selects direct tool
  mode.
- `projectName` applies only when starting. When present, it must name an open
  EDT project; use `list_projects` to discover valid names. When omitted,
  Workmate receives its `ProjectId.Default` context.
- `maxToolRounds` applies only when starting and optionally limits Workmate's
  internal tool-call rounds. It must be a positive integer. Omit it to use
  Workmate's own default. The limit is **per assistant turn**, which is how the
  platform itself uses it - Workmate's own autopilot passes the same value into
  every turn of its conversation loop - so a conversation continued to reach a
  final answer (see below) may spend the allowance again on each continuation.
  Workmate reports no tool-round count back, only an assistant-message count, so
  a remaining-rounds budget cannot be tracked across turns without inventing it;
  bound the whole job with `timeoutSeconds` instead.
- `skillName` applies only when starting and optionally selects a Workmate skill.
  Omit it: this tool then sends `custom`, the skill under which Workmate runs its
  own tool loop. Workmate's `raw` skill is NOT the default here and is worth
  knowing about only to avoid it - under `raw` the cloud answers from the model
  alone, calls no tools and inspects nothing.
- `timeoutSeconds` is the total wall-clock budget for the background job across
  every `get_job_status` poll. It must be positive and defaults to 300 seconds. When this budget
  expires, the job becomes `failed` - unless the request has already reached
  Workmate, which cannot be taken back: the job then waits for Workmate's own
  outcome instead, because a retryable timeout would invite a second run of
  work that is already under way.
- `waitSeconds` bounds only the initial `ask_workmate` call. It defaults to 5 and
  accepts values from 0 through 45; use 0 to return immediately with the job id.
  Later waits belong to `get_job_status`. Neither wait extends the job's total
  `timeoutSeconds` budget.
- `mode` applies only when starting and selects what happens to the question.
  `answer` (the default) runs Workmate's tool loop and returns its answer as
  text: it inspects the project with its own tools and, through this plugin's
  bridge, with EDT-MCP's, so it can also change code and metadata. `chat` hands
  the same question to Workmate's agentic chat instead; the work happens there
  and the answer is rendered in the EDT chat panel for a human, so it is **not**
  returned through MCP - the job completes with a handoff note. Prefer `answer`
  unless a human should carry the conversation on in the panel.
- `shareMcpTools` prefixes the question with the instructions Workmate needs to
  call EDT-MCP's tools through this plugin's in-process bridge. It defaults to
  true for `answer`, where nothing else would tell Workmate the bridge exists,
  and to false for `chat`, which loads the project's own `.workmate` rules and
  already finds the same instructions there. Pass it explicitly for a chat on a
  project that carries no such rules.
- `workmateTool` runs one of Workmate's OWN tools directly, with no language
  model in the loop, so the tool either runs or returns its own error. Pass the
  exact tool name, for example `JShellSession`, `JShellManual` or `JShell`.
  Its presence selects this mode by itself: `question` and `mode` are not used,
  and there is no `mode="tool"` value.
- `workmateArgs` carries that tool's arguments as a JSON object, for example
  `{}` or `{"scope":"eclipse","code":"..."}`. Defaults to an empty object.

## Examples

Start without a project context and return immediately:

```json
{
  "question":"Explain why this 1C query may be slow",
  "maxToolRounds":3,
  "waitSeconds":0
}
```

Poll the returned job through the shared polling surface:

```json
{"tool":"get_job_status","arguments":{"jobId":"<id returned by ask_workmate>","waitSeconds":5}}
```

Start in one EDT project's context and wait briefly for a fast answer:

```json
{
  "question":"Review the current project structure and suggest the next refactoring",
  "projectName":"MyConfiguration",
  "timeoutSeconds":300,
  "waitSeconds":5
}
```

Run one of Workmate's own tools with no model involved (here: create a JShell
session whose id another call can reuse):

```json
{"workmateTool":"JShellSession","workmateArgs":"{}","waitSeconds":45}
```

## Runtime requirements and safety

The tool requires a compatible 1C:Workmate installation in the same EDT JVM.
EDT-MCP does not compile against Workmate and does not add the 1C repository to
its target platform; the integration is discovered at runtime through OSGi and
reflection. A missing or changed Workmate installation is returned as an
actionable `failed` job status, not as an escaped exception. Poll any returned
job id with `get_job_status`; an unknown or expired id is reported there with
instructions to start a new job with its owning tool.
Before sending, the adapter also checks Workmate's public `ISettings`: the plugin
must be enabled and `hasClientToken()` must report a configured access key.

The progress journal reports only stages actually reached by the adapter:
question accepted, plugin located, conversation facade obtained, request sent,
each continuation, and response received or failure. When Workmate exposes its
assistant-message count, the completed result includes that value (summed over
every turn) without relabelling it as a tool-round count.

## Why a job can take several turns

One call into Workmate's conversation facade answers ONE assistant turn: its
future completes when that turn's stream ends, which happens on "I will look it
up in the documentation" exactly as it happens on a finished answer. Reporting
that first turn is what made `mode="answer"` return a plan - or nothing at all -
instead of a result (#427).

So a turn that is empty, or short and phrased as an announcement of intent, is
not accepted as the answer: the same conversation is continued (up to five
times) with an instruction to answer the original question now — and to answer
from its own knowledge when the tool it wants is not in its toolset, which is
what keeps a model that asked for a documentation search from announcing that
search over and over. Continuing the conversation is exactly what Workmate's own
autopilot does with this facade. The
continuations are bounded by the job's `timeoutSeconds` budget, count into
`assistantMessages`, and are visible in the progress journal. A long answer is
always taken at face value, so a finished reference answer is never re-asked.

How a turn is judged finished, in order of authority:

1. **Workmate says so.** This adapter appends one instruction to EVERY request it sends -
   the caller's question and each continuation alike - asking Workmate to end a FINAL answer
   with the marker `<!end>`. A turn carrying it is final whatever it sounds like, in any
   language. The marker is stripped before the answer reaches you.
2. **Phrasing, as a fallback.** A turn that declared nothing is judged by whether it reads as
   an announcement of work ("I will search the documentation", «я воспользуюсь поиском»)
   rather than a result. This is a heuristic and knows only Russian and English.

A turn that goes SILENT for two minutes ends the conversation instead of holding the job
open: you get whatever Workmate produced so far. Silence, not elapsed time - a turn that is
working keeps its clock reset, and "working" means a call it started through this plugin's
bridge or one still running there, so a tool loop that legitimately runs for minutes is never
cut off.

Three limits of that rule, all deliberate. It sees only what comes back through this plugin, so
a turn busy in Workmate's own tools or in a long model request looks silent - it can be
wound up while it was in fact working, which is why the report says "no sign of work" rather
than claiming Workmate stopped. And while more than one `ask_workmate` job is in flight the
rule STANDS DOWN entirely, because a bridge call cannot be attributed to a conversation:
ending a turn on another job's silence would be worse than waiting. With several jobs, the
`timeoutSeconds` budget is the only bound.

The third follows from the same anonymity, in the other direction: a `mode="chat"` hand-off
completes its job at once and Workmate keeps working in the panel, where it may call these same
tools. That traffic is counted as activity, so an answer-mode turn that really has gone quiet
can be kept alive by an unrelated chat and run to its `timeoutSeconds` budget. The error is on
the safe side - a job waits rather than being cut short - but it cannot be cut short by hand
either: an answer-mode job commits before its first request goes out, so `cancel_job` reports
`alreadyCommitted` and the job runs until Workmate answers or the budget ends it. Stop polling
and read the result later; the slot frees itself.

Whenever the marker never arrived - because the conversation went quiet, or because the
continuations ran out - the result carries an explicit **"Completion not confirmed"** note
above the answer. Read that as "this is the last thing it said", not as "this is the answer".

What comes back is the last text that was ACCEPTED as an answer: an announcement is never
promoted to the result just because nothing better followed it, and a later empty turn never
erases an answer already produced. If no turn ever produced an answer, the job FAILS rather
than returning the plan - the error quotes what Workmate kept announcing and says what to
change (a narrower question, a larger `timeoutSeconds`).

Silence is the one case where that plan is handed back rather than dropped, because it says
where Workmate stopped. It is then labelled **"Not an answer"** on top of the "Completion not
confirmed" note: what you are reading is what Workmate said it was GOING to do, so treat that
work as possibly half-done and inspect the project. Any failure after a turn has been
dispatched carries the same warning - inspect Workmate and the project before repeating the
request, because those turns had already run and their tools may have changed something.

Workmate may contact its configured cloud service and its conversation loop may
invoke Workmate's own tools. Review the question and selected project/skill with
the same care as a direct Workmate chat request.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

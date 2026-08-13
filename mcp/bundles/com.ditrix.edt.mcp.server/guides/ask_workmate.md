## Parameter details

- `question` starts a new background conversation. It must contain
  non-whitespace text and is mutually exclusive with `jobId`.
- `jobId` polls a job started by an earlier call. Pass it without `question`.
  Poll responses include the current status, elapsed time, timestamped progress,
  and the answer/reasoning once ready.
- `projectName` applies only when starting. When present, it must name an open
  EDT project; use `list_projects` to discover valid names. When omitted,
  Workmate receives its `ProjectId.Default` context.
- `maxToolRounds` applies only when starting and optionally limits Workmate's
  internal tool-call rounds. It must be a positive integer. Omit it to use
  Workmate's own default.
- `skillName` applies only when starting and optionally selects a Workmate skill.
  Omit it: this tool then sends `custom`, the skill under which Workmate runs its
  own tool loop. Workmate's `raw` skill is NOT the default here and is worth
  knowing about only to avoid it - under `raw` the cloud answers from the model
  alone, calls no tools and inspects nothing.
- `timeoutSeconds` is the total wall-clock budget for the background job across
  every poll. It must be positive and defaults to 300 seconds. When this budget
  expires, the job becomes `failed`.
- `waitSeconds` bounds only the current start or poll call. It defaults to 5 and
  accepts values from 0 through 45; use 0 to return immediately. It never extends
  the job's total `timeoutSeconds` budget.
- `mode` applies only when starting and selects what happens to the question.
  `answer` (the default) runs Workmate's tool loop and returns its answer as
  text: it inspects the project with its own tools and, through this plugin's
  bridge, with EDT-MCP's, so it can also change code and metadata. `chat` hands
  the same question to Workmate's agentic chat instead; the work happens there
  and the answer is rendered in the EDT chat panel for a human, so it is **not**
  returned through MCP - the job completes with a handoff note. Prefer `answer`
  unless a human should carry the conversation on in the panel.
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

Poll the returned job:

```json
{"jobId":"<id returned by the start call>","waitSeconds":5}
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
actionable `failed` job status, not as an escaped exception. Unknown job ids and
invalid start/poll argument combinations are returned as actionable tool errors.
Before sending, the adapter also checks Workmate's public `ISettings`: the plugin
must be enabled and `hasClientToken()` must report a configured access key.

The progress journal reports only stages actually reached by the adapter:
question accepted, plugin located, conversation facade obtained, request sent,
and response received or failure. When Workmate exposes its assistant-message
count, the completed result includes that value without relabelling it as a
tool-round count.

Workmate may contact its configured cloud service and its conversation loop may
invoke Workmate's own tools. Review the question and selected project/skill with
the same care as a direct Workmate chat request.

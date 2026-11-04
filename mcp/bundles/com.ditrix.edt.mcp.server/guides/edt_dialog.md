Inspect application-modal SWT dialogs open in EDT and explicitly answer a known dialog.

## List, then press

Call `edt_dialog({"action":"list"})`. Each entry has `dialogId`, `title`, `message`,
`buttons`, and `answerable`. Copy an exact label, including mnemonic markers, into:

```json
{"action":"press","dialogId":"<id from list>","button":"Run without update"}
```

IDs belong to one visible shell lifetime. A closed, hidden, disposed or replaced dialog
invalidates its ID. A refusal requires listing again; never guess a new ID or button.
Disabled, hidden and ambiguous buttons cannot be pressed. After a press, list again to
inspect subsequent dialogs; a successful press does not mean the launch/update has finished.

## Scope and effects

The initial allowlist reuses EDT-MCP's existing English/Russian recognition for
**Application update** and **Infobase configuration changes**. Other dialogs carry
`answerable:false` and `reason:"ask the user to answer it in EDT"`. Destructive-consent
and user-signal dialogs are always human-only, even if their titles match the allowlist.

**Update then run** applies the same native update as `launch(updateBeforeLaunch=true)`.
That launch path does not invoke the destructive-consent gate. **Import** in the conflict
dialog rewrites project sources; **Override** overwrites external infobase changes.
Press only the choice the user requested. The tool never arms automatic confirmation.
Pressing cannot be undone by this tool; recover changed sources through your normal version
control workflow. Cancel or Run without update avoids applying that update.

Tool responses include a short dialog notice only while an observed modal shell is open;
`get_server_status` is unchanged. `launch(updateBeforeLaunch=false)` watches up to 750 ms
after dispatch when its application is not known to be UPDATED and returns `edtDialogs`
if a modal appears. UPDATED launches skip that window. Later dialogs are visible through
the notice or another list call. The notice reads cached UI events without waiting for SWT.

List and press wait at most 500 ms for the UI. A queued press is cancelled if its deadline
expires. Check EDT and list again before retrying. Editable/password text is not returned.
Native OS `MessageBox` windows are not SWT shells: they are neither listed nor answerable,
and cannot produce this notice. A blocked UI cannot refresh the cached shell state.

# push_git_branch

Push a project's git branch (or an explicit refspec) to a remote (headless JGit push). remote AND refspec are BOTH required with no defaulting (the no-autonomous-push guard); force is opt-in (default false). refspec accepts a short branch name (pushed to the same-named remote branch) or an explicit 'src:dst'. SSH auth is transparent (ssh-agent/~/.ssh); for HTTPS pass username/token or the push fails fast with an actionable error (never a login dialog). A non-fast-forward rejection is reported as an error, not success. Runs in a background Job (up to 120 s). Full parameters and examples: call get_tool_guide('push_git_branch').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project whose git repository to push from (required). |
| remote | yes | string | Target remote (required): a configured remote name (e.g. 'origin') or a URL. No defaulting - you must name it explicitly (the no-autonomous-push guard). |
| refspec | yes | string | Refspec to push (required): a short branch name (e.g. 'feature/x', pushed to the same-named remote branch) or an explicit 'src:dst' (e.g. 'refs/heads/feature/x:refs/heads/feature/x'). No defaulting - you must name it explicitly. |
| force | — | boolean | Force-push, overwriting the remote branch even on a non-fast-forward. Opt-in; default false. |
| username | — | string | Optional git username for an HTTPS remote. Omit for an SSH remote (ssh-agent/~/.ssh authenticate transparently). |
| token | — | string | Optional git password or personal-access-token for an HTTPS remote. Without it an HTTPS remote that needs credentials fails fast (never a login dialog). |

## Guide
Push a project's git branch (or an explicit refspec) to a remote via a headless JGit `PushCommand` - the non-UI sibling of EDT's own "Push" command. Use `list_git_branches` first to confirm the local branch, and know the remote name (e.g. `origin`) you intend to push to.

## The "no autonomous push" guard
Both `remote` and `refspec` are **required with no defaulting**, and `force` is opt-in (`false` by default). There is deliberately no path on which this tool pushes to an inferred/tracking remote, or force-overwrites a remote branch, without you having spelled out exactly that intent. (This is enforced by the tool contract - required params fail closed on every path - not by a consent dialog: the destructive-consent gate returns ALLOW on the unattended path, so it would not actually block anything here.)

## Parameters
- `remote` - a configured remote name (`origin`) or a URL. Required.
- `refspec` - a short branch name (`feature/x`, pushed to the same-named remote branch), or an explicit `src:dst` (`refs/heads/feature/x:refs/heads/feature/x`). Required. A short name is expanded to `refs/heads/<b>:refs/heads/<b>`; a `src:dst` is used verbatim.
- `force` - overwrite the remote branch even on a non-fast-forward. Opt-in; default `false`.
- `username` / `token` - optional, for an **HTTPS** remote (a username + personal-access-token). Omit both for an **SSH** remote.

## Authentication (no secret storage, no dialog)
- **SSH remote** - transparent. EGit's process-global SSH session factory (ssh-agent + `~/.ssh`) authenticates with no parameters here.
- **HTTPS remote** - pass `username` and `token`. If you don't, and the remote needs credentials, the push **fails fast with an actionable error** - it never opens a login/passphrase dialog (that would hang an unattended session). No credential is stored; the token is used only for this call.

## What happens on success
The push runs in a bounded background Job (up to 120 seconds) - never on the UI thread. A push changes no local file, so **no workspace refresh** is performed. On success:
```json
{ "success": true, "remote": "origin", "resolvedRefspec": "refs/heads/feature/x:refs/heads/feature/x",
  "forced": false, "pushed": true,
  "updates": [ { "remoteName": "refs/heads/feature/x", "status": "OK" } ] }
```
`updates` mirrors JGit's per-ref result; `status` is the `RemoteRefUpdate.Status` (`OK` = the ref moved, `UP_TO_DATE` = nothing to do). Both count as success.

## Rejection & failure modes (a rejected push is an error, never a swallowed success)
Every ref update is inspected; any status other than `OK`/`UP_TO_DATE` makes the whole push a failure:
- **REJECTED_NONFASTFORWARD** - the remote branch has commits you don't have. The error steers you to integrate them first (`pull_git_branch`) and push again, or to set `force=true` to intentionally overwrite the remote branch.
- **REJECTED_REMOTE_CHANGED / REJECTED_OTHER_REASON / NON_EXISTING / AWAITING_REPORT / NOT_ATTEMPTED** - reported with the remote's own message when present.
- **Remote not found** - `remote` did not resolve to a configured remote or a valid URL.
- **Connect/authenticate failure** - for HTTPS, supply `username`/`token`; for SSH, ensure ssh-agent holds an authorized key. No dialog is ever opened.
- **Timeout** - the push did not finish within 120 seconds (checked network connectivity / remote).

## Notes & gotchas
- **Not gated by the destructive-consent dialog** - the required explicit `remote`+`refspec` and opt-in `force` are the guard, not a human prompt.
- The project must be inside a git working tree (EGit-shared, or a project whose location sits inside a `.git` clone) - see `list_git_branches` for the resolution rules and its actionable error when neither applies.
- This tool pushes what is already committed locally - it does not stage or commit; use `commit_git_changes` first if you have pending work.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*

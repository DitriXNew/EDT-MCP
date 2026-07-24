Run a git command in a project's repository through the real `git` CLI - the non-UI equivalent of typing it in a terminal. You send the command as a shell-style string; the tool parses it, accepts only a safe whitelist of subcommands, and runs `git` with a clean argument vector (never through a shell).

## Parameters
- `projectName` (**required**) - the EDT project whose git repository to run in. The repository is resolved the same way as `list_git_branches` (an EGit-shared project or a plain `.git` checkout the project lives inside); EGit is not required.
- `command` (**required**) - the git command, shell-style. A leading `git` is optional. Quotes (`"..."` or `'...'`) group an argument that contains spaces (e.g. a commit message); the string is **not** run through a shell, so shell metacharacters (`;`, `|`, `$`, backticks) are ordinary literals, never operators.

## Enabled on demand (off by default)
This tool is **disabled by default** because it is powerful (it can push, checkout, stash, ...). Enable it in **Window → Preferences → MCP Server → Tools** (it has its own **Git** group). Until then it is not advertised in `tools/list`. (It is disabled at the tool level, so `enable_toolset` - the progressive-disclosure mechanism - does not turn it on; use the Tools tab.)

## Authentication & config are the machine's (trust boundary)
There is deliberately **no** credential handling here. `git` uses whatever the machine running EDT is configured with - your `ssh-agent` key, a git credential helper, `~/.gitconfig` - exactly like the terminal you already use. The tool sets `GIT_TERMINAL_PROMPT=0`, so a missing credential **fails fast** with git's own error instead of hanging. The tool itself stores no secret and a `scheme://user:password@host` URL is **rejected** (it would be persisted in config and appear in the MCP request history) - use your credential helper or an ssh key.

**What is hardened vs. what is trusted.** The tool hardens against COMMAND-STRING injection: no shell, an allowlisted subcommand set, a denied set of program-running / file-writing / repo-redirecting options (matched by exact name, `=value`, AND abbreviation, since git resolves unique prefixes), rejection of `ext::`/`fd::` transport-helper and `user:password@` URLs, safe transports (`GIT_ALLOW_PROTOCOL`), and a scrubbed set of `GIT_*` redirection/exec variables. Because abbreviations are matched conservatively, a legitimate option that merely shares a prefix with a denied one may be over-rejected (e.g. `--con` for `--contains`); pass the full option name.

It does **not** sandbox the machine or the repository's own configuration: the `git` executable is resolved from the machine's `PATH`; a repository's hooks, filters, aliases and merge drivers run with your privileges; and operand paths are not confined to the work tree - **exactly as they would in your terminal**. Only enable this tool for a machine and repositories you already trust to run git in, as you do when you `cd` into them yourself.

## Supported subcommands (whitelist)
A minimal, deliberately-small set - inspection and the dev loop:
`add`, `blame`, `branch`, `checkout`, `cherry-pick`, `commit`, `describe`, `diff`, `fetch`, `log`, `ls-files`, `merge`, `pull`, `push`, `remote`, `restore`, `rev-parse`, `revert`, `show`, `stash`, `status`, `switch`, `tag`.

Anything else is **rejected** with an actionable error naming the supported set. Deliberately excluded: `config` (it can set `core.sshCommand`/aliases = arbitrary code), `clean` / `gc` / `reset` (irrecoverable data loss), `rebase` (its `--exec`/`-x` runs a command per step), `init` / `clone` (repository bootstrap is out of scope), `submodule` / `worktree` / `filter-branch` / `daemon` / `credential`.

Also rejected wherever they appear: the `--upload-pack` / `--receive-pack` / `--exec` remote/step-program options, the `--config` / `--config-env` inline-config, the `--git-dir` / `--work-tree` / `--exec-path` / `--namespace` repository redirections, and `--ext-diff` / `--output` / `--help` / `--no-index` (external driver / arbitrary file write / man viewer / reading files outside the repo) - and any **global** option before the subcommand (e.g. `-c core.sshCommand=...`), since the first token must be a bare subcommand. Transports are restricted (`GIT_ALLOW_PROTOCOL`) to the safe set, so a `ext::` / `fd::` transport-helper remote (which would run an arbitrary command) is refused.

## Result
JSON: `{ "success": <exit==0>, "exitCode": <n>, "command": "git ...", "output": "<combined stdout+stderr>" }`. A non-zero `exitCode` (a rejected push, a merge conflict, ...) is `success: false` with git's own message in `output` - never a false success. `output` is capped (`truncated: true` when it was cut). The op is bounded to 120 seconds; a stalled command is killed with a timeout error.

## Examples
```
{ "projectName": "MyProject", "command": "status" }
{ "projectName": "MyProject", "command": "diff HEAD~1" }
{ "projectName": "MyProject", "command": "commit -m \"fix: handle empty input\"" }
{ "projectName": "MyProject", "command": "push origin main" }
{ "projectName": "MyProject", "command": "pull origin main" }
```

## Notes & gotchas
- **openWorldHint = true**: `push` / `pull` / `fetch` reach an external remote. The tool never opens or authenticates against a 1C infobase.
- Operates on the ON-DISK content. Save or `resync_to_disk` the EDT model before `commit` so your model edits are captured.
- The typed `list_git_branches` / `switch_git_branch` / `create_git_branch` tools remain available (and enabled) for branch work with 1C application binding; this `git` tool is the general-purpose escape hatch.

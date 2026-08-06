Run a git command in a project's repository through the real `git` CLI - the non-UI equivalent of typing it in a terminal. You send the command as a shell-style string; the tool parses it, accepts only a safe whitelist of subcommands, and runs `git` with a clean argument vector (never through a shell).

## Parameters
- `projectName` (**required**) - the EDT project whose git repository to run in. The repository is resolved the same way as `list_git_branches` (an EGit-shared project or a plain `.git` checkout the project lives inside); EGit is not required.
- `command` (**required**) - the git command, shell-style. A leading `git` is optional. Quotes (`"..."` or `'...'`) group an argument that contains spaces (e.g. a commit message); the string is **not** run through a shell, so shell metacharacters (`;`, `|`, `$`, backticks) are ordinary literals, never operators.

## Enabled on demand (off by default)
This tool is **disabled by default** because it is powerful (it can push, checkout, stash, ...). Enable it in **Window → Preferences → MCP Server → Tools** (it has its own **Git** group). Until then it is not advertised in `tools/list`. (It is disabled at the tool level, so `enable_toolset` - the progressive-disclosure mechanism - does not turn it on; use the Tools tab.)

## Authentication & config are the machine's (trust boundary)
There is deliberately **no** credential handling here. `git` uses whatever the machine running EDT is configured with - your `ssh-agent` key, a git credential helper, `~/.gitconfig` - exactly like the terminal you already use. The tool sets `GIT_TERMINAL_PROMPT=0` and `credential.interactive=false`, so a missing credential **fails fast** with git's own error instead of a terminal prompt or a credential-manager window. Credential URLs are also redacted from the tool's output (`https://***@host/...`), but that redaction is **best-effort**: it can only mask a URL it manages to parse, so it is not a promise that a token already stored in the repository config never comes back - where the masking cannot be trusted, the operation is **refused** instead (see the next paragraph). The tool itself stores no secret; a `scheme://user:password@host` URL, an `https://<token>@host` one and a remote URL with a **query string** (`...repo.git?access_token=…`) are both **rejected** (it would be persisted in config and appear in the MCP request history) - use your credential helper or an ssh key. A plain `ssh://user@host/repo.git` (or `git+ssh://`) is NOT a credential and is accepted - that is git's documented SSH remote form.

**A stored credential that cannot be masked makes the command fail, not leak.** When a remote in the configuration this repository resolves (`remote.<name>.url` or `remote.<name>.pushurl`) carries a credential whose **authority** - everything between `://` and the first `/` - contains an ASCII whitespace character, or a `?`/`#` that comes before the `@`, `remote`, `push`, `fetch` and `pull` are **refused** with an error that names the remote and the fix. Both end the output redaction's scan before the `@`, so what sits in front of them would be printed verbatim and masking cannot be trusted. A control character in the authority is refused alongside them for a different reason - it ends none of those scans and such a URL *is* masked correctly, but it can never be legitimate there and must not travel verbatim into the response. The refusal never echoes the URL or the config value; the remote's name is the one thing it quotes, and even that is withheld when the name could itself be a credential URL. Such a URL is rejected on INPUT too, so this tool cannot create that remote itself.

Repair it in your terminal: drop the remote and add it again pointing at a URL with no embedded credentials (`git remote remove <name>`, then `git remote add`), and let a credential helper or an ssh key supply the secret. Removing and re-adding is prescribed because it works whatever the config holds - `git remote set-url` cannot repair a multi-valued `url`, and `set-url --delete` refuses to drop a remote's last non-push URL. For a remote inherited from your user or system configuration those repository-scoped commands answer `No such remote`; drop the `remote.<name>` section from the file that defines it instead (`git config --global --remove-section remote.<name>`, or `--system`). The refusal quotes the remote's name once and never pastes it into a command - where one needs it, it is written as a literal `<name>` placeholder, because a name read out of a config file is untrusted text. A name that could itself be a credential URL (one carrying `@`, `?` or `#`) is withheld instead of quoted; `git remote -v` in your terminal shows which entry it was.

**How far the check reaches.** It reads the **merged** configuration - this repository's config, files it `[include]`s unconditionally, your user config (both `~/.gitconfig` and `$XDG_CONFIG_HOME/git/config`, defaulting to `~/.config/git/config`) and the system config - so an inherited remote is refused too. Two blind spots remain: a `url.<base>.insteadOf` / `url.<base>.pushInsteadOf` rewrite rule is not inspected (either can point a clean-looking remote at a poisoned URL, `pushInsteadOf` for push only), and a conditional `[includeIf]` section is not evaluated. Whitespace outside the authority - in the path, e.g. `https://example.com/team/my repo.git` - does not trigger it, and neither does a `?`/`#` that comes after the `@` (there the redaction still masks the credential). If the configuration cannot be read at all, those four subcommands are refused as well (the check fails closed) with a generic message; this tool logs only the failure's exception types, because the message can quote the offending configuration, credentials included. That is a promise about what the tool itself writes: JGit logs a malformed **user** config on its own, before any of this runs, and that entry is the platform's.

**What is hardened vs. what is trusted.** The tool hardens against COMMAND-STRING injection: no shell, an allowlisted subcommand set, a denied set of program-running / file-writing / repo-redirecting options (matched by exact name, `=value`, AND abbreviation, since git resolves unique prefixes), rejection of `ext::`/`fd::` transport-helper and `user:password@` URLs, of a remote URL whose **authority** carries ASCII whitespace, and of a remote URL carrying a control character **anywhere** in it (the path included), safe transports (`GIT_ALLOW_PROTOCOL`), and a scrubbed set of `GIT_*` redirection/exec variables. Because abbreviations are matched conservatively, a legitimate option that merely shares a prefix with a denied one may be over-rejected (e.g. `--con` for `--contains`); pass the full option name.

It does **not** sandbox the machine or the repository's own configuration: the `git` executable is resolved from the machine's `PATH`; a repository's hooks, filters, aliases and merge drivers run with your privileges; and operand paths are not confined to the work tree - **exactly as they would in your terminal**. Only enable this tool for a machine and repositories you already trust to run git in, as you do when you `cd` into them yourself.

## What this tool is - and what it is not

It is a **terminal-equivalent convenience**, not a sandbox. Enabling it grants git - and whatever the
repository's and the machine's configuration make git run (hooks, filters, credential helpers,
`core.sshCommand`, a pager) - the same authority the EDT user already has. That is the same authority
you give git when you type the command yourself, which is exactly the point.

What the tool **does** guarantee, and what the checks below are for:

- the command never reaches a **shell** - it is executed as an argument vector, so there is no
  command-injection surface in the string you send;
- only **whitelisted subcommands** run, and every write-capable one asks for consent;
- it never **hangs**: stdin is closed and the editor, pager, askpass, credential prompt and signing
  are all disabled, so anything that would wait for a human fails fast instead;
- one **bounded** process (see the timeout below): at the timeout it is killed together with every
  child the JVM can still see, and the output is capped. A child that a hook or helper fully
  DETACHES before we sample it is out of reach — Java has no portable process-group / job-object
  API, so descendants are tracked by sampling while git runs — but it holds neither the tool's
  thread nor its pipe afterwards.

The rest - the blocked-option list, the checks that keep a path inside the repository, and the
credential redaction in the output - are **best-effort guardrails against a common mistake**, not a
containment boundary. The stored-remote check above is the one exception, and only within the shapes
it judges (the **authority** of a stored `remote.<name>.url` / `.pushurl`): there it does not mask, it
REFUSES. A credential that merely appears in git's OUTPUT as free text is still only best-effort
redacted.
Git owns the
option grammar (a value can be attached, clustered or abbreviated) and the output format, so a
determined caller, or a repository configured to run its own programs, can work around them. Treat
this tool as you would a terminal you handed to the agent: enable it for repositories you trust, and
rely on the consent gate for anything that writes.

## Supported subcommands (whitelist)
A minimal, deliberately-small set - inspection and the dev loop:
`add`, `blame`, `branch`, `checkout`, `cherry-pick`, `commit`, `describe`, `diff`, `fetch`, `log`, `ls-files`, `merge`, `pull`, `push`, `remote`, `restore`, `rev-parse`, `revert`, `show`, `stash`, `status`, `switch`, `tag`.

Anything else is **rejected** with an actionable error naming the supported set. Deliberately excluded: `config` (it can set `core.sshCommand`/aliases = arbitrary code), `clean` / `gc` / `reset` (irrecoverable data loss), `rebase` (its `--exec`/`-x` runs a command per step), `init` / `clone` (repository bootstrap is out of scope), `submodule` / `worktree` / `filter-branch` / `daemon` / `credential`.

Also rejected wherever they appear: the `--upload-pack` / `--receive-pack` / `--exec` remote/step-program options, the `--config` / `--config-env` inline-config, the `--git-dir` / `--work-tree` / `--exec-path` / `--namespace` repository redirections, and `--ext-diff` / `--output` / `--help` / `--no-index` (external driver / arbitrary file write / man viewer / reading files outside the repo), plus `--strategy` everywhere and a single-dash token that can carry `-s` on `merge`/`pull` - the cluster spells it too (`-nspwn` is `-n -s pwn`), while a value-taking letter ends it, so `-Xours` and `-mfixes` stay allowed, as do `cherry-pick -s`/`revert -s` (there `-s` is `--signoff`) (git runs the strategy as the program `git-<strategy>` from `PATH`; `-X`/`--strategy-option`, which only configures the built-in strategy, stays allowed), plus the options that take an arbitrary FILE as their value - `--contents` (`blame` prints it), `--file` / `-F` on `commit`/`tag`/`merge` (it lands in the message), `--template` and `--pathspec-from-file` - and any **global** option before the subcommand (e.g. `-c core.sshCommand=...`), since the first token must be a bare subcommand. Transports are restricted (`GIT_ALLOW_PROTOCOL`) to the safe set, so a `ext::` / `fd::` transport-helper remote (which would run an arbitrary command) is refused. A `file://` remote is refused too - git would read, and on a push WRITE, a repository anywhere on disk, and that path sits inside a URI where the repository-containment check cannot see it (a local remote written as a plain path is covered by that check).

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
- **Every write-capable subcommand asks first** (so a read-only form of one - `remote -v`, `branch --list`, `stash list` - prompts too). Only the read-only subcommands (`status`, `diff`, `log`, `show`, `blame`, `ls-files`, `rev-parse`, `describe`) run silently; every other subcommand goes through the server's destructive-consent gate (the same one `delete_metadata` uses). The rule is per SUBCOMMAND, not per flag, and deliberately coarse: whether a given `push`, `checkout` or `merge` destroys work depends on options git resolves per subcommand, with bundles (`-fq`), attached values (`-bfeature`) and accepted abbreviations (`--forc`) - classifying at that level both missed real forms (`push +main:main`, `merge --abort` discarding conflict resolutions) and prompted for safe ones. Under-asking loses work git cannot bring back; over-asking costs one click - or none, once you set the consent level in the MCP Server preferences (or `EDT_MCP_DESTRUCTIVE_CONSENT` at launch) for unattended use.
- **An ssh command you configured is respected.** When `core.sshCommand` or `GIT_SSH_COMMAND` is set, the tool uses it as-is (your custom identity, port or wrapper keeps working); only when neither is configured does it install its own `ssh -oBatchMode=yes`. A GUI askpass is disabled either way, and the per-call timeout bounds a command that still waits.
- **`diff` cannot read outside the repository.** `--no-index` is rejected, and so is an operand that resolves outside the work tree (git applies the filesystem-compare form implicitly for such a path).
- **GPG signing is not available** (creating *or* verifying). Signing needs the gpg-agent pinentry dialog, which an unattended MCP session cannot answer, so every call runs with the signing config off and no usable `gpg.*program`: a signing request fails immediately with a git error instead of hanging. Because git uses the same programs to verify, `tag -v` / `--show-signature` / `--verify-signatures` do not report signatures here either - sign and verify from your terminal.
- **openWorldHint = true**: `push` / `pull` / `fetch` reach an external remote. The tool never opens or authenticates against a 1C infobase.
- Operates on the ON-DISK content. Save or `resync_to_disk` the EDT model before `commit` so your model edits are captured.
- The typed `list_git_branches` / `switch_git_branch` / `create_git_branch` tools remain available (and enabled) for branch work with 1C application binding; this `git` tool is the general-purpose escape hatch.

"""
Ratchet: this run must not add NEW error-severity entries of OUR OWN to the EDT log
(kind: read, pseudo-tool: not an MCP tool, like _mutation_set_ratchet).

WHY THIS FILE EXISTS
--------------------
A green suite is not the same as a healthy server. Every assertion here checks what a tool
RETURNED; nothing checks what it logged on the way. So a whole class of failure is invisible:
the tool answers correctly while an exception is swallowed behind it.

That is not hypothetical. `MetadataRenameService` built EDT's `TextSearcher` reflectively
against a pinned constructor. The platform's signature no longer matched it - and had not
matched for at least two releases - so every construction threw NoSuchMethodException, the
`catch (Exception)` logged it and returned an empty match map, and rename kept "working" on
its fallback path. 32 stack traces per run, every rename test green, nobody the wiser. The
only place that failure was visible was the EDT log.

WHAT IT CHECKS
--------------
The ratchet first makes the server write a run-unique line of its own and refuses to certify
anything unless it finds that line back. Locating a workspace does not establish that its logs
are the ones this server writes; a live workspace can belong to a different EDT instance.

Only entries whose plugin is `com.ditrix.edt.mcp.server` at severity 4 (ERROR), and only
those stamped at or after this run started. Platform noise is deliberately out of scope: EDT
logs plenty of its own errors (its Moxel editor touching a stopped namespace, its Xtext
builder opening a nested transaction, legacy BSL checks throwing) and we neither cause nor
can fix those. Ours are the ones we own.

Known, accepted messages live in `edt_log_baseline.txt`, one normalized message per line.
Most of what is in there is a VALIDATION REFUSAL logged at ERROR - a tool correctly rejecting
a bad request on a negative test, but shouting about it in the log. Those entries are
technically noise we should demote to warnings; they are baselined rather than ignored so the
list stays visible and shrinkable, and so a genuinely new error cannot hide among them.

Adding a line to the baseline is a deliberate act. Prefer fixing the log call.
"""

import glob
import os
import re
import uuid

from harness import (
    RUN_STARTED_AT, HARNESS_DIR, E2ESkip, call, e2e_test, _fail, _workspace_dir,
)

OUR_PLUGIN = "com.ditrix.edt.mcp.server"
SEVERITY_ERROR = "4"
BASELINE_FILE = os.path.join(HARNESS_DIR, "edt_log_baseline.txt")

# !ENTRY <plugin> <severity> <code> <YYYY-MM-DD HH:MM:SS.mmm>
_ENTRY = re.compile(
    r"^!ENTRY (\S+) (\d+) (\d+) (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)")

# Volatile fragments that would make every run's message unique. Normalizing them keeps the
# baseline about the KIND of error rather than the object that happened to trigger it.
_NORMALIZERS = [
    (re.compile(r"'[^']*'"), "'<x>'"),
    (re.compile(r"\bE2E\w*"), "<e2e>"),
    (re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F-]{20,}"), "<uuid>"),
    (re.compile(r"\d+"), "<n>"),
    (re.compile(r"\s+"), " "),
]


def _normalize(message):
    text = message.strip()
    for pattern, replacement in _NORMALIZERS:
        text = pattern.sub(replacement, text)
    return text.strip()


def _entry_epoch(stamp):
    """EDT stamps local time; convert to epoch seconds for comparison with RUN_STARTED_AT."""
    import datetime
    parsed = datetime.datetime.strptime(stamp[:19], "%Y-%m-%d %H:%M:%S")
    return parsed.timestamp()


def _load_baseline():
    if not os.path.isfile(BASELINE_FILE):
        return set()
    accepted = set()
    with open(BASELINE_FILE, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#"):
                accepted.add(line)
    return accepted


def _emit_log_probe():
    """Make the server under test write a run-unique line into ITS OWN log; return the token.

    Locating a workspace does not establish whose logs are in it, and nothing on the filesystem
    can: a live .metadata belongs to whichever EDT is running, which need not be the one serving
    this suite. Only the server can answer that, so it is asked. Every tools/call that ends in an
    error outcome is logged once, at WARNING, by our own plugin, with the tool's error message
    attached (McpProtocolHandler.formatErrorLogLine), and get_project_errors names the project it
    could not find - so a project name nothing else could produce comes straight back out in the
    log. Finding it proves these files are the ones this server writes.

    That refusal is logged at WARNING and never at severity 4, so the probe cannot appear among
    the ERROR entries this ratchet reports. And the log call happens before the response is
    written, with Equinox flushing each entry as it writes it, so the line is already on disk by
    the time this returns - there is nothing to wait for.
    """
    token = "edtmcplogprobe%s" % uuid.uuid4().hex
    call("get_project_errors", {"projectName": token})
    return token


def _collect_our_errors(workspace, token):
    """Every ERROR entry of ours stamped this run, plus whether our probe was found."""
    metadata = os.path.join(workspace, ".metadata")
    # The log rotates at ~1 MB, so one run can span .log plus several .bak_N.log.
    files = sorted(glob.glob(os.path.join(metadata, ".bak_*.log"))) + \
        [os.path.join(metadata, ".log")]

    found = {}
    saw_probe = False
    for path in files:
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                lines = handle.readlines()
        except OSError:
            continue
        for index, line in enumerate(lines):
            match = _ENTRY.match(line)
            if not match:
                continue
            plugin, severity, _code, stamp = match.groups()
            try:
                if _entry_epoch(stamp) < RUN_STARTED_AT - 1:
                    continue
            except ValueError:
                continue
            if plugin != OUR_PLUGIN:
                continue
            message = ""
            for follow in lines[index + 1:index + 3]:
                if follow.startswith("!MESSAGE"):
                    message = follow[len("!MESSAGE"):]
                    break
            if token in message:
                saw_probe = True
            if severity != SEVERITY_ERROR or token in message:
                continue
            key = _normalize(message) or "(no message)"
            found[key] = found.get(key, 0) + 1
    return found, saw_probe


@e2e_test(tool="_edt_log_ratchet", kind="read", last=True)
def test_run_adds_no_unbaselined_error_entries_to_the_edt_log():
    workspace_override = os.environ.get("EDT_MCP_EDT_WORKSPACE")
    workspace = _workspace_dir()
    if workspace is None:
        # Same split as below, for the same reason and one step earlier: an override that is
        # not a workspace at all is the operator's assertion failing outright, so telling them
        # to set the variable they already set would be advice about the wrong problem.
        if workspace_override:
            _fail(
                "EDT_MCP_EDT_WORKSPACE names %s, but there is no .metadata directory there, so "
                "the log ratchet has nothing to read. Point it at the -data directory the "
                "server under test was launched with." % workspace_override)
        raise E2ESkip(
            "EDT workspace not found: set EDT_MCP_EDT_WORKSPACE to the -data directory "
            "so the log ratchet can read <workspace>/.metadata/.log")

    token = _emit_log_probe()
    found, saw_probe = _collect_our_errors(workspace, token)
    if not saw_probe:
        # Inference is only a filesystem guess, so absent evidence must skip; an explicit
        # override is the operator's assertion that this server writes here, so the same
        # absence disproves either that assertion or the probe and must fail the run.
        if workspace_override:
            _fail(
                "EDT workspace was named explicitly at %s by EDT_MCP_EDT_WORKSPACE, but the "
                "run-unique probe sent through get_project_errors under a run-unique project "
                "name did not come back in .metadata/.log or .metadata/.bak_*.log. Either "
                "that directory is not this server's workspace, or the probe no longer "
                "produces the log line the ratchet depends on."
                % workspace)
        raise E2ESkip(
            "EDT workspace was located at %s but does not carry this server's own log output: "
            "the run-unique probe sent through get_project_errors was not found in "
            ".metadata/.log or .metadata/.bak_*.log. These logs belong to a different EDT "
            "instance (or the plugin is not logging), so nothing about them can be certified."
            % workspace)
    accepted = _load_baseline()
    new = {msg: count for msg, count in found.items() if msg not in accepted}
    if not new:
        return

    lines = ["%4dx  %s" % (count, msg) for msg, count in
             sorted(new.items(), key=lambda kv: -kv[1])]
    _fail("this run logged %d NEW error-severity entr%s under %s that the baseline does not "
          "cover. An MCP tool can return a correct answer while swallowing an exception behind "
          "it, so these are failures no other assertion sees - read the stack in "
          "%s/.metadata/.log and fix the cause. If the entry is a validation REFUSAL, the log "
          "call itself is the bug: a rejected request is not a server error, so demote it "
          "instead of baselining it. Only add a line to %s when you have decided the entry is "
          "acceptable.\n%s"
          % (len(new), "y" if len(new) == 1 else "ies", OUR_PLUGIN, workspace,
             BASELINE_FILE, "\n".join(lines)))

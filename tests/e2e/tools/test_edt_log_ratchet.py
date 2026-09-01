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

from harness import (
    RUN_STARTED_AT, HARNESS_DIR, E2ESkip, e2e_test, _fail, _workspace_dir,
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


def _collect_our_errors(workspace):
    """Every ERROR entry of ours stamped at/after this run started, normalized + counted."""
    metadata = os.path.join(workspace, ".metadata")
    # The log rotates at ~1 MB, so one run can span .log plus several .bak_N.log.
    files = sorted(glob.glob(os.path.join(metadata, ".bak_*.log"))) + \
        [os.path.join(metadata, ".log")]

    found = {}
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
            if plugin != OUR_PLUGIN or severity != SEVERITY_ERROR:
                continue
            try:
                if _entry_epoch(stamp) < RUN_STARTED_AT - 1:
                    continue
            except ValueError:
                continue
            message = ""
            for follow in lines[index + 1:index + 3]:
                if follow.startswith("!MESSAGE"):
                    message = follow[len("!MESSAGE"):]
                    break
            key = _normalize(message) or "(no message)"
            found[key] = found.get(key, 0) + 1
    return found


@e2e_test(tool="_edt_log_ratchet", kind="read", last=True)
def test_run_adds_no_unbaselined_error_entries_to_the_edt_log():
    workspace = _workspace_dir()
    if workspace is None:
        raise E2ESkip(
            "EDT workspace not found: set EDT_MCP_EDT_WORKSPACE to the -data directory "
            "so the log ratchet can read <workspace>/.metadata/.log")

    found = _collect_our_errors(workspace)
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

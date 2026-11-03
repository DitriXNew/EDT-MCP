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
logs plenty of its own errors (its Xtext builder opening a nested transaction, legacy BSL checks
throwing) and we neither cause nor can fix those. Ours are the ones we own.

The plugin filter is also a blind spot: an entry logged under a PLATFORM plugin can still be
caused by us. "Moxel editor touching a stopped namespace" (BmNamespaceInactiveException from
MoxelControl under com._1c.g5.v8.dt.core) once sat in the noise list above, and it was ours -
get_template_screenshot leaked the off-screen control's listeners (issue #770). That case is
now checked by its own test in test_get_template_screenshot.py; this ratchet still sees only
our plugin's entries.

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
    RUN_STARTED_AT, HARNESS_DIR, E2ESkip, building_refusals_seen, call, e2e_test, _fail,
    _workspace_dir,
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
    """Every ERROR entry of ours stamped this run, plus whether an optional probe was found."""
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
            if token is not None and token in message:
                saw_probe = True
            if severity != SEVERITY_ERROR or (token is not None and token in message):
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

    # Read the error-bearing generations before the probe can rotate .log over a reused backup
    # name. Then emit the probe to bind this workspace to the server under test and read again so
    # errors written between the reads are included too. Counts form a multiset union: taking the
    # maximum retains a generation seen by only one read without double-counting stable entries.
    before_probe, _ = _collect_our_errors(workspace, None)
    token = _emit_log_probe()
    after_probe, saw_probe = _collect_our_errors(workspace, token)
    found = {
        message: max(before_probe.get(message, 0), after_probe.get(message, 0))
        for message in before_probe.keys() | after_probe.keys()
    }
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


# -- Issue #769: the "still building" refusal must name the build state, not a Java object ------
#
# ProjectStateChecker appended DerivedDataStatus.toString() to the refusal. The platform class does
# not override it, so the client got "Project is building: com._1c.g5.v8.derived.DerivedDataStatus@
# 6749e34c" - a JVM identity that changes on every call - and the EDT log carried one such WARNING
# per refused call. Nothing noticed, because call() retries every building refusal away and no
# assertion ever reads one. This test reads them: the texts call() retried (recorded by the
# harness) and our plugin's own log lines of this run, which include the refusals of the reset
# cycles and of the final cleanup - it runs after both, as a ratchet.
# Coverage is opportunistic: this read-only test does not trigger a busy pipeline. A run without a
# refusal skips and is not positive evidence for issue #769. At stand time, record the pre-deploy
# DerivedDataStatus@ count as a positive control, then the post-deploy count (expected 0) from NEW
# entries after redeploy. Historical entries in .log and .bak_*.log are not post-deploy evidence.

# Only the derived-data verdict's own wordings: participant/discovery refusals name projects instead.
_BUILD_STATE_REFUSAL = re.compile(r"Project is building|Project build in progress")
# Object.toString() as the JVM renders it - <class>@<hex identity hash> - or the class named at all.
_JAVA_IDENTITY = re.compile(r"DerivedDataStatus|[A-Za-z_$][\w$]*@[0-9a-fA-F]{4,}\b")


def _our_log_messages_this_run(workspace):
    """The !MESSAGE text of every entry of our plugin stamped this run, at any severity.

    A refusal is logged at WARNING ("Failed tools/call: <tool> - <error>"), so the ERROR-only
    collector above cannot see it.
    """
    metadata = os.path.join(workspace, ".metadata")
    files = sorted(glob.glob(os.path.join(metadata, ".bak_*.log"))) + \
        [os.path.join(metadata, ".log")]
    messages = []
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
            if not match or match.group(1) != OUR_PLUGIN:
                continue
            try:
                if _entry_epoch(match.group(4)) < RUN_STARTED_AT - 1:
                    continue
            except ValueError:
                continue
            for follow in lines[index + 1:index + 3]:
                if follow.startswith("!MESSAGE"):
                    messages.append(follow[len("!MESSAGE"):].strip())
                    break
    return messages


@e2e_test(tool="_edt_log_ratchet", kind="read", last=True)
def test_building_refusals_name_the_build_state_not_a_java_object_identity():
    texts = [("response", text) for text in building_refusals_seen()
             if _BUILD_STATE_REFUSAL.search(text)]

    workspace = _workspace_dir()
    log_note = "the EDT log was not read (no workspace located)"
    if workspace is not None:
        # Same binding as the error ratchet: only a run-unique line of the server's own proves these
        # files are the ones this server writes.
        token = _emit_log_probe()
        messages = _our_log_messages_this_run(workspace)
        if any(token in message for message in messages):
            texts += [("EDT log", message) for message in messages
                      if _BUILD_STATE_REFUSAL.search(message)]
            log_note = "the EDT log at %s was read" % workspace
        else:
            log_note = ("the EDT log at %s does not carry this server's run-unique probe, so it was "
                        "not used" % workspace)

    if not texts:
        raise E2ESkip(
            "no derived-data building refusal was issued during this run (%s), so there was no "
            "refusal text to check; this ratchet is opportunistic. At stand time record the "
            "pre-deploy DerivedDataStatus@ count and the post-deploy count (expected 0) from new "
            "entries after redeploy" % log_note)

    leaks = [(source, text) for source, text in texts if _JAVA_IDENTITY.search(text)]
    if leaks:
        sample = "\n".join("  [%s] %s" % (source, text[:300]) for source, text in leaks[:5])
        _fail(
            "%d of %d building refusal(s) seen this run carry a Java object identity instead of "
            "the build state (issue #769: ProjectStateChecker must word the DerivedDataStatus from "
            "its getters and never call its toString()). %s.\n%s"
            % (len(leaks), len(texts), log_note, sample))

    # The prefix is a wire contract: call() retries on it and the guides quote it.
    for source, text in texts:
        if "Project is building" in text:
            tail = text.split("Project is building", 1)[1]
            if not tail.startswith(": ") or re.search(r"\bnull\b", tail):
                _fail("[%s] a building refusal lost its 'Project is building: <state>' shape or "
                      "renders a literal null: %s" % (source, text[:300]))

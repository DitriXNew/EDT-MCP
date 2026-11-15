"""
e2e tests for `git clone` in the git tool (issue #934).

WHAT IS PINNED
--------------
`clone <url> <directory>` takes no projectName: it copies a remote repository into a new or empty
directory outside every git work tree and every workspace project folder, in a background job.
Local paths and file:// URLs are refused; a successful clone names the directory it went into and
what is left to do in EDT (nothing is imported).

CI STRATEGY
-----------
Same as test_git.py: the git tool is DISABLED BY DEFAULT, so every test is gated behind
EDT_MCP_GIT_E2E=1 (a stand where the operator enabled the tool). The happy path needs a remote the
grammar accepts and nothing on the network: it serves a bare repository with a local `git daemon`
(git://127.0.0.1) and skips when that cannot be started. Every target lives in a fresh temporary
directory, removed afterwards; the refusals change nothing on disk.
"""

import os
import shutil
import socket
import stat
import subprocess
import tempfile
import time

from harness import (
    E2ESkip,
    REPO_ROOT,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

# The same opt-in gate as test_git.py (set EDT_MCP_GIT_E2E=1 on a stand where git is enabled).
GIT_ENABLED = os.environ.get("EDT_MCP_GIT_E2E", "").strip() not in ("", "0", "false", "no")

URL = "https://example.com/team/repo.git"


def _requires_enabled_git():
    if not GIT_ENABLED:
        raise E2ESkip("git is disabled by default; set EDT_MCP_GIT_E2E=1 on a stand "
                      "where the operator enabled it to run the clone paths")


def _scratch():
    """A fresh temporary directory, with its real path (the one the tool reports)."""
    return os.path.realpath(tempfile.mkdtemp(prefix="edt-mcp-clone-e2e-"))


def _remove(path):
    def _writable(func, target, _exc):
        os.chmod(target, stat.S_IWRITE)
        func(target)
    shutil.rmtree(path, onerror=_writable)


def _git(cwd, *args):
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith("GIT_")}
    env["GIT_TERMINAL_PROMPT"] = "0"
    subprocess.run(["git", "-c", "core.hooksPath=", "-c", "init.templateDir="] + list(args),
                   cwd=cwd, env=env, check=True, capture_output=True, timeout=60)


def _serve(root):
    """A bare repository with one commit on 'trunk' behind a local git daemon: (process, url)."""
    work = os.path.join(root, "work")
    os.makedirs(work)
    _git(work, "init", "-q", "-b", "trunk")
    _git(work, "config", "user.email", "tests@example.com")
    _git(work, "config", "user.name", "EDT MCP e2e")
    with open(os.path.join(work, "tracked.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("first line\n")
    _git(work, "add", "tracked.txt")
    _git(work, "commit", "-q", "-m", "initial commit")
    served = os.path.join(root, "served")
    os.makedirs(served)
    _git(root, "clone", "-q", "--bare", work, os.path.join(served, "repo.git"))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    daemon = subprocess.Popen(["git", "daemon", "--reuseaddr", "--export-all", "--base-path=" + served,
                               "--listen=127.0.0.1", "--port=%d" % port, served],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    url = "git://127.0.0.1:%d/repo.git" % port
    deadline = time.time() + 20
    while time.time() < deadline:
        if daemon.poll() is not None:
            break
        if subprocess.run(["git", "ls-remote", url], capture_output=True, timeout=30).returncode == 0:
            return daemon, url
        time.sleep(0.3)
    _stop(daemon)
    raise E2ESkip("git daemon could not be started on this stand")


def _stop(daemon):
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(daemon.pid)], capture_output=True)
    else:
        daemon.kill()
    daemon.wait(timeout=10)


def _job_status(job_id):
    """Polls get_job_status until the job ends; returns its Markdown."""
    deadline = time.time() + 300
    while time.time() < deadline:
        r = call("get_job_status", {"jobId": job_id, "waitSeconds": 30})
        assert_ok(r, "get_job_status for the clone")
        if "| status | running |" not in (r.text or ""):
            return r.text
    raise AssertionError("the clone job %s did not end within 5 minutes" % job_id)


@e2e_test(tool="git", kind="read")
def test_clone_refuses_a_local_path_and_a_file_url():
    """A local repository and a file:// URL would copy a repository from anywhere on disk.

    Mutation check: a grammar that let a local path through would start a clone (or, on the master
    jar, answer that 'clone' is not supported) instead of this refusal.
    """
    _requires_enabled_git()
    scratch = _scratch()
    try:
        target = os.path.join(scratch, "repo")
        local = assert_error(call("git", {"command": "clone '%s' '%s'" % (REPO_ROOT, target)}),
                             "clone of a local path")
        assert_error_quality(local, names=["local path"], ctx="the refusal must say it is a local path")
        file_url = assert_error(call("git", {"command": "clone file:///srv/repo.git '%s'" % target}),
                                "clone of a file:// URL")
        assert_error_quality(file_url, names=["file://"], ctx="the refusal must name the scheme")
        if os.path.exists(target):
            raise AssertionError("a refused clone must create nothing, but %s exists" % target)
    finally:
        _remove(scratch)


@e2e_test(tool="git", kind="read")
def test_clone_refuses_a_project_name_and_a_target_inside_a_work_tree():
    """clone takes no projectName; a target inside the fixture checkout would nest a repository."""
    _requires_enabled_git()

    scratch = _scratch()
    try:
        named = assert_error(call("git", {"projectName": PROJECT,
                                          "command": "clone %s '%s'" % (URL, os.path.join(scratch, "r"))}),
                             "clone with a projectName")
        assert_error_quality(named, names=["takes no projectName"],
                             ctx="the refusal must say clone takes no projectName")
    finally:
        _remove(scratch)

    inside = os.path.join(REPO_ROOT, "clone-e2e-target-934")
    nested = assert_error(call("git", {"command": "clone %s '%s'" % (URL, inside)}),
                          "clone into the fixture checkout")
    assert_error_quality(nested, names=["lies inside the git repository", "nested"],
                         ctx="the refusal must name the enclosing repository")
    if os.path.exists(inside):
        raise AssertionError("a refused clone must create nothing, but %s exists" % inside)
    assert_no_diff("a refused clone must not touch the fixture")


@e2e_test(tool="git", kind="action")
def test_clone_from_a_local_daemon_lands_in_a_new_directory_and_refuses_it_again():
    """git daemon -> git clone -b trunk git://... <new dir> -> the clone is there, named, not imported.

    A second clone into the same, now existing, directory is refused before anything runs: only a new
    directory is accepted.
    """
    _requires_enabled_git()
    scratch = _scratch()
    daemon = None
    try:
        daemon, url = _serve(scratch)
        target = os.path.join(scratch, "clone")
        r = call("git", {"command": "clone -b trunk %s '%s'" % (url, target)})
        assert_ok(r, "git clone from the local daemon")
        data = r.structured or {}
        if data.get("status") == "running":
            text = _job_status(data.get("jobId"))
            if "| status | done |" not in text:
                raise AssertionError("the clone job must end done, got:\n%s" % text)
        else:
            if data.get("status") != "done" or data.get("exitCode") != 0:
                raise AssertionError("the clone must end done with exit 0, got %r" % data)
            if data.get("directory") != target:
                raise AssertionError("the result must name the real target %r, got %r"
                                     % (target, data.get("directory")))
            if "Projects from Git" not in (data.get("nextStep") or ""):
                raise AssertionError("a successful clone must say how to import it in EDT, got %r"
                                     % data.get("nextStep"))
        with open(os.path.join(target, ".git", "HEAD"), encoding="utf-8") as f:
            head = f.read().strip()
        if head != "ref: refs/heads/trunk":
            raise AssertionError("the clone must check out 'trunk', HEAD holds %r" % head)
        # newline="" keeps a CRLF that the machine's core.autocrlf may have written; drop it.
        with open(os.path.join(target, "tracked.txt"), encoding="utf-8", newline="") as f:
            if f.read().replace("\r", "") != "first line\n":
                raise AssertionError("the remote's file must come along")

        again = assert_error(call("git", {"command": "clone %s '%s'" % (url, target)}),
                             "a second clone into the same directory")
        assert_error_quality(again, names=["already exists", "remove the empty directory or pick a new path"],
                             ctx="a clone never lands over existing files")
    finally:
        if daemon is not None:
            _stop(daemon)
        _remove(scratch)
    assert_no_diff("the fixture must be untouched by a clone elsewhere")

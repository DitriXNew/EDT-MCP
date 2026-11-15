"""
e2e tests for `git init` in the git tool (issue #934).

WHAT IS PINNED
--------------
`init` is the one git subcommand that runs where there is NO repository yet: it creates one in the
project's own folder. It refuses a project that already belongs to a repository (that would nest
one repository in another), accepts only -q / -b <name>, and a successful answer carries
`nextStep` - the repository is not connected to EGit by the tool.

CI STRATEGY
-----------
Same as test_git.py: the git tool is DISABLED BY DEFAULT, so both tests are gated behind
EDT_MCP_GIT_E2E=1 (a stand where the operator enabled the tool). Neither touches an existing
repository: the refusal is asked of the fixture project, which lies inside the EDT-MCP checkout
and must come back untouched; the creation runs in a NEW project made by create_project in EDT's
workspace, which is removed in a finally block. If that workspace itself lies inside a git work tree
the creation test skips - it never initialises inside a repository.
"""

import os
import time

from harness import (
    E2ESkip,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    split_markdown_row,
    e2e_test,
    PROJECT,
)

# The same opt-in gate as test_git.py (set EDT_MCP_GIT_E2E=1 on a stand where git is enabled).
GIT_ENABLED = os.environ.get("EDT_MCP_GIT_E2E", "").strip() not in ("", "0", "false", "no")

NEW_PROJECT = "GitInitTest_Config_e2e"


def _requires_enabled_git():
    if not GIT_ENABLED:
        raise E2ESkip("git is disabled by default; set EDT_MCP_GIT_E2E=1 on a stand "
                      "where the operator enabled it to run the init paths")


def _ensure_absent(name):
    """Best-effort pre/post cleanup: remove a leftover project from a prior crashed run."""
    call("delete_project", {"projectName": name, "deleteContent": True, "confirm": True})


def _project_dir(project):
    """The project's directory as list_projects reports it (its Path column)."""
    lp = call("list_projects", {})
    assert_ok(lp, "list_projects for the created project's path")
    columns = None
    for line in (lp.text or "").splitlines():
        cells = split_markdown_row(line)
        if not cells:
            continue
        if columns is None:
            lowered = [cell.lower() for cell in cells]
            if "name" in lowered and "path" in lowered:
                columns = (lowered.index("name"), lowered.index("path"))
            continue
        if len(cells) > max(columns) and cells[columns[0]] == project and cells[columns[1]]:
            return cells[columns[1]]
    raise AssertionError("list_projects has no row with a path for %r:\n%s" % (project, lp.text))


@e2e_test(tool="git", kind="read")
def test_init_refuses_a_project_that_already_belongs_to_a_repository():
    """The fixture project lives inside the EDT-MCP checkout: init there would nest a repository.

    The refusal names the existing repository, runs before the consent gate, and changes nothing.
    Mutation check: an init that skipped the existing-repository check would create a nested
    '.git' in the fixture project and answer success.
    """
    _requires_enabled_git()

    err = assert_error(call("git", {"projectName": PROJECT, "command": "init -b main"}),
                       "init inside an existing repository")
    assert_error_quality(err, names=[PROJECT, "already belongs to the git repository"],
                         ctx="the refusal must name the project and say it already has a repository")
    if "nested" not in err:
        raise AssertionError("the refusal must say why: a nested repository, got %r" % err)

    assert_no_diff("a refused init must not touch the project on disk")


@e2e_test(tool="git", kind="action")
def test_init_creates_a_repository_in_a_new_project_and_then_refuses_a_second_one():
    """create_project -> git init -b main -> the project folder holds a repository on 'main'.

    After it, the other subcommands resolve that repository (status runs), and a second init is
    refused because the folder already holds '.git'. The created project is deleted at the end.
    """
    _requires_enabled_git()

    _ensure_absent(NEW_PROJECT)
    try:
        created = call("create_project", {"projectKind": "configuration", "name": NEW_PROJECT})
        assert_ok(created, "create_project for the init test")
        directory = _project_dir(NEW_PROJECT)

        r = call("git", {"projectName": NEW_PROJECT, "command": "init -b main"})
        if r.is_error and "already belongs to the git repository" in (r.text or ""):
            raise E2ESkip("EDT's workspace lies inside a git work tree on this machine; init is "
                          "refused there by design, so the creation path cannot be exercised")
        assert_ok(r, "git init in a fresh project")
        data = r.structured or {}
        if data.get("exitCode") != 0:
            raise AssertionError("init must exit 0, got %r" % data)
        if data.get("command") != "git init -b main":
            raise AssertionError("the result must echo the command it ran, got %r" % data.get("command"))
        if "Share Project" not in (data.get("nextStep") or ""):
            raise AssertionError("a successful init must say how to connect the repository in EDT, "
                                 "got nextStep=%r" % data.get("nextStep"))

        head = os.path.join(directory, ".git", "HEAD")
        deadline = time.time() + 10
        while not os.path.isfile(head) and time.time() < deadline:
            time.sleep(0.5)
        with open(head, encoding="utf-8") as f:
            content = f.read().strip()
        if content != "ref: refs/heads/main":
            raise AssertionError("the repository must start on 'main', HEAD holds %r" % content)

        status = call("git", {"projectName": NEW_PROJECT, "command": "status --short"})
        assert_ok(status, "the new repository is the project's repository now")

        again = assert_error(call("git", {"projectName": NEW_PROJECT, "command": "init"}),
                             "a second init")
        assert_error_quality(again, names=[NEW_PROJECT, ".git"],
                             ctx="a second init must be refused: the folder already holds '.git'")
    finally:
        _ensure_absent(NEW_PROJECT)

    assert_no_diff("the fixture must be untouched by an init in another project")

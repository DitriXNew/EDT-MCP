"""
e2e for #922: a metadata write right after switch_git_branch must not be lost, and must not lose the branch.

WHY THIS FILE IS GATED
----------------------
A real switch needs a repository whose branch may be changed. The CI fixture lives inside the
plugin's OWN git working tree (see test_switch_git_branch.py), so switching it would switch the
running checkout. This test therefore runs only against a THROWAWAY repository that the operator
imports into the stand next to the fixture, and is skipped everywhere else:

  EDT_MCP_E2E_SWITCH_REPO      absolute path of the throwaway repository's work tree
  EDT_MCP_E2E_SWITCH_PROJECTS  "<addressed>,<sibling>": two EDT configuration projects inside it
  EDT_MCP_E2E_SWITCH_BRANCH    the branch to switch to (the work tree must be on another branch,
                               clean); it must change both projects' Configuration.mdo

WHAT IT PINS
------------
Right after the switch, modify_metadata(Configuration, version) is sent to BOTH projects - the
addressed one and its sibling in the same work tree. Before #922 the write landed in the model
EDT had not reloaded yet: the answer was persisted:true, and the export wrote the PREVIOUS
branch's Configuration.mdo back over the checked-out one (or the reload later dropped the write).
Now each write either waits out the reload (the harness retries "Project is building") or goes
into the reloaded model, so the file on disk ends up with BOTH the branch's content and the
written version. The ground truth is the file itself and `git show <branch>:<file>`, never the
tool under test.
"""

import os
import re
import subprocess

from harness import E2ESkip, call, assert_ok, e2e_test

REPO = os.environ.get("EDT_MCP_E2E_SWITCH_REPO", "")
PROJECTS = [p.strip() for p in os.environ.get("EDT_MCP_E2E_SWITCH_PROJECTS", "").split(",") if p.strip()]
BRANCH = os.environ.get("EDT_MCP_E2E_SWITCH_BRANCH", "")
CONFIG_MDO = "src/Configuration/Configuration.mdo"


def _repo_git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True, encoding="utf-8")


def _require_scratch_repo():
    if not (REPO and len(PROJECTS) == 2 and BRANCH):
        raise E2ESkip("needs a throwaway repository: set EDT_MCP_E2E_SWITCH_REPO, "
                      "EDT_MCP_E2E_SWITCH_PROJECTS and EDT_MCP_E2E_SWITCH_BRANCH")
    if _repo_git("status", "--porcelain", "--untracked-files=no").stdout.strip():
        raise AssertionError("the throwaway repository must start without tracked changes")
    if _repo_git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip() == BRANCH:
        raise AssertionError("the throwaway repository must start on another branch than %s" % BRANCH)


def _branch_lines(project):
    """The <commonModules>/<comment> lines the branch's own Configuration.mdo carries."""
    text = _repo_git("show", "%s:%s/%s" % (BRANCH, project, CONFIG_MDO)).stdout
    return [line.strip() for line in text.splitlines()
            if "<commonModules>" in line or "<comment>" in line]


def _disk(project):
    with open(os.path.join(REPO, project, *CONFIG_MDO.split("/")), encoding="utf-8") as f:
        return f.read()


@e2e_test(tool="switch_git_branch", kind="action")
def test_a_write_right_after_a_switch_keeps_the_branch_and_the_write():
    """switch_git_branch, then at once modify_metadata(Configuration.version) on the addressed
    project AND its sibling: each Configuration.mdo must keep every branch line and gain the
    version. Before #922 the export wrote the previous branch's model over the checkout."""
    _require_scratch_repo()
    previous = _repo_git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    expected = {p: _branch_lines(p) for p in PROJECTS}
    for project, lines in expected.items():
        if not lines:
            raise AssertionError("branch %s must change %s's Configuration.mdo" % (BRANCH, project))
    try:
        r = call("switch_git_branch", {"projectName": PROJECTS[0], "branch": BRANCH})
        assert_ok(r, "switch to the throwaway branch")
        version = "9.22.0.1"
        for project in reversed(PROJECTS):  # the sibling first: the project #922 lost its write in
            w = call("modify_metadata", {"projectName": project, "fqn": "Configuration",
                                         "properties": [{"name": "version", "value": version}]})
            assert_ok(w, "write the version into %s right after the switch" % project)
        for project in PROJECTS:
            text = _disk(project)
            missing = [line for line in expected[project] if line not in text]
            assert not missing, ("%s: the write put the previous branch's Configuration.mdo back - "
                                 "%d branch line(s) lost, e.g. %s" % (project, len(missing), missing[:3]))
            assert re.search(r"<version>%s</version>" % re.escape(version), text), \
                "%s: the written version is not on disk" % project
    finally:
        _repo_git("checkout", "-q", "-f", previous)

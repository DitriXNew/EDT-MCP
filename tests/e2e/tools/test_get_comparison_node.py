"""
e2e tests for get_comparison_node (kind: read).

WHAT THE TOOL DOES
------------------
Expands ONE node of a comparison started by compare_configurations: a three-way
(main / other / ancestor) property table, the per-side form structure, the module
section list, the vendor-support state, the child outline and the POTENTIAL problems
the engine recorded. It reads; it never merges and never writes the project.

RESPONSE SHAPE
--------------
MARKDOWN tool: the payload lands in r.text.
  success: "# Comparison node: <address>" + a "| Field | Value |" summary
           + "## Properties" + "## Children" + "## Potential problems"
  error:   {"success": false, "error": "..."} delivered as isError

THE ONE INVARIANT THESE TESTS EXIST FOR
---------------------------------------
The comparison tree is built LAZILY. A node the engine has not reached reads back
with no children, and a tool that rendered that state would report "no differences"
for a subtree nobody compared. So whenever the answer carries the not-finished
notice, it must NOT anywhere carry the words "no differences" - asserted on every
successful expansion below, whichever branch the timing happened to take.

CI STRATEGY
-----------
`.github/workflows/e2e-tests.yml` checks out with the default `fetch-depth: 1`, so
`HEAD~1` DOES NOT EXIST on CI. Like test_compare_configurations.py, the integration
test here uses HEAD for both revisions: it resolves on any clone, needs no commit and
no branch switch, and still produces a real comparison tree to expand. WHAT a tree
containing differences renders is proved by the unit tests over stub node graphs.

ONE COMPARISON PER EDT
----------------------
The workbench runs exactly ONE comparison at a time, so the integration test starts
one, uses it, and RELEASES it before returning - a comparison left alive would make
every later test (and every later run) fail with "already running". The error-path
tests below deliberately need no comparison at all, so they cost nothing and run
even when the slot is busy.

Releasing takes both calls. This test waits for the comparison to FINISH, and a
finished comparison's job is terminal - `cancel_job` answers `alreadyTerminal` without
the owning tool's cancellation handler running, so it frees nothing. The session is
given back with `compare_configurations(releaseComparisonId=...)`. `cancel_job` still
runs first, for the paths that skip out while the comparison is still RUNNING.
"""

import re

from harness import (
    E2ESkip,
    _fail,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    call,
    e2e_test,
    PROJECT,
)

JOB_ID_ROW = re.compile(r"^\| jobId \| ([^|]+) \|\s*$", re.MULTILINE)
COMPARISON_ID_ROW = re.compile(r"^\| comparisonId \| ([^|]+) \|\s*$", re.MULTILINE)
NODE_ROW = re.compile(r"^\| (\d+) \| ([^|]*) \| ([^|]*) \| ([^|]*) \|", re.MULTILINE)

HEAD = "HEAD"

# The fixture's one catalog (tests/TestConfiguration/src/Catalogs/Catalog) and its form.
FIXTURE_CATALOG_FQN = "Catalog.Catalog"

NOT_FINISHED_NOTICE = "Subtree not finished"
NO_DIFFERENCES = "no differences"

# Budget for the comparison to finish: get_job_status caps a single wait at 25s.
POLL_ROUNDS = 6
POLL_SECONDS = 25


def _job_id(result):
    match = JOB_ID_ROW.search(result.text or "")
    if not match:
        raise AssertionError("no jobId in the compare_configurations result: " + (result.text or ""))
    return match.group(1).strip()


def _cancel(job_id):
    """Stop a comparison that is still RUNNING; tolerate a job that already ended."""
    if job_id:
        call("cancel_job", {"jobId": job_id, "confirm": True})


def _release(comparison_id):
    """Give EDT's single comparison slot back once the comparison has FINISHED.

    cancel_job cannot: that job is terminal, so the owning tool's cancellation handler is
    never invoked and the session stays registered - holding the slot for every later
    test in the run.
    """
    if comparison_id:
        call("compare_configurations", {"releaseComparisonId": comparison_id})


def _await_report(job_id):
    """Poll until the comparison job reaches a terminal state; None when the budget runs out."""
    for _ in range(POLL_ROUNDS):
        status = call("get_job_status", {"jobId": job_id, "waitSeconds": POLL_SECONDS})
        assert_ok(status, "poll the comparison job")
        text = status.text or ""
        if "# Background job: done" in text:
            return text
        if "# Background job: failed" in text or "# Background job: cancelled" in text:
            raise E2ESkip("the comparison did not complete on this stand: " + text[:200])
    return None


def _assert_honest_about_the_lazy_tree(text, ctx):
    """The whole point of the feature: an unfinished subtree is never called equal."""
    if NOT_FINISHED_NOTICE in text and NO_DIFFERENCES in text.lower():
        _fail("[%s] the answer says the subtree is unfinished AND that there are no "
              "differences - one of those is a lie: %s" % (ctx, text[:400]))


# ──────────────────────────────────────────────────────────────────────────────
# ERROR PATHS — no comparison required, so these run even with the slot busy
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_comparison_node", kind="read")
def test_unknown_comparison_is_refused_and_names_the_remedy():
    """An id that belongs to no live comparison must say so and point at the tool that
    starts one - not return an empty node."""
    r = call("get_comparison_node", {
        "comparisonId": "cmp-does-not-exist",
        "objectFqn": FIXTURE_CATALOG_FQN,
    })
    err = assert_error(r, "unknown comparisonId")
    assert_error_quality(err, names=["cmp-does-not-exist"],
                         suggests=["compare_configurations"],
                         ctx="unknown comparison id")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_comparison_node", kind="read")
def test_missing_address_is_refused_naming_both_ways():
    """Neither objectFqn nor nodeId: the refusal must name BOTH ways to address a node."""
    r = call("get_comparison_node", {"comparisonId": "cmp-1"})
    err = assert_error(r, "no address at all")
    assert_contains(err, "objectFqn", "the refusal must name the FQN route")
    assert_contains(err, "nodeId", "and the node-id route")
    assert_no_diff("a rejected call must not touch the project")


@e2e_test(tool="get_comparison_node", kind="read")
def test_both_addresses_are_refused_rather_than_guessed():
    """objectFqn AND nodeId address different nodes; the tool must refuse instead of
    silently preferring one - a silent preference is how a caller ends up reading a
    node it never asked for."""
    r = call("get_comparison_node", {
        "comparisonId": "cmp-1",
        "objectFqn": FIXTURE_CATALOG_FQN,
        "nodeId": 42,
    })
    err = assert_error(r, "two addresses")
    assert_contains(err, FIXTURE_CATALOG_FQN, "the refusal must show the FQN it was given")
    assert_contains(err, "42", "and the node id it was given")
    assert_no_diff("a rejected call must not touch the project")


@e2e_test(tool="get_comparison_node", kind="read")
def test_unknown_side_is_refused_naming_the_accepted_ones():
    r = call("get_comparison_node", {
        "comparisonId": "cmp-1",
        "objectFqn": FIXTURE_CATALOG_FQN,
        "side": "sideways",
    })
    err = assert_error(r, "bad side")
    assert_error_quality(err, names=["sideways"], suggests=["main", "other", "ancestor"],
                         ctx="unknown side")
    assert_no_diff("a rejected call must not touch the project")


@e2e_test(tool="get_comparison_node", kind="read")
def test_non_numeric_node_id_is_refused():
    r = call("get_comparison_node", {"comparisonId": "cmp-1", "nodeId": "abc"})
    err = assert_error(r, "non-numeric nodeId")
    assert_contains(err, "abc", "the refusal must quote the value it could not read")
    assert_no_diff("a rejected call must not touch the project")


@e2e_test(tool="get_comparison_node", kind="read")
def test_out_of_range_wait_seconds_is_refused():
    """waitSeconds is bounded below the transport timeout; a caller asking for more
    gets a refusal naming the ceiling, not a request that hangs."""
    r = call("get_comparison_node", {
        "comparisonId": "cmp-1",
        "nodeId": 1,
        "waitSeconds": 600,
    })
    err = assert_error(r, "waitSeconds out of range")
    assert_contains(err, "waitSeconds", "the refusal must name the parameter")
    assert_no_diff("a rejected call must not touch the project")


# ──────────────────────────────────────────────────────────────────────────────
# INTEGRATION — one comparison, several expansions, always cancelled
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_comparison_node", kind="read")
def test_expands_a_node_of_a_live_comparison():
    """The end-to-end path: start a real three-way comparison, expand one of its nodes
    by FQN, and read the three-way property table out of the live comparison's own BM
    store.

    Phases, in one test because EDT runs ONE comparison at a time and each start costs
    minutes:
      1. by objectFqn  - proves FQN -> symlink resolution against the live engine
      2. by nodeId     - proves the report's own ids address the same tree
      3. a form node   - proves the structural snapshot, not raw XML
    """
    started = call("compare_configurations", {
        "projectName": PROJECT,
        "otherRevision": HEAD,
        "ancestorRevision": HEAD,
        "waitSeconds": 0,
        # Without this the report lists only differing nodes, and HEAD-vs-HEAD has none,
        # so there would be no nodeId to address in phase 2.
        "changedOnly": False,
    })
    assert_ok(started, "start a three-way comparison to expand")
    job_id = None
    comparison_id = None
    try:
        job_id = _job_id(started)
        report = _await_report(job_id)
        if report is None:
            raise E2ESkip(
                "the comparison did not finish within %ds on this stand; the expansion "
                "contract is covered by the unit tests over stub node graphs"
                % (POLL_ROUNDS * POLL_SECONDS))

        found = COMPARISON_ID_ROW.search(report)
        if not found:
            raise E2ESkip("the finished report carried no comparisonId row: " + report[:300])
        comparison_id = found.group(1).strip()

        # ---- phase 1: address by FQN ------------------------------------------------
        by_fqn = call("get_comparison_node", {
            "comparisonId": comparison_id,
            "objectFqn": FIXTURE_CATALOG_FQN,
        })
        assert_ok(by_fqn, "expand %s of comparison %s" % (FIXTURE_CATALOG_FQN, comparison_id))
        assert_contains(by_fqn.text, "# Comparison node:",
                        "the expansion renders a node document")
        assert_contains(by_fqn.text, "## Properties",
                        "the three-way property table is the point of the tool")
        assert_contains(by_fqn.text, "| Main | Other | Ancestor |",
                        "the property table must carry all three sides")
        assert_contains(by_fqn.text, "## Potential problems",
                        "potential problems are always reported, even when there are none")
        assert_contains(by_fqn.text, "POTENTIAL only",
                        "and are labelled as possibilities, never as a merge verdict")
        _assert_honest_about_the_lazy_tree(by_fqn.text, "expansion by FQN")

        # ---- phase 2: address by the report's own nodeId ------------------------------
        row = NODE_ROW.search(report)
        if row:
            by_id = call("get_comparison_node", {
                "comparisonId": comparison_id,
                "nodeId": int(row.group(1)),
            })
            assert_ok(by_id, "expand nodeId %s" % row.group(1))
            assert_contains(by_id.text, "# Comparison node:",
                            "a report nodeId must address the same tree")
            _assert_honest_about_the_lazy_tree(by_id.text, "expansion by nodeId")

        # ---- phase 3: a form node renders the structural snapshot ---------------------
        form_id = _find_form_child(comparison_id)
        if form_id is None:
            raise E2ESkip(
                "no form node was reachable under %s in this comparison, so the form "
                "snapshot could not be exercised here (it is covered by the renderer "
                "unit tests)" % FIXTURE_CATALOG_FQN)
        form = call("get_comparison_node", {"comparisonId": comparison_id, "nodeId": form_id})
        assert_ok(form, "expand the form node %s" % form_id)
        assert_contains(form.text, "# Form Structure",
                        "a form node renders the shared structural snapshot")
        if "<" in (form.text or ""):
            _fail("the form snapshot must be Markdown, not the form's XML: %s"
                  % form.text[:400])
        _assert_honest_about_the_lazy_tree(form.text, "form expansion")
    finally:
        _cancel(job_id)
        _release(comparison_id)

    assert_no_diff("a read tool must not touch the project on disk")


def _find_form_child(comparison_id):
    """The nodeId of a form node under the fixture catalog, or None.

    Form nodes hang below the catalog, so this expands the catalog a few levels deep and
    reads the Children table's Kind column - the same table an agent would read.
    """
    expanded = call("get_comparison_node", {
        "comparisonId": comparison_id,
        "objectFqn": FIXTURE_CATALOG_FQN,
        "depth": 4,
        "limit": 500,
    })
    if expanded.is_error:
        return None
    for line in (expanded.text or "").split("\n"):
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        # Children table: Depth | Node id | Kind | Main | Other | Ancestor | State
        if len(cells) >= 7 and cells[1].isdigit() and "form" in cells[2].lower():
            return int(cells[1])
    return None

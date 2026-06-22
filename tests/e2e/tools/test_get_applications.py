"""
e2e tests for get_applications (kind: read).

What the tool does
------------------
get_applications enumerates the *applications* (infobases) registered for an EDT
project via IApplicationManager.getApplications(project). Each application carries
an id, name, type id, and update state; the tool also reports the default
application id. The returned application *id* is the round-trip input that
update_database and debug_launch consume (see the tool description) — so this is a
discovery/read tool, never a mutator.

Response shape (IMPORTANT)
--------------------------
GetApplicationsTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text — for a JSON tool r.text is just a "Done"/error
placeholder). The success envelope is:
    {"success": true,
     "project": "<name>",
     "applications": [ {"id","name","type","updateState","updateStateDescription",
                        ["requiredVersion"]}, ... ],
     "count": <int>,                       # == len(applications)
     ["defaultApplicationId": "<id>"],     # only when a default exists
     ["message": "No applications found for project"]}  # only on the empty branch
On error the envelope is {"success": false, "error": "<message>"} and the protocol
layer marks the result isError; assert_error returns that error string.

Why the happy path does NOT assert a fixed application id/name
--------------------------------------------------------------
Applications/infobases are NOT part of the git-tracked TestConfiguration/ tree —
they live in the EDT *workspace* (.metadata) and are created at runtime when an
infobase is registered. The fixture checkout has none guaranteed. The set of
applications is therefore environment-dependent and asserting a fixed id would be
flaky. Instead the happy path asserts the tool's STRUCTURAL INVARIANTS, which break
if the tool is broken regardless of how many applications exist:
  - the JSON envelope is present and well-formed (success=true, project echoed),
  - "applications" is a list and "count" is an int,
  - count == len(applications)   (the tool derives count from applications.size()),
  - if applications exist, every entry carries the round-trip "id" + "name" the
    sibling tools (update_database / debug_launch) require,
  - if NO applications exist, the tool emits the explicit empty-branch contract
    (count==0, applications==[], the "No applications found" message) rather than
    garbage or a crash.
A read tool must never touch the project tree, so every test ends with
assert_no_diff().

Inherited applications for a DEPENDENT project (issue #203)
-----------------------------------------------------------
An external-objects (V8ExternalObjectsNature) or extension project has NO
applications of its own — both share the EDT supertype IDependentProject, whose
getParentProject() points at the BASE configuration that owns the infobases. The
tool therefore RE-RUNS the lookup against the parent when the named (dependent)
project has none, and surfaces the base project's applications. On that inherited
branch the envelope keeps echoing the NAMED (dependent) project in "project" and
adds a NEW field "inheritedFromProject" == the base project name. The field is
emitted ONLY on the inherited branch; a plain Configuration project (never an
IDependentProject) never carries it, so its envelope stays byte-identical.

This is exercised by test_dependent_project_inherits_base_applications below. It is
ENVIRONMENT-TOLERANT in the SAME spirit as the happy path's count==0 handling: it
only asserts the inherited contract when a dependent fixture bound to a base WITH
applications is actually present in the workspace (applications live in the EDT
workspace, not the git fixture, so neither the dependent project nor its base is
guaranteed to have any). When no such fixture/application exists the inherited
assertions are skipped, so the case never fails in a headless CI workspace.

Parameter shape (from GetApplicationsTool.getInputSchema / execute)
-------------------------------------------------------------------
There is exactly ONE parameter: projectName (string, REQUIRED). There is NO enum,
NO XOR pair, and NO conditional-required parameter on this tool, so the
"invalid enum" / "mutually-exclusive" / "conditional-required" negatives from the
matrix do not apply here (documented, not skipped). The reachable negatives are:
  - missing required projectName  -> requireArgument -> "projectName is required"
  - empty-string projectName      -> same guard (extractStringArgument -> isEmpty)
  - non-existent project          -> the readiness pre-check
    (ProjectStateChecker.buildingErrorOrNull) returns null for a non-building name,
    so control falls through to getApplications, where ProjectContext.of(name)
    has exists()==false and the tool returns "Project not found: <name>".
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
    TESTS_PROJECT,
)


# Natures that identify an EDT DEPENDENT project (IDependentProject supertype): an
# extension or an external-objects project. Either inherits its applications from the
# base project it depends on (issue #203). list_projects abbreviates the nature ids to
# their last dotted segment in its Natures column, so match on those short tokens.
_DEPENDENT_NATURE_TOKENS = ("V8ExtensionNature", "V8ExternalObjectsNature")


def _project_natures():
    """Map every workspace project name -> its (abbreviated) Natures cell from
    list_projects. Used to detect, environment-tolerantly, whether a dependent
    (extension / external-objects) fixture is present without hard-coding it."""
    r = call("list_projects", {})
    assert_ok(r, "list_projects (dependent-fixture discovery)")
    natures = {}
    for line in (r.text or "").splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        # Header row is "| Name | State | ... | Natures |"; skip it and the separator.
        if len(cells) < 6 or cells[0] in ("Name", "") or set(cells[0]) <= set("-"):
            continue
        natures[cells[0]] = cells[-1]
    return natures


def _is_dependent(natures_cell):
    return any(tok in (natures_cell or "") for tok in _DEPENDENT_NATURE_TOKENS)


def _apps_envelope(r, expected_project, ctx):
    """Validate the JSON success envelope of a get_applications call that echoes
    `expected_project` (which may be a DEPENDENT project, unlike _envelope which pins
    the base PROJECT). Returns (structured, applications_list, count)."""
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    if sc.get("project") != expected_project:
        raise AssertionError(
            "envelope must echo project %r [%s]: %r" % (expected_project, ctx, sc.get("project")))
    apps = sc.get("applications")
    count = sc.get("count")
    if not isinstance(apps, list):
        raise AssertionError("'applications' must be a list [%s]: %r" % (ctx, apps))
    if not isinstance(count, int):
        raise AssertionError("'count' must be an int [%s]: %r" % (ctx, count))
    if count != len(apps):
        raise AssertionError(
            "count(%d) must equal len(applications)(%d) [%s]" % (count, len(apps), ctx))
    return sc, apps, count


def _envelope(r, ctx):
    """Validate the JSON success envelope and return (applications_list, count).

    A JSON tool MUST populate structuredContent; a missing/typed-wrong envelope
    means the tool returned the wrong shape (a real regression), so we hard-fail
    rather than tolerate it."""
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    # Echoed project name proves the tool walked the requested project, not a stray.
    if sc.get("project") != PROJECT:
        raise AssertionError(
            "envelope must echo project %r [%s]: %r" % (PROJECT, ctx, sc.get("project")))
    apps = sc.get("applications")
    count = sc.get("count")
    if not isinstance(apps, list):
        raise AssertionError("'applications' must be a list [%s]: %r" % (ctx, apps))
    if not isinstance(count, int):
        raise AssertionError("'count' must be an int [%s]: %r" % (ctx, count))
    # count is built from applications.size() — the two MUST agree; a desync means
    # the tool miscounted (e.g. counted before/after a filter), a real bug.
    if count != len(apps):
        raise AssertionError(
            "count(%d) must equal len(applications)(%d) [%s]" % (count, len(apps), ctx))
    return apps, count


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_applications", kind="read")
def test_returns_consistent_envelope_and_does_not_mutate():
    """Valid project -> a well-formed JSON envelope whose count matches the entry
    list, and which carries the round-trip id/name on each entry when any exist.

    This is render/environment-dependent on whether an infobase is registered:
      - if applications exist, each MUST expose the 'id' + 'name' update_database /
        debug_launch consume (the whole point of the tool);
      - if none exist, the tool MUST take its explicit empty branch (count==0,
        applications==[], a "No applications found" message) — NOT crash or emit
        garbage. Either way is a VALID observed contract; we assert whichever the
        environment produced and document the dependency here.
    A broken tool (returning the wrong shape, a desynced count, or entries without
    an id) fails this regardless of the environment's application count."""
    r = call("get_applications", {"projectName": PROJECT})
    assert_ok(r, "get_applications on the fixture project")

    sc = r.structured
    apps, count = _envelope(r, "fixture project envelope")

    if count == 0:
        # Empty branch is a real, distinct code path: the tool sets count=0, an
        # empty list, AND a specific human message. Assert all three so a tool that
        # silently returns an empty envelope without taking this branch is caught.
        if apps != []:
            raise AssertionError("count==0 must come with applications==[]: %r" % apps)
        if sc.get("message") != "No applications found for project":
            raise AssertionError(
                "empty branch must carry the explicit 'No applications found' message: %r"
                % sc.get("message"))
    else:
        # Non-empty: every entry must carry the round-trip identifiers the sibling
        # tools require. Missing 'id' would break update_database / debug_launch.
        for entry in apps:
            if not isinstance(entry, dict):
                raise AssertionError("each application entry must be an object: %r" % entry)
            if not entry.get("id"):
                raise AssertionError("every application entry must carry a non-empty 'id': %r" % entry)
            if "name" not in entry:
                raise AssertionError("every application entry must carry a 'name': %r" % entry)
        # When entries exist the tool also computes a default application id; if it
        # reported one, it MUST be one of the listed application ids (it is derived
        # from getDefaultApplication on the same project). This catches a default
        # id that points outside the returned set.
        default_id = sc.get("defaultApplicationId")
        if default_id is not None:
            ids = [e.get("id") for e in apps]
            if default_id not in ids:
                raise AssertionError(
                    "defaultApplicationId %r must be one of the listed application ids %r"
                    % (default_id, ids))

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_applications", kind="read")
def test_success_flag_set_and_no_error_field_on_happy_path():
    """The success envelope must positively flag success=true and must NOT carry an
    'error' field. This discriminates the success payload from the error payload
    ({"success": false, "error": ...}) — a tool that mislabels a successful read as
    an error (or vice-versa) is caught here, independent of how many apps exist."""
    r = call("get_applications", {"projectName": PROJECT})
    assert_ok(r, "get_applications success flag")
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict: %r" % sc)
    if sc.get("success") is not True:
        raise AssertionError("happy-path envelope must set success=true: %r" % sc)
    if "error" in sc:
        raise AssertionError("happy-path envelope must NOT carry an 'error' field: %r" % sc)
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# DEPENDENT-PROJECT INHERITANCE (issue #203)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_applications", kind="read")
def test_dependent_project_inherits_base_applications():
    """A DEPENDENT project (extension / external-objects, IDependentProject) inherits
    its base project's applications, and the envelope flags the inheritance.

    ENVIRONMENT-TOLERANT (issue #203): applications live in the EDT workspace, not the
    git fixture, so neither a dependent project nor its base is guaranteed to have any.
    The inherited branch only fires when a dependent project's OWN application set is
    empty AND its base owns applications. We therefore:
      - discover whether the canonical dependent fixture is present (the extension
        fixture TestConfiguration.tests == TESTS_PROJECT, base = PROJECT); we pin to it
        so the base it inherits from is KNOWN to be PROJECT and the inheritedFromProject /
        count assertions below stay valid by construction. If it is absent there is
        nothing to assert here (mirrors how the happy path tolerates count==0) and the
        case is a no-op pass;
      - only assert the inherited contract when the BASE project actually has
        applications (otherwise the dependent legitimately also has none and the
        inherited branch never runs).
    When the contract IS exercised it pins the #203 fix:
      - success=true and a well-formed, count-consistent envelope;
      - every inherited entry carries the round-trip id + name (so update_database /
        debug_launch can consume them through the dependent project);
      - count == the base project's application count (the set was inherited verbatim);
      - "inheritedFromProject" == the BASE project name (the new field);
      - "project" still echoes the NAMED (dependent) project, NOT the base.
    A regression that stopped inheriting (empty dependent envelope) or mislabelled the
    fields fails here whenever the fixture is present."""
    natures = _project_natures()

    # Exercise the inherited contract ONLY for the dependent project whose base is KNOWN
    # to be our base PROJECT: the canonical extension fixture TESTS_PROJECT
    # (TestConfiguration.tests, base = PROJECT). Pinning to it keeps the count==base_count
    # and inheritedFromProject==PROJECT assertions below valid by construction — a generic
    # "pick any dependent project" fallback would contradict them, since a dependent whose
    # base is some OTHER project would legitimately inherit a different base name/count and
    # falsely fail the test even though the tool behaved correctly.
    dependent = None
    if TESTS_PROJECT in natures and _is_dependent(natures[TESTS_PROJECT]):
        dependent = TESTS_PROJECT
    if dependent is None:
        # No dependent fixture whose base is PROJECT in this workspace -> nothing to
        # exercise (CI-safe no-op, mirrors how the happy path tolerates count==0).
        assert_no_diff("a read tool must not touch the project on disk")
        return

    # The base must actually own applications for the inherited branch to fire.
    base_r = call("get_applications", {"projectName": PROJECT})
    assert_ok(base_r, "base project applications (for inheritance comparison)")
    _, base_apps, base_count = _envelope(base_r, "base project envelope")
    if base_count == 0:
        # Base has no applications -> the dependent legitimately inherits nothing; the
        # inherited branch is not reachable in this environment. Skip the assertion the
        # same way the happy path skips the non-empty checks when count==0.
        assert_no_diff("a read tool must not touch the project on disk")
        return

    # Inherited branch is reachable: the dependent project must surface the base's apps.
    r = call("get_applications", {"projectName": dependent})
    assert_ok(r, "get_applications on the dependent project")
    sc, apps, count = _apps_envelope(r, dependent, "dependent project envelope")

    if count != base_count:
        raise AssertionError(
            "dependent count(%d) must equal the base application count(%d) it inherits"
            % (count, base_count))
    for entry in apps:
        if not isinstance(entry, dict):
            raise AssertionError("each inherited application entry must be an object: %r" % entry)
        if not entry.get("id"):
            raise AssertionError("every inherited entry must carry a non-empty 'id': %r" % entry)
        if "name" not in entry:
            raise AssertionError("every inherited entry must carry a 'name': %r" % entry)
    # The defining #203 signal: the new field names the BASE project the apps came from.
    if sc.get("inheritedFromProject") != PROJECT:
        raise AssertionError(
            "inherited envelope must set inheritedFromProject==%r (the base project): %r"
            % (PROJECT, sc.get("inheritedFromProject")))
    # And the envelope still identifies the NAMED dependent project, not the base.
    if sc.get("project") != dependent:
        raise AssertionError(
            "inherited envelope must keep echoing the named project %r, not the base: %r"
            % (dependent, sc.get("project")))
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_applications", kind="read")
def test_configuration_project_carries_no_inherited_field():
    """A plain Configuration project (never an IDependentProject) must NEVER carry the
    inheritedFromProject field — its output stays byte-identical to before #203. This
    guards the other half of the fix: the new field is emitted ONLY on the inherited
    branch, so a tool that started tagging every project with it is caught here. Holds
    regardless of how many applications the base has (the field is about the project
    KIND, not the application count)."""
    r = call("get_applications", {"projectName": PROJECT})
    assert_ok(r, "get_applications on the base configuration project")
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict: %r" % sc)
    if "inheritedFromProject" in sc:
        raise AssertionError(
            "a Configuration project envelope must NOT carry 'inheritedFromProject': %r" % sc)
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_applications", kind="read")
def test_missing_projectname_errors_clearly():
    """Required param omitted -> JsonUtils.requireArgument -> "projectName is
    required" delivered as a structured isError."""
    r = call("get_applications", {})
    err = assert_error(r, "missing required projectName")
    # The message names the missing parameter AND steers to the discovery tool: the shared
    # required-arg guard maps projectName -> list_projects, so the error is actionable.
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName names the param + steers to list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_applications", kind="read")
def test_empty_projectname_errors_like_missing():
    """Boundary: projectName="" is treated as missing by extractStringArgument
    (value.isEmpty()) -> the SAME "projectName is required" guard fires. This proves
    the empty string is not mistaken for a valid project name (which would later
    blow up deeper in the EDT lookup with an opaque error)."""
    r = call("get_applications", {"projectName": ""})
    err = assert_error(r, "empty-string projectName")
    # Same canonical guard as the missing case - and the same actionable list_projects hint.
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="empty projectName hits the required-arg guard")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_applications", kind="read")
def test_nonexistent_project_errors_and_does_not_mutate():
    """A syntactically valid but non-existent project name must error, not return a
    bogus empty success.

    ORDERING NOTE: in execute() the readiness pre-check
    ProjectStateChecker.buildingErrorOrNull(projectName) refuses ONLY the transient
    BUILDING state and returns null otherwise. For a name that is not a workspace
    project it returns null, so control falls through to getApplications, where
    ProjectContext.of(name) has exists()==false and the tool returns the
    value-naming "Project not found: <name>".

    The not-found wording is the shared ProjectContext.notFoundMessage(projectName),
    which both names the value AND points at the next-step discovery tool:
    "Project not found: <bad>. Use list_projects to see available projects." So:
      - names=[bad] asserts the reachable "Project not found: <bad>" message text (a
        broken tool that returned a fake empty-success envelope instead of an error
        would fail assert_error outright);
      - suggests=["list_projects"] asserts the actionable next-step tail is present."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_applications", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # Assert the reachable, value-naming not-found message AND its actionable tail.
    # This still fails loudly if the tool stopped erroring on an unknown project, and
    # now also pins the shared ProjectContext.notFoundMessage list_projects next-step.
    assert_error_quality(err, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project is named in the not-found error")
    assert_no_diff("an invalid call must not touch the project on disk")

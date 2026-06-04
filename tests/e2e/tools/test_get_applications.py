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
)


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
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_applications", kind="read")
def test_missing_projectname_errors_clearly():
    """Required param omitted -> JsonUtils.requireArgument -> "projectName is
    required" delivered as a structured isError."""
    r = call("get_applications", {})
    err = assert_error(r, "missing required projectName")
    # The message names the missing parameter.
    # AUDIT: "projectName is required" names the param but offers NO next step
    # (no mention of list_projects to discover a valid project name). suggests=[]
    # is deliberate; fix-card: make the required-arg guard actionable.
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_applications", kind="read")
def test_empty_projectname_errors_like_missing():
    """Boundary: projectName="" is treated as missing by extractStringArgument
    (value.isEmpty()) -> the SAME "projectName is required" guard fires. This proves
    the empty string is not mistaken for a valid project name (which would later
    blow up deeper in the EDT lookup with an opaque error)."""
    r = call("get_applications", {"projectName": ""})
    err = assert_error(r, "empty-string projectName")
    # Same canonical guard as the missing case.
    # AUDIT: as above, names the param but is not actionable -> suggests=[].
    assert_error_quality(err, names=["projectName"], suggests=[],
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

    The error names the offending project value, so:
      - names=[bad] asserts the reachable "Project not found: <bad>" message text (a
        broken tool that returned a fake empty-success envelope instead of an error
        would fail assert_error outright);
      - suggests=[] (no next-step tool in the message; the list_projects discovery
        tail is a separate change)."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_applications", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # Assert the reachable, value-naming not-found message. This still fails loudly
    # if the tool stopped erroring on an unknown project.
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project is named in the not-found error")
    assert_no_diff("an invalid call must not touch the project on disk")

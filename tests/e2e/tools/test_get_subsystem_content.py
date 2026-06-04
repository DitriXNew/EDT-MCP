"""
e2e tests for get_subsystem_content (kind: read).

The tool returns MARKDOWN (getResponseType() == MARKDOWN), so the payload is in
r.text (NOT r.structured). It resolves a subsystem by FQN and renders its
properties, content objects and child subsystems.

Fixture truth (TestConfiguration/src/Subsystems/Subsystem/Subsystem.mdo):
  - one subsystem, name "Subsystem", synonym(en) "Subsystem"
  - includeInCommandInterface=true, includeHelpInContents=true
  - NO content objects, NO child subsystems
FQN to address it: "Subsystem.Subsystem".

Real error paths in GetSubsystemContentTool.execute / getSubsystemContentInternal:
  - missing projectName     -> "projectName is required"
  - missing subsystemFqn    -> "subsystemFqn is required (e.g. 'Subsystem.Sales')"
  - project not found        -> "Project not found: <name>"
  - subsystem not resolvable -> "Subsystem not found: <fqn>. Check the FQN is
    'Subsystem.<Name>' (type token must be 'Subsystem'); use list_subsystems to
    see available subsystems."  (covers both a non-existent subsystem AND a
    malformed FQN — wrong type token / missing name, because
    SubsystemUtils.resolveByFqn returns null for all of them).
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_subsystem_content", kind="read")
def test_renders_fixture_subsystem_and_does_not_mutate():
    """Happy path: address the real fixture subsystem by FQN and assert the
    rendered markdown carries its real, fixture-specific content."""
    r = call("get_subsystem_content", {
        "projectName": PROJECT,
        "subsystemFqn": "Subsystem.Subsystem",
    })
    assert_ok(r, "get_subsystem_content on Subsystem.Subsystem")

    # MARKDOWN tool -> data is in r.text.
    # Header line is "# Subsystem: <Name> (<synonym>)" -> name from the .mdo.
    assert_contains(r.text, "# Subsystem: Subsystem",
                    "markdown header must carry the real subsystem name")
    # Properties table renders the FQN we passed and the synonym from the .mdo.
    assert_contains(r.text, "Subsystem.Subsystem",
                    "properties table must echo the requested FQN")
    # includeInCommandInterface=true in the fixture -> "Include In Command Interface | Yes".
    # A broken tool reading the wrong flag (or hardcoding) would not produce this exact pair.
    assert_contains(r.text, "Include In Command Interface | Yes",
                    "must reflect includeInCommandInterface=true from the .mdo")
    # The fixture subsystem has no content -> the tool emits this explicit marker.
    assert_contains(r.text, "No objects in this subsystem",
                    "empty-content fixture must render the no-objects marker")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_subsystem_content", kind="read")
def test_recursive_flag_renders_recursive_content_heading():
    """recursive=true is a real branch: the Content heading switches to
    '## Content (recursive)'. Assert the branch is honoured (not ignored)."""
    r = call("get_subsystem_content", {
        "projectName": PROJECT,
        "subsystemFqn": "Subsystem.Subsystem",
        "recursive": True,
    })
    assert_ok(r, "get_subsystem_content recursive=true")
    # Only the recursive branch appends " (recursive)" to the Content heading;
    # a tool that drops the flag would render the plain "## Content" heading instead.
    assert_contains(r.text, "## Content (recursive)",
                    "recursive=true must switch the Content heading")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_subsystem_content", kind="read")
def test_missing_project_name_errors_clearly():
    """Missing required projectName."""
    r = call("get_subsystem_content", {
        "subsystemFqn": "Subsystem.Subsystem",
    })
    e = assert_error(r, "missing projectName")
    # Message names the missing parameter.
    # AUDIT: "projectName is required" names the param but offers no next step
    # (no sibling tool such as list_projects to discover a valid project) ->
    # suggests=[] intentionally. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[])
    assert_no_diff()


@e2e_test(tool="get_subsystem_content", kind="read")
def test_missing_subsystem_fqn_errors_clearly():
    """Missing required subsystemFqn."""
    r = call("get_subsystem_content", {
        "projectName": PROJECT,
    })
    e = assert_error(r, "missing subsystemFqn")
    # This guard appends a usage hint with a concrete example FQN form, so it IS
    # actionable: it names the param and shows the expected shape "Subsystem.".
    assert_error_quality(e, names=["subsystemFqn"], suggests=["Subsystem."])
    assert_no_diff()


@e2e_test(tool="get_subsystem_content", kind="read")
def test_nonexistent_project_errors_clearly():
    """Valid-shaped args but the project does not exist."""
    bad = "NoSuchProject_e2e"
    r = call("get_subsystem_content", {
        "projectName": bad,
        "subsystemFqn": "Subsystem.Subsystem",
    })
    e = assert_error(r, "non-existent project")
    # Message names the bad project value AND is actionable: ProjectContext.notFoundMessage
    # appends "Use list_projects to see available projects."
    assert_error_quality(e, names=[bad], suggests=["list_projects"])
    assert_no_diff()


@e2e_test(tool="get_subsystem_content", kind="read")
def test_nonexistent_subsystem_errors_clearly():
    """Well-formed FQN, correct type token, but no such subsystem exists."""
    bad = "Subsystem.NoSuchSubsystem_e2e"
    r = call("get_subsystem_content", {
        "projectName": PROJECT,
        "subsystemFqn": bad,
    })
    e = assert_error(r, "non-existent subsystem")
    # Message must name the bad FQN and point at the discovery tool.
    assert_error_quality(e, names=[bad], suggests=["list_subsystems"])
    assert_no_diff()


@e2e_test(tool="get_subsystem_content", kind="read")
def test_malformed_fqn_wrong_type_token_errors_clearly():
    """A non-subsystem type token (Catalog.Catalog is a real object, but the wrong
    KIND): parseSubsystemPath rejects the token -> resolveByFqn returns null ->
    'Subsystem not found'. Confirms the tool does not silently accept a catalog FQN."""
    bad = "Catalog.Catalog"
    r = call("get_subsystem_content", {
        "projectName": PROJECT,
        "subsystemFqn": bad,
    })
    e = assert_error(r, "wrong type token in FQN")
    # The bad FQN value must appear in the message, and the message must point at
    # the discovery tool so a wrong-type-token FQN has an actionable next step.
    assert_error_quality(e, names=[bad], suggests=["list_subsystems"])
    assert_no_diff()

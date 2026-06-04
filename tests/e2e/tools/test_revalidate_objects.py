"""
e2e tests for revalidate_objects (kind: action).

WHAT THE TOOL DOES (RevalidateObjectsTool.java):
  Refreshes the project from disk, then either
    - full project revalidation  (objects array empty/missing) -> INCREMENTAL_BUILD
      + waitForBuildAndDerivedData, OR
    - partial revalidation        (objects = [FQN, ...]) -> look up each FQN in the BM
      model (inside a READ transaction) and schedule validation for the ones found.
  It returns MARKDOWN (getResponseType() == MARKDOWN), so the payload is in r.text
  (NOT r.structured). The FrontMatter header carries, on success:
    tool: revalidate_objects
    status: success
    project: <projectName>
    mode: full        (full revalidation)   OR   mode: objects (partial)
  The partial body lists per-FQN outcomes under "## Validated (n)" /
  "## Not found (n)" / "## Skipped (no persistent id) (n)" tables.

ACTION-TOOL SAFETY — why the happy path is safe here:
  revalidate_objects recomputes VALIDATION MARKERS / derived data (workspace +
  infobase state), and `project.refreshLocal(...)` only re-reads the project files
  from disk; it does NOT write the project tree. So a happy call must leave
  TestConfiguration byte-for-byte unchanged -> the correct happy assertion is
  assert_ok + assert the real MARKDOWN status + assert_no_diff(). There is NO
  destructive happy path to omit here: unlike import_configuration_from_xml /
  update_database, this tool never imports or mutates the configuration, so a real
  full+partial happy call against the committed fixture is correct and non-corrupting.

KEY EDGE: a non-existent FQN in `objects` is NOT an error — partial revalidation
  reports it in the "Not found" section and still returns status: success. That is a
  deliberate, asserted edge case below (a broken lookup that silently dropped the FQN,
  or mis-reported it as "Validated", would fail it).

REAL ERROR / SENTINEL PATHS (read from the Java):
  - projectName missing/empty:
      execute() reads projectName via extractStringArgument; when empty it SKIPS the
      ProjectStateChecker pre-check, then revalidateObjects() hits the guard
      `return ToolResult.error("projectName is required")`.
  - projectName non-existent:
      execute() calls ProjectStateChecker.buildingErrorOrNull(projectName) FIRST. That
      pre-check refuses ONLY the transient BUILDING state and returns null for an unknown
      name, so control falls through to revalidateObjects' own resolution:
      ProjectContext.of(name).exists() is false ->
      ToolResult.error(ProjectContext.notFoundMessage(name)). The user therefore sees the
      shared, actionable message that echoes the bad name AND points at list_projects:
      "Project not found: <name>. Use list_projects to see available projects."
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (safe: recompute markers, never touch the project tree)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="revalidate_objects", kind="action")
def test_full_project_revalidation_reports_success_and_does_not_mutate():
    """Full revalidation: omit `objects` -> the tool runs an INCREMENTAL_BUILD and
    returns the full-mode MARKDOWN status. Asserts the real FrontMatter contract
    (status: success, mode: full, the right project) AND that no project file
    changed on disk.

    Mutation thinking: a tool that no-oped, returned an error, swapped the mode
    token, or reported the wrong project would fail one of these. A tool that
    secretly wrote into the project tree would fail assert_no_diff().
    """
    r = call("revalidate_objects", {"projectName": PROJECT})
    assert_ok(r, "full project revalidation")

    # FrontMatter (in r.text for a MARKDOWN tool) must tag the producing tool,
    # carry success status, name the project, and select the FULL mode branch.
    assert_contains(r.text, "tool: revalidate_objects",
                    "FrontMatter must tag the producing tool")
    assert_contains(r.text, "status: success",
                    "full revalidation must report success status")
    assert_contains(r.text, "project: " + PROJECT,
                    "FrontMatter must echo the requested project")
    assert_contains(r.text, "mode: full",
                    "omitting `objects` must select the full-project mode")
    # Body heading is mode-specific: full uses "# Full project revalidation completed".
    assert_contains(r.text, "# Full project revalidation completed",
                    "full-mode body heading must render")

    assert_no_diff("revalidation recomputes markers only; the project tree must be clean")


@e2e_test(tool="revalidate_objects", kind="action")
def test_partial_revalidation_validates_real_fqn_and_does_not_mutate():
    """Partial revalidation of a REAL fixture object (Catalog.Catalog exists, name
    'Catalog'): the tool looks it up in the BM model, schedules validation, and
    reports it under "## Validated (1)" with mode: objects.

    Mutation thinking: a broken FQN lookup (wrong normalization, reading outside the
    BM read transaction, or dropping the object) would push Catalog.Catalog into the
    "Not found" section instead of "Validated", failing the Validated assertion. The
    objectsFound: 1 frontmatter count is the second independent signal.
    """
    r = call("revalidate_objects", {
        "projectName": PROJECT,
        "objects": ["Catalog.Catalog"],
    })
    assert_ok(r, "partial revalidation of Catalog.Catalog")

    assert_contains(r.text, "status: success",
                    "partial revalidation of a real object must succeed")
    assert_contains(r.text, "mode: objects",
                    "passing `objects` must select the per-object mode")
    assert_contains(r.text, "objectsRequested: 1",
                    "frontmatter must count the one requested FQN")
    assert_contains(r.text, "objectsFound: 1",
                    "Catalog.Catalog exists -> it must be counted as found")
    # The found object is listed in the Validated table; "Not found" must NOT appear
    # for a real object (no spurious not-found section).
    assert_contains(r.text, "## Validated (1)",
                    "the resolved object must be reported under Validated")
    assert_contains(r.text, "Catalog.Catalog",
                    "the validated FQN must be echoed in the body")

    assert_no_diff("partial revalidation recomputes markers only; project tree must be clean")


@e2e_test(tool="revalidate_objects", kind="action")
def test_partial_revalidation_nonexistent_fqn_is_reported_not_errored():
    """EDGE (not an error): a non-existent FQN in `objects` is NOT a tool error.
    Partial revalidation reports it under "## Not found (n)" and still returns
    status: success with objectsFound: 0. This pins the documented behaviour: a
    missing object is surfaced, not silently dropped and not raised as isError.

    Mutation thinking: if the lookup mis-reported the missing FQN as "Validated"
    (objectsFound: 1) or the tool raised isError, this fails. The bad FQN value must
    appear in the body so the caller knows WHICH object was missing.
    """
    bad = "Catalog.NoSuchCatalog_e2e"
    r = call("revalidate_objects", {
        "projectName": PROJECT,
        "objects": [bad],
    })
    # This is a SUCCESS response (the tool succeeds, the object is merely "not found").
    assert_ok(r, "partial revalidation with a non-existent FQN is a success, not an error")

    assert_contains(r.text, "status: success",
                    "a missing object is reported, not raised as a tool error")
    assert_contains(r.text, "objectsFound: 0",
                    "a non-existent FQN must NOT be counted as found")
    assert_contains(r.text, "## Not found (1)",
                    "the missing object must be surfaced in the Not found section")
    assert_contains(r.text, bad,
                    "the Not found section must name WHICH FQN was missing")
    # Guardrail: it must NOT have invented a Validated section for the missing object.
    assert_not_contains(r.text, "## Validated",
                        "a non-existent object must not appear as Validated")

    assert_no_diff("a no-op lookup must not touch the project tree")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="revalidate_objects", kind="action")
def test_missing_project_name_errors_clearly():
    """Missing required projectName. execute() skips the ProjectStateChecker
    pre-check (empty name) and revalidateObjects hits the guard
    `ToolResult.error("projectName is required")`.

    Must be a clean, named required-arg error -> NOT a NullPointer / generic failure.
    """
    r = call("revalidate_objects", {"objects": ["Catalog.Catalog"]})
    e = assert_error(r, "missing projectName")
    # Message names the missing parameter.
    # AUDIT: "projectName is required" names the param but offers no next step
    # (no sibling such as list_projects to discover a valid project) -> suggests=[]
    # intentionally. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("a rejected call must not touch the project tree")


@e2e_test(tool="revalidate_objects", kind="action")
def test_empty_project_name_errors_clearly():
    """Boundary: projectName present but the EMPTY string. extractStringArgument
    yields an empty value -> the pre-check is skipped (its guard is
    `projectName != null && !projectName.isEmpty()`) and revalidateObjects' own
    `projectName == null || projectName.isEmpty()` guard fires with
    "projectName is required". An empty name must NOT silently resolve to a default
    project (that would be a silent-success bug).
    """
    r = call("revalidate_objects", {"projectName": "", "objects": ["Catalog.Catalog"]})
    e = assert_error(r, "empty-string projectName")
    # AUDIT: empty value is reported via the same "projectName is required" guard;
    # it does not distinguish "empty" from "omitted" and offers no next step.
    # suggests=[] intentional; fix-card to make the guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="empty projectName rejected by the required-arg guard")
    assert_no_diff("a rejected call must not touch the project tree")


@e2e_test(tool="revalidate_objects", kind="action")
def test_nonexistent_project_errors_clearly():
    """Valid-shaped args, but the project does not exist. execute() runs
    ProjectStateChecker.buildingErrorOrNull(projectName) FIRST; that pre-check refuses
    ONLY the transient BUILDING state and returns null for an unknown name, so control
    falls through to revalidateObjects' own resolution: ProjectContext.of(bad).exists()
    is false -> ToolResult.error(ProjectContext.notFoundMessage(bad)).

    Assert the real downstream error -- it echoes the bad name the caller passed AND
    points at list_projects to discover a valid project, which is the diagnostic plus
    next step the user actually needs.
    """
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("revalidate_objects", {
        "projectName": bad,
        "objects": ["Catalog.Catalog"],
    })
    e = assert_error(r, "non-existent project")
    # The error surfaced is the shared ProjectContext.notFoundMessage(name) branch:
    # "Project not found: <name>. Use list_projects to see available projects." -- it
    # echoes the bad project name AND points the caller at list_projects to discover a
    # valid one, so the error is actionable.
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("a rejected call must not touch the project tree")


@e2e_test(tool="revalidate_objects", kind="action")
def test_nonexistent_project_with_full_mode_errors_clearly():
    """Same unknown-project case, but via the FULL-mode path (no `objects` at all),
    to prove an unknown project is rejected regardless of mode (a full revalidation of
    a non-existent project must NOT silently "succeed"). The BUILDING-only pre-check
    returns null for an unknown name, so revalidateObjects' own resolution emits the
    same shared, actionable ProjectContext.notFoundMessage(name) error as the
    partial-mode case (names the value AND points at list_projects).
    """
    bad = "GhostProject_full_e2e"
    r = call("revalidate_objects", {"projectName": bad})
    e = assert_error(r, "non-existent project, full mode")
    # Same shared ProjectContext.notFoundMessage(name) branch as the partial-mode case:
    # it names the bad value AND points the caller at list_projects to discover a valid one.
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="full-mode unknown project names the bad value and points at list_projects")
    assert_no_diff("a rejected call must not touch the project tree")

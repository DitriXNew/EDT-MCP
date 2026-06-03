"""
e2e tests for get_translation_project_info (kind: read).

The tool wraps LanguageTool's IProjectInformationApi (via reflection) and returns
MARKDOWN (getResponseType() == MARKDOWN), so the payload is in r.text
(NOT r.structured). The body has two fixed sections plus a FrontMatter header:

    ---
    tool: get_translation_project_info
    project: <projectName>
    storagesCount: <int>
    providersCount: <int>
    ---
    ## Storages

    - <storage id>        (or "(none)" when no dictionary storage is attached)

    ## Translation providers

    - <provider id>       (or "(none)" when LanguageTool exposes none)

ENVIRONMENT DEPENDENCY (documented, not a bug):
  This tool REQUIRES EDT with LanguageTool installed. Activator.getProjectInformationApi()
  returns null when LanguageTool is absent, and execute() then returns
  ToolResult.error("LanguageTool IProjectInformationApi is not available. Install
  LanguageTool in EDT."). The TestConfiguration fixture also has NO dictionary storage
  attached, so even WITH LanguageTool the Storages list is legitimately empty ("(none)",
  storagesCount: 0). The happy-path test therefore asserts the STRUCTURAL contract that
  holds whenever the API is available (the two section headings + the FrontMatter tool
  tag), which still fails if the tool no-ops / renders the wrong sections / swaps the
  body, rather than asserting a specific storage/provider id that the fixture does not
  guarantee. See GetTranslationProjectInfoTool.java for the exact branches.

Real error paths in GetTranslationProjectInfoTool.execute():
  - missing projectName      -> "projectName is required"                  (JsonUtils.requireArgument)
  - project not found/closed -> "Project not found or closed: <name>"      (ProjectContext.isOpen() false)
  - not an EDT project        -> "Not an EDT project: <name>"               (dtProject == null)
  - LanguageTool absent       -> "LanguageTool IProjectInformationApi is not available. ..."

Fixture inventory used (TestConfiguration, English Names): the project itself
(projectName "TestConfiguration"); no dictionary storage attached.
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
# HAPPY PATH
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_translation_project_info", kind="read")
def test_renders_structured_info_or_clear_unavailable_sentinel():
    """Happy path is ENVIRONMENT-DEPENDENT (like the form-render tools).

    get_translation_project_info wraps LanguageTool's IProjectInformationApi.
    - WITH LanguageTool installed: returns the structured MARKDOWN info document
      (FrontMatter tool/project tags + the two fixed section headings).
    - WITHOUT LanguageTool (this EDT): Activator.getProjectInformationApi() is null
      and the tool returns a CLEAR, ACTIONABLE error: "LanguageTool ... is not
      available. Install LanguageTool in EDT." Both are correct behavior.

    Assert the REAL observed branch; both are mutation-sensitive (a no-op / wrong
    document fails the success branch; a vague/blank error fails the sentinel
    branch). Either way the read tool must not mutate the project.
    """
    r = call("get_translation_project_info", {"projectName": PROJECT})
    if r.is_error:
        # LanguageTool absent: the sentinel must name LanguageTool and be actionable.
        err = r.error_text()
        assert_error_quality(err, names=["LanguageTool"], suggests=["Install"],
                             ctx="LanguageTool-unavailable sentinel is clear and actionable")
    else:
        # LanguageTool installed: assert the structured document shape.
        assert_contains(r.text, "tool: get_translation_project_info",
                        "FrontMatter must tag the producing tool")
        assert_contains(r.text, "project: " + PROJECT,
                        "FrontMatter must echo the requested project name")
        assert_contains(r.text, "## Storages",
                        "body must render the Storages section heading")
        assert_contains(r.text, "## Translation providers",
                        "body must render the Translation providers section heading")
        assert_contains(r.text, "storagesCount: 0",
                        "fixture has no storage attached -> storagesCount must be 0")

    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_translation_project_info", kind="read")
def test_missing_project_name_errors_clearly():
    """Missing required projectName -> JsonUtils.requireArgument guard.

    The ONLY declared parameter is projectName; omitting it must produce the
    required-arg error, not a NullPointer / generic failure.
    """
    r = call("get_translation_project_info", {})
    e = assert_error(r, "missing required projectName")
    # Message names the missing parameter.
    # AUDIT: "projectName is required" names the param but offers no next step
    # (no sibling such as list_projects to discover a valid project) -> suggests=[]
    # intentionally. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_translation_project_info", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped arg, but the project does not exist -> ProjectContext.isOpen()
    is false -> "Project not found or closed: <name>".

    The bad value MUST be echoed; a broken resolver that returned a generic
    "Not an EDT project" (or succeeded against the wrong project) would fail this.
    """
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_translation_project_info", {"projectName": bad})
    e = assert_error(r, "non-existent project")
    # AUDIT: "Project not found or closed: <name>" names the bad value but is NOT
    # actionable — it does not point at list_projects (the sibling tool that
    # enumerates valid open project names). suggests=[] is deliberate; fix-card.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_translation_project_info", kind="read")
def test_empty_project_name_errors_clearly():
    """Boundary: projectName present but the EMPTY string. JsonUtils.requireArgument
    checks `value == null || value.isEmpty()`, so "" is treated as missing and hits
    the same required-arg guard as the omitted-param case — it must NOT silently
    resolve to some default project. Confirms the empty-string boundary is rejected.
    (Verified against JsonUtils.requireArgument: it uses isEmpty(), NOT trim()+isEmpty,
    so a whitespace-only value would instead fall through to the "Project not found
    or closed" branch — covered separately below.)
    """
    r = call("get_translation_project_info", {"projectName": ""})
    e = assert_error(r, "empty-string projectName")
    # AUDIT: an empty value is reported via the same "projectName is required" guard;
    # it does not distinguish "empty" from "omitted" and offers no next step.
    # suggests=[] intentional; fix-card to make the guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="empty-string projectName rejected by the required-arg guard")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_translation_project_info", kind="read")
def test_whitespace_project_name_errors_and_names_value():
    """Boundary: a whitespace-only projectName. Because requireArgument uses
    isEmpty() (no trim), "   " is NOT caught by the required-arg guard; it flows
    to ProjectContext.of("   ") which is not an open project, so the tool returns
    "Project not found or closed:    ". This proves the tool does not strip/coerce
    a blank name into a real project (which would be a silent-success bug).
    """
    bad = "   "
    r = call("get_translation_project_info", {"projectName": bad})
    e = assert_error(r, "whitespace-only projectName")
    # The blank value is echoed verbatim after the prefix. Asserting the stable,
    # delimiter-free prefix is the robust signal (the trailing spaces themselves
    # are awkward to match through JSON escaping).
    # AUDIT: "Project not found or closed: <blank>" is not actionable (no pointer to
    # list_projects) AND a whitespace name surfaces as a confusing "not found"
    # rather than a "blank name" diagnostic -> suggests=[]. Fix-card: trim + reject
    # blank names with a dedicated message.
    assert_error_quality(e, names=["Project not found or closed"], suggests=[],
                         ctx="whitespace projectName surfaces as not-found")
    assert_no_diff("an invalid call must not touch the project on disk")

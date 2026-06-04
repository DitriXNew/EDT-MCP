"""
e2e tests for translate_configuration (kind: action).

translate_configuration runs EDT's "Translation -> Translate configuration"
action. Under the hood it invokes LanguageTool's public CLI
`ISynchronizeProjectApi.synchronizeProject(IDtProject, List<String> languages)`
via reflection, then waits for derived data. It returns MARKDOWN
(getResponseType() == MARKDOWN), so the payload is in r.text (NOT r.structured).

The success body is a FrontMatter document:

    ---
    tool: translate_configuration
    project: <projectName>
    targetLanguages: <comma-joined codes>
    status: success
    ---
    Translate configuration completed.

REQUIRED PARAMETERS (TranslateConfigurationTool.getInputSchema / .execute):
  - projectName     (string)        -> JsonUtils.requireArgument
  - targetLanguages (string array)  -> JsonUtils.extractArrayArgument; rejected
                                       when null/empty with its own guard

execute() guard order (this dictates which error wins, and is why each negative
test below isolates exactly ONE bad field):
  1. requireArgument(projectName)        -> "projectName is required"
  2. targetLanguages null/empty          -> "targetLanguages is required (e.g. [\"en\"])"
  3. ctx.exists() false                  -> "Project not found: <name>"
  4. ctx.isOpen() false                  -> "Project is closed: <name>"
  5. ProjectStateChecker BUILDING        -> (building "...Please wait and retry."
                                            only while derived data is building;
                                            missing/closed fall through to guards 3-4)
  6. dtProject == null                   -> "Not an EDT project: <name>"
  7. getSynchronizeProjectApi() == null  -> "LanguageTool ISynchronizeProjectApi
                                             is not available. Install LanguageTool in EDT."
  8. happy: synchronizeProject(...) -> waitForDerivedData -> FrontMatter status: success

ENVIRONMENT DEPENDENCY (documented, not a bug — mirrors get_translation_project_info):
  This action REQUIRES EDT with LanguageTool installed. In THIS EDT LanguageTool
  is NOT installed, so Activator.getSynchronizeProjectApi() returns null and the
  happy call returns the clear, actionable "not available" sentinel (guard #7).
  The happy-path test is therefore ENV-ROBUST: it supplies a fully valid call
  (real open project + a real target language) and accepts EITHER
    - the real success contract (LanguageTool present), OR
    - the LanguageTool-unavailable sentinel (LanguageTool absent),
  asserting the contract of whichever branch the live server actually took. Both
  branches are mutation-sensitive (a no-op / wrong document fails success; a
  vague/blank error fails the sentinel).

ACTION-TOOL SAFETY (why there is no "real mutating happy path" here):
  Even WITH LanguageTool, translate_configuration regenerates *translated
  artifacts* from dictionary storages bound to the project. The TestConfiguration
  fixture has NO dictionary storage attached (same as get_translation_project_info,
  storagesCount: 0), so there is nothing to translate; a real run does not write a
  deterministic, fixture-committed change we could assert without first corrupting
  the fixture by attaching a storage. Therefore EVERY test here asserts
  assert_no_diff() on TestConfiguration: the action must NOT alter the committed
  project source tree. (If a future fixture attaches a storage and LanguageTool is
  installed, add a dedicated mutating-happy test that diffs the regenerated
  artifact — do not relax these guardrails.)

Fixture inventory used (TestConfiguration, English Names): the project itself
(projectName "TestConfiguration"). Catalog.Catalog is reused only as a known
NON-project name to prove the "Project not found" branch echoes the bad value.
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
# HAPPY PATH (env-robust: real success contract OR LanguageTool sentinel)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="translate_configuration", kind="action")
def test_translate_succeeds_or_reports_languagetool_unavailable():
    """Fully valid call (open EDT project + a real target language code).

    The outcome is ENVIRONMENT-DEPENDENT:
      - WITH LanguageTool installed: synchronizeProject runs and the tool returns
        the FrontMatter success document (tool/project/targetLanguages tags +
        status: success + the "completed" line).
      - WITHOUT LanguageTool (this EDT): getSynchronizeProjectApi() is null and the
        tool returns the CLEAR, ACTIONABLE "LanguageTool ... is not available.
        Install LanguageTool in EDT." sentinel.

    Assert the REAL observed branch — both are mutation-sensitive. Either way the
    action must NOT touch the committed project source on disk.
    """
    r = call("translate_configuration", {
        "projectName": PROJECT,
        "targetLanguages": ["en"],
    })
    if r.is_error:
        # LanguageTool absent: the sentinel must name LanguageTool and tell the
        # user how to fix it (install). A blank/generic error fails this branch.
        err = r.error_text()
        assert_error_quality(err, names=["LanguageTool"], suggests=["Install"],
                             ctx="LanguageTool-unavailable sentinel is clear and actionable")
    else:
        # LanguageTool installed: assert the FrontMatter success contract.
        assert_ok(r, "translate_configuration happy path")
        # FrontMatter must tag the producing tool — a tool that emitted a generic
        # or wrong document (or copied another tool's FrontMatter) would fail here.
        assert_contains(r.text, "tool: translate_configuration",
                        "FrontMatter must tag the producing tool")
        # And echo the requested project + the target language we passed.
        assert_contains(r.text, "project: " + PROJECT,
                        "FrontMatter must echo the requested project name")
        assert_contains(r.text, "targetLanguages: en",
                        "FrontMatter must echo the requested target language code")
        # The success marker the tool writes only on the success path.
        assert_contains(r.text, "status: success",
                        "success path must set status: success")
        assert_contains(r.text, "Translate configuration completed.",
                        "success path must render the completion line")

    assert_no_diff("translate_configuration must not alter the committed project source")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="translate_configuration", kind="action")
def test_missing_project_name_errors_clearly():
    """Missing required projectName (guard #1).

    targetLanguages IS supplied so this isolates the projectName guard: if the
    guard order were broken (e.g. targetLanguages checked first) the message would
    differ. Must produce the required-arg error, not an NPE / generic failure.
    """
    r = call("translate_configuration", {
        "targetLanguages": ["en"],
    })
    e = assert_error(r, "missing projectName")
    # AUDIT: "projectName is required" names the param but offers no next step
    # (no sibling such as list_projects to discover a valid project) -> suggests=[]
    # intentionally. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="translate_configuration", kind="action")
def test_missing_target_languages_errors_clearly():
    """Missing required targetLanguages (guard #2).

    projectName IS supplied and valid, so the ONLY thing wrong is the absent
    language list. extractArrayArgument returns null -> the tool's dedicated guard
    fires BEFORE any project resolution. A tool that silently defaulted the
    languages (e.g. to all configured languages) would skip this error and is a bug.
    """
    r = call("translate_configuration", {
        "projectName": PROJECT,
    })
    e = assert_error(r, "missing targetLanguages")
    # The guard names the param AND shows the expected shape -> it IS actionable.
    # 'e.g.' carries a concrete example of the value to pass (["en"]).
    assert_error_quality(e, names=["targetLanguages"], suggests=["e.g."],
                         ctx="missing targetLanguages names the param and shows an example")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="translate_configuration", kind="action")
def test_empty_target_languages_array_errors_clearly():
    """Boundary: targetLanguages present but an EMPTY array.

    extractArrayArgument on "[]" yields an empty list, and the guard rejects
    null OR isEmpty(). This proves an empty language list is NOT silently treated
    as "translate nothing / success" — it hits the same required-value error as
    the omitted case. (Distinct from the omitted-param test: here the key is
    present, exercising the isEmpty() half of the guard.)
    """
    r = call("translate_configuration", {
        "projectName": PROJECT,
        "targetLanguages": [],
    })
    e = assert_error(r, "empty targetLanguages array")
    assert_error_quality(e, names=["targetLanguages"], suggests=["e.g."],
                         ctx="empty targetLanguages array rejected with the example hint")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="translate_configuration", kind="action")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args, but the project does not exist (guard #3).

    targetLanguages is valid and the name is well-formed but unknown. The
    BUILDING pre-check (guard #5) returns null for a missing project, so control
    falls through to ctx.exists() being false -> "Project not found: <name>".
    The bad value MUST be echoed; a broken resolver that silently translated the
    wrong project, or emitted a generic "Not an EDT project", would fail this.
    """
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("translate_configuration", {
        "projectName": bad,
        "targetLanguages": ["en"],
    })
    e = assert_error(r, "non-existent project")
    # "Project not found: <name>" names the bad value. suggests=[] is deliberate:
    # the list_projects discovery tail is a SEPARATE change, not asserted here.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="translate_configuration", kind="action")
def test_nonproject_name_errors_and_names_value():
    """A real metadata-object name (Catalog.Catalog) is NOT a workspace project.

    Passing a known-good *object* FQN as the projectName must still fail at
    ctx.exists(): "Catalog.Catalog" is not a workspace project handle. This guards
    against the tool confusing an object name for a project and "succeeding"
    against TestConfiguration by mistake.
    """
    bad = "Catalog.Catalog"
    r = call("translate_configuration", {
        "projectName": bad,
        "targetLanguages": ["en"],
    })
    e = assert_error(r, "metadata-object name passed as projectName")
    # AUDIT: same generic "Project not found: <name>" — names the value, not actionable.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="object FQN as projectName is rejected and echoed")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="translate_configuration", kind="action")
def test_empty_project_name_errors_clearly():
    """Boundary: projectName present but the EMPTY string (guard #1, isEmpty half).

    JsonUtils.requireArgument checks `value == null || value.isEmpty()`, so "" is
    treated as missing and hits the same "projectName is required" guard — it must
    NOT silently resolve to some default project. Confirms the empty-string
    boundary is rejected even though targetLanguages is valid.
    """
    r = call("translate_configuration", {
        "projectName": "",
        "targetLanguages": ["en"],
    })
    e = assert_error(r, "empty-string projectName")
    # AUDIT: an empty value surfaces via the same "projectName is required" guard;
    # it does not distinguish "empty" from "omitted" and offers no next step.
    # suggests=[] intentional; fix-card to make the guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="empty-string projectName rejected by the required-arg guard")
    assert_no_diff("a rejected call must not touch the project on disk")

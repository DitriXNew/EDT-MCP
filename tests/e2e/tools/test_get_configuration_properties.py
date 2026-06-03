"""
e2e tests for get_configuration_properties (kind: read).

Read tool: returns the configuration's properties as a human-readable YAML body
(ResponseType.YAML), so the payload is in r.text (NOT r.structured). Errors,
however, still travel as structured JSON via ToolResult.error(...).toJson()
({"success": false, "error": "<msg>"}) — the harness surfaces that through
err = assert_error(r) (error_text() reads structured.error first).

The tool has a SINGLE, OPTIONAL parameter:
  - projectName (optional): if omitted/empty -> first IConfigurationProject;
    if set -> the project whose name equals it.

Parameter-shape consequences for the negative matrix:
  - There is NO required parameter (projectName is optional) -> "missing required
    param" cannot be provoked: calling with {} is a VALID happy path, not an error.
  - There is NO enum, NO XOR, and NO conditional-required parameter -> none of those
    negative branches exist for this tool.
  - The ONLY error reachable from a well-formed client call is a non-existent
    projectName -> execute()'s configProject == null branch ->
    ToolResult.error("No configuration project found with name: <name>").
So the negative matrix here is intentionally a single case; that is the tool's
real surface, not an omission (see §5/anti-cheat-rule-11: there are no XOR/enum/
conditional combinations to leave untested).

Fixture ground truth (TestConfiguration/src/Configuration/Configuration.mdo):
  name=TestConfiguration, synonym{en:Test configuration},
  defaultRunMode=ManagedApplication, usePurposes=[PersonalComputer],
  dataLockControlMode=Managed, objectAutonumerationMode=NotAutoFree,
  modalityUseMode=DontUse, compatibilityMode=8.5.1,
  defaultLanguage=Language.English (languageCode=en, name=English).
These are the discriminating values asserted below: a broken tool that returned a
no-op / wrong project / empty body would FAIL these (mutation thinking).
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_configuration_properties", kind="read")
def test_default_project_returns_fixture_properties():
    # projectName omitted -> tool falls back to the first IConfigurationProject.
    # In this single-project fixture that resolves to TestConfiguration, so the
    # body MUST carry the fixture's real, discriminating property values.
    r = call("get_configuration_properties", {})
    assert_ok(r, "get_configuration_properties default project")
    # name: read from configuration.getName() — proves the Configuration object
    # was actually read (not an echo: we passed no projectName at all).
    assert_contains(r.text, "name: TestConfiguration",
                    "YAML must report the configuration name from the model")
    # compatibilityMode is fixture-specific (8.5.1) — a wrong/empty model read
    # would not produce this exact value.
    assert_contains(r.text, "compatibilityMode:",
                    "YAML must include the compatibilityMode key")
    assert_contains(r.text, "8.5.1",
                    "compatibilityMode must be the fixture's 8.5.1")
    # defaultLanguage is reported by language CODE (the synonym map key), not name
    # — the bilingual contract. Fixture default language code is 'en'.
    assert_contains(r.text, "defaultLanguage: en",
                    "defaultLanguage must be the language CODE 'en', not the name")
    assert_contains(r.text, "defaultLanguageName: English",
                    "defaultLanguageName must be the human-readable name 'English'")
    # projectName is echoed from configProject.getProject().getName() — proves the
    # fallback actually resolved a concrete project.
    assert_contains(r.text, "projectName: TestConfiguration",
                    "resolved project name must be echoed back")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_configuration_properties", kind="read")
def test_explicit_project_returns_synonym_and_runmode():
    # Explicit projectName exercises the name-matching branch (project.getName()
    # .equals(projectName)). Asserts properties the DEFAULT-path test does not, so
    # the two happy tests together cover scalar, list and localized-map emitters.
    r = call("get_configuration_properties", {"projectName": PROJECT})
    assert_ok(r, "get_configuration_properties explicit project")
    assert_contains(r.text, "name: TestConfiguration",
                    "explicit-project lookup must resolve TestConfiguration")
    # synonym is a localized EMap rendered as a nested block keyed by language code.
    # Fixture synonym is {en: Test configuration} -> both the block header and the
    # indented 'en:' entry must appear (proves toLocalizedMap walked the EMap by code).
    assert_contains(r.text, "synonym:",
                    "localized synonym block must be emitted")
    assert_contains(r.text, "Test configuration",
                    "synonym value 'Test configuration' must be rendered")
    # defaultRunMode is a scalar enum -> proves the scalar emitter ran for enums.
    assert_contains(r.text, "defaultRunMode: ManagedApplication",
                    "defaultRunMode must be the fixture's ManagedApplication")
    # usePurposes is a YAML list (appendList) -> proves the list emitter ran.
    assert_contains(r.text, "usePurposes:",
                    "usePurposes list block must be emitted")
    assert_contains(r.text, "PersonalComputer",
                    "usePurposes must contain the fixture's PersonalComputer")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix
#
# Only ONE negative is reachable from a well-formed client call: a non-existent
# projectName. projectName is OPTIONAL (no "missing required param" case) and there
# are NO enum / XOR / conditional-required parameters, so no other negative branch
# exists for this tool (documented in the module docstring above).
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_configuration_properties", kind="read")
def test_nonexistent_project_errors_and_names_value():
    # configProject == null branch ->
    # ToolResult.error("No configuration project found with name: <name>").toJson().
    # Delivered as structured JSON even though the success path is YAML.
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_configuration_properties", {"projectName": bad})
    err = assert_error(r, "non-existent projectName")
    # The error MUST name the bad value (which project was wrong).
    # AUDIT: the message names the bad project but is NOT actionable — it does not
    # point at list_projects (the sibling tool that enumerates valid project names),
    # nor say "omit projectName to use the first configuration project". suggests=[]
    # is deliberate (rule §5.1/§5.2: do not fudge a bad error) -> fix-card: add a
    # next-step hint to GetConfigurationPropertiesTool's "No configuration project
    # found" message.
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")

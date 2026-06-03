"""
e2e tests for generate_translation_strings (kind: action).

WHAT IT DOES
  Scans a V8 configuration project's translatable features and writes the
  generated keys (.lstr/.trans/.dict) into the *storages* declared on the
  project (see GenerateTranslationStringsTool.java). It is the MCP equivalent of
  the EDT menu "Translation -> Generate translation strings". The actual write
  targets a dictionary STORAGE, NOT the TestConfiguration source tree — and only
  ever runs when LanguageTool is installed. Returns MARKDOWN (getResponseType()
  == MARKDOWN), so the payload is in r.text (NOT r.structured).

ENVIRONMENT DEPENDENCY (documented, not a bug — mirrors get_translation_project_info)
  The tool wraps LanguageTool's CLI API
  (com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi) via reflection.
  LanguageTool is installed SEPARATELY (Help -> Install New Software) and is NOT
  bundled with the EDT base distribution. In THIS EDT it is absent, so
  Activator.getGenerateTranslationStringsApi() returns null and execute() returns
  the sentinel:
    "LanguageTool IGenerateTranslationStringsApi is not available. Install
     LanguageTool in EDT."
  The happy-path test is therefore ENV-ROBUST: if the LanguageTool sentinel
  fires, assert it is clear + actionable (names LanguageTool, suggests Install);
  otherwise assert the real success contract (FrontMatter status: success).

WHY THE DESTRUCTIVE HAPPY PATH IS SAFE HERE (no fixture-corruption risk)
  Unlike import_configuration_from_xml / update_database, a successful generate
  writes into the project's declared dictionary STORAGE, never blindly over the
  TestConfiguration source. The TestConfiguration fixture has NO dictionary
  storage configured, and LanguageTool is absent anyway, so the call cannot mutate
  the committed fixture. Every test still asserts assert_no_diff() as the on-disk
  guardrail: a tool that secretly wrote into TestConfiguration/ would be caught.

VALIDATION ORDER in execute() (this is what makes the negatives env-robust):
  1. requireArgument("projectName")        -> "projectName is required"
  2. targetLanguages null/empty            -> 'targetLanguages is required (e.g. ["en"])'
  3. fillUpType=FROM_PROVIDER + no providerId
                                            -> "providerId is required when fillUpType=FROM_PROVIDER.
                                                Use get_translation_project_info to list available providers."
  4. ProjectContext.of(name).!exists()     -> "Project not found: <name>"
  5. !isOpen()                             -> "Project is closed: <name>"
  6. ProjectStateChecker.checkReadyOrError
  7. !hasNature(V8ConfigurationNature)     -> "Not a V8 configuration project: <name>. ..."
  8. dtProject == null                     -> "EDT has not yet resolved an IDtProject for: <name>. ..."
  9. api == null                           -> the LanguageTool-absent sentinel
  10. success                              -> FrontMatter MARKDOWN with status: success

  Steps 1-3 run BEFORE project resolution AND before the LanguageTool check, so
  those negatives produce a deterministic, env-INDEPENDENT error even when
  LanguageTool is absent. Step 4 runs before the LanguageTool check too, so the
  non-existent-project negative is likewise env-robust.

Fixture inventory used (TestConfiguration, English Names): the project itself
(projectName "TestConfiguration", V8ConfigurationNature); no dictionary storage
configured.
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
# HAPPY PATH (env-robust: LanguageTool-absent sentinel OR real success contract)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="generate_translation_strings", kind="action")
def test_generate_returns_success_doc_or_clear_unavailable_sentinel():
    """Happy path is ENVIRONMENT-DEPENDENT (like the form-render / translation tools).

    Call with a valid configuration project + a valid target language list. Two
    correct outcomes:
      - WITHOUT LanguageTool (this EDT): api == null -> the tool returns a CLEAR,
        ACTIONABLE error naming LanguageTool and telling the caller to Install it.
      - WITH LanguageTool installed: the reflection call runs and the tool returns
        the FrontMatter MARKDOWN document echoing the request (tool/project/
        targetLanguages tags) and status: success.

    Assert the REAL observed branch — both are mutation-sensitive:
      * a no-op / vague / blank sentinel fails the error branch
        (assert_error_quality rejects a bare/empty/stacktrace error);
      * a tool that returned the wrong document / dropped the echoed request /
        omitted status:success fails the success branch.
    Either way the action must NOT touch the committed TestConfiguration tree.
    """
    r = call("generate_translation_strings", {
        "projectName": PROJECT,
        "targetLanguages": ["en"],
    })
    if r.is_error:
        # LanguageTool absent: the sentinel must name LanguageTool and be actionable.
        err = r.error_text()
        assert_error_quality(
            err, names=["LanguageTool"], suggests=["Install"],
            ctx="LanguageTool-unavailable sentinel must be clear + actionable")
    else:
        # LanguageTool installed: assert the FrontMatter success contract. The
        # document must echo the producing tool, the requested project, the
        # requested language, and report success — a broken tool that ran but
        # mis-rendered (or silently no-op'd) would not produce all four.
        assert_contains(r.text, "tool: generate_translation_strings",
                        "FrontMatter must tag the producing tool")
        assert_contains(r.text, "project: " + PROJECT,
                        "FrontMatter must echo the requested project name")
        assert_contains(r.text, "targetLanguages: en",
                        "FrontMatter must echo the requested target language")
        assert_contains(r.text, "status: success",
                        "FrontMatter must report a successful generation")

    # Guardrail for BOTH branches: a successful generate writes into the project's
    # dictionary STORAGE, never the TestConfiguration source; and the sentinel
    # branch writes nothing. Either way the committed fixture must stay clean.
    assert_no_diff("generate_translation_strings must not mutate the TestConfiguration source tree")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="generate_translation_strings", kind="action")
def test_missing_project_name_errors_clearly():
    """Missing required projectName -> JsonUtils.requireArgument guard (checked
    FIRST, before targetLanguages / project resolution / LanguageTool). Must be
    the required-arg error, not a NullPointer / generic failure. Env-independent.
    """
    r = call("generate_translation_strings", {
        "targetLanguages": ["en"],
    })
    e = assert_error(r, "missing projectName")
    # Message names the missing parameter.
    # AUDIT: "projectName is required" names the param but offers no next step
    # (no sibling such as list_projects to discover a valid project) -> suggests=[]
    # intentional. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="generate_translation_strings", kind="action")
def test_missing_target_languages_errors_clearly():
    """Missing required targetLanguages -> the second guard
    'targetLanguages is required (e.g. ["en"])'. This runs BEFORE project
    resolution and BEFORE the LanguageTool check, so it is deterministic even
    with LanguageTool absent. projectName is supplied so the failure is isolated
    to the targetLanguages branch (a broken tool that skipped this guard would
    fall through to a different — wrong — error).
    """
    r = call("generate_translation_strings", {
        "projectName": PROJECT,
    })
    e = assert_error(r, "missing targetLanguages")
    # The guard names the param AND shows the expected shape '["en"]', so it is
    # actionable: assert both the param name and the concrete example token.
    assert_error_quality(e, names=["targetLanguages"], suggests=['["en"]'],
                         ctx="missing targetLanguages names the param + shows example")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="generate_translation_strings", kind="action")
def test_empty_target_languages_array_errors_clearly():
    """Boundary: targetLanguages present but an EMPTY list. extractArrayArgument
    returns null for an empty/absent value, so the SAME 'targetLanguages is
    required' guard fires — an empty list must NOT silently be treated as
    "all languages" or a default. Confirms the empty-array boundary is rejected.
    """
    r = call("generate_translation_strings", {
        "projectName": PROJECT,
        "targetLanguages": [],
    })
    e = assert_error(r, "empty targetLanguages array")
    assert_error_quality(e, names=["targetLanguages"], suggests=['["en"]'],
                         ctx="empty targetLanguages array rejected by the required guard")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="generate_translation_strings", kind="action")
def test_from_provider_without_provider_id_errors_clearly():
    """Conditional-required parameter: fillUpType=FROM_PROVIDER but no providerId.
    execute() rejects this BEFORE project resolution and BEFORE the LanguageTool
    check, so it is env-robust even here (LanguageTool absent). projectName +
    targetLanguages are supplied so the failure is isolated to the providerId
    branch. The error is highly actionable: it names the offending combination
    and points at the sibling tool that lists valid providers.
    """
    r = call("generate_translation_strings", {
        "projectName": PROJECT,
        "targetLanguages": ["en"],
        "fillUpType": "FROM_PROVIDER",
    })
    e = assert_error(r, "FROM_PROVIDER without providerId")
    # Names the missing param + the triggering enum value, and suggests the
    # sibling discovery tool -> a genuinely actionable error.
    assert_error_quality(
        e,
        names=["providerId", "FROM_PROVIDER"],
        suggests=["get_translation_project_info"],
        ctx="FROM_PROVIDER without providerId is clear + actionable")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="generate_translation_strings", kind="action")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args (real language list) but the project does not exist.
    With targetLanguages supplied, validation reaches ProjectContext.of(<bad>),
    !exists() -> "Project not found: <name>". This runs BEFORE the LanguageTool
    check, so the bad value is reported regardless of whether LanguageTool is
    installed. A broken resolver that succeeded against the wrong project, or
    emitted a generic message, would fail this.
    """
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("generate_translation_strings", {
        "projectName": bad,
        "targetLanguages": ["en"],
    })
    e = assert_error(r, "non-existent project")
    # AUDIT: "Project not found: <name>" names the bad value but is NOT actionable
    # — it does not point at list_projects (the sibling tool that enumerates valid
    # project names). suggests=[] is deliberate; fix-card to make it actionable.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("a rejected call must not touch the project on disk")

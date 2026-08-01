"""
e2e tests for get_project_errors (kind: read).

The tool reads EDT's Configuration Problems (validation markers) for a project and
returns a Markdown report (response is the Markdown string -> Result.text; only the
error path goes through ToolResult.error(...).toJson() -> Result.structured.error).

Happy paths are made DETERMINISTIC despite the live marker state being out of our
control: a checkId filter that matches no check (and a NONE severity filter) forces
the documented "# No Errors Found" branch, which still echoes the project / severity /
objects filter banner. That branch text is produced ONLY when the tool actually ran
the marker stream and applied the filters, so a broken/no-op tool would fail it.

The `objects` filter accepts NESTED FQNs and normalizes EVERY structural segment (the
leading TYPE token and each nested KIND token) to both languages, so the English and the
Russian spelling of one address must produce the same report. A requested FQN that matches
no object is named back in an `objectsNotFound` warning - without it a typo is served as a
bare "# No Errors Found", which reads exactly like a clean object.

Read tool => every test also asserts assert_no_diff(): reading problems must never
mutate the project on disk.

Real error paths exercised by the negative matrix (read from GetProjectErrorsTool /
ProjectStateChecker):
  - non-existent projectName -> ProjectStateChecker.buildingErrorOrNull guards only the
    transient BUILDING state, so it falls through to "Project not found: <name>" (names the value)
  - out-of-set severity     -> "severity must be one of: ERRORS, BLOCKER, ..."
"""

from harness import (
    call, assert_ok, assert_contains, assert_not_contains, assert_error,
    assert_error_quality, assert_no_diff, e2e_test, PROJECT, _fail,
)

# A checkId that cannot match any real check id or short UID, so EVERY marker is
# filtered out and the tool is forced into the documented "# No Errors Found" branch.
NO_MATCH_CHECK = "zzz_no_such_check_xyz_e2e"

# The Russian structural tokens an FQN may use at any level.
RU_CATALOG = "Справочник"
RU_FORM = "Форма"
RU_ATTRIBUTE = "Реквизит"

# A catalog that exists in the fixture, and a form it owns.
FIXTURE_CATALOG = "Catalog"
FIXTURE_FORM = "ItemForm"

# Names that cannot exist in the fixture, so the objectsNotFound report is deterministic.
NO_SUCH_OBJECT = "NoSuchObject_e2e_xyz"
NO_SUCH_ATTRIBUTE = "NoSuchAttribute_e2e_xyz"


def _outcome(text):
    """Reduces a report to the part that must NOT depend on the filter's LANGUAGE.

    The banner echoes the requested FQNs verbatim, so two language spellings can never
    produce byte-identical output; everything else (which branch was rendered, how many
    problems matched, which rows) must be identical.
    """
    if "# Configuration Problems" in text:
        rows = [ln for ln in text.splitlines() if ln.startswith("|")]
        found = [ln for ln in text.splitlines() if ln.startswith("**Found:**")]
        return ("problems", found, rows)
    return ("empty", [], [])


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_project_errors", kind="read")
def test_no_match_filter_renders_no_errors_banner_for_project():
    """A filter that matches nothing => the 'No Errors Found' report that still names
    the project. Deterministic regardless of the live marker set, and it FAILS if the
    tool no-ops, ignores the project filter, or renders the wrong report."""
    r = call("get_project_errors", {"projectName": PROJECT, "checkId": NO_MATCH_CHECK})
    assert_ok(r, "get_project_errors happy path (no-match checkId filter)")
    # The empty-result branch heading: proves the tool ran the marker stream + filter.
    assert_contains(r.text, "# No Errors Found", "empty-result report heading must be present")
    # The branch echoes the requested project name back in the banner.
    assert_contains(r.text, PROJECT, "report must name the queried project")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_severity_and_object_filter_banner_echoed():
    """A valid severity enum + an objects filter, combined with the no-match checkId,
    deterministically reaches the empty-result branch AND proves the tool echoes BOTH
    the severity and the objects filter into the banner (so the filters were parsed,
    not silently dropped)."""
    r = call("get_project_errors", {
        "projectName": PROJECT,
        "severity": "MINOR",
        "objects": ["Catalog.Catalog"],
        "checkId": NO_MATCH_CHECK,
    })
    assert_ok(r, "get_project_errors with severity + objects + no-match checkId")
    assert_contains(r.text, "# No Errors Found", "empty-result heading must be present")
    # The banner reflects the accepted filters back to the caller.
    assert_contains(r.text, "MINOR", "severity filter must be echoed in the banner")
    assert_contains(r.text, "Catalog.Catalog", "objects filter must be echoed in the banner")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_concise_is_default_and_leaner_than_detailed():
    """responseFormat contract: the DEFAULT (concise) output is never larger than the
    explicit detailed output, and concise never carries the verbose 'Has docs' column.

    Determinism: this runs an unfiltered scan whose marker count we cannot control, so the
    invariants are written to hold for BOTH a populated and an empty marker set:
      - the default call (omitting responseFormat) is byte-identical to an explicit
        concise call (proves concise is the default);
      - detailed is never shorter than concise (the only difference is an extra column);
      - concise never contains the 'Has docs' column header.
    When a table is actually rendered ('# Configuration Problems'), we additionally prove
    the real token saving: detailed has the 'Has docs' column and concise omits it."""
    default = call("get_project_errors", {"projectName": PROJECT})
    concise = call("get_project_errors", {"projectName": PROJECT, "responseFormat": "concise"})
    detailed = call("get_project_errors", {"projectName": PROJECT, "responseFormat": "detailed"})
    assert_ok(default, "default (concise) scan")
    assert_ok(concise, "explicit concise scan")
    assert_ok(detailed, "detailed scan")

    # Omitting responseFormat must behave exactly like concise (concise is the default).
    if default.text != concise.text:
        _fail("default output must equal explicit concise output (concise is the default)")

    # The lean default never carries the secondary 'Has docs' column; detailed reintroduces it.
    assert_not_contains(concise.text, "Has docs", "concise must omit the 'Has docs' column")

    # Detailed is never smaller than concise: the only delta is an extra column.
    if len(detailed.text) < len(concise.text):
        _fail("detailed output must be >= concise output in length")

    # When real problems are present a table is rendered: prove the genuine token saving.
    if "# Configuration Problems" in detailed.text:
        assert_contains(detailed.text, "Has docs", "detailed must include the 'Has docs' column")
        # Same query, leaner output -> concise must be strictly smaller here.
        if len(concise.text) >= len(detailed.text):
            _fail("with problems present, concise must be strictly leaner than detailed")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_nested_fqn_objects_filter_is_language_symmetric():
    """A NESTED objects FQN must behave identically in English and in Russian.

    Regression for the bug where only the leading type token was translated: filtering by
    `Catalog.<c>.Form.<f>` expanded to `справочник.<c>.form.<f>`, which can never match the
    Russian marker location `Справочник.<c>.Форма.<f>` — every finding was dropped and the
    tool answered "# No Errors Found", indistinguishable from a genuinely clean object.

    The banner echoes the requested FQN verbatim, so the two calls can never be byte-equal;
    everything that must NOT depend on the spelling (which branch was rendered, the found
    count, the rows) is compared via _outcome(). Also asserts that NEITHER call claims the
    valid FQN is unknown — a false objectsNotFound would be just as misleading as the false
    clean report. The strength of the row comparison depends on the live marker set; the
    hard, cannot-false-green assertion for the bug lives in the next test and in the
    MetadataTypeUtilsTest unit test for getAllFqnVariants.
    """
    en_fqn = "Catalog.%s.Form.%s" % (FIXTURE_CATALOG, FIXTURE_FORM)
    ru_fqn = "%s.%s.%s.%s" % (RU_CATALOG, FIXTURE_CATALOG, RU_FORM, FIXTURE_FORM)

    en = call("get_project_errors", {"projectName": PROJECT, "objects": [en_fqn]})
    ru = call("get_project_errors", {"projectName": PROJECT, "objects": [ru_fqn]})
    assert_ok(en, "nested English FQN filter")
    assert_ok(ru, "nested Russian FQN filter")

    # Both spellings address the SAME form, so they must report the same thing.
    if _outcome(en.text) != _outcome(ru.text):
        _fail("English and Russian spellings of the same nested FQN gave different reports:\n"
              "EN=%r\nRU=%r" % (en.text, ru.text))

    # Both FQNs are real; neither may be reported as matching no object.
    for label, r in (("English", en), ("Russian", ru)):
        assert_not_contains(
            r.text, "objectsNotFound",
            "a valid %s nested FQN must not be reported as unknown" % label,
        )

    # The empty-result branch echoes the filter banner; when it is the branch taken, each call
    # must echo ITS OWN spelling back, proving the filter was parsed and not silently dropped.
    # (The problems-table branch renders no banner, so the echo is only asserted where it exists.)
    if "# No Errors Found" in en.text:
        assert_contains(en.text, en_fqn, "English FQN must be echoed in the banner")
        assert_contains(ru.text, ru_fqn, "Russian FQN must be echoed in the banner")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_unknown_objects_fqn_is_reported_not_silently_empty():
    """A typo in an `objects` FQN must be NAMED, never answered with a bare
    "# No Errors Found".

    This is the anti-false-green assertion of the pair: the report must carry the
    `objectsNotFound` marker together with the bogus FQN. The old build has no such text at
    all — it filtered every marker away and reported a clean project, so a caller could not
    tell a typo from a genuinely problem-free object. Both the English and the Russian
    spelling of the same non-existent object must be reported the same way.
    """
    bogus_en = "Catalog.%s" % NO_SUCH_OBJECT
    r = call("get_project_errors", {"projectName": PROJECT, "objects": [bogus_en]})
    assert_ok(r, "objects filter naming a non-existent object")
    assert_contains(r.text, "objectsNotFound", "an unmatched FQN must be reported")
    assert_contains(r.text, bogus_en, "the report must name the offending FQN")
    assert_contains(r.text, "get_metadata_objects", "the report must point at the discovery tool")

    # Same object, Russian type token: the honest answer must not depend on the spelling.
    bogus_ru = "%s.%s" % (RU_CATALOG, NO_SUCH_OBJECT)
    ru = call("get_project_errors", {"projectName": PROJECT, "objects": [bogus_ru]})
    assert_ok(ru, "objects filter naming a non-existent object (Russian token)")
    assert_contains(ru.text, "objectsNotFound", "an unmatched Russian FQN must be reported too")
    assert_contains(ru.text, bogus_ru, "the report must name the offending Russian FQN")

    # A NESTED miss under a real object is reported too, in both languages.
    nested_en = "Catalog.%s.Attribute.%s" % (FIXTURE_CATALOG, NO_SUCH_ATTRIBUTE)
    nested_ru = "%s.%s.%s.%s" % (RU_CATALOG, FIXTURE_CATALOG, RU_ATTRIBUTE, NO_SUCH_ATTRIBUTE)
    for label, fqn in (("English", nested_en), ("Russian", nested_ru)):
        nested = call("get_project_errors", {"projectName": PROJECT, "objects": [fqn]})
        assert_ok(nested, "nested objects filter naming a non-existent attribute")
        assert_contains(
            nested.text, "objectsNotFound",
            "a nested %s FQN whose leaf does not exist must be reported" % label,
        )
        assert_contains(nested.text, fqn, "the report must name the offending nested FQN")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_partial_objects_miss_is_reported_alongside_the_valid_fqn():
    """A PARTIAL miss must be surfaced: one real FQN plus one typo lists only the typo.

    Guards the half-fix where the warning is emitted on the empty report only — the miss is
    just as invisible when the other FQNs did find problems.
    """
    good = "Catalog.%s" % FIXTURE_CATALOG
    bogus = "Catalog.%s" % NO_SUCH_OBJECT
    r = call("get_project_errors", {"projectName": PROJECT, "objects": [good, bogus]})
    assert_ok(r, "objects filter mixing a real and a non-existent FQN")

    assert_contains(r.text, "objectsNotFound", "the miss must be reported")
    # The warning line names the bogus FQN only; the real one must not be listed as missing.
    warning = [ln for ln in r.text.splitlines() if "objectsNotFound" in ln]
    if not warning:
        _fail("expected an objectsNotFound warning line")
    if NO_SUCH_OBJECT not in warning[0]:
        _fail("the objectsNotFound line must name the bogus FQN: %r" % warning[0])
    if good in warning[0]:
        _fail("a resolvable FQN must NOT be listed as missing: %r" % warning[0])
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_nested_russian_kind_token_finds_the_same_markers_as_english():
    """The REGRESSION the issue is about, reproducible on this (English-script) fixture.

    The fixture has BSL markers whose presentation ends in the nested `Module` segment, e.g.
    `CommonModule.Вычисление.Module`. Filtering with the Russian nested token `.Модуль` used to
    translate only the FIRST segment, leaving `Модуль` untouched, so nothing matched and the tool
    answered `# No Errors Found` — a false green indistinguishable from a clean object. Both
    spellings must now return the same markers.
    """
    en_fqn = "CommonModule.Вычисление.Module"
    ru_fqn = "CommonModule.Вычисление." \
             "Модуль"

    en = call("get_project_errors", {"projectName": PROJECT, "objects": [en_fqn]})
    ru = call("get_project_errors", {"projectName": PROJECT, "objects": [ru_fqn]})
    assert_ok(en, "English nested module FQN filter")
    assert_ok(ru, "Russian nested module FQN filter")

    # The English spelling matched before the fix and must keep matching.
    assert_contains(en.text, "Configuration Problems",
                    "the English nested FQN must match the module's markers")
    # The Russian spelling is the one that used to come back falsely clean.
    assert_contains(ru.text, "Configuration Problems",
                    "the Russian nested token must match the SAME markers, not answer 'No Errors'")
    assert_not_contains(ru.text, "No Errors Found",
                        "a translated nested token must never produce a false green")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_form_name_typo_is_reported_and_a_real_form_is_not():
    """A typo in the FORM NAME must be reported — the exact shape #312 was reported for.

    The shared node resolver does not navigate the `Form` kind, so judging a form FQN on its
    `Type.Name` head alone would silently accept ANY form name: the safety net would have a hole
    precisely where the reporter was burned. A form FQN is therefore decided by the form reader.
    """
    real = "Catalog.%s.Form.ItemForm" % FIXTURE_CATALOG
    typo = "Catalog.%s.Form.NoSuchForm_e2e_xyz" % FIXTURE_CATALOG

    r = call("get_project_errors", {"projectName": PROJECT, "objects": [typo]})
    assert_ok(r, "objects filter naming a non-existent FORM")
    assert_contains(r.text, "objectsNotFound", "a form-name typo must be reported")
    assert_contains(r.text, "NoSuchForm_e2e_xyz", "the report must name the offending form FQN")

    ok = call("get_project_errors", {"projectName": PROJECT, "objects": [real]})
    assert_ok(ok, "objects filter naming a REAL form")
    assert_not_contains(ok.text, "objectsNotFound",
                        "an existing form must never be reported as missing")
    assert_no_diff("reading project errors must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_project_errors", kind="read")
def test_invalid_severity_enum_is_rejected_with_valid_set():
    """Out-of-set severity must be REJECTED (the tool refuses to silently widen to
    'all'), and the error must list the valid enum values so the caller can fix it."""
    bad = "WARNINGS"  # not in {ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL, NONE}
    r = call("get_project_errors", {"projectName": PROJECT, "severity": bad})
    err = assert_error(r, "invalid severity enum value")
    # Actionable: the message echoes the rejected value AND enumerates the accepted
    # values. The fix is to pick one of the listed values.
    assert_error_quality(
        err,
        names=["WARNINGS"],
        suggests=["severity", "ERRORS", "MINOR"],
        ctx="invalid severity echoes the bad value and lists the valid set",
    )
    assert_no_diff("a rejected read must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_nonexistent_project_is_rejected():
    """A non-existent projectName must error (not silently return all-projects output).
    The BUILDING-only readiness pre-check lets it fall through to the value-naming
    "Project not found: <name>" rejection."""
    bad = "NoSuchProject_e2e_xyz"
    r = call("get_project_errors", {"projectName": bad})
    err = assert_error(r, "non-existent projectName")
    # execute() now guards only the transient BUILDING state (buildingErrorOrNull), so a
    # non-existent project reaches getProjectErrors' "Project not found: <name>" branch,
    # which NAMES the bad value -- no longer the misleading "Project does not exist.
    # Please wait and retry." (which implied a transient build a retry would resolve).
    # The shared ProjectContext.notFoundMessage now appends the discovery tail, so the
    # error both names the value AND points the caller at list_projects.
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_projects"],
        ctx="non-existent project: names the bad value and points at list_projects",
    )
    # Independent, value-specific check that is NOT trivially true: the rejection text
    # must speak about the project not existing (catches a tool that errors for an
    # unrelated reason or returns a generic failure).
    assert_contains(
        err.lower(), "project", "non-existent project error must mention the project"
    )
    assert_no_diff("a rejected read must not touch the project on disk")

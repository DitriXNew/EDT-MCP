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

There are TWO object filters and they behave differently on purpose:
  * `objects`    - a loose case-insensitive SUBSTRING match over the reported location, with
                   every structural segment normalized to both languages. It makes NO
                   existence claim: a fragment and a typo are indistinguishable there, so it
                   never emits objectsNotFound.
  * `objectFqns` - EXACT model addresses, resolved with the same resolvers the write tools
                   use. This is the only input that reports back: the response is JSON with
                   `report` (the Markdown for a human) plus `objectsResolved` /
                   `objectsNotFound` / `objectsUnsupported`. "Exact" includes the KIND token
                   of a form member (a FIELD does not answer to a `Button.` address) and the
                   ё/е retry the write tools use (a name typed with ё resolves against the
                   stored е form).

Anything that must observe a REAL marker seeds it first (a syntax error written into a
fixture module with write_module_source, then a forced revalidation): the live marker set
is outside this file's control, so a test that merely hopes for one is a coin flip.
Such tests are kind="write-metadata", so the runner reverts the module on disk AND resyncs
the in-memory model afterwards.

Read tool => every non-seeding test also asserts assert_no_diff(): reading problems must
never mutate the project on disk. (The seeding tests dirty the module on purpose and are
cleaned up by the runner instead.)

Real error paths exercised by the negative matrix (read from GetProjectErrorsTool /
ProjectStateChecker):
  - non-existent projectName -> ProjectStateChecker.buildingErrorOrNull guards only the
    transient BUILDING state, so it falls through to "Project not found: <name>" (names the value)
  - out-of-set severity     -> "severity must be one of: ERRORS, BLOCKER, ..."
  - objects + objectFqns    -> refused (the two filters have different semantics)
"""

import time

from harness import (
    call, assert_ok, assert_contains, assert_not_contains, assert_error,
    assert_error_quality, assert_no_diff, e2e_test, PROJECT, _fail,
    wait_for_project_ready,
)

# A checkId that cannot match any real check id or short UID, so EVERY marker is
# filtered out and the tool is forced into the documented "# No Errors Found" branch.
NO_MATCH_CHECK = "zzz_no_such_check_xyz_e2e"

# A catalog that exists in the fixture, a form it owns, and the fixture's common form.
FIXTURE_CATALOG = "Catalog"
FIXTURE_FORM = "ItemForm"
FIXTURE_COMMON_FORM = "Form"

# Two items of that form, with the KIND each one really is. The kind token is part of the
# address, so an item addressed with the other one's kind must be a miss.
FIXTURE_FORM_FIELD = "Code"          # xsi:type form:FormField
FIXTURE_FORM_DECORATION = "Decoration1"  # xsi:type form:Decoration

# A catalog created by the ё-addressing test. create_metadata normalizes 'ё'->'е' in the Name
# by default, so the object is STORED under the 'е' spelling while the caller may keep typing 'ё'.
YO_CATALOG_YO = "Полёт"
YO_CATALOG_YE = "Полет"

# Names that cannot exist in the fixture, so the not-found verdicts are deterministic.
NO_SUCH_OBJECT = "NoSuchObject_e2e_xyz"
NO_SUCH_ATTRIBUTE = "NoSuchAttribute_e2e_xyz"

# The fixture common module the marker is seeded into. Its NAME is Cyrillic on purpose: the
# programmatic name must survive the bilingual normalization untouched (only the STRUCTURAL
# segments are translated), which is exactly what the language-symmetry test relies on.
SEEDED_MODULE_NAME = "Вычисление"
SEEDED_MODULE_PATH = "CommonModules/%s/Module.bsl" % SEEDED_MODULE_NAME
# A bare identifier at module level is not a statement in BSL -> a hard syntax error, the
# same shape the fixture's own CommonModules/Error/Module.bsl uses.
SEEDED_BAD_SOURCE = "\nE2eSyntaxProbeXyz\n"

# The address of the seeded module and the nested MODULE segment EDT appends to a BSL
# problem's location, in both spellings.
EN_MODULE_OWNER = "CommonModule.%s" % SEEDED_MODULE_NAME
RU_MODULE_OWNER = "ОбщийМодуль.%s" % SEEDED_MODULE_NAME
EN_MODULE_KIND = "Module"
RU_MODULE_KIND = "Модуль"

# How long to wait for EDT to publish the seeded marker after the write + revalidation.
MARKER_POLL_TIMEOUT = 120


def _report(result):
    """The Markdown report of a call, whichever channel carried it.

    A plain call returns Markdown in the text channel; an `objectFqns` call returns the
    machine payload, which carries the same Markdown in its `report` field.
    """
    structured = result.structured
    if isinstance(structured, dict) and isinstance(structured.get("report"), str):
        return structured["report"]
    return result.text or ""


def _rows(text):
    """The problem-table rows of a report - the part that must NOT depend on the filter's
    LANGUAGE (the banner echoes the requested address verbatim, so two spellings can never
    produce byte-identical output)."""
    return [ln for ln in text.splitlines() if ln.startswith("|")]


def _rows_for(fqn):
    """Run the loose `objects` filter for one address and return its problem rows."""
    r = call("get_project_errors", {"projectName": PROJECT, "objects": [fqn]})
    assert_ok(r, "objects filter %r" % fqn)
    return _rows(_report(r))


def _assert_verdicts(result, resolved, not_found, unsupported):
    """Assert the MACHINE-readable address verdicts of an `objectFqns` call.

    `unsupported` is given as a list of FQNs; the payload carries {fqn, reason} entries, and
    the reason must be non-empty (an unsupported verdict without a reason is unactionable).
    """
    structured = result.structured
    if not isinstance(structured, dict):
        _fail("an objectFqns call must return structuredContent, got %r" % (structured,))
    for key, expected in (("objectsResolved", resolved), ("objectsNotFound", not_found)):
        actual = structured.get(key)
        if actual != expected:
            _fail("%s mismatch: expected %r, got %r (payload=%r)"
                  % (key, expected, actual, structured))
    actual_unsupported = structured.get("objectsUnsupported")
    if not isinstance(actual_unsupported, list):
        _fail("objectsUnsupported must always be emitted as a list, got %r"
              % (actual_unsupported,))
    if [e.get("fqn") for e in actual_unsupported] != unsupported:
        _fail("objectsUnsupported mismatch: expected %r, got %r"
              % (unsupported, actual_unsupported))
    for entry in actual_unsupported:
        if not entry.get("reason"):
            _fail("an unsupported verdict must carry a reason: %r" % (entry,))


def _seed_bsl_error_and_wait():
    """Write a syntax error into the fixture common module and wait for EDT's marker.

    Returns the marker's reported LOCATION (the `Location` cell), so a test can build the
    address spellings from what EDT really renders instead of assuming a script variant.

    Fails (rather than skipping) if no marker appears: without one the assertions that
    follow would be vacuously true, which is the false-green this file exists to avoid.
    """
    w = call("write_module_source", {
        "projectName": PROJECT, "modulePath": SEEDED_MODULE_PATH,
        "mode": "append", "source": SEEDED_BAD_SOURCE,
    })
    assert_ok(w, "seeding a BSL syntax error into %s" % SEEDED_MODULE_PATH)
    wait_for_project_ready()
    # The tool reads EXISTING markers rather than forcing a compile; force one so the poll
    # below converges instead of racing EDT's background validation.
    call("revalidate_objects", {"projectName": PROJECT, "objects": [EN_MODULE_OWNER]})

    deadline = time.time() + MARKER_POLL_TIMEOUT
    last = ""
    while time.time() < deadline:
        r = call("get_project_errors", {"projectName": PROJECT, "objects": [EN_MODULE_OWNER]})
        assert_ok(r, "polling for the seeded marker")
        last = _report(r)
        rows = _rows(last)
        # rows[0] is the header, rows[1] the separator; the first data row is rows[2].
        if "# Configuration Problems" in last and len(rows) > 2:
            cells = [c.strip() for c in rows[2].strip("|").split("|")]
            # Description | Location | Module path | Line | Check code
            if len(cells) > 1 and cells[1]:
                return cells[1]
        time.sleep(2)
    _fail("no marker appeared for %s within %ds; last report:\n%s"
          % (EN_MODULE_OWNER, MARKER_POLL_TIMEOUT, last[:600]))
    return ""  # unreachable; _fail raises


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


@e2e_test(tool="get_project_errors", kind="write-metadata")
def test_seeded_marker_is_found_by_both_language_spellings_of_the_filter():
    """EN and RU spellings of the SAME address must select the SAME rows.

    The live marker set is outside this file's control (see the module docstring), so the
    marker is CREATED here: a syntax error is written into a fixture common module with the
    regular write tool and validation is forced. Only then is the filter exercised.

    Regression for the bug where only the leading type token was translated: the Russian
    nested token was left untouched, matched nothing, and the tool answered "# No Errors
    Found" - indistinguishable from a genuinely clean object.
    """
    location = _seed_bsl_error_and_wait()

    # 1) The OBJECT-level address, in both languages: same rows, and non-empty (the seeded
    #    marker is in there).
    en_rows = _rows_for(EN_MODULE_OWNER)
    ru_rows = _rows_for(RU_MODULE_OWNER)
    if not en_rows:
        _fail("the seeded marker must be selectable by %r, got no rows" % EN_MODULE_OWNER)
    if en_rows != ru_rows:
        _fail("English and Russian spellings of %r selected different rows:\nEN=%r\nRU=%r"
              % (EN_MODULE_OWNER, en_rows, ru_rows))

    # 2) The NESTED address, in both languages. The head is taken from the marker's OWN
    #    reported location, so the pair is built from what EDT really renders rather than
    #    from an assumption about the configuration's script variant.
    head = ".".join(location.split(".")[:2])
    nested_en = "%s.%s" % (head, EN_MODULE_KIND)
    nested_ru = "%s.%s" % (head, RU_MODULE_KIND)
    nested_en_rows = _rows_for(nested_en)
    nested_ru_rows = _rows_for(nested_ru)
    if not nested_en_rows:
        _fail("the nested address %r must select the seeded marker at %r, got no rows"
              % (nested_en, location))
    if nested_en_rows != nested_ru_rows:
        _fail("English and Russian spellings of the nested module segment selected different "
              "rows:\nEN(%s)=%r\nRU(%s)=%r"
              % (nested_en, nested_en_rows, nested_ru, nested_ru_rows))


@e2e_test(tool="get_project_errors", kind="write-metadata")
def test_loose_objects_filter_never_reports_a_fragment_as_missing():
    """`objects` is a SUBSTRING filter, so it must never claim an entry matched no object.

    A fragment and a typo are indistinguishable there, so the report carries no
    objectsNotFound at all - not for the fragment that DID select rows, and not for a bogus
    entry either (that question belongs to `objectFqns`). The seeded marker makes the
    "selected rows" half deterministic.
    """
    _seed_bsl_error_and_wait()

    # A deliberate fragment of the seeded module's address still selects its rows.
    fragment = EN_MODULE_OWNER[:-2]
    r = call("get_project_errors", {"projectName": PROJECT, "objects": [fragment]})
    assert_ok(r, "substring objects filter")
    assert_contains(_report(r), "Configuration Problems",
                    "the fragment %r must still select the module's markers" % fragment)
    assert_not_contains(_report(r), "objectsNotFound",
                        "a fragment that selected rows must NOT be reported as missing")

    # A bogus entry is equally silent: the loose filter makes no existence claim at all.
    bogus = call("get_project_errors",
                 {"projectName": PROJECT, "objects": ["Catalog.%s" % NO_SUCH_OBJECT]})
    assert_ok(bogus, "objects filter naming a non-existent object")
    assert_not_contains(_report(bogus), "objectsNotFound",
                        "the loose filter must make no existence claim, even for a typo")


# ──────────────────────────────────────────────────────────────────────────────
# objectFqns - the EXACT address filter
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_project_errors", kind="read")
def test_exact_address_that_exists_is_not_reported_missing_and_a_typo_is():
    """The core of the exact filter: a real address resolves silently, a typo is NAMED.

    Both verdicts are asserted on the MACHINE-readable payload, not only on the prose, and
    the existing object is queried with a severity that guarantees an empty result set - so
    "resolved" cannot be inferred from rows that merely happen to exist.
    """
    good = "Catalog.%s" % FIXTURE_CATALOG
    r = call("get_project_errors",
             {"projectName": PROJECT, "objectFqns": [good], "severity": "NONE"})
    assert_ok(r, "exact address that exists")
    _assert_verdicts(r, resolved=[good], not_found=[], unsupported=[])
    assert_not_contains(_report(r), "objectsNotFound",
                        "an existing address must never be reported as missing")

    typo = "Catalog.%s" % NO_SUCH_OBJECT
    bad = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [typo]})
    assert_ok(bad, "exact address that does not exist")
    _assert_verdicts(bad, resolved=[], not_found=[typo], unsupported=[])
    assert_contains(_report(bad), "objectsNotFound",
                    "the human report must mirror the machine verdict")
    assert_contains(_report(bad), "get_metadata_objects",
                    "the report must point at the discovery tool")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_exact_address_partial_success_reports_only_the_miss():
    """One good address plus one typo: the good one scopes the scan, only the typo is named."""
    good = "Catalog.%s" % FIXTURE_CATALOG
    typo = "Catalog.%s" % NO_SUCH_OBJECT
    r = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [good, typo]})
    assert_ok(r, "exact addresses mixing a real and a non-existent one")
    _assert_verdicts(r, resolved=[good], not_found=[typo], unsupported=[])
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_exact_form_member_leaf_is_really_checked():
    """A form MEMBER address is decided on its LEAF, not absolved by the form containing it.

    The form itself resolves, so a build that judged a member address by its form would call
    the ghost member found too. Only the real form may resolve.
    """
    form = "Catalog.%s.Form.%s" % (FIXTURE_CATALOG, FIXTURE_FORM)
    r = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(r, "the containing form itself")
    _assert_verdicts(r, resolved=[form], not_found=[], unsupported=[])

    ghost = "%s.Attribute.%s" % (form, NO_SUCH_ATTRIBUTE)
    m = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [ghost]})
    assert_ok(m, "a form member that does not exist")
    _assert_verdicts(m, resolved=[], not_found=[ghost], unsupported=[])

    # A CommonForm member leaf is checked the same way (its members live in the content
    # model too, while the CommonForm top object resolves on its own).
    common_ghost = "CommonForm.%s.Attribute.%s" % (FIXTURE_COMMON_FORM, NO_SUCH_ATTRIBUTE)
    c = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [common_ghost]})
    assert_ok(c, "a common-form member that does not exist")
    _assert_verdicts(c, resolved=[], not_found=[common_ghost], unsupported=[])
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_exact_form_member_kind_must_match_the_element():
    """A form member is addressed by KIND *and* name: the wrong kind is a MISS, not a clean report.

    The member lookup finds a form item by NAME alone, so `...Form.ItemForm.Button.Code` (where
    `Code` is a FIELD) would otherwise be called "resolved" and then filter the markers by a kind
    segment no location ever carries - the caller gets an empty problem report for an address that
    does not exist, which is exactly the false-clean this input exists to prevent.
    """
    form = "Catalog.%s.Form.%s" % (FIXTURE_CATALOG, FIXTURE_FORM)

    # Each item with its OWN kind: these must resolve.
    right = ["%s.Field.%s" % (form, FIXTURE_FORM_FIELD),
             "%s.Decoration.%s" % (form, FIXTURE_FORM_DECORATION)]
    r = call("get_project_errors",
             {"projectName": PROJECT, "objectFqns": right, "severity": "NONE"})
    assert_ok(r, "form members addressed with their own kind")
    _assert_verdicts(r, resolved=right, not_found=[], unsupported=[])

    # The SAME names with the other item's kind, plus a misspelt kind token: all misses.
    wrong = ["%s.Button.%s" % (form, FIXTURE_FORM_FIELD),
             "%s.Field.%s" % (form, FIXTURE_FORM_DECORATION),
             "%s.Fielld.%s" % (form, FIXTURE_FORM_FIELD)]
    w = call("get_project_errors", {"projectName": PROJECT, "objectFqns": wrong})
    assert_ok(w, "form members addressed with a foreign / misspelt kind")
    _assert_verdicts(w, resolved=[], not_found=wrong, unsupported=[])
    assert_contains(_report(w), "objectsNotFound",
                    "the human report must name the wrong-kind addresses")
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="write-metadata")
def test_exact_address_written_with_yo_resolves_against_the_stored_ye_name():
    """An address whose name carries 'ё' resolves against the name stored with 'е'.

    create_metadata normalizes 'ё'->'е' in Names by default, so the object the caller knows as
    "Полёт" is stored as "Полет". The write/delete tools already retry the normalized spelling;
    without the same retry here the exact filter declares an existing object missing. The object is
    CREATED by the test (the baseline fixture has no ё-name), and the runner reverts it afterwards.
    """
    created = call("create_metadata",
                   {"projectName": PROJECT, "fqn": "Catalog." + YO_CATALOG_YO})
    assert_ok(created, "creating a catalog whose Name carries ё")
    if (created.structured or {}).get("name") != YO_CATALOG_YE:
        _fail("the fixture for this test requires the ё->е normalized Name, got %r"
              % (created.structured,))
    wait_for_project_ready()

    # BOTH spellings must resolve: the stored one directly, the ё one through the fallback.
    for requested in ("Catalog." + YO_CATALOG_YE, "Catalog." + YO_CATALOG_YO):
        r = call("get_project_errors",
                 {"projectName": PROJECT, "objectFqns": [requested], "severity": "NONE"})
        assert_ok(r, "exact address %r" % requested)
        # The verdict keeps the caller's own spelling, whichever one resolved.
        _assert_verdicts(r, resolved=[requested], not_found=[], unsupported=[])

    # A ё name that exists in NEITHER spelling is still an ordinary miss: the fallback must not
    # blur the verdict into "everything resolves".
    ghost = "Catalog.Полёт" + NO_SUCH_OBJECT
    g = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [ghost]})
    assert_ok(g, "a ё address that exists in neither spelling")
    _assert_verdicts(g, resolved=[], not_found=[ghost], unsupported=[])


@e2e_test(tool="get_project_errors", kind="read")
def test_exact_address_with_unknown_head_or_kind_is_reported_missing():
    """An unknown TYPE token and a misspelt KIND token are both plain misses.

    `Catalog.<real>.Fom.ItemForm` has a real head, so a build that judged the address by its
    head would call it found while the filter matched nothing - the exact failure mode the
    exact input exists to prevent.
    """
    bad_kind = "Catalog.%s.Fom.%s" % (FIXTURE_CATALOG, FIXTURE_FORM)
    bad_head = "NoSuchType_e2e_xyz.Whatever"
    r = call("get_project_errors",
             {"projectName": PROJECT, "objectFqns": [bad_kind, bad_head]})
    assert_ok(r, "exact addresses with an unknown kind / head")
    _assert_verdicts(r, resolved=[], not_found=[bad_kind, bad_head], unsupported=[])
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="read")
def test_xdto_member_address_is_unsupported_not_missing():
    """An XDTO MEMBER address must be UNSUPPORTED, never "not found".

    EDT reports every problem of an XDTO package on the package itself, so a member address
    cannot match a marker by construction. Calling it "not found" would be a claim about the
    model that this tool never verified. The package level stays supported: it is an ordinary
    resolution, so a non-existent package is an ordinary miss.
    """
    for member in ("XDTOPackage.P.ObjectType.T",
                   "XDTOPackage.P.Property.N",
                   "XDTOPackage.P.ObjectType.T.Property.N"):
        r = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [member]})
        assert_ok(r, "XDTO member address %s" % member)
        _assert_verdicts(r, resolved=[], not_found=[], unsupported=[member])
        assert_contains(_report(r), "objectsUnsupported",
                        "the human report must mirror the unsupported verdict")
        assert_not_contains(_report(r), "objectsNotFound",
                            "an unsupported address must never be called missing")

    # The PACKAGE level is supported: this fixture has no XDTO package, so the honest verdict
    # for a package address is an ordinary miss - NOT "unsupported".
    pkg = "XDTOPackage.NoSuchPackage_e2e_xyz"
    r = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [pkg]})
    assert_ok(r, "XDTO package-level address")
    _assert_verdicts(r, resolved=[], not_found=[pkg], unsupported=[])
    assert_no_diff("reading project errors must not touch the project on disk")


@e2e_test(tool="get_project_errors", kind="write-metadata")
def test_exact_address_scopes_to_problems_inside_the_node():
    """Selection is "inside the resolved node", not equality with the whole location.

    EDT reports a BSL problem on `CommonModule.X` at location `CommonModule.X.Module`, so an
    exact filter demanding string equality would report ZERO problems for a module that
    demonstrably has one. The seeded marker makes this deterministic.
    """
    _seed_bsl_error_and_wait()

    r = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [EN_MODULE_OWNER]})
    assert_ok(r, "exact address of the seeded module")
    _assert_verdicts(r, resolved=[EN_MODULE_OWNER], not_found=[], unsupported=[])
    assert_contains(_report(r), "Configuration Problems",
                    "a problem INSIDE the resolved node must be reported")
    if (r.structured or {}).get("problemsFound", 0) < 1:
        _fail("problemsFound must count the seeded marker: %r" % (r.structured,))


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


@e2e_test(tool="get_project_errors", kind="read")
def test_objects_and_object_fqns_together_are_rejected():
    """The two object filters answer different questions; combining them is refused rather
    than silently reinterpreted into one of the two semantics."""
    r = call("get_project_errors", {
        "projectName": PROJECT,
        "objects": ["Catalog.Cat"],
        "objectFqns": ["Catalog.%s" % FIXTURE_CATALOG],
    })
    err = assert_error(r, "both object filters at once")
    assert_error_quality(
        err,
        names=["objects", "objectFqns"],
        ctx="the refusal names both parameters",
    )
    assert_no_diff("a rejected read must not touch the project on disk")

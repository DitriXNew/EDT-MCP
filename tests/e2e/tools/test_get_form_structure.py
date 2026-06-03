"""
e2e tests for get_form_structure (kind: read).

What the tool does
------------------
Reads the *editable BM model* of a managed form (NOT the rendered WYSIWYG layout
and NOT a PNG) and renders it as MARKDOWN. ResponseType is MARKDOWN, so the
payload is in r.text (r.structured is None). It walks the form's `items` tree
(groups/fields/decorations, recursively), the form `attributes` (name + value
type) and the form `formCommands` (name + title). Item NAME is the stable
programmatic id; the integer `id` and the EClass type name are shown alongside.
Titles are read from the title EMap by language CODE (en/ru), with a fallback to
the first non-empty title when the requested code is absent (see ROBUSTNESS note).

IMPORTANT — this is a MODEL reader, not a renderer
--------------------------------------------------
The CLAUDE.md form-render warning ("a blank result is not necessarily a bug")
applies to get_form_screenshot / get_form_layout_snapshot, which depend on a JVM
render flag + native/Java render mode. get_form_structure does NOT render: it
reads the BM model directly inside a READ transaction. So for our fixture form it
produces a *deterministic, non-blank* structure and we assert that real content.
The only "empty/sentinel" branches it has are the per-section markers
("_(no items)_" / "_(no attributes)_" / "_(no commands)_") and the whole-form
"Form has no editable model ..." error (ordinary/legacy/unbuilt form) — none of
which apply to our managed CommonForm fixture, which has exactly one item.

Fixture truth (TestConfiguration/src/CommonForms/Form/Form.form, committed):
  - one top-level item: <items xsi:type="form:Decoration"> name=OK id=1 title(en)=OK
      -> rendered as "- OK (type: Decoration, id: 1, title: OK)"
      (its extendedTooltip/contextMenu are NOT in the `items` feature, so they do
       NOT appear as child outline items — only OK is under ## Items)
  - NO <attributes>   -> "_(no attributes)_"
  - NO <formCommands> -> "_(no commands)_"
  Configuration default language = English, languageCode "en" (Configuration.mdo),
  so with no `language` arg the title resolves via code "en" -> "OK".
The addressing FQN is "CommonForm.Form" (a CommonForm IS a BasicForm).

Real execute() / readFormStructure error paths exercised below:
  - formPath missing/empty   -> "formPath is required (e.g. ... or 'CommonForm.MyForm')"
  - projectName missing      -> "projectName is required when formPath is specified"
  - project not found        -> "Project not found: <name>"
  - form not resolvable      -> "Form not found: <formPath>. Expected '...'. Names are
                                 the programmatic Name, not the synonym."
                                (covers non-existent form, malformed FQN shapes, and a
                                 wrong/non-existent owner object — resolveMdForm returns
                                 null for all of them)
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

# Addressing FQN for the fixture CommonForm.
FORM_FQN = "CommonForm.Form"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_form_structure", kind="read")
def test_renders_fixture_form_structure_and_does_not_mutate():
    """Address the real fixture form by FQN and assert the rendered model carries
    the fixture-specific item (OK / id 1 / Decoration / title OK) and the empty
    attribute & command section markers. Each assertion is fixture-specific, so a
    broken tool (no-op, wrong feature walked, wrong id, dropped title) would FAIL."""
    r = call("get_form_structure", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
    })
    assert_ok(r, "get_form_structure on CommonForm.Form")

    # MARKDOWN tool -> data is in r.text, never r.structured.
    assert r.structured is None, \
        "get_form_structure is a MARKDOWN tool; structuredContent must be None"

    # Heading echoes the normalized FQN (CommonForm normalizes to itself).
    assert_contains(r.text, "# Form Structure: CommonForm.Form",
                    "heading must carry the normalized form FQN")

    # The one real item: name=OK, EClass type=Decoration, id=1, title(en)=OK.
    # This single line proves the tool (a) walked the `items` feature, (b) read the
    # programmatic name, (c) reported the EClass type, (d) read the integer id, and
    # (e) resolved the title by language code. A broken reader fails at least one.
    assert_contains(r.text, "- OK (type: Decoration, id: 1, title: OK)",
                    "the OK Decoration item must render with its real name/type/id/title")

    # The fixture has no attributes and no commands -> explicit section markers.
    # (Distinguishes "tool ran and found nothing" from "tool failed to read".)
    assert_contains(r.text, "## Attributes",
                    "attributes section heading must be present")
    assert_contains(r.text, "_(no attributes)_",
                    "fixture form has no attributes -> the no-attributes marker")
    assert_contains(r.text, "## Commands",
                    "commands section heading must be present")
    assert_contains(r.text, "_(no commands)_",
                    "fixture form has no commands -> the no-commands marker")

    # Mutation guard: the OK item's nested extendedTooltip/contextMenu are NOT in
    # the `items` feature, so they must NOT leak in as outline items. If the tool
    # walked the wrong feature (e.g. all containments), "OKExtendedTooltip" would
    # appear — its absence proves the correct `items`-only traversal.
    assert_not_contains(r.text, "OKExtendedTooltip",
                        "nested tooltip is not an `items` child -> must not appear")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_russian_type_token_resolves_same_form():
    """Bilingual addressing: the metadata TYPE token may be Russian. 'ОбщаяФорма'
    is the Russian token for CommonForm; 'ОбщаяФорма.Form' must resolve to the SAME
    form and normalize the heading back to the English 'CommonForm.Form'. The object
    name ('Form') is the programmatic Name and stays as-is in BOTH dialects.

    This is the CLAUDE.md bilingual contract: only the TYPE token is dialect-aware;
    a tool that only accepted the English token would error here."""
    # "ОбщаяФорма" == CommonForm (Russian). Kept as a literal so the test reads in
    # the actual dialect a user would type.
    russian_fqn = "ОбщаяФорма.Form"
    r = call("get_form_structure", {
        "projectName": PROJECT,
        "formPath": russian_fqn,
    })
    assert_ok(r, "get_form_structure with Russian type token")
    # normalizeFqn rewrites the Russian token to English in the heading.
    assert_contains(r.text, "# Form Structure: CommonForm.Form",
                    "Russian type token must normalize to the English FQN heading")
    # Same underlying form -> same real item rendered.
    assert_contains(r.text, "- OK (type: Decoration, id: 1, title: OK)",
                    "Russian-token resolution must yield the identical form model")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_explicit_language_en_renders_english_title():
    """Explicit language='en' is the configured code -> the title EMap entry
    (en -> 'OK') is selected. Asserting the title proves the language-code keyed
    read (CLAUDE.md don't #2: keyed by code, never by language name)."""
    r = call("get_form_structure", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "language": "en",
    })
    assert_ok(r, "get_form_structure language=en")
    assert_contains(r.text, "title: OK",
                    "language=en must resolve the en-keyed title 'OK'")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_unknown_language_falls_back_not_errors():
    """ROBUSTNESS (documented, not a bug): an unconfigured language code does NOT
    error. MetadataLanguageUtils.getSynonymForLanguage falls back to the first
    non-empty title when the requested code is absent, so the title still renders.
    The title EMap here has only en->'OK', so even language='zz' yields 'OK'.

    This is the REAL observed contract: assert the graceful fallback (success +
    title still present), not a fabricated error. A tool that blanked the title or
    errored on an unknown code would FAIL this."""
    r = call("get_form_structure", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "language": "zz",
    })
    assert_ok(r, "get_form_structure unknown language=zz falls back, does not error")
    assert_contains(r.text, "- OK (type: Decoration, id: 1, title: OK)",
                    "unknown language must fall back to the only title 'OK', not blank it")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_form_structure", kind="read")
def test_missing_formpath_errors_actionably():
    """Required formPath omitted. execute() checks formPath FIRST and returns
    "formPath is required (e.g. 'Catalog.Products.Forms.ItemForm' or
    'CommonForm.MyForm')" — names the param AND shows the expected shape."""
    r = call("get_form_structure", {"projectName": PROJECT})
    e = assert_error(r, "missing formPath")
    # Actionable: names the param and gives a concrete CommonForm example shape.
    assert_error_quality(e, names=["formPath"], suggests=["CommonForm."],
                         ctx="missing formPath names the param and shows the shape")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_missing_projectname_errors_clearly():
    """formPath is present but projectName is omitted -> the SECOND guard fires:
    "projectName is required when formPath is specified". (formPath must be present,
    else the formPath guard wins first.)"""
    r = call("get_form_structure", {"formPath": FORM_FQN})
    e = assert_error(r, "missing projectName")
    # AUDIT: names the missing param but offers no next step (no list_projects hint
    # to discover a valid project). suggests=[] is intentional -> fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists()
    is false -> "Project not found: <name>"."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_form_structure", {"projectName": bad, "formPath": FORM_FQN})
    e = assert_error(r, "non-existent project")
    # AUDIT: names the bad project but is not actionable — no pointer to list_projects
    # (the sibling tool that enumerates valid project names). suggests=[] -> fix-card.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_nonexistent_common_form_errors_actionably():
    """Correct type token + correct FQN shape, but no such CommonForm exists ->
    resolveMdForm returns null -> "Form not found: <formPath>. Expected '...'.
    Names are the programmatic Name, not the synonym." This error IS actionable:
    it states the expected FQN format and the Name-vs-synonym gotcha."""
    bad = "CommonForm.NoSuchForm_e2e"
    r = call("get_form_structure", {"projectName": PROJECT, "formPath": bad})
    e = assert_error(r, "non-existent common form")
    # Names the bad FQN AND tells the user the expected shape / Name-not-synonym rule.
    assert_error_quality(e, names=[bad], suggests=["CommonForm.FormName"],
                         ctx="non-existent form names value and the expected format")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_nonexistent_owner_object_form_errors():
    """Owner-object branch: 'Catalog.NoSuchCatalog.Forms.X' is a well-formed 4-part
    FQN, but the owner Catalog does not exist -> findObject returns null ->
    "Form not found: <formPath>". Confirms a missing OWNER (not just a missing form)
    is rejected, with the bad value named."""
    bad = "Catalog.NoSuchCatalog_e2e.Forms.ItemForm"
    r = call("get_form_structure", {"projectName": PROJECT, "formPath": bad})
    e = assert_error(r, "non-existent owner object")
    assert_error_quality(e, names=[bad], suggests=["CommonForm.FormName"],
                         ctx="missing owner object names value and the expected format")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_malformed_fqn_wrong_part_count_errors():
    """Boundary: a 3-part FQN ('Catalog.Catalog.ItemForm') matches NEITHER the 2-part
    CommonForm shape NOR the 4-part 'Type.Object.Forms.Form' shape -> resolveMdForm
    returns null -> "Form not found". Uses a REAL existing object (Catalog.Catalog)
    so the rejection is about the malformed SHAPE, not a missing object."""
    bad = "Catalog.Catalog.ItemForm"
    r = call("get_form_structure", {"projectName": PROJECT, "formPath": bad})
    e = assert_error(r, "malformed FQN (3 parts)")
    assert_error_quality(e, names=[bad], suggests=["CommonForm.FormName"],
                         ctx="malformed 3-part FQN names value and the expected format")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_malformed_fqn_missing_forms_token_errors():
    """Boundary: a 4-part FQN whose 3rd token is NOT 'Forms'
    ('Catalog.Catalog.Items.ItemForm') is rejected by resolveMdForm's
    !"forms".equalsIgnoreCase(parts[2]) guard -> "Form not found". Confirms the
    'Forms' segment is actually validated, not ignored."""
    bad = "Catalog.Catalog.Items.ItemForm"
    r = call("get_form_structure", {"projectName": PROJECT, "formPath": bad})
    e = assert_error(r, "4-part FQN without Forms token")
    assert_error_quality(e, names=[bad], suggests=["CommonForm.FormName"],
                         ctx="missing Forms token names value and the expected format")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_two_part_non_commonform_token_errors():
    """Boundary: a 2-part FQN whose type token is NOT CommonForm ('Catalog.Catalog')
    is rejected by resolveMdForm (only CommonForm is a 2-part BasicForm) ->
    "Form not found". Confirms the tool does not mistake an arbitrary 2-part object
    FQN for a form."""
    bad = "Catalog.Catalog"
    r = call("get_form_structure", {"projectName": PROJECT, "formPath": bad})
    e = assert_error(r, "2-part non-CommonForm token")
    assert_error_quality(e, names=[bad], suggests=["CommonForm.FormName"],
                         ctx="2-part non-form FQN names value and the expected format")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_form_structure", kind="read")
def test_form_addressed_by_synonym_not_name_errors():
    """CLAUDE.md bilingual rule: a form is addressed by its programmatic Name, NOT
    its synonym. The fixture form's Name is 'Form' and its en-synonym is also 'Form',
    so to prove the Name-vs-synonym distinction we use the Configuration synonym
    'Test configuration' as a fake form name — it must NOT resolve. resolveMdForm
    matches against getName() only -> "Form not found"."""
    bad = "CommonForm.Test configuration"
    r = call("get_form_structure", {"projectName": PROJECT, "formPath": bad})
    e = assert_error(r, "addressed by synonym, not Name")
    # The error explicitly states names are the programmatic Name, not the synonym.
    assert_error_quality(e, names=[bad], suggests=["not the synonym"],
                         ctx="synonym addressing rejected with the Name-not-synonym hint")
    assert_no_diff("an invalid call must not touch the project on disk")

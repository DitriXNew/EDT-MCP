"""
e2e tests for find_references (kind: read).

What the tool does
------------------
find_references finds every place a top-level *metadata OBJECT* (addressed by its
FQN, e.g. 'Catalog.Catalog', 'CommonModule.Calc') is used: in other metadata
objects (Configuration containment, form attributes/items, produced-type usages,
input-by-string, etc.) AND in BSL code. ResponseType is MARKDOWN, so the payload
is in r.text (r.structured is None for the success path; the structured-error
diversion still populates r.structured on isError).

IMPORTANT — this is an OBJECT-level reference finder, NOT a method/symbol finder.
The required params are projectName + objectFqn (plus an optional `limit`,
default 100, clamped to 500). It does NOT take a line/column position; it does
NOT find references to a single BSL method. So 'find references to the Add()
call' is out of scope for THIS tool (that is go_to_definition / call-hierarchy
territory) — here we exercise the object FQN contract.

Output shape (verified live against the committed TestConfiguration fixture):
  # References to <normalizedFqn>

  **Total references found:** <N>

  - <metadata path> [ - <feature>]      (one per metadata reference, sorted)
  ...
  ### BSL Modules                       (only when BSL refs exist)
  - <module path> [Line X; Line Y]

Fixture truth (committed; values confirmed by calling the live tool):
  - Catalog.Catalog -> 9 references, including the Configuration containment
    'Configuration - Catalogs - catalogs' and form-item data-path usages like
    'Catalog.Catalog.Form.ItemForm.Form - Items.Code.Data path - Type: types'.
  - CommonModule.Calc -> 2 references, including
    'Configuration - Common modules - commonModules'.
  - CommonModule.OK -> 2 references (same Configuration containment shape).
  - Subsystem.Subsystem -> 1 reference: 'Configuration - Subsystems - subsystems'.
  Every object in this tiny fixture is at least referenced by its Configuration
  containment, so there is no genuine 0-reference object to exercise the
  "No references found." branch — that branch is therefore NOT asserted here
  (we do not fabricate a fixture for it). See AUDIT note below.

Bilingual contract (CLAUDE.md): the metadata TYPE token is dialect-aware and
normalized to English before resolution; the object NAME is the programmatic
Name and is unchanged. Verified live:
  - 'Справочник.Catalog' (Russian token) resolves to the SAME object and the
    heading is normalized to the English 'Catalog.Catalog'.
  - 'Catalogs.Catalog' (English PLURAL token) also resolves to 'Catalog.Catalog'.
  - 'ОбщийМодуль.OK' (Russian CommonModule token) resolves to 'CommonModule.OK'.
  - For a NON-existent Russian-token FQN the error message shows the NORMALIZED
    English FQN (the token is normalized before the not-found check).

Real execute()/findReferences error paths exercised below (live-confirmed text):
  - projectName missing        -> "projectName is required"
  - objectFqn missing          -> "objectFqn is required"
  - project not found          -> "Project not found: <name>. Use list_projects
                                  to see available projects." (shared
                                  ProjectContext.notFoundMessage — actionable)
  - object not found (2 parts) -> "Object not found: <fqn>"
  - sub-object FQN (>2 parts)  -> "Object not found: <fqn>. find_references only
                                  supports top-level metadata objects (...). Sub-objects
                                  such as attributes, forms, commands and tabular
                                  sections are not supported (...)."  (the rich,
                                  actionable variant)
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
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="find_references", kind="read")
def test_catalog_references_real_usages_and_does_not_mutate():
    """Find references to the real fixture object Catalog.Catalog and assert the
    fixture-specific reference set. Each assertion is tied to a concrete usage in
    the committed fixture, so a broken tool (no-op, wrong target, dropped category,
    miscounted) would FAIL at least one of them."""
    r = call("find_references", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
    })
    assert_ok(r, "find_references on Catalog.Catalog")

    # MARKDOWN tool -> success payload lives in r.text, never r.structured.
    assert r.structured is None, \
        "find_references is a MARKDOWN tool; structuredContent must be None on success"

    # Heading echoes the resolved FQN.
    assert_contains(r.text, "# References to Catalog.Catalog",
                    "heading must carry the resolved object FQN")

    # Exact count is fixture-deterministic (9 real references). A miscount or a
    # broken collector changes this number, so asserting the literal total is a
    # strong mutation guard (not a vague 'non-empty').
    assert_contains(r.text, "**Total references found:** 9",
                    "Catalog.Catalog has exactly 9 references in the fixture")

    # The Configuration containment reference (every top object has it). Proves the
    # back-reference collector ran and the path/feature formatting is correct.
    assert_contains(r.text, "Configuration - Catalogs - catalogs",
                    "the Configuration containment reference must be listed")

    # A form-item produced-type usage: proves the tool follows produced types and
    # walks into the owned ItemForm, formatting the inner EDT-style path + feature.
    assert_contains(r.text, "Catalog.Catalog.Form.ItemForm.Form - Items.Code.Data path - Type: types",
                    "the ItemForm Code data-path produced-type usage must be listed")

    # An input-by-string usage (a non-form, non-BSL metadata reference category).
    assert_contains(r.text, "Catalog.Catalog - Input by string - Code",
                    "the input-by-string Code reference must be listed")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_common_module_references_configuration_containment():
    """A CommonModule object resolves and its references are found. CommonModule.Calc
    is referenced by the Configuration (it owns the module) -> exactly 2 references.
    This proves object resolution + reference collection for a *different* metadata
    type than Catalog, with its own deterministic count."""
    r = call("find_references", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
    })
    assert_ok(r, "find_references on CommonModule.Calc")
    assert_contains(r.text, "# References to CommonModule.Calc",
                    "heading must carry the CommonModule FQN")
    assert_contains(r.text, "**Total references found:** 2",
                    "CommonModule.Calc has exactly 2 references in the fixture")
    assert_contains(r.text, "Configuration - Common modules - commonModules",
                    "the Configuration commonModules containment reference must be listed")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_subsystem_single_configuration_reference():
    """Subsystem.Subsystem is referenced exactly once (the Configuration owns it).
    Asserting the single, specific reference proves the count and the
    Subsystems-category path formatting are both correct."""
    r = call("find_references", {
        "projectName": PROJECT,
        "objectFqn": "Subsystem.Subsystem",
    })
    assert_ok(r, "find_references on Subsystem.Subsystem")
    assert_contains(r.text, "# References to Subsystem.Subsystem",
                    "heading must carry the Subsystem FQN")
    assert_contains(r.text, "**Total references found:** 1",
                    "Subsystem.Subsystem has exactly 1 reference in the fixture")
    assert_contains(r.text, "Configuration - Subsystems - subsystems",
                    "the single Configuration subsystems reference must be listed")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_russian_type_token_normalizes_and_yields_same_object():
    """Bilingual addressing (CLAUDE.md): the metadata TYPE token may be Russian.
    'Справочник' is the Russian token for Catalog; 'Справочник.Catalog' must
    resolve to the SAME object and the heading must normalize back to the English
    'Catalog.Catalog' (the object NAME 'Catalog' is the programmatic Name and is
    unchanged). A tool that only accepted the English token would error here, and
    a tool that failed to normalize the heading would print the Russian token."""
    russian_fqn = "Справочник.Catalog"
    r = call("find_references", {
        "projectName": PROJECT,
        "objectFqn": russian_fqn,
    })
    assert_ok(r, "find_references with Russian type token")
    # normalizeFqn rewrites the Russian token to English in the heading.
    assert_contains(r.text, "# References to Catalog.Catalog",
                    "Russian type token must normalize to the English FQN heading")
    # The Russian token must NOT leak into the rendered output.
    assert_not_contains(r.text, "Справочник",
                        "the Russian type token must be normalized away, not echoed")
    # Same underlying object -> same deterministic reference set.
    assert_contains(r.text, "**Total references found:** 9",
                    "Russian-token resolution must yield the identical 9-reference set")
    assert_contains(r.text, "Configuration - Catalogs - catalogs",
                    "Russian-token resolution must yield the identical references")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_plural_english_type_token_resolves_same_object():
    """The English type token is also accepted in its PLURAL form: 'Catalogs.Catalog'
    must resolve to the same 'Catalog.Catalog' object (MetadataTypeUtils handles
    singular/plural). Heading normalizes to the singular English FQN. This guards
    the singular/plural branch of the resolver, distinct from the ru/en branch."""
    r = call("find_references", {
        "projectName": PROJECT,
        "objectFqn": "Catalogs.Catalog",
    })
    assert_ok(r, "find_references with English plural type token")
    assert_contains(r.text, "# References to Catalog.Catalog",
                    "plural type token must normalize to the singular FQN heading")
    assert_contains(r.text, "**Total references found:** 9",
                    "plural-token resolution must yield the identical 9-reference set")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="find_references", kind="read")
def test_missing_projectname_errors_and_names_param():
    """Required projectName omitted -> the shared requireArgument guard fires FIRST
    (projectName is checked before objectFqn) -> "projectName is required"."""
    r = call("find_references", {"objectFqn": "Catalog.Catalog"})
    e = assert_error(r, "missing projectName")
    # The shared required-arg guard maps projectName -> list_projects, so the error names the
    # missing param AND points at the tool that enumerates valid project names.
    assert_error_quality(e, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName names the param + steers to list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_missing_objectfqn_errors_and_names_param():
    """projectName present but the required objectFqn omitted -> the SECOND guard
    fires -> "objectFqn is required" (projectName must be present, else its guard
    wins first)."""
    r = call("find_references", {"projectName": PROJECT})
    e = assert_error(r, "missing objectFqn")
    # The shared required-arg guard maps objectFqn -> get_metadata_objects, so the error names
    # the missing param AND points at the tool that enumerates object FQNs.
    assert_error_quality(e, names=["objectFqn"], suggests=["get_metadata_objects"],
                         ctx="missing objectFqn names the param + steers to get_metadata_objects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists()
    is false -> the shared ProjectContext.notFoundMessage:
    "Project not found: <name>. Use list_projects to see available projects."
    The error both names the bad value AND points at list_projects (the sibling
    tool that enumerates valid project names), so it is actionable."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("find_references", {"projectName": bad, "objectFqn": "Catalog.Catalog"})
    e = assert_error(r, "non-existent project")
    # Names the bad project AND is actionable: the migrated message carries the
    # list_projects discovery tail.
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_nonexistent_object_errors_and_names_value():
    """Correct type token + correct 2-part FQN shape, but no such Catalog exists ->
    MetadataTypeUtils.findObject returns null and the FQN has only 2 parts ->
    "Object not found: <fqn>"."""
    bad = "Catalog.NoSuchCatalog_e2e"
    r = call("find_references", {"projectName": PROJECT, "objectFqn": bad})
    e = assert_error(r, "non-existent object")
    # AUDIT: names the bad FQN but offers no next step (no get_metadata_objects /
    # list hint to discover valid object names). suggests=[] -> fix-card.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent object names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_bad_type_token_errors_and_names_value():
    """An unrecognized type token ('NotAType') is not a known metadata type, so
    findObject returns null and (2 parts) -> "Object not found: NotAType.Whatever".
    Confirms an invalid TYPE prefix is rejected with the bad value named (not a
    silent empty result)."""
    bad = "NotAType.Whatever"
    r = call("find_references", {"projectName": PROJECT, "objectFqn": bad})
    e = assert_error(r, "unrecognized type token")
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="bad type token names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_no_dot_fqn_errors_and_names_value():
    """A degenerate FQN with no dot ('JustAName') splits to <2 parts -> findObject
    returns null -> "Object not found: JustAName". Confirms the malformed (no type
    prefix) shape is rejected with the value named."""
    bad = "JustAName"
    r = call("find_references", {"projectName": PROJECT, "objectFqn": bad})
    e = assert_error(r, "no-dot FQN")
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="no-dot FQN names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_subobject_fqn_4parts_errors_actionably():
    """A 4-part sub-object FQN ('Catalog.Catalog.Attribute.Attribute') uses a REAL
    existing parent object but addresses a sub-object (an attribute). The tool only
    supports top-level objects -> the RICH, actionable error variant fires (more
    than 2 dot parts): it names the bad FQN AND explains that only top-level objects
    are supported, with concrete examples. This is a GOOD error -> assert its quality
    in full (not weakened)."""
    bad = "Catalog.Catalog.Attribute.Attribute"
    r = call("find_references", {"projectName": PROJECT, "objectFqn": bad})
    e = assert_error(r, "sub-object FQN (4 parts)")
    # Names the bad value AND is actionable: states the supported scope ("top-level
    # metadata objects") and that sub-objects/attributes are unsupported.
    assert_error_quality(e, names=[bad],
                         suggests=["top-level metadata objects", "attributes"],
                         ctx="sub-object FQN names value and explains the supported scope")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_subobject_fqn_3parts_errors_actionably():
    """Boundary: a 3-part FQN ('Catalog.Catalog.Attribute') is the smallest input
    that trips the >2-dot-parts branch -> the same RICH sub-object error. Uses a
    real existing parent so the rejection is about the sub-object SHAPE, not a
    missing parent. Guards the exact '> 2' boundary of the dot-part check."""
    bad = "Catalog.Catalog.Attribute"
    r = call("find_references", {"projectName": PROJECT, "objectFqn": bad})
    e = assert_error(r, "sub-object FQN (3 parts, boundary)")
    assert_error_quality(e, names=[bad],
                         suggests=["top-level metadata objects", "attributes"],
                         ctx="3-part sub-object FQN trips the rich error at the boundary")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="find_references", kind="read")
def test_russian_token_nonexistent_object_normalizes_value_in_error():
    """Bilingual + negative: a Russian-token FQN for a NON-existent object
    ('Справочник.NoSuchCat_e2e') is normalized BEFORE the not-found check, so the
    error names the NORMALIZED English FQN 'Catalog.NoSuchCat_e2e' (NOT the raw
    Russian input). This documents the real observed contract: normalization
    happens first, then resolution fails. Asserting the normalized value also
    proves the error is not echoing the raw input blindly."""
    normalized = "Catalog.NoSuchCat_e2e"
    r = call("find_references", {"projectName": PROJECT, "objectFqn": "Справочник.NoSuchCat_e2e"})
    e = assert_error(r, "non-existent object via Russian token")
    # The error must name the normalized English FQN; the Russian token must NOT
    # appear (it was normalized away before the not-found message was built).
    assert_error_quality(e, names=[normalized], suggests=[],
                         ctx="Russian-token not-found error names the normalized FQN")
    assert_not_contains(e, "Справочник",
                        "the not-found error must show the normalized token, not the Russian one")
    assert_no_diff("an invalid call must not touch the project on disk")

"""
Cross-tool EXTENSION-project smoke coverage.

The per-tool e2e files all target the BASE configuration `TestConfiguration`. A 1C
configuration EXTENSION (`TestConfiguration.tests`, V8ExtensionNature, namePrefix
`tests_`, which ADOPTS the base config) exercises a DIFFERENT EDT project type, and
a tool that silently assumes "the project is a base Configuration" breaks on it — as
`get_configuration_properties` did (it gated on `instanceof IConfigurationProject`,
which excludes an extension; fixed in commit 9a97d27). This file is the regression
guard for that whole bug class: it runs the common READ/resolution tools against the
extension project and asserts a real, discriminating result — so a future tool that
stops resolving an extension project fails here.

It is a deliberately cross-tool file (the one-file-per-tool rule's documented
exception, like test_live_roundtrip.py): the value is "do these tools work on an
EXTENSION at all", not per-tool depth (that lives in the per-tool files against the
base config). All tests are READ-only; the extension fixture lives under tests/tests
(outside the base PROJECT_REL), so they never mutate the git-tracked base project and
each ends with assert_no_diff().

Extension fixture ground truth (tests/tests, English module names):
  CommonModules tests_SampleTests / tests_MathHelper / tests_FailureDemo;
  tests_MathHelper exports Function Subtract(Minuend, Subtrahend); it is called from
  tests_SampleTests.MathHelperSubtracts (an intra-extension cross-module reference).
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT, TESTS_PROJECT,
)


@e2e_test(tool="get_metadata_objects", kind="read")
def test_extension_own_common_modules_are_listed():
    """get_metadata_objects on the EXTENSION lists the extension's OWN common modules.

    Resolving an extension project's metadata is the exact step get_configuration_
    properties used to fail. A tool that rejected the extension project (or returned
    the base config's objects instead) would not show the tests_ modules here.
    """
    r = call("get_metadata_objects",
             {"projectName": TESTS_PROJECT, "metadataType": "commonModules"})
    assert_ok(r, "get_metadata_objects on the extension")
    assert_contains(r.text, "tests_SampleTests",
                    "the extension's own common module must be listed")
    assert_contains(r.text, "tests_MathHelper",
                    "the extension's helper common module must be listed")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_extension_module_structure_resolves():
    """get_module_structure resolves a method inside an EXTENSION module.

    Proves BSL-module resolution works for an extension project (path under the
    extension's src/), not just the base configuration.
    """
    r = call("get_module_structure",
             {"projectName": TESTS_PROJECT,
              "modulePath": "CommonModules/tests_MathHelper/Module.bsl"})
    assert_ok(r, "get_module_structure on an extension module")
    assert_contains(r.text, "Subtract",
                    "the extension module's exported function must be in the structure")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="find_references", kind="read")
def test_extension_intra_reference_is_found():
    """find_references on an extension's OWN object finds its intra-extension caller.

    tests_MathHelper is called from tests_SampleTests.MathHelperSubtracts — a
    cross-module reference WITHIN the extension. find_references must resolve the
    extension FQN and scan the extension's BSL for the call site. (A base-config
    object the extension only CALLS at run time but does not adopt — e.g.
    CommonModule.Calc — is correctly NOT in the extension's metadata; cross-config
    resolution of adopted/core objects is a separate, future capability.)
    """
    r = call("find_references",
             {"projectName": TESTS_PROJECT, "objectFqn": "CommonModule.tests_MathHelper"})
    assert_ok(r, "find_references on an extension-own object")
    assert_contains(r.text, "tests_SampleTests",
                    "the intra-extension caller module must be found")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_extension_module_source_reads():
    """read_module_source returns the source of an EXTENSION module (its frontmatter
    echoes the extension project name) — proving file reads work for an extension."""
    r = call("read_module_source",
             {"projectName": TESTS_PROJECT,
              "modulePath": "CommonModules/tests_MathHelper/Module.bsl"})
    assert_ok(r, "read_module_source on an extension module")
    assert_contains(r.text, "projectName: " + TESTS_PROJECT,
                    "frontmatter must echo the extension project name")
    assert_contains(r.text, "Функция Subtract",
                    "the extension module's source must be returned")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_nonexistent_extension_object_errors_clearly():
    """A metadata read for an FQN absent from the extension errors clearly, naming the
    value — NOT a silent empty success or a raw exception. (Mirrors the bug class: a
    tool must reject a bad extension input cleanly, not crash on the project type.)"""
    r = call("find_references",
             {"projectName": TESTS_PROJECT, "objectFqn": "CommonModule.tests_NoSuchModule"})
    err = assert_error(r, "non-existent extension object")
    assert_error_quality(err, names=["tests_NoSuchModule"], suggests=[],
                         ctx="missing extension object names the bad value")
    assert_no_diff("a rejected read must not touch the base project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Object ORIGIN (core vs extension-adopted vs extension-own)
#
# Listing an EXTENSION mixes objects ADOPTED from the base configuration (to
# override/intercept them) with the extension's OWN new objects. The Origin column
# (get_metadata_objects) / Origin field (get_metadata_details) is the ONLY signal
# that tells them apart; it is driven by MdObject.getObjectBelonging() read in the
# context of the project type (ExtensionOriginUtils). A base configuration keeps the
# original columns (no Origin) — its objects are all native, so the column would be
# pure noise; that base-side invariant is guarded in the per-tool files.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_objects", kind="read")
def test_extension_listing_marks_object_origin():
    """The extension listing gains an Origin column: an adopted base object reads
    `core (adopted)`, the extension's own object reads `extension`. A regression
    that stopped reading objectBelonging (or dropped the column) fails here."""
    r = call("get_metadata_objects", {"projectName": TESTS_PROJECT})
    assert_ok(r, "get_metadata_objects on the extension")
    assert_contains(r.text, "| Origin |", "extension listing must carry an Origin column")
    # Calc and Catalog are ADOPTED from the base config -> core (adopted).
    assert_contains(r.text, "core (adopted)",
                    "an adopted base object must be marked 'core (adopted)'")
    # tests_* are the extension's own -> extension. (The label only appears as an
    # Origin cell here, so its presence proves the own-object branch ran.)
    assert_contains(r.text, "| extension |",
                    "the extension's own object must be marked 'extension'")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="get_metadata_details", kind="read")
def test_extension_details_report_origin():
    """get_metadata_details footers each object with its Origin: an adopted base
    object is `core (adopted)`, the extension's own is `extension`."""
    r = call("get_metadata_details",
             {"projectName": TESTS_PROJECT,
              "objectFqns": ["CommonModule.Calc", "CommonModule.tests_MathHelper"]})
    assert_ok(r, "get_metadata_details on extension objects")
    assert_contains(r.text, "**Origin:** core (adopted)",
                    "the adopted CommonModule.Calc must be tagged core (adopted)")
    assert_contains(r.text, "**Origin:** extension",
                    "the extension's own tests_MathHelper must be tagged extension")
    assert_no_diff("a read tool must not touch the base project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Method INTERCEPTION footer (both directions)
#
# The extension adopts CommonModule.Calc and annotates `tests_Test` with
# `&ChangeAndValidate("Test")`, intercepting the core method `Test`. The code tools
# surface this link from BOTH sides via the platform IModuleExtensionService:
#   - reading the CORE method/module names the intercepting extension;
#   - reading the EXTENSION method/module names the core method it intercepts.
# A regression that lost the service wiring drops the footer and fails these.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="read_method_source", kind="read")
def test_core_method_footer_names_intercepting_extension():
    """Reading the CORE method appends a footer naming the extension that intercepts
    it, the intercepting method, and the annotation kind."""
    r = call("read_method_source",
             {"projectName": PROJECT,
              "modulePath": "CommonModules/Calc/Module.bsl", "methodName": "Test"})
    assert_ok(r, "read_method_source on the intercepted core method")
    assert_contains(r.text, "Intercepted by extension",
                    "the core method footer must announce extension interception")
    assert_contains(r.text, TESTS_PROJECT,
                    "the footer must name the intercepting extension project")
    assert_contains(r.text, "tests_Test",
                    "the footer must name the intercepting extension method")
    assert_contains(r.text, "&ChangeAndValidate",
                    "the footer must name the interception annotation")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="read_method_source", kind="read")
def test_extension_method_footer_names_core_target():
    """Reading the EXTENSION method appends a footer naming the core method it
    intercepts and the annotation kind."""
    r = call("read_method_source",
             {"projectName": TESTS_PROJECT,
              "modulePath": "CommonModules/Calc/Module.bsl", "methodName": "tests_Test"})
    assert_ok(r, "read_method_source on the intercepting extension method")
    assert_contains(r.text, "intercepts core method",
                    "the extension method footer must announce what it intercepts")
    assert_contains(r.text, "&ChangeAndValidate",
                    "the footer must name the interception annotation")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="get_module_structure", kind="read")
def test_core_module_structure_lists_interception():
    """get_module_structure on the CORE module appends an 'Extension interception'
    section linking its methods to the extension interceptors."""
    r = call("get_module_structure",
             {"projectName": PROJECT, "modulePath": "CommonModules/Calc/Module.bsl"})
    assert_ok(r, "get_module_structure on the intercepted core module")
    assert_contains(r.text, "Extension interception",
                    "the core module structure must carry an interception section")
    assert_contains(r.text, "tests_Test",
                    "the interception section must name the extension interceptor")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="read_module_source", kind="read")
def test_extension_module_source_shows_interception():
    """read_module_source on the EXTENSION module appends an 'Extension interception'
    section. (The footer touches the BSL model, which read_module_source resolves on
    the UI thread like the sibling code tools; this guards that path.)"""
    r = call("read_module_source",
             {"projectName": TESTS_PROJECT, "modulePath": "CommonModules/Calc/Module.bsl"})
    assert_ok(r, "read_module_source on the intercepting extension module")
    assert_contains(r.text, "Extension interception",
                    "the extension module source must carry an interception section")
    assert_contains(r.text, "intercepts core method",
                    "the section must state what the extension method intercepts")
    assert_no_diff("a read tool must not touch the base project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Adopted/overridden FORM (and attribute) marking in get_metadata_details
#
# An extension's adopted object lists, alongside the base members it adopts, which
# of them it OVERRIDES. get_metadata_details adds an Origin column to the Forms /
# Attributes tables when any member is ADOPTED, marking an overridden member
# `core (adopted)` vs the extension's own `extension`. The `| Origin |` column
# header distinguishes this from the object-level `**Origin:**` line.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="read")
def test_extension_adopted_form_marked_overridden():
    """get_metadata_details(full) on the extension's adopted Catalog marks its overridden
    ItemForm with an Origin column reading `core (adopted)` — surfacing that the FORM is
    overridden by the extension (the owner's 'эта форма перехвачена' ask)."""
    r = call("get_metadata_details",
             {"projectName": TESTS_PROJECT, "objectFqns": ["Catalog.Catalog"], "full": True})
    assert_ok(r, "get_metadata_details on the adopted Catalog (full)")
    assert_contains(r.text, "ItemForm", "the adopted catalog's overridden form must be listed")
    # The TABLE Origin column (distinct from the object-level **Origin:** line) proves the
    # per-member marking ran; a base config (no adopted members) would not emit it.
    assert_contains(r.text, "| Origin |", "the Forms table must gain an Origin column for adopted members")
    assert_contains(r.text, "core (adopted)", "the overridden form must be marked core (adopted)")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# rename_metadata_object refuses to rename an ADOPTED object
#
# An adopted object mirrors a base object and MUST keep the base name; renaming only
# the extension copy desyncs the names and breaks the adoption (EDT then reports the
# adopted object's source not found, and interception/override resolution stops).
# The tool must refuse with an actionable error, not silently produce that broken
# state. Preview (confirm omitted) already errors, so nothing is mutated even on a
# guard regression.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write")
def test_rename_adopted_object_is_refused():
    """Renaming an ADOPTED object is refused with an actionable error that names the
    object and explains the base-name rule. confirm is omitted (preview), so a guard
    regression degrades to a harmless preview rather than corrupting the adoption."""
    r = call("rename_metadata_object",
             {"projectName": TESTS_PROJECT, "objectFqn": "CommonModule.Calc", "newName": "Compute"})
    err = assert_error(r, "rename of an adopted object")
    assert_error_quality(err, names=["Calc"], suggests=[],
                         ctx="adopted-object rename names the object")
    low = err.lower()
    if "adopt" not in low:
        raise AssertionError("the refusal must explain the object is adopted; got: " + err)
    if "base" not in low:
        raise AssertionError("the refusal must point at renaming the base object; got: " + err)
    assert_no_diff("a refused rename must not touch the project on disk")

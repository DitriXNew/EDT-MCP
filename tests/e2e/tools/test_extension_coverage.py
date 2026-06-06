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
    assert_contains, assert_no_diff, e2e_test, TESTS_PROJECT,
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

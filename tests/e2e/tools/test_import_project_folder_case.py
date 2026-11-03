"""Issue #678: case-variant import names are refused before either tool can mutate a project."""

import os

from harness import (
    PROJECT,
    PROJECT_DIR,
    REPO_ROOT,
    _workspace_dir,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_not_contains,
    call,
    e2e_test,
)


def _assert_case_refusal(result, requested):
    error = assert_error(result, "an existing project with a case-variant name")
    assert_contains(error, "already exists")
    assert_error_quality(error, names=[PROJECT, requested], suggests=["new project name"])
    # The quality helper folds case; these exact spellings independently prove both names appear.
    assert_contains(error, "`" + PROJECT + "`")
    assert_contains(error, "`" + requested + "`")
    assert_contains(error, "letter case")
    # The fixture project is imported from the repository, not created in the workspace folder, so
    # the refusal must not claim that both names share <workspace root>/<name>: they share no folder.
    # Eclipse refuses the case variant by its resource-tree rule, and the message says so.
    assert_contains(error, "one resource")
    assert_contains(error.lower(), PROJECT_DIR.lower(), "the refusal names where the project really is")
    workspace = _workspace_dir()
    assert workspace, "EDT workspace not found; set EDT_MCP_EDT_WORKSPACE to the -data directory"
    for name in (PROJECT, requested):
        assert_not_contains(error.lower(), os.path.join(workspace, name).lower(),
                            "no shared default folder is claimed for a project outside it")


@e2e_test(tool="import_configuration_from_xml", kind="action")
def test_a_name_differing_only_by_case_from_an_existing_project_is_refused_on_a_case_insensitive_fs():
    requested = PROJECT.lower() if PROJECT.lower() != PROJECT else PROJECT.upper()
    result = call("import_configuration_from_xml", {
        "importPath": REPO_ROOT,
        "projectName": requested,
        # REPO_ROOT has no Configuration.xml. This assertion forces a harmless dump-kind refusal
        # on a case-sensitive host, even when the EDT CLI API is installed.
        "projectNature": "com._1c.g5.v8.dt.core.V8ConfigurationNature",
    })
    if os.path.isdir(PROJECT_DIR.swapcase()):
        _assert_case_refusal(result, requested)
    else:
        error = assert_error(result, "the separate name reaches dump validation")
        assert_not_contains(error, "already exists")
        assert_contains(error, "does not match")
    assert_no_diff("a refused XML import changes no fixture")


@e2e_test(tool="import_project_from_file", kind="action")
def test_a_taken_name_differing_only_by_case_is_refused_at_once():
    requested = PROJECT.lower() if PROJECT.lower() != PROJECT else PROJECT.upper()
    result = call("import_project_from_file", {
        "filePath": os.path.join(REPO_ROOT, "tests", "ImportFiles", "TestConfiguration.cf"),
        "projectName": requested,
        # Keep the case-sensitive positive control non-mutating on stands with a platform too.
        "platformVersion": "0.0.1",
    })
    if os.path.isdir(PROJECT_DIR.swapcase()):
        _assert_case_refusal(result, requested)
    else:
        error = assert_error(result, "the separate name reaches platform validation")
        assert_not_contains(error, "already exists")
    assert_not_contains(result.text, "jobId", "a refusal starts no job")
    assert_no_diff("a refused file import changes no fixture")

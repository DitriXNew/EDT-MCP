"""SessionParameter creation carries the EDT wizard's String(10) default (#795).

Both type-token languages must persist the type, expose it through the model, and
finish without the type-not-specified error. The runner restores each write.
"""

import xml.etree.ElementTree as ET

from harness import (
    E2ECallTimeout,
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
    assert_contains,
    assert_marker_contract,
    assert_not_contains,
    assert_ok,
    call,
    e2e_test,
    poll_disk_contains_all,
    restore_extension_fixture,
    split_markdown_row,
    wait_for_project_ready,
)


def _assert_session_parameter_default(requested_fqn, name, project=PROJECT, fixture_rel=None):
    assert wait_for_project_ready(), "EDT must finish indexing before the create"
    canonical_fqn = "SessionParameter." + name
    created = call("create_metadata", {"projectName": project, "fqn": requested_fqn})
    assert_ok(created, "create %s" % requested_fqn)
    assert created.structured.get("action") == "created", created.structured
    assert created.structured.get("fqn") == canonical_fqn, created.structured

    # DISK FIRST: inspect the parameter itself, never Configuration.mdo's reference.
    path = "src/SessionParameters/%s/%s.mdo" % (name, name)
    mdo = poll_disk_contains_all(
        path, ["<type>", "<types>String</types>", "<length>10</length>"],
        ctx="the new SessionParameter must carry the wizard's variable String(10)", fixture_rel=fixture_rel,
    )
    root = ET.fromstring(mdo)
    type_blocks = [child for child in root if child.tag.rsplit("}", 1)[-1] == "type"]
    assert len(type_blocks) == 1, "expected one mandatory type in %s: %s" % (path, mdo)
    types = [child.text for child in type_blocks[0] if child.tag.rsplit("}", 1)[-1] == "types"]
    assert types == ["String"], "the default must be exactly String: %r" % types
    qualifiers = {block.tag.rsplit("}", 1)[-1]:
                  {child.tag.rsplit("}", 1)[-1]: child.text for child in block}
                  for block in type_blocks[0] if block.tag.rsplit("}", 1)[-1].endswith("Qualifiers")}
    assert qualifiers == {"stringQualifiers": {"length": "10"}}, \
        "the wizard gives a variable String(10), without numeric qualifiers: %r" % qualifiers
    assert_not_contains(mdo, "<fixed>true</fixed>", "the default string is variable length")

    incomplete, markers = assert_marker_contract(created.structured, "create %s" % canonical_fqn)
    if not incomplete:
        assert not [row for row in markers if row["object"] == canonical_fqn], \
            "a complete create response must carry no marker for the new parameter: %r" % markers

    details = call("get_metadata_details", {
        "projectName": project, "objectFqns": [canonical_fqn], "assignable": True,
    })
    assert_ok(details, "read the new SessionParameter's type through the model")
    rows = [cells for cells in (split_markdown_row(line) for line in details.text.splitlines())
            if len(cells) == 4 and cells[0] == "type"]
    assert len(rows) == 1, "the model must expose the parameter's type: %s" % details.text
    assert_contains(rows[0][2], "String", "the model must read back the default String type")

    # Like the #643 marker probes, wait for validation before consulting its result.
    # Repeat the settled query: one early empty marker stream is insufficient evidence.
    for _ in range(3):
        assert wait_for_project_ready(), "EDT must finish validating the new parameter"
        errors = call("get_project_errors", {
            "projectName": project, "objectFqns": [canonical_fqn],
        })
        assert_ok(errors, "settled SessionParameter errors")
        assert errors.structured.get("objectsResolved") == [canonical_fqn], errors.structured
        assert errors.structured.get("objectsNotFound") == [], errors.structured
        assert errors.structured.get("objectsUnsupported") == [], errors.structured
        assert errors.structured.get("problemsFound") == 0, \
            "the typed parameter must have no marker at all; the untyped one carries EDT's MAJOR type-not-specified marker (TypedMdObjectValidator 68): %r" \
            % errors.structured


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_session_parameter_carries_the_wizard_default_type():
    _assert_session_parameter_default("SessionParameter.E2E795Param", "E2E795Param")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_session_parameter_with_russian_type_token_carries_the_wizard_default_type():
    _assert_session_parameter_default("ПараметрСеанса.E2E795Параметр", "E2E795Параметр")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_session_parameter_in_an_extension_carries_the_wizard_default_type():
    """The extension is the representative target: its own new SessionParameter gets the same
    String(10). Writes into TESTS_PROJECT, so it restores the extension itself."""
    try:
        _assert_session_parameter_default("SessionParameter.tests_E2E795Param", "tests_E2E795Param",
                                          project=TESTS_PROJECT, fixture_rel=TESTS_PROJECT_REL)
    except E2ECallTimeout:
        # No cleanup: the timed-out call may still be writing; the orchestrator aborts the run.
        raise
    except BaseException:
        restore_extension_fixture("creating an extension-own SessionParameter")
        raise
    else:
        restore_extension_fixture("creating an extension-own SessionParameter")

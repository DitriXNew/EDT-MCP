"""Issue #732: writing a sibling must preserve valid negative form item ids.

Seed the fixture bytes directly because MCP intentionally does not expose an id setter.
The runner owns fixture isolation; the finally block also restores this file byte for byte
and reloads the original model so a failed assertion cannot leave negative test ids behind.
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET

from harness import (
    PROJECT,
    PROJECT_DIR,
    assert_contains,
    assert_ok,
    call,
    calls_aborted,
    e2e_test,
    read_disk,
    wait_for_project_ready,
)


_FORM_FILE = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
_SIBLING_FQN = "Catalog.Catalog.Form.ItemForm.Field.Description"
_NEGATIVE_IDS = {"Code": -5, "CodeContextMenu": -6, "CodeExtendedTooltip": -7}
_TITLE = "E2E732 sibling title"


def _seed_negative_ids(original):
    """Replace only id text, preserving the fixture's encoding and line endings."""
    seeded = original
    for name, item_id in _NEGATIVE_IDS.items():
        pattern = rb"(<name>" + re.escape(name.encode("utf-8")) + rb"</name>\s*<id>)[^<]+(</id>)"
        seeded, count = re.subn(
            pattern, lambda match: match[1] + str(item_id).encode("ascii") + match[2], seeded)
        assert count == 1, "expected exactly one fixture item named %s, found %d" % (name, count)
    return seeded


def _assert_negative_ids(xml):
    root = ET.fromstring(xml)
    for name, expected in _NEGATIVE_IDS.items():
        nodes = [node for node in root.iter() if node.findtext("name") == name]
        assert len(nodes) == 1, "expected exactly one persisted item named %s" % name
        assert nodes[0].findtext("id") == str(expected), \
            "%s must retain id %d, got %r" % (name, expected, nodes[0].findtext("id"))
    assert root.findtext("autoCommandBar/id") == "-1", "the root bar must keep its sentinel"
    return root


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_write_preserves_negative_item_and_companion_ids():
    form_path = Path(PROJECT_DIR) / _FORM_FILE
    wait_for_project_ready()
    original = form_path.read_bytes()
    seeded = _seed_negative_ids(original)
    _assert_negative_ids(seeded)

    try:
        form_path.write_bytes(seeded)
        wait_for_project_ready()
        # Re-import deterministically; filesystem auto-refresh alone is not sufficient on Windows.
        assert_ok(call("clean_project", {"projectName": PROJECT}), "import the seeded negative ids")
        wait_for_project_ready()

        result = call("modify_metadata", {
            "projectName": PROJECT,
            "fqn": _SIBLING_FQN,
            "properties": [{"name": "title", "value": _TITLE, "language": "en"}],
        })
        assert_ok(result, "write a different member of the form containing negative ids")

        # modify_metadata drains its own export before it answers, so the file is read ONCE, right
        # away. The NEW sibling value is checked in the same snapshot: the seeded ids alone predate
        # the write and would let a no-op tool pass without exercising form normalization.
        xml = read_disk(_FORM_FILE)
        assert_contains(xml, "<value>%s</value>" % _TITLE, "the sibling write must already be on disk")
        root = _assert_negative_ids(xml.encode("utf-8"))
        sibling = [node for node in root.findall("items") if node.findtext("name") == "Description"]
        assert len(sibling) == 1, "the unrelated Description field must remain in the form"
        assert sibling[0].findtext("title/key") == "en"
        assert sibling[0].findtext("title/value") == _TITLE, "the new title must belong to the sibling"

        details = call("get_metadata_details", {
            "projectName": PROJECT, "objectFqns": [_SIBLING_FQN], "assignable": True,
        })
        assert_ok(details, "read the changed sibling from the model")
        assert_contains(details.text, _TITLE, "the model must also expose the new sibling title")
    finally:
        # A timed-out call may still be importing or exporting this very form: once calls are
        # aborted the harness freezes the fixtures, and the file is left alone instead of raced.
        if not calls_aborted():
            form_path.write_bytes(original)
            wait_for_project_ready()
            assert_ok(call("clean_project", {"projectName": PROJECT}), "reload the restored form fixture")
            wait_for_project_ready()
            assert form_path.read_bytes() == original, "restoration must preserve the fixture bytes"

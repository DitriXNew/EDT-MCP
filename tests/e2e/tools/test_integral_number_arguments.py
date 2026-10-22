"""Regression coverage for whole numbers carried as JSON numbers by the MCP transport."""

import xml.etree.ElementTree as ET

from harness import (
    rename_call,
    E2ESkip,
    PROJECT,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_not_contains,
    assert_ok,
    e2e_test,
    call,
    read_disk,
    tree_snapshot,
    assert_tree_unchanged,
)
from tools.test_modify_metadata import _form_structure_text, _seed_form_field


FORM_PATH = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
FORM_FQN = "Catalog.Catalog.Form.ItemForm"
MISSING_LOG = "/tmp/no_such_1cv8log_integral_796_e2e"


def _root_item_names():
    root = ET.fromstring(read_disk(FORM_PATH))
    return [item.findtext("name") for item in root.findall("items")]


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_integral_number_position_lands_at_index_two():
    _seed_form_field("Integral796AttrA", "Integral796FieldA")
    _seed_form_field("Integral796AttrB", "Integral796FieldB")
    before = _root_item_names()
    moved = "Integral796FieldB"
    assert before.index(moved) != 2, "fixture must require a real reorder: %r" % before
    expected = [name for name in before if name != moved]
    expected.insert(2, moved)

    result = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": FORM_FQN + ".Field." + moved,
        "properties": [{"name": "position", "value": 2}],
    })
    assert_ok(result, "move with position sent as a JSON number")
    assert "position" in (result.structured.get("applied") or []), result.structured
    assert_contains(result.structured.get("destination") or "", "index 2", "reported destination")
    # Disk first: modify_metadata submits and drains this form's export before returning.
    actual = _root_item_names()
    assert actual == expected, "Form.form root order must be %r, got %r" % (expected, actual)

    outline = _form_structure_text()
    assert_contains(outline, moved, "moved field must remain in the model")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_integral_number_negative_position_echoes_the_integer():
    before = tree_snapshot()
    result = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": FORM_FQN + ".Field.Code",
        "properties": [{"name": "position", "value": -1}],
    })
    error = assert_error(result, "negative JSON-number position")
    assert_error_quality(error, names=["index '-1'"], suggests=["zero or positive"])
    assert_not_contains(error, "-1.0", "negative position must quote integer digits")
    assert_tree_unchanged(before, "a refused position must not reorder the form")
    assert_no_diff("negative position must leave the fixture clean")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_integral_number_disableindices_and_array_are_accepted_for_preview():
    before = tree_snapshot()
    for indices in (2, [2, 3]):
        result = rename_call({
            "projectName": PROJECT,
            "objectFqn": "CommonModule.CascadeEn",
            "newName": "Reckoner",
            "confirm": False,
            "disableIndices": indices,
        })
        assert_not_contains(result.error_text(), "could not be read as a change-point index",
                            "whole-number disableIndices must pass parsing")
        assert_ok(result, "preview with disableIndices=%r" % indices)
        assert_contains(result.text, "action: preview", "rename must remain a preview")
        assert_tree_unchanged(before, "disableIndices preview must not rename anything")
    model = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["CommonModule.CascadeEn"],
    })
    assert_ok(model, "original module must still resolve after both previews")
    assert_contains(model.text, "CascadeEn", "preview must preserve the original module in the model")
    assert_no_diff("both rename calls are previews")


@e2e_test(tool="get_event_log", kind="read")
def test_integral_number_session_passes_parsing_before_log_lookup():
    result = call("get_event_log", {"logDir": MISSING_LOG, "session": 5})
    error = assert_error(result, "nonexistent log after integral session parsing")
    assert_not_contains(error, "Invalid session", "JSON-number session must pass the integer guard")
    assert_error_quality(error, names=["no_such_1cv8log_integral_796_e2e"], suggests=["directory"])
    assert_no_diff("event-log lookup is read-only")


@e2e_test(tool="get_event_log", kind="read")
def test_integral_number_fractional_session_is_refused_verbatim():
    result = call("get_event_log", {"logDir": MISSING_LOG, "session": 5.5})
    error = assert_error(result, "fractional JSON-number session")
    assert_error_quality(error, names=["'5.5'"], suggests=["integer session number"])
    assert_no_diff("refused event-log filter is read-only")


@e2e_test(tool="get_event_log", kind="read")
def test_integral_number_bound_infobase_accepts_session_form():
    apps = call("get_applications", {"projectName": PROJECT})
    assert_ok(apps, "discover a bound infobase for the numeric session filter")
    applications = (apps.structured or {}).get("applications")
    assert isinstance(applications, list), "applications envelope must contain a list: %r" % apps.structured
    if not applications:
        assert_no_diff("no application is bound")
        raise E2ESkip("no infobase is bound to %s; the offline numeric-session test still runs" % PROJECT)
    result = call("get_event_log", {
        "projectName": PROJECT, "applicationId": applications[0]["id"], "session": 5,
    })
    if result.is_error:
        error = assert_error(result, "bound event-log query")
        assert_not_contains(error, "Invalid session", "numeric session must reach infobase/log resolution")
        assert error.strip(), "bound log resolution must return an explanation"
    else:
        assert_ok(result, "bound log with numeric session filter")
        assert isinstance((result.structured or {}).get("events"), list), result.structured
    assert_no_diff("bound event-log query is read-only")

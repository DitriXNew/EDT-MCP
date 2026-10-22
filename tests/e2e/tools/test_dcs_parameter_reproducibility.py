"""#682 replacement refusals and the #680 filter-right pin; run only on an EDT stand."""

from copy import deepcopy
from io import StringIO
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

from harness import (
    PROJECT, PROJECT_DIR, assert_error, assert_error_quality, assert_ok, call, e2e_test,
    poll_disk_contains, wait_for_project_ready,
)
from tools.test_dcs import (
    _get, _hash, _poll_report_dcs, _read_all_xml, _seed_report, _write, _xml_structure,
)

def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _parse(xml):
    # iterparse returns (event, (prefix, uri)); retain every declaration used in QName values.
    namespaces = dict(value for _event, value in ET.iterparse(StringIO(xml), events=("start-ns",)))
    return ET.fromstring(xml), namespaces


def _render(document, namespaces):
    for prefix, uri in namespaces.items():
        if not re.fullmatch(r"ns\d+", prefix) and prefix != "xml":
            ET.register_namespace(prefix, uri)
    xml = ET.tostring(document, encoding="unicode")
    # ElementTree does not know that xsi:type contains a QName; retain those prefix declarations.
    declarations = []
    head = xml.split(">", 1)[0]
    for prefix, uri in sorted(namespaces.items()):
        name = "xmlns:" + prefix if prefix else "xmlns"
        if not re.search(r"\b" + re.escape(name) + r"=", head):
            declarations.append(" " + name + "=" + quoteattr(uri))
    if declarations:
        at = xml.index(" ") if " " in head else xml.index(">")
        xml = xml[:at] + "".join(declarations) + xml[at:]
    return xml


def _xml(fqn):
    return _read_all_xml(fqn)[0]


def _import(fqn, document, namespaces):
    current = _get(fqn, "schema")
    assert_ok(current, "read schema hash before XML seeding")
    result = _write(fqn, "replace", "schema", {"xml": _render(document, namespaces)},
                    expectedHash=_hash(current))
    assert_ok(result, "seed an EDT-serialized value through the lossless XML channel")


def _parameter_item(document, *, name=None, marker=None):
    for element in document.iter():
        parameters = [child for child in element if _local(child.tag) == "parameter"]
        if not parameters:
            continue
        if name is not None and any(child.text == name for child in parameters):
            return element
        if marker is not None and any(node.text == marker for node in element.iter()):
            return element
    raise AssertionError("serialized parameter item missing: %r / %r" % (name, marker))


def _values(item):
    return [child for child in item if _local(child.tag) in ("value", "values")]


def _flushed_bytes(report_name, fqn):
    relative = _poll_report_dcs(report_name)
    path = Path(PROJECT_DIR, relative)
    expected = _xml_structure(_xml(fqn))
    deadline = time.monotonic() + 30
    while True:
        payload = path.read_bytes()
        if _xml_structure(payload.decode("utf-8-sig")) == expected:
            return path, payload
        if time.monotonic() >= deadline:
            raise AssertionError("Template.dcs export did not catch up with the schema model")
        time.sleep(0.2)


def _refused_unchanged(report_name, fqn, address, target_type, body, names, update_hint=True):
    path, before = _flushed_bytes(report_name, fqn)
    current = _get(address, target_type)
    assert_ok(current, "read exact replacement target")
    error = assert_error(_write(address, "replace", target_type, body, expectedHash=_hash(current)),
                         "replace must refuse a value its writer cannot reproduce")
    assert_error_quality(error, names=names,
                         suggests=["action='update'", "lossless XML channel"] if update_hint else ["DCS designer"],
                         ctx="reproducibility refusal")
    assert path.read_bytes() == before, "a refused replace must leave Template.dcs byte-identical"
    return error


@e2e_test(tool="dcs", kind="write-metadata")
def test_replace_refuses_a_data_parameter_whose_values_it_cannot_restate():
    name = "E2EDcs682DataValues"
    fqn = _seed_report(name)
    assert_ok(_write(fqn, "upsert", "schema", {
        "parameters": [{"name": "E2E682P", "valueType": {"types": [{"kind": "Number"}]}}],
        "defaultSettings": {"dataParameters": {"items": [{
            "parameter": {"kind": "parameter", "value": "E2E682P"},
            "value": {"kind": "number", "value": 701}, "use": True,
        }]}},
    }), "seed one authorable data parameter")
    document, namespaces = _parse(_xml(fqn))
    item = _parameter_item(document, name="E2E682P")
    stored = _values(item)
    assert len(stored) == 1, "the authored parameter must serialize one value"
    item.append(deepcopy(stored[0]))
    _import(fqn, document, namespaces)
    _refused_unchanged(name, fqn, fqn + "#/defaultSettings", "userSettings", {},
                       ["SettingsParameterValue", "#/defaultSettings/dataParameters/items/0", "2 values"])
    current = _get(fqn + "#/defaultSettings/dataParameters/items/0", "dataParameter")
    assert_ok(current)
    assert_ok(_write(fqn + "#/defaultSettings/dataParameters/items/0", "update", "dataParameter",
                     {"use": False}, expectedHash=_hash(current)), "update must preserve both unnamed values")
    persisted, _ = _parse(_xml(fqn))
    assert len(_values(_parameter_item(persisted, name="E2E682P"))) == 2
    _flushed_bytes(name, fqn)


@e2e_test(tool="dcs", kind="write-metadata")
def test_replace_refuses_an_output_parameter_name_outside_the_platform_catalogue():
    name = "E2EDcs682UnknownOutput"
    fqn = _seed_report(name)
    assert_ok(_write(fqn, "upsert", "schema", {
        "defaultSettings": {"outputParameters": {"items": [{
            "parameter": {"kind": "parameter", "value": "Title"},
            "value": {"en": "E2E682Output"}, "use": True,
        }]}},
    }), "seed a valid typed output parameter")
    document, namespaces = _parse(_xml(fqn))
    item = _parameter_item(document, marker="E2E682Output")
    parameter = next(child for child in item if _local(child.tag) == "parameter")
    parameter.text = "E2ENotACatalogueName"
    _import(fqn, document, namespaces)
    error = _refused_unchanged(name, fqn, fqn + "#/defaultSettings", "userSettings", {},
                               ["E2ENotACatalogueName", "platform catalogue for"])
    assert re.search(r"platform catalogue for \d+\.\d+", error), error
    current = _get(fqn + "#/defaultSettings/outputParameters/items/0", "outputParameter")
    assert_ok(current)
    assert_ok(_write(fqn + "#/defaultSettings/outputParameters/items/0", "update", "outputParameter",
                     {"use": False}, expectedHash=_hash(current)), "update must keep the unknown name")
    persisted, _ = _parse(_xml(fqn))
    assert _parameter_item(persisted, name="E2ENotACatalogueName") is not None
    _flushed_bytes(name, fqn)


@e2e_test(tool="dcs", kind="write-metadata")
def test_replace_refuses_a_font_height_the_writer_cannot_restate():
    name = "E2EDcs682FractionalFont"
    fqn = _seed_report(name)
    assert_ok(_write(fqn, "upsert", "schema", {
        "defaultSettings": {"conditionalAppearance": {"items": [{
            "appearance": {"Font": {"font": {"faceName": "Arial", "height": 10}}},
        }]}},
    }), "seed an authorable font")
    document, namespaces = _parse(_xml(fqn))
    font = next(node for node in document.iter() if node.get("height") in ("10", "10.0"))
    font.set("height", "10.5")
    _import(fqn, document, namespaces)
    _refused_unchanged(name, fqn, fqn + "#/defaultSettings", "userSettings", {}, ["font height 10.5"])
    font.set("height", "10")
    _import(fqn, document, namespaces)
    current = _get(fqn + "#/defaultSettings", "userSettings")
    assert_ok(current)
    assert_ok(_write(fqn + "#/defaultSettings", "replace", "userSettings", {},
                     expectedHash=_hash(current)), "integer-height positive control must pass")


@e2e_test(tool="dcs", kind="write-metadata")
def test_replace_refuses_value_list_and_standard_beginning_date_in_filter_right():
    # Fixture is produced by DcsFilterRightSerializationTest using EDT's own model serializer
    # (run it with -Dedtmcp.refreshDcsFixtures=true to write target/dcs-682-filter-right.xml).
    # Import, read back via get format=xml, then clone that native serialization into the target.
    source = _seed_report("E2EDcs682RightSource")
    fixture = Path(__file__).resolve().parents[1] / "fixtures" / "dcs_682_filter_right.xml"
    document, namespaces = _parse(fixture.read_text(encoding="utf-8-sig"))
    _import(source, document, namespaces)
    source_document, source_namespaces = _parse(_xml(source))
    samples = [node for node in source_document.iter() if _local(node.tag) == "right"]
    assert len(samples) == 2, "EDT fixture must serialize both filter-right shapes"
    for index, (sample, expected_class) in enumerate(zip(samples, ("TypedValueList", "StandardBeginningDateValue"))):
        name = "E2EDcs682Right%d" % index
        fqn = _seed_report(name)
        assert_ok(_write(fqn, "upsert", "schema", {"defaultSettings": {"items": [{
            "name": "G", "filter": {"items": [{
                "left": {"kind": "field", "value": "Amount1"}, "comparisonType": "Equal",
                "right": [{"kind": "number", "value": 1}],
            }]},
        }]}}), "seed a filter with an authorable right operand")
        destination, destination_namespaces = _parse(_xml(fqn))
        parents = {child: parent for parent in destination.iter() for child in parent}
        old_right = next(node for node in destination.iter() if _local(node.tag) == "right")
        owner = parents[old_right]
        at = list(owner).index(old_right)
        owner.remove(old_right)
        replacement = deepcopy(sample)
        replacement.tag = old_right.tag
        owner.insert(at, replacement)
        destination_namespaces.update(source_namespaces)
        _import(fqn, destination, destination_namespaces)
        _refused_unchanged(name, fqn, fqn + "#/defaultSettings/items/0", "grouping", {"name": "G"},
                           [expected_class + " at " + fqn + "#/defaultSettings/items/0/filter/items/0/right"],
                           update_hint=False)


@e2e_test(tool="dcs", kind="write-metadata")
def test_form_conditional_appearance_replace_keeps_a_form_only_appearance_key():
    # Pins the form call site's catalogue routing: 'Visible' exists only in the form appearance
    # catalogue, so a replace scanned with the schema catalogue would refuse the seeded rule.
    catalog_name = "E2EDcs682FormAppearance"
    catalog = "Catalog." + catalog_name
    form = catalog + ".Form.ItemForm"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": catalog}),
              "seed catalog for form conditional appearance")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}),
              "seed managed form for conditional appearance")
    wait_for_project_ready()

    def rule(marker):
        return {"items": [{
            "selection": {"items": [{"use": True, "field": {"kind": "field", "value": marker}}]},
            "appearance": {"Visible": {"kind": "boolean", "value": False}},
        }]}

    assert_ok(_write(form, "upsert", "conditionalAppearance", rule("E2E682FormSeed")),
              "seed a rule that carries the form-only appearance key")
    current = _get(form, "conditionalAppearance")
    assert_ok(current, "read the form conditional appearance hash")
    replaced = _write(form, "replace", "conditionalAppearance", rule("E2E682FormReplaced"),
                      expectedHash=_hash(current))
    assert_ok(replaced, "a form replace over a form-only appearance key must pass the reproducibility guard")
    appearance_rel = "src/Catalogs/%s/Forms/ItemForm/ConditionalAppearance.dcssca" % catalog_name
    poll_disk_contains(appearance_rel, "E2E682FormReplaced",
                       ctx="the replaced form conditional appearance must reach ConditionalAppearance.dcssca")
    read_back = _get(form, "conditionalAppearance")
    assert_ok(read_back, "read back the replaced form conditional appearance")
    assert "E2E682FormReplaced" in read_back.text and "Visible" in read_back.text, read_back.text
    assert "E2E682FormSeed" not in read_back.text, "replace must drop the rule the body does not name"

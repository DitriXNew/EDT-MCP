"""#682 replacement refusals and round trips, and the #680 filter-right values; run only on an EDT stand."""

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


def _seed_two_values(name, first, mutate_copy):
    """A report whose data parameter E2E682P holds `first` twice in EDT's own XML; the copy is edited."""
    fqn = _seed_report(name)
    assert_ok(_write(fqn, "upsert", "schema", {
        "defaultSettings": {"dataParameters": {"items": [{
            "parameter": {"kind": "parameter", "value": "E2E682P"}, "value": first, "use": True,
        }]}},
    }), "seed one authorable data parameter")
    document, namespaces = _parse(_xml(fqn))
    item = _parameter_item(document, name="E2E682P")
    stored = _values(item)
    assert len(stored) == 1, "the authored parameter must serialize one value"
    copy = deepcopy(stored[0])
    mutate_copy(copy)
    item.append(copy)
    _import(fqn, document, namespaces)
    return fqn


@e2e_test(tool="dcs", kind="write-metadata")
def test_replace_restates_a_data_parameter_with_several_values():
    # #682 round 5: several values of one data parameter are restated through 'values'.
    name = "E2EDcs682DataValues"
    fqn = _seed_two_values(name, {"kind": "number", "value": 701}, lambda copy: None)
    path, before = _flushed_bytes(name, fqn)
    address = fqn + "#/defaultSettings/dataParameters/items/0"
    current = _get(address, "dataParameter")
    assert_ok(current)
    assert_ok(_write(address, "replace", "dataParameter", {
        "parameter": {"kind": "parameter", "value": "E2E682P"},
        "values": [{"kind": "number", "value": 701}, {"kind": "number", "value": 702}], "use": True,
    }, expectedHash=_hash(current)), "replace must accept and write both values")
    persisted, _ = _parse(_xml(fqn))
    values = _values(_parameter_item(persisted, name="E2E682P"))
    assert [value.text for value in values] == ["701", "702"], [value.text for value in values]
    _, after = _flushed_bytes(name, fqn)
    assert after != before and b"702" in after, "Template.dcs must carry the restated values"
    whole = _get(fqn + "#/defaultSettings", "userSettings")
    assert_ok(whole)
    assert_ok(_write(fqn + "#/defaultSettings", "replace", "userSettings", {"dataParameters": {"items": [{
        "parameter": {"kind": "parameter", "value": "E2E682P"},
        "values": [{"kind": "number", "value": 701}, {"kind": "number", "value": 702}], "use": True,
    }]}}, expectedHash=_hash(whole)), "the guard must pass a restatable multi-valued data parameter")
    persisted, _ = _parse(_xml(fqn))
    assert len(_values(_parameter_item(persisted, name="E2E682P"))) == 2


@e2e_test(tool="dcs", kind="write-metadata")
def test_replace_refuses_a_data_parameter_value_it_cannot_restate():
    name = "E2EDcs682DataEmptyField"

    def empty(copy):
        copy.text = None

    fqn = _seed_two_values(name, {"kind": "field", "value": "E2E682Field"}, empty)
    _refused_unchanged(name, fqn, fqn + "#/defaultSettings", "userSettings", {},
                       ["SettingsParameterValue", "#/defaultSettings/dataParameters/items/0", "value #1",
                        "the writer refuses its body"])
    current = _get(fqn + "#/defaultSettings/dataParameters/items/0", "dataParameter")
    assert_ok(current)
    assert_ok(_write(fqn + "#/defaultSettings/dataParameters/items/0", "update", "dataParameter",
                     {"use": False}, expectedHash=_hash(current)), "update must preserve both unnamed values")
    persisted, _ = _parse(_xml(fqn))
    assert len(_values(_parameter_item(persisted, name="E2E682P"))) == 2
    _flushed_bytes(name, fqn)


@e2e_test(tool="dcs", kind="write-metadata")
def test_standard_period_data_parameter_round_trips_through_replace():
    # #682 round 5: the most common data-parameter value in real reports is a standard period.
    name = "E2EDcs682Period"
    fqn = _seed_report(name)
    period = {"kind": "standardPeriod", "value": {"variant": "ThisMonth"}}
    beginning = {"kind": "standardBeginningDate",
                 "value": {"variant": "Custom", "date": "2026-01-01T00:00:00"}}
    items = [{"parameter": {"kind": "parameter", "value": "E2E682Period"}, "value": period, "use": True},
             {"parameter": {"kind": "parameter", "value": "E2E682Begin"}, "value": beginning, "use": True}]
    assert_ok(_write(fqn, "upsert", "schema", {"defaultSettings": {"dataParameters": {"items": items}}}),
              "write a standard period and a standard beginning date")
    path, before = _flushed_bytes(name, fqn)
    text = before.decode("utf-8-sig")
    assert "ThisMonth" in text and "2026-01-01T00:00:00" in text, "both values must reach Template.dcs"
    shapes = [_shape(value) for value in _values(_parameter_item(_parse(_xml(fqn))[0], name="E2E682Period"))]
    whole = _get(fqn + "#/defaultSettings", "userSettings")
    assert_ok(whole)
    assert_ok(_write(fqn + "#/defaultSettings", "replace", "userSettings",
                     {"dataParameters": {"items": items}}, expectedHash=_hash(whole)),
              "the guard must pass a standard period it can restate")
    persisted, _ = _parse(_xml(fqn))
    assert [_shape(value) for value in _values(_parameter_item(persisted, name="E2E682Period"))] == shapes, \
        "the replaced period must serialize exactly as before"
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


def _shape(element):
    """An element's structure with namespace URIs dropped: local tag, attributes, text, children."""
    text = element.text.strip() if element.text and element.text.strip() else None
    return (_local(element.tag), tuple(sorted(element.attrib.items())), text,
            tuple(_shape(child) for child in element))


@e2e_test(tool="dcs", kind="write-metadata")
def test_filter_right_value_list_and_standard_beginning_date_round_trip_through_replace():
    # #680 after #682 round 5: the value list and the standard beginning date EDT stores in a
    # filter's right side are restated by valueList and standardBeginningDate bodies. Fixture from
    # DcsFilterRightSerializationTest (EDT's own serializer); never hand-written element names.
    source = _seed_report("E2EDcs682RightSource")
    fixture = Path(__file__).resolve().parents[1] / "fixtures" / "dcs_682_filter_right.xml"
    document, namespaces = _parse(fixture.read_text(encoding="utf-8-sig"))
    _import(source, document, namespaces)
    source_document, source_namespaces = _parse(_xml(source))
    samples = [node for node in source_document.iter() if _local(node.tag) == "right"]
    assert len(samples) == 2, "EDT fixture must serialize both filter-right shapes"
    bodies = [
        {"kind": "valueList", "value": {"lastId": 1, "items": [{"id": 1, "value": {"kind": "number", "value": 1}}]}},
        {"kind": "standardBeginningDate", "value": {"variant": "Custom", "date": "2026-01-01T00:00:00"}},
    ]
    for index, (sample, body) in enumerate(zip(samples, bodies)):
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
        seeded = [node for node in _parse(_xml(fqn))[0].iter() if _local(node.tag) == "right"]
        assert len(seeded) == 1 and _shape(seeded[0]) == _shape(sample), "the native value must be seeded"
        address = fqn + "#/defaultSettings/items/0"
        current = _get(address, "grouping")
        assert_ok(current, "read the grouping that holds the native value")
        assert_ok(_write(address, "replace", "grouping", {"name": "G", "filter": {"items": [{
            "left": {"kind": "field", "value": "Amount1"}, "comparisonType": "Equal", "right": [body],
        }]}}, expectedHash=_hash(current)), "replace must pass the guard and restate the native value")
        restated = [node for node in _parse(_xml(fqn))[0].iter() if _local(node.tag) == "right"]
        assert len(restated) == 1, "the restated filter must keep one right operand"
        assert _shape(restated[0]) == _shape(sample), \
            "the writer must rebuild EDT's own serialization: %r != %r" % (_shape(restated[0]), _shape(sample))
        _flushed_bytes(name, fqn)


@e2e_test(tool="dcs", kind="write-metadata")
def test_platform_color_references_round_trip_through_replace():
    # #682 round 5: web, Windows and platform style colors are written and restated by name.
    name = "E2EDcs682Colors"
    fqn = _seed_report(name)
    appearance = {"TextColor": {"color": {"web": "Red"}}, "BackColor": {"color": {"style": "FieldBackColor"}},
                  "BorderColor": {"color": {"windows": "WindowText"}}}
    rule = {"conditionalAppearance": {"items": [{"appearance": appearance}]}}
    assert_ok(_write(fqn, "upsert", "schema", {"defaultSettings": rule}), "write platform named colors")
    path, before = _flushed_bytes(name, fqn)
    text = before.decode("utf-8-sig")
    for stored in (">web:Red<", ">style:FieldBackColor<", ">win:WindowText<"):
        assert stored in text, "Template.dcs must store %s: %s" % (stored, text[:4000])
    # Run the guard on values EDT read with the project, including catalogue and BM proxies.
    _import(fqn, *_parse(_xml(fqn)))
    whole = _get(fqn + "#/defaultSettings", "userSettings")
    assert_ok(whole)
    assert_ok(_write(fqn + "#/defaultSettings", "replace", "userSettings", rule, expectedHash=_hash(whole)),
              "the guard must pass the platform color references it can restate")
    _, after = _flushed_bytes(name, fqn)
    for stored in (">web:Red<", ">style:FieldBackColor<", ">win:WindowText<"):
        assert stored in after.decode("utf-8-sig"), "the replace must keep " + stored


@e2e_test(tool="dcs", kind="write-metadata")
def test_configuration_style_color_round_trips_through_replace():
    # #682 round 5: the guard restates a configuration style color through the project's resolver.
    style_name = "E2EDcs682StyleColor"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "StyleItem." + style_name}),
              "create the style item")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "StyleItem." + style_name, "properties": [{
        "name": "value", "value": {"color": {"red": 10, "green": 20, "blue": 30}}}]}),
        "make the style item a color")
    wait_for_project_ready()
    name = "E2EDcs682StyleReport"
    fqn = _seed_report(name)
    rule = {"conditionalAppearance": {"items": [{"appearance": {"BackColor": {"color": {"style": style_name}}}}]}}
    assert_ok(_write(fqn, "upsert", "schema", {"defaultSettings": rule}), "write the configuration style color")
    _, before = _flushed_bytes(name, fqn)
    assert (">style:%s<" % style_name) in before.decode("utf-8-sig")
    # Run the guard on values EDT read with the project, including catalogue and BM proxies.
    _import(fqn, *_parse(_xml(fqn)))
    whole = _get(fqn + "#/defaultSettings", "userSettings")
    assert_ok(whole)
    assert_ok(_write(fqn + "#/defaultSettings", "replace", "userSettings", rule, expectedHash=_hash(whole)),
              "the guard must pass a configuration style color the writer can restate")
    _, after = _flushed_bytes(name, fqn)
    assert (">style:%s<" % style_name) in after.decode("utf-8-sig")


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

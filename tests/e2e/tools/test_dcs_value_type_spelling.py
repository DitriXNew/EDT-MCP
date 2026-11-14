"""End-to-end tests for #920: one reference syntax and the StandardPeriod parameter type in ``dcs``.

A schema parameter or field type is built by the same type grammar ``modify_metadata`` uses. The
platform stores and every read prints a reference type as ``CatalogRef.<Name>``, so a reference named
that way - ``{kind:'Ref', ref:'CatalogRef.<Name>'}``, or the Russian reference type token - must land
the very same stored type as ``{kind:'Ref', ref:'Catalog.<Name>'}`` and ``{kind:'CatalogRef',
ref:'<Name>'}``. A ``StandardPeriod`` parameter type is matched case-insensitively, the way the
``standardPeriod`` value spec is spelled.

Every assertion reads the stored ``Template.dcs`` (or ``.mdo``) from disk, not only the response.
"""

import re
import xml.etree.ElementTree as ET

from harness import (
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
    assert_error,
    assert_error_quality,
    assert_ok,
    call,
    e2e_test,
    poll_disk_contains_all,
    read_fixture_file,
    restore_extension_fixture,
    wait_for_project_ready,
)

MAIN_DCS_TEMPLATE = (
    "Основная"
    "СхемаКомпоновки"
    "Данных"
)
# SpravochnikSsylka - the Russian spelling of the CatalogRef reference type token.
RU_CATALOG_REF = (
    "Справочник"
    "Ссылка"
)
# StandartnyjPeriod, lower-cased the way a caller may type it.
RU_STANDARD_PERIOD_LOWER = (
    "стандартный"
    "период"
)
# The extension fixture's own catalog and the base catalog it adopts.
EXT_OWN_CATALOG = "tests_ExtOnly"
EXT_ADOPTED_CATALOG = "Catalog"


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _child_text(element, name):
    for child in element:
        if _local(child.tag) == name:
            return child.text
    return None


def _stored_types(xml, tag, key_tag, key):
    """The stored type names of the <tag> whose <key_tag> is key, without their namespace prefix."""
    for element in ET.fromstring(xml).iter():
        if _local(element.tag) != tag or _child_text(element, key_tag) != key:
            continue
        for child in element:
            if _local(child.tag) == "valueType":
                return [t.text.split(":", 1)[-1] for t in child if _local(t.tag) == "Type"]
        return []
    return None


def _dcs_path(report):
    return "src/Reports/%s/Templates/%s/Template.dcs" % (report, MAIN_DCS_TEMPLATE)


def _seed_report(project, name):
    fqn = "Report." + name
    assert_ok(call("create_metadata", {"projectName": project, "fqn": fqn}), "seed report " + fqn)
    wait_for_project_ready()
    assert_ok(call("dcs", {
        "projectName": project, "fqn": fqn, "action": "upsert", "type": "schema",
        "body": {"dataSets": [{"name": "DataSet1", "type": "query", "query": "SELECT 1 AS Amount",
                               "autoFillFields": False, "fields": [{"dataPath": "Amount"}]}]},
    }), "author report DCS " + fqn)
    wait_for_project_ready()
    return fqn


def _dcs(project, fqn, action, target_type, body):
    return call("dcs", {"projectName": project, "fqn": fqn, "action": action, "type": target_type,
                        "body": body})


def _parameter(project, report, name, item):
    return _dcs(project, report, "upsert", "parameter", {"name": name, "valueType": {"types": [item]}})


def _field(project, report, data_path, item):
    return _dcs(project, report + "#/dataSets/DataSet1/fields/" + data_path, "upsert", "field",
                {"dataPath": data_path, "valueType": {"types": [item]}})


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_reference_type_name_lands_the_same_type_as_the_object_address_920():
    """The reporter's case: an EXTENSION report's parameters and fields, typed by the reference type."""
    name = "E2EDcsRefSpelling920"
    try:
        report = _seed_report(TESTS_PROJECT, name)
        parameters = {
            "ByRefTypeName": {"kind": "Ref", "ref": "CatalogRef." + EXT_OWN_CATALOG},
            "ByRuRefTypeName": {"kind": "Ref", "ref": RU_CATALOG_REF + "." + EXT_OWN_CATALOG},
            "ByObjectAddress": {"kind": "Ref", "ref": "Catalog." + EXT_OWN_CATALOG},
            "ByRefKind": {"kind": "CatalogRef", "ref": EXT_OWN_CATALOG},
        }
        for parameter, item in parameters.items():
            assert_ok(_parameter(TESTS_PROJECT, report, parameter, item), "parameter " + parameter)
        fields = {
            "AdoptedByRefTypeName": {"kind": "Ref", "ref": "CatalogRef." + EXT_ADOPTED_CATALOG},
            "AdoptedByObjectAddress": {"kind": "Ref", "ref": "Catalog." + EXT_ADOPTED_CATALOG},
        }
        for field, item in fields.items():
            assert_ok(_field(TESTS_PROJECT, report, field, item), "field " + field)

        disk = poll_disk_contains_all(_dcs_path(name), list(parameters) + list(fields),
                                      fixture_rel=TESTS_PROJECT_REL, ctx="typed members reach the .dcs")
        for parameter in parameters:
            assert _stored_types(disk, "parameter", "name", parameter) == ["CatalogRef." + EXT_OWN_CATALOG], \
                "parameter %s must store the catalog's reference type:\n%s" % (parameter, disk)
        for field in fields:
            assert _stored_types(disk, "field", "dataPath", field) == ["CatalogRef." + EXT_ADOPTED_CATALOG], \
                "field %s must store the adopted catalog's reference type:\n%s" % (field, disk)
    finally:
        restore_extension_fixture("dcs reference type spelling in the extension")


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_unresolved_reference_in_an_extension_suggests_adopting_it_920():
    """An extension cannot see a base object until it is adopted; the dcs refusal must say so."""
    name = "E2EDcsRefAdoptHint920"
    try:
        report = _seed_report(TESTS_PROJECT, name)
        before = poll_disk_contains_all(_dcs_path(name), ["DataSet1"], fixture_rel=TESTS_PROJECT_REL,
                                        ctx="seeded schema reaches disk")
        refused = _parameter(TESTS_PROJECT, report, "Missing", {"kind": "Ref", "ref": "CatalogRef.E2EMissing920"})
        err = assert_error(refused, "an unresolved reference is refused")
        assert_error_quality(err, names=["CatalogRef.E2EMissing920"], suggests=["adopt_metadata_object"])
        after = read_fixture_file(TESTS_PROJECT_REL, _dcs_path(name))
        assert before == after, "a refused parameter must not change the .dcs"
    finally:
        restore_extension_fixture("dcs unresolved reference in the extension")


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_standard_period_parameter_type_in_any_case_920():
    """``standardPeriod`` - the value spec's spelling - and a lower-cased Russian token build the type."""
    name = "E2EDcsPeriodCase920"
    report = _seed_report(PROJECT, name)
    period = {"kind": "standardPeriod", "value": {"variant": "ThisMonth"}}
    for parameter, kind in [("PeriodValueSpelling", "standardPeriod"), ("PeriodRuLower", RU_STANDARD_PERIOD_LOWER)]:
        assert_ok(_dcs(PROJECT, report, "upsert", "parameter", {
            "name": parameter, "valueType": {"types": [{"kind": kind}]}, "values": [period],
        }), "StandardPeriod parameter typed as " + kind)
    disk = poll_disk_contains_all(_dcs_path(name), ["PeriodValueSpelling", "PeriodRuLower", "ThisMonth"],
                                  ctx="period parameters reach the .dcs")
    for parameter in ("PeriodValueSpelling", "PeriodRuLower"):
        assert _stored_types(disk, "parameter", "name", parameter) == ["StandardPeriod"], \
            "parameter %s must store the platform StandardPeriod type:\n%s" % (parameter, disk)
    read = call("dcs", {"projectName": PROJECT, "fqn": report + "#/parameters/PeriodValueSpelling",
                        "action": "get", "type": "parameter"})
    assert_ok(read, "read the period parameter back")
    assert "StandardPeriod" in read.text and "ThisMonth" in read.text, read.text


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_metadata_reference_type_name_lands_the_same_type_920():
    """The grammar is shared: an attribute typed by the reference type stores what the object address does."""
    catalog = "Catalog.E2ERefTypeName920"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": catalog}), "seed catalog")
    wait_for_project_ready()
    attributes = {
        "ByRefTypeName": {"kind": "Ref", "ref": "CatalogRef.E2ERefTypeName920"},
        "ByRuRefTypeName": {"kind": "Ref", "ref": RU_CATALOG_REF + ".E2ERefTypeName920"},
        "ByObjectAddress": {"kind": "Ref", "ref": "Catalog.E2ERefTypeName920"},
    }
    for attribute, item in attributes.items():
        fqn = catalog + ".Attribute." + attribute
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed " + fqn)
        assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": fqn,
                                           "properties": [{"name": "type", "value": {"types": [item]}}]}),
                  "type " + fqn)
    path = "src/Catalogs/E2ERefTypeName920/E2ERefTypeName920.mdo"

    def every_attribute_typed(text):
        blocks = re.findall(r"<attributes\b.*?</attributes>", text, re.S)
        typed = [b for b in blocks if "CatalogRef.E2ERefTypeName920</types>" in b]
        return len(typed) == len(attributes)

    disk = poll_disk_contains_all(path, list(attributes), ctx="typed attributes reach the .mdo",
                                  check=every_attribute_typed)
    assert every_attribute_typed(disk), disk

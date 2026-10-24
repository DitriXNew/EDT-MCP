"""Route-map stand proofs. Every positive case fails on a jar without the tool.

The baseline fixture has no BusinessProcess. Each positive case creates its own
ZzRm object, deletes it in finally, and also uses the runner's write-metadata reset.
Run with EDT_MCP_DESTRUCTIVE_CONSENT=allow on the stand for replace/remove/cleanup.
"""

from contextlib import contextmanager
import os
import xml.etree.ElementTree as ET

from harness import (
    PROJECT, PROJECT_DIR, assert_error, assert_error_quality, assert_no_diff, assert_ok,
    assert_tree_unchanged, call, e2e_test, poll_disk_contains_all,
    poll_disk_path_gone, tree_snapshot, wait_for_project_ready,
)


TOOL = "business_process_route_map"
POINTS = [
    {"name": "Begin", "type": "Start"},
    {"name": "Task", "type": "Activity"},
    {"name": "Done", "type": "Completion"},
]
CONNECTIONS = [
    {"from": "Begin", "to": "Task", "name": "BeginTask"},
    {"from": "Task", "to": "Done", "name": "TaskDone"},
]


@contextmanager
def _process(suffix):
    fqn = "BusinessProcess.ZzRm" + suffix
    created = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(created, "create the test BusinessProcess")
    try:
        yield fqn
    finally:
        removed = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
        assert_ok(removed, "delete the test BusinessProcess")


def _get(fqn):
    result = call(TOOL, {"projectName": PROJECT, "fqn": fqn, "action": "get"})
    assert_ok(result, "read route map")
    assert result.structured and result.structured["action"] == "get"
    return result.structured


def _replace(fqn, points=POINTS, connections=CONNECTIONS):
    result = call(TOOL, {"projectName": PROJECT, "fqn": fqn, "action": "replace",
                         "points": points, "connections": connections})
    assert_ok(result, "replace route map")
    data = result.structured
    assert data["persisted"] is True, data
    assert PROJECT in data["writtenProjects"], data
    assert data["points"] == len(points) and data["connections"] == len(connections), data
    return data


def _file(data):
    # IProjectFileSystemSupport owns the path. Accept its project-relative or
    # workspace-absolute spelling, and never guess the Flowchart.scheme location.
    path = data["file"].replace("\\", "/")
    prefix = "/" + PROJECT + "/"
    if path.startswith(prefix):
        path = path[len(prefix):]
    elif path.startswith(PROJECT + "/"):
        path = path[len(PROJECT) + 1:]
    elif os.path.isabs(path):
        path = os.path.relpath(path, PROJECT_DIR).replace("\\", "/")
    assert path.startswith("src/") and ".." not in path.split("/"), data
    return path


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _props(node):
    for child in node:
        if _local(child.tag) == "Properties":
            return child
    return None


def _end(node, side):
    props = _props(node)
    if props is not None:
        for child in props:
            if _local(child.tag) == "Connect":
                for end in child:
                    if _local(end.tag) == side:
                        return {_local(c.tag): (c.text or "") for c in end}
    return {}


def _value(node, key, default=None):
    # EDT exports xcf/scheme XML, not EMF feature names.
    if key == "pointUUID":
        return node.attrib.get("uuid", default)
    if key == "id":
        return node.attrib.get("id", default)
    ends = {"sourceItemName": ("From", "Item"), "targetItemName": ("To", "Item"),
            "sourcePortIndex": ("From", "PortIndex"), "targetPortIndex": ("To", "PortIndex")}
    if key in ends:
        side, tag = ends[key]
        return _end(node, side).get(tag, default)
    props = _props(node)
    if props is not None:
        tag = {"name": "Name"}.get(key, key)
        for child in props:
            if _local(child.tag) == tag:
                return child.text or ""
    return default


def _disk(data, names):
    text = poll_disk_contains_all(_file(data), ["uuid="] + list(names), ctx="export route-map document")
    document = ET.fromstring(text)
    assert document.tag == "{http://v8.1c.ru/8.3/xcf/scheme}GraphicalSchema", document.tag
    items = {}
    for node in document.iter():
        name = _value(node, "name")
        if name in names:
            assert name not in items, "duplicate serialized item " + name
            items[name] = node
    assert set(items) == set(names), (set(items), set(names))
    return items


def _bounds(node):
    props = _props(node)
    assert props is not None, ET.tostring(node, encoding="unicode")
    matches = [child for child in props if _local(child.tag) == "Location"]
    assert len(matches) == 1, ET.tostring(node, encoding="unicode")
    box = {key: int(matches[0].attrib[key]) for key in ("top", "left", "bottom", "right")}
    return {"x": box["left"], "y": box["top"],
            "width": box["right"] - box["left"], "height": box["bottom"] - box["top"]}


def _assert_basic_read(fqn):
    data = _get(fqn)
    assert data["routeMapExists"] is True
    assert {point["name"]: point["type"] for point in data["points"]} == {
        "Begin": "Start", "Task": "Activity", "Done": "Completion"}
    assert {(edge["from"], edge["to"], edge["fromPort"], edge["toPort"])
            for edge in data["connections"]} == {("Begin", "Task", 4, 2), ("Task", "Done", 4, 2)}
    return data


@e2e_test(tool=TOOL, kind="write-metadata")
def test_get_on_a_business_process_without_a_route_map_reports_absent_and_the_platform_defaults():
    with _process("Absent") as fqn:
        before = tree_snapshot()
        data = _get(fqn)
        assert data["routeMapExists"] is False, data
        assert data["points"] == [] and data["connections"] == []
        assert data["defaults"]["sizes"]["Condition"] == [100, 40]
        assert data["defaults"]["ports"] == {"source": 4, "target": 2, "conditionTrue": 3, "conditionFalse": 1}
        poll_disk_path_gone(_file(data), ctx="absent route map has no file")
        assert_tree_unchanged(before, "get must not create the inferred flowchart proxy")


@e2e_test(tool=TOOL, kind="write-metadata")
def test_replace_writes_flowchart_scheme_on_disk_with_platform_default_sizes():
    with _process("Defaults") as fqn:
        result = _replace(fqn)
        items = _disk(result, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])
        for name in ("Begin", "Task", "Done"):
            bounds = _bounds(items[name])
            assert (bounds["width"], bounds["height"]) == (60, 60), bounds
            assert _value(items[name], "pointUUID"), name
        for name, source, target in (("BeginTask", "Begin", "Task"), ("TaskDone", "Task", "Done")):
            assert _value(items[name], "sourceItemName") == source
            assert _value(items[name], "targetItemName") == target
        _assert_basic_read(fqn)


@e2e_test(tool=TOOL, kind="write-metadata")
def test_an_explicit_size_is_written_as_given_while_an_omitted_sibling_gets_the_default():
    with _process("Sizes") as fqn:
        points = POINTS + [{"name": "Check", "type": "Condition", "width": 140, "height": 60}]
        result = _replace(fqn, points)
        items = _disk(result, ["Begin", "Task", "Done", "Check", "BeginTask", "TaskDone"])
        assert (_bounds(items["Check"])["width"], _bounds(items["Check"])["height"]) == (140, 60)
        assert (_bounds(items["Task"])["width"], _bounds(items["Task"])["height"]) == (60, 60)
        read = {point["name"]: point for point in _get(fqn)["points"]}
        assert read["Check"]["bounds"]["width"] == 140
        assert read["Task"]["bounds"]["width"] == 60


@e2e_test(tool=TOOL, kind="write-metadata")
def test_condition_branches_land_on_the_true_and_false_ports():
    with _process("Branches") as fqn:
        points = [{"name": "Check", "type": "Condition"}, {"name": "Done", "type": "Completion"}]
        edges = [{"from": "Check", "to": "Done", "branch": "true", "name": "Yes"},
                 {"from": "Check", "to": "Done", "branch": "false", "name": "No"}]
        result = _replace(fqn, points, edges)
        items = _disk(result, ["Check", "Done", "Yes", "No"])
        assert int(_value(items["Yes"], "sourcePortIndex")) == 3
        assert int(_value(items["No"], "sourcePortIndex")) == 1
        read = {edge["name"]: edge for edge in _get(fqn)["connections"]}
        assert (read["Yes"]["branch"], read["Yes"]["fromPort"]) == ("true", 3)
        assert (read["No"]["branch"], read["No"]["fromPort"]) == ("false", 1)


@e2e_test(tool=TOOL, kind="write-metadata")
def test_get_round_trips_what_replace_wrote():
    with _process("Roundtrip") as fqn:
        result = _replace(fqn)
        read = _assert_basic_read(fqn)
        items = _disk(result, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])
        for point in read["points"]:
            assert point["bounds"] == _bounds(items[point["name"]])
            assert point["id"] == int(_value(items[point["name"]], "id"))
        for edge in read["connections"]:
            assert edge["fromId"] == int(_value(items[edge["from"]], "id"))
            assert edge["toId"] == int(_value(items[edge["to"]], "id"))


@e2e_test(tool=TOOL, kind="write-metadata")
def test_the_route_map_survives_clean_project():
    with _process("Clean") as fqn:
        result = _replace(fqn)
        _disk(result, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])
        clean = call("clean_project", {"projectName": PROJECT})
        assert_ok(clean, "reimport the separately attached route map")
        wait_for_project_ready()
        _assert_basic_read(fqn)
        _disk(result, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])


@e2e_test(tool=TOOL, kind="write-metadata")
def test_replace_keeps_the_point_uuid_of_same_named_points():
    with _process("Uuid") as fqn:
        first = _replace(fqn)
        items = _disk(first, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])
        uuids = {name: _value(items[name], "pointUUID") for name in ("Begin", "Task", "Done")}
        assert all(uuids.values())
        second = _replace(fqn, [dict(point, width=80) for point in POINTS])
        updated = _disk(second, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])
        assert uuids == {name: _value(updated[name], "pointUUID") for name in uuids}
        assert all(point["bounds"]["width"] == 80 for point in _assert_basic_read(fqn)["points"])


@e2e_test(tool=TOOL, kind="write-metadata")
def test_replace_over_an_existing_map_goes_through_consent_and_remove_deletes_the_file():
    with _process("Remove") as fqn:
        first = _replace(fqn)
        _disk(first, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])
        second = _replace(fqn, [dict(point, width=80) for point in POINTS])
        assert _bounds(_disk(second, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])["Task"])["width"] == 80
        _assert_basic_read(fqn)
        removed = call(TOOL, {"projectName": PROJECT, "fqn": fqn, "action": "remove"})
        assert_ok(removed, "remove with destructive consent")
        assert removed.structured["persisted"] is True
        assert removed.structured["points"] == len(POINTS)
        assert removed.structured["connections"] == len(CONNECTIONS)
        assert "No markers were observed for the route-map FQN" not in removed.structured.get("message", "")
        poll_disk_path_gone(_file(second), ctx="global commit must delete Flowchart.scheme")
        assert _get(fqn)["routeMapExists"] is False
        error = assert_error(call(TOOL, {"projectName": PROJECT, "fqn": fqn, "action": "remove"}))
        assert_error_quality(error, names=[fqn], suggests=["replace"])


@e2e_test(tool=TOOL, kind="write-metadata")
def test_edt_validator_markers_are_reported_for_a_map_without_completion():
    with _process("Markers") as fqn:
        result = _replace(fqn, POINTS[:2], CONNECTIONS[:1])
        _disk(result, ["Begin", "Task", "BeginTask"])
        assert {point["name"] for point in _get(fqn)["points"]} == {"Begin", "Task"}
        assert result.get("markerCount", 0) > 0 or (
            result.get("markersIncomplete") is True and result.get("markersIncompleteReason")), result
        if not any(row.get("object") == result["routeMapFqn"] for row in result["markers"]):
            assert result["markersIncomplete"] is True and result["markersIncompleteReason"], result
            assert result.get("markerCount") != 0, result


@e2e_test(tool=TOOL, kind="write-metadata")
def test_the_russian_type_token_addresses_the_business_process():
    with _process("Bilingual") as fqn:
        result = _replace(fqn)
        russian = "БизнесПроцесс." + fqn.split(".", 1)[1]
        read = _assert_basic_read(russian)
        assert read["fqn"] == fqn
        _disk(result, ["Begin", "Task", "Done", "BeginTask", "TaskDone"])


@e2e_test(tool=TOOL, kind="read")
def test_an_invalid_point_name_is_refused_without_a_write():
    error = assert_error(call(TOOL, {"projectName": PROJECT, "fqn": "BusinessProcess.ZzRmInvalid",
                                    "action": "replace", "points": [{"name": "1Bad", "type": "Start"}],
                                    "connections": []}))
    assert_error_quality(error, names=["1Bad"], suggests=["identifier"])
    assert_no_diff()


@e2e_test(tool=TOOL, kind="read")
def test_route_map_negative_parameters_have_actionable_errors():
    base = {"projectName": PROJECT, "fqn": "BusinessProcess.ZzRmMissing", "action": "get"}
    replace = dict(base, action="replace", points=POINTS, connections=[])
    condition = [{"name": "Check", "type": "Condition"}, {"name": "Done", "type": "Completion"}]
    for params, names, suggests in [
        ({}, ["projectName"], ["required"]),
        ({"projectName": PROJECT, "action": "get"}, ["fqn"], ["required"]),
        ({"projectName": PROJECT, "fqn": base["fqn"]}, ["action"], ["required"]),
        (dict(base, action="bad"), ["bad"], ["get", "replace", "remove"]),
        (dict(base, action="replace"), ["points"], ["JSON array"]),
        (dict(base, action="replace", points=POINTS), ["connections"], ["JSON array"]),
        (dict(base, points=[]), ["get"], ["replace"]),
        (dict(replace, points=[{"name": "Choose", "type": "Switch"}]), ["Switch"], ["follow-up"]),
        (dict(replace, points=[{"name": "Dup", "type": "Start"}, {"name": "Dup", "type": "Completion"}]),
         ["Dup"], ["unique"]),
        (dict(replace, connections=[{"from": "Ghost", "to": "Done"}]), ["Ghost"], ["names from points"]),
        (dict(replace, points=condition, connections=[{"from": "Check", "to": "Done"}]),
         ["Check", "Done"], ["branch"]),
        (dict(replace, points=condition, connections=[{"from": "Check", "to": "Done", "branch": "yes"}]),
         ["yes"], ["true", "false"]),
        (dict(replace, points=condition,
              connections=[{"from": "Check", "to": "Done", "branch": "true", "fromPort": 1}]),
         ["Check", "Done", "fromPort", "1"], ["3", "omit"]),
        (dict(replace, points=condition,
              connections=[{"from": "Check", "to": "Done", "branch": "false", "fromPort": 3},
                           {"from": "Check", "to": "Done", "branch": "true"}]),
         ["Check", "Done", "fromPort", "3"], ["1", "omit"]),
        (dict(replace, connections=[{"from": "Begin", "to": "Done", "fromPort": 5}]),
         ["fromPort", "5"], ["1 to 4"]),
        (dict(replace, points=[{"name": "Task", "type": "Activity", "width": 0}]),
         ["width", "0"], ["positive integer"]),
        (base, ["ZzRmMissing"], ["get_metadata_objects"]),
        (dict(base, fqn="Catalog.Catalog"), ["Catalog"], ["BusinessProcess"]),
        (dict(base, projectName="ZzRmMissingProject"), ["ZzRmMissingProject"], ["list_projects"]),
    ]:
        error = assert_error(call(TOOL, params))
        assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()

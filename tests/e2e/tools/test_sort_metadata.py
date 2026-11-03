"""Order is proved independently in .mdo XML and a model reader after every mutation."""

import xml.etree.ElementTree as ET

from harness import (
    E2EAssertion, call, assert_ok, assert_error, assert_error_quality, assert_no_diff,
    poll_disk_contains_all, read_disk, split_markdown_row, restore_extension_fixture,
    metadata_collection_order, metadata_collection_state, assert_autosort_notice, autosort_descending, tree_snapshot, assert_tree_unchanged,
    e2e_test, PROJECT, TESTS_PROJECT, TESTS_PROJECT_REL,
)


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _xml_order(xml, collection, owner_name=None):
    root = ET.fromstring(xml)
    if owner_name is not None:
        root = next(node for node in root.iter()
                    if any(_local(c.tag) == "name" and c.text == owner_name for c in node))
    result = []
    for node in root:
        if _local(node.tag) == collection:
            name = next((c.text for c in node if _local(c.tag) == "name"), None)
            result.append(name if name is not None else (node.text or "").rsplit(".", 1)[-1])
    return result


def _disk_order(path, collection, expected, owner_name=None, fixture_rel=None):
    observed = None

    def matches(xml):
        nonlocal observed
        observed = _xml_order(xml, collection, owner_name)
        return observed == expected

    try:
        return poll_disk_contains_all(path, [], fixture_rel=fixture_rel, check=matches,
                                      ctx="exact %s order %r" % (collection, expected))
    except E2EAssertion as error:
        raise E2EAssertion("%s %s order: expected %r; actual parsed order %r. %s"
                           % (path, collection, expected, observed, error)) from error


def _model_order(project, owner_fqn, expected, metadata_type=None):
    if metadata_type:
        actual = metadata_collection_order(project, "Configuration." + metadata_type)
        assert actual == expected, (expected, actual)
        return
    result = call("get_metadata_details", {"projectName": project, "objectFqns": [owner_fqn], "full": True})
    assert_ok(result, "independent model order read-back")
    # Formatters iterate the actual model lists; use the shared escaped-table parser.
    found = []
    for line in result.text.splitlines():
        if line.strip().startswith("|"):
            cells = split_markdown_row(line)
            if cells and cells[0].strip() in expected and cells[0].strip() not in found:
                found.append(cells[0].strip())
    assert found == expected, (expected, found, result.text)


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_list_observes_unsorted_order_without_changing_model_or_disk():
    owner, child, collection, folder = "Catalog.E861Observe", "Attribute", "attributes", "Catalogs"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner}))
    if "autosort" in metadata_collection_state(PROJECT, owner + ".Attributes"):
        owner, child, collection, folder = "Enum.E861Observe", "EnumValue", "enumValues", "Enums"
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner}))
    for name in ("Zulu", "Alpha"):
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner + "." + child + "." + name}))
    path = "src/%s/E861Observe/E861Observe.mdo" % folder
    _disk_order(path, collection, ["Zulu", "Alpha"])
    before = tree_snapshot()
    assert metadata_collection_order(PROJECT, owner + "." + collection) == ["Zulu", "Alpha"]
    _model_order(PROJECT, owner, ["Zulu", "Alpha"])
    assert_tree_unchanged(before, "mode list must not reorder or export")


def _sort(project, fqn, expected, original, **options):
    result = call("sort_metadata", dict(projectName=project, fqn=fqn, **options))
    assert_ok(result, "sort %s" % fqn)
    body = result.structured
    assert body["persisted"] is True, body
    assert len(body["collections"]) == 1, body
    row = body["collections"][0]
    assert row["order"] == expected, body
    moves = [{"name": name, "from": original.index(name) + 1, "to": i + 1}
             for i, name in enumerate(expected) if original.index(name) != i]
    assert row["moved"] == moves, body
    assert row["alreadySorted"] is (not moves), body
    assert body["alreadySorted"] is (not moves), body
    if moves:
        assert row["warning"] and body["sensitiveCollections"], body
    assert "autosort" not in row, row
    return body


def _all_modes(fqn, path, collection, current):
    expected = sorted(current, key=str.casefold)
    _sort(PROJECT, fqn, expected, current)
    _disk_order(path, collection, expected)
    assert metadata_collection_order(PROJECT, fqn) == expected
    current = expected
    operations = [
        ({"mode": "explicit", "items": ["E861Zulu", "E861Alpha"]},
         ["E861Zulu", "E861Alpha"] + [n for n in current if n not in ("E861Zulu", "E861Alpha")]),
    ]
    for options, expected in operations:
        _sort(PROJECT, fqn, expected, current, **options)
        _disk_order(path, collection, expected)
        assert metadata_collection_order(PROJECT, fqn) == expected
        current = expected
    for options in [
        {"objectName": "E861Beta", "direction": "up", "positions": 2},
        {"objectName": "E861Zulu", "direction": "down", "positions": 1000},
        {"objectName": "E861Alpha", "before": "E861Beta"},
        {"objectName": "E861Alpha", "before": "E861Beta"},  # Already immediately before.
        {"objectName": "E861Alpha", "after": "E861Beta"},  # Swap adjacent siblings.
        {"objectName": "E861Alpha", "after": "E861Beta"},   # Already immediately after.
        {"objectName": "E861Beta", "after": "E861Zulu"},
        {"objectName": "E861Beta", "before": "E861Alpha"},  # Last to first.
        {"objectName": "E861Beta", "after": "E861Zulu"},   # First to last.
    ]:
        name = options["objectName"]
        expected = current.copy()
        old_index = expected.index(name)
        expected.remove(name)
        if "direction" in options:
            shift = options["positions"] * (-1 if options["direction"] == "up" else 1)
            index = max(0, min(len(expected), old_index + shift))
        else:
            sibling = options.get("before", options.get("after"))
            index = expected.index(sibling) + (1 if "after" in options else 0)
        expected.insert(index, name)
        _sort(PROJECT, fqn, expected, current, mode="move", **options)
        _disk_order(path, collection, expected)
        assert metadata_collection_order(PROJECT, fqn) == expected
        current = expected
    _sort(PROJECT, fqn, current, current, mode="explicit", items=current)
    _disk_order(path, collection, current)
    assert metadata_collection_order(PROJECT, fqn) == current


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_configuration_autosort_policy_or_all_modes_persist_exact_disk_order():
    path = "src/Configuration/Configuration.mdo"
    fqn = "Configuration.Catalogs"
    state = metadata_collection_state(PROJECT, fqn)
    seeded = ["E861Zulu", "E861Alpha", "E861Beta"]
    for name in seeded:
        created = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + name})
        assert_ok(created, "seed catalog")
        if "autosort" in state:
            assert_autosort_notice(created.structured, "Configuration.catalogs")
        else:
            assert "autosort" not in created.structured, created.structured
    current = state["order"] + seeded
    if "autosort" not in state:
        _all_modes(fqn, path, "catalogs", current)
        return
    expected = sorted(current, key=str.casefold, reverse=autosort_descending(state))
    result = call("sort_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(result, "alphabetical follows the active autosort comparator")
    row = result.structured["collections"][0]
    assert row["order"] == expected and "autosort" in row, result.structured
    assert result.structured["autosortCollectionCount"] == 1, result.structured
    assert_autosort_notice(row, "Configuration.catalogs", message=result.structured["message"])
    _disk_order(path, "catalogs", expected)
    assert metadata_collection_order(PROJECT, fqn) == expected
    before = tree_snapshot()
    for options in ({"mode": "explicit", "items": ["E861Zulu", "E861Alpha"]},
                    {"mode": "move", "objectName": "E861Alpha", "before": "E861Beta"}):
        refused = call("sort_metadata", dict(projectName=PROJECT, fqn=fqn, **options))
        error = assert_error(refused)
        assert_autosort_notice(refused.structured, "Configuration.catalogs")
        assert_error_quality(error, names=["Configuration.catalogs", "Autosort"],
                             suggests=["ask the user", "Auto sort", "do not change"])
        _disk_order(path, "catalogs", expected)
        assert metadata_collection_order(PROJECT, fqn) == expected
        assert_tree_unchanged(before, "autosort refusal must not write")


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_member_all_modes_persist_exact_order_including_adjacent_noops():
    owner, child, collection, folder = "Catalog.E861Modes", "Attribute", "attributes", "Catalogs"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner}), "seed member owner")
    if "autosort" in metadata_collection_state(PROJECT, owner + ".Attributes"):
        # Enum values are outside the plugin's supported subordinate lists.
        owner, child, collection, folder = "Enum.E861Modes", "EnumValue", "enumValues", "Enums"
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner}), "seed ungoverned owner")
    names = ["Catalog", "E861Zulu", "E861Alpha", "E861Beta"]
    for name in names:
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner + "." + child + "." + name}))
    fqn = owner + "." + collection
    assert "autosort" not in metadata_collection_state(PROJECT, fqn)
    _all_modes(fqn, "src/%s/E861Modes/E861Modes.mdo" % folder, collection, names)


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_russian_address_and_recursive_scope_persist_owner_mdo():
    owner = "Catalog.E861Recursive"
    path = "src/Catalogs/E861Recursive/E861Recursive.mdo"
    for fqn in [owner, owner + ".Attribute.ZRoot", owner + ".Attribute.ARoot",
                owner + ".TabularSection.ZSection", owner + ".TabularSection.ASection",
                owner + ".TabularSection.ZSection.Attribute.ZNested",
                owner + ".TabularSection.ZSection.Attribute.ANested"]:
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed hierarchy")
    root_state = metadata_collection_state(PROJECT, owner + ".Attributes")
    nested_fqn = owner + ".TabularSection.ZSection.Attributes"
    nested_state = metadata_collection_state(PROJECT, nested_fqn)
    root_expected = sorted(["ARoot", "ZRoot"], reverse=autosort_descending(root_state))
    root_result = call("sort_metadata", {"projectName": PROJECT, "fqn": "Справочник.E861Recursive.Реквизиты"})
    assert_ok(root_result, "Russian collection address follows its policy")
    assert root_result.structured["collections"][0]["order"] == root_expected, root_result.structured
    _disk_order(path, "attributes", root_expected)
    if "autosort" in nested_state:
        assert_ok(call("sort_metadata", {"projectName": PROJECT, "fqn": nested_fqn}))
    nested_initial = metadata_collection_order(PROJECT, nested_fqn)
    _disk_order(path, "attributes", nested_initial, "ZSection")
    sections = metadata_collection_state(PROJECT, owner + ".TabularSections")
    section_expected = sorted(["ASection", "ZSection"], reverse=autosort_descending(sections))
    result = call("sort_metadata", {"projectName": PROJECT, "fqn": owner})
    assert_ok(result, "non-recursive object level")
    _disk_order(path, "tabularSections", section_expected)
    _disk_order(path, "attributes", nested_initial, "ZSection")
    nested_expected = sorted(["ANested", "ZNested"], reverse=autosort_descending(nested_state))
    result = call("sort_metadata", {"projectName": PROJECT, "fqn": owner, "recursive": True})
    assert_ok(result, "recursive object level")
    moved_count = 0 if nested_initial == nested_expected else 2
    assert result.structured["alreadySorted"] is (moved_count == 0), result.structured
    assert any(row["fqn"].endswith("ZSection.attributes") and row["movedCount"] == moved_count
               and ("autosort" in row) is ("autosort" in nested_state)
               for row in result.structured["subCollections"]), result.structured
    for row in result.structured["collections"] + result.structured["subCollections"]:
        if "autosort" in row:
            assert_autosort_notice(row, row["fqn"], message=result.structured["message"])
    _disk_order(path, "attributes", nested_expected, "ZSection")
    assert metadata_collection_order(PROJECT, nested_fqn) == nested_expected
    again = call("sort_metadata", {"projectName": PROJECT, "fqn": owner + ".TabularSections", "recursive": True})
    assert_ok(again, "recursive collection level")
    assert again.structured["alreadySorted"] is True, again.structured
    _disk_order(path, "attributes", nested_expected, "ZSection")


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_dimensions_and_enum_order_emit_specific_warnings():
    for kind, child_kind, collection, warning in [
        ("InformationRegister", "Dimension", "dimensions", "indexes"),
        ("Enum", "EnumValue", "enumValues", "ORDER BY"),
    ]:
        owner = kind + ".E861Sensitive"
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": owner}), "seed sensitive owner")
        for name in ("Zulu", "Alpha"):
            assert_ok(call("create_metadata", {"projectName": PROJECT,
                                               "fqn": owner + "." + child_kind + "." + name}), "seed member")
        result = call("sort_metadata", {"projectName": PROJECT, "fqn": owner, "recursive": True})
        assert_ok(result, "sensitive recursive sort is allowed")
        warnings = result.structured["sensitiveCollections"]
        assert any(row["fqn"].endswith("." + collection) and warning in row["warning"] for row in warnings), warnings
        folder = "InformationRegisters" if kind == "InformationRegister" else "Enums"
        _disk_order("src/%s/E861Sensitive/E861Sensitive.mdo" % folder, collection, ["Alpha", "Zulu"])
        _model_order(PROJECT, owner, ["Alpha", "Zulu"])


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_extension_reorders_adopted_and_own_catalogs_only_in_extension():
    path = "src/Configuration/Configuration.mdo"
    base_before = read_disk(path)
    try:
        state = metadata_collection_state(TESTS_PROJECT, "Configuration.Catalogs")
        options = {"mode": "explicit", "items": ["tests_ExtOnly", "Catalog"]}
        if "autosort" in state:
            refused = call("sort_metadata", dict(projectName=TESTS_PROJECT, fqn="Configuration.Catalogs", **options))
            error = assert_error(refused)
            assert_autosort_notice(refused.structured, "Configuration.catalogs")
            assert_error_quality(error, names=["Autosort", "Configuration.catalogs"], suggests=["ask the user", "Auto sort"])
            options = {"mode": "alphabetical"}
            expected = sorted(state["order"], key=str.casefold, reverse=autosort_descending(state))
        else:
            expected = ["tests_ExtOnly", "Catalog"]
        result = call("sort_metadata", dict(projectName=TESTS_PROJECT, fqn="Configuration.Catalogs", **options))
        assert_ok(result, "extension own and adopted entries follow the observed policy")
        assert result.structured["collections"][0]["order"] == expected, result.structured
        _disk_order(path, "catalogs", expected, fixture_rel=TESTS_PROJECT_REL)
        _model_order(TESTS_PROJECT, None, expected, "Catalogs")
        assert read_disk(path) == base_before, "extension sorting changed the base Configuration.mdo"
    finally:
        restore_extension_fixture("sort_metadata extension ordering")


@e2e_test(tool="sort_metadata", kind="write-metadata")
def test_rejected_modes_members_and_addresses_leave_disk_unchanged():
    collection_fqn = "Catalog.Catalog.Attributes"
    if "autosort" in metadata_collection_state(PROJECT, collection_fqn):
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Enum.E861Negative"}))
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Enum.E861Negative.EnumValue.Attribute"}))
        collection_fqn = "Enum.E861Negative.EnumValues"
    before = tree_snapshot()
    for options, bad, remedy in [
        ({"mode": "unknown"}, "unknown", "alphabetical"),
        ({"mode": "explicit", "items": ["Missing861"]}, "Missing861", "get_metadata_details"),
        ({"mode": "explicit", "items": ["Attribute", "attribute"]}, "attribute", "once"),
        ({"mode": "move", "objectName": "Attribute", "before": "Attribute"}, "Attribute", "another sibling"),
        ({"mode": "move", "objectName": "Attribute", "direction": "up", "positions": 0}, "move", "positive"),
        ({"mode": "move", "objectName": "Attribute", "before": "Missing861"}, "Missing861", "get_metadata_details"),
        ({"mode": "explicit", "items": [], "recursive": True}, "recursive", "alphabetical"),
        ({"mode": "list", "recursive": True}, "recursive", "alphabetical"),
        ({"mode": "list", "items": ["Catalog"]}, "items", "omit"),
        ({"fqn": "Catalog.DoesNotExist861"}, "DoesNotExist861", "get_metadata_objects"),
        ({"fqn": "Configuration.name"}, "name", "collection"),
    ]:
        args = dict(projectName=PROJECT, fqn=collection_fqn)
        args.update(options)
        error = assert_error(call("sort_metadata", args), "refuse invalid sort")
        assert_error_quality(error, names=[bad], suggests=[remedy])
        assert_tree_unchanged(before, "invalid sort must preserve disk")

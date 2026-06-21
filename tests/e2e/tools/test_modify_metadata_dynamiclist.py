"""
e2e tests for modify_metadata giving a form list attribute a custom DynamicList query.

A list / choice form shows its rows through a dynamic-list form attribute. create_metadata makes a
plain form attribute; modify_metadata with `queryText` / `customQuery` (+ optional `mainTable`) turns
it into a dynamic list with a custom query (a DynamicList value type + a DynamicListExtInfo,
autoFillAvailableFields + dynamicDataRead on so EDT derives the available fields - no manual DCS
`<fields>`). A dynamic-list COLUMN is then output with create_metadata for a Field whose dataPath is
`List.<query-field>`. The query props are structural and cannot be combined with other property
changes in one call.

reset: kind="write-metadata" -> reset_model() after each test. Each seeding test uses a UNIQUE
catalog name (a created top object is not guaranteed to be reverted, like the StyleItem e2e tests).
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

# A fixed FQN that is NEVER created - used by the pure-validation negatives, which are rejected
# before the form is even resolved (so the catalog need not exist).
RO_ATTR = "Catalog.E2EDynListRO.Form.ListForm.Attribute.List"


def _seed_catalog_and_form(suffix):
    """Catalog + an (empty) list form (NOT the attribute). Returns (base, list_form, list_attr)."""
    base = "Catalog.E2EDynList" + suffix
    list_form = base + ".Form.ListForm"
    list_attr = list_form + ".Attribute.List"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": base}), "seed catalog " + suffix)
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": list_form}), "seed list form")
    wait_for_project_ready()
    return base, list_form, list_attr


def _seed_catalog_form_attribute(suffix):
    """Catalog + list form + a bare (plain) form attribute ready to become a dynamic list."""
    base, list_form, list_attr = _seed_catalog_and_form(suffix)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": list_attr}), "seed bare attribute")
    wait_for_project_ready()
    return base, list_form, list_attr


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set a custom query (turns the plain attribute into a dynamic list), then
#         toggle the custom query off (keeps the dynamic list, no re-creation)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_custom_query_then_toggle_off():
    base, list_form, list_attr = _seed_catalog_form_attribute("Toggle")

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [
            {"name": "queryText",
             "value": "SELECT Ref, Description AS Description FROM " + base},
            {"name": "customQuery", "value": True},
        ],
    })
    assert_ok(r, "set the dynamic-list custom query")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    applied = r.structured.get("applied") or []
    # anti-cheat: the attribute was actually converted AND the query props were set.
    assert "dynamicList" in applied, "the attribute must be converted to a dynamic list: %r" % (applied,)
    assert "queryText" in applied, "queryText must be applied: %r" % (applied,)
    assert "customQuery" in applied, "customQuery must be applied: %r" % (applied,)

    # read-back: the form now shows a DynamicList attribute named List.
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [list_form]})
    assert_ok(d, "read back the list form")
    assert_contains(d.text, "List", "the dynamic-list attribute must be listed")
    assert_contains(d.text, "DynamicList", "the attribute type must be DynamicList")

    # toggle the custom query OFF: only customQuery changes (no re-creation of the dynamic list).
    off = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [{"name": "customQuery", "value": False}],
    })
    assert_ok(off, "switch the dynamic list back to its automatic query")
    off_applied = off.structured.get("applied") or []
    assert off_applied == ["customQuery"], \
        "toggling an existing dynamic list must apply ONLY customQuery: %r" % (off_applied,)


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set the main table, then output a column bound to a query field
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_main_table_and_output_column():
    base, list_form, list_attr = _seed_catalog_form_attribute("Main")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [
            {"name": "queryText", "value": "SELECT Ref, Description AS Description FROM " + base},
            {"name": "customQuery", "value": True},
            {"name": "mainTable", "value": base},
        ],
    })
    assert_ok(r, "set the custom query + main table")
    applied = r.structured.get("applied") or []
    assert "mainTable" in applied, "mainTable must be applied: %r" % (applied,)

    # output a dynamic-list column: a Field with a dotted dataPath List.<query-field>. (No parent ->
    # the form root; the point of the test is that the dotted dynamic-list dataPath is accepted.)
    col = call("create_metadata", {
        "projectName": PROJECT, "fqn": list_form + ".Field.RefColumn",
        "properties": [{"name": "dataPath", "value": "List.Ref"}],
    })
    assert_ok(col, "output a dynamic-list column")
    assert col.structured.get("action") == "created", \
        "the column field must be created: %r" % (col.structured,)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_unresolvable_main_table_is_error():
    base, list_form, list_attr = _seed_catalog_form_attribute("BadMain")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [
            {"name": "queryText", "value": "SELECT Ref FROM " + base},
            {"name": "mainTable", "value": "Catalog.NoSuchObject_E2E"},
        ],
    })
    e = assert_error(r, "unresolvable main table")
    assert_error_quality(e, names=["Catalog.NoSuchObject_E2E"], suggests=["main table"],
                         ctx="an unresolvable main table is a clean error")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — the query targets an attribute that does not exist yet
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_query_on_missing_attribute_is_error():
    base, list_form, list_attr = _seed_catalog_and_form("Missing")  # form exists, no attribute
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [{"name": "queryText", "value": "SELECT Ref FROM " + base}],
    })
    e = assert_error(r, "query on a missing attribute")
    assert_error_quality(e, names=["List"], suggests=["create_metadata"],
                         ctx="a missing attribute points the user at create_metadata")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_custom_query_flag_alone_on_plain_attribute_is_error():
    # Toggling customQuery on an attribute that is NOT yet a dynamic list (and giving no queryText)
    # would create an incomplete list, so it is rejected with a pointer to provide queryText.
    base, list_form, list_attr = _seed_catalog_form_attribute("FlagOnly")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [{"name": "customQuery", "value": True}],
    })
    e = assert_error(r, "customQuery alone on a plain attribute")
    assert_error_quality(e, suggests=["queryText"],
                         ctx="creating a dynamic list requires a queryText")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — malformed inputs are clean, actionable errors (no model access needed:
#            the inputs are rejected before the form is even opened, so no seeding)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="read")
def test_empty_query_text_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": RO_ATTR,
        "properties": [{"name": "queryText", "value": "   "}],
    })
    e = assert_error(r, "blank query text")
    assert_error_quality(e, suggests=["queryText"],
                         ctx="a blank queryText is rejected with the expected shape")


@e2e_test(tool="modify_metadata", kind="read")
def test_non_boolean_custom_query_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": RO_ATTR,
        "properties": [{"name": "customQuery", "value": "maybe"}],
    })
    e = assert_error(r, "non-boolean customQuery")
    assert_error_quality(e, suggests=["boolean"],
                         ctx="a non-boolean customQuery is rejected")


@e2e_test(tool="modify_metadata", kind="read")
def test_query_mixed_with_other_property_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": RO_ATTR,
        "properties": [
            {"name": "queryText", "value": "SELECT Ref FROM Catalog.Anything"},
            {"name": "title", "value": "List"},
        ],
    })
    e = assert_error(r, "query mixed with another property")
    assert_error_quality(e, names=["title"], suggests=["separate"],
                         ctx="mixing the query with another property change is rejected")

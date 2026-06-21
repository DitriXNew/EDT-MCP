"""
e2e tests for modify_metadata giving a form list attribute a custom DynamicList query.

A list / choice form shows its rows through a dynamic-list form attribute. create_metadata makes a
plain form attribute; modify_metadata with `queryText` / `customQuery` turns it into a dynamic list
with a custom query (a DynamicList value type + a DynamicListExtInfo, autoFillAvailableFields on so
EDT derives the available fields - no manual DCS `<fields>`). The query props are structural and
cannot be combined with other property changes in one call.

reset: kind="write-metadata" -> reset_model() after each test (the seeded catalog / form / attribute
is reverted from the git fixture).
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

CATALOG = "E2EDynList"
BASE = "Catalog." + CATALOG
LIST_FORM = BASE + ".Form.ListForm"
LIST_ATTR = LIST_FORM + ".Attribute.List"


def _seed_catalog_and_form():
    """Catalog + an (empty) list form - but NOT the attribute."""
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": BASE}), "seed catalog")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": LIST_FORM}), "seed list form")
    wait_for_project_ready()


def _seed_catalog_form_attribute():
    """Catalog + list form + a bare (plain) form attribute ready to become a dynamic list."""
    _seed_catalog_and_form()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": LIST_ATTR}), "seed bare attribute")
    wait_for_project_ready()


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set a custom query (turns the plain attribute into a dynamic list), then
#         toggle the custom query off (keeps the dynamic list, no re-creation)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_custom_query_then_toggle_off():
    _seed_catalog_form_attribute()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [
            {"name": "queryText",
             "value": "SELECT Ref, Description AS Description FROM " + BASE},
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
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [LIST_FORM]})
    assert_ok(d, "read back the list form")
    assert_contains(d.text, "List", "the dynamic-list attribute must be listed")
    assert_contains(d.text, "DynamicList", "the attribute type must be DynamicList")

    # toggle the custom query OFF: only customQuery changes (no re-creation of the dynamic list).
    off = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [{"name": "customQuery", "value": False}],
    })
    assert_ok(off, "switch the dynamic list back to its automatic query")
    off_applied = off.structured.get("applied") or []
    assert off_applied == ["customQuery"], \
        "toggling an existing dynamic list must apply ONLY customQuery: %r" % (off_applied,)


# ──────────────────────────────────────────────────────────────────────────────
# Negative — the query targets an attribute that does not exist yet
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_query_on_missing_attribute_is_error():
    _seed_catalog_and_form()  # the form exists, but no attribute named List
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [{"name": "queryText", "value": "SELECT Ref FROM " + BASE}],
    })
    e = assert_error(r, "query on a missing attribute")
    assert_error_quality(e, names=["List"], suggests=["create_metadata"],
                         ctx="a missing attribute points the user at create_metadata")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_custom_query_flag_alone_on_plain_attribute_is_error():
    # Toggling customQuery on an attribute that is NOT yet a dynamic list (and giving no queryText)
    # would create an incomplete list, so it is rejected with a pointer to provide queryText.
    _seed_catalog_form_attribute()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [{"name": "customQuery", "value": True}],
    })
    e = assert_error(r, "customQuery alone on a plain attribute")
    assert_error_quality(e, suggests=["queryText"],
                         ctx="creating a dynamic list requires a queryText")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — malformed inputs are clean, actionable errors (no model access needed:
#            the inputs are rejected before the form is even opened)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="read")
def test_empty_query_text_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [{"name": "queryText", "value": "   "}],
    })
    e = assert_error(r, "blank query text")
    assert_error_quality(e, suggests=["queryText"],
                         ctx="a blank queryText is rejected with the expected shape")


@e2e_test(tool="modify_metadata", kind="read")
def test_non_boolean_custom_query_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [{"name": "customQuery", "value": "maybe"}],
    })
    e = assert_error(r, "non-boolean customQuery")
    assert_error_quality(e, suggests=["boolean"],
                         ctx="a non-boolean customQuery is rejected")


@e2e_test(tool="modify_metadata", kind="read")
def test_query_mixed_with_other_property_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": LIST_ATTR,
        "properties": [
            {"name": "queryText", "value": "SELECT Ref FROM " + BASE},
            {"name": "title", "value": "List"},
        ],
    })
    e = assert_error(r, "query mixed with another property")
    assert_error_quality(e, names=["title"], suggests=["separate"],
                         ctx="mixing the query with another property change is rejected")

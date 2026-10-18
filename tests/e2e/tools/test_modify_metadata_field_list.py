"""
e2e tests for modify_metadata on a FIELD LIST - inputByString and dataLockFields (issue #740).

A BasicDbObject (Catalog, Document, ChartOfCharacteristicTypes, ChartOfAccounts,
ChartOfCalculationTypes, ExchangePlan, BusinessProcess, Task) holds two lists of its OWN fields:
inputByString (input by string) and dataLockFields (data lock fields). Both are FIELD_LIST
properties: the value is a JSON array of field addresses - the names EDT itself writes into the
.mdo, `Catalog.X.StandardAttribute.Description`, `Catalog.X.Attribute.Y`, `CommonAttribute.Z` -
full replace, `[]` clears, the type / kind tokens and a standard field's name may be Russian, and
the list is stored and read back in English. Only the fields EDT offers for the list are accepted
(get_metadata_details(assignable:true) shows them as the row's Allowed values), judged on the object
as it stands before the call; a field list is refused only when the same call changes whether EDT
offers a standard field it names - through codeLength / descriptionLength / numberLength for Code /
Description / Number (not an exchange plan's input by string, which offers them whatever the length),
hierarchical for Parent and IsFolder, hierarchyType for a catalog's IsFolder, owners for a catalog's
Owner, actionPeriodUse for ActionPeriodIsBasic. A call that brings the field back is told to set the
property first and the list in a separate call, one that takes it away to leave the field out or keep
the property; one that keeps the offer as it is, or a list that leaves the field out, shares the call.
An extension cannot change an ADOPTED object's lists. Where EDT's validator is narrower than its
editor, the validator wins: an exchange plan's ExchangeDate, and its Code / Description while the length
is 0, are not offered for data locking.

Every test here is red on master: there the property is missing from the assignable table, a write
is refused as "not assignable" (a text that echoes no value), and the full view prints `Field.Code`.

Disk checks parse the .mdo and compare the EXACT ordered list of <inputByString> / <dataLockFields>
texts. After a step that changes the object's derived fields (indexing, codeLength, hierarchical), the
offered list is polled through the assignable Allowed cell: EDT recomputes it asynchronously.

reset: kind="write-metadata" -> the orchestrator's reset after each test; the extension-writing
test restores the extension fixture itself (restore_extension_fixture).

Fixture (shipped): TestConfiguration Catalog.Catalog - flat (not hierarchical), codeLength 9,
descriptionLength 25, inputByString [Code, Description], no dataLockFields, attribute `Attribute`
String(10) not indexed.
TestConfiguration.tests: Catalog.Catalog ADOPTED (no inputByString), Catalog.tests_ExtOnly
extension-own (codeLength 9, descriptionLength 25, inputByString [Code, Description]).
"""

import json
import time
import xml.etree.ElementTree as ET

from harness import (
    E2ECallTimeout,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff_rel,
    assert_not_contains,
    assert_tree_unchanged,
    read_disk,
    read_fixture_file,
    restore_extension_fixture,
    split_markdown_row,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    _fail,
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
)

CATALOG = "Catalog.Catalog"
CATALOG_MDO = "src/Catalogs/Catalog/Catalog.mdo"
CODE = "Catalog.Catalog.StandardAttribute.Code"
DESCRIPTION = "Catalog.Catalog.StandardAttribute.Description"
ATTRIBUTE = "Catalog.Catalog.Attribute.Attribute"
PARENT = "Catalog.Catalog.StandardAttribute.Parent"

EXT_ONLY = "Catalog.tests_ExtOnly"
EXT_ONLY_MDO = "src/Catalogs/tests_ExtOnly/tests_ExtOnly.mdo"
EXT_CODE = "Catalog.tests_ExtOnly.StandardAttribute.Code"
EXT_DESCRIPTION = "Catalog.tests_ExtOnly.StandardAttribute.Description"

# Russian spellings: the type token of a catalog, a document and an exchange plan, the kind token of a
# standard attribute and of an attribute, and the platform's Russian names of the Description, Code,
# Number, Parent and ExchangeDate standard fields.
RU_CATALOG = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a"
RU_DOCUMENT = "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442"
RU_STANDARD_ATTRIBUTE = "\u0421\u0442\u0430\u043d\u0434\u0430\u0440\u0442\u043d\u044b\u0439\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442"
RU_ATTRIBUTE = "\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442"
RU_DESCRIPTION = "\u041d\u0430\u0438\u043c\u0435\u043d\u043e\u0432\u0430\u043d\u0438\u0435"
RU_CODE = "\u041a\u043e\u0434"
RU_NUMBER = "\u041d\u043e\u043c\u0435\u0440"
RU_PARENT = "\u0420\u043e\u0434\u0438\u0442\u0435\u043b\u044c"
RU_EXCHANGE_PLAN = "\u041f\u043b\u0430\u043d\u041e\u0431\u043c\u0435\u043d\u0430"
RU_EXCHANGE_DATE = "\u0414\u0430\u0442\u0430\u041e\u0431\u043c\u0435\u043d\u0430"

FIELD_LIST = "FIELD_LIST"


def _stored(xml_text, element):
    """The ordered texts of the top object's own <element> children (inputByString / dataLockFields)."""
    root = ET.fromstring(xml_text)
    return [(child.text or "").strip() for child in root
            if child.tag.rsplit("}", 1)[-1] == element]


def _poll_stored(read, element, expected, ctx, timeout=20):
    """Polls the .mdo until its <element> list is EXACTLY `expected` (the export may trail the reply)."""
    deadline = time.time() + timeout
    actual = None
    while True:
        actual = _stored(read(), element)
        if actual == expected:
            return
        if time.time() >= deadline:
            _fail("expected <%s> on disk to be exactly %r, got %r [%s]" % (element, expected, actual, ctx))
        time.sleep(0.5)


def _base_mdo():
    return read_disk(CATALOG_MDO)


def _ext_only_mdo():
    return read_fixture_file(TESTS_PROJECT_REL, EXT_ONLY_MDO)


def _row(fqn, prop, project=PROJECT):
    """The assignable-table row of one property as [Property, Kind, Current, Allowed]."""
    r = call("get_metadata_details", {"projectName": project, "objectFqns": [fqn], "assignable": True})
    assert_ok(r, "get_metadata_details(assignable) for %s in %s" % (fqn, project))
    for line in (r.text or "").splitlines():
        cells = split_markdown_row(line)
        if len(cells) >= 4 and cells[0] == prop:
            return cells
    _fail("the assignable table of %s (%s) has no row for %r:\n%s" % (fqn, project, prop, (r.text or "")[:1500]))


def _current(cells):
    """The Current cell of a FIELD_LIST row: the JSON array of the stored addresses."""
    if cells[1] != FIELD_LIST:
        _fail("expected Kind %s, got %r: %r" % (FIELD_LIST, cells[1], cells))
    try:
        return json.loads(cells[2])
    except ValueError:
        _fail("the Current cell of a field list must be a JSON array: %r" % (cells,))


def _allowed(cells):
    return [a.strip() for a in cells[3].split(",") if a.strip()]


def _poll_allowed(fqn, prop, predicate, ctx, project=PROJECT, timeout=90):
    """Polls the Allowed cell until predicate holds: the offered fields are derived data EDT recomputes."""
    deadline = time.time() + timeout
    while True:
        cells = _row(fqn, prop, project)
        if predicate(_allowed(cells)):
            return cells
        if time.time() >= deadline:
            _fail("the offered fields of %s.%s never satisfied the condition [%s]; last row: %r"
                  % (fqn, prop, ctx, cells))
        time.sleep(1.0)


def _set(fqn, prop, value, project=PROJECT):
    return call("modify_metadata", {"projectName": project, "fqn": fqn,
                                    "properties": [{"name": prop, "value": value}]})


def _assert_written(r, prop, ctx):
    assert_ok(r, ctx)
    s = r.structured or {}
    if s.get("action") != "modified" or prop not in (s.get("applied") or []) or s.get("persisted") is not True:
        _fail("expected action=modified, %r applied and persisted [%s]: %r" % (prop, ctx, s))


# ──────────────────────────────────────────────────────────────────────────────
# Happy - read, write in English, write in Russian, read back, send Current back
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_input_by_string_round_trips_in_both_languages():
    cells = _row(CATALOG, "inputByString")
    if _current(cells) != [CODE, DESCRIPTION]:
        _fail("the fixture stores [Code, Description]; Current must say so as addresses: %r" % (cells,))
    allowed = _allowed(cells)
    if CODE not in allowed or DESCRIPTION not in allowed or ATTRIBUTE in allowed:
        _fail("Allowed must offer Code and Description and not the non-indexed attribute: %r" % (allowed,))
    # C4: an empty list is a value, [] - the same token the wire clears with.
    if _current(_row(CATALOG, "dataLockFields")) != []:
        _fail("the fixture has no data lock fields; Current must read []")

    _assert_written(_set(CATALOG, "inputByString", [DESCRIPTION]), "inputByString",
                    "replace the list with Description only, in English")
    _poll_stored(_base_mdo, "inputByString", [DESCRIPTION], "the English write")

    ru = [RU_CATALOG + ".Catalog." + RU_STANDARD_ATTRIBUTE + "." + RU_DESCRIPTION,
          RU_CATALOG + ".Catalog." + RU_STANDARD_ATTRIBUTE + "." + RU_CODE]
    _assert_written(_set(CATALOG, "inputByString", ru), "inputByString",
                    "replace the list with Description and Code, in Russian")
    _poll_stored(_base_mdo, "inputByString", [DESCRIPTION, CODE],
                 "a Russian write is stored in English, in the given order")

    current = _current(_row(CATALOG, "inputByString"))
    if current != [DESCRIPTION, CODE]:
        _fail("Current must read back the stored addresses in order: %r" % (current,))
    _assert_written(_set(CATALOG, "inputByString", current), "inputByString",
                    "sending Current back is accepted")
    _poll_stored(_base_mdo, "inputByString", [DESCRIPTION, CODE], "sending Current back changes nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Negative - every refusal names what it refused and says what to do; nothing is written
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_refusals_are_actionable_and_atomic():
    before = tree_snapshot()

    e = assert_error(_set(CATALOG, "inputByString", [ATTRIBUTE]), "a non-indexed attribute")
    assert_error_quality(e, names=[ATTRIBUTE], suggests=["indexed", DESCRIPTION],
                         ctx="not offered: the rule and the offered list")

    nope = "Catalog.Catalog.StandardAttribute.Nope"
    e = assert_error(_set(CATALOG, "inputByString", [nope]), "an unknown field")
    assert_error_quality(e, names=[nope], suggests=[CODE], ctx="unknown field: the offered list")

    other = "Catalog.Other.StandardAttribute.Code"
    e = assert_error(_set(CATALOG, "inputByString", [other]), "another object's field")
    assert_error_quality(e, names=[other, "Catalog.Other"], suggests=[CATALOG],
                         ctx="a field list holds the object's own fields only")

    typo = "Catalg.Catalog.StandardAttribute.Code"
    e = assert_error(_set(CATALOG, "inputByString", [typo]), "an unknown type token")
    assert_error_quality(e, names=[typo, "'Catalg' is not a metadata type"], suggests=[CATALOG + ".<Kind>.<Name>"],
                         ctx="an unknown type token is reported as such, with the object's own address")

    wrong_kind = "Catalog.Catalog.Attribute.Code"
    e = assert_error(_set(CATALOG, "inputByString", [wrong_kind]), "the standard Code under the attribute kind")
    assert_error_quality(e, names=[wrong_kind], suggests=[CODE],
                         ctx="the kind segment is part of the field's identity")

    # The fixed text says "address" too ("a JSON array of field addresses"): only the quoted key of
    # the bad value proves that the refusal echoes it.
    e = assert_error(_set(CATALOG, "inputByString", {"address": CODE}), "an object, not an array")
    assert_error_quality(e, names=['{"address"'], suggests=["JSON array"], ctx="the shape, with the bad value")

    ru_code = RU_CATALOG + ".Catalog." + RU_STANDARD_ATTRIBUTE + "." + RU_CODE
    e = assert_error(_set(CATALOG, "inputByString", [CODE, ru_code]), "the same field twice")
    assert_error_quality(e, names=[CODE, ru_code], suggests=["twice"], ctx="a duplicate across spellings")

    # C1: codeLength 9 -> 0 in the same call would take away the Code the list names, and nothing marks a
    # stale input-by-string entry - refused, with the way out: leave Code out, or keep the length.
    e = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": CATALOG, "properties": [
        {"name": "codeLength", "value": 0}, {"name": "inputByString", "value": [CODE]}]}),
        "a field list naming Code batched with codeLength 0")
    assert_error_quality(e, names=["codeLength", "inputByString", CODE],
                         suggests=["would no longer offer it once this call has changed", "out of the list",
                                   "do not change 'codeLength'"],
                         ctx="the batch guard names both properties, the field and what to do instead")
    assert_not_contains(e, "separate call", "a second call would not bring the taken-away Code back")

    assert_tree_unchanged(before, "a refused field list must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# A length and a list that leaves its field out share one call: no code, input by Description
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_a_length_and_a_list_without_its_field_share_one_call():
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": CATALOG, "properties": [
        {"name": "codeLength", "value": 0}, {"name": "inputByString", "value": [DESCRIPTION]}]})
    assert_ok(r, "codeLength 0 with a list that leaves Code out, in one call")
    s = r.structured or {}
    applied = s.get("applied") or []
    if s.get("persisted") is not True or "codeLength" not in applied or "inputByString" not in applied:
        _fail("both properties must be applied and persisted by the one call: %r" % (s,))
    _poll_stored(_base_mdo, "inputByString", [DESCRIPTION], "the list holds Description only")
    length = _row(CATALOG, "codeLength")
    if length[2] != "0":
        _fail("codeLength must read back 0: %r" % (length,))


# ──────────────────────────────────────────────────────────────────────────────
# A length change that keeps the field offered shares the call with a list naming it
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_a_length_change_that_keeps_the_field_offered_shares_one_call():
    # codeLength 9 -> 10 keeps Code offered for input by string, so the list naming it is judged right on the
    # catalog as it stands: both properties are written by the one call (PR #744's second review).
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": CATALOG, "properties": [
        {"name": "codeLength", "value": 10}, {"name": "inputByString", "value": [CODE]}]})
    assert_ok(r, "codeLength 9 -> 10 with a list naming Code, in one call")
    s = r.structured or {}
    applied = s.get("applied") or []
    if s.get("persisted") is not True or "codeLength" not in applied or "inputByString" not in applied:
        _fail("both properties must be applied and persisted by the one call: %r" % (s,))
    _poll_stored(_base_mdo, "inputByString", [CODE], "the list holds Code only")
    _poll_stored(_base_mdo, "codeLength", ["10"], "the new code length on disk")
    length = _row(CATALOG, "codeLength")
    if length[2] != "10":
        _fail("codeLength must read back 10: %r" % (length,))


# ──────────────────────────────────────────────────────────────────────────────
# hierarchical decides whether Parent is lockable: bringing it back takes two calls, taking it away is refused
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_a_hierarchy_change_and_a_list_naming_parent():
    # The fixture catalog is flat: EDT keeps a Parent field on it, but does not offer it for data locking.
    if PARENT in _allowed(_row(CATALOG, "dataLockFields")):
        _fail("the fixture catalog is flat; Parent must not be offered for data locking before the call")

    # Making the catalog hierarchical in the same call as a list naming Parent: the list would be
    # judged on the flat catalog, so the batch is refused with the order to use - in English and in
    # Russian (type and kind tokens and the platform's own Russian name of Parent).
    before = tree_snapshot()
    ru_parent = RU_CATALOG + ".Catalog." + RU_STANDARD_ATTRIBUTE + "." + RU_PARENT
    for parent in (PARENT, ru_parent):
        e = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": CATALOG, "properties": [
            {"name": "hierarchical", "value": True}, {"name": "dataLockFields", "value": [parent]}]}),
            "hierarchical:true batched with a data lock list naming %s" % parent)
        assert_error_quality(e, names=["hierarchical", "dataLockFields", PARENT], suggests=["separate call"],
                             ctx="the batch guard names the property, the list, the field and the order to use")
    assert_tree_unchanged(before, "a refused batch must change nothing")

    # The recipe works: the property first, the list in a separate call.
    _assert_written(_set(CATALOG, "hierarchical", True), "hierarchical", "make the catalog hierarchical first")
    wait_for_project_ready()
    _poll_allowed(CATALOG, "dataLockFields", lambda allowed: PARENT in allowed,
                  "Parent is lockable once the catalog is hierarchical")
    _assert_written(_set(CATALOG, "dataLockFields", [PARENT]), "dataLockFields", "then lock by Parent")
    _poll_stored(_base_mdo, "dataLockFields", [PARENT], "the data lock field on disk")

    # The other direction (PR #744's review): taking the hierarchy away in the same call as a list that
    # names Parent would leave the list naming a field EDT no longer offers - refused, nothing written, and
    # the way out is to leave Parent out or keep the hierarchy (a second call would not bring Parent back).
    before = tree_snapshot()
    e = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": CATALOG, "properties": [
        {"name": "hierarchical", "value": False}, {"name": "dataLockFields", "value": [PARENT]}]}),
        "hierarchical:false batched with a data lock list naming Parent")
    assert_error_quality(e, names=["hierarchical", "dataLockFields", PARENT],
                         suggests=["would no longer offer it once this call has changed", "out of the list",
                                   "do not change 'hierarchical'"],
                         ctx="a list naming Parent may not share a call with the property that takes it away")
    assert_not_contains(e, "separate call", "a second call would not bring the taken-away Parent back")
    assert_tree_unchanged(before, "the refused batch leaves the hierarchical catalog and its list as they were")


# ──────────────────────────────────────────────────────────────────────────────
# Eligibility follows the model: indexing an attribute makes it offered
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_an_indexed_attribute_becomes_eligible():
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": ATTRIBUTE,
                                       "properties": [{"name": "indexing", "value": "Index"}]}),
              "index the String(10) attribute")
    wait_for_project_ready()
    _poll_allowed(CATALOG, "inputByString", lambda allowed: ATTRIBUTE in allowed,
                  "an indexed single-String attribute is offered for input by string")

    ru_attribute = RU_CATALOG + ".Catalog." + RU_ATTRIBUTE + ".Attribute"
    _assert_written(_set(CATALOG, "inputByString", [DESCRIPTION, ru_attribute]), "inputByString",
                    "Description and the attribute, the attribute in Russian")
    _poll_stored(_base_mdo, "inputByString", [DESCRIPTION, ATTRIBUTE], "the attribute's stored address")


# ──────────────────────────────────────────────────────────────────────────────
# dataLockFields has its own eligibility: a String with a length is lockable unindexed
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_data_lock_fields_have_their_own_eligibility():
    allowed = _allowed(_row(CATALOG, "dataLockFields"))
    if ATTRIBUTE not in allowed:
        _fail("a String(10) attribute is lockable without an index; Allowed: %r" % (allowed,))

    _assert_written(_set(CATALOG, "dataLockFields", [ATTRIBUTE]), "dataLockFields",
                    "lock by the unindexed attribute")
    _poll_stored(_base_mdo, "dataLockFields", [ATTRIBUTE], "the data lock field on disk")
    if _stored(_base_mdo(), "inputByString") != [CODE, DESCRIPTION]:
        _fail("writing dataLockFields must leave inputByString as it was")


# ──────────────────────────────────────────────────────────────────────────────
# Where EDT's validator is narrower than its editor, the validator wins: an exchange plan's data lock
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_exchange_plan_data_lock_follows_edts_validator():
    # EDT's editor offers an exchange plan's ExchangeDate (compatibility mode above 8.3.21 - the fixture is
    # 8.5.1) and its Code / Description whatever their lengths for data locking, but EDT's validator admits
    # Code / Description only while the length is not 0 and ExchangeDate never, and marks the rest (error 81).
    name = "E2EFieldListPlan"
    plan = "ExchangePlan." + name
    mdo = "src/ExchangePlans/%s/%s.mdo" % (name, name)
    code = plan + ".StandardAttribute.Code"
    description = plan + ".StandardAttribute.Description"
    exchange_date = plan + ".StandardAttribute.ExchangeDate"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": plan}), "create " + plan)
    wait_for_project_ready()
    # A new exchange plan has codeLength 9 and descriptionLength 25 (EDT's ExchangePlanInitializer).
    cells = _poll_allowed(plan, "dataLockFields", lambda allowed: code in allowed and description in allowed,
                          "a new exchange plan offers its Code and Description for data locking")
    if exchange_date in _allowed(cells):
        _fail("EDT's validator never admits ExchangeDate for data locking; Allowed must not offer it: %r" % (cells,))

    # The field is there and EDT's editor offers it - the refusal says it is the VALIDATOR that rejects it,
    # in English and in Russian (the type and kind tokens and the platform's own Russian name).
    before = tree_snapshot()
    ru_exchange_date = RU_EXCHANGE_PLAN + "." + name + "." + RU_STANDARD_ATTRIBUTE + "." + RU_EXCHANGE_DATE
    for entry in (exchange_date, ru_exchange_date):
        e = assert_error(_set(plan, "dataLockFields", [entry]), "data locking by ExchangeDate (%s)" % entry)
        assert_error_quality(e, names=[entry], suggests=["validator rejects in 'dataLockFields'", "ExchangeDate"],
                             ctx="EDT's editor offers ExchangeDate, its validator rejects it")
    assert_tree_unchanged(before, "a refused data lock list must change nothing")

    # The batch guard speaks of the validator here (PR #744, third gate): EDT's editor offers Code whatever the
    # length, so codeLength 0 in the same call as a list naming Code would leave the list naming a field the
    # VALIDATOR rejects - refused, nothing written, and the way out is to leave Code out or keep the length.
    before = tree_snapshot()
    e = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": plan, "properties": [
        {"name": "codeLength", "value": 0}, {"name": "dataLockFields", "value": [code]}]}),
        "codeLength 0 batched with a data lock list naming the plan's Code")
    assert_error_quality(e, names=["codeLength", "dataLockFields", code],
                         suggests=["validator accepts that field now", "would reject it once this call has changed",
                                   "out of the list", "do not change 'codeLength'"],
                         ctx="the take-away names the validator, the field and what to do instead")
    assert_not_contains(e, "no longer offer", "EDT's editor keeps offering an exchange plan's Code")
    assert_not_contains(e, "separate call", "a second call would not make the validator accept Code")
    assert_tree_unchanged(before, "a refused batch must change nothing")

    # Code: admitted only while codeLength is not 0.
    _assert_written(_set(plan, "codeLength", 0), "codeLength", "codeLength 0, in its own call")
    wait_for_project_ready()
    _poll_allowed(plan, "dataLockFields", lambda allowed: code not in allowed and description in allowed,
                  "Code leaves the data lock offer once codeLength is 0")
    before = tree_snapshot()
    e = assert_error(_set(plan, "dataLockFields", [code]), "data locking by Code while codeLength is 0")
    assert_error_quality(e, names=[code], suggests=["validator rejects in 'dataLockFields'", "codeLength"],
                         ctx="the rule names the length that would admit it")
    # And back: codeLength 9 in the same call as a list naming Code would have the list judged on the plan as it
    # stands, whose validator rejects Code - the separate-call recipe, again in the validator's words.
    e = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": plan, "properties": [
        {"name": "codeLength", "value": 9}, {"name": "dataLockFields", "value": [code]}]}),
        "codeLength 9 batched with a data lock list naming the plan's Code")
    assert_error_quality(e, names=["codeLength", "dataLockFields", code],
                         suggests=["whether EDT's validator accepts that field depends on", "separate call"],
                         ctx="the bring-back names the validator and the order to use")
    assert_not_contains(e, "whether EDT offers that field", "EDT's editor offers an exchange plan's Code anyway")
    assert_tree_unchanged(before, "a refused data lock list must change nothing")

    # Description still has its length: it is lockable, and written.
    _assert_written(_set(plan, "dataLockFields", [description]), "dataLockFields", "lock by Description")
    _poll_stored(lambda: read_disk(mdo), "dataLockFields", [description], "the data lock field on disk")


# ──────────────────────────────────────────────────────────────────────────────
# The reporter's case: an extension-own catalog without a code takes input by Description only
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_extension_own_catalog_without_code():
    ctx = "the field-list writes to the extension-own catalog"
    try:
        assert_ok(_set(EXT_ONLY, "codeLength", 0, project=TESTS_PROJECT), "codeLength 0 on " + EXT_ONLY)
        wait_for_project_ready()
        cells = _poll_allowed(EXT_ONLY, "inputByString",
                              lambda allowed: EXT_CODE not in allowed and EXT_DESCRIPTION in allowed,
                              "Code leaves the offered fields once codeLength is 0", project=TESTS_PROJECT)
        current = _current(cells)
        if current != [EXT_CODE, EXT_DESCRIPTION]:
            _fail("EDT keeps the stale Code entry; Current must show it: %r" % (current,))

        e = assert_error(_set(EXT_ONLY, "inputByString", [EXT_CODE], project=TESTS_PROJECT),
                         "Code without a code length")
        assert_error_quality(e, names=[EXT_CODE], suggests=["codeLength"], ctx="the rule names the length")
        # C4: Current sent back carries the entry EDT no longer offers - refused with the way out.
        e = assert_error(_set(EXT_ONLY, "inputByString", current, project=TESTS_PROJECT),
                         "Current sent back with a stale entry")
        assert_error_quality(e, names=[EXT_CODE], suggests=["current list", "drop it"],
                             ctx="a stale Current entry is named and the caller told to drop it")

        ru = RU_CATALOG + ".tests_ExtOnly." + RU_STANDARD_ATTRIBUTE + "." + RU_DESCRIPTION
        _assert_written(_set(EXT_ONLY, "inputByString", [ru], project=TESTS_PROJECT), "inputByString",
                        "input by Description only, in Russian")
        _poll_stored(_ext_only_mdo, "inputByString", [EXT_DESCRIPTION],
                     "the extension's own .mdo holds Description only")
    except E2ECallTimeout:
        # NO cleanup here: the timed-out call may still be writing these very files.
        raise
    except BaseException:
        restore_extension_fixture(ctx)
        raise
    else:
        restore_extension_fixture(ctx)


# ──────────────────────────────────────────────────────────────────────────────
# A new document starts with [Number]: [] clears it, the Russian address restores it
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_document_number_clears_and_restores():
    name = "E2EFieldListDoc"
    doc = "Document." + name
    mdo = "src/Documents/%s/%s.mdo" % (name, name)
    number = doc + ".StandardAttribute.Number"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": doc}), "create " + doc)
    wait_for_project_ready()
    if _current(_row(doc, "inputByString")) != [number]:
        _fail("a new document is created with input by Number (the platform initializer's default)")
    # The disk half of the clear below proves something only if the element was on disk first.
    _poll_stored(lambda: read_disk(mdo), "inputByString", [number], "the new document is exported with Number")

    _assert_written(_set(doc, "inputByString", []), "inputByString", "[] clears the list")
    _poll_stored(lambda: read_disk(mdo), "inputByString", [], "the cleared list leaves no element")
    if _current(_row(doc, "inputByString")) != []:
        _fail("a cleared list reads back as []")

    ru = RU_DOCUMENT + "." + name + "." + RU_STANDARD_ATTRIBUTE + "." + RU_NUMBER
    _assert_written(_set(doc, "inputByString", [ru]), "inputByString", "Number again, in Russian")
    _poll_stored(lambda: read_disk(mdo), "inputByString", [number], "the restored Number entry")


# ──────────────────────────────────────────────────────────────────────────────
# C2 - an extension cannot change an adopted object's lists
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_field_list_an_adopted_object_is_refused_in_the_extension():
    cells = _row(CATALOG, "inputByString", project=TESTS_PROJECT)
    if _current(cells) != [] or _allowed(cells):
        _fail("the adopted catalog stores no list of its own and is offered none: %r" % (cells,))

    e = assert_error(_set(CATALOG, "inputByString", [DESCRIPTION], project=TESTS_PROJECT),
                     "input by string on the adopted catalog")
    assert_error_quality(e, names=["inputByString"], suggests=["adopted", "base configuration"],
                         ctx="the refusal says why and where to change it")
    assert_no_diff_rel(TESTS_PROJECT_REL, "a refused write to the adopted catalog must not touch the extension")


# ──────────────────────────────────────────────────────────────────────────────
# The full view prints the stored address, not the field's EClass name
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="read")
def test_field_list_full_view_prints_the_stored_address():
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [CATALOG], "full": True})
    assert_ok(r, "get_metadata_details full for " + CATALOG)
    row = next((line for line in (r.text or "").splitlines()
                if (split_markdown_row(line) or [""])[0] == "Input By String"), None)
    if row is None:
        _fail("the full view must have an Input By String row:\n%s" % (r.text or "")[:1500])
    if CODE not in row or DESCRIPTION not in row:
        _fail("the full view must print the stored addresses: %r" % (row,))
    if "Field.Code" in row:
        _fail("the full view must not print the field's EClass name: %r" % (row,))

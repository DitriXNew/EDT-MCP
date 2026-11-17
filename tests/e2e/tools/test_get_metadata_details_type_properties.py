"""
e2e tests for get_metadata_details: the TYPE-VALUED properties of an object (issue #955).

WHAT IT PINS: an event subscription's `source`, and the `type` of a defined type (one of the same kind:
a contained, single TypeDescription), are shown as a `| Source |` / `| Type |` row in BOTH views - the
default one and full=true. Before #955 the reflective dump skipped every containment reference and the
collection formatters rendered only many-valued ones, so neither row existed in either view.

  - the reporter's case: the extension's OWN subscription with two sources;
  - a subscription of the base configuration with two sources;
  - a subscription the extension ADOPTED: while its source state is None the extension stores no source
    (the base one applies) and the row says so instead of printing the model's empty copy; once the
    source is Extended the row lists the base source plus the addition and names the state;
  - a defined type's type, and an adopted defined type, whose composition lives in the extension block's
    type extension (each type with its state), which is what its row shows.

DISK FIRST: the source a write leaves is read from the .mdo before the details are read, so a jar that
renders the row but whose write did not land fails on the file, not on the row. The cell is parsed and its
types compared as a SET (the order of a type description carries no meaning).

RED ON THE OLD JAR: every rendering test fails on its first `| Source |` / `| Type |` row lookup.
"""

import xml.etree.ElementTree as ET

from harness import (
    E2ECallTimeout,
    call,
    assert_ok,
    assert_contains,
    poll_disk_contains_all,
    restore_extension_fixture,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
)

_BASE_CATALOG = "Catalog"
_OWN_CATALOG = "tests_ExtOnly"
_DISK_TIMEOUT = 30
_NOT_STORED = "not stored in the extension: state None, the base configuration's value applies"
_TYPE_EXTENSION = "the extension's type extension"


def _create(project, fqn, ctx):
    assert_ok(call("create_metadata", {"projectName": project, "fqn": fqn}), "create %s [%s]" % (fqn, ctx))
    wait_for_project_ready()


def _set_types(project, fqn, prop, kinds, ctx):
    r = call("modify_metadata", {"projectName": project, "fqn": fqn,
                                 "properties": [{"name": prop, "value": {"types": list(kinds)}}]})
    assert_ok(r, "set %s of %s [%s]" % (prop, fqn, ctx))
    wait_for_project_ready()
    return r


def _row(project, fqn, label, full):
    """The value cell of the `| label |` row in the details of fqn, or raise naming what was rendered."""
    r = call("get_metadata_details", {"projectName": project, "objectFqns": [fqn], "full": full})
    assert_ok(r, "get_metadata_details(full=%s) of %s" % (full, fqn))
    text = r.text or ""
    prefix = "| " + label + " | "
    rows = [line.strip() for line in text.splitlines() if line.strip().startswith(prefix)]
    if len(rows) != 1:
        raise AssertionError("expected exactly one '%s' row in the details of %s (full=%s); got %d:\n%s"
                             % (label, fqn, full, len(rows), text[:1500]))
    return rows[0][len(prefix):-2].strip()


def _types_of(cell):
    """The type names of a cell, the note in parentheses after them dropped."""
    head = cell.split(" (", 1)[0]
    return {part.strip() for part in head.split(",") if part.strip()}


def _source_types_on_disk(text):
    root = ET.fromstring(text)
    source = [child for child in list(root) if child.tag.rsplit("}", 1)[-1] == "source"]
    if len(source) != 1:
        return None
    return {(t.text or "").strip() for t in list(source[0]) if t.tag.rsplit("}", 1)[-1] == "types"}


def _assert_in_both_views(project, fqn, label, check, ctx):
    for full in (False, True):
        cell = _row(project, fqn, label, full)
        check(cell, "%s, full=%s" % (ctx, full))


def _restoring(ctx, body):
    """Run body; restore the extension after it - except after a timed-out call, which may still be
    writing these very files (the orchestrator aborts the run on it)."""
    try:
        body()
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture(ctx)
        raise
    else:
        restore_extension_fixture(ctx)


# ──────────────────────────────────────────────────────────────────────────────
# A subscription of the base configuration
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_a_subscription_shows_its_two_sources_in_both_views():
    reg_a, reg_b, sub = "E2E955RegA", "E2E955RegB", "E2E955Sub"
    fqn = "EventSubscription." + sub
    expected = {"InformationRegisterRecordSet." + reg_a, "InformationRegisterRecordSet." + reg_b}
    _create(PROJECT, "InformationRegister." + reg_a, "source A")
    _create(PROJECT, "InformationRegister." + reg_b, "source B")
    _create(PROJECT, fqn, "the subscription")
    _set_types(PROJECT, fqn, "source", [{"kind": "InformationRegisterRecordSet", "ref": reg_a},
                                        {"kind": "InformationRegisterRecordSet", "ref": reg_b}], "two sources")

    path = "src/EventSubscriptions/%s/%s.mdo" % (sub, sub)
    text = poll_disk_contains_all(path, sorted(expected), timeout=_DISK_TIMEOUT, ctx="the sources reach the .mdo")
    if _source_types_on_disk(text) != expected:
        raise AssertionError("PREMISE: the .mdo holds both sources; got %r" % (_source_types_on_disk(text),))

    def check(cell, ctx):
        if _types_of(cell) != expected or "(" in cell:
            raise AssertionError("the Source row lists exactly the two sources [%s]; got %r" % (ctx, cell))
    _assert_in_both_views(PROJECT, fqn, "Source", check, "a base subscription")


@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_a_defined_type_shows_its_type_in_both_views():
    name = "E2E955Defined"
    fqn = "DefinedType." + name
    _create(PROJECT, fqn, "the defined type")
    _set_types(PROJECT, fqn, "type", [{"kind": "String", "length": 10}, {"kind": "CatalogRef", "ref": _BASE_CATALOG}],
               "String(10) and a catalog ref")
    poll_disk_contains_all("src/DefinedTypes/%s/%s.mdo" % (name, name), ["CatalogRef." + _BASE_CATALOG],
                           timeout=_DISK_TIMEOUT, ctx="the type reaches the .mdo")

    def check(cell, ctx):
        if _types_of(cell.replace("String(10, Variable)", "String")) != {"String", "CatalogRef." + _BASE_CATALOG}:
            raise AssertionError("the Type row lists String(10) and the catalog ref [%s]; got %r" % (ctx, cell))
        assert_contains(cell, "String(10, Variable)", "the String qualifiers read as in an attribute table [%s]" % ctx)
    _assert_in_both_views(PROJECT, fqn, "Type", check, "a base defined type")


# ──────────────────────────────────────────────────────────────────────────────
# The extension's own subscription (the reporter's case)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_the_extensions_own_subscription_shows_its_sources():
    reg, sub = "E2E955ExtReg", "E2E955ExtSub"
    fqn = "EventSubscription." + sub
    expected = {"InformationRegisterRecordSet." + reg, "CatalogObject." + _OWN_CATALOG}

    def body():
        _create(TESTS_PROJECT, "InformationRegister." + reg, "the extension's register")
        _create(TESTS_PROJECT, fqn, "the extension's subscription")
        _set_types(TESTS_PROJECT, fqn, "source", [{"kind": "InformationRegisterRecordSet", "ref": reg},
                                                  {"kind": "CatalogObject", "ref": _OWN_CATALOG}], "two sources")
        text = poll_disk_contains_all("src/EventSubscriptions/%s/%s.mdo" % (sub, sub), sorted(expected),
                                      timeout=_DISK_TIMEOUT, ctx="the sources reach the extension's .mdo",
                                      fixture_rel=TESTS_PROJECT_REL)
        if _source_types_on_disk(text) != expected:
            raise AssertionError("PREMISE: the .mdo holds both sources; got %r" % (_source_types_on_disk(text),))

        def check(cell, ctx):
            if _types_of(cell) != expected or "(" in cell:
                raise AssertionError("the Source row lists exactly the two sources [%s]; got %r" % (ctx, cell))
        _assert_in_both_views(TESTS_PROJECT, fqn, "Source", check, "the extension's own subscription")

    _restoring("the extension's own subscription", body)


# ──────────────────────────────────────────────────────────────────────────────
# Objects the extension adopted
# ──────────────────────────────────────────────────────────────────────────────
def _adopt(fqn, ctx):
    r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "adopt %s [%s]" % (fqn, ctx))
    s = r.structured if isinstance(r.structured, dict) else {}
    if s.get("action") != "adopted" or s.get("extensionProject") != TESTS_PROJECT:
        raise AssertionError("expected %s adopted into %s [%s]; got %r" % (fqn, TESTS_PROJECT, ctx, s))
    wait_for_project_ready()


@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_an_adopted_subscription_says_what_the_extension_stores():
    sub = "E2E955AdoptSub"
    fqn = "EventSubscription." + sub
    base = "CatalogObject." + _BASE_CATALOG
    own = "CatalogObject." + _OWN_CATALOG

    def body():
        _create(PROJECT, fqn, "the base subscription")
        _set_types(PROJECT, fqn, "source", [{"kind": "CatalogObject", "ref": _BASE_CATALOG}], "the base source")
        _adopt(fqn, "#955")

        def follows_the_base(cell, ctx):
            assert_contains(cell, _NOT_STORED, "a fresh adoption stores no source [%s]" % ctx)
        _assert_in_both_views(TESTS_PROJECT, fqn, "Source", follows_the_base, "a fresh adoption")

        _set_types(TESTS_PROJECT, fqn, "source", [{"kind": "CatalogObject", "ref": _OWN_CATALOG}], "extend the source")
        poll_disk_contains_all("src/EventSubscriptions/%s/%s.mdo" % (sub, sub), [own, ">Extended<"],
                               timeout=_DISK_TIMEOUT, ctx="the extended source reaches the .mdo",
                               fixture_rel=TESTS_PROJECT_REL)

        def extended(cell, ctx):
            if _types_of(cell) != {base, own} or not cell.endswith("(extension state Extended)"):
                raise AssertionError("an Extended source lists the base source plus the addition and names "
                                     "the state [%s]; got %r" % (ctx, cell))
        _assert_in_both_views(TESTS_PROJECT, fqn, "Source", extended, "an extended source")

    _restoring("an adopted subscription", body)


@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_an_adopted_defined_type_shows_its_type_extension():
    name = "E2E955AdoptDefined"
    fqn = "DefinedType." + name

    def body():
        _create(PROJECT, fqn, "the base defined type")
        _set_types(PROJECT, fqn, "type", [{"kind": "CatalogRef", "ref": _BASE_CATALOG}], "the base type")
        _adopt(fqn, "#955")
        _set_types(TESTS_PROJECT, fqn, "type", [{"kind": "CatalogRef", "ref": _OWN_CATALOG},
                                                {"kind": "CatalogRef", "ref": _BASE_CATALOG}], "add a type")
        poll_disk_contains_all("src/DefinedTypes/%s/%s.mdo" % (name, name), ["CatalogRef." + _OWN_CATALOG,
                                                                              ">Extended<"],
                               timeout=_DISK_TIMEOUT, ctx="the addition reaches the type extension",
                               fixture_rel=TESTS_PROJECT_REL)

        def type_extension(cell, ctx):
            assert_contains(cell, _TYPE_EXTENSION, "the row reads the block's type extension [%s]" % ctx)
            assert_contains(cell, "CatalogRef.%s (Extended)" % _OWN_CATALOG,
                            "the addition is named with its state [%s]" % ctx)
            assert_contains(cell, "CatalogRef." + _BASE_CATALOG, "the base type is listed [%s]" % ctx)
        _assert_in_both_views(TESTS_PROJECT, fqn, "Type", type_extension, "an adopted defined type")

    _restoring("an adopted defined type", body)

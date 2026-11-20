"""#662: a ``dcs`` write reads before it writes, and returns EDT's validation of what it wrote.

* Read before write: every write into a root that already holds a schema needs ``expectedHash``;
  without it the call is refused before any model work, naming the current hash and the ``get`` to
  run. A write that creates the schema (a fresh report, a plain attribute converted to a dynamic list,
  a form without conditional appearance) needs none.
* Validate after write: after the export the call waits for EDT's validation and lists every marker
  of the schema it wrote - the same markers ``get_project_errors objectFqns`` reads for that object.

The marker assertions compare the write's own table with ``get_project_errors``: a check this EDT
build does not ship produces no marker on either side, and both must still agree.
"""

import re

from harness import (
    PROJECT,
    assert_error,
    assert_error_quality,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    read_disk,
    tree_snapshot,
)
from tools.test_dcs import (
    MAIN_DCS_TEMPLATE,
    _NO_HASH,
    _get,
    _hash,
    _seed_dynamic_list,
    _seed_report,
    _write,
)

_VALIDATION = re.compile(r"\*\*Validation:\*\* `(complete|incomplete)`")
_VALIDATED = re.compile(r"\*\*Validated objects:\*\* `([^`]*)`")


def _section(result, ctx):
    """The write's validation verdict and its validated top objects."""
    match = _VALIDATION.search(result.text)
    assert match, "a successful dcs write must report its validation [%s]:\n%s" % (ctx, result.text)
    validated = _VALIDATED.search(result.text)
    assert validated, "the validation must name the objects it covers [%s]:\n%s" % (ctx, result.text)
    return match.group(1), [part.strip() for part in validated.group(1).split(",")]


def _reported_checks(result):
    """(check id, location) of every row of the write's own marker table."""
    tail = result.text[result.text.index("**Validation:**"):]
    return sorted(set(re.findall(r"^\| [A-Z]+ \| ([^|]+?) \| .*? \| ([^|]+?) \|$", tail, re.M)))


def _owner(presentation, objects):
    """The longest top-object FQN the marker location lies in, or None."""
    owners = [o for o in objects if presentation == o or presentation.startswith(o + ".")]
    return max(owners, key=len) if owners else None


def _project_checks(fqn, objects, others=()):
    """(check id, location) of the markers get_project_errors reads for fqn that lie in the given top
    objects; a location inside one of ``others`` (another top object nested under the same prefix)
    belongs to that object instead."""
    read = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [fqn], "limit": 100})
    assert_ok(read, "read the markers of %s" % fqn)
    problems = (read.structured or {}).get("problems") or []
    kept = set()
    for p in problems:
        location = str(p.get("objectPresentation", ""))
        if _owner(location, list(objects) + list(others)) in objects:
            kept.add((p["checkId"], location))
    return sorted(kept)


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_write_into_an_existing_schema_without_a_hash_is_refused_662():
    root = _seed_report("E2EDcsReadFirst662")  # creating the schema needed no hash
    dcs_rel = "src/Reports/E2EDcsReadFirst662/Templates/%s/Template.dcs" % MAIN_DCS_TEMPLATE
    read = _get(root, "schema")
    assert_ok(read, "read the seeded schema")
    before = tree_snapshot()
    disk = read_disk(dcs_rel)

    for action, address, kind, body in (
            ("upsert", root, "parameter", {"name": "Unread662"}),
            # An unread write is refused before its query is even checked: this query is invalid.
            ("update", root + "#/dataSets/DataSet1", "dataSet", {"query": "SELECT Missing662 FROM Catalog.Missing662"}),
            ("upsert", root + "#/defaultSettings/filter", "filter", {"items": []})):
        refused = _write(address, action, kind, body, expectedHash=_NO_HASH)
        error = assert_error(refused, "%s %s without expectedHash" % (action, kind))
        assert_error_quality(error, names=["Read the schema first", root, _hash(read), action],
                             suggests=["action='get'", "type='schema'", "expectedHash"],
                             ctx="the refusal names the read to run and the current hash")
        assert "nothing was written" in error, error
    assert_tree_unchanged(before, "an unread write must not touch the project")
    assert read_disk(dcs_rel) == disk, "an unread write must leave Template.dcs byte-identical"

    written = _write(root, "upsert", "parameter", {"name": "Read662"}, expectedHash=_hash(read))
    assert_ok(written, "the same write after reading the schema")
    assert "Read662" in read_disk(dcs_rel)


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_dynamic_list_and_form_appearance_writes_need_the_read_hash_662():
    attribute = _seed_dynamic_list("ReadFirst662")  # converted through modify_metadata
    form = attribute.rsplit(".Attribute.", 1)[0]
    before = tree_snapshot()
    refused = _write(attribute + "#/listSettings/order", "upsert", "order", {"items": []},
                     expectedHash=_NO_HASH)
    error = assert_error(refused, "a dynamic-list write without expectedHash")
    listed = _get(attribute, "dynamicList")
    assert_ok(listed)
    assert_error_quality(error, names=["Read the schema first", attribute, _hash(listed)],
                         suggests=["type='dynamicList'"])
    assert_tree_unchanged(before, "an unread dynamic-list write must not touch the form")

    # A form without conditional appearance gets its first rule without a hash (it creates it) ...
    first = _write(form, "upsert", "conditionalAppearance", {"items": [{"appearance": {"Text": "First662"}}]},
                   expectedHash=_NO_HASH)
    assert_ok(first, "the first conditional-appearance rule of a form creates it")
    # ... and the next write into it must read it first.
    before = tree_snapshot()
    refused = _write(form, "upsert", "conditionalAppearance", {"items": [{"appearance": {"Text": "Second662"}}]},
                     expectedHash=_NO_HASH)
    error = assert_error(refused, "a second conditional-appearance write without expectedHash")
    assert_error_quality(error, names=["Read the schema first", form],
                         suggests=["type='conditionalAppearance'"])
    assert_tree_unchanged(before, "an unread conditional-appearance write must not touch the form")


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_write_returns_every_validation_marker_of_the_schema_it_wrote_662():
    root = _seed_report("E2EDcsValidate662")
    content = root + ".Template." + MAIN_DCS_TEMPLATE + ".Template"

    clean = _write(root, "upsert", "parameter", {"name": "Clean662"})
    assert_ok(clean, "a write that leaves the schema valid")
    verdict, objects = _section(clean, "clean write")
    assert objects == [content], "a schema write validates its DCS content only: %s" % objects
    if verdict == "complete":
        assert "**Markers:** `0`" in clean.text and "finished: no markers" in clean.text, clean.text
    else:
        assert "do not treat it as clean" in clean.text and "objectFqns=[%s]" % root in clean.text, clean.text

    # 'Default' as a variant name is a v8-code-style finding on the schema itself.
    flagged = _write(root, "upsert", "variant", {"name": "Default", "presentation": "Default"})
    assert_ok(flagged, "a write EDT validation flags is still a successful write")
    verdict, objects = _section(flagged, "flagged write")
    assert objects == [content], objects
    assert verdict == "complete", "EDT validates a small schema well within the budget:\n" + flagged.text
    reported = _reported_checks(flagged)
    expected = _project_checks(root, [content])
    assert reported == expected, \
        "the write must list exactly the markers get_project_errors reads for the schema: %s vs %s\n%s" \
        % (reported, expected, flagged.text)
    if expected:
        assert any(check == "data-composition-variant-name-default" for check, _ in reported), flagged.text
        assert "**Markers:** `%d`" % len(expected) in flagged.text, flagged.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_dcs_form_hosted_writes_validate_the_appearance_and_the_list_with_its_form_662():
    attribute = _seed_dynamic_list("Validate662")
    form = attribute.rsplit(".Attribute.", 1)[0]

    appearance = _write(form, "upsert", "conditionalAppearance", {"items": [{"appearance": {"Text": "A662"}}]})
    assert_ok(appearance, "write the form's conditional appearance")
    verdict, objects = _section(appearance, "form conditional appearance")
    assert objects == [form + ".Form.ConditionalAppearance"], \
        "form conditional appearance validates its own top object: %s" % objects
    assert verdict == "complete", appearance.text
    assert _reported_checks(appearance) == _project_checks(form, objects), appearance.text

    listed = _write(attribute + "#/listSettings/conditionalAppearance", "upsert", "conditionalAppearance",
                    {"items": [{"appearance": {"Text": "L662"}}]})
    assert_ok(listed, "write the dynamic list's conditional appearance")
    verdict, objects = _section(listed, "dynamic list")
    assert form + ".Form" in objects, "a dynamic list is validated with its form: %s" % objects
    assert any(o.endswith(".ExtInfo.ListSettings") for o in objects), objects
    assert "validated with the form that holds it" in listed.text, listed.text
    assert verdict == "complete", listed.text
    assert _reported_checks(listed) == _project_checks(form, objects, [form + ".Form.ConditionalAppearance"]), \
        listed.text

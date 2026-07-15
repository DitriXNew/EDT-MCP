"""
e2e tests for modify_metadata authoring a Report's Data Composition Schema (СКД / .dcs) via the `dcs`
payload (#241, extended with calculated fields by #267).

A 1C Report produces its output from a Data Composition Schema (схема компоновки данных) - datasets
(typically a query with its query text + fields), calculated fields and schema parameters. #241 lets
that schema be AUTHORED through modify_metadata via a sibling `dcs` payload on a Report FQN
(`Report.<Name>`), mirroring the #245 `template` payload and the Role `rights[]` / CommonAttribute
`content[]` precedent - NO new tool:

    modify_metadata(fqn="Report.X",
                    dcs={
                        dataSources:      [{name, type?}],
                        dataSets:         [{name, type:'query', query, dataSource?, autoFillFields?,
                                            fields:[{dataPath, name?, title?, role?}]}],
                        parameters:       [{name, valueType?, title?, use?}],
                        calculatedFields: [{dataPath, expression, title?}] })

Every entry is FOUND-OR-UPDATED by its key (dataSets[].name, parameters[].name,
calculatedFields[].dataPath): a repeated `dcs` call naming an entry already in the schema UPDATES it in
place (e.g. a dataset's query text, or a calculated field's expression) rather than duplicating it.

The report has no schema when freshly created; the FIRST `dcs` write FINDS-OR-CREATES the report's main
DCS template (its `.dcs` content resource) and fills it. The write goes through a BM write transaction
and force-exports the schema so its CONTENT resource (the `Template.dcs` that lives beside the report's
`.mdo`, at src/Reports/<Name>/Templates/<Tpl>/Template.dcs) drains to disk - the load-bearing proof here
(the #245 / #239 force-export-target lesson: a change committed only in-memory is silently discarded on
the next refresh). The success payload reports action="modified" like every other modify_metadata payload.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

FIXTURE TRUTH (TestConfiguration, English Names)
  - No Report ships in the fixture, so the happy path SEEDS a FRESH Report with create_metadata
    (Report.<Name>) and authors its DCS - a fresh report has NO .dcs resource, so every token landing on
    disk is a clean anti-cheat (a no-op would fail the diff). The created top object is reverted by the
    write-metadata reset (reset_fixture git-clean + reset_model clean_project).
  - Catalog.Catalog -> a real Catalog used as the WRONG-kind target for the "dcs payload on a non-Report
    FQN" negative (a rejected write must leave the tree byte-for-byte untouched, assert_no_diff).
"""

import os

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    poll_diff_contains,
    read_disk,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)

# A real object that is NOT a Report (a Catalog) - the wrong-kind target for the non-Report negative.
NON_REPORT_FQN = "Catalog.Catalog"


def _seed_report(name):
    """Create a FRESH Report top object and wait for the derived-data rebuild to settle. Returns the
    Report FQN. A fresh report carries NO Data Composition Schema resource yet - the clean canvas the
    happy path authors onto (the first `dcs` write find-or-creates the .dcs)."""
    fqn = "Report." + name
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed report " + fqn)
    wait_for_project_ready()
    return fqn


def _dcs_result(r, ctx):
    """A modify_metadata dcs write reports the shared modified envelope (action='modified')."""
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    assert r.structured.get("action") == "modified", "%s: must report modified: %r" % (ctx, r.structured)


def _find_report_dcs(report_name):
    """Locate the report's DCS content resource on disk: src/Reports/<Name>/Templates/<Tpl>/Template.dcs.
    The DCS template folder name (<Tpl>) is chosen by the tool (the report's main schema template), so WALK
    the report's Templates tree for the Template.dcs rather than hardcode it. Returns the project-relative
    path (forward slashes) or None when no .dcs exists yet."""
    templates_dir = os.path.join(PROJECT_DIR, "src", "Reports", report_name, "Templates")
    if not os.path.isdir(templates_dir):
        return None
    for root, _dirs, files in os.walk(templates_dir):
        for fn in files:
            if fn.lower() == "template.dcs":
                return os.path.relpath(os.path.join(root, fn), PROJECT_DIR).replace(os.sep, "/")
    return None


# ══════════════════════════════════════════════════════════════════════════════
# Happy — author a query dataset (query text + a field) + a schema parameter; the
# content DRAINS to the report's own .dcs content resource on disk
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_dcs_content_lands_in_dcs_on_disk():
    report = "E2EDcsReport"
    fqn = _seed_report(report)

    # Three MUTUALLY-DISTINCT markers, each carried by its OWN schema element so its on-disk presence
    # proves that element drained (not a bystander): a query-only table alias, an explicit dataset field
    # path (absent from the query), and a schema parameter name (absent from the query). A fresh report has
    # none of them, so each token appearing on disk is a clean anti-cheat.
    query_marker = "E2EDcsSource"                                        # a query-only table alias
    query = "SELECT E2EDcsSource.Ref AS Ref FROM Catalog.Catalog AS E2EDcsSource"
    field = "E2EDcsFieldRef"                                             # authored dataset field dataPath
    parameter = "E2EDcsParam"                                            # schema parameter name

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "dcs": {
            "dataSets": [{
                "name": "DataSet1",
                "type": "query",
                "query": query,
                # Author the field EXPLICITLY (autoFill off) so the <field> we assert on disk is the one we
                # supplied - not a platform-derived field that might rename/prune it.
                "autoFillFields": False,
                "fields": [{"dataPath": field}],
            }],
            # A TYPED parameter (carries a `valueType`) is the ratchet for the tool->TypeResolver wiring: the
            # parameter type is built through the shared S2 MetadataTypeBuilder inside the write. Before that
            # wiring existed the writer got a null resolver and REJECTED any parameter with a valueType, so
            # this write would fail assert_ok - guarding against a regression to the 2-arg DcsWriter.apply.
            "parameters": [{"name": parameter, "valueType": {"types": [{"kind": "Date", "fractions": "Date"}]}}],
        },
    })
    assert_ok(r, "author a query dataset + a field + a TYPED schema parameter on the fresh report")
    _dcs_result(r, "dcs content author")

    # ON-DISK: wait for the force-export to flush the content resource, then pin every authored element on
    # disk. poll_diff_contains scans the whole diff + every untracked file under the project (the new
    # Report folder), so it finds the tokens wherever the .dcs lands - the #239 proof that the schema
    # drained to its own resource, not just committed in-memory.
    poll_diff_contains(query_marker, ctx="the dataset query text must flush to the report's .dcs on disk")
    poll_diff_contains(field, ctx="the authored dataset field must flush to the report's .dcs on disk")
    poll_diff_contains(parameter, ctx="the schema parameter must flush to the report's .dcs on disk")

    # Pin the drain to the report's OWN .dcs content resource (src/Reports/<Name>/Templates/<Tpl>/
    # Template.dcs) - not merely "somewhere in the diff" - and byte-verify all three markers in that file.
    dcs_rel = _find_report_dcs(report)
    assert dcs_rel is not None, \
        "the first dcs write must find-or-create the report's .dcs content resource under " \
        "src/Reports/%s/Templates/<Tpl>/Template.dcs" % report
    doc = read_disk(dcs_rel)
    assert query_marker in doc, \
        "the query text must serialize into the report's .dcs (%s): %r" % (dcs_rel, doc[:600])
    assert field in doc, \
        "the authored dataset field must serialize into the report's .dcs (%s): %r" % (dcs_rel, doc[:600])
    assert parameter in doc, \
        "the schema parameter must serialize into the report's .dcs (%s): %r" % (dcs_rel, doc[:600])
    # The auto-created default data source must carry the platform-canonical local-infobase token "Local"
    # (capital L) - the exact token EDT's own DCS designer writes - so the query dataset binds to the current
    # infobase and the report matches a designer-created one. A lower-cased 'local' would pass every token
    # check above yet silently break data composition at runtime, so pin the canonical serialized form here.
    assert "<dataSourceType>Local</dataSourceType>" in doc, \
        "the auto-created data source must serialize the canonical <dataSourceType>Local</dataSourceType> " \
        "into the report's .dcs (%s): %r" % (dcs_rel, doc[:600])


# ══════════════════════════════════════════════════════════════════════════════
# Happy — a calculated field lands with its expression, and a re-applied entry for the SAME dataPath
# UPDATES that expression in place instead of adding a duplicate (#267)
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_dcs_calculated_field_lands_and_updates_in_place():
    report = "E2EDcsCalcReport"
    fqn = _seed_report(report)

    # A unique dataPath marker plus two mutually-distinct expression markers: the FIRST expression must
    # land on disk, then the SECOND apply (same dataPath, changed expression) must REPLACE it - proving
    # find-or-UPDATE rather than find-or-CREATE-a-duplicate.
    data_path = "E2EDcsCalcMargin"
    # NB: neither expression may be a substring of the other, or the "old expression is GONE"
    # assert below could never (or vacuously) pass.
    expr1 = "E2EDcsCalcRevenue - E2EDcsCalcCost"
    expr2 = "E2EDcsCalcRevenue * 2 - E2EDcsCalcCost"

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "dcs": {
            "calculatedFields": [{"dataPath": data_path, "expression": expr1, "title": "Margin"}],
        },
    })
    assert_ok(r, "author a calculated field on the fresh report")
    _dcs_result(r, "dcs calculated field author")

    poll_diff_contains(expr1,
        ctx="the calculated field's expression must flush to the report's .dcs on disk")

    dcs_rel = _find_report_dcs(report)
    assert dcs_rel is not None, \
        "the first dcs write must find-or-create the report's .dcs content resource under " \
        "src/Reports/%s/Templates/<Tpl>/Template.dcs" % report
    doc = read_disk(dcs_rel)
    assert data_path in doc, \
        "the calculated field's dataPath must serialize into the report's .dcs (%s): %r" % (dcs_rel, doc[:600])
    assert expr1 in doc, \
        "the calculated field's expression must serialize into the report's .dcs (%s): %r" % (dcs_rel, doc[:600])

    # Re-apply with a CHANGED expression on the SAME dataPath: the existing calculated field must be
    # UPDATED in place, not duplicated - the same find-or-update discipline as a re-applied dataSet /
    # parameter (the #267 reporter's exact confusion, now guarded for calculatedFields too).
    r2 = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "dcs": {
            "calculatedFields": [{"dataPath": data_path, "expression": expr2}],
        },
    })
    assert_ok(r2, "re-author the calculated field with a changed expression")
    _dcs_result(r2, "dcs calculated field update")

    poll_diff_contains(expr2, ctx="the UPDATED expression must flush to the report's .dcs on disk")
    doc2 = read_disk(dcs_rel)
    assert expr2 in doc2, \
        "the updated expression must serialize into the report's .dcs (%s): %r" % (dcs_rel, doc2[:600])
    assert expr1 not in doc2, \
        "the OLD expression must be GONE after the update, not merely superseded (%s): %r" % (dcs_rel, doc2[:600])
    assert doc2.count(data_path) == 1, \
        "the calculated field must be UPDATED in place (one dataPath occurrence), not duplicated " \
        "(%s): %r" % (dcs_rel, doc2[:600])


# ══════════════════════════════════════════════════════════════════════════════
# Negative — a dcs payload on a NON-Report FQN is rejected (wrong kind)
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_dcs_payload_on_non_report_fqn_is_error():
    # A `dcs` payload is only valid for a Report FQN. Addressed to a Catalog (a real object of the WRONG
    # kind), it is refused UP FRONT naming the FQN - it must NOT fall through to the generic property path
    # (which, with no properties, would report a false success and drop the payload).
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": NON_REPORT_FQN,
        "dcs": {"dataSets": [{"name": "DataSet1", "type": "query",
                              "query": "SELECT Ref FROM Catalog.Catalog"}]},
    })
    e = assert_error(r, "a dcs payload on a non-Report FQN")
    assert_error_quality(e, names=[NON_REPORT_FQN], suggests=["dcs", "Report"],
                         ctx="a dcs payload on a non-Report object names the FQN + a Report hint")
    assert_no_diff("a rejected non-Report dcs write must change nothing on disk")

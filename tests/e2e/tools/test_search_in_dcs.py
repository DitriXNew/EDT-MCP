"""
e2e tests for search_in_dcs (kind: read; the seeded scenarios are write-metadata).

Read tool: literal/regex full-text search over every *.dcs file under the project's src/ folder
(the Template.dcs of report / object-owned templates and of common templates). It shares the
scanner and the renderer with search_in_code, so the three output modes have the same shape:
  - "full"  (default): "## Search Results for \"<q>\"" + "**Total:** N matches in M files"
            + per-file "### <path>" sections, a "**Template:** `<FQN>`" line for a recognised
            template layout, and "**Line K:**" + ```xml``` context blocks.
  - "count":           "## Search Count for \"<q>\"" + "**Total matches:** N in **M** files".
  - "files":           "## Search Files for \"<q>\"" + a "| File | Template | Matches |" table.
Paths are shown WITHOUT the leading src/, and that same string is what fileMask matches.
A complete scan without hits renders "No matches found."; an incomplete one never does.

TestConfiguration ships NO DCS at all, so every positive scenario seeds its own reports and common
template through the supported authoring path (create_metadata + dcs upsert, as test_dcs.py does)
and then waits for the Template.dcs FILE to carry the marker: a BM write only schedules the export,
so the file appears after the call returns. The orchestrator resets the fixture after each
write-metadata test.
"""

import os
import re
import time

from harness import (
    PROJECT,
    PROJECT_DIR,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_not_contains,
    assert_ok,
    call,
    diff,
    e2e_test,
    split_markdown_row,
    wait_for_project_ready,
)


MARKER = "E2EDCS541_MARKER_"
BSL_MARKER = "E2EBSL541_ONLY_IN_BSL"
BSL_MODULE = "CommonModules/OK/Module.bsl"


def _seed_report(name, marker, query=None):
    """A report whose main DCS query carries ``marker``; returns the Template.dcs display path."""
    fqn = "Report." + name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed report " + fqn)
    wait_for_project_ready()
    assert_ok(call("dcs", {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": "upsert",
        "type": "schema",
        "body": {"dataSets": [{
            "name": "DataSet1",
            "type": "query",
            "query": query if query is not None else 'SELECT "%s" AS Amount' % marker,
            "autoFillFields": False,
            "fields": [{"dataPath": "Amount"}],
        }]},
    }), "author the DCS of " + fqn)
    wait_for_project_ready()
    return _poll_dcs_with(os.path.join("src", "Reports", name), marker)


def _seed_common_template(name, marker):
    """A common DCS template whose query carries ``marker``; returns the Template.dcs display path."""
    fqn = "CommonTemplate." + name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed " + fqn)
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": fqn,
        "properties": [{"name": "templateType", "value": "DataCompositionSchema"}],
    }), "declare " + fqn + " a DCS")
    wait_for_project_ready()
    assert_ok(call("dcs", {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": "upsert",
        "type": "schema",
        "body": {"dataSets": [{
            "name": "DataSet1",
            "type": "query",
            "query": 'SELECT "%s" AS Amount' % marker,
            "autoFillFields": False,
            "fields": [{"dataPath": "Amount"}],
        }]},
    }), "author the DCS of " + fqn)
    wait_for_project_ready()
    return _poll_dcs_with(os.path.join("src", "CommonTemplates", name), marker)


def _poll_dcs_with(owner_rel, marker, timeout=30):
    """The display path (without src/) of the Template.dcs under ``owner_rel`` once it carries
    ``marker`` on disk. Polls the FILE, not the git diff: the seeding already put the owner's name
    in the diff, so a diff poll would release before the export wrote the schema."""
    owner = os.path.join(PROJECT_DIR, owner_rel)
    deadline = time.time() + timeout
    while True:
        if os.path.isdir(owner):
            for root, _dirs, files in os.walk(owner):
                if "Template.dcs" in files:
                    path = os.path.join(root, "Template.dcs")
                    try:
                        with open(path, encoding="utf-8-sig") as handle:
                            text = handle.read()
                    except OSError:
                        text = ""
                    if marker in text:
                        rel = os.path.relpath(path, os.path.join(PROJECT_DIR, "src"))
                        return rel.replace(os.sep, "/")
        if time.time() >= deadline:
            raise AssertionError("no Template.dcs under %s carried %s within %gs - the dcs write "
                                 "reported success, so the export did not reach the disk"
                                 % (owner_rel, marker, timeout))
        time.sleep(0.5)


def _search(query, **extra):
    args = {"projectName": PROJECT, "query": query}
    args.update(extra)
    return call("search_in_dcs", args)


def _files_rows(text):
    """{path: (template, count)} from the files-mode table."""
    rows = {}
    for line in text.splitlines():
        if not line.startswith("|") or line.startswith("|---") or line.startswith("| File"):
            continue
        cells = split_markdown_row(line)
        if len(cells) == 3 and cells[2].strip().isdigit():
            assert cells[0] not in rows, "files mode must list each file once: %s" % cells[0]
            rows[cells[0].strip()] = (cells[1].strip(), int(cells[2]))
    return rows


def _count_totals(text):
    m = re.search(r"\*\*Total matches:\*\* (\d+) in \*\*(\d+)\*\* files", text)
    assert m, "count/files header missing:\n%s" % text
    return int(m.group(1)), int(m.group(2))


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (seeded)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_dcs", kind="write-metadata")
def test_query_fragments_match_after_xml_entity_decoding():
    """Search the query's parameter and comparisons after EDT escapes them on export."""
    marker = MARKER + "ENTITIES"
    query = 'SELECT "%s" AS Amount WHERE &%s > 0 AND &%s < 10' % (marker, marker, marker)
    path = _seed_report("E2EDcsSearchEntities", marker, query=query)
    with open(os.path.join(PROJECT_DIR, "src", *path.split("/")), encoding="utf-8-sig") as handle:
        exported = handle.read()
    assert_contains(exported, "&amp;" + marker, "the exported query actually contains an XML entity")
    before = diff()
    model = call("dcs", {
        "projectName": PROJECT, "fqn": "Report.E2EDcsSearchEntities#/dataSets/DataSet1/query",
        "action": "get", "type": "dataSet",
    })
    assert_ok(model, "model read-back of the seeded query")
    assert_contains(model.text, query, "the DCS model carries the exact query written to disk")

    fragment = "&" + marker + " > 0"
    full = _search(fragment, fileMask=path)
    assert_ok(full, "literal search for a decoded query fragment")
    assert_contains(full.text, "**Total:** 1 matches in 1 files", "the query source line matches once")
    assert_contains(full.text, "&amp;" + marker, "context keeps the source XML escaping")
    assert_not_contains(full.text, "No matches found.", "escaped query text must not produce false absence")
    for mode in ("count", "files"):
        result = _search(fragment, outputMode=mode, fileMask=path, limit=1)
        assert_ok(result, "decoded search in " + mode + " mode")
        assert _count_totals(result.text) == (1, 1), result.text
    regex = _search(re.escape("&" + marker) + r"\s+>\s+0", isRegex=True,
                    outputMode="count", fileMask=path)
    assert_ok(regex, "regex search for a decoded query fragment")
    assert _count_totals(regex.text) == (1, 1), regex.text
    less_than = _search("&" + marker + " < 10", outputMode="count", fileMask=path)
    assert_ok(less_than, "less-than comparison search")
    assert _count_totals(less_than.text) == (1, 1), less_than.text
    assert diff() == before, "reading the seeded schema must not change its files"


@e2e_test(tool="search_in_dcs", kind="write-metadata")
def test_full_mode_reports_the_display_path_and_a_template_fqn_dcs_can_open():
    """The marker lives only in the seeded report's query. full mode must show the file by its
    path WITHOUT src/, name the template FQN under it, and that FQN must open in dcs."""
    marker = MARKER + "1"
    path = _seed_report("E2EDcsSearch1", marker)
    assert path.startswith("Reports/E2EDcsSearch1/Templates/") and path.endswith("/Template.dcs"), path
    template = path.split("/")[3]
    fqn = "Report.E2EDcsSearch1.Template." + template

    r = _search(marker)
    assert_ok(r, "full-mode search for the seeded marker")
    assert_contains(r.text, 'Search Results for "%s"' % marker, "the heading echoes the query")
    assert_contains(r.text, "**Total:** 1 matches in 1 files", "exactly the seeded query line")
    assert_contains(r.text, "### " + path + "\n\n**Template:** `" + fqn + "`",
                    "the file is shown without src/ and with its template FQN")
    assert_not_contains(r.text, "src/Reports", "paths never carry the src/ prefix")
    assert_contains(r.text, "```xml", "DCS context is fenced as xml")
    assert_contains(r.text, marker, "the matched line is in the context block")
    assert_not_contains(r.text, "No matches found.", "a real hit must not render the sentinel")

    opened = call("dcs", {"projectName": PROJECT, "fqn": fqn, "action": "get", "type": "schema"})
    assert_ok(opened, "the reported template FQN must be a dcs address")
    assert_contains(opened.text, fqn + "#/dataSets", "dcs read the very template the search named")


@e2e_test(tool="search_in_dcs", kind="write-metadata")
def test_modes_mask_limit_regex_and_case_over_several_templates():
    """Two reports and one common template carry MARKER_1/2/3. The modes must agree with each
    other, fileMask must scope by the path without src/, limit must cap details but never counts."""
    p1 = _seed_report("E2EDcsSearch1", MARKER + "1")
    p2 = _seed_report("E2EDcsSearch2", MARKER + "2")
    p3 = _seed_common_template("E2EDcsSearchCommon", MARKER + "3")
    assert p3 == "CommonTemplates/E2EDcsSearchCommon/Template.dcs", p3

    files = _search(MARKER, outputMode="files")
    assert_ok(files, "files mode")
    assert_contains(files.text, "| File | Template | Matches |", "files mode carries the template column")
    rows = _files_rows(files.text)
    assert set(rows) == {p1, p2, p3}, "exactly the three seeded schemas: %s" % rows
    assert rows[p3][0] == "CommonTemplate.E2EDcsSearchCommon", rows[p3]
    assert rows[p1][0].startswith("Report.E2EDcsSearch1.Template."), rows[p1]
    total, matched_files = _count_totals(files.text)
    assert matched_files == 3 and total == sum(count for _t, count in rows.values()), files.text

    count = _search(MARKER, outputMode="count")
    assert_ok(count, "count mode")
    assert _count_totals(count.text) == (total, 3), "count mode agrees with files mode:\n" + count.text
    assert _count_totals(_search(MARKER, outputMode="count", limit=1).text) == (total, 3), \
        "counts never depend on limit"

    capped = _search(MARKER, limit=1)
    assert_ok(capped, "full mode capped at 1")
    assert_contains(capped.text, "(showing 1 of %d)" % total, "the truncation notice names the total")
    assert capped.text.count("**Line ") == 1, "limit=1 renders exactly one match:\n" + capped.text

    masked = _search(MARKER, outputMode="files", fileMask="Reports/E2EDcsSearch2")
    assert_ok(masked, "fileMask on the display path")
    assert set(_files_rows(masked.text)) == {p2}, "the mask keeps only the second report:\n" + masked.text
    with_src = _search(MARKER, outputMode="count", fileMask="src/Reports")
    assert _count_totals(with_src.text) == (0, 0), "the mask never sees src/:\n" + with_src.text

    regex = _search(MARKER + r"\d+", isRegex=True, outputMode="files")
    assert_ok(regex, "regex search")
    assert set(_files_rows(regex.text)) == {p1, p2, p3}, regex.text

    lower = (MARKER + "1").lower()
    assert _count_totals(_search(lower, outputMode="count").text)[0] >= 1, "case-insensitive by default"
    assert _count_totals(_search(lower, outputMode="count", caseSensitive=True).text) == (0, 0), \
        "caseSensitive=true must miss the lower-cased marker"


@e2e_test(tool="search_in_dcs", kind="write-metadata")
def test_scope_dcs_is_not_bsl_and_bsl_is_not_dcs():
    """A marker written only into a BSL module is not found by search_in_dcs, and a marker only in
    a DCS schema is not found by search_in_code - each tool proves its positive control first."""
    dcs_marker = MARKER + "SCOPE"
    _seed_report("E2EDcsSearchScope", dcs_marker)
    assert_ok(call("write_module_source", {
        "projectName": PROJECT, "modulePath": BSL_MODULE,
        "mode": "append", "source": "// %s\n" % BSL_MARKER,
    }), "write the BSL-only marker")

    code_hit = call("search_in_code", {"projectName": PROJECT, "query": BSL_MARKER, "outputMode": "count"})
    assert_ok(code_hit, "positive control: search_in_code sees the BSL marker")
    assert _count_totals(code_hit.text) == (1, 1), code_hit.text
    dcs_hit = _search(dcs_marker, outputMode="count")
    assert_ok(dcs_hit, "positive control: search_in_dcs sees the DCS marker")
    assert _count_totals(dcs_hit.text) == (1, 1), dcs_hit.text

    dcs_miss = _search(BSL_MARKER)
    assert_ok(dcs_miss, "search_in_dcs for the BSL-only marker")
    assert_contains(dcs_miss.text, "No matches found.", "BSL modules are outside search_in_dcs")
    code_miss = call("search_in_code", {"projectName": PROJECT, "query": dcs_marker})
    assert_ok(code_miss, "search_in_code for the DCS-only marker")
    assert_contains(code_miss.text, "No matches found.", "DCS schemas are outside search_in_code")


# ──────────────────────────────────────────────────────────────────────────────
# READ-ONLY PATHS (pristine fixture: it carries no *.dcs at all)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_dcs", kind="read")
def test_a_complete_scan_without_hits_says_no_matches():
    r = _search(MARKER + "ABSENT")
    assert_ok(r, "search an absent marker")
    assert_contains(r.text, "**Total:** 0 matches in 0 files", "nothing matched")
    assert_contains(r.text, "No matches found.", "a complete empty scan renders the sentinel")
    assert_not_contains(r.text, "INCOMPLETE", "a complete scan is not reported as incomplete")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_dcs", kind="read")
def test_invalid_regex_is_an_actionable_error():
    r = _search("Sum(", isRegex=True)
    err = assert_error(r, "invalid regex")
    assert_error_quality(err, names=["Sum("], suggests=["isRegex=false"], ctx="invalid regex")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_dcs", kind="read")
def test_unknown_project_is_named():
    r = call("search_in_dcs", {"projectName": "NoSuchProject541", "query": "x"})
    err = assert_error(r, "unknown project")
    assert_error_quality(err, names=["NoSuchProject541"], suggests=["list_projects"], ctx="unknown project")


@e2e_test(tool="search_in_dcs", kind="read")
def test_missing_query_and_bad_output_mode_are_rejected():
    err = assert_error(call("search_in_dcs", {"projectName": PROJECT}), "missing query")
    assert_error_quality(err, names=["query"], ctx="missing query names the parameter")
    err = assert_error(_search("x", outputMode="sideways"), "bad outputMode")
    assert_error_quality(err, names=["outputMode"], suggests=["full", "count", "files"], ctx="bad outputMode")
    assert_no_diff("an invalid call must not touch the project on disk")

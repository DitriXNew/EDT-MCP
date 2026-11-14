"""
e2e tests for get_metadata_objects paging (kind: read) - issue #932.

An ERP configuration holds 2,000+ roles, `limit` stops at 1000, and the only way past the first
page was guessing names for a filter. `offset` now walks the rest, a page without a filter reads
only the objects it shows, and the Total line names the window and the next page.

The fixture is small, so these tests pin the paging CONTRACT on the fixture's common modules: the
Total line names the next page, consecutive pages cover every object exactly once and in the same
order as one big page, and an offset past the end says so instead of printing an empty table.
The cost model (a page loads only its own objects) is pinned by GetMetadataObjectsToolPagingTest.
"""

import re

from harness import (
    call, assert_ok, assert_contains, assert_no_diff, e2e_test, PROJECT,
)


def _names(text):
    names = []
    for line in text.splitlines():
        if not line.startswith("| ") or line.startswith("| Name ") or line.startswith("| ---"):
            continue
        names.append(line.split("|")[1].strip())
    return names


def _total(text):
    m = re.search(r"\*\*Total:\*\* (\d+) objects", text)
    assert m, "the listing must carry a Total line:\n" + text[:600]
    return int(m.group(1))


@e2e_test(tool="get_metadata_objects", kind="read")
def test_pages_cover_every_object_once_in_order():
    whole = call("get_metadata_objects",
                 {"projectName": PROJECT, "metadataType": "CommonModule", "limit": 1000})
    assert_ok(whole, "one page holding every common module")
    expected = _names(whole.text)
    total = _total(whole.text)
    assert total == len(expected) and total >= 3, \
        "the fixture must hold at least 3 common modules, all on the big page: %d / %r" % (total, expected)

    first = call("get_metadata_objects",
                 {"projectName": PROJECT, "metadataType": "CommonModule", "limit": 2})
    assert_ok(first, "the first page of 2")
    assert_contains(first.text, "(showing 1-2 of %d; next page: offset=2)" % total,
                    "the Total line must name the window and the next page")

    seen = []
    offset = 0
    while offset < total:
        page = call("get_metadata_objects",
                    {"projectName": PROJECT, "metadataType": "CommonModule", "limit": 2,
                     "offset": offset})
        assert_ok(page, "page at offset=%d" % offset)
        assert _total(page.text) == total, "every page must report the same Total"
        rows = _names(page.text)
        assert 1 <= len(rows) <= 2, "a page of limit 2 holds 1-2 rows, got %r" % rows
        seen.extend(rows)
        offset += len(rows)
    assert seen == expected, \
        "consecutive pages must cover every object exactly once, in the big page's order:\n%r\n%r" % (
            seen, expected)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_an_offset_past_the_end_says_so():
    whole = call("get_metadata_objects",
                 {"projectName": PROJECT, "metadataType": "CommonModule", "limit": 1000})
    assert_ok(whole, "one page holding every common module")
    total = _total(whole.text)

    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "CommonModule", "offset": total + 5})
    assert_ok(r, "an offset past the end is not an error")
    assert _total(r.text) == total, "the Total must still count every object"
    assert_contains(r.text, "offset=%d is past the last of the %d matching objects" % (total + 5, total),
                    "an empty page must say why it is empty")
    assert _names(r.text) == [], "no rows past the end"
    assert_no_diff("a read tool must not touch the project on disk")

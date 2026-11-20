"""#662: ``dcs replace type='schema'`` takes the XML document in parts.

``body:{xmlChunk, offset, totalChars, uploadId}`` is the write-side mirror of the paged
``format='xml'`` get. Nothing reaches the model or disk before the chunk that completes the
document, and that chunk runs the unchanged whole-document replace, so its ``expectedHash`` check
still catches a schema that changed while the parts were arriving.
"""

from harness import (
    assert_error,
    assert_error_quality,
    assert_ok,
    assert_tree_unchanged,
    e2e_test,
    poll_disk_contains,
    read_disk,
    tree_snapshot,
)
from tools.test_dcs import _get, _hash, _poll_report_dcs, _read_all_xml, _seed_report, _write

_OLD_QUERY = "SELECT 1 AS Amount"
_NEW_QUERY = "SELECT 662 AS Amount"


def _chunk(root, expected_hash, upload_id, part, offset, total):
    return _write(root, "replace", "schema",
                  {"xmlChunk": part, "offset": offset, "totalChars": total, "uploadId": upload_id},
                  expectedHash=expected_hash)


def _parts(xml, count=3):
    size = -(-len(xml) // count)
    return [(offset, xml[offset:offset + size]) for offset in range(0, len(xml), size)]


def _seeded(name):
    root = _seed_report(name)
    dcs_rel = _poll_report_dcs(name, ctx="the seeded report schema")
    xml, _pages = _read_all_xml(root)
    assert _OLD_QUERY in xml, "the seeded schema must carry its query:\n%s" % xml[:2000]
    current = _get(root, "schema")
    assert_ok(current, "read the hash before the chunked replace")
    return root, dcs_rel, xml, _hash(current)


@e2e_test(tool="dcs", kind="write-metadata")
def test_chunked_replace_writes_nothing_until_the_last_chunk_then_replaces_the_schema():
    root, dcs_rel, xml, before_hash = _seeded("E2EDcsChunked662")
    changed = xml.replace(_OLD_QUERY, _NEW_QUERY)
    parts = _parts(changed)
    assert len(parts) >= 3, "the document must travel in at least three chunks"
    disk_before = read_disk(dcs_rel)
    tree_before = tree_snapshot()

    for offset, part in parts[:-1]:
        staged = _chunk(root, before_hash, "e2e-662-ok", part, offset, len(changed))
        assert_ok(staged, "stage the chunk at offset %d" % offset)
        received = offset + len(part)
        assert "chunk staged, not written" in staged.text, staged.text
        assert "**Received:** `%d` of `%d` characters" % (received, len(changed)) in staged.text, staged.text
        assert "**Next offset:** `%d`" % received in staged.text, staged.text
        assert read_disk(dcs_rel) == disk_before, "a non-final chunk must leave Template.dcs untouched"
        assert_tree_unchanged(tree_before, "a non-final chunk must not touch the project")
        still = _get(root, "schema")
        assert_ok(still, "read the schema between chunks")
        assert _hash(still) == before_hash, "a non-final chunk must not change the model"

    offset, part = parts[-1]
    committed = _chunk(root, before_hash, "e2e-662-ok", part, offset, len(changed))
    assert_ok(committed, "the last chunk commits the assembled document")
    assert "xml=wholesale" in committed.text, committed.text
    assert "**Export drained:** `true`" in committed.text, committed.text
    assert _hash(committed) != before_hash, "the replace must move the hash:\n%s" % committed.text

    poll_disk_contains(dcs_rel, _NEW_QUERY, ctx="the chunked replace must reach Template.dcs")
    assert _OLD_QUERY not in read_disk(dcs_rel), "the old query must be gone from Template.dcs"
    after_xml, _pages = _read_all_xml(root)
    assert _NEW_QUERY in after_xml and _OLD_QUERY not in after_xml, \
        "the model read back must carry the change"
    after = _get(root, "schema")
    assert_ok(after, "read the hash after the chunked replace")
    assert _hash(after) == _hash(committed), "the read-back hash must equal the commit's hash"

    resent = assert_error(_chunk(root, before_hash, "e2e-662-ok", part, offset, len(changed)),
                          "re-sending the final chunk after the commit")
    assert_error_quality(resent, names=["e2e-662-ok"], suggests=["body.offset=0", "action='get'"],
                         ctx="re-sent final chunk")


@e2e_test(tool="dcs", kind="write-metadata")
def test_chunked_replace_refuses_a_schema_that_changed_mid_upload():
    root, dcs_rel, xml, before_hash = _seeded("E2EDcsChunkedRace662")
    changed = xml.replace(_OLD_QUERY, _NEW_QUERY)
    parts = _parts(changed)

    offset, part = parts[0]
    assert_ok(_chunk(root, before_hash, "e2e-662-race", part, offset, len(changed)),
              "stage the first chunk against the current hash")

    # Another writer changes the schema while the upload is in flight.
    moved = _write(root, "upsert", "schema", {"dataSets": [{
        "name": "DataSet2", "type": "query", "query": "SELECT 2 AS Amount2",
        "autoFillFields": False, "fields": [{"dataPath": "Amount2"}]}]})
    assert_ok(moved, "change the schema between chunks")
    poll_disk_contains(dcs_rel, "DataSet2", ctx="the concurrent change reaches Template.dcs")
    disk_between = read_disk(dcs_rel)

    for offset, part in parts[1:-1]:
        assert_ok(_chunk(root, before_hash, "e2e-662-race", part, offset, len(changed)),
                  "stage the chunk at offset %d" % offset)
    offset, part = parts[-1]
    refused = assert_error(_chunk(root, before_hash, "e2e-662-race", part, offset, len(changed)),
                           "the last chunk against a schema that changed")
    assert_error_quality(refused, names=[before_hash], suggests=["action='get'", "expectedHash"],
                         ctx="stale hash at commit")
    assert "does not match current hash" in refused, refused
    assert read_disk(dcs_rel) == disk_between, "the refused commit must leave Template.dcs untouched"
    assert _NEW_QUERY not in read_disk(dcs_rel)

    # The refused upload is gone; a stale hash on a NEW upload is caught on its first chunk.
    gone = assert_error(_chunk(root, before_hash, "e2e-662-race", part, offset, len(changed)),
                        "continuing an upload whose commit was refused")
    assert_error_quality(gone, names=["e2e-662-race"], suggests=["body.offset=0"], ctx="refused upload")
    offset, part = parts[0]
    early = assert_error(_chunk(root, before_hash, "e2e-662-early", part, offset, len(changed)),
                         "a first chunk with a stale hash")
    assert "does not match current hash" in early, early
    assert_error_quality(early, names=[before_hash], suggests=["action='get'"], ctx="stale first chunk")


@e2e_test(tool="dcs", kind="write-metadata")
def test_chunked_replace_refuses_a_gap_and_a_mixed_body_without_writing():
    root, dcs_rel, xml, before_hash = _seeded("E2EDcsChunkedGap662")
    parts = _parts(xml)
    disk_before = read_disk(dcs_rel)

    offset, part = parts[0]
    assert_ok(_chunk(root, before_hash, "e2e-662-gap", part, offset, len(xml)), "stage the first chunk")
    gap_offset, gap_part = parts[2]
    gap = assert_error(_chunk(root, before_hash, "e2e-662-gap", gap_part, gap_offset, len(xml)),
                       "a chunk that skips the middle")
    assert_error_quality(gap, names=["body.offset=%d" % gap_offset, "e2e-662-gap"],
                         suggests=["body.offset=%d" % len(part), "restart it at body.offset=0"],
                         ctx="gap in a chunked upload")

    mixed = assert_error(_write(root, "replace", "schema",
                                {"xml": xml, "xmlChunk": part, "offset": 0, "totalChars": len(xml),
                                 "uploadId": "e2e-662-mixed"}, expectedHash=before_hash),
                         "xml and xmlChunk together")
    assert_error_quality(mixed, names=["body.xmlChunk", "body.xml"], suggests=["xmlChunk"],
                         ctx="xml with xmlChunk")
    assert read_disk(dcs_rel) == disk_before, "refused chunks must leave Template.dcs untouched"

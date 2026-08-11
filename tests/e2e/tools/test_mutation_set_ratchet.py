"""
Ratchet: every Java tool that WRITES the model must be listed in
harness.MODEL_MUTATION_TOOLS (kind: read, pseudo-tool: not an MCP tool, like
_markdown_table_parser).

WHY THIS FILE EXISTS
--------------------
MODEL_MUTATION_TOOLS is what the reset shortcut trusts. A successful call to any tool in
it forfeits the shortcut outright, and an UNRESOLVED call to one of them (a request that
died in flight) aborts the run instead of racing a cleanup against a write the server may
still be executing. Both guarantees are only as good as the set's membership.

And the membership was hand-maintained, on the far side of a language boundary from the
tools it names. It rotted exactly as you would expect: apply_quick_fix landed on master
extending AbstractMetadataWriteTool and executing a real fix, while this set - documented
as the tools that change the BM model - had never heard of it. Nothing failed. The
shortcut simply started skipping resets after a write, and an unresolved quick fix would
have been cleaned up on top of rather than aborted on.

So the set stops being a list somebody remembers to update. The Java side already says which
tools write - by extending AbstractMetadataWriteTool, or by calling BmTransactions.write /
forceExportToDisk - and this test reads that and requires the Python side to agree. All three
signals are needed: the base class is a statement of INTENT that a writer can simply not make
(build_external_objects implements IMcpTool directly and writes anyway), so a ratchet reading
only the base class certified a set that was already missing a tool.

The check is deliberately ONE-WAY: every write tool must be IN the set, but the set may
hold more (rename_metadata_object, update_database, clean_project and the project tools
mutate without sharing that base class). Over-inclusion costs a reset that was not needed;
omission costs correctness, and only omission is a ratchet failure.

Pure source inspection - no live server, so it cannot flake and it fails at the same
moment the offending tool is written.
"""

import os
import re

from harness import MODEL_MUTATION_TOOLS, REPO_ROOT, e2e_test, _fail

_TOOLS_IMPL_DIR = os.path.join(
    REPO_ROOT, "mcp", "bundles", "com.ditrix.edt.mcp.server", "src", "com", "ditrix", "edt",
    "mcp", "server", "tools", "impl")

# How a tool declares, in Java, that it writes. THREE signals, because one was not enough: the
# base class is a statement of INTENT, and a tool can write without making it -
# BuildExternalObjectsTool `implements IMcpTool` directly and still calls BmTransactions.write +
# forceExportToDisk, so a base-class-only ratchet certified a set that was missing it. Reading the
# mutation CALLS as well catches a writer however it is declared.
#
# forceExportToDisk counts even without a write transaction: it puts bytes on disk, and the thing
# this set protects against is a cleanup racing work the server is still doing - which a disk
# export is just as much as a model change.
_WRITE_MARKERS = (
    "extends AbstractMetadataWriteTool",
    "BmTransactions.write(",
    "forceExportToDisk(",
)

# Each tool publishes its wire name as a NAME constant; that is the string the harness sees.
_NAME_RE = re.compile(r'String\s+NAME\s*=\s*"([a-z_0-9]+)"')


def _write_tools():
    """(tool name, java file) for every tool declaring itself a metadata writer."""
    found = []
    for entry in sorted(os.listdir(_TOOLS_IMPL_DIR)):
        if not entry.endswith("Tool.java"):
            continue
        path = os.path.join(_TOOLS_IMPL_DIR, entry)
        with open(path, encoding="utf-8") as f:
            source = f.read()
        if not any(marker in source for marker in _WRITE_MARKERS):
            continue
        match = _NAME_RE.search(source)
        if match:
            found.append((match.group(1), entry))
        else:
            # A writer whose wire name cannot be read is not a pass - it is a tool this
            # ratchet cannot vouch for, which is the state it exists to prevent.
            found.append((None, entry))
    return found


@e2e_test(tool="_mutation_set_ratchet", kind="read")
def test_every_metadata_write_tool_is_a_known_mutation():
    """A tool that writes the model but is missing from MODEL_MUTATION_TOOLS silently
    weakens the reset shortcut AND the unresolved-write abort. Fail here, at the moment
    it is added, rather than in whichever later test inherits the leaked state."""
    writers = _write_tools()
    if not writers:
        _fail("found no model-writing tools under %s - the ratchet is looking in the wrong place "
              "and would pass no matter what happened" % _TOOLS_IMPL_DIR)

    unnamed = [java for name, java in writers if name is None]
    if unnamed:
        _fail("cannot read the NAME constant of metadata-write tool(s): %s. Without the wire "
              "name this ratchet cannot check them, so it refuses to report a pass."
              % ", ".join(sorted(unnamed)))

    missing = sorted("%s (%s)" % (name, java) for name, java in writers
                     if name not in MODEL_MUTATION_TOOLS)
    if missing:
        _fail("these tools write the model (they extend AbstractMetadataWriteTool, or call "
              "BmTransactions.write / forceExportToDisk) but are absent from "
              "harness.MODEL_MUTATION_TOOLS: %s. A successful call to one of them must forfeit "
              "the reset shortcut, and one that dies in flight must abort the run instead of "
              "being cleaned up on top of - neither happens while the set does not know the "
              "tool. Add it to MODEL_MUTATION_TOOLS (or to DEEP_MUTATION_TOOLS if merely CALLING "
              "it, refused or not, can move the model)." % "; ".join(missing))


@e2e_test(tool="_mutation_set_ratchet", kind="read")
def test_the_ratchet_actually_reads_the_write_tools():
    """Guards the guard: if the markers or the layout ever change, the scan above would quietly
    find nothing and pass. Pin writers that must always be found - including the one that is only
    visible through its mutation CALLS. build_external_objects implements IMcpTool directly, so it
    is exactly what a base-class-only scan misses, and pinning it means the detection cannot
    silently narrow back to what it was."""
    names = {name for name, _ in _write_tools()}
    for expected in ("create_metadata", "modify_metadata", "build_external_objects"):
        if expected not in names:
            _fail("the scan did not find %r among the model-writing tools - one of the markers %r "
                  "or the NAME convention changed, and this ratchet is no longer checking what it "
                  "claims to" % (expected, _WRITE_MARKERS))

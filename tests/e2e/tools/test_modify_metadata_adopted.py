"""
e2e tests for modify_metadata on objects a configuration EXTENSION adopted (issues #709 / #710).

WHAT IT PINS (read AdoptedPropertyRouter.java and ModifyMetadataTool.routeAdopted for the branches):
  EDT's .mdo writer for an extension (MdExtXmlFormatter.shouldSaveFeature) leaves a property of an
  ADOPTED object out of the extension's .mdo while the same-named state in the object's extension
  block is unset or None. modify_metadata used to write such a value into the model and answer
  `persisted: true` while the file did not change. Now:
  - an adopted defined type's `type` (#709) is ADDED, as Extended, to the type extension in its
    extension block; its base types and the types the extension checks stay as they are, and each
    call states the whole set of additions;
  - an adopted event subscription's `source` (#710) becomes the base sources plus the additions, with
    the state Extended; a call that adds nothing returns the state to None;
  - any other write EDT would leave out - an adopted object's synonym, a catalog's owners, the
    predefined items of an adopted owner - is refused before anything is written;
  - controls: a property without a state (comment) of an adopted object, every property of an object
    the extension owns, and the content of an adopted subsystem - EDT's adopter marks it Extended, so
    EDT keeps it - are written as before. (The guard of the subsystem content path refuses a content
    state of None, which MCP cannot produce: ModifyMetadataToolAdoptedTest pins that refusal.)

ENVIRONMENT / SCOPE:
  The fixture extension `TestConfiguration.tests` (Runtime-Version 8.5.1: the type route needs 8.3.20,
  the source route 8.3.17) adopts Catalog.Catalog and owns Catalog.tests_ExtOnly. A routed test seeds its
  object in the BASE (create_metadata + modify_metadata), adopts it into the extension with
  adopt_metadata_object and then writes it through the extension; the refusal tests address objects
  the fixture extension already adopts, and the subsystem control adopts Subsystem.Subsystem first.
  Every test that writes into the extension - or might, on a jar without the fix - restores it through
  harness.restore_extension_fixture; the orchestrator resets the base model after the test from the
  `writtenProjects` evidence, as for every write-metadata test.

DISK FIRST: what a write leaves is read from the extension's .mdo directly and parsed with ElementTree.
The ROOT's own <type> / <source> (the native value) is told apart from the children of the object's
<extension> block (the states and the type extension); types are compared as SETS, never by order and
never as substrings. The message is asserted after the file, so a jar without the fix fails on the file.

RED ON THE OLD JAR (the stand phase pins it): the routed tests fail on their disk poll after a
"successful" call (the file does not change); the refusal tests fail on assert_error (old answer:
success) or on assert_error_quality (the predefined item: "not found" instead of the refusal); the
controls - the adopted subsystem's content among them - stay green on both jars.
"""

import os
import time
import xml.etree.ElementTree as ET

from harness import (
    E2ECallTimeout,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_marker_contract,
    assert_contains,
    assert_not_contains,
    assert_no_diff_rel,
    project_error_rows,
    read_disk,
    read_fixture_file,
    restore_extension_fixture,
    status_porcelain_rel,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    REPO_ROOT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
)

_EXTENDED = "Extended"
_CHECKED = "Checked"
_ADOPTED = "<objectBelonging>Adopted</objectBelonging>"
# The object of the base configuration the fixture extension adopts, and the extension's own catalog.
_BASE_CATALOG = "Catalog"
_OWN_CATALOG = "tests_ExtOnly"
_EXT_CATALOG_MDO = "src/Catalogs/%s/%s.mdo" % (_BASE_CATALOG, _BASE_CATALOG)
# Long enough for an export the call submitted to land on a loaded stand.
_DISK_TIMEOUT = 30


# ──────────────────────────────────────────────────────────────────────────────
# Reading the extension's .mdo
# ──────────────────────────────────────────────────────────────────────────────
def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _children(element, name):
    """The DIRECT children of element with the local name `name` - never a deeper descendant, so the
    root's own <source> is never confused with the <source> state inside the <extension> block."""
    return [child for child in list(element) if _local(child.tag) == name]


def _one_or_none(element, name, ctx):
    found = _children(element, name)
    if len(found) > 1:
        raise AssertionError("expected at most one <%s> under <%s> [%s]; got %d"
                             % (name, _local(element.tag), ctx, len(found)))
    return found[0] if found else None


def _text(element):
    return (element.text or "").strip()


def _ext_mdo(kind_dir, name):
    """The extension-relative .mdo of a top object."""
    return "src/%s/%s/%s.mdo" % (kind_dir, name, name)


def _read_ext(relpath, ctx):
    """One read of an extension file, as text and parsed."""
    text = read_fixture_file(TESTS_PROJECT_REL, relpath)
    try:
        return text, ET.fromstring(text)
    except ET.ParseError as e:
        raise AssertionError("%s/%s is not well-formed XML [%s]: %s\n%s"
                             % (TESTS_PROJECT_REL, relpath, ctx, e, text[:500]))


def _poll_ext(relpath, ctx, needles=(), lacking=(), timeout=_DISK_TIMEOUT):
    """Poll one extension file until ONE read holds every needle, holds none of `lacking` and parses;
    return that read, parsed. A read that lands while the export replaces the file is no verdict."""
    deadline = time.time() + timeout
    last = ""
    while True:
        try:
            last = read_fixture_file(TESTS_PROJECT_REL, relpath)
            if all(n in last for n in needles) and not any(n in last for n in lacking):
                return ET.fromstring(last)
        except (OSError, ET.ParseError) as e:
            last = "(not readable yet: %s) %s" % (e, last[:300])
        if time.time() >= deadline:
            break
        time.sleep(0.5)
    raise AssertionError("expected %s/%s to hold %r and not %r [%s]; it holds:\n%s"
                         % (TESTS_PROJECT_REL, relpath, list(needles), list(lacking), ctx, last[:900]))


def _block(root, ctx):
    """The object's <extension> block (a direct child of the root), or None."""
    return _one_or_none(root, "extension", ctx)


def _type_extension(root, ctx):
    """The {(type, state)} pairs of the type extension in the extension block (a state EDT does not
    write - None - reads as None), and the <typeExtension> element itself (None when there is none)."""
    block = _block(root, ctx)
    tde = None if block is None else _one_or_none(block, "typeExtension", ctx)
    pairs = set()
    if tde is not None:
        for item in _children(tde, "types"):
            types = _children(item, "type")
            states = _children(item, "state")
            if len(types) != 1 or len(states) > 1:
                raise AssertionError("a type-extension item holds one <type> and at most one <state> [%s]: %s"
                                     % (ctx, ET.tostring(item, encoding="unicode")))
            pairs.add((_text(types[0]), _text(states[0]) if states else None))
    return pairs, tde


def _string_length(tde, ctx):
    """The String length the type extension carries, or None."""
    qualifiers = None if tde is None else _one_or_none(tde, "stringQualifiers", ctx)
    length = None if qualifiers is None else _one_or_none(qualifiers, "length", ctx)
    return None if length is None else _text(length)


def _root_types(root, name, ctx):
    """The type names of the ROOT's own <name> element (the native value), or None when it is absent."""
    element = _one_or_none(root, name, ctx)
    return None if element is None else [_text(t) for t in _children(element, "types")]


def _root_values(root, name):
    """The texts of the ROOT's own <name> elements, in order - a list reference writes one element per member -
    never the same-named state inside the <extension> block."""
    return [_text(element) for element in _children(root, name)]


def _block_state(root, name, ctx):
    """The text of the state <name> in the extension block, or None when EDT did not write it (None)."""
    block = _block(root, ctx)
    state = None if block is None else _one_or_none(block, name, ctx)
    return None if state is None else _text(state)


# ──────────────────────────────────────────────────────────────────────────────
# Calls
# ──────────────────────────────────────────────────────────────────────────────
def _seed_base(fqn, properties, ctx):
    """Create a top object in the BASE and give it its value."""
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed %s in the base [%s]" % (fqn, ctx))
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": fqn, "properties": properties}),
              "set the base value of %s [%s]" % (fqn, ctx))
    wait_for_project_ready()


def _adopt(fqn, ctx):
    """Adopt a base object into the fixture extension."""
    r = call("adopt_metadata_object", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "adopt %s into the extension [%s]" % (fqn, ctx))
    s = r.structured if isinstance(r.structured, dict) else {}
    if s.get("action") != "adopted" or s.get("extensionProject") != TESTS_PROJECT:
        raise AssertionError("expected %s adopted into %s [%s]; got %r" % (fqn, TESTS_PROJECT, ctx, s))
    wait_for_project_ready()


def _modify_ext(fqn, properties=None, content=None):
    args = {"projectName": TESTS_PROJECT, "fqn": fqn}
    if properties is not None:
        args["properties"] = properties
    if content is not None:
        args["content"] = content
    return call("modify_metadata", args)


def _types(name, *kinds):
    """A properties list writing one type-like property."""
    return [{"name": name, "value": {"types": list(kinds)}}]


def _applied(r, ctx):
    s = r.structured if isinstance(r.structured, dict) else {}
    return s.get("applied"), s.get("message") or ""


def _ext_assignable_row(fqn, prop):
    """MODEL read-back in the extension: the assignable-table row of one property, or None."""
    r = call("get_metadata_details", {"projectName": TESTS_PROJECT, "objectFqns": [fqn], "assignable": True})
    assert_ok(r, "get_metadata_details(assignable) for %s in the extension" % fqn)
    prefix = "| " + prop + " |"
    return next((line for line in (r.text or "").splitlines() if line.strip().startswith(prefix)), None)


def _assert_not_in_extension(fqn, ctx):
    """The extension holds no copy of the base object `fqn`: its model does not find it, and it has no .mdo of it."""
    r = call("get_metadata_details", {"projectName": TESTS_PROJECT, "objectFqns": [fqn]})
    assert_ok(r, "get_metadata_details of %s in the extension [%s]" % (fqn, ctx))
    if "## Errors" not in (r.text or "") or "Object not found" not in (r.text or ""):
        raise AssertionError("PREMISE: %s must not be in the extension [%s]; get_metadata_details found it:\n%s"
                             % (fqn, ctx, (r.text or "")[:500]))
    kind, name = fqn.split(".", 1)
    folder = os.path.join(REPO_ROOT, *TESTS_PROJECT_REL.split("/"), "src", kind + "s", name)
    if os.path.exists(folder):
        raise AssertionError("PREMISE: the extension must hold no %s [%s]; found %s" % (fqn, ctx, folder))


def _assert_no_marker_names(needle, rows, ctx):
    """No marker row's message or location names `needle`."""
    named = [row for row in rows
             if needle in str(row.get("message") or "") or needle in str(row.get("location") or "")]
    if named:
        raise AssertionError("EDT reports a marker naming %s [%s]: %r" % (needle, ctx, named))


def _assert_refused_without_a_trace(r, relpath, before_text, before_status, ctx):
    """A refusal writes nothing: the file and the extension's status are what they were before the call."""
    err = assert_error(r, ctx)
    after_text = read_fixture_file(TESTS_PROJECT_REL, relpath)
    if after_text != before_text:
        raise AssertionError("a refused call must leave %s as it was [%s]" % (relpath, ctx))
    if status_porcelain_rel(TESTS_PROJECT_REL) != before_status:
        raise AssertionError("a refused call must leave the extension's files as they were [%s]" % ctx)
    return err


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


def _restoring_on_failure(ctx, body):
    """Run a body that must write nothing; restore the extension only when it failed (a jar without the
    fix wrote the model)."""
    try:
        body()
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture(ctx)
        raise


# ──────────────────────────────────────────────────────────────────────────────
# #709: an adopted defined type's type
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_an_adopted_defined_type_adds_a_type_to_its_type_extension():
    """#709, the reporter's case: a base defined type [CatalogRef.Catalog, String(10)], adopted, then the
    type [CatalogRef.tests_ExtOnly, CatalogRef.Catalog] written through the extension. The extension's own
    catalog ref is ADDED, as Extended, to the type extension; the base type CatalogRef.Catalog is a no-op
    and is not Extended; the base items the adoption wrote stay as they were; there is no root <type>.

    Mutation thinking: a native write (master) leaves the file as the adoption wrote it - the disk poll
    fails; a write that replaced the type extension instead of adding to it loses the adoption's items; a
    base type passed back marked Extended fails the Extended set."""
    name = "E2EAdoptDt709Ref"
    fqn = "DefinedType." + name
    mdo = _ext_mdo("DefinedTypes", name)

    def body():
        _seed_base(fqn, _types("type", {"kind": "CatalogRef", "ref": _BASE_CATALOG},
                               {"kind": "String", "length": 10}), "the reporter's composite base")
        _adopt(fqn, "#709")
        before = _poll_ext(mdo, "the adopted defined type's .mdo", needles=[_ADOPTED])
        initial, _ = _type_extension(before, "after the adoption")
        if any(state == _EXTENDED for _, state in initial):
            raise AssertionError("a fresh adoption extends no type; got %r" % sorted(initial, key=repr))

        r = _modify_ext(fqn, _types("type", {"kind": "CatalogRef", "ref": _OWN_CATALOG},
                                    {"kind": "CatalogRef", "ref": _BASE_CATALOG}))
        assert_ok(r, "add the extension's own catalog ref to the adopted defined type")

        after = _poll_ext(mdo, "the addition must reach the type extension",
                          needles=["CatalogRef." + _OWN_CATALOG, ">" + _EXTENDED + "<"])
        pairs, _ = _type_extension(after, "after the addition")
        extended = {t for t, state in pairs if state == _EXTENDED}
        if extended != {"CatalogRef." + _OWN_CATALOG}:
            raise AssertionError("exactly the extension's own catalog ref is Extended; got %r" % sorted(pairs, key=repr))
        if pairs - {("CatalogRef." + _OWN_CATALOG, _EXTENDED)} != initial:
            raise AssertionError("the items the adoption wrote must stay as they were: before %r, after %r"
                                 % (sorted(initial, key=repr), sorted(pairs, key=repr)))
        if _root_types(after, "type", "after the addition"):
            raise AssertionError("an adopted defined type's native type is never written to the extension: %r"
                                 % _root_types(after, "type", "after the addition"))

        applied, message = _applied(r, "the addition")
        if applied != ["type"]:
            raise AssertionError("the routed type must be reported as applied: %r" % (r.structured,))
        assert_contains(message, "added (Extended): CatalogRef." + _OWN_CATALOG, "the message names the addition")
        assert_contains(message, "already base types, left as they stand: CatalogRef." + _BASE_CATALOG,
                        "the message names the base type it left as it stands")

    _restoring("an adopted defined type's type extension", body)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_an_adopted_defined_types_additions_are_replaced_by_each_call():
    """#709, the repeat semantics: a base defined type [String(10)] is adopted with String Checked and its
    length. Adding CatalogRef.tests_ExtOnly keeps the Checked String and its qualifiers; a call that passes
    only the base type removes the addition; repeating it changes nothing; the base type with another
    length is refused and writes nothing.

    Mutation thinking: a write that kept every earlier addition fails the removal; one that dropped the
    Checked base item (or its qualifiers) fails the first write; a base type taken with the request's
    qualifiers instead of refused changes the file."""
    name = "E2EAdoptDt709Str"
    fqn = "DefinedType." + name
    mdo = _ext_mdo("DefinedTypes", name)
    own = "CatalogRef." + _OWN_CATALOG

    def body():
        _seed_base(fqn, _types("type", {"kind": "String", "length": 10}), "a String(10) base")
        _adopt(fqn, "#709 repeat")
        before = _poll_ext(mdo, "the adopted defined type's .mdo", needles=[_ADOPTED])
        initial, tde = _type_extension(before, "after the adoption")
        if initial != {("String", _CHECKED)} or _string_length(tde, "after the adoption") != "10":
            raise AssertionError("PRECONDITION (EDT's adopter): the adoption checks String(10) in the type "
                                 "extension; got %r, length %r - record this on the stand"
                                 % (sorted(initial, key=repr), _string_length(tde, "after the adoption")))

        r = _modify_ext(fqn, _types("type", {"kind": "String", "length": 10}, {"kind": "CatalogRef", "ref": _OWN_CATALOG}))
        assert_ok(r, "add a catalog ref next to the checked base String")
        added = _poll_ext(mdo, "the addition must reach the type extension", needles=[own])
        pairs, tde = _type_extension(added, "after the addition")
        if pairs != {("String", _CHECKED), (own, _EXTENDED)}:
            raise AssertionError("the checked base String stays and the ref is Extended; got %r" % sorted(pairs, key=repr))
        if _string_length(tde, "after the addition") != "10":
            raise AssertionError("the base String keeps its length 10; got %r" % _string_length(tde, "after the addition"))

        r = _modify_ext(fqn, _types("type", {"kind": "String", "length": 10}))
        assert_ok(r, "pass only the base type: the earlier addition goes")
        removed = _poll_ext(mdo, "the earlier addition must leave the type extension", needles=[_ADOPTED],
                            lacking=[own])
        pairs, tde = _type_extension(removed, "after the removal")
        if pairs != {("String", _CHECKED)} or _string_length(tde, "after the removal") != "10":
            raise AssertionError("only the checked base String(10) is left; got %r, length %r"
                                 % (sorted(pairs, key=repr), _string_length(tde, "after the removal")))
        assert_contains(_applied(r, "the removal")[1], "earlier additions removed: " + own,
                        "the message names the removed addition")

        text, _ = _read_ext(mdo, "before the repeat")
        status = status_porcelain_rel(TESTS_PROJECT_REL)
        r = _modify_ext(fqn, _types("type", {"kind": "String", "length": 10}))
        assert_ok(r, "repeat the call")
        applied, message = _applied(r, "the repeat")
        if applied:
            raise AssertionError("a repeat applies nothing; got %r" % (r.structured,))
        if (r.structured or {}).get("writtenProjects") != []:
            raise AssertionError("a repeat queues nothing and must say so (#408): writtenProjects=%r"
                                 % ((r.structured or {}).get("writtenProjects"),))
        assert_contains(message, "Nothing was changed on " + fqn, "a repeat says nothing changed")
        if read_fixture_file(TESTS_PROJECT_REL, mdo) != text or status_porcelain_rel(TESTS_PROJECT_REL) != status:
            raise AssertionError("a repeat must leave the extension's files as they were")

        r = _modify_ext(fqn, _types("type", {"kind": "String", "length": 20}))
        err = _assert_refused_without_a_trace(r, mdo, text, status, "the base String with another length")
        assert_error_quality(err, names=["String", fqn, "base type"],
                             suggests=["only the types to add", "Nothing was changed"],
                             ctx="a base type with other qualifiers is refused, naming it and the way out")

    _restoring("an adopted defined type's repeated writes", body)


# ──────────────────────────────────────────────────────────────────────────────
# #710: an adopted event subscription's source
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_an_adopted_event_subscription_extends_its_source():
    """#710: a base subscription on CatalogObject.Catalog, adopted, then the source
    [CatalogObject.tests_ExtOnly] written through the extension. The state becomes Extended and the root
    <source> holds the base source plus the addition; a call that adds nothing beyond the base returns the
    state to None and EDT writes no root <source> again.

    Mutation thinking: a native write (master) leaves the file without the source - the poll fails; a
    write that replaced the source instead of extending it loses CatalogObject.Catalog; one that skipped
    the state leaves no Extended in the block; one that left Extended on an empty addition keeps the root
    <source>."""
    name = "E2EAdoptSub710"
    fqn = "EventSubscription." + name
    mdo = _ext_mdo("EventSubscriptions", name)
    base = "CatalogObject." + _BASE_CATALOG
    own = "CatalogObject." + _OWN_CATALOG

    def body():
        _seed_base(fqn, _types("source", {"kind": "CatalogObject", "ref": _BASE_CATALOG}), "a catalog subscription")
        _adopt(fqn, "#710")
        before = _poll_ext(mdo, "the adopted subscription's .mdo", needles=[_ADOPTED])
        if _block_state(before, "source", "after the adoption") not in (None, "None"):
            raise AssertionError("a fresh adoption leaves the source state None; got %r"
                                 % _block_state(before, "source", "after the adoption"))

        r = _modify_ext(fqn, _types("source", {"kind": "CatalogObject", "ref": _OWN_CATALOG}))
        assert_ok(r, "extend the adopted subscription's source")
        after = _poll_ext(mdo, "the extended source must reach the file", needles=[own, ">" + _EXTENDED + "<"])
        if _block_state(after, "source", "after the extension") != _EXTENDED:
            raise AssertionError("the source state is Extended; got %r" % _block_state(after, "source", "after"))
        if set(_root_types(after, "source", "after the extension") or []) != {base, own}:
            raise AssertionError("the source is the base source plus the addition; got %r"
                                 % _root_types(after, "source", "after the extension"))
        applied, message = _applied(r, "the extension")
        if applied != ["source"]:
            raise AssertionError("the routed source must be reported as applied: %r" % (r.structured,))
        assert_contains(message, "its source is Extended - the base sources %s plus %s" % (base, own),
                        "the message names the base sources and the addition")
        row = _ext_assignable_row(fqn, "source")
        if row is None or base not in row or own not in row:
            raise AssertionError("the model's source is the base source plus the addition; got %r" % (row,))

        r = _modify_ext(fqn, _types("source", {"kind": "CatalogObject", "ref": _BASE_CATALOG}))
        assert_ok(r, "a source with nothing beyond the base")
        back = _poll_ext(mdo, "the source must follow the base again", needles=[_ADOPTED], lacking=[own])
        if _block_state(back, "source", "after the return") not in (None, "None"):
            raise AssertionError("the source state is None again; got %r" % _block_state(back, "source", "after"))
        if _root_types(back, "source", "after the return") is not None:
            raise AssertionError("EDT writes no root <source> while the state is None; got %r"
                                 % _root_types(back, "source", "after the return"))
        assert_contains(_applied(r, "the return")[1], "no longer extended (state None)",
                        "the message says the source follows the base again")
        row = _ext_assignable_row(fqn, "source")
        if row is None or base not in row or own in row:
            raise AssertionError("the model's source is the base source alone again; got %r" % (row,))

    _restoring("an adopted subscription's source", body)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_an_adopted_subscription_never_drops_a_base_source():
    """#710, the BSP shape: the base subscription also handles a document the extension has NOT adopted.
    An extended source is the base sources plus the additions, so a base source the extension cannot
    carry must not silently go. The extension carries a base source whose object it adopted, or one an
    earlier extended write left in the subscription's own source in the extension; this document is
    neither, so the call is refused naming the document to adopt. On EDT 2026.2 an adoption leaves the
    subscription's own source empty (stand probes P1/P2), so this fixture takes the refused branch; the
    carried branch (a source an earlier extended write left there - by EDT's editor, for one) is still
    accepted, and the invariant is the same either way: no base source is ever dropped.

    PREMISE, checked: the document stays out of the extension - after the adoption and after the write - so the
    test cannot pass in the carried branch on a shape that is no longer the BSP one. EDT's verdict on the carried
    branch: no marker names the document, neither in the write's marker report nor in the settled
    get_project_errors of the extension (markersIncomplete may be true when the call returns - the settled view
    decides then). The reload of the carried source is the stand phase's probe P2 (clean_project, then
    get_project_errors).

    Mutation thinking: a write that composed the source from the additions alone (master writes just the
    request, and EDT leaves it out) fails both branches."""
    doc = "E2EBspDoc710"
    name = "E2EBspSub710"
    fqn = "EventSubscription." + name
    mdo = _ext_mdo("EventSubscriptions", name)
    sources = {"DocumentObject." + doc, "CatalogObject." + _BASE_CATALOG}
    own = "CatalogObject." + _OWN_CATALOG

    def body():
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Document." + doc}),
                  "seed the base document the extension does not adopt")
        wait_for_project_ready()
        _seed_base(fqn, _types("source", {"kind": "DocumentObject", "ref": doc},
                               {"kind": "CatalogObject", "ref": _BASE_CATALOG}), "a two-source base")
        _adopt(fqn, "the BSP shape")
        _poll_ext(mdo, "the adopted subscription's .mdo", needles=[_ADOPTED])
        _assert_not_in_extension("Document." + doc, "adopting the subscription must not adopt the document")
        text, _ = _read_ext(mdo, "before the write")
        status = status_porcelain_rel(TESTS_PROJECT_REL)

        r = _modify_ext(fqn, _types("source", {"kind": "CatalogObject", "ref": _OWN_CATALOG}))
        if r.is_error:
            err = _assert_refused_without_a_trace(r, mdo, text, status, "a base source the extension cannot carry")
            assert_error_quality(err, names=["Document." + doc, "DocumentObject." + doc],
                                 suggests=["adopt_metadata_object", "Nothing was changed"],
                                 ctx="the refusal names the document to adopt")
            print("    [BSP] refused: Document.%s is not adopted and the subscription's own source does not hold "
                  "DocumentObject.%s" % (doc, doc))
            return
        after = _poll_ext(mdo, "the extended source must reach the file", needles=[own, ">" + _EXTENDED + "<"])
        written = set(_root_types(after, "source", "after the extension") or [])
        if written != sources | {own}:
            raise AssertionError("an extended source carries every base source; got %r" % sorted(written))
        incomplete, rows = assert_marker_contract(r.structured, "the carried BSP write")
        print("    [BSP] carried: an earlier extended write left DocumentObject.%s in the subscription's own source; "
              "the write's markers: incomplete=%s rows=%r" % (doc, incomplete, rows))
        _assert_no_marker_names(doc, rows, "the write's marker report")
        wait_for_project_ready()
        _assert_no_marker_names(doc, [{"checkId": check, "message": message, "location": location}
                                      for check, message, location in project_error_rows(fqn, project=TESTS_PROJECT)],
                                "get_project_errors of the extension once settled")
        _assert_not_in_extension("Document." + doc, "the carried write must not adopt the document either")

    _restoring("an adopted subscription with an unadopted base source", body)


# ──────────────────────────────────────────────────────────────────────────────
# Writes EDT would leave out: refused, nothing written
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_an_adopted_objects_synonym_is_refused():
    """The general rule: Catalog.Catalog is adopted with no synonym state, so EDT would leave a synonym
    out of the extension's .mdo. The write is refused, naming the property, the object, the state and the
    base configuration, and saying how EDT allows it (mark it Extended); nothing is written.

    Mutation thinking: master answers success with persisted:true and an unchanged file - assert_error
    fails; a refusal that does not name the way out fails assert_error_quality."""
    def body():
        r = _modify_ext("Catalog." + _BASE_CATALOG,
                        [{"name": "synonym", "value": "E2EAdopted709Synonym", "language": "en"}])
        err = assert_error(r, "the synonym of an adopted catalog")
        assert_error_quality(err, names=["synonym", "Catalog." + _BASE_CATALOG, "adopted", "None",
                                         "base configuration", PROJECT],
                             suggests=["Extended", "Nothing was changed"],
                             ctx="the refusal names the property, the object, the state and the way out")
        assert_no_diff_rel(TESTS_PROJECT_REL, "a refused write must not touch the extension")

    _restoring_on_failure("a refused synonym of an adopted object", body)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_the_owners_of_an_adopted_catalog_are_refused():
    """A membership `content` payload on an adopted catalog edits its owners, which EDT would leave out
    while their state is None; an extension can only check owners against the base at this platform, so
    the refusal says so; nothing is written.

    Mutation thinking: without the guard at the top of the content dispatch master adds the owner and
    answers success."""
    def body():
        r = _modify_ext("Catalog." + _BASE_CATALOG, content=[{"metadata": "Catalog." + _OWN_CATALOG}])
        err = assert_error(r, "the owners of an adopted catalog")
        assert_error_quality(err, names=["owners", "Catalog." + _BASE_CATALOG, "adopted", "None",
                                         "base configuration"],
                             suggests=["only check", "Nothing was changed"],
                             ctx="the refusal names the list and that an extension can only check it")
        assert_no_diff_rel(TESTS_PROJECT_REL, "a refused content write must not touch the extension")

    _restoring_on_failure("refused owners of an adopted catalog", body)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_the_content_of_an_adopted_subsystem_is_written_while_its_state_is_extended():
    """Control on the subsystem's own content path - no false refusal: EDT's adopter marks an adopted subsystem's
    `content` Extended (SubsystemAdopterParticipant), so EDT's .mdo writer keeps the list, and a member added
    through the extension lands in the extension's Subsystem.mdo - there, and not in the base's. The guard of this
    path refuses a content state of None, which MCP cannot produce (adopt_metadata_object always leaves it
    Extended); ModifyMetadataToolAdoptedTest pins that refusal on the same path.

    PREMISE, checked: right after the adoption the extension's Subsystem.mdo holds the content state Extended in
    its extension block and lists no member. On a fixture where it does not, the test fails naming the premise
    instead of judging the guard.

    Mutation thinking: a guard that refused an adopted subsystem's content whatever its state (wouldDrop forced
    true for it) answers an error and leaves the file - the disk poll fails; a write that landed in the base
    instead of the extension leaves the extension's .mdo without the member and lists it in the base's."""
    fqn = "Subsystem.Subsystem"
    mdo = "src/Subsystems/Subsystem/Subsystem.mdo"
    member = "Catalog." + _OWN_CATALOG

    def body():
        _adopt(fqn, "the subsystem whose content is written")
        before = _poll_ext(mdo, "the adopted subsystem's .mdo", needles=[_ADOPTED])
        state = _block_state(before, "content", "after the adoption")
        if state != _EXTENDED:
            raise AssertionError("PREMISE (EDT's adopter): the adopted subsystem's content state is Extended right after "
                                 "the adoption; got %r - this control needs it" % (state,))
        if _root_values(before, "content"):
            raise AssertionError("PREMISE: a fresh adoption lists no content member; got %r"
                                 % _root_values(before, "content"))

        r = _modify_ext(fqn, content=[{"metadata": member}])
        after = _poll_ext(mdo, "the member must reach the extension's .mdo (the call answered: %s)"
                          % (r.text or "")[:300], needles=[">%s<" % member])
        if _root_values(after, "content") != [member]:
            raise AssertionError("the adopted subsystem lists exactly the member; got %r" % _root_values(after, "content"))
        if _block_state(after, "content", "after the write") != _EXTENDED:
            raise AssertionError("the content state stays Extended; got %r"
                                 % _block_state(after, "content", "after the write"))
        assert_ok(r, "the content of an adopted subsystem whose state is Extended")
        added = ((r.structured or {}).get("content") or {}).get("added")
        if added != 1:
            raise AssertionError("the answer counts one member added; got %r" % (r.structured,))
        assert_not_contains(read_disk(mdo), member,
                            "the base's Subsystem.mdo does not list the extension's member")

    _restoring("the content of an adopted subsystem", body)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_a_predefined_item_of_an_adopted_owner_is_refused():
    """The predefined items of an adopted catalog reach the extension's .mdo only when the extension marks
    them; with the state None every predefined write is refused, before the item is looked up.

    Mutation thinking: without the guard master looks the item up and answers 'not found' - an error, but
    one that names neither the adoption nor the state, so assert_error_quality fails."""
    def body():
        item = "Catalog.%s.Predefined.E2ENoSuchItem709" % _BASE_CATALOG
        r = _modify_ext(item, [{"name": "description", "value": "E2E709"}])
        err = assert_error(r, "a predefined item of an adopted catalog")
        assert_error_quality(err, names=["predefined", "Catalog." + _BASE_CATALOG, "adopted", "None"],
                             suggests=["Extended", "Nothing was changed"],
                             ctx="the refusal names the predefined items and the way out")
        assert_no_diff_rel(TESTS_PROJECT_REL, "a refused predefined write must not touch the extension")

    _restoring_on_failure("a refused predefined item of an adopted owner", body)


# ──────────────────────────────────────────────────────────────────────────────
# Controls (green on both jars)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_a_stateless_property_of_an_adopted_object_is_still_written():
    """Control: `comment` has no state in the extension block, so EDT writes it for an adopted object
    too; the router leaves it as a plain write."""
    value = "E2EAdopted709Comment"

    def body():
        r = _modify_ext("Catalog." + _BASE_CATALOG, [{"name": "comment", "value": value}])
        assert_ok(r, "the comment of an adopted catalog")
        root = _poll_ext(_EXT_CATALOG_MDO, "the comment must reach the file",
                         needles=["<comment>%s</comment>" % value])
        comment = _one_or_none(root, "comment", "the adopted catalog's comment")
        if comment is None or _text(comment) != value:
            raise AssertionError("the root <comment> holds the value; got %r" % (comment,))
        assert_not_contains(_applied(r, "the comment")[1], "adopted",
                            "a plain write says nothing about the extension block")

    _restoring("a comment on an adopted object", body)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_the_extensions_own_defined_type_and_subscription_are_written_natively():
    """Control (the epic's boundary): objects the extension OWNS have no extension block, and their type
    and source are written as their native values, exactly as before."""
    dt = "tests_E2EOwnDt709"
    sub = "tests_E2EOwnSub709"
    dt_mdo = _ext_mdo("DefinedTypes", dt)
    sub_mdo = _ext_mdo("EventSubscriptions", sub)

    def body():
        assert_ok(call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": "DefinedType." + dt}),
                  "seed a defined type the extension owns")
        wait_for_project_ready()
        r = _modify_ext("DefinedType." + dt, _types("type", {"kind": "String", "length": 15}))
        assert_ok(r, "type the extension's own defined type")
        root = _poll_ext(dt_mdo, "the native type must reach the file", needles=["<length>15</length>"])
        if _block(root, "the own defined type") is not None:
            raise AssertionError("an object the extension owns has no extension block")
        if _root_types(root, "type", "the own defined type") != ["String"]:
            raise AssertionError("the native type is String(15); got %r" % _root_types(root, "type", "own"))
        assert_not_contains(_applied(r, "the own type")[1], "adopted", "an own object is not adopted")

        assert_ok(call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": "EventSubscription." + sub}),
                  "seed a subscription the extension owns")
        wait_for_project_ready()
        r = _modify_ext("EventSubscription." + sub, _types("source", {"kind": "CatalogObject", "ref": _OWN_CATALOG}))
        assert_ok(r, "the source of the extension's own subscription")
        root = _poll_ext(sub_mdo, "the native source must reach the file",
                         needles=["CatalogObject." + _OWN_CATALOG])
        if _block(root, "the own subscription") is not None:
            raise AssertionError("an object the extension owns has no extension block")
        if _root_types(root, "source", "the own subscription") != ["CatalogObject." + _OWN_CATALOG]:
            raise AssertionError("the native source is the extension's catalog; got %r"
                                 % _root_types(root, "source", "own"))
        row = _ext_assignable_row("EventSubscription." + sub, "source")
        if row is None or "CatalogObject." + _OWN_CATALOG not in row:
            raise AssertionError("the model's source is the extension's catalog; got %r" % (row,))

    _restoring("the extension's own defined type and subscription", body)

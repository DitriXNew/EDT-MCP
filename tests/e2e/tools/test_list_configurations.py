"""
e2e tests for list_configurations (kind: read).

The tool lists EDT *launch* configurations (runtime-client + Attach + other 1C
launch types) from the live EDT workbench, with each entry's running state. It is
the discovery step before debug_launch / run_yaxunit_tests / debug_yaxunit_tests /
update_database.

Response type is JSON (ListConfigurationsTool.getResponseType()==JSON), so the
payload lands in Result.structured as {"configurations": [...], "count": N};
Result.text is only a placeholder. All happy-path assertions read r.structured.

Why the happy path does NOT assert a specific configuration name:
launch configs live in the EDT *workspace* (.metadata), NOT in the git-tracked
TestConfiguration/ tree (there are no *.launch files in the fixture). The set of
configs is therefore environment-dependent. Asserting a fixed name would be flaky.
Instead we assert the tool's structural INVARIANTS, which break if the tool is
broken regardless of how many configs exist:
  - "configurations" is a list and "count" is an int,
  - count == len(configurations)  (the tool computes both from the same loop),
  - the type filter actually filters (every entry of an attach-filtered result
    has attach==True; the filtered count never exceeds the unfiltered count).

Parameter shape (from the Java): two OPTIONAL filters only —
  type: enum {"attach","client","all"} (default all),
  projectName: optional exact-match project filter.
There is NO required parameter and NO XOR/conditional parameter, so "missing
required" / "mutually-exclusive" negatives do not apply to this tool. See the
AUDIT notes below for where the tool is intentionally lenient (no error path on
bad input) — those are recorded, not papered over.
"""

from harness import call, assert_ok, assert_no_diff, e2e_test


def _configs_and_count(r, ctx):
    """Validate the structured envelope and return (configurations_list, count)."""
    sc = r.structured
    if not isinstance(sc, dict):
        # A JSON tool MUST populate structuredContent; a missing/typed-wrong
        # envelope means the tool returned the wrong shape (a real regression).
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    configs = sc.get("configurations")
    count = sc.get("count")
    if not isinstance(configs, list):
        raise AssertionError("'configurations' must be a list [%s]: %r" % (ctx, configs))
    if not isinstance(count, int):
        raise AssertionError("'count' must be an int [%s]: %r" % (ctx, count))
    if count != len(configs):
        raise AssertionError(
            "count(%d) must equal len(configurations)(%d) [%s]" % (count, len(configs), ctx))
    return configs, count


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_configurations", kind="read")
def test_lists_configs_with_consistent_envelope_and_no_mutation():
    """Default (no args): the JSON envelope must be well-formed and count must
    equal the number of entries; a read tool must not touch the project tree."""
    r = call("list_configurations", {})
    assert_ok(r, "list_configurations default")
    configs, count = _configs_and_count(r, "default listing")
    # Every entry that exists must carry the discovery contract fields the tool
    # promises (name + the boolean flags it always puts). This breaks if the tool
    # stops populating an entry correctly.
    for entry in configs:
        if "name" not in entry:
            raise AssertionError("every config entry must have a 'name': %r" % entry)
        if not isinstance(entry.get("attach"), bool):
            raise AssertionError("every entry must have a boolean 'attach': %r" % entry)
        if not isinstance(entry.get("running"), bool):
            raise AssertionError("every entry must have a boolean 'running': %r" % entry)
    assert_no_diff("a read/list tool must not mutate the project on disk")


@e2e_test(tool="list_configurations", kind="read")
def test_attach_filter_narrows_to_attach_configs_only():
    """type='attach' must return a subset of the full list where EVERY entry is an
    attach config. This fails if the filter is a no-op or returns client configs."""
    full = call("list_configurations", {})
    assert_ok(full, "unfiltered baseline")
    _, full_count = _configs_and_count(full, "unfiltered baseline")

    r = call("list_configurations", {"type": "attach"})
    assert_ok(r, "attach filter")
    configs, count = _configs_and_count(r, "attach filter")

    # Filtering can only shrink (or keep) the set, never grow it.
    if count > full_count:
        raise AssertionError(
            "attach-filtered count(%d) must not exceed unfiltered count(%d)" % (count, full_count))
    # Contract: with type='attach' the tool only appends entries whose isAttach is
    # true, and it stamps each entry's 'attach' flag from that same isAttach.
    for entry in configs:
        if entry.get("attach") is not True:
            raise AssertionError("type='attach' must yield only attach==True entries: %r" % entry)
    assert_no_diff("a read/list tool must not mutate the project on disk")


@e2e_test(tool="list_configurations", kind="read")
def test_client_filter_excludes_attach_configs():
    """type='client' must never return attach configs (the mirror invariant of the
    attach filter). Fails if 'client' accidentally falls through to 'all'."""
    r = call("list_configurations", {"type": "client"})
    assert_ok(r, "client filter")
    configs, _ = _configs_and_count(r, "client filter")
    for entry in configs:
        if entry.get("attach") is True:
            raise AssertionError("type='client' must exclude attach configs: %r" % entry)
    assert_no_diff("a read/list tool must not mutate the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative / edge matrix
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_configurations", kind="read")
def test_nonexistent_project_filter_returns_empty_not_garbage():
    """A projectName that matches no launch config must yield an EMPTY, consistent
    result (count==0, configurations==[]). The tool filters by exact project match
    (ATTR_PROJECT_NAME), so a bogus project name excludes everything.

    NOTE: the tool does NOT treat 'unknown project' as an error — it silently
    returns zero rows. We assert the empty/consistent result here (which fails if
    the project filter were ignored and configs leaked through). See AUDIT below."""
    bogus = "NoSuchProject_ZZZ_e2e"
    r = call("list_configurations", {"projectName": bogus})
    assert_ok(r, "nonexistent project filter")
    configs, count = _configs_and_count(r, "nonexistent project filter")
    if count != 0 or configs != []:
        raise AssertionError(
            "filtering by a nonexistent project must return zero configs, got count=%d: %r"
            % (count, configs))
    # AUDIT: list_configurations does not distinguish "no configs match this
    # project" from "no configs at all": an unknown projectName yields a silent
    # empty list with no diagnostic naming the bad filter or pointing at
    # list_projects to find a valid project name. A clearer signal (e.g. a note
    # when projectName matched nothing) would make the empty result actionable.
    assert_no_diff("a read/list tool must not mutate the project on disk")


@e2e_test(tool="list_configurations", kind="read")
def test_unknown_type_filter_is_handled_not_crashing():
    """An out-of-enum 'type' value: the schema declares enum {attach,client,all},
    but execute()'s matchesTypeFilter is deliberately PERMISSIVE for unknown
    filters (returns true -> behaves like 'all'). So a bad type must NOT crash and
    must NOT silently drop valid configs — it must match the unfiltered listing."""
    full = call("list_configurations", {})
    assert_ok(full, "unfiltered baseline")
    _, full_count = _configs_and_count(full, "unfiltered baseline")

    r = call("list_configurations", {"type": "bogus_enum_value_e2e"})
    # The tool itself never validates 'type', so this should succeed (behaving as
    # 'all'). If a future schema-level enforcement rejects it, this assertion will
    # surface that change instead of silently passing.
    assert_ok(r, "unknown type filter should be permissive (behaves as 'all')")
    _, count = _configs_and_count(r, "unknown type filter")
    if count != full_count:
        raise AssertionError(
            "permissive unknown type must equal unfiltered count: %d != %d" % (count, full_count))
    # AUDIT: the schema advertises type as a closed enum {attach,client,all}, but
    # the implementation accepts ANY value and silently treats unknown filters as
    # 'all' (matchesTypeFilter fall-through, ListConfigurationsTool.java:188-189).
    # An out-of-enum value is neither rejected nor flagged — the schema enum is
    # not enforced. A real validation error naming the bad value and listing the
    # accepted set ({attach,client,all}) would be more actionable.
    assert_no_diff("a read/list tool must not mutate the project on disk")

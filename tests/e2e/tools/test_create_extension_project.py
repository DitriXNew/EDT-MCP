"""
e2e tests for create_extension_project (kind: action).

THE TOOL: creates a NEW extension project in the EDT workspace bound to a
base configuration project. Complement of adopt_metadata_object.

HAPPY PATH: creates a fresh extension from the fixture base config, verifies
it appears in list_projects, then deletes it via delete_project (cleanup).

NEGATIVE: missing params, nonexistent base project, duplicate name.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

# A unique extension project name unlikely to collide with anything in the workspace.
NEW_EXT = "CreateExtTest_e2e"


def _ensure_absent(name):
    """Best-effort pre/post cleanup: remove a leftover project from a prior crashed run."""
    call("delete_project", {"projectName": name, "deleteContent": True, "confirm": True})


# ── NEGATIVE ──────────────────────────────────────────────────────────────────

@e2e_test(tool="create_extension_project", kind="action")
def test_missing_name_errors():
    """No arguments at all — the first required arg 'name' is reported."""
    r = call("create_extension_project", {})
    e = assert_error(r, "no args")
    assert_error_quality(e, names=["name"], suggests=[],
                         ctx="missing 'name' is named in the error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_extension_project", kind="action")
def test_missing_base_project_name_errors():
    """'name' present but 'baseProjectName' missing — error names the second required param."""
    r = call("create_extension_project", {"name": NEW_EXT})
    e = assert_error(r, "missing baseProjectName")
    assert_error_quality(e, names=["baseProjectName"], suggests=[],
                         # AUDIT: the shared requireArgument guard does not attach a discovery hint
                         # for 'baseProjectName' (only 'projectName' is mapped in discoveryHint).
                         # If a future fix adds one, update suggests=[...] here.
                         ctx="missing baseProjectName named in error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_extension_project", kind="action")
def test_nonexistent_base_project_errors():
    """A base project name that does not exist in the workspace."""
    bad = "NoSuchBase_cep_zzz"
    r = call("create_extension_project",
             {"name": NEW_EXT, "baseProjectName": bad})
    e = assert_error(r, "nonexistent base project")
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="nonexistent base project named + list_projects hint")
    assert_no_diff("a rejected call must not touch the fixture")


# ── HAPPY PATH + DUPLICATE GUARD ──────────────────────────────────────────────

@e2e_test(tool="create_extension_project", kind="action")
def test_create_extension_then_delete():
    """Create a fresh extension from the fixture base, verify it, then clean up."""
    # The tool creates PROJECT + "." + NEW_EXT as the effective project name; pre-clean that.
    effective_name = PROJECT + "." + NEW_EXT
    _ensure_absent(effective_name)
    try:
        # Happy path: create the extension
        r = call("create_extension_project",
                 {"name": NEW_EXT, "baseProjectName": PROJECT})
        assert_ok(r, "create_extension_project happy path")
        assert r.structured is not None, "response must carry structuredContent"
        assert r.structured.get("action") == "created", \
            "action must be 'created', got %r" % (r.structured,)
        assert r.structured.get("extensionProject") is not None, \
            "extensionProject must be present in the response"
        ext_project_name = r.structured["extensionProject"]
        assert ext_project_name == PROJECT + "." + NEW_EXT, \
            "extensionProject must default to '<base>.<name>', got %r" % ext_project_name

        # Verify it appears in list_projects
        wait_for_project_ready()
        lp = call("list_projects", {})
        assert_contains(lp.text, ext_project_name,
                        "new extension must appear in list_projects (proves it was really created)")

        # Duplicate guard: calling again with the same computed name must fail
        r2 = call("create_extension_project",
                  {"name": NEW_EXT, "baseProjectName": PROJECT})
        e2 = assert_error(r2, "duplicate extension name")
        assert_error_quality(e2, names=[ext_project_name], suggests=[],
                             ctx="duplicate extension project name is named in the error")

    finally:
        _ensure_absent(NEW_EXT)
        _ensure_absent(PROJECT + "." + NEW_EXT)

    assert_no_diff("the fixture must be untouched after the round-trip")

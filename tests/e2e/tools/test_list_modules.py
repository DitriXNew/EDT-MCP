"""
e2e tests for list_modules (kind: read).

list_modules lists BSL modules in a project. It is a MARKDOWN-response tool, so
the payload lives in r.text (not r.structured). Real tool behaviour (read from
ListModulesTool.java):
  - Required param: projectName only. Missing -> "projectName is required".
  - Non-existent project -> "Project not found: <name>".
  - metadataType is an effective enum (switch over a fixed type list);
    metadataType='all' (the default) does a filesystem scan, a specific type uses
    the EDT API, an UNKNOWN type -> "Unknown metadata type: <x>. Supported: ...".
  - objectName / nameFilter are filters: a non-matching value is NOT an error,
    it yields "No modules found." (so that is a happy call, not a negative).

Fixture (TestConfiguration) modules actually on disk:
  CommonModules/Error/Module.bsl   (CommonModule, type Module)
  CommonModules/OK/Module.bsl      (CommonModule, type Module)
  Configuration/ManagedApplicationModule.bsl
Catalog.Catalog / CommonForm.Form have .mdo but no .bsl, so they do not appear.

A read tool must never mutate the project: every test ends with assert_no_diff().
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_modules", kind="read")
def test_default_all_lists_a_real_module_and_does_not_mutate():
    # Default metadataType=all -> filesystem scan. The Error common module's .bsl
    # is committed in the fixture, so its module path MUST appear. If the tool
    # were a no-op or scanned nothing, this exact path would be absent and fail.
    r = call("list_modules", {"projectName": PROJECT})
    assert_ok(r, "list_modules all")
    assert_contains(r.text, "CommonModules/Error/Module.bsl",
                    "default 'all' scan must list the committed Error module")
    assert_contains(r.text, "CommonModules/OK/Module.bsl",
                    "default 'all' scan must list the committed OK module")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_modules", kind="read")
def test_filter_commonmodules_lists_both_with_parent_type():
    # metadataType=commonModules uses the EDT API path (not the filesystem scan),
    # exercising a different code branch than the 'all' default. Both common
    # modules and the CommonModule parent-type label must be present.
    r = call("list_modules", {"projectName": PROJECT, "metadataType": "commonModules"})
    assert_ok(r, "list_modules commonModules")
    assert_contains(r.text, "CommonModules/Error/Module.bsl",
                    "commonModules filter must list Error")
    assert_contains(r.text, "CommonModules/OK/Module.bsl",
                    "commonModules filter must list OK")
    assert_contains(r.text, "CommonModule",
                    "commonModules filter must label the parent type CommonModule")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_modules", kind="read")
def test_objectname_filter_narrows_to_one_object():
    # objectName=OK must keep the OK module and drop the Error module. A broken
    # filter that ignored objectName would still list Error -> this fails.
    r = call("list_modules", {"projectName": PROJECT, "objectName": "OK"})
    assert_ok(r, "list_modules objectName=OK")
    assert_contains(r.text, "CommonModules/OK/Module.bsl",
                    "objectName=OK must keep the OK module")
    if "CommonModules/Error/Module.bsl" in (r.text or ""):
        # The filter let a non-matching object through.
        raise AssertionError(
            "objectName=OK must exclude the Error module; got:\n" + (r.text or "")[:400])
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_modules", kind="read")
def test_namefilter_substring_excludes_nonmatching_modules():
    # nameFilter is a case-insensitive substring on the module PATH. "/ok/"
    # matches only the OK module path; the Error path must be filtered out.
    r = call("list_modules", {"projectName": PROJECT, "nameFilter": "/ok/"})
    assert_ok(r, "list_modules nameFilter=/ok/")
    assert_contains(r.text, "CommonModules/OK/Module.bsl",
                    "nameFilter '/ok/' must keep the OK module")
    if "CommonModules/Error/Module.bsl" in (r.text or ""):
        raise AssertionError(
            "nameFilter '/ok/' must exclude the Error module; got:\n" + (r.text or "")[:400])
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_modules", kind="read")
def test_objectname_no_match_reports_empty_not_error():
    # A non-existent objectName is a filter that matches nothing -> NOT an error,
    # the tool reports an empty result. (Documents the tool's real semantics.)
    r = call("list_modules",
             {"projectName": PROJECT, "objectName": "NoSuchObject_zzz"})
    assert_ok(r, "list_modules objectName no-match is a success with empty result")
    assert_contains(r.text, "No modules found.",
                    "a non-matching objectName must yield an empty-result notice")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_modules", kind="read")
def test_missing_projectname_errors_clearly():
    # Required param omitted -> "projectName is required".
    r = call("list_modules", {})
    e = assert_error(r, "missing required projectName")
    # AUDIT: the message names the missing param but offers no next step (which
    # sibling tool to call, e.g. list_projects, to discover a valid project).
    # Keeping suggests=[] rather than weakening to a fake suggestion.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="list_modules", kind="read")
def test_nonexistent_project_errors_clearly():
    # A project name that does not exist -> "Project not found: <name>".
    bad = "NoSuchProject_zzz"
    r = call("list_modules", {"projectName": bad})
    e = assert_error(r, "non-existent project")
    assert_contains(e, "not found", "error must say the project was not found")
    # AUDIT: the message names the bad project but does not point to a sibling
    # tool (list_projects) to enumerate valid project names. suggests=[] kept
    # honest; this is a fix-card to make the not-found error actionable.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="list_modules", kind="read")
def test_unknown_metadatatype_errors_with_supported_list():
    # metadataType is an effective enum; an unknown value hits the switch default
    # -> "Unknown metadata type: <x>. Supported: all, documents, catalogs,
    # commonModules, ...". This error names the bad value AND lists the valid
    # enum members, which IS an actionable next step.
    bad = "nosuchtype"
    r = call("list_modules", {"projectName": PROJECT, "metadataType": bad})
    e = assert_error(r, "unknown metadataType enum value")
    assert_error_quality(e, names=[bad], suggests=["commonModules"],
                         ctx="unknown metadataType")
    assert_no_diff("a rejected call must not touch the project on disk")

"""
e2e tests for get_metadata_objects (kind: read).

Read tool: returns a MARKDOWN table (Name, Synonym, Comment, Type, ObjectModule,
ManagerModule) of the configuration's metadata objects, with optional filtering by
metadataType / nameFilter. ResponseType is MARKDOWN, so the payload is in r.text
(NOT r.structured).

Happy paths assert that real fixture objects appear in the table; every test ends
with assert_no_diff() because a read tool must never mutate the project on disk.

Negative matrix targets the tool's REAL execute() error paths:
  - missing required projectName  -> "projectName is required"  (JsonUtils.requireArgument)
  - non-existent project          -> "Project not found: <name>"
  - invalid metadataType enum     -> "Unknown metadata type: <type>. Supported categories ..."

issue #289: metadataType now ALSO accepts a standard type-name token (the FQN form, as
used elsewhere in the API - English or Russian, singular or plural, e.g. "CommonModule",
"Справочник"), resolved via the shared MetadataTypeUtils resolver, IN ADDITION TO the
legacy lowercase-plural category tokens (documents/catalogs/.../scheduledJobs) which stay
supported for back-compat. Separately, an AI naturally sending an undeclared
types=["ScheduledJob"] array (instead of the declared metadataType) is now caught and
rejected with an actionable error instead of being silently ignored.

Fixture inventory used (TestConfiguration, English Names):
  Catalog.Catalog, CommonModule.Error, CommonModule.OK,
  CommonForm.Form (CommonForm has no listed metadataType filter), Subsystem.Subsystem,
  CommonAttribute.CommonAttribute, SessionParameter.SessionParameter.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_objects", kind="read")
def test_lists_catalog_and_does_not_mutate():
    # Default metadataType ('all') must enumerate the configuration; the fixture's
    # Catalog "Catalog" is a real object that MUST appear with its Catalog type.
    r = call("get_metadata_objects", {"projectName": PROJECT})
    assert_ok(r, "get_metadata_objects all")
    # Name column value — present iff the tool actually walked config.getCatalogs().
    assert_contains(r.text, "Catalog", "the 'all' listing must include the fixture Catalog")
    # Type token proves the row was built (not just an echo of our project name).
    assert_contains(r.text, "CommonModule",
                    "the 'all' listing must include common modules with their Type")
    # A BASE configuration keeps the original columns: the Origin column (and its
    # extension-only labels) is appended ONLY for an extension project, so a base
    # listing must NOT carry it (extension-origin coverage: test_extension_coverage).
    assert_not_contains(r.text, "| Origin |",
                        "a base configuration listing must not gain the extension Origin column")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_filter_commonmodules_returns_both_fixture_modules():
    # metadataType=commonModules must return ONLY common modules; the fixture has
    # exactly two: "Error" and "OK". Both Names must appear -> proves the collector
    # ran and the filter selected the right family.
    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "commonModules"})
    assert_ok(r, "get_metadata_objects commonModules")
    assert_contains(r.text, "Error", "commonModules filter must list CommonModule 'Error'")
    assert_contains(r.text, "OK", "commonModules filter must list CommonModule 'OK'")
    # The filter narrative is echoed in the markdown header — proves filtering applied.
    assert_contains(r.text, "commonModules", "header should report the active filter")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_namefilter_narrows_results():
    # nameFilter is a case-insensitive partial match. "rror" matches CommonModule
    # "Error" but NOT "OK" -> if filtering were broken (ignored), "OK" would leak in.
    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "commonModules", "nameFilter": "rror"})
    assert_ok(r, "get_metadata_objects nameFilter=rror")
    assert_contains(r.text, "Error", "nameFilter 'rror' must keep CommonModule 'Error'")
    # Mutation guard: a broken (ignored) filter would still emit the 'OK' module row.
    # The 'OK' Name appears in the table only as its own row's first cell; assert the
    # row marker for OK is absent. The OK module has an empty module file -> its row,
    # if present, reads "| OK |". Its absence is the discriminating signal.
    assert "| OK " not in r.text, \
        "nameFilter 'rror' must EXCLUDE CommonModule 'OK' (filter must actually filter)"
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_filter_by_english_type_name_token_returns_only_that_type():
    # issue #289 fix: metadataType now ALSO accepts a standard type-name token (the FQN
    # form, e.g. "CommonModule"), not just the legacy category token ("commonModules").
    # Must behave identically to test_filter_commonmodules_returns_both_fixture_modules -
    # both resolve to the same internal category via MetadataTypeUtils.
    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "CommonModule"})
    assert_ok(r, "get_metadata_objects metadataType=CommonModule (type-name token)")
    assert_contains(r.text, "Error", "type-name filter must list CommonModule 'Error'")
    assert_contains(r.text, "OK", "type-name filter must list CommonModule 'OK'")
    # Mutation guard: a broken (ignored) filter would leak in the fixture Catalog row -
    # the ONLY object in the fixture whose Name/Type is "Catalog", so its total absence
    # proves the filter narrowed to common modules only.
    assert_not_contains(r.text, "Catalog",
                        "CommonModule type-name filter must EXCLUDE the fixture Catalog")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_filter_by_russian_type_name_token_returns_only_that_type():
    # Bilingual side of the same fix: the Russian singular "Справочник" (Catalog) must
    # resolve to the same category as the English "Catalog"/"catalogs" token, via the
    # shared MetadataTypeUtils resolver (not a second hand-rolled Russian table).
    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "Справочник"})
    assert_ok(r, "get_metadata_objects metadataType=Справочник (Russian type-name token)")
    assert_contains(r.text, "Catalog", "Russian type-name filter must list the fixture Catalog")
    # Mutation guard: a broken (ignored) filter would leak in CommonModule rows.
    assert_not_contains(r.text, "CommonModule",
                        "Russian type-name filter must EXCLUDE common modules")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (mandatory)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_objects", kind="read")
def test_missing_projectname_errors_clearly():
    # Required param omitted -> JsonUtils.requireArgument -> "projectName is required".
    r = call("get_metadata_objects", {})
    err = assert_error(r, "missing required projectName")
    # AUDIT: the message names the missing param but offers NO next step (no mention
    # of list_projects to discover a valid project name). Keep suggests=[] and flag it.
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_nonexistent_project_errors_and_names_value():
    # Resolves to ToolResult.error("Project not found: <name>").
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_metadata_objects", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # AUDIT: error names the bad project but is NOT actionable — it does not point at
    # list_projects (the sibling tool that enumerates valid project names). suggests=[]
    # is deliberate; this is a fix-card to add a next-step hint to the message.
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_invalid_metadatatype_enum_errors_actionably():
    # metadataType accepts EITHER a category token OR a standard type-name token; a
    # value recognized as NEITHER -> "Unknown metadata type: <type>. Supported
    # categories (case-insensitive): all, documents, catalogs, ...". This error IS
    # actionable: it enumerates the valid category values.
    bad = "bogusType_e2e"
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": bad})
    err = assert_error(r, "invalid metadataType enum")
    # Names the bad value AND points at a valid alternative ('catalogs' is one of the
    # listed supported values) -> the message is genuinely actionable.
    assert_error_quality(err, names=[bad], suggests=["catalogs"],
                         ctx="invalid metadataType names value and lists valid ones")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_unrecognized_type_name_token_still_errors():
    # A value that IS a metadata type name MetadataTypeUtils recognizes, but that this
    # tool has NO collector for (e.g. Subsystem - see SUPPORTED_CATEGORIES), must still
    # fall through to the actionable "Unknown metadata type" error, same as a totally
    # bogus value - not silently succeed with an empty/wrong result.
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "Subsystem"})
    err = assert_error(r, "recognized-but-uncollected type name")
    assert_error_quality(err, names=["Subsystem"], suggests=["catalogs"],
                         ctx="uncollected type name still names the value and lists valid ones")
    assert_no_diff("an invalid call must not touch the project on disk")

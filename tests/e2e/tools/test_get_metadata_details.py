"""
e2e tests for get_metadata_details (kind: read).

The tool resolves an array of FQNs against a project's configuration and renders
their properties as MARKDOWN (ResponseType.MARKDOWN -> assert on Result.text, not
Result.structured). It is a pure read: it must never touch the project on disk.

Two distinct error channels exist and are tested separately:

  * WHOLE-CALL errors (server sets isError via ToolResult.error): a missing
    `projectName`, a missing/empty `objectFqns`, or a non-existent project.
    These are the `assert_error` + `assert_error_quality` negative matrix.

  * PER-OBJECT resolution failures are NOT whole-call errors. A bad/non-existent
    FQN does NOT set isError; the call SUCCEEDS and the bad FQN is reported in a
    dedicated `## Errors` markdown table (FQN | Status | Reason) in the success
    body (GetMetadataDetailsTool.formatFailures / describeResolutionFailure). We
    assert that in-band channel explicitly: it is the tool's real contract, and a
    regression that promoted a per-object miss to a whole-call failure (or that
    silently dropped the failures table) must make these tests FAIL.

The tool has no XOR / conditional / enum parameters: `projectName` and
`objectFqns` are both unconditionally required, `full` is an optional boolean,
and an unknown `language` is silently tolerated (MetadataLanguageUtils falls back
to any non-empty synonym) -- so there is no invalid enum/combination to test.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="read")
def test_basic_details_for_catalog_and_no_mutation():
    # Catalog.Catalog is a real fixture object (TestConfiguration/src/Catalogs/Catalog).
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": ["Catalog.Catalog"],
    })
    assert_ok(r, "get_metadata_details Catalog.Catalog (basic)")
    # The main header is "## <Type>: <Name>" (AbstractMetadataFormatter.addMainHeader);
    # the Basic Properties section always emits a Name row with the object's Name.
    # Both depend on the object having actually resolved -- a broken resolver
    # (returning nothing, or the wrong object) would not produce this.
    assert_contains(r.text, "Catalog: Catalog", "main header for the resolved Catalog object")
    assert_contains(r.text, "Basic Properties", "basic-properties section must render")
    # Every object footers its ORIGIN. In a BASE configuration that is always "core"
    # (extension-adopted/own labels are exercised in test_extension_coverage). A
    # regression that dropped the origin footer, or mislabelled a base object, fails.
    assert_contains(r.text, "**Origin:** core", "a base object must be tagged Origin: core")
    # A resolved object must NOT appear in the failures table.
    if "## Errors" in r.text:
        raise AssertionError("Catalog.Catalog should resolve, but a ## Errors section was emitted:\n" + r.text[:400])
    assert_no_diff("get_metadata_details is read-only; must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_full_mode_emits_more_than_basic():
    # full:true triggers the dynamic-reflection dump on top of the basic header.
    # The full-only section "### All Properties" (verified live against the real
    # tool) appears in full mode and is ABSENT from basic mode. A no-op full flag
    # would make both outputs identical and fail this.
    common = {"projectName": PROJECT, "objectFqns": ["Catalog.Catalog"]}
    r_basic = call("get_metadata_details", dict(common, full=False))
    r_full = call("get_metadata_details", dict(common, full=True))
    assert_ok(r_basic, "get_metadata_details Catalog.Catalog (basic)")
    assert_ok(r_full, "get_metadata_details Catalog.Catalog (full)")
    assert_contains(r_full.text, "Catalog: Catalog", "full mode still renders the main header")
    # Full-only reflected section (real header is "### All Properties", plus the
    # "### Attributes" table). Basic mode must NOT emit it.
    assert_contains(r_full.text, "### All Properties", "full mode must add the reflected properties section")
    assert_not_contains(r_basic.text, "### All Properties", "basic mode must NOT emit the full-only section")
    assert_no_diff("full-mode read must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_russian_type_token_resolves_to_same_object():
    # The FQN type token is bilingual: "Справочник" (Russian for Catalog) must
    # resolve to the SAME object as "Catalog" (the object Name itself is never
    # translated -- it stays "Catalog"). MetadataTypeUtils.toEnglishSingular maps
    # the Russian type token before lookup. Справочник = "Справочник".
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": ["Справочник.Catalog"],
    })
    assert_ok(r, "get_metadata_details with Russian type token")
    # Renders as the English type + the (untranslated) Name -> "Catalog: Catalog".
    assert_contains(r.text, "Catalog: Catalog", "Russian type token must resolve to the Catalog object")
    if "## Errors" in r.text:
        raise AssertionError("Russian type token should resolve, but got a ## Errors section:\n" + r.text[:400])
    assert_no_diff("bilingual read must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_multiple_fqns_one_valid_one_missing_split_into_two_channels():
    # A batch with one resolvable and one non-existent FQN must render the good one
    # as data AND list the bad one in the ## Errors table -- a SUCCESS, not a
    # whole-call error. This is the tool's two-channel contract: a per-object miss
    # never poisons the whole call.
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": ["Catalog.Catalog", "Catalog.NoSuchCatalog"],
    })
    assert_ok(r, "batch with one valid + one missing FQN must still succeed")
    assert_contains(r.text, "Catalog: Catalog", "the valid object renders as data")
    assert_contains(r.text, "## Errors", "the missing object goes into the failures section")
    assert_contains(r.text, "Catalog.NoSuchCatalog", "the failures table must name the bad FQN")
    assert_contains(r.text, "Object not found", "the failure reason for a non-existent object")
    assert_no_diff("read with a partial miss must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# In-band per-object failure channel (call SUCCEEDS, failure reported as data)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="read")
def test_nonexistent_object_reported_in_errors_table_not_as_whole_call_error():
    bad = "Catalog.DefinitelyDoesNotExist"
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": [bad],
    })
    # Contract: a single non-existent FQN is a per-object failure, NOT isError.
    assert_ok(r, "a non-existent FQN is reported in-band, not as a whole-call error")
    assert_contains(r.text, "## Errors", "non-existent FQN must produce the ## Errors section")
    assert_contains(r.text, bad, "the failures table must name the missing FQN")
    assert_contains(r.text, "Object not found", "the reason must say the object was not found")
    # The reason is actionable: it names the discovery tool (get_metadata_objects) to
    # obtain a valid FQN, matching the sibling tools' "Object not found" guidance.
    assert_contains(r.text, "get_metadata_objects", "the reason must point at a discovery next-step")
    assert_no_diff("a lookup miss must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_malformed_fqn_without_dot_reported_with_format_hint():
    bad = "JustAName"  # no "Type.Name" separator -> resolveObject returns null
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": [bad],
    })
    assert_ok(r, "a malformed FQN is reported in-band, not as a whole-call error")
    assert_contains(r.text, "## Errors", "malformed FQN must produce the ## Errors section")
    assert_contains(r.text, bad, "the failures table must name the malformed FQN")
    # This reason IS actionable: it states the expected format and an example.
    assert_contains(r.text, "Type.Name", "the reason must state the expected FQN format")
    assert_no_diff("a malformed FQN must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Whole-call error matrix (server sets isError)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="read")
def test_missing_project_name_is_error():
    r = call("get_metadata_details", {
        # projectName omitted on purpose
        "objectFqns": ["Catalog.Catalog"],
    })
    e = assert_error(r, "missing required projectName")
    # JsonUtils.requireArgument -> "projectName is required".
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    # AUDIT: "projectName is required" names the missing param and says it is
    # required, but does not point at a discovery tool (list_projects) to obtain a
    # valid value. Weakly actionable. Fix-card: mention list_projects.
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_missing_object_fqns_is_error():
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        # objectFqns omitted on purpose
    })
    e = assert_error(r, "missing required objectFqns")
    # GetMetadataDetailsTool: "objectFqns is required (array of FQNs like 'Catalog.Products')".
    # The message names the param AND shows the expected shape -> actionable.
    assert_error_quality(e, names=["objectFqns"], suggests=["required", "Catalog.Products"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_empty_object_fqns_array_is_error():
    # An explicitly empty array is the same failure as omitting it: extractArrayArgument
    # yields null/empty -> the "objectFqns is required" guard fires.
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": [],
    })
    e = assert_error(r, "empty objectFqns array")
    assert_error_quality(e, names=["objectFqns"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("get_metadata_details", {
        "projectName": bogus,
        "objectFqns": ["Catalog.Catalog"],
    })
    e = assert_error(r, "non-existent project")
    # ProjectContext.exists() == false -> ProjectContext.notFoundMessage: "Project not
    # found: <name>. Use list_projects to see available projects." Names the value AND
    # points at the discovery tool.
    assert_error_quality(e, names=[bogus], suggests=["not found", "list_projects"])
    assert_no_diff("a rejected call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# FORM structure — a form FQN renders the form's ENRICHED structure
# (folds get_form_structure; FormStructureReader.render adds visibility / dataPath /
# per-kind extras to the items outline, Main / SavedData flags to the Attributes table,
# and a new Event handlers section)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_form_fqn_renders_structure():
    # A managed-form FQN (Type.Object.Form.FormName) renders its ENRICHED STRUCTURE: items
    # outline (now with visibility / dataPath / per-kind extras), an Attributes table (now
    # carrying Main / SavedData flags) and a NEW Event handlers section -- the enrichment folded
    # into get_metadata_details by FormStructureReader.render. Seed an attribute + command,
    # then read the whole form back by its FQN.
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.GMDFAttr"})
    assert_ok(r1, "seed form attribute")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command.GMDFCmd"})
    assert_ok(r2, "seed form command")
    wait_for_project_ready()
    r = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(r, "get_metadata_details on a managed-form FQN")
    assert_contains(r.text, "Form Structure", "must render the form-structure heading")
    assert_contains(r.text, "## Attributes", "must render the Attributes section")
    assert_contains(r.text, "GMDFAttr", "must list the seeded form attribute")
    assert_contains(r.text, "GMDFCmd", "must list the seeded form command")
    # Enriched markers (render). The auto-generated ItemForm binds its fields to the
    # object (e.g. Object.Code / Object.Description), so the items outline now exposes their
    # dataPath. A no-op enrichment (the former plain render) would lack all three. Because the
    # dataPath marker depends on the auto-generated form actually binding a field, also assert a
    # marker the enriched render ALWAYS emits for any form (the enriched Attributes header row).
    assert_contains(r.text, "dataPath", "the enriched items outline must expose item dataPath")
    # The Attributes table gained the Main / SavedData flag columns: assert the enriched header row
    # rather than bare "Main" / "SavedData" substrings (which could match anywhere in the body).
    assert_contains(r.text, "| Main | SavedData |", "the enriched Attributes header must carry Main / SavedData columns")
    # A new, always-rendered Event handlers section (empty-section convention emits the header
    # even when the form has no handlers).
    assert_contains(r.text, "Event handlers", "the enriched structure must add an Event handlers section")


@e2e_test(tool="get_metadata_details", kind="read")
def test_common_form_fqn_renders_structure():
    # A CommonForm FQN (2-part) also renders its structure (no mutation: pure read of an existing form).
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["CommonForm.Form"]})
    assert_ok(r, "get_metadata_details on a CommonForm FQN")
    assert_contains(r.text, "Form Structure", "a CommonForm FQN must render the form structure")
    assert_contains(r.text, "## Items", "must render the Items section")
    # The enriched renderer (FormStructureReader.render) always emits the Event handlers section
    # header, even for a form that declares no handlers (empty-section convention).
    assert_contains(r.text, "Event handlers", "the enriched structure must add an Event handlers section")


# ──────────────────────────────────────────────────────────────────────────────
# FORM-MEMBER assignable schema — a form GROUP FQN (assignable:true) lists the
# layout props nested in <extInfo> (issue #235). A form member is NOT an mdclass
# node, so the assignable view used to fail with "Object not found"; it now routes
# the FQN through modify_metadata's form resolver and renders the element's own
# features UNION its extInfo's layout props (the general reflective extInfo path).
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_assignable_on_form_group_lists_extinfo_layout_props():
    # Seed a UsualGroup on the fixture form, then read its assignable schema.
    grp = "GMDAsgGrp"
    r0 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group." + grp})
    assert_ok(r0, "seed form group " + grp)
    wait_for_project_ready()

    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": ["Catalog.Catalog.Form.ItemForm.Group." + grp],
        "assignable": True,
    })
    assert_ok(r, "assignable schema for a form-group FQN")
    # Regression guard for the reported bug: a valid form-group FQN must resolve via the form
    # resolver, NOT be rejected as an unresolvable mdclass node.
    assert_not_contains(r.text, "Object not found",
        "a valid form-group FQN must not fail as 'Object not found'")
    if "## Errors" in r.text:
        raise AssertionError(
            "the form group should resolve, but a ## Errors section was emitted:\n" + r.text[:400])
    assert_contains(r.text, "Assignable properties",
        "assignable mode must render the schema heading for a form member")
    # The UsualGroupExtInfo layout props live INSIDE <extInfo>; the general reflective extInfo path
    # now surfaces them. `group` (the grouping layout enum) and `united` are the canonical two
    # from the issue. Assert the table CELLS (| group |) so the FQN heading's "Group" cannot match.
    assert_contains(r.text, "| group |",
        "the extInfo layout 'group' enum must be listed as assignable")
    assert_contains(r.text, "| united |",
        "the extInfo 'united' layout flag must be listed as assignable")
    # (No assert_no_diff here: the test intentionally SEEDS the group, so the tree is dirty; the
    # pure-read nature of the assignable view is covered by the mdclass assignable read tests.)


# ──────────────────────────────────────────────────────────────────────────────
# TEMPLATE Data Composition Schema (СКД) structure — a template FQN whose content is a
# DataCompositionSchema renders the schema's STRUCTURE (issue #267): data sources, data sets
# (with the FULL query text in a fenced block + a fields table), calculated fields, parameters,
# and (skipped here — the write side has no way to author them yet) the default settings variant.
# A template whose content is NOT a DataCompositionSchema (a SpreadsheetDocument print form) is
# UNCHANGED: it still renders the generic object's basic info, never the DCS structure.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_dcs_template_fqn_renders_schema_structure():
    # Seed a fresh Report (the fixture ships none) and author its Data Composition Schema via
    # modify_metadata's `dcs` payload (#241/#267) — a query data set with an explicit query text +
    # field, a calculated field, and an untyped parameter. The FIRST `dcs` write find-or-creates the
    # report's main DCS template under the platform-default name (ОсновнаяСхемаКомпоновкиДанных).
    report = "GMDDcsReport"
    fqn = "Report." + report
    r0 = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r0, "seed report " + fqn)
    wait_for_project_ready()

    query_marker = "GMDDcsSource"                     # a query-only table alias
    query = "SELECT " + query_marker + ".Ref AS Ref FROM Catalog.Catalog AS " + query_marker
    field_path = "GMDDcsFieldRef"                      # authored dataset field dataPath
    calc_path = "GMDDcsMargin"                         # calculated field dataPath
    calc_expr = "GMDDcsRevenue - GMDDcsCost"           # calculated field expression
    parameter = "GMDDcsParam"                          # schema parameter name

    r1 = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "dcs": {
            "dataSets": [{
                "name": "DataSet1",
                "type": "query",
                "query": query,
                "autoFillFields": False,
                "fields": [{"dataPath": field_path, "title": "GMDFieldTitle"}],
            }],
            "calculatedFields": [{"dataPath": calc_path, "expression": calc_expr, "title": "Margin"}],
            "parameters": [{"name": parameter, "title": "GMDParamTitle"}],
        },
    })
    assert_ok(r1, "author dataSet + calculatedField + parameter on the fresh report")
    wait_for_project_ready()

    # The Cyrillic default DCS template name the platform pre-fills for a report's main schema
    # (matches ModifyMetadataTool.DEFAULT_DCS_TEMPLATE_NAME / EDT's own designer).
    template_name = "ОсновнаяСхема" \
        "КомпоновкиДанных"
    template_fqn = fqn + ".Template." + template_name

    r2 = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": [template_fqn],
    })
    assert_ok(r2, "get_metadata_details on the report's DCS template FQN")
    if "## Errors" in r2.text:
        raise AssertionError(
            "the DCS template FQN should resolve, but a ## Errors section was emitted:\n" + r2.text[:600])
    assert_contains(r2.text, "Data Composition Schema", "must render the DCS structure heading")
    assert_contains(r2.text, "## Data sets", "must render the Data sets section")
    # The FULL query text lands verbatim inside a fenced code block, not a mangled table cell.
    assert_contains(r2.text, "```sql", "the query text must be in a fenced code block")
    assert_contains(r2.text, query, "the FULL query text must be present verbatim")
    assert_contains(r2.text, field_path, "the authored dataset field's dataPath must be listed")
    assert_contains(r2.text, "## Calculated fields", "must render the Calculated fields section")
    assert_contains(r2.text, calc_path, "the calculated field's dataPath must be listed")
    assert_contains(r2.text, calc_expr, "the calculated field's expression must be listed")
    assert_contains(r2.text, "## Parameters", "must render the Parameters section")
    assert_contains(r2.text, parameter, "the schema parameter's name must be listed")
    # (No assert_no_diff: the test intentionally seeds a Report and authors its DCS content, so the
    # tree is dirty by design — kind="write-metadata" resets it after the test.)


@e2e_test(tool="get_metadata_details", kind="read")
def test_non_dcs_template_fqn_renders_basic_info_unchanged():
    # A template FQN whose content is NOT a DataCompositionSchema (the real fixture common template
    # CommonTemplate.PrintForm is a SpreadsheetDocument print form, per test_get_template_screenshot.py)
    # must behave EXACTLY as before #267: no DCS structure, just the generic object's basic info.
    r = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": ["CommonTemplate.PrintForm"],
    })
    assert_ok(r, "get_metadata_details on a non-DCS common template")
    if "## Errors" in r.text:
        raise AssertionError(
            "CommonTemplate.PrintForm should resolve, but a ## Errors section was emitted:\n" + r.text[:400])
    assert_not_contains(r.text, "Data Composition Schema",
        "a non-DCS template must NOT render the DCS structure heading")
    assert_contains(r.text, "PrintForm", "the template's own name must still appear")
    assert_no_diff("a non-DCS template read must not change the project")

"""
e2e tests for validate_query (kind: read).

validate_query parses 1C:Enterprise query (QL) text in the context of a real
EDT project and reports syntax/semantic issues. It is a READ tool: it loads a
throwaway in-memory Xtext resource, validates, and unloads it — it must NEVER
mutate the project on disk, so every test ends with assert_no_diff().

Response type is JSON (ValidateQueryTool.getResponseType() == JSON), so the
semantic payload lands in Result.structured ({valid, dcsMode, errorCount,
warningCount, infoCount, issues}); Result.text is only a bounded digest.
Tool-level failures (ToolResult.error) surface as is_error with the real
message in structuredContent.error (Result.error_text() reads it).

Fixture: TestConfiguration contains the metadata object Catalog.Catalog, so a
query against it must validate clean; a deliberately broken query must validate
DIRTY — that contrast is what proves the tool actually parses rather than
rubber-stamping.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, assert_no_diff_rel, FIXTURE_REL_BY_PROJECT,
    e2e_test, PROJECT, TESTS_PROJECT, assert_diff_contains, read_disk,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="validate_query", kind="read")
def test_valid_query_against_fixture_catalog_is_clean():
    # Catalog.Catalog is a real metadata object in TestConfiguration, so this
    # well-formed query must parse AND resolve with zero errors.
    r = call("validate_query", {
        "projectName": PROJECT,
        "queryText": "SELECT Ref FROM Catalog.Catalog",
    })
    assert_ok(r, "valid query against an existing catalog")

    # The semantic verdict lives in structuredContent (JSON response type).
    assert r.structured is not None, \
        "JSON tool must populate structuredContent; got: %r" % (r.text,)
    # Load-bearing: a clean query must report valid=True with no errors. If the
    # tool were broken (always-invalid, no-op, wrong payload) this fails.
    assert r.structured.get("valid") is True, \
        "expected valid=True for a correct query, got: %r" % (r.structured,)
    assert r.structured.get("errorCount") == 0, \
        "expected errorCount=0 for a correct query, got: %r" % (r.structured,)
    assert r.structured.get("issues") == [], \
        "expected no issues for a correct query, got: %r" % (r.structured,)

    # The digest text mirrors the structured keys (proves the JSON wiring, not
    # a stale 'Done' placeholder).
    assert_contains(r.text, "valid", "success digest must reflect the result keys")

    assert_no_diff("validate_query is read-only; nothing may change on disk")


@e2e_test(tool="validate_query", kind="read")
def test_extension_union_scope_resolves_inherited_field():
    r = call("validate_query", {
        "projectName": TESTS_PROJECT,
        "queryText": "ВЫБРАТЬ Ссылка, Наименование ИЗ Справочник.Catalog",
    })
    assert_ok(r, "extension union scope must resolve inherited fields")
    assert r.structured is not None, \
        "extension validation must return structuredContent; got: %r" % (r.text,)
    assert r.structured.get("validationState") == "VALID", r.structured
    assert r.structured.get("validationComplete") is True, r.structured
    assert r.structured.get("valid") is True, \
        "the inherited field must be valid in the union scope: %r" % (r.structured,)
    assert r.structured.get("issues") == [], \
        "the inherited field must produce no issues: %r" % (r.structured,)
    assert_no_diff("validate_query is read-only; nothing may change on disk")


@e2e_test(tool="validate_query", kind="read")
def test_extension_union_scope_resolves_extension_owned_object():
    r = call("validate_query", {
        "projectName": TESTS_PROJECT,
        "queryText": "ВЫБРАТЬ Ссылка, ExtValue ИЗ Справочник.tests_ExtOnly",
    })
    assert_ok(r, "extension union scope must resolve extension-owned objects")
    assert r.structured is not None, \
        "extension validation must return structuredContent; got: %r" % (r.text,)
    assert r.structured.get("validationState") == "VALID", r.structured
    assert r.structured.get("validationComplete") is True, r.structured
    assert r.structured.get("unresolvedSources") == [], r.structured
    assert any(field.get("name") == "ExtValue"
               for source in r.structured.get("sources", [])
               for field in source.get("availableFields", [])), r.structured
    assert r.structured.get("valid") is True, \
        "the extension-owned object must be valid in the union scope: %r" % (r.structured,)
    assert r.structured.get("issues") == [], \
        "the extension-owned object must produce no issues: %r" % (r.structured,)
    assert r.structured.get("validationScope") == \
        ("Project '%s' (extension; base configuration '%s' requested, availability not verified)"
         % (TESTS_PROJECT, PROJECT)), \
        "validation must qualify the requested base scope: %r" % (r.structured,)
    assert_no_diff("validate_query is read-only; nothing may change on disk")
    assert_no_diff_rel(FIXTURE_REL_BY_PROJECT[TESTS_PROJECT])


@e2e_test(tool="validate_query", kind="read")
def test_extension_union_resolves_own_and_adopted_fields_together_in_both_dialects():
    queries = (
        "SELECT E.Ref, E.ExtValue, B.Description "
        "FROM Catalog.tests_ExtOnly AS E "
        "LEFT JOIN Catalog.Catalog AS B ON E.ExtValue = B.Description",
        "ВЫБРАТЬ E.Ссылка, E.ExtValue, B.Наименование "
        "ИЗ Справочник.tests_ExtOnly КАК E "
        "ЛЕВОЕ СОЕДИНЕНИЕ Справочник.Catalog КАК B ПО E.ExtValue = B.Наименование",
    )
    for dcs_mode in (False, True):
        for query in queries:
            r = call("validate_query", {
                "projectName": TESTS_PROJECT, "queryText": query, "dcsMode": dcs_mode,
            })
            assert_ok(r, "extension-owned and inherited fields in the same query")
            assert r.structured is not None, "missing structuredContent: %r" % (r.text,)
            assert r.structured.get("validationState") == "VALID", r.structured
            assert r.structured.get("validationComplete") is True, r.structured
            assert r.structured.get("unresolvedSources") == [], r.structured
            assert r.structured.get("valid") is True, r.structured
            assert r.structured.get("issues") == [], r.structured
            assert r.structured.get("dcsMode") is dcs_mode, r.structured
    assert_no_diff()
    assert_no_diff_rel(FIXTURE_REL_BY_PROJECT[TESTS_PROJECT])


@e2e_test(tool="validate_query", kind="read")
def test_extension_owned_object_still_rejects_unknown_field():
    r = call("validate_query", {
        "projectName": TESTS_PROJECT,
        "queryText": "SELECT E.ExtValue, E.NoSuchField713 FROM Catalog.tests_ExtOnly AS E",
    })
    assert_ok(r, "semantic errors are returned as validation issues")
    assert r.structured is not None, "missing structuredContent: %r" % (r.text,)
    assert r.structured.get("validationState") == "INVALID", r.structured
    assert r.structured.get("validationComplete") is True, r.structured
    assert r.structured.get("valid") is False, r.structured
    errors = [issue for issue in r.structured.get("issues", []) if issue.get("severity") == "ERROR"]
    assert len(errors) == 1, "only the unknown field must fail: %r" % (r.structured,)
    assert_contains(errors[0].get("message", ""), "NoSuchField713")
    assert_no_diff()
    assert_no_diff_rel(FIXTURE_REL_BY_PROJECT[TESTS_PROJECT])


@e2e_test(tool="validate_query", kind="read")
def test_extension_union_scope_rejects_unknown_borrowed_field():
    r = call("validate_query", {
        "projectName": TESTS_PROJECT,
        "queryText": "ВЫБРАТЬ НетТакогоПоля ИЗ Справочник.Catalog",
    })
    assert_ok(r, "extension union scope must still run semantic validation")
    assert r.structured is not None, \
        "extension validation must return structuredContent; got: %r" % (r.text,)
    assert r.structured.get("valid") is False, \
        "an unknown field must be invalid in the union scope: %r" % (r.structured,)
    issues = r.structured.get("issues") or []
    assert any("not found" in str(issue.get("message", "")).lower() for issue in issues), \
        "the unknown field must produce a 'not found' issue: %r" % (issues,)
    assert_no_diff("validate_query is read-only; nothing may change on disk")


@e2e_test(tool="validate_query", kind="read")
def test_broken_query_is_reported_invalid_with_issues():
    # A query with a syntax error ("SELCT" + an empty FROM source) must be
    # detected: the call itself SUCCEEDS (reporting problems is the tool's job),
    # but the verdict must be invalid with at least one ERROR issue. This is the
    # mutation-sensitive counterpart to the clean case: a tool that always
    # answered valid=True would pass the happy test but FAIL here.
    r = call("validate_query", {
        "projectName": PROJECT,
        "queryText": "SELCT FROM",
    })
    assert_ok(r, "validate_query must succeed even when the query is malformed")

    assert r.structured is not None, \
        "JSON tool must populate structuredContent; got: %r" % (r.text,)
    assert r.structured.get("valid") is False, \
        "expected valid=False for a malformed query, got: %r" % (r.structured,)
    assert r.structured.get("errorCount", 0) >= 1, \
        "expected at least one error for a malformed query, got: %r" % (r.structured,)
    issues = r.structured.get("issues") or []
    assert any(i.get("severity") == "ERROR" for i in issues), \
        "expected an ERROR-severity issue for a malformed query, got: %r" % (issues,)

    assert_no_diff("validate_query is read-only; nothing may change on disk")


@e2e_test(tool="validate_query", kind="read")
def test_dcs_mode_flag_is_reflected_in_result():
    # The dcsMode flag is echoed back in the payload; passing it true must be
    # reflected as dcsMode=true (proves the boolean argument is read & honoured,
    # not silently dropped).
    r = call("validate_query", {
        "projectName": PROJECT,
        "queryText": "SELECT Ref FROM Catalog.Catalog",
        "dcsMode": True,
    })
    assert_ok(r, "valid query in DCS mode")
    assert r.structured is not None, \
        "JSON tool must populate structuredContent; got: %r" % (r.text,)
    assert r.structured.get("dcsMode") is True, \
        "dcsMode=true must round-trip into the result, got: %r" % (r.structured,)
    assert_no_diff("validate_query is read-only; nothing may change on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="validate_query", kind="read")
def test_missing_projectname_errors_clearly():
    # projectName is required (JsonUtils.requireArguments). Omitting it must be a
    # machine-detectable error that names the missing parameter.
    r = call("validate_query", {
        "queryText": "SELECT Ref FROM Catalog.Catalog",
    })
    err = assert_error(r, "missing required projectName")
    # AUDIT: the message is exactly "projectName is required" — it names the
    # parameter but gives NO actionable next step (no mention of list_projects
    # or how to discover a valid project name). suggests=[] until the tool's
    # required-arg error is enriched with a next-step hint. -> fix-card.
    assert_error_quality(err, names=["projectName"], suggests=[],
                         ctx="missing projectName names the parameter")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="validate_query", kind="read")
def test_missing_querytext_errors_clearly():
    # Neither queryText nor modulePath is an actionable XOR error.
    r = call("validate_query", {
        "projectName": PROJECT,
    })
    err = assert_error(r, "missing required queryText")
    assert_error_quality(err, names=["queryText", "modulePath"], suggests=["exactly one", "list_modules"],
                         ctx="missing input mode names both choices")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="validate_query", kind="write-metadata")
def test_stored_module_queries_map_diagnostics_and_skip_dynamic_in_both_languages():
    # Runtime-only modules keep deliberate QL errors and their markers out of the shared baseline.
    cases = [
        ("E2EQueriesEn538", "QueryLiterals538", "qUERYlITERALS538", 14,
         'Function QueryLiterals538(Condition) Export\n'
         '\tQueryText = "SELECT\n\t| C.MissingField538\n\t|FROM Catalog.Catalog AS C";\n'
         '\tDynamicText = "SELECT C.Ref FROM Catalog.Catalog AS C" + Condition;\n'
         '\tCaption = "Select file";\n'
         '\tQuotedAlias = "SELECT ""value"" AS Label";\n'
         '\tQuotedFrom = "SELECT ""value"" FROM Catalog.Catalog";\n'
         '\tReturn New Structure("Query,Dynamic,Caption,QuotedAlias,QuotedFrom", QueryText, DynamicText, Caption, QuotedAlias, QuotedFrom);\n'
         'EndFunction\n'),
        ("E2EЗапросы538", "Запросы538", "зАПРОСЫ538", 17,
         'Функция Запросы538(Условие) Экспорт\n'
         '\tТекстЗапроса = "ВЫБРАТЬ\n\t| C.MissingField538\n\t|ИЗ Справочник.Catalog КАК C";\n'
         '\tДинамическийТекст = "ВЫБРАТЬ C.Ссылка ИЗ Справочник.Catalog КАК C" + Условие;\n'
         '\tЗаголовок = "Выбрать файл";\n'
         '\tСтрокаСМеткой = "ВЫБРАТЬ ""значение"" КАК Метка";\n'
         '\tСтрокаИзТаблицы = "ВЫБРАТЬ ""значение"" ИЗ Справочник.Catalog";\n'
         '\tВозврат Новый Структура("Запрос,Динамический,Заголовок,СМеткой,ИзТаблицы", ТекстЗапроса, ДинамическийТекст, Заголовок, СтрокаСМеткой, СтрокаИзТаблицы);\n'
         'КонецФункции\n'),
    ]
    for module, expected_method, requested_method, column, source in cases:
        fqn = "CommonModule." + module
        path = "CommonModules/%s/Module.bsl" % module
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
                  "seed dedicated query module")
        assert_diff_contains("<name>%s</name>" % module, "the dedicated module must be exported")
        listed = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "commonModules", "nameFilter": module})
        assert_ok(listed, "dedicated module model read-back")
        assert_contains(listed.text, "| " + module + " |", "the created module must exist in the model")
        assert_ok(call("write_module_source", {"projectName": PROJECT, "modulePath": path,
                  "mode": "replace", "source": source, "overwrite": True}), "write stored query fixture")
        saved = read_disk("src/" + path)
        # EDT stores BSL with a BOM and CRLF; the text itself must match exactly.
        assert saved.lstrip("﻿").replace("\r\n", "\n") == source, \
            "the exact query fixture must reach disk"
        for scope in [{}, {"method": requested_method}, {"method": requested_method, "dcsMode": True}]:
            r = call("validate_query", {"projectName": PROJECT, "modulePath": path, **scope})
            assert_ok(r, "stored bilingual module queries")
            payload = r.structured
            assert payload and payload["foundCount"] == 3 and payload["skippedCount"] == 2, payload
            query = payload["queries"][0]
            assert query["method"] == expected_method and query["line"] == 2, query
            assert query["column"] == column, query
            assert query["validationState"] == "INVALID" and query["dcsMode"] is scope.get("dcsMode", False), query
            issues = [i for i in query["issues"] if "MissingField538" in i.get("message", "")]
            assert issues and all(i.get("line") == 3 and i.get("queryLine") == 2 for i in issues), query
            skipped = payload["skipped"][0]
            assert skipped["reason"] == "dynamic" and skipped["line"] == 5, skipped
            assert skipped["method"] == expected_method, skipped
            caption = payload["skipped"][1]
            assert caption["reason"] == "no query structure" and caption["line"] == 6, caption
            quoted = payload["queries"][1:]
            assert len(quoted) == 2 and [q["line"] for q in quoted] == [7, 8], quoted
            for projection in quoted:
                assert projection["method"] == expected_method and projection["valid"] is True, projection
                assert projection["validationState"] == "VALID" and projection["validationComplete"] is True, projection
                assert projection["errorCount"] == 0 and projection["dcsMode"] is scope.get("dcsMode", False), projection
            assert read_disk("src/" + path) == saved, "validation must leave the saved module unchanged"


@e2e_test(tool="validate_query", kind="read")
def test_module_input_contract_and_resolution_errors_are_actionable():
    path = "CommonModules/Calc/Module.bsl"
    for args, names, hints in [
        ({"queryText": "SELECT 1", "modulePath": path}, ["queryText", "modulePath"], ["exactly one"]),
        ({"method": "QueryLiterals538"}, ["method", "modulePath"], ["list_modules"]),
        ({"method": "QueryLiterals538", "queryText": "SELECT 1"}, ["method", "modulePath"], ["remove method"]),
        ({"method": " ", "modulePath": path}, ["method"], ["get_module_structure"]),
        ({"modulePath": "CommonModules/Missing538/Module.bsl"}, ["Missing538"], ["list_modules"]),
        ({"modulePath": path, "method": "Missing538"}, ["Missing538"], ["get_module_structure"]),
    ]:
        r = call("validate_query", {"projectName": PROJECT, **args})
        err = assert_error(r, "module input contract")
        assert_error_quality(err, names=names, suggests=hints)
    assert_no_diff("rejected module calls leave the fixture unchanged")


@e2e_test(tool="validate_query", kind="read")
def test_method_without_query_literals_returns_empty_with_querytext_hint():
    r = call("validate_query", {"projectName": PROJECT, "modulePath": "CommonModules/Calc/Module.bsl", "method": "Add"})
    assert_ok(r, "method with no query literals")
    assert r.structured["queries"] == [] and r.structured["skipped"] == [], r.structured
    assert r.structured["foundCount"] == 0 and r.structured["skippedCount"] == 0, r.structured
    assert "no query literal found" in r.structured["message"] and "queryText" in r.structured["message"], r.structured
    assert_no_diff()


@e2e_test(tool="validate_query", kind="read")
def test_nonexistent_project_errors_clearly():
    # A projectName that does not exist must fail with an error that NAMES the
    # bad value (ProjectContext.exists() == false -> "Project not found: <name>").
    bogus = "NoSuchProject_ZZZ"
    r = call("validate_query", {
        "projectName": bogus,
        "queryText": "SELECT Ref FROM Catalog.Catalog",
    })
    err = assert_error(r, "non-existent project")
    # AUDIT: message is "Project not found: <name>" — it names the bad value but
    # does NOT point at a recovery path (e.g. "use list_projects to find a valid
    # project"). suggests=[] until the message becomes actionable. -> fix-card.
    assert_error_quality(err, names=[bogus], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="validate_query", kind="read")
def test_native_dbview_matrix_has_authoritative_bilingual_verdicts():
    cases = [
        ("Catalog.Catalog", "Ref", "Справочник.Catalog", "Ссылка"),
        ("Document.QueryOrder.Items", "Quantity", "Документ.QueryOrder.Items", "Quantity"),
        ("AccumulationRegister.QueryStock.Balance()", "QuantityBalance",
         "РегистрНакопления.QueryStock.Остатки()", "QuantityОстаток"),
        ("AccumulationRegister.QueryStock.Turnovers()", "QuantityTurnover",
         "РегистрНакопления.QueryStock.Обороты()", "QuantityОборот"),
        ("AccumulationRegister.QueryStock.BalanceAndTurnovers()", "QuantityClosingBalance",
         "РегистрНакопления.QueryStock.ОстаткиИОбороты()", "QuantityКонечныйОстаток"),
        ("InformationRegister.QueryPrices.SliceLast()", "Price",
         "РегистрСведений.QueryPrices.СрезПоследних()", "Price"),
    ]
    for en_source, en_field, ru_source, ru_field in cases:
        for source, field, select, from_kw, as_kw in [
            (en_source, en_field, "SELECT", "FROM", "AS"),
            (ru_source, ru_field, "ВЫБРАТЬ", "ИЗ", "КАК"),
        ]:
            for requested, expected in [(field, "VALID"), ("MissingField513", "INVALID")]:
                query = "%s T.%s %s %s %s T" % (select, requested, from_kw, source, as_kw)
                r = call("validate_query", {"projectName": PROJECT, "queryText": query})
                assert_ok(r, query)
                payload = r.structured
                assert payload and payload.get("validationState") == expected, (query, payload)
                assert payload.get("valid") is (expected == "VALID"), (query, payload)
                assert payload.get("validationComplete") is True, (query, payload)
                sources = payload.get("sources")
                assert len(sources) == 1 and sources[0]["resolution"] == "RESOLVED", payload
                fields = sources[0].get("availableFields")
                assert any(field in (f.get("name"), f.get("nameRu")) for f in fields), (query, payload)
                if expected == "INVALID":
                    assert payload.get("errorCount", 0) > 0, payload
                    assert any(i.get("origin") == "validator" and "MissingField513" in i.get("message", "")
                               for i in payload.get("issues", [])), payload
    assert_no_diff("the complete native DBView matrix must leave the fixture clean")


@e2e_test(tool="validate_query", kind="read")
def test_future_source_is_partial_and_independent_error_still_wins():
    for query in ["SELECT F.Ref FROM Catalog.Future513 AS F",
                  "SELECT Ref FROM Catalog.Future513",
                  "ВЫБРАТЬ F.Ссылка ИЗ Справочник.Future513 КАК F"]:
        r = call("validate_query", {"projectName": PROJECT, "queryText": query})
        assert_ok(r, "unavailable future source")
        payload = r.structured
        assert payload.get("validationState") == "PARTIAL", payload
        assert payload.get("valid") is False and payload.get("validationComplete") is False, payload
        assert payload.get("errorCount") == 0, payload
        assert any("Future513" in s.get("source", "") and s.get("reason")
                   for s in payload.get("unresolvedSources", [])), payload
    r = call("validate_query", {"projectName": PROJECT, "queryText":
        "SELECT C.MissingField513, F.Ref FROM Catalog.Catalog AS C "
        "LEFT JOIN Catalog.Future513 AS F ON TRUE"})
    assert_ok(r, "independent error alongside an unresolved source")
    assert r.structured.get("validationState") == "INVALID", r.structured
    assert r.structured.get("validationComplete") is False, r.structured
    assert_no_diff()


@e2e_test(tool="validate_query", kind="read")
def test_fixture_query_corpus_does_not_produce_false_refusals():
    # Exercises parser/scope constructs against deterministic fixture objects, without executing QL.
    corpus = [
        ("SELECT 1 AS Amount", True),
        ("SELECT Ref FROM Catalog.Catalog WHERE Description LIKE &Pattern", False),
        ("SELECT C.Ref, C.Description FROM Catalog.Catalog AS C ORDER BY Description", False),
        ("SELECT COUNT(*) AS Total FROM Document.QueryOrder.Items", False),
        ("SELECT T.QuantityBalance FROM AccumulationRegister.QueryStock.Balance(, Item = &Item) AS T", False),
        ("SELECT T.Price FROM InformationRegister.QueryPrices.SliceLast(, Item = &Item) AS T", False),
        ("SELECT T.Ref FROM (SELECT Ref FROM Catalog.Catalog) AS T", False),
        ("SELECT Ref INTO Temp FROM Catalog.Catalog; SELECT Ref FROM Temp", False),
    ]
    refusals = []
    for query, dcs_mode in corpus:
        r = call("validate_query", {"projectName": PROJECT, "queryText": query, "dcsMode": dcs_mode})
        assert_ok(r, "fixture corpus validation")
        if r.structured.get("validationState") != "VALID":
            refusals.append((query, r.structured))
    assert not refusals, "false refusals in %d fixture queries: %r" % (len(corpus), refusals)
    assert_no_diff()

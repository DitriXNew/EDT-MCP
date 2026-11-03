package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com.google.gson.JsonParser;

public class QueryWriteValidationTest
{
    @Test
    public void schemaBatchAndNestedUnionQueriesUseDcsMode()
    {
        var body = JsonParser.parseString("""
            {"dataSets":[{"name":"Sales", "query":"SELECT 1 AS A"},
              {"name":"Union", "items":[{"name":"Child", "query":"SELECT 2 AS B"}]}],
             "parameters":[{"query":"not a query dataset"}]}
            """).getAsJsonObject();
        var queries = QueryWriteValidation.dcs("schema", body, null);
        assertEquals(2, queries.size());
        assertTrue(queries.stream().allMatch(QueryWriteValidation.Query::dcsMode));
        assertEquals("SELECT 2 AS B", queries.get(1).text());
        assertEquals("body/dataSets/1/items/0/query", queries.get(1).path());
    }

    @Test
    public void datasetPartialUpdateAndDynamicListUseSameGateWithDifferentModes()
    {
        var body = JsonParser.parseString("{\"query\":\"SELECT Ref FROM Catalog.Catalog\"}").getAsJsonObject();
        assertTrue(QueryWriteValidation.dcs("dataSet", body, null).get(0).dcsMode());
        body = JsonParser.parseString("{\"queryText\":\"SELECT Ref FROM Catalog.Catalog\"}").getAsJsonObject();
        assertFalse(QueryWriteValidation.dynamicList("dynamicList", body).get(0).dcsMode());
        assertTrue(QueryWriteValidation.dynamicList("field", body).isEmpty());
    }

    @Test
    public void xmlImportIncludesNestedQueryDatasets()
    {
        var schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        var union = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetUnion();
        var query = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        query.setName("Child");
        query.setQuery("SELECT 1 AS A");
        union.getItems().add(query);
        schema.getDataSets().add(union);
        assertEquals("SELECT 1 AS A", QueryWriteValidation.dcs("schema", null, schema).get(0).text());
    }

    @Test
    public void invalidQueryAndUnavailableServiceRefuseBeforeWrite()
    {
        var query = new QueryWriteValidation.Query("body/query", "bad", true);
        var error = new QueryValidationService.Problem("ERROR", "Field not found", 1, 1, 0,
            "validator", "Field not found", false);
        var invalid = new QueryValidationService.Result(true, List.of(error), List.of(), null);
        for (var verdict : List.of(invalid, QueryValidationService.Result.unavailable(true, "QL unavailable")))
        {
            var result = QueryWriteValidation.validate(List.of(query), q -> verdict);
            assertNotNull(result.errorJson());
            assertTrue(result.errorJson().contains("before mutation"));
            assertTrue(result.errorJson().contains("validate_query"));
            assertTrue(result.errorJson().contains("body/query"));
            assertFalse(result.errorJson().contains("mutationCommitted"));
        }
    }

    @Test
    public void partialForwardAuthoringIsAllowedWithExplicitIncompleteNotice()
    {
        var unresolved = new QueryValidationService.Source(null, "Catalog.Future", "not resolved", List.of(), null);
        var partial = new QueryValidationService.Result(false, List.of(), List.of(unresolved), null);
        var result = QueryWriteValidation.validate(List.of(new QueryWriteValidation.Query("queryText", "future", false)),
            q -> partial);
        assertNull(result.errorJson());
        assertTrue(result.note().contains("PARTIAL"));
        assertTrue(result.note().contains("validationComplete=false"));
        assertTrue(result.results().toString().contains("Catalog.Future"));
    }

    @Test
    public void validQueryProducesCompleteNotice()
    {
        var valid = new QueryValidationService.Result(false, List.of(), List.of(), null);
        var result = QueryWriteValidation.validate(List.of(new QueryWriteValidation.Query("queryText", "SELECT 1", false)),
            q -> valid);
        assertNull(result.errorJson());
        assertTrue(result.note().contains("`VALID`"));
        assertTrue(result.note().contains("validationComplete=true"));
    }
}

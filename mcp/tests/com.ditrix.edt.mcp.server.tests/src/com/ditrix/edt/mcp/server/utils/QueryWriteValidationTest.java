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
            assertEquals(!verdict.complete(), result.errorJson().contains("validate_query"));
            assertTrue(result.errorJson().contains("body/query"));
            assertFalse(result.errorJson().contains("mutationCommitted"));
        }
    }

    @Test
    public void refusalListsLocatedIssuesWithoutSourceCensusAndCapsOnlyTheText()
    {
        var issues = new java.util.ArrayList<QueryValidationService.Problem>();
        for (int i = 0; i < 12; i++) issues.add(new QueryValidationService.Problem("ERROR",
            "Missing field " + i, 2, 14, 8, "validator", "Field not found", false));
        var source = new QueryValidationService.Source(null, "Catalog.Catalog", null,
            List.of(java.util.Map.of("name", "CensusMustStayStructured")), "Table");
        var verdict = new QueryValidationService.Result(true, issues, List.of(source), null);
        var batch = QueryWriteValidation.validate(
            List.of(new QueryWriteValidation.Query("body/query", "SELECT bad", true)), q -> verdict);
        var result = JsonParser.parseString(batch.errorJson()).getAsJsonObject();
        String text = result.get("error").getAsString();
        assertTrue(text, text.contains("body/query"));
        assertTrue(text, text.contains("ERROR: Missing field 0; line 2, column 14"));
        assertTrue(text, text.contains("Missing field 9;"));
        assertFalse(text, text.contains("Missing field 10"));
        assertTrue(text, text.contains("Further issues omitted"));
        assertFalse(text, text.contains("availableFields"));
        assertFalse(text, text.contains("CensusMustStayStructured"));
        assertFalse(text, text.contains("validate_query"));
        var structured = result.getAsJsonArray("queryValidation").get(0).getAsJsonObject();
        assertEquals(12, structured.getAsJsonArray("issues").size());
        assertTrue(structured.toString().contains("CensusMustStayStructured"));

        var registry = com.ditrix.edt.mcp.server.tools.McpToolRegistry.getInstance();
        registry.clear();
        try
        {
            registry.register(new com.ditrix.edt.mcp.server.tools.impl.DcsTool()
            {
                @Override public String execute(java.util.Map<String, String> params) { return batch.errorJson(); }
            });
            var handler = new com.ditrix.edt.mcp.server.protocol.McpProtocolHandler();
            var wire = JsonParser.parseString(handler.processRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"dcs","arguments":{}}}
                """)).getAsJsonObject().getAsJsonObject("result");
            assertTrue(wire.toString(), wire.get("isError").getAsBoolean());
            String content = wire.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
            assertTrue(content, content.contains(text));
            assertTrue(content, content.contains("get_tool_guide"));
            assertFalse(content, content.contains("availableFields"));
            assertEquals(text, wire.getAsJsonObject("structuredContent").get("error").getAsString());
            assertEquals(structured, wire.getAsJsonObject("structuredContent")
                .getAsJsonArray("queryValidation").get(0));
        }
        finally { registry.clear(); }
    }

    @Test
    public void warningsCannotPushTheRefusingErrorBeyondTheTextCap()
    {
        var issues = new java.util.ArrayList<QueryValidationService.Problem>();
        for (int i = 0; i < 20; i++) issues.add(new QueryValidationService.Problem("WARNING",
            "Earlier warning", 1, 1, 0, "validator", null, false));
        issues.add(new QueryValidationService.Problem("ERROR", "The refusing error", 2, 14, 10,
            "validator", null, false));
        var result = QueryWriteValidation.validate(
            List.of(new QueryWriteValidation.Query("dataSets/Sales/query", "bad", true)),
            q -> new QueryValidationService.Result(true, issues, List.of(), null));
        String message = JsonParser.parseString(result.errorJson()).getAsJsonObject().get("error").getAsString();
        assertTrue(message, message.contains("ERROR: The refusing error; line 2, column 14"));
        assertTrue(message, message.indexOf("The refusing error") < message.indexOf("Earlier warning"));
        assertTrue(message.contains("dataSets/Sales/query"));
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

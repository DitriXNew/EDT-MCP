/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Collects submitted query slots, then validates all of them before any writer is entered. */
public final class QueryWriteValidation
{
    private QueryWriteValidation() { }

    public record Query(String path, String text, boolean dcsMode) { }

    public static List<Query> dcs(String type, JsonObject body, EObject imported)
    {
        List<Query> queries = new ArrayList<>();
        if (imported != null)
        {
            var all = imported.eAllContents();
            while (all.hasNext())
            {
                EObject object = all.next();
                if (object instanceof DataCompositionSchemaDataSetQuery)
                {
                    var query = (DataCompositionSchemaDataSetQuery)object;
                    queries.add(new Query("dataSets/" + query.getName() + "/query", query.getQuery(), true));
                }
            }
        }
        else if (body != null)
        {
            if ("dataSet".equals(type)) dataSet(body, "body", queries);
            else if ("schema".equals(type)) dataSets(body.get("dataSets"), "body/dataSets", queries);
        }
        return queries;
    }

    private static void dataSets(JsonElement value, String path, List<Query> queries)
    {
        if (value == null || !value.isJsonArray()) return;
        int index = 0;
        for (JsonElement item : value.getAsJsonArray())
        {
            if (item.isJsonObject()) dataSet(item.getAsJsonObject(), path + "/" + index, queries);
            index++;
        }
    }

    private static void dataSet(JsonObject body, String path, List<Query> queries)
    {
        JsonElement query = body.get("query");
        if (query != null && query.isJsonPrimitive() && query.getAsJsonPrimitive().isString())
            queries.add(new Query(path + "/query", query.getAsString(), true));
        dataSets(body.get("items"), path + "/items", queries);
    }

    public static List<Query> dynamicList(String type, JsonObject body)
    {
        if (!"dynamicList".equals(type) || body == null || !body.has("queryText")
            || !body.get("queryText").isJsonPrimitive()
            || !body.getAsJsonPrimitive("queryText").isString()) return List.of();
        return List.of(new Query("body/queryText", body.get("queryText").getAsString(), false));
    }

    public static Batch validate(IProject project, List<Query> queries)
    {
        return validate(queries, q -> new QueryValidationService().validate(project, q.text(), q.dcsMode()));
    }

    static Batch validate(List<Query> queries,
        java.util.function.Function<Query, QueryValidationService.Result> validator)
    {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Query query : queries)
        {
            QueryValidationService.Result result = validator.apply(query);
            Map<String, Object> value = new LinkedHashMap<>(result.values());
            value.put("path", query.path());
            results.add(value);
            if (!result.available() || result.state() == QueryValidationService.State.INVALID)
            {
                String message = "Query at '" + query.path() + "' was refused before mutation. "
                    + "Run validate_query with the same query and dcsMode=" + query.dcsMode()
                    + ", correct its errors, then retry.";
                return new Batch(ToolResult.error(message).put("queryValidation", results).toJson(), results);
            }
        }
        return new Batch(null, results);
    }

    public record Batch(String errorJson, List<Map<String, Object>> results)
    {
        public String note()
        {
            if (results.isEmpty()) return "";
            boolean partial = results.stream().anyMatch(r -> "PARTIAL".equals(r.get("validationState")));
            if (!partial) return "\n\n**Query validation:** `VALID`; `validationComplete=true`.";
            StringBuilder note = new StringBuilder(
                "\n\n**Query validation:** `PARTIAL`; `validationComplete=false`. "
                    + "Query validation incomplete; the query was saved for forward authoring. "
                    + "Resolve its sources and run validate_query before using it.\n\n");
            note.append(MarkdownUtils.tableHeader("Query", "Source", "Incomplete reason"));
            for (Map<String, Object> result : results)
            {
                if (!(result.get("unresolvedSources") instanceof List<?>)) continue;
                for (Object source : (List<?>)result.get("unresolvedSources"))
                {
                    if (source instanceof Map<?, ?> value)
                        note.append(MarkdownUtils.tableRow(String.valueOf(result.get("path")),
                            String.valueOf(value.get("source")), String.valueOf(value.get("reason"))));
                }
            }
            return note.toString();
        }

        public void addTo(ToolResult result)
        {
            if (!results.isEmpty()) result.put("queryValidation", results);
        }
    }
}

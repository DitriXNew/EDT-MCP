/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TemplateContentToolTest
{
    private final TemplateContentTool tool = new TemplateContentTool();

    @Test
    public void testContract()
    {
        assertEquals("template_content", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('template_content')"));
        JsonObject schema = json(tool.getInputSchema());
        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals(Set.of("method", "fqn", "projectName", "template", "areaName", "offset", "limit"), properties.keySet());
        assertEquals(1, schema.getAsJsonArray("required").size());
        assertEquals("method", schema.getAsJsonArray("required").get(0).getAsString());
        JsonObject payload = properties.getAsJsonObject("template");
        assertEquals(Set.of("text", "mode", "oldText", "expectedHash", "expectedSource", "overwrite",
            "cells", "merges", "areas", "columnWidths", "rowHeights", "unmerges", "removeAreas", "renameAreas"),
            payload.getAsJsonObject("properties").keySet());
        String description = payload.get("description").getAsString();
        assertTrue(description, description.contains("widthWeightFactor? (only with autoWidthCalculation:true)"));
        assertTrue(description, description.contains("Non-empty text clears parameter and vice versa"));
        assertTrue(description, description.contains("but wrap:false restores inherited placement"));
        assertTrue(description, description.contains("Omitted or null cell keys keep their values"));
        assertTrue(json(tool.getOutputSchema()).getAsJsonObject("properties").keySet().containsAll(
            Set.of("success", "method", "action", "help", "fqn", "template", "persisted", "writtenProjects")));
    }

    @Test
    public void testHelpHasNoProjectOrMutationPayload()
    {
        // The pure help branch runs without resolving an EDT project.
        JsonObject result = json(tool.executeOnUiThread(Map.of("method", "help")));
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("help", result.get("method").getAsString());
        assertFalse(result.get("help").getAsString().isBlank());
        assertFalse(result.has("writtenProjects"));
        assertTrue(tool.execute(Map.of("method", "help", "template", "{}")).contains("takes no other parameters"));
    }

    @Test
    public void testInvalidMethodsAndModifyArgumentsRefusedBeforeProjectAccess()
    {
        assertTrue(tool.execute(Map.of()).contains("method='help', method='read' or method='modify'"));
        assertTrue(tool.execute(Map.of("method", "edit")).contains("method='help', method='read' or method='modify'"));
        assertTrue(tool.execute(Map.of("method", "modify")).contains("projectName"));
        for (String value : new String[] {"[]", "{broken", "null", "\"a\""})
        {
            String refusal = tool.execute(Map.of("method", "modify", "projectName", "NoProject",
                "fqn", "CommonTemplate.Print", "template", value));
            assertTrue(refusal, refusal.contains("'template' must be a JSON object"));
        }
        String mixed = tool.execute(Map.of("method", "modify", "projectName", "NoProject",
            "fqn", "CommonTemplate.Print", "template", "{}", "properties", "[]"));
        assertTrue(mixed, mixed.contains("Unknown template_content argument 'properties'"));
    }

    @Test
    public void testReadAndModifyArgumentsCannotBeMixed()
    {
        assertTrue(tool.execute(Map.of("method", "read")).contains("projectName"));
        var args = new java.util.HashMap<>(Map.of("method", "read", "projectName", "NoProject",
            "fqn", "CommonTemplate.Print"));
        for (var invalid : Map.of("offset", "-1", "limit", "201", "areaName", " ", "template", "{}").entrySet())
        {
            args.put(invalid.getKey(), invalid.getValue());
            assertTrue(tool.execute(args).contains(invalid.getKey()));
            args.remove(invalid.getKey());
        }
        args.put("offset", "1e0");
        args.put("limit", "1.0");
        assertNull(tool.validateRequest(args));
        args.put("limit", "1.5");
        assertTrue(tool.validateRequest(args).contains("limit"));
        assertTrue(tool.execute(Map.of("method", "modify", "projectName", "NoProject",
            "fqn", "CommonTemplate.Print", "template", "{}", "areaName", "Header")).contains("areaName"));
    }

    @Test
    public void testLazySpreadsheetContentIsProjectedInsideRollback()
    {
        var doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertFalse(SpreadsheetTemplateWriter.apply(doc, json("""
            {"cells":[{"row":0,"col":1,"parameter":"FullName"}],
             "areas":[{"name":"Header","fromRow":0,"fromCol":0,"toRow":0,"toCol":1}]}
            """)).hasError());
        IBmModel model = mock(IBmModel.class);
        IBmTransaction tx = mock(IBmTransaction.class);
        CommonTemplate template = mock(CommonTemplate.class, withSettings().extraInterfaces(IBmObject.class));
        when(tx.getObjectById(17L)).thenReturn((IBmObject)template);
        when(template.getTemplateType()).thenReturn(TemplateType.SPREADSHEET_DOCUMENT);
        boolean[] active = {false};
        when(template.getTemplate()).thenAnswer(inv -> {
            assertTrue("lazy content needs the rollback transaction", active[0]);
            return doc;
        });
        when(model.executeAndRollback(any())).thenAnswer(inv -> {
            IBmTask<?> task = inv.getArgument(0);
            active[0] = true;
            try { return task.execute(tx, null); }
            finally { active[0] = false; }
        });

        JsonObject response = json(TemplateContentTool.readTemplateContent(model, 17L,
            "CommonTemplate.Print", Map.of("areaName", "header", "limit", "1")));
        assertTrue(response.get("success").getAsBoolean());
        JsonObject page = response.getAsJsonObject("template");
        assertEquals("Header", page.get("areaName").getAsString());
        assertEquals("FullName", page.getAsJsonArray("cells").get(0).getAsJsonObject().get("parameter").getAsString());
        assertFalse(response.has("persisted"));
        assertFalse(response.has("writtenProjects"));
        verify(tx).getObjectById(17L);
        verify(model).executeAndRollback(any());
        verify(model, never()).executeReadonlyTask(any());
        verify(model, never()).execute(any());
    }

    @Test
    public void testWrongTemplateKindRefusalNamesFqnAndKind()
    {
        var template = MdClassFactory.eINSTANCE.createCommonTemplate();
        for (TemplateType type : TemplateType.values())
        {
            template.setTemplateType(type);
            String refusal = TemplateContentTool.nonSpreadsheetTemplateError(template, "CommonTemplate.Print");
            if (type == TemplateType.SPREADSHEET_DOCUMENT)
            {
                assertNull(refusal);
            }
            else
            {
                assertNotNull(refusal);
                assertTrue(refusal, refusal.contains("CommonTemplate.Print"));
                assertTrue(refusal, refusal.contains(type.getName()));
            }
        }
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}

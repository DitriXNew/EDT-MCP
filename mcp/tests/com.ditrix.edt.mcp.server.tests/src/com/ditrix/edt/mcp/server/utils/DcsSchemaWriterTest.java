/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Unit contract for node-addressed schema upsert/update semantics. */
public class DcsSchemaWriterTest
{
    @Test
    public void testUpsertCreatesThenUpdatesNaturalKeyWithoutDuplicate()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result created = apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"Sales\",\"type\":\"query\",\"query\":\"SELECT 1\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result updated = apply(schema, "upsert", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales", "{\"query\":\"SELECT 2\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(created.error(), created.isSuccess());
        assertTrue(updated.error(), updated.isSuccess());
        assertEquals(1, schema.getDataSets().size());
        assertEquals("SELECT 2", query(schema).getQuery()); //$NON-NLS-1$
    }

    @Test
    public void testUpdateRequiresExistingExactNodeAndListsSiblings()
    {
        DataCompositionSchema schema = newSchema();
        apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"Existing\",\"type\":\"query\",\"query\":\"SELECT 1\"}"); //$NON-NLS-1$

        DcsSchemaWriter.Result missing = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Missing", "{\"query\":\"SELECT 2\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(missing.isSuccess());
        assertTrue(missing.error(), missing.error().contains("Missing")); //$NON-NLS-1$
        assertTrue(missing.error(), missing.error().contains("Existing")); //$NON-NLS-1$
        assertTrue(missing.error(), missing.error().contains("upsert")); //$NON-NLS-1$
        assertEquals("SELECT 1", query(schema).getQuery()); //$NON-NLS-1$
        assertEquals(1, schema.getDataSets().size());
    }

    @Test
    public void testUnknownNestedMemberLeavesExistingModelUntouched()
    {
        DataCompositionSchema schema = newSchema();
        apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"Sales\",\"type\":\"query\",\"query\":\"SELECT 1\"}"); //$NON-NLS-1$

        DcsSchemaWriter.Result rejected = apply(schema, "upsert", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales", //$NON-NLS-1$
            "{\"query\":\"SELECT 2\",\"fields\":[{\"dataPath\":\"Amount\",\"titel\":\"Amount\"}]}"); //$NON-NLS-1$

        assertFalse(rejected.isSuccess());
        assertTrue(rejected.error(), rejected.error().contains("titel")); //$NON-NLS-1$
        assertTrue(rejected.error(), rejected.error().contains("Accepted members")); //$NON-NLS-1$
        assertEquals("SELECT 1", query(schema).getQuery()); //$NON-NLS-1$
        assertTrue(query(schema).getFields().isEmpty());
    }

    @Test
    public void testTotalFieldCanBeAuthoredAndPartiallyUpdated()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result created = apply(schema, "upsert", "totalField", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataPath\":\"Amount\",\"expression\":\"Sum(Amount)\",\"groups\":[\"Goods\"]}"); //$NON-NLS-1$
        DcsSchemaWriter.Result updated = apply(schema, "update", "totalField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/totalFields/Amount", "{\"groups\":[\"Warehouse\"]}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(created.error(), created.isSuccess());
        assertTrue(updated.error(), updated.isSuccess());
        assertEquals(1, schema.getTotalFields().size());
        assertEquals("Sum(Amount)", schema.getTotalFields().get(0).getExpression()); //$NON-NLS-1$
        assertEquals(Arrays.asList("Warehouse"), schema.getTotalFields().get(0).getGroups()); //$NON-NLS-1$
    }

    @Test
    public void testBilingualNamesAndSynonymStayDistinctAndCanonical()
    {
        DataCompositionSchema schema = newSchema();
        String russianName = MetadataLanguageUtils.cp(0x041f, 0x0440, 0x043e, 0x0434, 0x0430, 0x0436, 0x0438);
        String russianSynonym = MetadataLanguageUtils.cp(0x0418, 0x043c, 0x044f);
        JsonObject body = json("{\"dataSets\":[{\"name\":\"Sales\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 1\"},{\"name\":\"placeholder\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 2\",\"fields\":[{\"dataPath\":\"Name\",\"title\":{\"EN\":\"Name\"}}]}]}"); //$NON-NLS-1$
        body.getAsJsonArray("dataSets").get(1).getAsJsonObject().addProperty("name", russianName); //$NON-NLS-1$ //$NON-NLS-2$
        body.getAsJsonArray("dataSets").get(1).getAsJsonObject().getAsJsonArray("fields") //$NON-NLS-1$ //$NON-NLS-2$
            .get(0).getAsJsonObject().getAsJsonObject("title").addProperty("RU", russianSynonym); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result result = apply(schema, "upsert", "schema", "Report.Sales", body); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result.error(), result.isSuccess());
        assertEquals("Sales", schema.getDataSets().get(0).getName()); //$NON-NLS-1$
        assertEquals(russianName, schema.getDataSets().get(1).getName());
        DataCompositionSchemaDataSetField field = (DataCompositionSchemaDataSetField)
            ((DataCompositionSchemaDataSetQuery)schema.getDataSets().get(1)).getFields().get(0);
        assertNotNull(field.getTitle().getLocalValue());
        assertEquals("Name", field.getTitle().getLocalValue().getContent().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(russianSynonym, field.getTitle().getLocalValue().getContent().get("ru")); //$NON-NLS-1$
        assertFalse("a synonym is presentation data, not a programmatic natural key", //$NON-NLS-1$
            russianSynonym.equals(schema.getDataSets().get(1).getName()));
    }

    private static DcsSchemaWriter.Result apply(DataCompositionSchema schema, String action, String type,
        String address, String body)
    {
        return apply(schema, action, type, address, json(body));
    }

    private static DcsSchemaWriter.Result apply(DataCompositionSchema schema, String action, String type,
        String address, JsonObject body)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(address);
        assertTrue(parsed.failure() == null ? address : parsed.failure().message(), parsed.isSuccess());
        DcsPresentationParser.LanguageContext languages =
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "ru")); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.PrepareResult prepared =
            DcsSchemaWriter.prepare(action, type, parsed.address(), body, languages);
        assertTrue(prepared.error(), prepared.isSuccess());
        return DcsSchemaWriter.apply(schema, prepared.request(), null);
    }

    private static JsonObject json(String value)
    {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static DataCompositionSchema newSchema()
    {
        return DcsFactory.eINSTANCE.createDataCompositionSchema();
    }

    private static DataCompositionSchemaDataSetQuery query(DataCompositionSchema schema)
    {
        return (DataCompositionSchemaDataSetQuery)schema.getDataSets().get(0);
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;
import com.google.gson.JsonParser;

/** An automatic ScaleColor must be refused before it can produce unreadable Template.dcs XML. */
public class DcsScaleColorStorageTest
{
    @Test
    public void testAutomaticScaleColorIsRefusedButAnExplicitColorReloads() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(OutputParameterCatalogue.GROUP,
            DcsStoredXmlTestSupport.VERSION, "en");
        assertNotNull(catalogue.findItem("ChartType.ScaleColor"));
        var defaultColor = ((ColorValue)catalogue.findItem("ChartType.ScaleColor").defValue()).getValue();
        assertTrue("the designer catalogue stores an explicit default", defaultColor instanceof ColorDef);
        assertEquals(0, ((ColorDef)defaultColor).getRed());
        assertEquals(0, ((ColorDef)defaultColor).getGreen());
        assertEquals(0, ((ColorDef)defaultColor).getBlue());
        var item = DcsStoredXmlTestSupport.SETTINGS.createSettingsParameterValue();
        var body = JsonParser.parseString("{\"parameter\":{\"kind\":\"parameter\",\"value\":\"ChartType.ScaleColor\"},"
            + "\"value\":{\"color\":\"auto\"}}").getAsJsonObject();
        String error = DcsSettingsWriter.applyOutputParameterItemForTest(item, body, null, catalogue);
        assertNotNull("EDT rejects the XML this body used to write", error);
        assertTrue(error, error.contains("ChartType.ScaleColor"));
        assertTrue(error, error.contains("{color:'auto'}"));
        assertTrue(error, error.contains("EDT cannot reload that value"));
        assertTrue(error, error.contains("RGB color"));

        body.add("value", JsonParser.parseString("{\"color\":{\"red\":0,\"green\":0,\"blue\":0}}"));
        assertNull(DcsSettingsWriter.applyOutputParameterItemForTest(item, body, null, catalogue));
        assertTrue(item.getValues().get(0) instanceof ColorValue);
        var schema = DcsStoredXmlTestSupport.schema();
        var group = DcsStoredXmlTestSupport.SETTINGS.createDataCompositionGroup();
        group.setOutputParameters(DcsStoredXmlTestSupport.SETTINGS.createDataCompositionGroupOutputParameterValues());
        group.getOutputParameters().getItems().add(item);
        schema.getDefaultSettings().getItems().add(group);
        var read = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(schema));
        assertNull(DcsStoredXmlTestSupport.guard(read));
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.tools.metadata.UniversalMetadataFormatter;
import com.google.gson.JsonParser;

/** get_metadata_details must render a StyleItem's stored platform ColorRef without resolving it. */
public class StyleItemPlatformColorRenderingTest
{
    @Test
    public void testEveryPlatformSourceRendersItsQualifiedNameInMetadataDetails()
    {
        var catalogue = AppearanceValueBuilderTest.catalogue("v8:/Colors/Test/v8.3.27",
            "Web.Red", "Windows.WindowText", "Style.FieldBackColor");
        String[][] samples = {{"web", "Red", "Web.Red"}, {"windows", "WindowText", "Windows.WindowText"},
            {"style", "FieldBackColor", "Style.FieldBackColor"}};
        for (String[] sample : samples)
        {
            var built = StyleValueBuilder.build(JsonParser.parseString(
                "{color:{" + sample[0] + ":'" + sample[1] + "'}}"),
                StyleValueBuilder.forConfiguration(null, catalogue));
            assertNull(built.error);
            var item = MdClassFactory.eINSTANCE.createStyleItem();
            item.setName("PlatformColor");
            item.setType(built.type);
            item.setValue(built.value);
            String details = UniversalMetadataFormatter.getInstance().format(item, false, "en");
            assertTrue(details, details.contains("| Color | " + sample[2] + " |"));
        }
    }
}

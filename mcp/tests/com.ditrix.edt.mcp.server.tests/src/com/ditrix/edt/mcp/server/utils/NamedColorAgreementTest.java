/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorRef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonParser;

/** Named-color precedence, palette fallback and suggestions are identical across the writers. */
public class NamedColorAgreementTest
{
    @Test
    public void testAllSourcesIncludingPlatformPaletteAgreeWithFormAppearanceAndTheGuard()
    {
        var catalogue = AppearanceValueBuilderTest.catalogue("v8:/Colors/Test/v8.3.27",
            "Style.FieldBackColor", "Palette.Primary", "Web.Red", "Windows.WindowText");
        var resolver = StyleValueBuilder.forConfiguration(null, catalogue);
        String[][] cases = {{"style", "FieldBackColor"}, {"palette", "Primary"}, {"web", "Red"},
            {"windows", "WindowText"}};
        for (String[] sample : cases)
        {
            var body = JsonParser.parseString("{\"color\":{\"" + sample[0] + "\":\"" + sample[1] + "\"}}");
            var form = AppearanceValueBuilder.buildColor(body, null, catalogue);
            var dcs = StyleValueBuilder.build(body, resolver);
            assertNull(form.error);
            assertNull(dcs.error);
            String name = AppearanceValueBuilder.colorReferenceName((ColorRef)form.value);
            assertEquals(name, AppearanceValueBuilder.colorReferenceName((ColorRef)((ColorValue)dcs.value).getValue()));
            assertNull(name, DcsSettingsWriter.typedValueRefusal(McoreFactory.eINSTANCE.createColorValue(),
                dcs.value, null, resolver));
        }
    }

    @Test
    public void testAConfigurationItemShadowsThePlatformEvenWhenItHoldsTheWrongKind()
    {
        var config = MdClassFactory.eINSTANCE.createConfiguration();
        var item = MdClassFactory.eINSTANCE.createStyleItem();
        item.setName("FieldBackColor");
        var color = McoreFactory.eINSTANCE.createStyleColor();
        color.setName("FieldBackColor");
        item.setAppearanceItem(color);
        config.getStyleItems().add(item);
        var catalogue = AppearanceValueBuilderTest.catalogue("v8:/Colors/Test/v8.3.27", "Style.FieldBackColor");
        var body = JsonParser.parseString("{\"color\":{\"style\":\"FieldBackColor\"}}");
        var resolver = StyleValueBuilder.forConfiguration(config, catalogue);
        var dcs = StyleValueBuilder.build(body, resolver);
        assertNull(dcs.error);
        assertSame(color, ((ColorRef)((ColorValue)dcs.value).getValue()).getColor());
        var form = AppearanceValueBuilder.buildColor(body, config, catalogue);
        assertSame(item, form.configurationItem);

        item.setAppearanceItem(McoreFactory.eINSTANCE.createStyleFont());
        form = AppearanceValueBuilder.buildColor(body, config, catalogue);
        dcs = StyleValueBuilder.build(body, resolver);
        assertNotNull("a non-color shadows the platform name too", dcs.error);
        assertEquals(form.error, dcs.error);
        assertTrue(dcs.error, dcs.error.contains("holds no color"));
    }

    @Test
    public void testUnresolvedNamesHaveTheSameBoundedCandidateList()
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 40; i++) names.add("Palette.Color" + i);
        names.add("Palette.BlueAccent");
        var catalogue = AppearanceValueBuilderTest.catalogue("v8:/Colors/Test/v8.3.27", names.toArray(String[]::new));
        var body = JsonParser.parseString("{\"color\":{\"palette\":\"Blue\"}}");
        var form = AppearanceValueBuilder.buildColor(body, null, catalogue);
        var dcs = StyleValueBuilder.build(body, StyleValueBuilder.forConfiguration(null, catalogue));
        assertNotNull(form.error);
        assertEquals(form.error, dcs.error);
        assertTrue(dcs.error, dcs.error.contains("Valid names: Palette.BlueAccent, Palette.Color0"));
        assertTrue(dcs.error, dcs.error.contains("(16 more)"));
    }
}

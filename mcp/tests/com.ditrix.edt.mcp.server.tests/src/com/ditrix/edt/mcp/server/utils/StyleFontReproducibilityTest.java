/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.*;
import com.google.gson.JsonParser;

/** Pins every font member that the DCS/StyleItem font constructor can reproduce. */
public class StyleFontReproducibilityTest
{
    @Test
    public void testTheFontConstructorOutputIsReproducible()
    {
        var result = StyleValueBuilder.build(JsonParser.parseString(
            "{\"font\":{\"faceName\":\"Arial\",\"height\":12,\"bold\":true}}"));
        assertNull(result.error);
        Font font = ((FontValue)result.value).getValue();
        assertNull(StyleValueBuilder.fontRefusal(font));
        FontDef explicit = (FontDef)font;
        explicit.setHeight(0f);
        assertNull(StyleValueBuilder.fontRefusal(explicit));
        explicit.setHeight(10f);
        assertNull(StyleValueBuilder.fontRefusal(explicit));
        var maximum = StyleValueBuilder.build(JsonParser.parseString(
            "{\"font\":{\"height\":2147483647}}"));
        assertNull(maximum.error);
        Font maximumFont = ((FontValue)maximum.value).getValue();
        assertEquals((float)Integer.MAX_VALUE, ((FontDef)maximumFont).getHeight(), 0f);
        assertNull(StyleValueBuilder.fontRefusal(maximumFont));
    }

    @Test
    public void testFractionalNegativeNonFiniteAndOutOfRangeHeightsAreRefused()
    {
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        for (float height : new float[] {10.5f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE,
            Math.nextUp((float)Integer.MAX_VALUE)})
        {
            font.setHeight(height);
            String refusal = StyleValueBuilder.fontRefusal(font);
            assertNotNull("height " + height, refusal);
            assertTrue(refusal, refusal.contains("font height"));
            assertTrue(refusal, refusal.contains("integer height"));
        }
    }

    @Test
    public void testScaleAndInheritedFontFormsAreRefused()
    {
        McoreFactory factory = McoreFactory.eINSTANCE;
        FontDef font = factory.createFontDef();
        font.setScale(90);
        String refusal = StyleValueBuilder.fontRefusal(font);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("font scale 90"));
        for (Font inherited : new Font[] {factory.createFontRef(), factory.createAutoFont(), factory.createStyleFont()})
        {
            refusal = StyleValueBuilder.fontRefusal(inherited);
            assertNotNull(inherited.eClass().getName(), refusal);
            assertTrue(refusal, refusal.contains(inherited.eClass().getName()));
        }
        assertNotNull(StyleValueBuilder.fontRefusal(null));
    }
}

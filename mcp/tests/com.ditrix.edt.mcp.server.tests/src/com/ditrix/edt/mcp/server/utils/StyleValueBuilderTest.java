/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.AutoColor;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com.ditrix.edt.mcp.server.utils.StyleValueBuilder.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests {@link StyleValueBuilder}: building the mcore Color / Font value from the structured
 * {@code value} JSON (pure mcore-factory construction, no live project), and the shared Color / Font
 * rendering - including the load-bearing AutoColor-first ordering ({@code AutoColor extends ColorDef},
 * so an automatic color must render as "Auto", never as "RGB(0,0,0)").
 */
public class StyleValueBuilderTest
{
    private static JsonObject json(String s)
    {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ==================== building a Color ====================

    @Test
    public void testBuildExplicitRgbColor()
    {
        Result r = StyleValueBuilder.build(json("{\"color\":{\"red\":255,\"green\":128,\"blue\":0}}")); //$NON-NLS-1$
        assertNull("a valid RGB color must not error: " + r.error, r.error); //$NON-NLS-1$
        assertEquals(StyleElementType.COLOR, r.type);
        assertTrue("a color must build a ColorValue", r.value instanceof ColorValue); //$NON-NLS-1$
        Object inner = ((ColorValue)r.value).getValue();
        assertTrue("an explicit color must wrap a ColorDef", inner instanceof ColorDef); //$NON-NLS-1$
        ColorDef def = (ColorDef)inner;
        assertEquals(255, def.getRed());
        assertEquals(128, def.getGreen());
        assertEquals(0, def.getBlue());
        assertTrue(r.summary.contains("255")); //$NON-NLS-1$
    }

    @Test
    public void testBuildAutoColorFromString()
    {
        Result r = StyleValueBuilder.build(json("{\"color\":\"auto\"}")); //$NON-NLS-1$
        assertNull(r.error);
        assertEquals(StyleElementType.COLOR, r.type);
        Object inner = ((ColorValue)r.value).getValue();
        assertTrue("an automatic color must build an AutoColor", inner instanceof AutoColor); //$NON-NLS-1$
        assertEquals("Color=Auto", r.summary); //$NON-NLS-1$
    }

    @Test
    public void testBuildAutoColorFromFlagObject()
    {
        Result r = StyleValueBuilder.build(json("{\"color\":{\"auto\":true}}")); //$NON-NLS-1$
        assertNull(r.error);
        assertTrue(((ColorValue)r.value).getValue() instanceof AutoColor);
    }

    @Test
    public void testRgbOutOfRangeIsError()
    {
        Result r = StyleValueBuilder.build(json("{\"color\":{\"red\":300,\"green\":0,\"blue\":0}}")); //$NON-NLS-1$
        assertNotNull("an out-of-range component must error", r.error); //$NON-NLS-1$
        assertTrue("the error must name the offending value", r.error.contains("300")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIncompleteRgbIsError()
    {
        Result r = StyleValueBuilder.build(json("{\"color\":{\"red\":10,\"green\":20}}")); //$NON-NLS-1$
        assertNotNull("a missing component must error", r.error); //$NON-NLS-1$
    }

    // ==================== building a Font ====================

    @Test
    public void testBuildFontWithFaceHeightAndFlags()
    {
        Result r = StyleValueBuilder.build(
            json("{\"font\":{\"faceName\":\"Arial\",\"height\":12,\"bold\":true,\"italic\":true}}")); //$NON-NLS-1$
        assertNull("a valid font must not error: " + r.error, r.error); //$NON-NLS-1$
        assertEquals(StyleElementType.FONT, r.type);
        assertTrue("a font must build a FontValue", r.value instanceof FontValue); //$NON-NLS-1$
        FontDef def = (FontDef)((FontValue)r.value).getValue();
        assertEquals("Arial", def.getFaceName()); //$NON-NLS-1$
        assertTrue(def.getHeight() == 12f);
        assertTrue(def.isBold());
        assertTrue(def.isItalic());
        assertTrue("an unset flag must default to false", !def.isUnderline()); //$NON-NLS-1$
    }

    @Test
    public void testBuildFontWithOnlyAFlag()
    {
        // A single flag is enough; faceName / height are optional.
        Result r = StyleValueBuilder.build(json("{\"font\":{\"bold\":true}}")); //$NON-NLS-1$
        assertNull(r.error);
        assertTrue(((FontDef)((FontValue)r.value).getValue()).isBold());
    }

    @Test
    public void testEmptyFontIsError()
    {
        Result r = StyleValueBuilder.build(json("{\"font\":{}}")); //$NON-NLS-1$
        assertNotNull("an empty font (no face / height / flag) must error", r.error); //$NON-NLS-1$
    }

    @Test
    public void testNonPositiveHeightIsError()
    {
        Result r = StyleValueBuilder.build(json("{\"font\":{\"height\":0}}")); //$NON-NLS-1$
        assertNotNull("a non-positive height must error", r.error); //$NON-NLS-1$
    }

    // ==================== shape errors ====================

    @Test
    public void testBothColorAndFontIsError()
    {
        Result r = StyleValueBuilder.build(json("{\"color\":\"auto\",\"font\":{\"bold\":true}}")); //$NON-NLS-1$
        assertNotNull("color + font together must error", r.error); //$NON-NLS-1$
    }

    @Test
    public void testNeitherColorNorFontIsError()
    {
        Result r = StyleValueBuilder.build(json("{\"foo\":1}")); //$NON-NLS-1$
        assertNotNull("neither color nor font must error", r.error); //$NON-NLS-1$
    }

    @Test
    public void testNullValueIsError()
    {
        Result r = StyleValueBuilder.build(null);
        assertNotNull("a null value must error", r.error); //$NON-NLS-1$
    }

    // ==================== rendering (AutoColor-first ordering) ====================

    @Test
    public void testRenderAutoColorIsAutoNotRgb()
    {
        // The load-bearing case: AutoColor extends ColorDef, so an AutoColor must render as "Auto",
        // NOT fall through to the ColorDef branch and render as "RGB(0, 0, 0)".
        AutoColor auto = McoreFactory.eINSTANCE.createAutoColor();
        assertEquals("Auto", StyleValueBuilder.renderColor(auto)); //$NON-NLS-1$
    }

    @Test
    public void testRenderExplicitColorIsRgb()
    {
        ColorDef def = McoreFactory.eINSTANCE.createColorDef();
        def.setRed(10);
        def.setGreen(20);
        def.setBlue(30);
        assertEquals("RGB(10, 20, 30)", StyleValueBuilder.renderColor(def)); //$NON-NLS-1$
    }

    @Test
    public void testRenderNullColorIsNull()
    {
        assertNull(StyleValueBuilder.renderColor(null));
    }

    @Test
    public void testRenderFontShowsFaceHeightAndFlags()
    {
        FontDef def = McoreFactory.eINSTANCE.createFontDef();
        def.setFaceName("Arial"); //$NON-NLS-1$
        def.setHeight(12f);
        def.setBold(true);
        def.setStrikeout(true);
        String s = StyleValueBuilder.renderFont(def);
        assertNotNull(s);
        assertTrue(s.contains("face='Arial'")); //$NON-NLS-1$
        assertTrue(s.contains("height=12")); //$NON-NLS-1$
        assertTrue(s.contains("bold")); //$NON-NLS-1$
        assertTrue(s.contains("strikeout")); //$NON-NLS-1$
        assertTrue("an unset flag must not be rendered", !s.contains("italic")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderNullFontIsNull()
    {
        assertNull(StyleValueBuilder.renderFont(null));
    }
}

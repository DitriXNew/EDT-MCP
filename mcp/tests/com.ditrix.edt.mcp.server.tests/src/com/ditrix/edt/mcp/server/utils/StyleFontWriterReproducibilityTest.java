/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.google.gson.JsonObject;

/** Stored fonts must survive the real structured writer, including default and unset features. */
public class StyleFontWriterReproducibilityTest
{
    @Test
    public void testEmptyFaceNameMatchesTheRealWriterDefaultWithoutWrites()
    {
        for (boolean otherMembers : new boolean[] {false, true})
        {
            FontDef font = McoreFactory.eINSTANCE.createFontDef();
            font.setFaceName("");
            JsonObject body = new JsonObject();
            body.addProperty("bold", false);
            if (otherMembers)
            {
                font.setHeight(12f);
                font.setBold(true);
                body.addProperty("height", 12);
                body.addProperty("bold", true);
            }
            FontDef snapshot = EcoreUtil.copy(font);
            AtomicInteger writes = watch(font);

            FontDef written = build(body);
            assertEquals("EDT declares an empty default faceName", "", written.getFaceName());
            assertTrue("an empty faceName can be rebuilt exactly", EcoreUtil.equals(font, written));
            assertNull(StyleValueBuilder.fontRefusal(font));
            assertEquals("", font.getFaceName());
            assertTrue(EcoreUtil.equals(snapshot, font));
            assertEquals(0, writes.get());
        }
    }

    @Test
    public void testEmptyBodyIsRejectedButFalseFlagRebuildsItsModelExactly()
    {
        JsonObject fontBody = new JsonObject();
        fontBody.addProperty("faceName", "");
        JsonObject value = new JsonObject();
        value.add("font", fontBody);
        var empty = StyleValueBuilder.build(value);
        assertNotNull("an empty name alone is not a valid body", empty.error);
        assertTrue(empty.error, empty.error.contains("at least one"));

        // Reproducibility is about stored model state, not the body that originally produced it.
        fontBody.addProperty("bold", false);
        FontDef written = build(fontBody);
        assertTrue(EcoreUtil.equals(McoreFactory.eINSTANCE.createFontDef(), written));
        assertNull(StyleValueBuilder.fontRefusal(written));
    }

    @Test
    public void testProjectionRefusalsNameTheInvalidFeatureAndRecovery()
    {
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        font.setHeight(10.5f);
        String refusal = StyleValueBuilder.fontRefusal(font);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("font height 10.5"));
        assertTrue(refusal, refusal.contains("integer height"));
        assertTrue(refusal, refusal.contains("DCS designer"));
        assertTrue(refusal, refusal.contains("action='update'"));
        font.setHeight(12f);
        font.setScale(90);
        refusal = StyleValueBuilder.fontRefusal(font);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("font scale 90"));
        assertTrue(refusal, refusal.contains("scale 100"));
        assertTrue(refusal, refusal.contains("action='update'"));
    }

    @Test
    public void testNegativeZeroReportsBothValuesAndSetStatesWithoutWrites()
    {
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        font.setFaceName("Arial");
        font.setHeight(-0.0f);
        FontDef snapshot = EcoreUtil.copy(font);
        AtomicInteger writes = watch(font);

        String refusal = StyleValueBuilder.fontRefusal(font);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("font height -0.0"));
        assertTrue(refusal, refusal.contains("rebuilt 0.0"));
        assertTrue(refusal, refusal.contains("set="));
        assertTrue(refusal, refusal.contains("font writer cannot reproduce"));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(font.getHeight()));
        assertTrue(EcoreUtil.equals(snapshot, font));
        assertEquals(0, writes.get());
    }

    @Test
    public void testFactoryDefaultsAndExplicitFalseFlagsRemainReproducible()
    {
        FontDef defaults = McoreFactory.eINSTANCE.createFontDef();
        assertNull(StyleValueBuilder.fontRefusal(defaults));
        JsonObject body = new JsonObject();
        body.addProperty("bold", false);
        FontDef rebuilt = build(body);
        assertTrue("default/unset features must match a writer-built false flag", EcoreUtil.equals(defaults, rebuilt));
        assertNull(StyleValueBuilder.fontRefusal(rebuilt));
        rebuilt.setHeight(+0.0f);
        assertNull(StyleValueBuilder.fontRefusal(rebuilt));
        assertEquals(Float.floatToRawIntBits(+0.0f), Float.floatToRawIntBits(rebuilt.getHeight()));
    }

    @Test
    public void testEveryWriterBuiltMemberCombinationRoundTripsWithoutRefusal()
    {
        String[] names = {null, "Arial", "\u0410\u0440\u0438\u0430\u043b", "", " "};
        Integer[] heights = {null, 1, 2, 12, 255, 16777217, Integer.MAX_VALUE};
        String[] flags = {"bold", "italic", "underline", "strikeout"};
        int accepted = 0;
        for (String name : names)
        {
            for (Integer height : heights)
            {
                // Each flag independently takes all three states: absent, false, true.
                for (int combination = 0; combination < 81; combination++)
                {
                    JsonObject body = new JsonObject();
                    if (name != null) body.addProperty("faceName", name);
                    if (height != null) body.addProperty("height", height);
                    int states = combination;
                    for (String flag : flags)
                    {
                        int state = states % 3;
                        states /= 3;
                        if (state != 0) body.addProperty(flag, state == 2);
                    }
                    if (combination == 0 && height == null && (name == null || name.isEmpty())) continue;
                    FontDef font = build(body);
                    FontDef snapshot = EcoreUtil.copy(font);
                    AtomicInteger writes = watch(font);
                    assertNull(body.toString(), StyleValueBuilder.fontRefusal(font));
                    assertTrue(body.toString(), EcoreUtil.equals(snapshot, font));
                    assertEquals(body.toString(), 0, writes.get());
                    accepted++;
                }
            }
        }
        assertEquals("the complete member-state product was checked", 2833, accepted);
    }

    private static FontDef build(JsonObject body)
    {
        JsonObject value = new JsonObject();
        value.add("font", body);
        var result = StyleValueBuilder.build(value);
        assertNull(body + ": " + result.error, result.error);
        return (FontDef)((FontValue)result.value).getValue();
    }

    private static AtomicInteger watch(FontDef font)
    {
        AtomicInteger writes = new AtomicInteger();
        font.eAdapters().add(new EContentAdapter()
        {
            @Override
            public void notifyChanged(Notification notification)
            {
                super.notifyChanged(notification);
                writes.incrementAndGet();
            }
        });
        return writes;
    }
}

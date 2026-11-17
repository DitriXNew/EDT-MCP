// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonObject;

/**
 * The DCS planners' per-thread state lives only in {@link ThreadLocalScope.Slot}s: a raw
 * {@link ThreadLocal} field would let a {@code set} outlive the planner call on a pooled request
 * or job thread, pinning a named-color resolver and with it the live configuration.
 */
public class DcsPlannerSlotTest
{
    private static final DcsPresentationParser.LanguageContext LANGUAGES =
        new DcsPresentationParser.LanguageContext(List.of("en", "ru"));

    @Test public void thePlannersDeclareNoRawThreadLocal()
    {
        for (Class<?> writer : List.of(DcsSettingsWriter.class, DcsDynamicListWriter.class))
        {
            int slots = 0;
            for (Field field : writer.getDeclaredFields())
            {
                assertFalse(writer.getSimpleName() + "." + field.getName() + " is a raw ThreadLocal",
                    ThreadLocal.class.isAssignableFrom(field.getType()));
                if (field.getType() == ThreadLocalScope.Slot.class)
                {
                    assertTrue(field.getName(), Modifier.isStatic(field.getModifiers()));
                    slots++;
                }
            }
            assertEquals(writer.getSimpleName(), writer == DcsSettingsWriter.class ? 3 : 1, slots);
        }
    }

    @Test public void colorAndCatalogueBindingsEndWithThePlannerCall() throws Exception
    {
        try
        {
            DcsSettingsWriter.planSchema(null, "upsert", "selection", DcsAddress.parse("Report.Sales").address(),
                new JsonObject(), LANGUAGES, Version.LATEST, (source, name) -> null);
        }
        catch (RuntimeException failedPlan)
        {
            // The binding must end on an exceptional exit as well.
        }
        assertUnbound(DcsSettingsWriter.class, "NAMED_COLOR_RESOLVER");
        assertUnbound(DcsSettingsWriter.class, "PARSE_SCRATCH");

        var listed = DcsSettingsWriter.planDynamicList(DcsFactory.eINSTANCE.createDataCompositionSettings(), "upsert",
            "no-such-type", DcsAddress.parse("Catalog.Items.Form.Main.Attribute.List").address(), new JsonObject(),
            LANGUAGES, Version.LATEST);
        assertFalse(listed.isSuccess());
        assertUnbound(DcsSettingsWriter.class, "APPEARANCE_CATALOGUE");
        assertUnbound(DcsSettingsWriter.class, "PARSE_SCRATCH");
    }

    private static void assertUnbound(Class<?> writer, String name) throws Exception
    {
        Field field = writer.getDeclaredField(name);
        field.setAccessible(true);
        ThreadLocalScope.Slot<?> slot = (ThreadLocalScope.Slot<?>)field.get(null);
        assertNull(writer.getSimpleName() + "." + name + " outlived the planner call", slot.get());
    }
}

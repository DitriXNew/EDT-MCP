// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public class DcsParseScratchLifecycleTest
{
    private static final DcsPresentationParser.LanguageContext LANGUAGES =
        new DcsPresentationParser.LanguageContext(List.of("en", "ru"));

    @Test public void successfulPlannersReleaseScratch() throws Exception
    {
        var settings = DcsSettingsWriter.planSettings(null, List.of(), "upsert", "userSettings", new JsonObject(), LANGUAGES);
        assertTrue(settings.error(), settings.isSuccess());
        assertReleased(DcsSettingsWriter.class);
        var address = DcsAddress.parse("Catalog.Items.Form.Main.Attribute.List");
        var dynamicList = DcsDynamicListWriter.plan(null, "upsert", "dynamicList", address.address(), new JsonObject(),
            null, LANGUAGES, Version.LATEST);
        assertTrue(dynamicList.error(), dynamicList.isSuccess());
        assertReleased(DcsDynamicListWriter.class);
        assertReleased(DcsSettingsWriter.class);
    }

    @Test public void settingsFailureReleasesScratch() throws Exception
    {
        JsonObject body = new JsonObject();
        body.addProperty("use", "invalid");
        var result = DcsSettingsWriter.planSettings(null, List.of("selection"), "upsert", "selection", body, LANGUAGES);
        assertFalse(result.isSuccess());
        assertReleased(DcsSettingsWriter.class);
    }

    @Test public void settingsValueEntryReleasesScratch() throws Exception
    {
        var result = DcsSettingsWriter.buildValue(new JsonPrimitive(false), "value");
        assertNotNull(result.error);
        assertReleased(DcsSettingsWriter.class);
    }

    @Test public void settingsExceptionReleasesScratch() throws Exception
    {
        assertThrows(NullPointerException.class, () -> DcsSettingsWriter.planSettings(null,
            List.of(), "upsert", "userSettings", null, LANGUAGES));
        assertReleased(DcsSettingsWriter.class);
    }

    @Test public void dynamicListFailureReleasesScratch() throws Exception
    {
        JsonObject body = new JsonObject();
        body.addProperty("customQuery", "invalid");
        var address = DcsAddress.parse("Catalog.Items.Form.Main.Attribute.List");
        var result = DcsDynamicListWriter.plan(null, "upsert", "dynamicList", address.address(), body,
            null, LANGUAGES, Version.LATEST);
        assertFalse(result.isSuccess());
        assertEquals("Member 'dynamic-list body.customQuery' must be true or false.", result.error());
        assertReleased(DcsDynamicListWriter.class);
    }

    private static void assertReleased(Class<?> writer) throws Exception
    {
        Field field = writer.getDeclaredField("PARSE_SCRATCH");
        field.setAccessible(true);
        ThreadLocal<?> scratch = (ThreadLocal<?>)field.get(null);
        try { assertNull(writer.getSimpleName() + " retained parse state on the caller thread", scratch.get()); }
        finally { scratch.remove(); }
    }
}

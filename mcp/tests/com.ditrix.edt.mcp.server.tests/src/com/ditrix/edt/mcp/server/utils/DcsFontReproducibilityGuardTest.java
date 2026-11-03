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

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonObject;

/** Shared replace guards preserve the whole stored model before any planner or commit. */
public class DcsFontReproducibilityGuardTest
{
    private static final Version VERSION = Version.V8_3_27;

    @Test
    public void testSchemaAcceptsExactlyReproducibleEmptyFaceName()
    {
        assertEmptyNameAccepted(AppearanceCatalogue.SCHEMA);
    }

    @Test
    public void testFormAcceptsExactlyReproducibleEmptyFaceName()
    {
        assertEmptyNameAccepted(AppearanceCatalogue.FORM);
    }

    @Test
    public void testDynamicListAcceptsExactlyReproducibleEmptyFaceName()
    {
        assertEmptyNameAccepted(AppearanceCatalogue.DYNAMIC_LIST);
    }

    @Test
    public void testSchemaRejectsNegativeZeroWithoutWrites()
    {
        assertRefused(AppearanceCatalogue.SCHEMA, "Arial", -0.0f, "font height -0.0");
    }

    @Test
    public void testFormRejectsNegativeZeroWithoutWrites()
    {
        assertRefused(AppearanceCatalogue.FORM, "Arial", -0.0f, "font height -0.0");
    }

    @Test
    public void testDynamicListRejectsNegativeZeroWithoutWrites()
    {
        assertRefused(AppearanceCatalogue.DYNAMIC_LIST, "Arial", -0.0f, "font height -0.0");
    }

    @Test
    public void testPositiveControlsReachTheRealPlannersAndCommit()
    {
        for (AppearanceCatalogue kind : AppearanceCatalogue.values())
        {
            for (float height : new float[] {+0.0f, 12f})
            {
                var schema = schema("Arial", height);
                AtomicInteger writes = watch(schema);
                assertNull(kind.toString(), replace(schema, kind));
                if (kind == AppearanceCatalogue.SCHEMA || kind == AppearanceCatalogue.DYNAMIC_LIST)
                {
                    assertNull(schema.getDefaultSettings().getConditionalAppearance());
                }
                else
                {
                    assertTrue(schema.getDefaultSettings().getConditionalAppearance().getItems().isEmpty());
                }
                assertTrue("the allowed replacement actually committed for " + kind, writes.get() > 0);
            }
        }
    }

    private static void assertEmptyNameAccepted(AppearanceCatalogue kind)
    {
        var schema = schema("", 12f);
        FontDef stored = font(schema);
        var snapshot = EcoreUtil.copy(schema);
        AtomicInteger writes = watch(schema);
        assertNull("EDT's empty default faceName is reproducible for " + kind, guard(schema, kind));
        assertSame(stored, font(schema));
        assertEquals("", stored.getFaceName());
        assertTrue(EcoreUtil.equals(snapshot, schema));
        assertEquals("the guard remains a read-only dry run", 0, writes.get());
        assertNull(kind.toString(), replace(schema, kind));
        assertTrue("an accepted replacement reaches the real commit for " + kind, writes.get() > 0);
    }

    private static void assertRefused(AppearanceCatalogue kind, String faceName, float height, String reason)
    {
        var schema = schema(faceName, height);
        var settings = schema.getDefaultSettings();
        FontDef font = font(schema);
        var snapshot = EcoreUtil.copy(schema);
        AtomicInteger writes = watch(schema);

        String refusal = replace(schema, kind);

        assertNotNull(kind + " must refuse an unreproducible font", refusal);
        assertTrue(refusal, refusal.contains("action='replace'"));
        assertTrue(refusal, refusal.contains("appearance/items/0"));
        assertTrue(refusal, refusal.contains(reason));
        assertTrue(refusal, refusal.contains("action='update'"));
        assertTrue(refusal, refusal.contains("DCS designer"));
        assertSame(settings, schema.getDefaultSettings());
        assertSame(font, font(schema));
        assertEquals(faceName, font.getFaceName());
        assertEquals(Float.floatToRawIntBits(height), Float.floatToRawIntBits(font.getHeight()));
        assertTrue("the entire schema is preserved for " + kind, EcoreUtil.equals(snapshot, schema));
        assertEquals("refusal must precede every original-model notification for " + kind, 0, writes.get());
    }

    private static String replace(DataCompositionSchema schema, AppearanceCatalogue kind)
    {
        var settings = schema.getDefaultSettings();
        var appearance = settings.getConditionalAppearance();
        var target = target(kind);
        String refusal = guard(schema, kind);
        if (refusal != null) return refusal;
        if (kind == AppearanceCatalogue.SCHEMA)
        {
            var planned = DcsSettingsWriter.planSchema(schema, "replace", "userSettings", target,
                new JsonObject(), null, VERSION);
            assertTrue(planned.error(), planned.isSuccess());
            planned.plan().commit(schema);
        }
        else if (kind == AppearanceCatalogue.FORM)
        {
            var planned = DcsSettingsWriter.planFormConditionalAppearance(appearance, "replace",
                "conditionalAppearance", target, new JsonObject(), null, VERSION, null);
            assertTrue(planned.error(), planned.isSuccess());
            DcsSettingsWriter.commitConditionalAppearance(appearance, planned.settings().getConditionalAppearance());
        }
        else
        {
            var planned = DcsSettingsWriter.planDynamicList(settings, "replace", "userSettings", target,
                new JsonObject(), null, VERSION);
            assertTrue(planned.error(), planned.isSuccess());
            DcsSettingsWriter.commitSettings(settings, planned.settings());
        }
        return null;
    }

    private static DcsAddress target(AppearanceCatalogue kind)
    {
        String root = kind == AppearanceCatalogue.SCHEMA ? "Report.Sales"
            : kind == AppearanceCatalogue.FORM ? "Catalog.Products.Form.ItemForm"
                : "Catalog.Products.Form.ListForm.Attribute.List";
        return DcsAddress.parse(root + (kind == AppearanceCatalogue.SCHEMA ? "#/defaultSettings"
            : kind == AppearanceCatalogue.DYNAMIC_LIST ? "#/listSettings" : "")).address();
    }

    private static String guard(DataCompositionSchema schema, AppearanceCatalogue kind)
    {
        var target = target(kind);
        // The live dynamic-list tool scopes its separately stored settings root with a relative
        // pointer, removing listSettings before it calls the shared guard.
        var guardTarget = kind == AppearanceCatalogue.DYNAMIC_LIST
            ? DcsAddress.parse(target.rootFqn()).address() : target;
        return DcsMutationGuard.replaceError(kind == AppearanceCatalogue.SCHEMA ? schema
            : kind == AppearanceCatalogue.FORM ? schema.getDefaultSettings().getConditionalAppearance()
                : schema.getDefaultSettings(), guardTarget, catalogues(kind));
    }

    private static DcsWriterCatalogues catalogues(AppearanceCatalogue kind)
    {
        return new DcsWriterCatalogues(VERSION, kind, "en", (actual, version, language) ->
        {
            assertEquals(kind, actual);
            assertEquals(VERSION, version);
            assertEquals("en", language);
            return DcsReplaceTestCatalogues.available("Font", "Alias", McoreFactory.eINSTANCE.createFontValue());
        });
    }

    private static DataCompositionSchema schema(String faceName, float height)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var appearance = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        var rule = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearanceItem();
        var holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Font"));
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        font.setFaceName(faceName);
        font.setHeight(height);
        FontValue value = McoreFactory.eINSTANCE.createFontValue();
        value.setValue(font);
        item.getValues().add(value);
        holder.getItems().add(item);
        rule.setAppearance(holder);
        appearance.getItems().add(rule);
        schema.getDefaultSettings().setConditionalAppearance(appearance);
        return schema;
    }

    private static FontDef font(DataCompositionSchema schema)
    {
        return (FontDef)((FontValue)schema.getDefaultSettings().getConditionalAppearance().getItems().get(0)
            .getAppearance().getItems().get(0).getValues().get(0)).getValue();
    }

    private static AtomicInteger watch(DataCompositionSchema schema)
    {
        AtomicInteger writes = new AtomicInteger();
        schema.eAdapters().add(new EContentAdapter()
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

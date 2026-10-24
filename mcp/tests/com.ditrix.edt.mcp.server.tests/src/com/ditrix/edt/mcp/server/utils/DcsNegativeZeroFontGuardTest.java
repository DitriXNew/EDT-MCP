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

/** A stored negative-zero font must block replacement before any settings write. */
public class DcsNegativeZeroFontGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final Version VERSION = Version.V8_3_27;

    @Test
    public void testNegativeZeroFontRefusesContainingSettingsReplaceWithoutWrites()
    {
        var schema = schema(-0.0f);
        var original = schema.getDefaultSettings();
        FontDef font = font(schema);
        var snapshot = EcoreUtil.copy(schema);
        AtomicInteger writes = watch(schema);

        String refusal = replaceSettings(schema);

        assertNotNull("the real replace guard must reject a stored negative-zero font", refusal);
        assertTrue(refusal, refusal.contains("action='replace'"));
        assertTrue(refusal, refusal.contains(ROOT
            + "#/defaultSettings/conditionalAppearance/items/0/appearance/items/0"));
        assertTrue(refusal, refusal.contains("font height -0.0"));
        assertTrue(refusal, refusal.contains("negative zero"));
        assertTrue(refusal, refusal.contains("font writer cannot reproduce"));
        assertSame(original, schema.getDefaultSettings());
        assertSame(font, font(schema));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(font.getHeight()));
        assertTrue("all stored settings must remain unchanged", EcoreUtil.equals(snapshot, schema));
        assertEquals("refusal must occur before any original-model notification", 0, writes.get());
    }

    @Test
    public void testPositiveZeroAllowsContainingSettingsReplace()
    {
        var schema = schema(+0.0f);
        assertEquals(Float.floatToRawIntBits(+0.0f), Float.floatToRawIntBits(font(schema).getHeight()));
        assertNull("positive zero must pass the same guard and replacement planner", replaceSettings(schema));
        assertNull("the accepted replacement actually replaces the old appearance",
            schema.getDefaultSettings().getConditionalAppearance());
    }

    @Test
    public void testFormAndDynamicListGuardsPreserveNegativeZeroWithoutWrites()
    {
        for (AppearanceCatalogue kind : new AppearanceCatalogue[] {AppearanceCatalogue.FORM,
            AppearanceCatalogue.DYNAMIC_LIST})
        {
            var schema = schema(-0.0f);
            var root = schema.getDefaultSettings().getConditionalAppearance();
            var snapshot = EcoreUtil.copy(root);
            FontDef font = font(schema);
            AtomicInteger writes = watch(schema);
            String refusal = DcsMutationGuard.replaceError(root, DcsAddress.parse(ROOT).address(),
                catalogues(kind));
            assertNotNull(kind + " must use the shared negative-zero refusal", refusal);
            assertTrue(refusal, refusal.contains("font height -0.0"));
            assertTrue(refusal, refusal.contains("negative zero"));
            assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(font.getHeight()));
            assertTrue(EcoreUtil.equals(snapshot, root));
            assertEquals(0, writes.get());
        }
    }

    @Test
    public void testFormAndDynamicListGuardsAcceptPositiveZero()
    {
        for (AppearanceCatalogue kind : new AppearanceCatalogue[] {AppearanceCatalogue.FORM,
            AppearanceCatalogue.DYNAMIC_LIST})
        {
            var schema = schema(+0.0f);
            assertNull(kind.toString(), DcsMutationGuard.replaceError(
                schema.getDefaultSettings().getConditionalAppearance(), DcsAddress.parse(ROOT).address(),
                catalogues(kind)));
            assertEquals(Float.floatToRawIntBits(+0.0f), Float.floatToRawIntBits(font(schema).getHeight()));
        }
    }

    private static String replaceSettings(DataCompositionSchema schema)
    {
        var target = DcsAddress.parse(ROOT + "#/defaultSettings").address();
        String refusal = DcsMutationGuard.replaceError(schema, target, catalogues(AppearanceCatalogue.SCHEMA));
        if (refusal != null) return refusal;
        var planned = DcsSettingsWriter.planSchema(schema, "replace", "userSettings", target,
            new JsonObject(), null, VERSION);
        assertTrue(planned.error(), planned.isSuccess());
        planned.plan().commit(schema);
        return null;
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

    private static DataCompositionSchema schema(float height)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var appearance = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        var rule = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearanceItem();
        var holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Font"));
        FontDef font = McoreFactory.eINSTANCE.createFontDef();
        font.setFaceName("Arial");
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

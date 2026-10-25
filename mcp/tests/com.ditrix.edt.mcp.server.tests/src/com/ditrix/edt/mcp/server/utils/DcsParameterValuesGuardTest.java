/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;

/**
 * Real output and appearance catalogue entries each take one typed value; data-parameter arrays
 * are exercised separately by DcsStandardValueKindsRoundTripTest.
 */
public class DcsParameterValuesGuardTest
{
    private static final com._1c.g5.v8.dt.dcs.model.settings.DcsFactory SETTINGS =
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;

    @Test
    public void testARealOutputParameterWritesOneTypedValue() throws Exception
    {
        DataCompositionSchema schema = schema();
        DataCompositionOutputParameterValues holder = SETTINGS.createDataCompositionOutputParameterValues();
        SettingsParameterValue item = item("Title", text("A"), text("B"));
        holder.getItems().add(item);
        schema.getDefaultSettings().setOutputParameters(holder);
        var catalogue = DcsReplaceTestCatalogues.load(DcsSettingsWriter.OutputParameterCatalogue.SETTINGS,
            Version.V8_3_27, "en");
        String refusal = guard(schema, catalogue);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("(2 values; 'Title' takes one typed value; only data parameters accept 'values')"));
        item.getValues().remove(1);
        assertNull(guard(schema, catalogue));
    }

    @Test
    public void testAnAppearanceKeyWritesOneValue() throws Exception
    {
        DataCompositionSchema schema = schema();
        DataCompositionConditionalAppearance appearance = SETTINGS.createDataCompositionConditionalAppearance();
        DataCompositionConditionalAppearanceItem rule = SETTINGS.createDataCompositionConditionalAppearanceItem();
        DataCompositionAppearance holder =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        holder.getItems().add(item("Text", text("A"), text("B")));
        rule.setAppearance(holder);
        appearance.getItems().add(rule);
        schema.getDefaultSettings().setConditionalAppearance(appearance);
        String refusal = guard(schema, DcsReplaceTestCatalogues.load(AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en"));
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("(2 values; an appearance key writes one typed value)"));
    }

    private static String guard(DataCompositionSchema schema, DcsAvailableParameterCollection parameters)
    {
        DcsWriterCatalogues catalogues = new DcsWriterCatalogues(Version.V8_3_27, AppearanceCatalogue.SCHEMA, "en",
            (kind, version, language) -> parameters);
        return DcsMutationGuard.replaceError(schema, DcsAddress.parse("Report.Sales").address(), catalogues);
    }

    private static DataCompositionSchema schema()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(SETTINGS.createDataCompositionSettings());
        return schema;
    }

    private static SettingsParameterValue item(String name, LocalString... values)
    {
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter(name));
        for (LocalString value : values) item.getValues().add(value);
        return item;
    }

    private static LocalString text(String english)
    {
        LocalString text = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
        text.getContent().put("en", english); //$NON-NLS-1$
        return text;
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameter;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;

/**
 * #682 round 5: a typed parameter is restated with several values exactly when its writer takes
 * them - an output parameter the platform declares a value list for - and each value must pass on
 * its own; an appearance key always writes one value.
 */
public class DcsParameterValuesGuardTest
{
    private static final com._1c.g5.v8.dt.dcs.model.settings.DcsFactory SETTINGS =
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;

    @Test
    public void testSeveralOutputParameterValuesPassOnlyWithADeclaredValueList()
    {
        DataCompositionSchema schema = schema();
        DataCompositionOutputParameterValues holder = SETTINGS.createDataCompositionOutputParameterValues();
        SettingsParameterValue item = item("Known", text("A"), text("B"));
        holder.getItems().add(item);
        schema.getDefaultSettings().setOutputParameters(holder);

        String single = guard(schema, catalogue(false));
        assertNotNull(single);
        assertTrue(single, single.contains("(2 values; 'Known' takes one value, the platform declares no value "
            + "list for it)"));
        assertNull("a declared value list restates every value", guard(schema, catalogue(true)));

        item.getValues().add(com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createStringValue());
        String typed = guard(schema, catalogue(true));
        assertNotNull(typed);
        assertTrue(typed, typed.contains("(value #2: declared LocalString, stored StringValue"));
    }

    @Test
    public void testAnAppearanceKeyWritesOneValue()
    {
        DataCompositionSchema schema = schema();
        DataCompositionConditionalAppearance appearance = SETTINGS.createDataCompositionConditionalAppearance();
        DataCompositionConditionalAppearanceItem rule = SETTINGS.createDataCompositionConditionalAppearanceItem();
        DataCompositionAppearance holder =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        holder.getItems().add(item("Known", text("A"), text("B")));
        rule.setAppearance(holder);
        appearance.getItems().add(rule);
        schema.getDefaultSettings().setConditionalAppearance(appearance);
        String refusal = guard(schema, catalogue(true));
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("(2 values; an appearance key writes one typed value)"));
    }

    private static DcsAvailableParameterCollection catalogue(boolean valueListAllowed)
    {
        DcsAvailableParameter parameter = Mockito.mock(DcsAvailableParameter.class);
        LocalString declared = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
        Mockito.doReturn(declared).when(parameter).defValue();
        Mockito.doReturn(Boolean.valueOf(valueListAllowed)).when(parameter).isValueListAllowed();
        Mockito.doReturn("Known").when(parameter).key(0); //$NON-NLS-1$
        DcsAvailableParameterCollection result = Mockito.mock(DcsAvailableParameterCollection.class);
        Mockito.doReturn(parameter).when(result).findItem("Known"); //$NON-NLS-1$
        return result;
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

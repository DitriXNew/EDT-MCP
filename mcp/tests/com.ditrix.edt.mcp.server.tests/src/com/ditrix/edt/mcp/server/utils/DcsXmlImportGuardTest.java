/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

/** Independent model checks for XML replacement; XML projection alone cannot establish attachment. */
public class DcsXmlImportGuardTest
{
    @Test
    public void testMissingParametersRefuseWithModelPathAndBothCounts()
    {
        var requested = schema();
        var actual = EcoreUtil.copy(requested);
        actual.getParameters().clear();

        String error = error(requested, actual);

        assertNotNull(error);
        assertTrue(error, error.contains("root/parameters/@size"));
        assertTrue(error, error.contains("parameters=4"));
        assertTrue(error, error.contains("parameters=0"));
        assertTrue(error, error.contains("Report.Sales"));
        assertTrue(error, error.contains("nothing was written"));
        assertTrue(error, error.contains("action='get'"));
    }

    @Test
    public void testSameCountsCannotHideLostParameterExpression()
    {
        var requested = schema();
        var actual = EcoreUtil.copy(requested);
        actual.getParameters().get(1).setExpression(null);

        assertEquals(DcsReadProjection.schemaCounts(requested), DcsReadProjection.schemaCounts(actual));
        String error = error(requested, actual);
        assertNotNull(error);
        assertTrue(error, error.contains("root/parameters/1/expression"));
    }

    @Test
    public void testEqualCountsCannotHideLostVariantDataParameterValue()
    {
        var requested = schema();
        var actual = EcoreUtil.copy(requested);
        actual.getSettingsVariants().get(0).getSettings().getDataParameters()
            .getItems().get(0).getValues().clear();

        String error = error(requested, actual);
        assertNotNull(error);
        assertTrue(error, error.contains("settingsVariants/0/settings/dataParameters/items/0/values/@size"));
    }

    @Test
    public void testLocalizedOutputAndFieldAndConditionalAppearanceSurviveXmlReplacement() throws Exception
    {
        var requested = schema();
        var core = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE;
        var settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        var output = settings.createDataCompositionOutputParameterValues();
        var title = settings.createSettingsParameterValue();
        title.setParameter(DcsReplaceTestCatalogues.parameter("Title"));
        var localized = core.createLocalString();
        localized.getContent().put("en", "Balances");
        localized.getContent().put("ru", "Остатки");
        title.getValues().add(localized);
        output.getItems().add(title);
        requested.getDefaultSettings().setOutputParameters(output);
        var appearance = core.createDataCompositionAppearance();
        var format = core.createDataCompositionParameterValue();
        format.setParameter(DcsReplaceTestCatalogues.parameter("Format"));
        var formatValue = core.createLocalString();
        formatValue.getContent().put("en", "NFD=2");
        format.getValues().add(formatValue);
        appearance.getItems().add(format);
        var field = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
        field.setDataPath("Amount");
        field.setAppearance(appearance);
        var set = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        set.setName("Sales");
        set.getFields().add(field);
        requested.getDataSets().add(set);
        var conditional = settings.createDataCompositionConditionalAppearance();
        var item = settings.createDataCompositionConditionalAppearanceItem();
        item.setAppearance(EcoreUtil.copy(appearance));
        conditional.getItems().add(item);
        requested.getDefaultSettings().setConditionalAppearance(conditional);
        String xml = DcsStoredXmlTestSupport.serialize(requested);
        var parsed = DcsStoredXmlTestSupport.deserialize(xml);
        var actual = DcsFactory.eINSTANCE.createDataCompositionSchema();

        DcsXmlCodec.replaceContent(actual, parsed);

        assertNull(error(parsed, actual));
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(xml, DcsStoredXmlTestSupport.serialize(actual)));
        actual.getDefaultSettings().getOutputParameters().getItems().get(0).getValues().clear();
        assertNotNull("losing localized output text must refuse too", error(parsed, actual));
    }

    @Test
    public void testCensusCountsConcreteFieldsInsideUnionsAndFolders()
    {
        var schema = schema();
        var union = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetUnion();
        var query = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        var folder = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetFieldFolder();
        folder.setDataPath("Folder");
        var child = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
        child.setDataPath("Folder.Amount");
        query.getFields().add(folder);
        query.getFields().add(child);
        union.getItems().add(query);
        schema.getDataSets().add(union);

        assertEquals(new DcsReadProjection.SchemaCounts(0, 1, 1, 4, 1),
            DcsReadProjection.schemaCounts(schema));
        assertNull(error(schema, EcoreUtil.copy(schema)));
    }

    private static String error(DataCompositionSchema requested, DataCompositionSchema actual)
    {
        return DcsMutationGuard.xmlReplaceError(requested, actual,
            DcsAddress.parse("Report.Sales").address());
    }

    private static DataCompositionSchema schema()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (String name : new String[] {"Period", "Start", "End", "Дата"})
        {
            var parameter = DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
            parameter.setName(name);
            parameter.setExpression("&Period.StartDate");
            schema.getParameters().add(parameter);
        }
        var variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant();
        variant.setName("Main");
        var value = McoreFactory.eINSTANCE.createStringValue();
        value.setValue("retained");
        variant.setSettings(DcsStoredXmlTestSupport.operand(false, value).getDefaultSettings());
        schema.getSettingsVariants().add(variant);
        return schema;
    }
}

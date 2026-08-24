/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionDataParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrder;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTable;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTableGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldCase;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldExpression;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldsCaseVariants;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionUserFieldsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;
import com.google.gson.JsonParser;

/** Pure summary, pagination, pointer and address-printing tests. */
public class DcsReadProjectionTest
{
    @Test
    public void testTypedReadsRenderEveryDeliberatelyEmptyRequiredString()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSchemaCalculatedField calculated =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaCalculatedField();
        calculated.setDataPath("RuntimeFilled"); //$NON-NLS-1$
        calculated.setExpression(""); //$NON-NLS-1$
        schema.getCalculatedFields().add(calculated);

        DataCompositionSchemaDataSetQuery query = query("EmptyQuery", ""); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(query);
        DataCompositionSchemaDataSetObject object =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetObject();
        object.setName("EmptyObject"); //$NON-NLS-1$
        object.setObjectName(""); //$NON-NLS-1$
        schema.getDataSets().add(object);

        assertTypedEmptyString(schema, "Report.Empty#/calculatedFields/RuntimeFilled", //$NON-NLS-1$
            "calculatedField", "expression"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTypedEmptyString(schema, "Report.Empty#/dataSets/EmptyQuery", //$NON-NLS-1$
            "dataSet", "query"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTypedEmptyString(schema, "Report.Empty#/dataSets/EmptyObject", //$NON-NLS-1$
            "dataSet", "objectName"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertTypedEmptyString(DataCompositionSchema schema, String address,
        String type, String member)
    {
        DcsReadProjection.Result result = DcsReadProjection.render("Report.Empty", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(), type,
            "en", 100_000, 0); //$NON-NLS-1$
        assertTrue(result.error(), result.isSuccess());
        assertTrue(result.markdown(), result.markdown().contains("- " + member + ": \n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaSummaryPrintsCountsAndAddressesButOmitsQuery()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT SecretQueryText"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result result = render(schema, "Report.Sales", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.isSuccess());
        assertTrue(result.markdown().contains("Report.Sales#/dataSets")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Sales")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("| Data sets | 1 |")); //$NON-NLS-1$
        assertFalse(result.markdown().contains("SecretQueryText")); //$NON-NLS-1$
    }

    @Test
    public void testFormConditionalAppearanceReadPrintsWritableRootRelativeAddresses()
    {
        String root = "Catalog.Products.Form.ListForm"; //$NON-NLS-1$
        DataCompositionConditionalAppearance appearance = com._1c.g5.v8.dt.dcs.model.settings
            .DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        appearance.getItems().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionConditionalAppearanceItem());

        DcsReadProjection.Result result = DcsReadProjection.render(root, TargetKind.FORM,
            appearance, DcsAddress.parse(root).address(), "conditionalAppearance", "en", //$NON-NLS-1$ //$NON-NLS-2$
            100_000, 0);

        assertTrue(result.error(), result.isSuccess());
        assertTrue(result.markdown(), result.markdown().contains("**Address:** `" + root + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.markdown(), result.markdown().contains(root + "#/items/0")); //$NON-NLS-1$
    }

    @Test
    public void testExactNodeEnvelopeOwnsAddressAndValueKeepsEClassName()
    {
        String root = "Report.ExactAddress"; //$NON-NLS-1$
        String address = root + "#/dataSets/Sales"; //$NON-NLS-1$
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$

        DcsReadProjection.Result result = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "dataSet", "en", 100_000, 0); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.error(), result.isSuccess());
        assertEquals(1, occurrences(result.markdown(), "**Address:** `" + address + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.markdown(), result.markdown().contains(
            "# DCS node: DataCompositionSchemaDataSetQuery")); //$NON-NLS-1$
        assertFalse(textPageValue(result.markdown()).contains("**Address:** `" + address + "`")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullSchemaCollectionsUseMaterializedCanonicalAddresses()
    {
        String root = "Report.Empty"; //$NON-NLS-1$
        DataCompositionSchema materialized =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchema();
        materialized.setDefaultSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings());
        String[][] cases = {
            {"dataSource", "#/dataSources"}, {"dataSet", "#/dataSets"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"field", "#/dataSets"}, {"parameter", "#/parameters"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"calculatedField", "#/calculatedFields"}, {"totalField", "#/totalFields"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"variant", "#/variants"}, {"grouping", "#/defaultSettings/items"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {"table", "#/defaultSettings/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"selection", "#/defaultSettings/selection/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"filter", "#/defaultSettings/filter/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"dataParameter", "#/defaultSettings/dataParameters/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"order", "#/defaultSettings/order/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"conditionalAppearance", "#/defaultSettings/conditionalAppearance/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"userField", "#/defaultSettings/userFields/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"outputParameter", "#/defaultSettings/outputParameters/items"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"userSettings", "#/defaultSettings"} //$NON-NLS-1$ //$NON-NLS-2$
        };

        for (String[] one : cases)
        {
            DcsReadProjection.Result absent = render(null, root, one[0]);
            DcsReadProjection.Result present = render(materialized, root, one[0]);
            String address = "**Address:** `" + root + one[1] + "`"; //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(one[0] + ": " + absent.error(), absent.isSuccess()); //$NON-NLS-1$
            assertTrue(one[0] + ": " + present.error(), present.isSuccess()); //$NON-NLS-1$
            assertTrue(one[0] + ": " + absent.markdown(), absent.markdown().contains(address)); //$NON-NLS-1$
            assertTrue(one[0] + ": " + present.markdown(), present.markdown().contains(address)); //$NON-NLS-1$
        }
    }

    @Test
    public void testBareUserSettingsReadPagesTheWholeSettingsTarget()
    {
        String reportRoot = "Report.Settings"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(settingsWithSelection("ReportRevenue")); //$NON-NLS-1$

        DcsReadProjection.Result report = DcsReadProjection.render(reportRoot,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(reportRoot).address(),
            "userSettings", "en", 10000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.error(), report.isSuccess());
        assertTrue(report.markdown(), report.markdown().contains(
            "**Address:** `Report.Settings#/defaultSettings`")); //$NON-NLS-1$
        assertTrue(report.markdown(), report.markdown().contains("ReportRevenue")); //$NON-NLS-1$
        assertTrue(report.markdown(), report.markdown().contains(
            "Report.Settings#/defaultSettings/selection/items/0")); //$NON-NLS-1$

        DynamicListExtInfo dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        dynamic.setListSettings(settingsWithSelection("ListRevenue")); //$NON-NLS-1$
        String listRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        DcsReadProjection.Result list = DcsReadProjection.render(listRoot, TargetKind.DYNAMIC_LIST,
            dynamic, DcsAddress.parse(listRoot).address(), "userSettings", "en", 10000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(list.error(), list.isSuccess());
        assertTrue(list.markdown(), list.markdown().contains(
            "**Address:** `" + listRoot + "#/listSettings`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(list.markdown(), list.markdown().contains("ListRevenue")); //$NON-NLS-1$

        DcsReadProjection.Result bounded = DcsReadProjection.render(reportRoot,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(reportRoot).address(),
            "userSettings", "en", 40, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(bounded.error(), bounded.isSuccess());
        assertFalse(bounded.markdown(), bounded.markdown().contains("**Next offset:** none")); //$NON-NLS-1$
    }

    @Test
    public void testExactCompositePagesByCharactersAndReassemblesWithoutLoss()
    {
        String root = "Report.LargeSettings"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionSelectedFields selection = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionSelectedFields();
        for (int i = 0; i < 24; i++)
        {
            selection.getItems().add(selectedField("ExactMarker" + i + "x".repeat(60))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        settings.setSelection(selection);
        schema.setDefaultSettings(settings);
        String address = root + "#/defaultSettings"; //$NON-NLS-1$

        DcsReadProjection.Result complete = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "userSettings", "en", 100_000, 0, 100_000); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(complete.error(), complete.isSuccess());
        String expected = textPageValue(complete.markdown());

        DcsReadProjection.Result first = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "userSettings", "en", 100_000, 0, 550); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error(), first.isSuccess());
        assertTrue(first.markdown(), first.markdown().contains(
            "**Stopped by:** serialized character budget")); //$NON-NLS-1$

        String reconstructed = readAllTextPages(root, schema, address, "userSettings", 550); //$NON-NLS-1$
        assertEquals(expected, reconstructed);
        for (int i = 0; i < 24; i++)
        {
            String itemAddress = address + "/selection/items/" + i; //$NON-NLS-1$
            assertEquals(1, occurrences(reconstructed,
                "DataCompositionSelectedField — `" + itemAddress + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testEveryCharacterPagedPathUsesTheCharacterDefaultAndMeasuredClamp()
    {
        int pageBudget = 60_000;
        String reportRoot = "Report.CharacterLimits"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionSelectedFields selection = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionSelectedFields();
        for (int i = 0; i < 600; i++)
        {
            selection.getItems().add(selectedField(
                "CompositeBudgetMarker" + i + "x".repeat(100))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        settings.setSelection(selection);
        schema.setDefaultSettings(settings);
        schema.getDataSets().add(query("LargeQuery", "q".repeat(150_000))); //$NON-NLS-1$ //$NON-NLS-2$

        assertCharacterPageUtilization(reportRoot, TargetKind.REPORT_MAIN_DCS, schema,
            reportRoot, "userSettings", pageBudget); //$NON-NLS-1$
        assertCharacterPageUtilization(reportRoot, TargetKind.REPORT_MAIN_DCS, schema,
            reportRoot + "#/defaultSettings", "userSettings", pageBudget); //$NON-NLS-1$ //$NON-NLS-2$
        assertCharacterPageUtilization(reportRoot, TargetKind.REPORT_MAIN_DCS, schema,
            reportRoot + "#/dataSets/LargeQuery/query", "dataSet", pageBudget); //$NON-NLS-1$ //$NON-NLS-2$

        String dynamicRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        DynamicListExtInfo dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        dynamic.eSet(dynamic.eClass().getEStructuralFeature("queryText"), //$NON-NLS-1$
            "d".repeat(150_000)); //$NON-NLS-1$
        assertCharacterPageUtilization(dynamicRoot, TargetKind.DYNAMIC_LIST, dynamic,
            dynamicRoot + "#/queryText", "dynamicList", pageBudget); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testItemPagesKeepCollectionDefaultAndMaximum()
    {
        String root = "Report.ItemLimits"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        for (int i = 0; i < 1100; i++)
        {
            DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory
                .eINSTANCE.createDataCompositionSchemaParameter();
            parameter.setName("P" + i); //$NON-NLS-1$
            schema.getParameters().add(parameter);
        }

        DcsReadProjection.Result defaultPage = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "parameter", "en", null, 0, 500_000); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result clampedPage = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "parameter", "en", Integer.valueOf(100_000), 0, 500_000); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(defaultPage.error(), defaultPage.isSuccess());
        assertTrue(clampedPage.error(), clampedPage.isSuccess());
        assertEquals(100, metadataInt(defaultPage.markdown(), "Page items")); //$NON-NLS-1$
        assertEquals(100, metadataInt(defaultPage.markdown(), "Next offset")); //$NON-NLS-1$
        assertEquals(1000, metadataInt(clampedPage.markdown(), "Page items")); //$NON-NLS-1$
        assertEquals(1000, metadataInt(clampedPage.markdown(), "Next offset")); //$NON-NLS-1$
    }

    @Test
    public void testCollectionPageStopsAtFirstUnrenderedItemWhenBudgetBinds()
    {
        String root = "Report.LargeCollection"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        for (int i = 0; i < 16; i++)
        {
            DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory
                .eINSTANCE.createDataCompositionSchemaParameter();
            parameter.setName("CollectionMarker" + i + "x".repeat(70)); //$NON-NLS-1$ //$NON-NLS-2$
            schema.getParameters().add(parameter);
        }

        DcsReadProjection.Result first = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "parameter", "en", 100, 0, 750); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error(), first.isSuccess());
        assertTrue(first.markdown(), first.markdown().contains(
            "**Stopped by:** serialized character budget")); //$NON-NLS-1$
        int firstNext = metadataInt(first.markdown(), "Next offset"); //$NON-NLS-1$
        int rendered = 0;
        for (int i = 0; i < 16; i++)
        {
            rendered += namedTableRows(first.markdown(),
                "CollectionMarker" + i + "x".repeat(70)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals(firstNext, rendered);

        String pages = readAllItemPages(root, TargetKind.REPORT_MAIN_DCS, schema,
            "parameter", 100, 750); //$NON-NLS-1$
        for (int i = 0; i < 16; i++)
        {
            assertEquals(1, namedTableRows(pages,
                "CollectionMarker" + i + "x".repeat(70))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        DcsReadProjection.Result limited = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "parameter", "en", 2, 0, 100_000); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(limited.markdown(), limited.markdown().contains(
            "**Stopped by:** requested limit")); //$NON-NLS-1$
        assertEquals(2, metadataInt(limited.markdown(), "Next offset")); //$NON-NLS-1$

        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("LargePresentation"); //$NON-NLS-1$
        DcsPresentationParser.ParseResult presentation = DcsPresentationParser.parse(
            JsonParser.parseString("{\"en\":\"" + "p".repeat(10_000) + "\"}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new DcsPresentationParser.LanguageContext(java.util.Collections.singletonList("en")), //$NON-NLS-1$
            "variant.presentation"); //$NON-NLS-1$
        assertTrue(presentation.error(), presentation.isSuccess());
        variant.setPresentation(DcsPresentationParser.build(presentation.plan()));
        schema.getSettingsVariants().add(variant);
        DcsReadProjection.Result boundedCell = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "variant", "en", 100, 0, 5000); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(boundedCell.error(), boundedCell.isSuccess());
        assertTrue(boundedCell.markdown(), boundedCell.markdown().length() <= 5000);
        assertTrue(boundedCell.markdown(), boundedCell.markdown().contains(
            "characters; read `" + root + "#/variants/LargePresentation`")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testProductionPageFitsAfterHashAndWorstCaseMarkdownSignal()
    {
        String root = "Report.SerializedBudget"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        for (int i = 0; i < 400; i++)
        {
            DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory
                .eINSTANCE.createDataCompositionSchemaParameter();
            parameter.setName("WireMarker" + i + "<&>".repeat(70)); //$NON-NLS-1$ //$NON-NLS-2$
            schema.getParameters().add(parameter);
        }

        DcsReadProjection.Result page = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "parameter", "en", 1000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(page.error(), page.isSuccess());
        assertTrue(page.markdown(), page.markdown().contains(
            "**Stopped by:** serialized character budget")); //$NON-NLS-1$
        int finalClientCharacters = 34 + page.markdown().length()
            + McpProtocolHandler.MAX_MARKDOWN_USER_SIGNAL_AUGMENTATION_CHARS;
        assertTrue(page.markdown(), finalClientCharacters <= OutputSizeGuard.MAX_CONTENT_CHARS);
    }

    @Test
    public void testRootSummariesKeepCountsAndPageEveryAggregateRowExactlyOnce()
    {
        String reportRoot = "Report.LargeSummary"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        for (int i = 0; i < 18; i++)
        {
            schema.getDataSets().add(query("SummaryMarker" + i + "x".repeat(70), "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        DcsReadProjection.Result first = DcsReadProjection.render(reportRoot,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(reportRoot).address(),
            "schema", "en", 100, 0, 2500); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error(), first.isSuccess());
        assertTrue(first.markdown(), first.markdown().contains("| Data sets | 18 |")); //$NON-NLS-1$
        assertTrue(first.markdown(), first.markdown().contains(
            "**Stopped by:** serialized character budget")); //$NON-NLS-1$

        DcsReadProjection.Result limited = DcsReadProjection.render(reportRoot,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(reportRoot).address(),
            "schema", "en", 2, 0, 100_000); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(limited.markdown(), limited.markdown().contains(
            "**Stopped by:** requested limit")); //$NON-NLS-1$
        assertEquals(2, metadataInt(limited.markdown(), "Next offset")); //$NON-NLS-1$

        String reportPages = readAllItemPages(reportRoot, TargetKind.REPORT_MAIN_DCS,
            schema, "schema", 100, 2500); //$NON-NLS-1$
        for (int i = 0; i < 18; i++)
        {
            assertEquals(1, namedTableRows(reportPages,
                "SummaryMarker" + i + "x".repeat(70))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        DynamicListExtInfo dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        for (int i = 0; i < 12; i++)
        {
            DataCompositionSchemaDataSetField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory
                .eINSTANCE.createDataCompositionSchemaDataSetField();
            field.setDataPath("DynamicSummaryMarker" + i + "x".repeat(60)); //$NON-NLS-1$ //$NON-NLS-2$
            dynamic.getFields().add(field);
        }
        String dynamicRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        String dynamicPages = readAllItemPages(dynamicRoot, TargetKind.DYNAMIC_LIST,
            dynamic, "dynamicList", 100, 1800); //$NON-NLS-1$
        for (int i = 0; i < 12; i++)
        {
            assertEquals(1, namedTableRows(dynamicPages,
                "DynamicSummaryMarker" + i + "x".repeat(60))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testCutSummarySectionCarriesLocalContinuationMarkers()
    {
        String root = "Report.SectionPaging"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        for (int i = 0; i < 8; i++)
        {
            SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createSettingsVariant();
            variant.setName("Variant" + i); //$NON-NLS-1$
            schema.getSettingsVariants().add(variant);
        }

        DcsReadProjection.Result first = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "schema", "en", 3, 0, 100_000); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result middle = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "schema", "en", 3, 3, 100_000); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result last = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(root).address(),
            "schema", "en", 3, 6, 100_000); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(first.error(), first.isSuccess());
        assertTrue(first.markdown(), first.markdown().contains("| Variants | 8 |")); //$NON-NLS-1$
        assertTrue(first.markdown(), first.markdown().contains(
            "_(section continues at offset 3)_")); //$NON-NLS-1$
        assertTrue(middle.markdown(), middle.markdown().contains(
            "_(continued from an earlier page)_")); //$NON-NLS-1$
        assertTrue(middle.markdown(), middle.markdown().contains(
            "_(section continues at offset 6)_")); //$NON-NLS-1$
        assertTrue(last.markdown(), last.markdown().contains(
            "_(continued from an earlier page)_")); //$NON-NLS-1$
        assertFalse(last.markdown(), last.markdown().contains("section continues at offset")); //$NON-NLS-1$
    }

    @Test
    public void testVariantStructureCollectionRefusalExplainsReadWriteAsymmetry()
    {
        String root = "Report.StructureCollection"; //$NON-NLS-1$
        String settingsAddress = root + "#/variants/Mixed/settings"; //$NON-NLS-1$
        String address = root + "#/variants/Mixed/settings/items"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("Mixed"); //$NON-NLS-1$
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        settings.getItems().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionGroup());
        settings.getItems().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionTable());
        variant.setSettings(settings);
        schema.getSettingsVariants().add(variant);

        for (String type : new String[] {"grouping", "table"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            DcsReadProjection.Result refusedRoot = DcsReadProjection.render(root,
                TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(settingsAddress).address(),
                type, "en", 100, 0); //$NON-NLS-1$
            assertFalse(refusedRoot.isSuccess());
            assertTrue(refusedRoot.error(), refusedRoot.error().contains(
                address + "' and reads with type='userSettings'")); //$NON-NLS-1$
            assertTrue(refusedRoot.error(), refusedRoot.error().contains(address + "/<index>")); //$NON-NLS-1$
            assertTrue(refusedRoot.error(), refusedRoot.error().contains("For a write")); //$NON-NLS-1$

            DcsReadProjection.Result refused = DcsReadProjection.render(root,
                TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
                type, "en", 100, 0); //$NON-NLS-1$
            assertFalse(refused.isSuccess());
            assertTrue(refused.error(), refused.error().contains("type='userSettings'")); //$NON-NLS-1$
            assertTrue(refused.error(), refused.error().contains(address + "/<index>")); //$NON-NLS-1$
            assertTrue(refused.error(), refused.error().contains("For a write")); //$NON-NLS-1$
            assertTrue(refused.error(), refused.error().contains("describes the body")); //$NON-NLS-1$
        }

        DcsReadProjection.Result collection = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "userSettings", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result item = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address + "/0").address(),
            "grouping", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(collection.error(), collection.isSuccess());
        assertTrue(item.error(), item.isSuccess());

        DcsSettingsWriter.SchemaResult write = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "grouping", DcsAddress.parse(address).address(), //$NON-NLS-1$
            JsonParser.parseString("{\"name\":\"Appended\"}").getAsJsonObject(), //$NON-NLS-1$
            new DcsPresentationParser.LanguageContext(java.util.Arrays.asList("en"))); //$NON-NLS-1$
        assertTrue(write.error(), write.isSuccess());
    }

    @Test
    public void testGenericParameterItemsUseOwningHolderTypeForReadAndWrite()
    {
        String root = "Report.Parameters"; //$NON-NLS-1$
        DataCompositionSchema schema =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionDataParameterValues data = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionDataParameterValues();
        DataCompositionOutputParameterValues output = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionOutputParameterValues();
        SettingsParameterValue dataItem = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsParameterValue();
        SettingsParameterValue outputItem = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsParameterValue();
        data.getItems().add(dataItem);
        output.getItems().add(outputItem);
        settings.setDataParameters(data);
        settings.setOutputParameters(output);
        schema.setDefaultSettings(settings);
        String dataAddress = root + "#/defaultSettings/dataParameters/items/0"; //$NON-NLS-1$
        String outputAddress = root + "#/defaultSettings/outputParameters/items/0"; //$NON-NLS-1$
        String outline = DcsReadProjection.renderSettingsOutline(root + "#/defaultSettings", //$NON-NLS-1$
            settings, "en"); //$NON-NLS-1$
        assertTrue(outline, outline.contains(dataAddress));
        assertTrue(outline, outline.contains(outputAddress));

        DcsReadProjection.Result dataPage = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(dataAddress).address(),
            "dataParameter", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result outputPage = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(outputAddress).address(),
            "outputParameter", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dataPage.error(), dataPage.isSuccess());
        assertTrue(outputPage.error(), outputPage.isSuccess());

        DcsPresentationParser.LanguageContext languages =
            new DcsPresentationParser.LanguageContext(java.util.Arrays.asList("en")); //$NON-NLS-1$
        DcsSettingsWriter.SettingsResult dataWrite = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("dataParameters", "items", "0"), "update", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dataParameter", JsonParser.parseString("{\"use\":false}").getAsJsonObject(), //$NON-NLS-1$ //$NON-NLS-2$
            languages);
        DcsSettingsWriter.SettingsResult outputWrite = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("outputParameters", "items", "0"), "update", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "outputParameter", JsonParser.parseString("{\"use\":false}").getAsJsonObject(), //$NON-NLS-1$ //$NON-NLS-2$
            languages);
        assertTrue(dataWrite.error(), dataWrite.isSuccess());
        assertTrue(outputWrite.error(), outputWrite.isSuccess());
        assertFalse(((SettingsParameterValue)dataWrite.settings().getDataParameters().getItems()
            .get(0)).isUse());
        assertFalse(((SettingsParameterValue)outputWrite.settings().getOutputParameters().getItems()
            .get(0)).isUse());
    }

    @Test
    public void testDynamicListSummaryAdvertisesPagedByteExactQueryText()
    {
        DynamicListExtInfo list = FormFactory.eINSTANCE.createDynamicListExtInfo();
        String query = "SELECT Ref, Description\nFROM Catalog.Products"; //$NON-NLS-1$
        list.eSet(list.eClass().getEStructuralFeature("queryText"), query); //$NON-NLS-1$
        String root = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        String address = root + "#/queryText"; //$NON-NLS-1$

        DcsReadProjection.Result summary = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            list, DcsAddress.parse(root).address(), "dynamicList", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(summary.error(), summary.isSuccess());
        assertTrue(summary.markdown(), summary.markdown().contains(address));
        assertTrue(summary.markdown(), summary.markdown().contains(query.length() + " characters")); //$NON-NLS-1$
        assertFalse(summary.markdown(), summary.markdown().contains(query));

        DcsReadProjection.Result first = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            list, DcsAddress.parse(address).address(), "dynamicList", "en", 12, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result second = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            list, DcsAddress.parse(address).address(), "dynamicList", "en", 100, 12); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error(), first.isSuccess());
        assertTrue(second.error(), second.isSuccess());
        assertEquals(query.substring(0, 12), fencedValue(first.markdown()));
        assertEquals(query.substring(12), fencedValue(second.markdown()));
        assertTrue(first.markdown(), first.markdown().contains("**Page characters:** 12")); //$NON-NLS-1$
        assertTrue(first.markdown(), first.markdown().contains("**Next offset:** 12")); //$NON-NLS-1$
        assertTrue(second.markdown(), second.markdown().contains("**Next offset:** none")); //$NON-NLS-1$
    }

    @Test
    public void testDynamicListScalarPagesNeverSplitSurrogatePairs()
    {
        DynamicListExtInfo list = FormFactory.eINSTANCE.createDynamicListExtInfo();
        String query = "A\uD83D\uDE00BC"; //$NON-NLS-1$
        list.eSet(list.eClass().getEStructuralFeature("queryText"), query); //$NON-NLS-1$
        String root = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        String address = root + "#/queryText"; //$NON-NLS-1$

        DcsReadProjection.Result first = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            list, DcsAddress.parse(address).address(), "dynamicList", "en", 2, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result second = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            list, DcsAddress.parse(address).address(), "dynamicList", "en", 100, 1); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error(), first.isSuccess());
        assertTrue(second.error(), second.isSuccess());

        String firstPage = fencedValue(first.markdown());
        String secondPage = fencedValue(second.markdown());
        assertEquals(query, firstPage + secondPage);
        assertFalse(hasUnpairedSurrogate(firstPage));
        assertFalse(hasUnpairedSurrogate(secondPage));
        assertTrue(first.markdown(), first.markdown().contains("**Next offset:** 1")); //$NON-NLS-1$
        assertTrue(second.markdown(), second.markdown().contains("**Offset:** 1")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaSummaryAndSchemaReadsExposeDataSetLinks()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetLink link = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("Sales|Retail"); //$NON-NLS-1$
        link.setDestinationDataSet("Archive"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);

        DcsReadProjection.Result summary = render(schema, "Report.Sales", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(summary.error(), summary.isSuccess());
        assertTrue(summary.markdown(), summary.markdown().contains("| Data set links | 1 |")); //$NON-NLS-1$
        assertTrue(summary.markdown(), summary.markdown().contains("Report.Sales#/dataSetLinks")); //$NON-NLS-1$
        assertTrue(summary.markdown(), summary.markdown().contains("Sales\\|Retail → Archive")); //$NON-NLS-1$

        DcsReadProjection.Result collection = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/dataSetLinks").address(), "schema", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(collection.error(), collection.isSuccess());
        assertTrue(collection.markdown(), collection.markdown().contains("Report.Sales#/dataSetLinks/0")); //$NON-NLS-1$

        DcsReadProjection.Result exact = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/dataSetLinks/0").address(), "schema", "en", 1000, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(exact.error(), exact.isSuccess());
        assertTrue(exact.markdown(), exact.markdown().contains("Sales\\|Retail")); //$NON-NLS-1$
        assertTrue(exact.markdown(), exact.markdown().contains("Archive")); //$NON-NLS-1$
    }

    @Test
    public void testExtendedSchemaMembersWrittenAtAParentAreRenderedBelowThatSameAddress()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DcsWriter.Result written = DcsWriter.apply(schema, JsonParser.parseString("{" //$NON-NLS-1$
            + "\"dataSets\":[{\"name\":\"Sales\",\"type\":\"query\",\"query\":\"SELECT Code\"," //$NON-NLS-1$
            + "\"fields\":[{\"dataPath\":\"Code\",\"attributeUseRestriction\":{\"field\":true}," //$NON-NLS-1$
            + "\"presentationExpression\":\"Present(Code)\",\"availableValues\":[{" //$NON-NLS-1$
            + "\"value\":{\"kind\":\"string\",\"value\":\"A\"},\"presentation\":\"Alpha\"}]}]}]," //$NON-NLS-1$
            + "\"parameters\":[{\"name\":\"P\",\"values\":[{\"kind\":\"string\",\"value\":\"D\"}]," //$NON-NLS-1$
            + "\"valueListAllowed\":true}]}" //$NON-NLS-1$
        ).getAsJsonObject(), null);
        assertFalse(written.error, written.hasError());

        DcsReadProjection.Result field = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/dataSets/Sales/fields/Code").address(), //$NON-NLS-1$
            "field", "en", 10_000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result parameter = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/parameters/P").address(), //$NON-NLS-1$
            "parameter", "en", 10_000, 0); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(field.error(), field.isSuccess());
        assertTrue(field.markdown(), field.markdown().contains("attributeUseRestriction")); //$NON-NLS-1$
        assertTrue(field.markdown(), field.markdown().contains(
            "| presentationExpression | Present(Code) |")); //$NON-NLS-1$
        assertTrue(field.markdown(), field.markdown().contains("#/dataSets/Sales/fields/Code/availableValues")); //$NON-NLS-1$
        assertTrue(parameter.error(), parameter.isSuccess());
        assertTrue(parameter.markdown(), parameter.markdown().contains("| valueListAllowed | true |")); //$NON-NLS-1$
        assertTrue(parameter.markdown(), parameter.markdown().contains("#/parameters/P/values")); //$NON-NLS-1$
    }

    @Test
    public void testDataSetLinkParameterIsReportedAsAParameterReference()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetLink link = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setParameter("LinkParameter"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);

        assertEquals(java.util.Arrays.asList("Report.Sales#/dataSetLinks/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, "Report.Sales", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
                "LinkParameter")); //$NON-NLS-1$
    }

    @Test
    public void testStructuredReferencesMatchIdentitiesCaseInsensitively()
    {
        String root = "Report.Sales"; //$NON-NLS-1$
        DataCompositionSchema schema = schemaWithDataSet("Retail", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        ((DataCompositionSchemaDataSetQuery)schema.getDataSets().get(0))
            .setDataSource("mysource"); //$NON-NLS-1$

        DataCompositionSchemaDataSetLink link = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory
            .eINSTANCE.createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("sales"); //$NON-NLS-1$
        link.setDestinationDataSet("archive"); //$NON-NLS-1$
        link.setParameter("linkparameter"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);

        DataCompositionSettings settings = settingsWithSelection("revenue"); //$NON-NLS-1$
        DataCompositionDataParameterValues parameters = com._1c.g5.v8.dt.dcs.model.settings
            .DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
        SettingsParameterValue parameterValue = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createSettingsParameterValue();
        DataCompositionParameter parameter = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionParameter();
        parameter.setValue("period"); //$NON-NLS-1$
        parameterValue.setParameter(parameter);
        parameters.getItems().add(parameterValue);
        settings.setDataParameters(parameters);
        schema.setDefaultSettings(settings);

        assertEquals(java.util.Arrays.asList(
            root + "#/defaultSettings/selection/items/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "field", "Revenue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.Arrays.asList(
            root + "#/defaultSettings/dataParameters/items/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "parameter", "Period")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.Arrays.asList(root + "#/dataSetLinks/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "parameter", //$NON-NLS-1$
                "LinkParameter")); //$NON-NLS-1$
        assertEquals(java.util.Arrays.asList(root + "#/dataSetLinks/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "dataSet", "Sales")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.Arrays.asList(root + "#/dataSetLinks/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "dataSet", "Archive")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.Arrays.asList(root + "#/dataSets/Retail"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "dataSource", "MySource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExpressionSuffixAndLinkConditionAttributesReportTheirOwningLinks()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(query("Archive", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetLink sourceExpression =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetLink();
        sourceExpression.setSourceDataSet("Sales"); //$NON-NLS-1$
        sourceExpression.setDestinationDataSet("Archive"); //$NON-NLS-1$
        sourceExpression.setSourceExpression("Amount"); //$NON-NLS-1$
        schema.getDataSetLinks().add(sourceExpression);
        DataCompositionSchemaDataSetLink linkCondition =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetLink();
        linkCondition.setSourceDataSet("Sales"); //$NON-NLS-1$
        linkCondition.setDestinationDataSet("Archive"); //$NON-NLS-1$
        linkCondition.setLinkConditionExpression("Amount > 0"); //$NON-NLS-1$
        schema.getDataSetLinks().add(linkCondition);

        assertEquals(java.util.Arrays.asList("Report.Sales#/dataSetLinks/0", //$NON-NLS-1$
            "Report.Sales#/dataSetLinks/1"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, "Report.Sales", "field", "Amount")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testUserFieldReferenceScanReadsDetailAndTotalExpressionsButNotPresentations()
    {
        String root = "Report.UserFieldExpressions"; //$NON-NLS-1$
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionUserFields userFields = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionUserFields();

        DataCompositionUserFieldExpression detail =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionUserFieldExpression();
        detail.setDataPath("Net"); //$NON-NLS-1$
        detail.setDetailExpression("Gross - Tax"); //$NON-NLS-1$
        detail.setDetailExpressionPresentation("PresentationOnly"); //$NON-NLS-1$
        userFields.getItems().add(detail);

        DataCompositionUserFieldExpression total =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionUserFieldExpression();
        total.setDataPath("GrossTotal"); //$NON-NLS-1$
        total.setTotalExpression("SUM(Gross)"); //$NON-NLS-1$
        total.setTotalExpressionPresentation("AlsoPresentationOnly"); //$NON-NLS-1$
        userFields.getItems().add(total);
        settings.setUserFields(userFields);
        schema.setDefaultSettings(settings);

        assertEquals(java.util.Arrays.asList(
            root + "#/defaultSettings/userFields/items/0", //$NON-NLS-1$
            root + "#/defaultSettings/userFields/items/1"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "userField", "Gross")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.Collections.singletonList(
            root + "#/defaultSettings/userFields/items/0"), //$NON-NLS-1$
            DcsReadProjection.referenceAddresses(schema, root, "userField", "Tax")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DcsReadProjection.referenceAddresses(schema, root, "userField", //$NON-NLS-1$
            "PresentationOnly").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testBareCollectionUsesSharedPaginationAndCanonicalAddresses()
    {
        DataCompositionSchema schema = schemaWithDataSet("First", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(query("Second", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        DcsAddress address = DcsAddress.parse("Report.Sales").address(); //$NON-NLS-1$
        DcsReadProjection.Result result = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, address, "dataSet", "en", 1, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.isSuccess());
        assertTrue(result.markdown().contains("showing 1 of 2")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("**Next offset:** 1")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Report.Sales#/dataSets/First")); //$NON-NLS-1$
        assertFalse(result.markdown().contains("Report.Sales#/dataSets/Second")); //$NON-NLS-1$
    }

    @Test
    public void testPointerDataSetAdvertisesPagedQueryAndCompleteFieldAddress()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT\n  Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsAddress address = DcsAddress.parse("Report.Sales#/dataSets/Sales").address(); //$NON-NLS-1$
        DcsReadProjection.Result result = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, address, "dataSet", "en", 1000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.isSuccess());
        assertFalse(result.markdown().contains("```sql\nSELECT\n  Amount\n```")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("**Characters:** 15")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Report.Sales#/dataSets/Sales/query")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Report.Sales#/dataSets/Sales")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Report.Sales#/dataSets/Sales/fields/Amount")); //$NON-NLS-1$
    }

    @Test
    public void testDataSetQueryScalarUsesTheSharedSurrogateSafePager()
    {
        String query = "A\uD83D\uDE00BC"; //$NON-NLS-1$
        DataCompositionSchema schema = schemaWithDataSet("Sales", query); //$NON-NLS-1$
        String root = "Report.Sales"; //$NON-NLS-1$
        String address = root + "#/dataSets/Sales/query"; //$NON-NLS-1$

        DcsReadProjection.Result first = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "dataSet", "en", 2, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result second = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "dataSet", "en", 100, 1); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(first.error(), first.isSuccess());
        assertTrue(second.error(), second.isSuccess());

        String firstPage = fencedValue(first.markdown());
        String secondPage = fencedValue(second.markdown());
        assertEquals(query, firstPage + secondPage);
        assertFalse(hasUnpairedSurrogate(firstPage));
        assertFalse(hasUnpairedSurrogate(secondPage));
        assertTrue(first.markdown(), first.markdown().contains("**Next offset:** 1")); //$NON-NLS-1$

        DcsReadProjection.Result wrongType = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(),
            "userSettings", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(wrongType.isSuccess());
        assertTrue(wrongType.error(), wrongType.error().contains("its type is 'dataSet'")); //$NON-NLS-1$
    }

    @Test
    public void testBadPointerNamesFailedSegmentAndExistingKeys()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsAddress address = DcsAddress.parse("Report.Sales#/dataSets/Missing").address(); //$NON-NLS-1$
        DcsReadProjection.Result result = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, address, "dataSet", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("Missing")); //$NON-NLS-1$
        assertTrue(result.error().contains("Sales")); //$NON-NLS-1$
        assertTrue(result.error().contains("Existing keys/indices")); //$NON-NLS-1$
    }

    @Test
    public void testKnownButUnsetSettingsFeatureDoesNotContradictItsNavigationKeys()
    {
        String root = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        DynamicListExtInfo dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        dynamic.setListSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings());
        String settingsAddress = root + "#/listSettings"; //$NON-NLS-1$
        String userFieldsAddress = settingsAddress + "/userFields"; //$NON-NLS-1$

        DcsReadProjection.Result unset = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            dynamic, DcsAddress.parse(userFieldsAddress).address(), "userField", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(unset.isSuccess());
        assertTrue(unset.error(), unset.error().contains("names a feature on '" //$NON-NLS-1$
            + settingsAddress + "'")); //$NON-NLS-1$
        assertTrue(unset.error(), unset.error().contains("feature is not set")); //$NON-NLS-1$
        assertTrue(unset.error(), unset.error().contains("action='upsert'")); //$NON-NLS-1$
        assertTrue(unset.error(), unset.error().contains(userFieldsAddress));
        assertFalse(unset.error(), unset.error().contains("Existing keys/indices")); //$NON-NLS-1$

        DcsReadProjection.Result unknown = DcsReadProjection.render(root, TargetKind.DYNAMIC_LIST,
            dynamic, DcsAddress.parse(settingsAddress + "/notAFeature").address(), //$NON-NLS-1$
            "userField", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(unknown.isSuccess());
        assertTrue(unknown.error(), unknown.error().contains("Existing keys/indices")); //$NON-NLS-1$
        assertTrue(unknown.error(), unknown.error().contains("userFields")); //$NON-NLS-1$
    }

    @Test
    public void testCaseVariantsAddressAdvertisedByParentResolvesAgainstCaseOwner()
    {
        String root = "Report.CaseVariants"; //$NON-NLS-1$
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionUserFields userFields =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionUserFields();
        DataCompositionUserFieldCase caseField =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionUserFieldCase();
        DataCompositionUserFieldsCaseVariants variants =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionUserFieldsCaseVariants();
        DataCompositionUserFieldsVariant variant =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionUserFieldsVariant();
        variant.setUse(true);
        variants.getItems().add(variant);
        caseField.setVariants(variants);
        userFields.getItems().add(caseField);
        settings.setUserFields(userFields);
        schema.setDefaultSettings(settings);

        String fieldAddress = root + "#/defaultSettings/userFields/items/0"; //$NON-NLS-1$
        String variantAddress = fieldAddress + "/variants/items/0"; //$NON-NLS-1$
        DcsReadProjection.Result parent = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(fieldAddress).address(),
            "userField", "en", 1000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result exact = DcsReadProjection.render(root,
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(variantAddress).address(),
            "userField", "en", 1000, 0); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(parent.error(), parent.isSuccess());
        assertTrue(parent.markdown(), parent.markdown().contains(variantAddress));
        assertTrue(exact.error(), exact.isSuccess());
        assertTrue(exact.markdown(), exact.markdown().contains("DataCompositionUserFieldsVariant")); //$NON-NLS-1$
    }

    @Test
    public void testVariantPresentationAppearsInExactNodeAndCollectionReads()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("Main"); //$NON-NLS-1$
        DcsPresentationParser.ParseResult parsed = DcsPresentationParser.parse(
            JsonParser.parseString("{\"en\":\"Main variant\"}"), //$NON-NLS-1$
            new DcsPresentationParser.LanguageContext(java.util.Collections.singletonList("en")), //$NON-NLS-1$
            "variant.presentation"); //$NON-NLS-1$
        assertTrue(parsed.error(), parsed.isSuccess());
        variant.setPresentation(DcsPresentationParser.build(parsed.plan()));
        variant.setSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings());
        schema.getSettingsVariants().add(variant);

        DcsReadProjection.Result exact = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/variants/Main").address(), "variant", "en", 1000, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsReadProjection.Result collection = render(schema, "Report.Sales", "variant"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(exact.error(), exact.isSuccess());
        assertTrue(exact.markdown(), exact.markdown().contains("Main variant")); //$NON-NLS-1$
        assertTrue(exact.markdown(), exact.markdown().contains("#/variants/Main/presentation")); //$NON-NLS-1$
        assertTrue(collection.error(), collection.isSuccess());
        assertTrue(collection.markdown(), collection.markdown().contains("Main variant")); //$NON-NLS-1$
    }

    @Test
    public void testRootFieldPageRecursesIntoUnionAndPrintsResolvableAddressOnce()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSchemaDataSetUnion union =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetUnion();
        union.setName("AllSales"); //$NON-NLS-1$
        DataCompositionSchemaDataSetQuery member = query("Retail", "SELECT 1 AS MemberAmount"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetField();
        field.setDataPath("MemberAmount"); //$NON-NLS-1$
        member.getFields().add(field);
        union.getItems().add(member);
        schema.getDataSets().add(union);

        DcsReadProjection.Result page = render(schema, "Report.Sales", "field"); //$NON-NLS-1$ //$NON-NLS-2$
        String copied = "Report.Sales#/dataSets/AllSales/items/Retail/fields/MemberAmount"; //$NON-NLS-1$
        assertTrue(page.error(), page.isSuccess());
        assertTrue(page.markdown(), page.markdown().contains(copied));
        assertFalse(page.markdown(), page.markdown().contains("/fields/fields/")); //$NON-NLS-1$

        DcsReadProjection.Result resolved = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(copied).address(), "field", //$NON-NLS-1$
            "en", 1000, 0); //$NON-NLS-1$
        assertTrue(resolved.error(), resolved.isSuccess());
        assertTrue(resolved.markdown(), resolved.markdown().contains("MemberAmount")); //$NON-NLS-1$
    }

    @Test
    public void testFailedPointerBoundsLargeExistingKeyList()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetQuery dataSet = (DataCompositionSchemaDataSetQuery)
            schema.getDataSets().get(0);
        dataSet.getFields().clear();
        for (int i = 0; i < 25; i++)
        {
            DataCompositionSchemaDataSetField field =
                com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                    .createDataCompositionSchemaDataSetField();
            field.setDataPath(String.format("Field%02d", i)); //$NON-NLS-1$
            dataSet.getFields().add(field);
        }

        DcsReadProjection.Result result = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/dataSets/Sales/fields/Missing").address(), //$NON-NLS-1$
            "field", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("Field19")); //$NON-NLS-1$
        assertFalse(result.error(), result.error().contains("Field20")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("(5 more)")); //$NON-NLS-1$
    }

    @Test
    public void testReportAndDynamicListSettingsUseSameAddressAwareOutline()
    {
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionOrder order = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionOrder();
        DataCompositionOrderItem item = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionOrderItem();
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionField();
        field.setValue("Amount"); //$NON-NLS-1$
        item.setField(field);
        order.getItems().add(item);
        settings.setOrder(order);
        DataCompositionGroup namedGroup =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionGroup();
        namedGroup.setName("ByCustomer"); //$NON-NLS-1$
        settings.getItems().add(namedGroup);

        String report = DcsReadProjection.renderSettingsOutline(
            "Report.Sales#/defaultSettings", settings, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String dynamic = DcsReadProjection.renderSettingsOutline(
            "Catalog.Products.Form.ListForm.Attribute.List#/listSettings", settings, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.contains("#/defaultSettings/order/items/0")); //$NON-NLS-1$
        assertTrue(dynamic.contains("#/listSettings/order/items/0")); //$NON-NLS-1$
        assertTrue(report.contains("#/defaultSettings/items/0")); //$NON-NLS-1$
        assertTrue(dynamic.contains("#/listSettings/items/0")); //$NON-NLS-1$
        assertFalse(report.contains("#/defaultSettings/items/ByCustomer")); //$NON-NLS-1$
        assertTrue(report.contains("DataCompositionOrderItem")); //$NON-NLS-1$
        assertTrue(dynamic.contains("DataCompositionOrderItem")); //$NON-NLS-1$
    }

    @Test
    public void testSettingsCollectionAddressesUseTheirOwnerPublicType()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionSelectedFields selection =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionSelectedFields();
        settings.setSelection(selection);
        DataCompositionFilter filter = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionFilter();
        settings.setFilter(filter);
        DataCompositionOrder order = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionOrder();
        settings.setOrder(order);
        DataCompositionConditionalAppearance conditionalAppearance =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionConditionalAppearance();
        DataCompositionConditionalAppearanceItem appearanceItem =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionConditionalAppearanceItem();
        DataCompositionAppearanceFields appearance =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionAppearanceFields();
        appearanceItem.setSelection(appearance);
        appearanceItem.setAppearance(com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionAppearance());
        conditionalAppearance.getItems().add(appearanceItem);
        settings.setConditionalAppearance(conditionalAppearance);
        DataCompositionGroup group = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionGroup();
        DataCompositionConditionalAppearance groupAppearance =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createDataCompositionConditionalAppearance();
        groupAppearance.getItems().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionConditionalAppearanceItem());
        group.setConditionalAppearance(groupAppearance);
        group.getItems().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionGroup());
        settings.getItems().add(group);
        DataCompositionTable table = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionTable();
        DataCompositionTableGroup row = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionTableGroup();
        DataCompositionTableGroup column = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionTableGroup();
        table.getRows().add(row);
        table.getColumns().add(column);
        settings.getItems().add(table);
        schema.setDefaultSettings(settings);

        assertCollectionType(schema, "Report.Sales#/defaultSettings/selection/items", "selection"); //$NON-NLS-1$ //$NON-NLS-2$
        assertCollectionType(schema, "Report.Sales#/defaultSettings/filter/items", "filter"); //$NON-NLS-1$ //$NON-NLS-2$
        assertCollectionType(schema, "Report.Sales#/defaultSettings/order/items", "order"); //$NON-NLS-1$ //$NON-NLS-2$
        assertCollectionType(schema,
            "Report.Sales#/defaultSettings/conditionalAppearance/items", //$NON-NLS-1$
            "conditionalAppearance"); //$NON-NLS-1$
        assertCollectionType(schema,
            "Report.Sales#/defaultSettings/conditionalAppearance/items/0/selection/items", //$NON-NLS-1$
            "conditionalAppearance"); //$NON-NLS-1$
        assertCollectionType(schema,
            "Report.Sales#/defaultSettings/conditionalAppearance/items/0/appearance/items", //$NON-NLS-1$
            "conditionalAppearance"); //$NON-NLS-1$
        assertCollectionType(schema, "Report.Sales#/defaultSettings/items", "userSettings"); //$NON-NLS-1$ //$NON-NLS-2$
        assertCollectionType(schema, "Report.Sales#/defaultSettings/items/0/items", "grouping"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsReadProjection.Result groupAppearanceRead = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(
                "Report.Sales#/defaultSettings/items/0/conditionalAppearance").address(), //$NON-NLS-1$
            "conditionalAppearance", "en", 100_000, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(groupAppearanceRead.error(), groupAppearanceRead.isSuccess());
        assertTrue(groupAppearanceRead.markdown(), groupAppearanceRead.markdown().contains(
            "Report.Sales#/defaultSettings/items/0/conditionalAppearance/items/0")); //$NON-NLS-1$
        assertCollectionType(schema, "Report.Sales#/defaultSettings/items/1/rows", "table"); //$NON-NLS-1$ //$NON-NLS-2$
        assertCollectionType(schema, "Report.Sales#/defaultSettings/items/1/columns", "table"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertCollectionType(DataCompositionSchema schema, String address,
        String type)
    {
        DcsReadProjection.Result result = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, DcsAddress.parse(address).address(), type,
            "en", 100, 0); //$NON-NLS-1$
        assertTrue(result.error(), result.isSuccess());
        assertTrue(result.markdown(), result.markdown().contains("# DCS collection: " + type)); //$NON-NLS-1$
    }

    private static DcsReadProjection.Result render(DataCompositionSchema schema, String fqn,
        String type)
    {
        return DcsReadProjection.render(fqn, TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse(fqn).address(), type, "en", 100, 0); //$NON-NLS-1$
    }

    private static DataCompositionSchema schemaWithDataSet(String name, String queryText)
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery dataSet = query(name, queryText);
        DataCompositionSchemaDataSetField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetField();
        field.setDataPath("Amount"); //$NON-NLS-1$
        field.setField("Sales.Amount"); //$NON-NLS-1$
        dataSet.getFields().add(field);
        schema.getDataSets().add(dataSet);
        return schema;
    }

    private static DataCompositionSchemaDataSetQuery query(String name, String text)
    {
        DataCompositionSchemaDataSetQuery dataSet = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        dataSet.setName(name);
        dataSet.setQuery(text);
        return dataSet;
    }

    private static DataCompositionSettings settingsWithSelection(String value)
    {
        DataCompositionSettings settings = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        DataCompositionSelectedFields selection = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionSelectedFields();
        DataCompositionSelectedField selected = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionSelectedField();
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionField();
        field.setValue(value);
        selected.setField(field);
        selection.getItems().add(selected);
        settings.setSelection(selection);
        return settings;
    }

    private static String fencedValue(String markdown)
    {
        String opening = "```sql\n"; //$NON-NLS-1$
        int start = markdown.indexOf(opening) + opening.length();
        int end = markdown.lastIndexOf("\n```"); //$NON-NLS-1$
        return markdown.substring(start, end);
    }

    private static String readAllTextPages(String root, EObject rootObject, String address,
        String type, int maxPageChars)
    {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        for (int page = 0; page < 1000; page++)
        {
            DcsReadProjection.Result rendered = DcsReadProjection.render(root,
                TargetKind.REPORT_MAIN_DCS, rootObject, DcsAddress.parse(address).address(),
                type, "en", 100_000, offset, maxPageChars); //$NON-NLS-1$
            assertTrue(rendered.error(), rendered.isSuccess());
            assertTrue(rendered.markdown(), rendered.markdown().length() <= maxPageChars);
            result.append(textPageValue(rendered.markdown()));
            String next = metadataValue(rendered.markdown(), "Next offset"); //$NON-NLS-1$
            if ("none".equals(next)) //$NON-NLS-1$
            {
                return result.toString();
            }
            int following = Integer.parseInt(next);
            assertTrue("a text continuation must advance", following > offset); //$NON-NLS-1$
            offset = following;
        }
        throw new AssertionError("text pagination did not terminate"); //$NON-NLS-1$
    }

    private static void assertCharacterPageUtilization(String root, TargetKind kind,
        EObject rootObject, String address, String type, int pageBudget)
    {
        DcsReadProjection.Result defaultPage = DcsReadProjection.render(root, kind, rootObject,
            DcsAddress.parse(address).address(), type, "en", null, 0, pageBudget); //$NON-NLS-1$
        assertBusyCharacterPage(defaultPage, DcsXmlCodec.DEFAULT_CHUNK_CHARS,
            pageBudget);
        assertEquals(DcsXmlCodec.DEFAULT_CHUNK_CHARS,
            metadataInt(defaultPage.markdown(), "Page characters")); //$NON-NLS-1$
        assertTrue(defaultPage.markdown(), defaultPage.markdown().contains(
            "**Stopped by:** requested limit")); //$NON-NLS-1$

        DcsReadProjection.Result clampedPage = DcsReadProjection.render(root, kind, rootObject,
            DcsAddress.parse(address).address(), type, "en", Integer.valueOf(100_000), //$NON-NLS-1$
            0, pageBudget);
        assertBusyCharacterPage(clampedPage, pageBudget, pageBudget);
        assertTrue(clampedPage.markdown(), clampedPage.markdown().contains(
            "**Stopped by:** serialized character budget")); //$NON-NLS-1$
    }

    private static void assertBusyCharacterPage(DcsReadProjection.Result page,
        int effectiveLimit, int pageBudget)
    {
        assertTrue(page.error(), page.isSuccess());
        assertFalse(page.markdown(), "none".equals(metadataValue(page.markdown(), //$NON-NLS-1$
            "Next offset"))); //$NON-NLS-1$
        int pageCharacters = metadataInt(page.markdown(), "Page characters"); //$NON-NLS-1$
        assertTrue(page.markdown(), pageCharacters >= effectiveLimit / 2);
        assertTrue(page.markdown(), page.markdown().length() <= pageBudget);
    }

    private static String readAllItemPages(String root, TargetKind kind, EObject rootObject,
        String type, int limit, int maxPageChars)
    {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        for (int page = 0; page < 1000; page++)
        {
            DcsReadProjection.Result rendered = DcsReadProjection.render(root, kind, rootObject,
                DcsAddress.parse(root).address(), type, "en", limit, offset, maxPageChars); //$NON-NLS-1$
            assertTrue(rendered.error(), rendered.isSuccess());
            assertTrue(rendered.markdown(), rendered.markdown().length() <= maxPageChars);
            result.append(rendered.markdown());
            String next = metadataValue(rendered.markdown(), "Next offset"); //$NON-NLS-1$
            if ("none".equals(next)) //$NON-NLS-1$
            {
                return result.toString();
            }
            int following = Integer.parseInt(next);
            assertTrue("an item continuation must advance", following > offset); //$NON-NLS-1$
            offset = following;
        }
        throw new AssertionError("item pagination did not terminate"); //$NON-NLS-1$
    }

    private static String textPageValue(String markdown)
    {
        int characters = metadataInt(markdown, "Page characters"); //$NON-NLS-1$
        String heading = "## Value\n\n"; //$NON-NLS-1$
        int start = markdown.indexOf(heading);
        assertTrue(markdown, start >= 0);
        start += heading.length();
        return markdown.substring(start, start + characters);
    }

    private static int metadataInt(String markdown, String label)
    {
        return Integer.parseInt(metadataValue(markdown, label));
    }

    private static String metadataValue(String markdown, String label)
    {
        Matcher matcher = Pattern.compile("\\*\\*" + Pattern.quote(label) //$NON-NLS-1$
            + ":\\*\\* ([^\\r\\n]+)").matcher(markdown); //$NON-NLS-1$
        assertTrue(markdown, matcher.find());
        return matcher.group(1);
    }

    private static int occurrences(String text, String value)
    {
        int count = 0;
        int from = 0;
        while (true)
        {
            int found = text.indexOf(value, from);
            if (found < 0)
            {
                return count;
            }
            count++;
            from = found + value.length();
        }
    }

    private static int namedTableRows(String markdown, String name)
    {
        Matcher matcher = Pattern.compile("(?m)^\\| " + Pattern.quote(name) + " \\|") //$NON-NLS-1$ //$NON-NLS-2$
            .matcher(markdown);
        int count = 0;
        while (matcher.find())
        {
            count++;
        }
        return count;
    }

    private static boolean hasUnpairedSurrogate(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current))
            {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                {
                    return true;
                }
                i++;
            }
            else if (Character.isLowSurrogate(current))
            {
                return true;
            }
        }
        return false;
    }
    private static com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField selectedField(
        String value)
    {
        com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField selected =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionSelectedField();
        com._1c.g5.v8.dt.dcs.model.core.DataCompositionField field =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        field.setValue(value);
        selected.setField(field);
        return selected;
    }

}

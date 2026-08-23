/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrder;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;
import com.google.gson.JsonParser;

/** Pure summary, pagination, pointer and address-printing tests. */
public class DcsReadProjectionTest
{
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
            DcsAddress.parse("Report.Sales#/dataSetLinks/0").address(), "schema", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(exact.error(), exact.isSuccess());
        assertTrue(exact.markdown(), exact.markdown().contains("Sales\\|Retail")); //$NON-NLS-1$
        assertTrue(exact.markdown(), exact.markdown().contains("Archive")); //$NON-NLS-1$
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
    public void testPointerDataSetRendersFullQueryAndCompleteFieldAddress()
    {
        DataCompositionSchema schema = schemaWithDataSet("Sales", "SELECT\n  Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsAddress address = DcsAddress.parse("Report.Sales#/dataSets/Sales").address(); //$NON-NLS-1$
        DcsReadProjection.Result result = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS, schema, address, "dataSet", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.isSuccess());
        assertTrue(result.markdown().contains("```sql\nSELECT\n  Amount\n```")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Report.Sales#/dataSets/Sales")); //$NON-NLS-1$
        assertTrue(result.markdown().contains("Report.Sales#/dataSets/Sales/fields/Amount")); //$NON-NLS-1$
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
            DcsAddress.parse("Report.Sales#/variants/Main").address(), "variant", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
            "en", 100, 0); //$NON-NLS-1$
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
        group.getItems().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionGroup());
        settings.getItems().add(group);
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
        assertCollectionType(schema, "Report.Sales#/defaultSettings/items/0/items", "grouping"); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static String fencedValue(String markdown)
    {
        String opening = "```sql\n"; //$NON-NLS-1$
        int start = markdown.indexOf(opening) + opening.length();
        int end = markdown.lastIndexOf("\n```"); //$NON-NLS-1$
        return markdown.substring(start, end);
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrder;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

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
}

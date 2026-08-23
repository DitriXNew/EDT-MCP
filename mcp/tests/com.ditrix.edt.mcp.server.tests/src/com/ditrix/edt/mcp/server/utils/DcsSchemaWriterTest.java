/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetFieldFolder;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSource;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Unit contract for node-addressed schema upsert/update semantics. */
public class DcsSchemaWriterTest
{
    @Test
    public void testUpsertCreatesThenUpdatesNaturalKeyWithoutDuplicate()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result created = apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"Sales\",\"type\":\"query\",\"query\":\"SELECT 1\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result updated = apply(schema, "upsert", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales", "{\"query\":\"SELECT 2\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(created.error(), created.isSuccess());
        assertTrue(updated.error(), updated.isSuccess());
        assertEquals(1, schema.getDataSets().size());
        assertEquals("SELECT 2", query(schema).getQuery()); //$NON-NLS-1$
    }

    @Test
    public void testSchemaUpsertCreatesAndRecursivelyUpsertsUnionItemsAndFields()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result created = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"type\":\"query\",\"query\":\"SELECT 1 AS Amount\"," //$NON-NLS-1$
                + "\"autoFillFields\":false,\"fields\":[{\"dataPath\":\"Amount\"}]}," //$NON-NLS-1$
                + "{\"name\":\"Products\",\"type\":\"object\"," //$NON-NLS-1$
                + "\"objectName\":\"Catalog.Products\",\"fields\":[{\"dataPath\":\"Ref\"}]}," //$NON-NLS-1$
                + "{\"name\":\"Nested\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Wholesale\",\"type\":\"query\"," //$NON-NLS-1$
                + "\"query\":\"SELECT 2 AS Quantity\",\"fields\":[{\"dataPath\":\"Quantity\"}]}]}]}]}"); //$NON-NLS-1$

        assertTrue(created.error(), created.isSuccess());
        assertEquals(1, schema.getDataSets().size());
        DataCompositionSchemaDataSetUnion union = (DataCompositionSchemaDataSetUnion)
            schema.getDataSets().get(0);
        assertEquals("AllSales", union.getName()); //$NON-NLS-1$
        assertEquals(3, union.getItems().size());
        DataCompositionSchemaDataSetQuery retail = (DataCompositionSchemaDataSetQuery)
            union.getItems().get(0);
        assertEquals("SELECT 1 AS Amount", retail.getQuery()); //$NON-NLS-1$
        assertEquals("Amount", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)retail.getFields().get(0)).getDataPath());
        DataCompositionSchemaDataSetObject products = (DataCompositionSchemaDataSetObject)
            union.getItems().get(1);
        assertEquals("Catalog.Products", products.getObjectName()); //$NON-NLS-1$
        assertEquals("Ref", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)products.getFields().get(0)).getDataPath());
        DataCompositionSchemaDataSetUnion nested = (DataCompositionSchemaDataSetUnion)
            union.getItems().get(2);
        DataCompositionSchemaDataSetQuery wholesale = (DataCompositionSchemaDataSetQuery)
            nested.getItems().get(0);
        assertEquals("Quantity", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)wholesale.getFields().get(0)).getDataPath());

        DcsSchemaWriter.Result updated = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"fields\":[{\"dataPath\":\"Tax\"}]}]}]}"); //$NON-NLS-1$

        assertTrue(updated.error(), updated.isSuccess());
        assertEquals(1, schema.getDataSets().size());
        union = (DataCompositionSchemaDataSetUnion)schema.getDataSets().get(0);
        assertEquals(3, union.getItems().size());
        retail = (DataCompositionSchemaDataSetQuery)union.getItems().get(0);
        assertEquals("SELECT 1 AS Amount", retail.getQuery()); //$NON-NLS-1$
        assertEquals(2, retail.getFields().size());
        assertEquals("Tax", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)retail.getFields().get(1)).getDataPath());
    }

    @Test
    public void testReaderAddressWritesNestedUnionMemberFieldForEveryAction()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result seeded = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"type\":\"query\",\"query\":\"SELECT 1 AS Amount\"," //$NON-NLS-1$
                + "\"fields\":[{\"dataPath\":\"Amount\",\"field\":\"OriginalAmount\"}]}]}]}"); //$NON-NLS-1$
        assertTrue(seeded.error(), seeded.isSuccess());

        String copied = "Report.Sales#/dataSets/AllSales/items/Retail/fields/Amount"; //$NON-NLS-1$
        DcsReadProjection.Result page = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales").address(), "field", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(page.error(), page.isSuccess());
        assertTrue(page.markdown(), page.markdown().contains(copied));

        DcsSchemaWriter.Result updated = apply(schema, "update", "field", copied, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"field\":\"UpdatedAmount\"}"); //$NON-NLS-1$
        assertTrue(updated.error(), updated.isSuccess());
        assertEquals("UpdatedAmount", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)retail(schema).getFields().get(0)).getField());

        String collection = "Report.Sales#/dataSets/AllSales/items/Retail/fields"; //$NON-NLS-1$
        DcsSchemaWriter.Result upserted = apply(schema, "upsert", "field", collection, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"dataPath\":\"Tax\",\"field\":\"OriginalTax\"}"); //$NON-NLS-1$
        assertTrue(upserted.error(), upserted.isSuccess());
        assertEquals(2, retail(schema).getFields().size());

        String beforeCollision = DcsHash.compute(schema);
        DcsSchemaWriter.Result collision = apply(schema, "update", "field", copied, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"dataPath\":\"Tax\"}"); //$NON-NLS-1$
        assertFalse(collision.isSuccess());
        assertTrue(collision.error(), collision.error().contains("Tax")); //$NON-NLS-1$
        assertEquals(beforeCollision, DcsHash.compute(schema));

        String taxAddress = collection + "/Tax"; //$NON-NLS-1$
        DcsSchemaWriter.Result replaced = apply(schema, "replace", "field", taxAddress, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"field\":\"ReplacementTax\"}"); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        assertEquals("ReplacementTax", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)retail(schema).getFields().get(1)).getField());

        String beforeSubtypeError = DcsHash.compute(schema);
        DcsSchemaWriter.Result badSubtype = apply(schema, "upsert", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/AllSales/items/Retail/items/Impossible/fields", //$NON-NLS-1$
            "{\"dataPath\":\"Nope\"}"); //$NON-NLS-1$
        assertFalse(badSubtype.isSuccess());
        assertTrue(badSubtype.error(), badSubtype.error().contains("not union")); //$NON-NLS-1$
        assertEquals(beforeSubtypeError, DcsHash.compute(schema));

        DcsSchemaWriter.Result removed = apply(schema, "remove", "field", taxAddress, "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.error(), removed.isSuccess());
        assertEquals(1, retail(schema).getFields().size());
        assertEquals("Amount", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)retail(schema).getFields().get(0)).getDataPath());
    }

    @Test
    public void testReaderAddressWritesNestedUnionMemberDataSetForEveryAction()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result seeded = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"type\":\"query\",\"query\":\"SELECT 1\"}," //$NON-NLS-1$
                + "{\"name\":\"Keep\",\"type\":\"query\",\"query\":\"SELECT 2\"}]}]}"); //$NON-NLS-1$
        assertTrue(seeded.error(), seeded.isSuccess());

        String copied = "Report.Sales#/dataSets/AllSales/items/Retail"; //$NON-NLS-1$
        DcsReadProjection.Result page = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales#/dataSets/AllSales/items").address(), //$NON-NLS-1$
            "dataSet", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(page.error(), page.isSuccess());
        assertTrue(page.markdown(), page.markdown().contains(copied));

        DcsSchemaWriter.Result updated = apply(schema, "update", "dataSet", copied, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"query\":\"SELECT 3\"}"); //$NON-NLS-1$
        assertTrue(updated.error(), updated.isSuccess());
        assertEquals("SELECT 3", retail(schema).getQuery()); //$NON-NLS-1$

        DcsSchemaWriter.Result upserted = apply(schema, "upsert", "dataSet", copied, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"query\":\"SELECT 4\"}"); //$NON-NLS-1$
        assertTrue(upserted.error(), upserted.isSuccess());
        assertEquals("SELECT 4", retail(schema).getQuery()); //$NON-NLS-1$

        String beforeCollision = DcsHash.compute(schema);
        DcsSchemaWriter.Result collision = apply(schema, "update", "dataSet", copied, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"name\":\"Keep\"}"); //$NON-NLS-1$
        assertFalse(collision.isSuccess());
        assertTrue(collision.error(), collision.error().contains("sibling 'Keep'")); //$NON-NLS-1$
        assertEquals(beforeCollision, DcsHash.compute(schema));

        DcsSchemaWriter.Result replaced = apply(schema, "replace", "dataSet", copied, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"type\":\"object\",\"objectName\":\"Catalog.Products\"}"); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        DataCompositionSchemaDataSetUnion union = (DataCompositionSchemaDataSetUnion)
            schema.getDataSets().get(0);
        assertTrue(union.getItems().get(0) instanceof DataCompositionSchemaDataSetObject);
        assertEquals("Catalog.Products", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetObject)union.getItems().get(0)).getObjectName());
        assertEquals("Keep", union.getItems().get(1).getName()); //$NON-NLS-1$

        DcsSchemaWriter.Result removed = apply(schema, "remove", "dataSet", copied, "{}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.error(), removed.isSuccess());
        union = (DataCompositionSchemaDataSetUnion)schema.getDataSets().get(0);
        assertEquals(1, union.getItems().size());
        assertEquals("Keep", union.getItems().get(0).getName()); //$NON-NLS-1$
    }

    @Test
    public void testUnionRefusesQueryWithoutMutatingSchema()
    {
        DataCompositionSchema schema = newSchema();

        DcsSchemaWriter.Result result = apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"AllSales\",\"type\":\"union\",\"query\":\"SELECT 1\"," //$NON-NLS-1$
                + "\"items\":[]}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("AllSales")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("cannot declare 'query'")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("nested data set under 'items'")); //$NON-NLS-1$
        assertTrue(schema.getDataSets().isEmpty());
        assertTrue(schema.getDataSources().isEmpty());
    }

    @Test
    public void testUnionItemsRejectDuplicateNaturalKeysWithoutMutatingSchema()
    {
        DataCompositionSchema schema = newSchema();

        DcsSchemaWriter.Result result = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"type\":\"query\",\"query\":\"SELECT 1\"}," //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"type\":\"object\"," //$NON-NLS-1$
                + "\"objectName\":\"Catalog.Products\"}]}]}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("union 'AllSales'")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("natural key 'Retail'")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("more than once")); //$NON-NLS-1$
        assertTrue(schema.getDataSets().isEmpty());
        assertTrue(schema.getDataSources().isEmpty());
    }

    @Test
    public void testUpdateRequiresExistingExactNodeAndListsSiblings()
    {
        DataCompositionSchema schema = newSchema();
        apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"Existing\",\"type\":\"query\",\"query\":\"SELECT 1\"}"); //$NON-NLS-1$

        DcsSchemaWriter.Result missing = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Missing", "{\"query\":\"SELECT 2\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(missing.isSuccess());
        assertTrue(missing.error(), missing.error().contains("Missing")); //$NON-NLS-1$
        assertTrue(missing.error(), missing.error().contains("Existing")); //$NON-NLS-1$
        assertTrue(missing.error(), missing.error().contains("upsert")); //$NON-NLS-1$
        assertEquals("SELECT 1", query(schema).getQuery()); //$NON-NLS-1$
        assertEquals(1, schema.getDataSets().size());
    }

    @Test
    public void testUpdateRenamesSupportedNaturalKeysInPlace()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("OldSet", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSource source = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSource();
        source.setName("OldSource"); //$NON-NLS-1$
        source.setDataSourceType("Local"); //$NON-NLS-1$
        schema.getDataSources().add(source);
        DataCompositionSchemaParameter oldParameter = DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        oldParameter.setName("OldParameter"); //$NON-NLS-1$
        schema.getParameters().add(oldParameter);
        DataCompositionSchemaCalculatedField oldCalculation = DcsFactory.eINSTANCE
            .createDataCompositionSchemaCalculatedField();
        oldCalculation.setDataPath("OldCalculation"); //$NON-NLS-1$
        oldCalculation.setExpression("1 + 1"); //$NON-NLS-1$
        schema.getCalculatedFields().add(oldCalculation);

        DcsSchemaWriter.Result dataSet = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/OldSet", "{\"name\":\"NewSet\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result dataSource = apply(schema, "update", "dataSource", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSources/OldSource", "{\"name\":\"NewSource\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result parameter = apply(schema, "update", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/OldParameter", "{\"name\":\"NewParameter\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result calculation = apply(schema, "update", "calculatedField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/calculatedFields/OldCalculation", //$NON-NLS-1$
            "{\"dataPath\":\"NewCalculation\"}"); //$NON-NLS-1$

        assertTrue(dataSet.error(), dataSet.isSuccess());
        assertTrue(dataSource.error(), dataSource.isSuccess());
        assertTrue(parameter.error(), parameter.isSuccess());
        assertTrue(calculation.error(), calculation.isSuccess());
        assertEquals("NewSet", schema.getDataSets().get(0).getName()); //$NON-NLS-1$
        assertEquals("SELECT 1", query(schema).getQuery()); //$NON-NLS-1$
        assertTrue(schema.getDataSources().stream()
            .anyMatch(item -> "NewSource".equals(item.getName()))); //$NON-NLS-1$
        assertFalse(schema.getDataSources().stream()
            .anyMatch(item -> "OldSource".equals(item.getName()))); //$NON-NLS-1$
        assertEquals("NewParameter", schema.getParameters().get(0).getName()); //$NON-NLS-1$
        assertEquals("NewCalculation", schema.getCalculatedFields().get(0).getDataPath()); //$NON-NLS-1$
        assertEquals("1 + 1", schema.getCalculatedFields().get(0).getExpression()); //$NON-NLS-1$
    }

    @Test
    public void testUpdateRenameRefusesTakenSiblingAndLeavesHashUnchanged()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("Old", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(dataSet("Taken", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Old", "{\"name\":\"Taken\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result empty = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Old", "{\"name\":\"\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("Old")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("sibling 'Taken'")); //$NON-NLS-1$
        assertFalse(empty.isSuccess());
        assertTrue(empty.error(), empty.error().contains("non-empty 'name'")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testUpdateRenameRefusesReferencedIdentityAndLeavesHashUnchanged()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("Old", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(dataSet("Destination", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetLink link = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("Old"); //$NON-NLS-1$
        link.setDestinationDataSet("Destination"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Old", "{\"name\":\"New\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("Cannot remove or rename")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Report.Sales#/dataSetLinks/0")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testLinkParameterBlocksRenameAndRemoveWithoutChangingHash()
    {
        DataCompositionSchema schema = schemaWithLinkParameter("LinkParameter"); //$NON-NLS-1$
        String linkAddress = "Report.Sales#/dataSetLinks/0"; //$NON-NLS-1$
        assertEquals(Arrays.asList(linkAddress), DcsReadProjection.referenceAddresses(schema,
            "Report.Sales", "parameter", "LinkParameter")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result renamed = apply(schema, "update", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/LinkParameter", "{\"name\":\"RenamedParameter\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result removed = apply(schema, "remove", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/LinkParameter", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(renamed.isSuccess());
        assertTrue(renamed.error(), renamed.error().contains(linkAddress));
        assertFalse(removed.isSuccess());
        assertTrue(removed.error(), removed.error().contains(linkAddress));
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testSchemaReplaceRefusesDanglingLinkParameterWithoutChangingHash()
    {
        DataCompositionSchema schema = schemaWithLinkParameter("LinkParameter"); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "replace", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"A\",\"type\":\"query\",\"query\":\"SELECT 1\"}," //$NON-NLS-1$
                + "{\"name\":\"B\",\"type\":\"query\",\"query\":\"SELECT 2\"}]," //$NON-NLS-1$
                + "\"dataSetLinks\":[{\"sourceDataSet\":\"A\",\"destinationDataSet\":\"B\"," //$NON-NLS-1$
                + "\"sourceExpression\":\"Key\",\"destinationExpression\":\"Key\"," //$NON-NLS-1$
                + "\"parameter\":\"MissingParameter\"}]}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("dangling parameter 'MissingParameter'")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Report.Sales#/dataSetLinks/0")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Add or keep a parameter named")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testFieldFolderRenameIsUnsupportedAndLeavesHashUnchanged()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetFieldFolder folder = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetFieldFolder();
        folder.setDataPath("Folder"); //$NON-NLS-1$
        dataSet.getFields().add(folder);
        schema.getDataSets().add(dataSet);
        String address = "Report.Sales#/dataSets/Sales/fields/Folder"; //$NON-NLS-1$
        DcsReadProjection.Result page = DcsReadProjection.render("Report.Sales", //$NON-NLS-1$
            DcsTargetResolver.TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse("Report.Sales").address(), "field", "en", 100, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(page.error(), page.isSuccess());
        assertTrue(page.markdown(), page.markdown().contains(address));
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "update", "field", address, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"dataPath\":\"RenamedFolder\"}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("DataCompositionSchemaDataSetFieldFolder")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("folders are not authorable")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testDuplicateRegularFieldRenameIsAmbiguousAndLeavesHashUnchanged()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 2; i++)
        {
            DataCompositionSchemaDataSetField field = DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSetField();
            field.setDataPath("Amount"); //$NON-NLS-1$
            field.setField("Amount" + i); //$NON-NLS-1$
            dataSet.getFields().add(field);
        }
        schema.getDataSets().add(dataSet);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Amount", //$NON-NLS-1$
            "{\"dataPath\":\"RenamedAmount\"}"); //$NON-NLS-1$

        assertAmbiguousRename(result, "field"); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testRegularFieldAndFolderWithSameKeyMakeRenameAmbiguous()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        dataSet.getFields().add(field("Amount")); //$NON-NLS-1$
        DataCompositionSchemaDataSetFieldFolder folder = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetFieldFolder();
        folder.setDataPath("Amount"); //$NON-NLS-1$
        dataSet.getFields().add(folder);
        schema.getDataSets().add(dataSet);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Amount", //$NON-NLS-1$
            "{\"dataPath\":\"RenamedAmount\"}"); //$NON-NLS-1$

        assertAmbiguousRename(result, "field"); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testDataSetRenameRefusesNameUsedByNestedUnionMember()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("Old", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetUnion union = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetUnion();
        union.setName("AllSales"); //$NON-NLS-1$
        union.getItems().add(dataSet("Retail", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(union);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Old", "{\"name\":\"Retail\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("data set 'Retail' already exists")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains(
            "Report.Sales#/dataSets/AllSales/items/Retail")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testRemoveRefusesAmbiguousNaturalKeysAndUniqueRemoveDeletesOneNode()
    {
        DataCompositionSchema schema = newSchema();
        for (int i = 0; i < 2; i++)
        {
            DataCompositionSchemaParameter duplicate = DcsFactory.eINSTANCE
                .createDataCompositionSchemaParameter();
            duplicate.setName("Duplicate"); //$NON-NLS-1$
            schema.getParameters().add(duplicate);
        }
        DataCompositionSchemaParameter unique = DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        unique.setName("Unique"); //$NON-NLS-1$
        schema.getParameters().add(unique);
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        dataSet.getFields().add(field("Amount")); //$NON-NLS-1$
        dataSet.getFields().add(field("Amount")); //$NON-NLS-1$
        schema.getDataSets().add(dataSet);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result ambiguousParameter = apply(schema, "remove", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/Duplicate", "{}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result ambiguousField = apply(schema, "remove", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Amount", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(ambiguousParameter.isSuccess());
        assertTrue(ambiguousParameter.error(),
            ambiguousParameter.error().contains("Cannot remove parameter")); //$NON-NLS-1$
        assertTrue(ambiguousParameter.error(),
            ambiguousParameter.error().contains("matches 2 existing nodes")); //$NON-NLS-1$
        assertFalse(ambiguousField.isSuccess());
        assertTrue(ambiguousField.error(),
            ambiguousField.error().contains("Cannot remove field")); //$NON-NLS-1$
        assertTrue(ambiguousField.error(),
            ambiguousField.error().contains("matches 2 existing nodes")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));

        DcsSchemaWriter.Result removed = apply(schema, "remove", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/Unique", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(removed.error(), removed.isSuccess());
        assertEquals(2, schema.getParameters().size());
        assertTrue(schema.getParameters().stream()
            .allMatch(parameter -> "Duplicate".equals(parameter.getName()))); //$NON-NLS-1$
    }

    @Test
    public void testExpressionReferenceBlocksFieldRenameAndRemove()
    {
        DataCompositionSchema schema = schemaWithFields("Revenue"); //$NON-NLS-1$
        schema.getCalculatedFields().add(calculatedField("Margin", "Revenue - Cost")); //$NON-NLS-1$ //$NON-NLS-2$
        String expressionAddress = "Report.Sales#/calculatedFields/Margin"; //$NON-NLS-1$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result renamed = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Revenue", //$NON-NLS-1$
            "{\"dataPath\":\"NetRevenue\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result removed = apply(schema, "remove", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Revenue", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(renamed.isSuccess());
        assertTrue(renamed.error(), renamed.error().contains(expressionAddress));
        assertFalse(removed.isSuccess());
        assertTrue(removed.error(), removed.error().contains(expressionAddress));
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testTotalFieldExpressionBlocksFieldRemove()
    {
        DataCompositionSchema schema = schemaWithFields("Quantity"); //$NON-NLS-1$
        DataCompositionSchemaTotalField total = DcsFactory.eINSTANCE
            .createDataCompositionSchemaTotalField();
        total.setDataPath("TotalQuantity"); //$NON-NLS-1$
        total.setExpression("Quantity"); //$NON-NLS-1$
        schema.getTotalFields().add(total);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "remove", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Quantity", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains(
            "Report.Sales#/totalFields/TotalQuantity")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testExpressionReferenceUsesWholeTokensWithoutPartialMatches()
    {
        DataCompositionSchema schema = schemaWithFields("Revenue", "Cost", "CostPrice", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "LiteralOnly"); //$NON-NLS-1$
        schema.getCalculatedFields().add(calculatedField("Margin", //$NON-NLS-1$
            "revenue - Cost + \"prefix \"\"embedded\"\" LiteralOnly\"")); //$NON-NLS-1$

        DcsSchemaWriter.Result revenue = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Revenue", //$NON-NLS-1$
            "{\"dataPath\":\"NetRevenue\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result cost = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Cost", //$NON-NLS-1$
            "{\"dataPath\":\"DirectCost\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result costPrice = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/CostPrice", //$NON-NLS-1$
            "{\"dataPath\":\"WholesaleCost\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result literalOnly = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/LiteralOnly", //$NON-NLS-1$
            "{\"dataPath\":\"TextToken\"}"); //$NON-NLS-1$

        assertFalse(revenue.isSuccess());
        assertTrue(revenue.error(), revenue.error().contains(
            "Report.Sales#/calculatedFields/Margin")); //$NON-NLS-1$
        assertFalse(cost.isSuccess());
        assertTrue(cost.error(), cost.error().contains("Report.Sales#/calculatedFields/Margin")); //$NON-NLS-1$
        assertTrue(costPrice.error(), costPrice.isSuccess());
        assertEquals("WholesaleCost", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)query(schema).getFields().get(2)).getDataPath());
        assertTrue(literalOnly.error(), literalOnly.isSuccess());
        assertEquals("TextToken", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)query(schema).getFields().get(3)).getDataPath());
    }

    @Test
    public void testExpressionParameterReferenceBlocksParameterRename()
    {
        DataCompositionSchema schema = schemaWithFields("Amount"); //$NON-NLS-1$
        DataCompositionSchemaParameter period = DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        period.setName("Period"); //$NON-NLS-1$
        schema.getParameters().add(period);
        // An expression names a parameter as '&Period'; '&' is not an identifier character, so the
        // same whole-token scan that guards field renames sees the parameter too.
        schema.getCalculatedFields().add(calculatedField("Recent", "Amount > &Period")); //$NON-NLS-1$ //$NON-NLS-2$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result blocked = apply(schema, "update", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/Period", "{\"name\":\"Interval\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(blocked.isSuccess());
        assertTrue(blocked.error(), blocked.error().contains("Recent")); //$NON-NLS-1$
        assertEquals("a refused parameter rename must leave the model unchanged", //$NON-NLS-1$
            beforeHash, DcsHash.compute(schema));

        DcsSchemaWriter.Result allowed = apply(schema, "update", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/Period", "{\"name\":\"Period\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(allowed.error(), allowed.isSuccess());
    }

    @Test
    public void testExpressionFunctionNameDoesNotBlockFieldRename()
    {
        DataCompositionSchema schema = schemaWithFields("Сумма"); //$NON-NLS-1$
        schema.getCalculatedFields().add(calculatedField("Total", "Сумма(Оборот)")); //$NON-NLS-1$ //$NON-NLS-2$

        DcsSchemaWriter.Result result = apply(schema, "update", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Сумма", //$NON-NLS-1$
            "{\"dataPath\":\"Итого\"}"); //$NON-NLS-1$

        assertTrue(result.error(), result.isSuccess());
        assertEquals("Итого", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetField)query(schema).getFields().get(0)).getDataPath());
    }

    @Test
    public void testExpressionDottedPathReferencesItsHeadField()
    {
        DataCompositionSchema schema = schemaWithFields("Товар"); //$NON-NLS-1$
        schema.getCalculatedFields().add(calculatedField("ProductName", //$NON-NLS-1$
            "Товар.Наименование")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "remove", "field", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales/fields/Товар", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains(
            "Report.Sales#/calculatedFields/ProductName")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testEveryNonFieldRenameRefusesDuplicateOldIdentityWithoutChangingHash()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("DuplicateSet", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(dataSet("DuplicateSet", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < 2; i++)
        {
            DataCompositionSchemaDataSource source = DcsFactory.eINSTANCE
                .createDataCompositionSchemaDataSource();
            source.setName("DuplicateSource"); //$NON-NLS-1$
            source.setDataSourceType("Local"); //$NON-NLS-1$
            schema.getDataSources().add(source);
            DataCompositionSchemaParameter parameter = DcsFactory.eINSTANCE
                .createDataCompositionSchemaParameter();
            parameter.setName("DuplicateParameter"); //$NON-NLS-1$
            schema.getParameters().add(parameter);
            DataCompositionSchemaCalculatedField calculated = DcsFactory.eINSTANCE
                .createDataCompositionSchemaCalculatedField();
            calculated.setDataPath("DuplicateCalculated"); //$NON-NLS-1$
            calculated.setExpression("1 + 1"); //$NON-NLS-1$
            schema.getCalculatedFields().add(calculated);
            DataCompositionSchemaTotalField total = DcsFactory.eINSTANCE
                .createDataCompositionSchemaTotalField();
            total.setDataPath("DuplicateTotal"); //$NON-NLS-1$
            total.setExpression("Sum(Amount)"); //$NON-NLS-1$
            schema.getTotalFields().add(total);
        }
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result dataSet = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/DuplicateSet", "{\"name\":\"RenamedSet\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result dataSource = apply(schema, "update", "dataSource", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSources/DuplicateSource", "{\"name\":\"RenamedSource\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result parameter = apply(schema, "update", "parameter", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/parameters/DuplicateParameter", "{\"name\":\"RenamedParameter\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result calculated = apply(schema, "update", "calculatedField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/calculatedFields/DuplicateCalculated", //$NON-NLS-1$
            "{\"dataPath\":\"RenamedCalculated\"}"); //$NON-NLS-1$
        DcsSchemaWriter.Result total = apply(schema, "update", "totalField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/totalFields/DuplicateTotal", //$NON-NLS-1$
            "{\"dataPath\":\"RenamedTotal\"}"); //$NON-NLS-1$

        assertAmbiguousRename(dataSet, "dataSet"); //$NON-NLS-1$
        assertAmbiguousRename(dataSource, "dataSource"); //$NON-NLS-1$
        assertAmbiguousRename(parameter, "parameter"); //$NON-NLS-1$
        assertAmbiguousRename(calculated, "calculatedField"); //$NON-NLS-1$
        assertAmbiguousRename(total, "totalField"); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testUnknownNestedMemberLeavesExistingModelUntouched()
    {
        DataCompositionSchema schema = newSchema();
        apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"Sales\",\"type\":\"query\",\"query\":\"SELECT 1\"}"); //$NON-NLS-1$

        DcsSchemaWriter.Result rejected = apply(schema, "upsert", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Sales", //$NON-NLS-1$
            "{\"query\":\"SELECT 2\",\"fields\":[{\"dataPath\":\"Amount\",\"titel\":\"Amount\"}]}"); //$NON-NLS-1$

        assertFalse(rejected.isSuccess());
        assertTrue(rejected.error(), rejected.error().contains("titel")); //$NON-NLS-1$
        assertTrue(rejected.error(), rejected.error().contains("Accepted members")); //$NON-NLS-1$
        assertEquals("SELECT 1", query(schema).getQuery()); //$NON-NLS-1$
        assertTrue(query(schema).getFields().isEmpty());
    }

    @Test
    public void testTotalFieldCanBeAuthoredAndPartiallyUpdated()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result created = apply(schema, "upsert", "totalField", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataPath\":\"Amount\",\"expression\":\"Sum(Amount)\",\"groups\":[\"Goods\"]}"); //$NON-NLS-1$
        DcsSchemaWriter.Result updated = apply(schema, "update", "totalField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/totalFields/Amount", "{\"groups\":[\"Warehouse\"]}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(created.error(), created.isSuccess());
        assertTrue(updated.error(), updated.isSuccess());
        assertEquals(1, schema.getTotalFields().size());
        assertEquals("Sum(Amount)", schema.getTotalFields().get(0).getExpression()); //$NON-NLS-1$
        assertEquals(Arrays.asList("Warehouse"), schema.getTotalFields().get(0).getGroups()); //$NON-NLS-1$
    }

    @Test
    public void testExactExpressionFieldReplaceRequiresExpressionAndLeavesModelUnchanged()
    {
        DataCompositionSchema schema = newSchema();
        assertTrue(apply(schema, "upsert", "calculatedField", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataPath\":\"Margin\",\"expression\":\"Revenue - Cost\"}").isSuccess()); //$NON-NLS-1$
        assertTrue(apply(schema, "upsert", "totalField", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataPath\":\"Amount\",\"expression\":\"Sum(Amount)\"}").isSuccess()); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result calculated = apply(schema, "replace", "calculatedField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/calculatedFields/Margin", "{\"dataPath\":\"Margin\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result total = apply(schema, "replace", "totalField", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/totalFields/Amount", "{\"dataPath\":\"Amount\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(calculated.isSuccess());
        assertTrue(calculated.error(), calculated.error().contains("must carry 'expression'")); //$NON-NLS-1$
        assertTrue(calculated.error(), calculated.error().contains("action='get'")); //$NON-NLS-1$
        assertFalse(total.isSuccess());
        assertTrue(total.error(), total.error().contains("must carry 'expression'")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testExactDataSetReplaceChangesSubtypeAndKeepsLinkIdentity()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("Switch", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(dataSet("Other", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetField oldField = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetField();
        oldField.setDataPath("OldField"); //$NON-NLS-1$
        schema.getDataSets().get(0).getFields().add(oldField);
        DataCompositionSchemaDataSetLink link = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("Switch"); //$NON-NLS-1$
        link.setDestinationDataSet("Other"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);

        DcsSchemaWriter.Result object = apply(schema, "replace", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Switch", //$NON-NLS-1$
            "{\"type\":\"object\",\"objectName\":\"Catalog.Products\"}"); //$NON-NLS-1$
        assertTrue(object.error(), object.isSuccess());
        assertTrue(schema.getDataSets().get(0) instanceof DataCompositionSchemaDataSetObject);
        assertEquals("Catalog.Products", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetObject)schema.getDataSets().get(0)).getObjectName());
        assertTrue(schema.getDataSets().get(0).getFields().isEmpty());

        DcsSchemaWriter.Result union = apply(schema, "replace", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Switch", //$NON-NLS-1$
            "{\"type\":\"union\",\"items\":[{\"name\":\"Member\",\"type\":\"query\"," //$NON-NLS-1$
                + "\"query\":\"SELECT 3\"}]}"); //$NON-NLS-1$
        assertTrue(union.error(), union.isSuccess());
        assertTrue(schema.getDataSets().get(0) instanceof DataCompositionSchemaDataSetUnion);
        assertEquals(1, ((DataCompositionSchemaDataSetUnion)schema.getDataSets().get(0))
            .getItems().size());

        DcsSchemaWriter.Result query = apply(schema, "replace", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Switch", //$NON-NLS-1$
            "{\"type\":\"query\",\"query\":\"SELECT 4\"}"); //$NON-NLS-1$
        assertTrue(query.error(), query.isSuccess());
        assertTrue(schema.getDataSets().get(0) instanceof DataCompositionSchemaDataSetQuery);
        assertEquals("SELECT 4", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetQuery)schema.getDataSets().get(0)).getQuery());
        assertEquals("Switch", schema.getDataSetLinks().get(0).getSourceDataSet()); //$NON-NLS-1$
    }

    @Test
    public void testExactDataSetReplaceWithoutTypeKeepsExistingSubtype()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetObject object = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetObject();
        object.setName("Products"); //$NON-NLS-1$
        object.setObjectName("Catalog.OldProducts"); //$NON-NLS-1$
        schema.getDataSets().add(object);

        DcsSchemaWriter.Result result = apply(schema, "replace", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/Products", //$NON-NLS-1$
            "{\"objectName\":\"Catalog.NewProducts\"}"); //$NON-NLS-1$

        assertTrue(result.error(), result.isSuccess());
        assertTrue(schema.getDataSets().get(0) instanceof DataCompositionSchemaDataSetObject);
        assertEquals("Catalog.NewProducts", //$NON-NLS-1$
            ((DataCompositionSchemaDataSetObject)schema.getDataSets().get(0)).getObjectName());
    }

    @Test
    public void testBilingualNamesAndSynonymStayDistinctAndCanonical()
    {
        DataCompositionSchema schema = newSchema();
        String russianName = MetadataLanguageUtils.cp(0x041f, 0x0440, 0x043e, 0x0434, 0x0430, 0x0436, 0x0438);
        String russianSynonym = MetadataLanguageUtils.cp(0x0418, 0x043c, 0x044f);
        JsonObject body = json("{\"dataSets\":[{\"name\":\"Sales\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 1\"},{\"name\":\"placeholder\",\"type\":\"query\"," //$NON-NLS-1$
            + "\"query\":\"SELECT 2\",\"fields\":[{\"dataPath\":\"Name\",\"title\":{\"EN\":\"Name\"}}]}]}"); //$NON-NLS-1$
        body.getAsJsonArray("dataSets").get(1).getAsJsonObject().addProperty("name", russianName); //$NON-NLS-1$ //$NON-NLS-2$
        body.getAsJsonArray("dataSets").get(1).getAsJsonObject().getAsJsonArray("fields") //$NON-NLS-1$ //$NON-NLS-2$
            .get(0).getAsJsonObject().getAsJsonObject("title").addProperty("RU", russianSynonym); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.Result result = apply(schema, "upsert", "schema", "Report.Sales", body); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(result.error(), result.isSuccess());
        assertEquals("Sales", schema.getDataSets().get(0).getName()); //$NON-NLS-1$
        assertEquals(russianName, schema.getDataSets().get(1).getName());
        DataCompositionSchemaDataSetField field = (DataCompositionSchemaDataSetField)
            ((DataCompositionSchemaDataSetQuery)schema.getDataSets().get(1)).getFields().get(0);
        assertNotNull(field.getTitle().getLocalValue());
        assertEquals("Name", field.getTitle().getLocalValue().getContent().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(russianSynonym, field.getTitle().getLocalValue().getContent().get("ru")); //$NON-NLS-1$
        assertFalse("a synonym is presentation data, not a programmatic natural key", //$NON-NLS-1$
            russianSynonym.equals(schema.getDataSets().get(1).getName()));
    }

    @Test
    public void testSchemaReplaceRefusesDanglingLinkAndLeavesModelUnchanged()
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("A", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(dataSet("B", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetLink link = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("A"); //$NON-NLS-1$
        link.setDestinationDataSet("B"); //$NON-NLS-1$
        link.setSourceExpression("Key"); //$NON-NLS-1$
        link.setDestinationExpression("Key"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "replace", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSets\":[{\"name\":\"A\",\"type\":\"query\",\"query\":\"SELECT 1\"}]," //$NON-NLS-1$
                + "\"dataSetLinks\":[{\"sourceDataSet\":\"A\",\"destinationDataSet\":\"B\"," //$NON-NLS-1$
                + "\"sourceExpression\":\"Key\",\"destinationExpression\":\"Key\"}]}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("destinationDataSet 'B'")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Report.Sales#/dataSetLinks/0")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Add or keep a data set named 'B'")); //$NON-NLS-1$
        assertEquals("a refused schema replacement must leave the model unchanged", //$NON-NLS-1$
            beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testSchemaReplaceAllowsIdentityReordering()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result seeded = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSources\":[{\"name\":\"SourceA\",\"type\":\"Local\"}," //$NON-NLS-1$
                + "{\"name\":\"SourceB\",\"type\":\"Local\"}],\"dataSets\":[" //$NON-NLS-1$
                + "{\"name\":\"A\",\"type\":\"query\",\"dataSource\":\"SourceA\"," //$NON-NLS-1$
                + "\"query\":\"SELECT 1\"},{\"name\":\"B\",\"type\":\"query\"," //$NON-NLS-1$
                + "\"dataSource\":\"SourceB\",\"query\":\"SELECT 2\"}]," //$NON-NLS-1$
                + "\"dataSetLinks\":[{\"sourceDataSet\":\"A\",\"destinationDataSet\":\"B\"," //$NON-NLS-1$
                + "\"sourceExpression\":\"Key\",\"destinationExpression\":\"Key\"}]}"); //$NON-NLS-1$
        assertTrue(seeded.error(), seeded.isSuccess());

        DcsSchemaWriter.Result reordered = apply(schema, "replace", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSources\":[{\"name\":\"SourceB\",\"type\":\"Local\"}," //$NON-NLS-1$
                + "{\"name\":\"SourceA\",\"type\":\"Local\"}],\"dataSets\":[" //$NON-NLS-1$
                + "{\"name\":\"B\",\"type\":\"query\",\"dataSource\":\"SourceB\"," //$NON-NLS-1$
                + "\"query\":\"SELECT 2\"},{\"name\":\"A\",\"type\":\"query\"," //$NON-NLS-1$
                + "\"dataSource\":\"SourceA\",\"query\":\"SELECT 1\"}]," //$NON-NLS-1$
                + "\"dataSetLinks\":[{\"sourceDataSet\":\"A\",\"destinationDataSet\":\"B\"," //$NON-NLS-1$
                + "\"sourceExpression\":\"Key\",\"destinationExpression\":\"Key\"}]}"); //$NON-NLS-1$

        assertTrue(reordered.error(), reordered.isSuccess());
        assertEquals("SourceB", schema.getDataSources().get(0).getName()); //$NON-NLS-1$
        assertEquals("SourceA", schema.getDataSources().get(1).getName()); //$NON-NLS-1$
        assertEquals("B", schema.getDataSets().get(0).getName()); //$NON-NLS-1$
        assertEquals("A", schema.getDataSets().get(1).getName()); //$NON-NLS-1$
        assertEquals("A", schema.getDataSetLinks().get(0).getSourceDataSet()); //$NON-NLS-1$
        assertEquals("B", schema.getDataSetLinks().get(0).getDestinationDataSet()); //$NON-NLS-1$
    }

    @Test
    public void testSchemaValidationResolvesUnionMemberDataSetReferences()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetUnion union = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetUnion();
        union.setName("Combined"); //$NON-NLS-1$
        union.getItems().add(dataSet("A", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        union.getItems().add(dataSet("B", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(union);

        DcsSchemaWriter.Result result = apply(schema, "upsert", "schema", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"dataSetLinks\":[{\"sourceDataSet\":\"A\",\"destinationDataSet\":\"B\"," //$NON-NLS-1$
                + "\"sourceExpression\":\"Key\",\"destinationExpression\":\"Key\"}]}"); //$NON-NLS-1$

        assertTrue(result.error(), result.isSuccess());
        assertEquals(1, schema.getDataSetLinks().size());
    }

    @Test
    public void testDataSetCollectionReplaceRefusesToStrandRetainedLink()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery removed = dataSet("Removed", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaDataSetQuery retained = dataSet("Retained", "SELECT 2"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(removed);
        schema.getDataSets().add(retained);
        DataCompositionSchemaDataSetLink link = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("Removed"); //$NON-NLS-1$
        link.setDestinationDataSet("Retained"); //$NON-NLS-1$
        link.setSourceExpression("Key"); //$NON-NLS-1$
        link.setDestinationExpression("Key"); //$NON-NLS-1$
        schema.getDataSetLinks().add(link);

        DcsSchemaWriter.Result result = apply(schema, "replace", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets", //$NON-NLS-1$
            "{\"name\":\"Retained\",\"type\":\"query\",\"query\":\"SELECT 2\"}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("Removed")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("#/dataSetLinks/0")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("referring nodes")); //$NON-NLS-1$
        assertEquals("a refused collection replacement must leave both identities in place", //$NON-NLS-1$
            2, schema.getDataSets().size());
        assertEquals(1, schema.getDataSetLinks().size());
    }

    @Test
    public void testDataSourceCollectionReplaceRefusesToStrandRetainedDataSet()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSource removed = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSource();
        removed.setName("RemovedSource"); //$NON-NLS-1$
        removed.setDataSourceType("Local"); //$NON-NLS-1$
        DataCompositionSchemaDataSource retained = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSource();
        retained.setName("RetainedSource"); //$NON-NLS-1$
        retained.setDataSourceType("Local"); //$NON-NLS-1$
        schema.getDataSources().add(removed);
        schema.getDataSources().add(retained);
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        dataSet.setDataSource("RemovedSource"); //$NON-NLS-1$
        schema.getDataSets().add(dataSet);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "replace", "dataSource", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSources", //$NON-NLS-1$
            "{\"name\":\"RetainedSource\",\"type\":\"Local\"}"); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("RemovedSource")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("#/dataSets/Sales")); //$NON-NLS-1$
        assertEquals("a refused source replacement must leave both sources on the model", //$NON-NLS-1$
            2, schema.getDataSources().size());
        assertEquals("RemovedSource", dataSet.getDataSource()); //$NON-NLS-1$
        assertEquals("a refused source replacement must leave the model unchanged", //$NON-NLS-1$
            beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testRemoveReferencedDataSourceIsRefusedWithoutChangingHash()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSource source = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSource();
        source.setName("Source"); //$NON-NLS-1$
        source.setDataSourceType("Local"); //$NON-NLS-1$
        schema.getDataSources().add(source);
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        dataSet.setDataSource("Source"); //$NON-NLS-1$
        schema.getDataSets().add(dataSet);
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result result = apply(schema, "remove", "dataSource", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSources/Source", "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("Source")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Report.Sales#/dataSets/Sales")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
    }

    @Test
    public void testDataSetUpdateRefusesCreatingNestedFieldsAndUnionMembers()
    {
        DataCompositionSchema schema = newSchema();
        DcsSchemaWriter.Result authored = apply(schema, "upsert", "dataSet", "Report.Sales", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"AllSales\",\"type\":\"union\",\"items\":[" //$NON-NLS-1$
                + "{\"name\":\"Retail\",\"type\":\"query\",\"query\":\"SELECT 1 AS Amount\"," //$NON-NLS-1$
                + "\"fields\":[{\"dataPath\":\"Amount\"}]}]}"); //$NON-NLS-1$
        assertTrue(authored.error(), authored.isSuccess());
        String beforeHash = DcsHash.compute(schema);

        DcsSchemaWriter.Result missingField = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/AllSales/items/Retail", //$NON-NLS-1$
            "{\"fields\":[{\"dataPath\":\"Tax\"}]}"); //$NON-NLS-1$
        DcsSchemaWriter.Result missingMember = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/AllSales", //$NON-NLS-1$
            "{\"items\":[{\"name\":\"Wholesale\",\"type\":\"query\"," //$NON-NLS-1$
                + "\"query\":\"SELECT 2\"}]}"); //$NON-NLS-1$

        assertFalse(missingField.isSuccess());
        assertTrue(missingField.error(), missingField.error().contains("nested field 'Tax'")); //$NON-NLS-1$
        assertTrue(missingField.error(), missingField.error().contains("action='upsert'")); //$NON-NLS-1$
        assertFalse(missingMember.isSuccess());
        assertTrue(missingMember.error(), missingMember.error().contains("nested dataSet 'Wholesale'")); //$NON-NLS-1$
        assertTrue(missingMember.error(), missingMember.error().contains("action='upsert'")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));

        DcsSchemaWriter.Result existingMember = apply(schema, "update", "dataSet", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales#/dataSets/AllSales", //$NON-NLS-1$
            "{\"items\":[{\"name\":\"Retail\",\"query\":\"SELECT 3 AS Amount\"}]}"); //$NON-NLS-1$
        assertTrue(existingMember.error(), existingMember.isSuccess());
        assertEquals("SELECT 3 AS Amount", retail(schema).getQuery()); //$NON-NLS-1$
        assertEquals(1, ((DataCompositionSchemaDataSetUnion)schema.getDataSets().get(0)).getItems().size());
    }

    private static DcsSchemaWriter.Result apply(DataCompositionSchema schema, String action, String type,
        String address, String body)
    {
        return apply(schema, action, type, address, json(body));
    }

    private static DcsSchemaWriter.Result apply(DataCompositionSchema schema, String action, String type,
        String address, JsonObject body)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(address);
        assertTrue(parsed.failure() == null ? address : parsed.failure().message(), parsed.isSuccess());
        DcsPresentationParser.LanguageContext languages =
            new DcsPresentationParser.LanguageContext(Arrays.asList("en", "ru")); //$NON-NLS-1$ //$NON-NLS-2$
        DcsSchemaWriter.PrepareResult prepared =
            DcsSchemaWriter.prepare(action, type, parsed.address(), body, languages);
        assertTrue(prepared.error(), prepared.isSuccess());
        return DcsSchemaWriter.apply(schema, prepared.request(), null);
    }

    private static JsonObject json(String value)
    {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static void assertAmbiguousRename(DcsSchemaWriter.Result result, String type)
    {
        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("Cannot rename " + type)); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("matches 2 existing nodes")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("disambiguate")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("DCS designer")); //$NON-NLS-1$
    }

    private static DataCompositionSchema newSchema()
    {
        return DcsFactory.eINSTANCE.createDataCompositionSchema();
    }

    private static DataCompositionSchemaDataSetQuery query(DataCompositionSchema schema)
    {
        return (DataCompositionSchemaDataSetQuery)schema.getDataSets().get(0);
    }

    private static DataCompositionSchemaDataSetQuery dataSet(String name, String query)
    {
        DataCompositionSchemaDataSetQuery result = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetQuery();
        result.setName(name);
        result.setQuery(query);
        return result;
    }

    private static DataCompositionSchemaDataSetField field(String dataPath)
    {
        DataCompositionSchemaDataSetField result = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetField();
        result.setDataPath(dataPath);
        return result;
    }

    private static DataCompositionSchemaCalculatedField calculatedField(String dataPath,
        String expression)
    {
        DataCompositionSchemaCalculatedField result = DcsFactory.eINSTANCE
            .createDataCompositionSchemaCalculatedField();
        result.setDataPath(dataPath);
        result.setExpression(expression);
        return result;
    }

    private static DataCompositionSchema schemaWithFields(String... dataPaths)
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet = dataSet("Sales", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String dataPath : dataPaths)
        {
            dataSet.getFields().add(field(dataPath));
        }
        schema.getDataSets().add(dataSet);
        return schema;
    }

    private static DataCompositionSchema schemaWithLinkParameter(String parameterName)
    {
        DataCompositionSchema schema = newSchema();
        schema.getDataSets().add(dataSet("A", "SELECT 1")); //$NON-NLS-1$ //$NON-NLS-2$
        schema.getDataSets().add(dataSet("B", "SELECT 2")); //$NON-NLS-1$ //$NON-NLS-2$
        DataCompositionSchemaParameter parameter = DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName(parameterName);
        schema.getParameters().add(parameter);
        DataCompositionSchemaDataSetLink link = DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetLink();
        link.setSourceDataSet("A"); //$NON-NLS-1$
        link.setDestinationDataSet("B"); //$NON-NLS-1$
        link.setSourceExpression("Key"); //$NON-NLS-1$
        link.setDestinationExpression("Key"); //$NON-NLS-1$
        link.setParameter(parameterName);
        schema.getDataSetLinks().add(link);
        return schema;
    }

    private static DataCompositionSchemaDataSetQuery retail(DataCompositionSchema schema)
    {
        DataCompositionSchemaDataSetUnion union = (DataCompositionSchemaDataSetUnion)
            schema.getDataSets().get(0);
        return (DataCompositionSchemaDataSetQuery)union.getItems().get(0);
    }
}

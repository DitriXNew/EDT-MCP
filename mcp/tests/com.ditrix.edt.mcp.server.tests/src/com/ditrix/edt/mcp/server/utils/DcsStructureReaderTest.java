/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterUse;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionSortDirection;
import com._1c.g5.v8.dt.dcs.model.core.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValue;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValueValue;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetFieldFolder;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaNestedDataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAutoSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionComparisonType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemsGroupType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrder;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOrderItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFieldGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedFields;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.EnumValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.TypeValue;
import com._1c.g5.v8.dt.mcore.UndefinedValue;

/**
 * Tests {@link DcsStructureReader}: the pure Markdown renderer for a {@link DataCompositionSchema} content.
 * <p>
 * The {@code schema} / {@code core} / {@code mcore} packages are ACCESSIBLE, so a query data set's FULL
 * query text / fields / calculated fields / total fields / parameters are exercised against a REAL
 * in-memory schema built with the typed {@code DcsFactory} singletons (the same pattern
 * {@code DcsWriterTest} uses).
 * </p>
 * The default settings projection is exercised through the generated typed settings factory and the
 * package-visible selection / filter / order rendering seams.
 */
public class DcsStructureReaderTest
{
    private static DataCompositionSchema newSchema()
    {
        return com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchema();
    }

    private static Presentation title(String text)
    {
        Presentation presentation = DcsFactory.eINSTANCE.createPresentation();
        presentation.setValue(text);
        return presentation;
    }

    private static DataCompositionField field(String path)
    {
        DataCompositionField f = DcsFactory.eINSTANCE.createDataCompositionField();
        f.setValue(path);
        return f;
    }

    // ==================== empty / null schema ====================

    @Test
    public void testRenderNullSchemaRendersMinimalNote()
    {
        String rendered = DcsStructureReader.render("Report.X.Template.Main", null, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rendered.contains("Report.X.Template.Main")); //$NON-NLS-1$
        assertTrue(rendered.contains("no schema content")); //$NON-NLS-1$
    }

    @Test
    public void testRenderEmptySchemaSkipsEverySection()
    {
        String rendered = DcsStructureReader.render("CommonTemplate.Empty", newSchema(), "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rendered.contains("Data Composition Schema: CommonTemplate.Empty")); //$NON-NLS-1$
        assertFalse(rendered.contains("## Data sources")); //$NON-NLS-1$
        assertFalse(rendered.contains("## Data sets")); //$NON-NLS-1$
        assertFalse(rendered.contains("## Calculated fields")); //$NON-NLS-1$
        assertFalse(rendered.contains("## Total fields")); //$NON-NLS-1$
        assertFalse(rendered.contains("## Parameters")); //$NON-NLS-1$
        assertFalse(rendered.contains("## Default settings")); //$NON-NLS-1$
    }

    // ==================== data sets: query text in a fenced block, fields table ====================

    @Test
    public void testQueryDataSetRendersFullQueryInFencedBlock()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales"); //$NON-NLS-1$
        String query = "SELECT\n\tGoods.Description AS Description\nFROM\n\tCatalog.Goods AS Goods"; //$NON-NLS-1$
        dataSet.setQuery(query);
        dataSet.setDataSource("Local1"); //$NON-NLS-1$

        DataCompositionSchemaDataSetField goodsField = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetField();
        goodsField.setDataPath("Description"); //$NON-NLS-1$
        goodsField.setField("Goods.Description"); //$NON-NLS-1$
        goodsField.setTitle(title("Item|name")); // a '|' must be escaped in the table cell //$NON-NLS-1$
        com._1c.g5.v8.dt.dcs.model.common.DataCompositionDataSetFieldRole role =
            com._1c.g5.v8.dt.dcs.model.common.DcsFactory.eINSTANCE.createDataCompositionDataSetFieldRole();
        role.setDimension(true);
        goodsField.setRole(role);
        dataSet.getFields().add(goodsField);
        schema.getDataSets().add(dataSet);

        String rendered = DcsStructureReader.render("Report.Sales.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the section heading must be present", rendered.contains("## Data sets")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the data set name/kind subsection must be present", //$NON-NLS-1$
            rendered.contains("### Sales (query)")); //$NON-NLS-1$
        assertTrue("the data source must be present", rendered.contains("**Data source:** Local1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the FULL query text must be present verbatim inside a fenced block", //$NON-NLS-1$
            rendered.contains("```sql\n" + query + "\n```")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the field's data path must be present", rendered.contains("Description")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the field's source column must be present", rendered.contains("Goods.Description")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a table cell '|' must be escaped", rendered.contains("Item\\|name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the role summary must list the set dimension flag", rendered.contains("dimension")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testObjectDataSetRendersObjectNameAndKind()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetObject dataSet =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetObject();
        dataSet.setName("Obj1"); //$NON-NLS-1$
        dataSet.setObjectName("Catalog.Goods"); //$NON-NLS-1$
        schema.getDataSets().add(dataSet);

        String rendered = DcsStructureReader.render("CommonTemplate.Obj", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rendered.contains("### Obj1 (object)")); //$NON-NLS-1$
        assertTrue(rendered.contains("**Object:** Catalog.Goods")); //$NON-NLS-1$
    }

    @Test
    public void testObjectDataSetRendersDataSourceWhenPresent()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetObject dataSet =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetObject();
        dataSet.setName("Obj2"); //$NON-NLS-1$
        dataSet.setObjectName("Catalog.Warehouses"); //$NON-NLS-1$
        dataSet.setDataSource("Local2"); //$NON-NLS-1$
        schema.getDataSets().add(dataSet);

        String rendered = DcsStructureReader.render("CommonTemplate.Obj2", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an object data set's own data source must be present", //$NON-NLS-1$
            rendered.contains("**Data source:** Local2")); //$NON-NLS-1$
    }

    @Test
    public void testUnionDataSetRendersUnionOfNestedNames()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetUnion union =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetUnion();
        union.setName("Combined"); //$NON-NLS-1$

        DataCompositionSchemaDataSetObject first =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetObject();
        first.setName("Sales"); //$NON-NLS-1$
        DataCompositionSchemaDataSetObject second =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetObject();
        second.setName("Returns"); //$NON-NLS-1$
        union.getItems().add(first);
        union.getItems().add(second);
        schema.getDataSets().add(union);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the union's own kind/name subsection must be present", //$NON-NLS-1$
            rendered.contains("### Combined (union)")); //$NON-NLS-1$
        assertTrue("the union's nested item names must be joined", //$NON-NLS-1$
            rendered.contains("**Union of:** Sales, Returns")); //$NON-NLS-1$
    }

    @Test
    public void testDataSetFieldFolderRendersAsAFolderRow()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales"); //$NON-NLS-1$
        DataCompositionSchemaDataSetFieldFolder folder = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaDataSetFieldFolder();
        folder.setDataPath("Group"); //$NON-NLS-1$
        folder.setTitle(title("Group title")); //$NON-NLS-1$
        dataSet.getFields().add(folder);
        schema.getDataSets().add(dataSet);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the folder's data path must be present", rendered.contains("Group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the folder's title must be present", rendered.contains("Group title")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a folder field must be marked as such", rendered.contains("(folder)")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDataSetFieldOfUnrecognizedKindFallsBackToEClassName()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaDataSetQuery dataSet =
            com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales"); //$NON-NLS-1$
        // DataCompositionSchemaNestedDataSet is a THIRD DataSetField subinterface (besides Field/Folder) -
        // it hits the reader's defensive "else" row (data path/field/title columns empty, EClass name only).
        DataCompositionSchemaNestedDataSet nested = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaNestedDataSet();
        dataSet.getFields().add(nested);
        schema.getDataSets().add(dataSet);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an unrecognized DataSetField kind must fall back to its EClass simple name", //$NON-NLS-1$
            rendered.contains("DataCompositionSchemaNestedDataSet")); //$NON-NLS-1$
    }

    // ==================== calculated fields / total fields ====================

    @Test
    public void testCalculatedFieldRendersDataPathTitleAndExpression()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaCalculatedField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaCalculatedField();
        field.setDataPath("Total"); //$NON-NLS-1$
        field.setExpression("Quantity * Price"); //$NON-NLS-1$
        field.setTitle(title("Total amount")); //$NON-NLS-1$
        schema.getCalculatedFields().add(field);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rendered.contains("## Calculated fields")); //$NON-NLS-1$
        assertTrue(rendered.contains("Total")); //$NON-NLS-1$
        assertTrue(rendered.contains("Total amount")); //$NON-NLS-1$
        assertTrue(rendered.contains("Quantity * Price")); //$NON-NLS-1$
    }

    @Test
    public void testTotalFieldRendersDataPathExpressionAndGroups()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaTotalField field = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaTotalField();
        field.setDataPath("Amount"); //$NON-NLS-1$
        field.setExpression("Sum(Amount)"); //$NON-NLS-1$
        field.getGroups().add("Goods"); //$NON-NLS-1$
        schema.getTotalFields().add(field);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rendered.contains("## Total fields")); //$NON-NLS-1$
        assertTrue(rendered.contains("Sum(Amount)")); //$NON-NLS-1$
        assertTrue(rendered.contains("Goods")); //$NON-NLS-1$
    }

    // ==================== parameters: title / value type / value / use ====================

    @Test
    public void testParameterRendersTitleValueTypeAndUse()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("Period"); //$NON-NLS-1$
        parameter.setTitle(title("Period")); //$NON-NLS-1$
        parameter.setUse(DataCompositionParameterUse.AUTO);

        TypeDescription valueType = McoreFactory.eINSTANCE.createTypeDescription();
        Type stringType = McoreFactory.eINSTANCE.createType();
        stringType.setName("String"); //$NON-NLS-1$
        valueType.getTypes().add(stringType);
        parameter.setValueType(valueType);

        NumberValue defaultValue = McoreFactory.eINSTANCE.createNumberValue();
        defaultValue.setValue(BigDecimal.TEN);
        parameter.getValues().add(defaultValue);

        schema.getParameters().add(parameter);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(rendered.contains("## Parameters")); //$NON-NLS-1$
        assertTrue(rendered.contains("Period")); //$NON-NLS-1$
        assertTrue("the resolved type name must be present", rendered.contains("String")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the default value must be present", rendered.contains("10")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the use literal must be present", //$NON-NLS-1$
            rendered.contains(DataCompositionParameterUse.AUTO.getName()));
    }

    @Test
    public void testParameterTitleFallsBackToFirstLocalizedValue()
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory
            .eINSTANCE.createDataCompositionSchemaParameter();
        parameter.setName("P"); //$NON-NLS-1$
        Presentation presentation = DcsFactory.eINSTANCE.createPresentation();
        LocalString local = DcsFactory.eINSTANCE.createLocalString();
        local.getContent().put("en", "English fallback"); //$NON-NLS-1$ //$NON-NLS-2$
        presentation.setLocalValue(local);
        parameter.setTitle(presentation);
        schema.getParameters().add(parameter);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "ru"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(rendered, rendered.contains("English fallback")); //$NON-NLS-1$
    }

    @Test
    public void testParameterDefaultValuesCoverEveryDescribeValueKind()
    {
        // One parameter with one default value of every mcore Value kind describeValue/describeSimpleValue/
        // describeTypedValue dispatch on, plus one kind NONE of them recognize (the eClass-name fallback) -
        // joinValues comma-joins them all into a single table cell, so one render covers every branch.
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("Mixed"); //$NON-NLS-1$

        StringValue stringValue = McoreFactory.eINSTANCE.createStringValue();
        stringValue.setValue("Hello"); //$NON-NLS-1$
        parameter.getValues().add(stringValue);

        BooleanValue booleanValue = McoreFactory.eINSTANCE.createBooleanValue();
        booleanValue.setValue(true);
        parameter.getValues().add(booleanValue);

        DateValue dateValue = McoreFactory.eINSTANCE.createDateValue();
        com._1c.g5.v8.dt.mcore.util.Date rawDate = new com._1c.g5.v8.dt.mcore.util.Date(2024, 1, 1, 0, 0, 0);
        dateValue.setValue(rawDate);
        parameter.getValues().add(dateValue);

        EnumValue enumValue = McoreFactory.eINSTANCE.createEnumValue();
        enumValue.setValue(DataCompositionParameterUse.AUTO);
        parameter.getValues().add(enumValue);

        TypeValue typeValue = McoreFactory.eINSTANCE.createTypeValue();
        Type numberType = McoreFactory.eINSTANCE.createType();
        numberType.setName("Number"); //$NON-NLS-1$
        typeValue.setValue(numberType);
        parameter.getValues().add(typeValue);

        ReferenceValue referenceValue = McoreFactory.eINSTANCE.createReferenceValue();
        LocalString referenceTarget = DcsFactory.eINSTANCE.createLocalString();
        referenceValue.setValue(referenceTarget);
        parameter.getValues().add(referenceValue);

        DesignTimeValue designTimeValue = DcsFactory.eINSTANCE.createDesignTimeValue();
        designTimeValue.setValue("MyDesignTimeExpr"); //$NON-NLS-1$
        DesignTimeValueValue designTimeValueValue = DcsFactory.eINSTANCE.createDesignTimeValueValue();
        designTimeValueValue.setValue(designTimeValue);
        parameter.getValues().add(designTimeValueValue);

        UndefinedValue undefinedValue = McoreFactory.eINSTANCE.createUndefinedValue();
        parameter.getValues().add(undefinedValue);

        schema.getParameters().add(parameter);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a StringValue must render quoted", rendered.contains("\"Hello\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a BooleanValue must render its literal", rendered.contains("true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a DateValue must render the mcore Date's raw toString()", //$NON-NLS-1$
            rendered.contains(rawDate.toString()));
        assertTrue("an EnumValue must render its literal name", //$NON-NLS-1$
            rendered.contains(DataCompositionParameterUse.AUTO.getName()));
        assertTrue("a TypeValue must render the resolved type name", rendered.contains("Number")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a ReferenceValue must render the referenced object's toString()", //$NON-NLS-1$
            rendered.contains(referenceTarget.toString()));
        assertTrue("a DesignTimeValueValue must render its raw text", //$NON-NLS-1$
            rendered.contains("MyDesignTimeExpr")); //$NON-NLS-1$
        assertTrue("an unrecognized Value kind must fall back to its EClass simple name", //$NON-NLS-1$
            rendered.contains("UndefinedValue")); //$NON-NLS-1$
    }

    @Test
    public void testParameterValueTypeFallsBackToEClassNameForANonTypeTypeItem()
    {
        // TypeSet is a TypeItem that is NOT a Type - typeItemName() must fall back to its EClass simple
        // name rather than a (nonexistent) getName() call.
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("SetParam"); //$NON-NLS-1$

        TypeDescription valueType = McoreFactory.eINSTANCE.createTypeDescription();
        TypeSet typeSet = McoreFactory.eINSTANCE.createTypeSet();
        valueType.getTypes().add(typeSet);
        parameter.setValueType(valueType);
        schema.getParameters().add(parameter);

        String rendered = DcsStructureReader.render("Report.X.Template.Main", schema, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a non-Type TypeItem must fall back to its EClass simple name", //$NON-NLS-1$
            rendered.contains("TypeSet")); //$NON-NLS-1$
    }

    // ==================== default settings: selection / filter (incl. group) / order ====================

    @Test
    public void testRenderSelectionListsFieldTitleAndUse()
    {
        DataCompositionSelectedField selectedField = settingsFactory().createDataCompositionSelectedField();
        selectedField.setField(field("Description")); //$NON-NLS-1$
        selectedField.setTitle(title("Description")); //$NON-NLS-1$
        selectedField.setUse(true);

        DataCompositionSelectedFields selection = settingsFactory().createDataCompositionSelectedFields();
        selection.getItems().add(selectedField);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue(rendered.contains("### Selection")); //$NON-NLS-1$
        assertTrue(rendered.contains("Description")); //$NON-NLS-1$
        assertTrue(rendered.contains("(title: Description)")); //$NON-NLS-1$
        assertFalse(rendered.contains("[not used]")); //$NON-NLS-1$
    }

    @Test
    public void testRenderSelectionEmptyIsEmptyString()
    {
        assertTrue(DcsStructureReader.renderSelection(null, "en").isEmpty()); //$NON-NLS-1$
        DataCompositionSelectedFields emptySelection = settingsFactory().createDataCompositionSelectedFields();
        assertTrue(DcsStructureReader.renderSelection(emptySelection, "en").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testRenderSelectionFlagsADisabledField()
    {
        DataCompositionSelectedField selectedField = settingsFactory().createDataCompositionSelectedField();
        selectedField.setField(field("Description")); //$NON-NLS-1$
        selectedField.setUse(false);

        DataCompositionSelectedFields selection = settingsFactory().createDataCompositionSelectedFields();
        selection.getItems().add(selectedField);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue("a disabled selected field must be flagged", rendered.contains("[not used]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderSelectionRendersAGroupWithNestedChildren()
    {
        DataCompositionSelectedField child = settingsFactory().createDataCompositionSelectedField();
        child.setField(field("Description")); //$NON-NLS-1$
        child.setUse(true);

        DataCompositionSelectedFieldGroup group = settingsFactory().createDataCompositionSelectedFieldGroup();
        group.setField(field("GroupField")); //$NON-NLS-1$
        group.setUse(true);
        group.getItems().add(child);

        DataCompositionSelectedFields selection = settingsFactory().createDataCompositionSelectedFields();
        selection.getItems().add(group);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue("the group's own field must be present", rendered.contains("GroupField")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a group item must be marked as such", rendered.contains("(group)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the nested child must be rendered too", rendered.contains("Description")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderSelectionRendersTheAutoFieldsMarker()
    {
        DataCompositionAutoSelectedField auto = settingsFactory().createDataCompositionAutoSelectedField();
        DataCompositionSelectedFields selection = settingsFactory().createDataCompositionSelectedFields();
        selection.getItems().add(auto);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue("a DataCompositionAuto* item must render the auto-fields marker", //$NON-NLS-1$
            rendered.contains("_(auto fields)_")); //$NON-NLS-1$
    }

    @Test
    public void testRenderFilterConditionAndNestedGroup()
    {
        DataCompositionFilterItem topCondition = settingsFactory().createDataCompositionFilterItem();
        topCondition.setLeft(field("Quantity")); //$NON-NLS-1$
        topCondition.setComparisonType(DataCompositionComparisonType.GREATER);
        NumberValue ten = McoreFactory.eINSTANCE.createNumberValue();
        ten.setValue(BigDecimal.TEN);
        topCondition.getRight().add(ten);
        topCondition.setUse(true);

        DataCompositionFilterItem nestedCondition = settingsFactory().createDataCompositionFilterItem();
        nestedCondition.setLeft(field("Warehouse")); //$NON-NLS-1$
        nestedCondition.setComparisonType(DataCompositionComparisonType.EQUAL);
        nestedCondition.setUse(false);

        DataCompositionFilterItemGroup group = settingsFactory().createDataCompositionFilterItemGroup();
        group.setGroupType(DataCompositionFilterItemsGroupType.AND_GROUP);
        group.getItems().add(nestedCondition);

        DataCompositionFilter filter = settingsFactory().createDataCompositionFilter();
        filter.getItems().add(topCondition);
        filter.getItems().add(group);

        String rendered = DcsStructureReader.renderFilter(filter);
        assertTrue(rendered.contains("### Filter")); //$NON-NLS-1$
        assertTrue("the left field of the top-level condition must be present", //$NON-NLS-1$
            rendered.contains("Quantity")); //$NON-NLS-1$
        assertTrue("the comparison literal must be present", rendered.contains("GREATER")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the right-hand literal value must be present", rendered.contains("10")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the nested group's type must be present", rendered.contains("AND_GROUP group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a disabled nested condition must be flagged", rendered.contains("[not used]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the nested condition's field must be present", rendered.contains("Warehouse")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderFilterEmptyIsEmptyString()
    {
        assertTrue(DcsStructureReader.renderFilter(null).isEmpty());
        assertTrue(DcsStructureReader.renderFilter(settingsFactory().createDataCompositionFilter()).isEmpty());
    }

    @Test
    public void testRenderFilterConditionWithoutARightHandValue()
    {
        // "right" is intentionally left empty; the optional trailing value must simply be omitted.
        DataCompositionFilterItem condition = settingsFactory().createDataCompositionFilterItem();
        condition.setLeft(field("Quantity")); //$NON-NLS-1$
        condition.setComparisonType(DataCompositionComparisonType.GREATER);
        condition.setUse(true);

        DataCompositionFilter filter = settingsFactory().createDataCompositionFilter();
        filter.getItems().add(condition);

        String rendered = DcsStructureReader.renderFilter(filter);
        assertTrue("with no right-hand value the line must end right after the comparison literal", //$NON-NLS-1$
            rendered.contains("- Quantity GREATER\n")); //$NON-NLS-1$
    }

    @Test
    public void testRenderOrderListsFieldDirectionAndUse()
    {
        DataCompositionOrderItem orderItem = settingsFactory().createDataCompositionOrderItem();
        orderItem.setField(field("Description")); //$NON-NLS-1$
        orderItem.setOrderType(DataCompositionSortDirection.ASC);
        orderItem.setUse(true);

        DataCompositionOrder order = settingsFactory().createDataCompositionOrder();
        order.getItems().add(orderItem);

        String rendered = DcsStructureReader.renderOrder(order);
        assertTrue(rendered.contains("### Order")); //$NON-NLS-1$
        assertTrue(rendered.contains("Description")); //$NON-NLS-1$
        assertTrue(rendered.contains("ASC")); //$NON-NLS-1$
        assertFalse(rendered.contains("[not used]")); //$NON-NLS-1$
    }

    @Test
    public void testRenderOrderEmptyIsEmptyString()
    {
        assertTrue(DcsStructureReader.renderOrder(null).isEmpty());
        assertTrue(DcsStructureReader.renderOrder(settingsFactory().createDataCompositionOrder()).isEmpty());
    }

    @Test
    public void testRenderOrderFlagsADisabledItem()
    {
        DataCompositionOrderItem orderItem = settingsFactory().createDataCompositionOrderItem();
        orderItem.setField(field("Description")); //$NON-NLS-1$
        orderItem.setOrderType(DataCompositionSortDirection.DESC);
        orderItem.setUse(false);

        DataCompositionOrder order = settingsFactory().createDataCompositionOrder();
        order.getItems().add(orderItem);

        String rendered = DcsStructureReader.renderOrder(order);
        assertTrue(rendered.contains("DESC")); //$NON-NLS-1$
        assertTrue("a disabled order item must be flagged", rendered.contains("[not used]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderOrderRendersTheAutoOrderMarker()
    {
        DataCompositionAutoOrderItem auto = settingsFactory().createDataCompositionAutoOrderItem();
        DataCompositionOrder order = settingsFactory().createDataCompositionOrder();
        order.getItems().add(auto);

        String rendered = DcsStructureReader.renderOrder(order);
        assertTrue("a DataCompositionAuto* item must render the auto-order marker", //$NON-NLS-1$
            rendered.contains("_(auto order)_")); //$NON-NLS-1$
    }

    private static com._1c.g5.v8.dt.dcs.model.settings.DcsFactory settingsFactory()
    {
        return com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
    }
}

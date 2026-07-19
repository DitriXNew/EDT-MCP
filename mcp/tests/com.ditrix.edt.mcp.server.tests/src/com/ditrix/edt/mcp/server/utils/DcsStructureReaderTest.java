/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterUse;
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
 * <p>
 * The default settings variant (selection / filter / order) lives in
 * {@code com._1c.g5.v8.dt.dcs.model.settings}, which is a Tycho ACCESS-RESTRICTED (non-API) package on
 * this target platform (proven at build time - referencing any of its types fails the build), so
 * {@link DcsStructureReader#renderSelection} / {@code renderFilter} / {@code renderOrder} (package-visible
 * for exactly this reason, mirroring {@code DcsWriter.parse}) are exercised against a tiny SELF-CONTAINED
 * dynamic EMF fixture reproducing just the feature names the reader reads reflectively - the same
 * technique {@code FormStructureReaderTest} uses for the (also inaccessible) form-model package.
 * </p>
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
    //
    // com._1c.g5.v8.dt.dcs.model.settings is access-restricted (see the class javadoc), so these exercise
    // DcsStructureReader's package-visible renderSelection/renderFilter/renderOrder directly against a
    // tiny dynamic EMF fixture (SETTINGS_MODEL below) instead of a real DataCompositionSettings.

    @Test
    public void testRenderSelectionListsFieldTitleAndUse()
    {
        EObject selectedField = SETTINGS_MODEL.newItem(SETTINGS_MODEL.selectedField);
        selectedField.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("field"), field("Description")); //$NON-NLS-1$
        selectedField.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("title"), title("Description")); //$NON-NLS-1$ //$NON-NLS-2$
        selectedField.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("use"), Boolean.TRUE); //$NON-NLS-1$

        EObject selection = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.selectedFields);
        SETTINGS_MODEL.addItem(selection, selectedField);

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
        EObject emptySelection = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.selectedFields);
        assertTrue(DcsStructureReader.renderSelection(emptySelection, "en").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testRenderSelectionFlagsADisabledField()
    {
        EObject selectedField = SETTINGS_MODEL.newItem(SETTINGS_MODEL.selectedField);
        selectedField.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("field"), field("Description")); //$NON-NLS-1$ //$NON-NLS-2$
        selectedField.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("use"), Boolean.FALSE); //$NON-NLS-1$

        EObject selection = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.selectedFields);
        SETTINGS_MODEL.addItem(selection, selectedField);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue("a disabled selected field must be flagged", rendered.contains("[not used]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderSelectionRendersAGroupWithNestedChildren()
    {
        EObject child = SETTINGS_MODEL.newItem(SETTINGS_MODEL.selectedField);
        child.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("field"), field("Description")); //$NON-NLS-1$ //$NON-NLS-2$
        child.eSet(SETTINGS_MODEL.selectedField.getEStructuralFeature("use"), Boolean.TRUE); //$NON-NLS-1$

        EObject group = SETTINGS_MODEL.newItem(SETTINGS_MODEL.selectedFieldGroup);
        group.eSet(SETTINGS_MODEL.selectedFieldGroup.getEStructuralFeature("field"), field("GroupField")); //$NON-NLS-1$ //$NON-NLS-2$
        group.eSet(SETTINGS_MODEL.selectedFieldGroup.getEStructuralFeature("use"), Boolean.TRUE); //$NON-NLS-1$
        SETTINGS_MODEL.addTo(group, "items", child); //$NON-NLS-1$

        EObject selection = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.selectedFields);
        SETTINGS_MODEL.addItem(selection, group);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue("the group's own field must be present", rendered.contains("GroupField")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a group item must be marked as such", rendered.contains("(group)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the nested child must be rendered too", rendered.contains("Description")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderSelectionRendersTheAutoFieldsMarker()
    {
        EObject auto = SETTINGS_MODEL.newItem(SETTINGS_MODEL.autoSelectedField);
        EObject selection = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.selectedFields);
        SETTINGS_MODEL.addItem(selection, auto);

        String rendered = DcsStructureReader.renderSelection(selection, "en"); //$NON-NLS-1$
        assertTrue("a DataCompositionAuto* item must render the auto-fields marker", //$NON-NLS-1$
            rendered.contains("_(auto fields)_")); //$NON-NLS-1$
    }

    @Test
    public void testRenderFilterConditionAndNestedGroup()
    {
        EObject topCondition = SETTINGS_MODEL.newItem(SETTINGS_MODEL.filterItem);
        topCondition.eSet(SETTINGS_MODEL.filterItem.getEStructuralFeature("left"), field("Quantity")); //$NON-NLS-1$
        SETTINGS_MODEL.setEnum(topCondition, SETTINGS_MODEL.filterItem, "comparisonType", "GREATER"); //$NON-NLS-1$ //$NON-NLS-2$
        NumberValue ten = McoreFactory.eINSTANCE.createNumberValue();
        ten.setValue(BigDecimal.TEN);
        SETTINGS_MODEL.addTo(topCondition, "right", ten); //$NON-NLS-1$
        topCondition.eSet(SETTINGS_MODEL.filterItem.getEStructuralFeature("use"), Boolean.TRUE); //$NON-NLS-1$

        EObject nestedCondition = SETTINGS_MODEL.newItem(SETTINGS_MODEL.filterItem);
        nestedCondition.eSet(SETTINGS_MODEL.filterItem.getEStructuralFeature("left"), field("Warehouse")); //$NON-NLS-1$
        SETTINGS_MODEL.setEnum(nestedCondition, SETTINGS_MODEL.filterItem, "comparisonType", "EQUAL"); //$NON-NLS-1$ //$NON-NLS-2$
        nestedCondition.eSet(SETTINGS_MODEL.filterItem.getEStructuralFeature("use"), Boolean.FALSE); //$NON-NLS-1$

        EObject group = SETTINGS_MODEL.newItem(SETTINGS_MODEL.filterItemGroup);
        SETTINGS_MODEL.setEnum(group, SETTINGS_MODEL.filterItemGroup, "groupType", "AND_GROUP"); //$NON-NLS-1$ //$NON-NLS-2$
        SETTINGS_MODEL.addTo(group, "items", nestedCondition); //$NON-NLS-1$

        EObject filter = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.filter);
        SETTINGS_MODEL.addTo(filter, "items", topCondition); //$NON-NLS-1$
        SETTINGS_MODEL.addTo(filter, "items", group); //$NON-NLS-1$

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
        assertTrue(DcsStructureReader.renderFilter(SETTINGS_MODEL.newContainer(SETTINGS_MODEL.filter)).isEmpty());
    }

    // NOTE (SKIPPED sub-branch, honestly documented rather than forced): appendFilterItem's
    // "groupType.isEmpty() ? "group" : ..." bare-label fallback (reached when enumFeature() returns "")
    // is NOT reachable through this dynamic fixture: an unset EMF EEnum-typed attribute does not resolve
    // to null/absent - it defaults to the enum's own lowest-value LITERAL (verified empirically; e.g. an
    // untouched "groupType" here still reads back as AND_GROUP, not "") - so an empty result would need
    // either an EEnum with literally zero literals (which cannot share groupTypeEnum without breaking
    // testRenderFilterConditionAndNestedGroup's AND_GROUP assertions) or the real restricted settings
    // type's actual (unknown) unset behaviour. Not forced with fixture surgery; left uncovered.

    @Test
    public void testRenderFilterConditionWithoutARightHandValue()
    {
        // "right" is intentionally left EMPTY (a many-valued reference feature genuinely reads back as an
        // empty list when untouched, unlike a single-valued enum/boolean attribute - see the NOTE above) -
        // the optional trailing right-hand value must simply be omitted, not rendered as empty junk.
        EObject condition = SETTINGS_MODEL.newItem(SETTINGS_MODEL.filterItem);
        condition.eSet(SETTINGS_MODEL.filterItem.getEStructuralFeature("left"), field("Quantity")); //$NON-NLS-1$ //$NON-NLS-2$
        SETTINGS_MODEL.setEnum(condition, SETTINGS_MODEL.filterItem, "comparisonType", "GREATER"); //$NON-NLS-1$ //$NON-NLS-2$
        condition.eSet(SETTINGS_MODEL.filterItem.getEStructuralFeature("use"), Boolean.TRUE); //$NON-NLS-1$

        EObject filter = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.filter);
        SETTINGS_MODEL.addTo(filter, "items", condition); //$NON-NLS-1$

        String rendered = DcsStructureReader.renderFilter(filter);
        assertTrue("with no right-hand value the line must end right after the comparison literal", //$NON-NLS-1$
            rendered.contains("- Quantity GREATER\n")); //$NON-NLS-1$
    }

    @Test
    public void testRenderOrderListsFieldDirectionAndUse()
    {
        EObject orderItem = SETTINGS_MODEL.newItem(SETTINGS_MODEL.orderItem);
        orderItem.eSet(SETTINGS_MODEL.orderItem.getEStructuralFeature("field"), field("Description")); //$NON-NLS-1$
        SETTINGS_MODEL.setEnum(orderItem, SETTINGS_MODEL.orderItem, "orderType", "ASC"); //$NON-NLS-1$ //$NON-NLS-2$
        orderItem.eSet(SETTINGS_MODEL.orderItem.getEStructuralFeature("use"), Boolean.TRUE); //$NON-NLS-1$

        EObject order = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.order);
        SETTINGS_MODEL.addTo(order, "items", orderItem); //$NON-NLS-1$

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
        assertTrue(DcsStructureReader.renderOrder(SETTINGS_MODEL.newContainer(SETTINGS_MODEL.order)).isEmpty());
    }

    @Test
    public void testRenderOrderFlagsADisabledItem()
    {
        EObject orderItem = SETTINGS_MODEL.newItem(SETTINGS_MODEL.orderItem);
        orderItem.eSet(SETTINGS_MODEL.orderItem.getEStructuralFeature("field"), field("Description")); //$NON-NLS-1$ //$NON-NLS-2$
        SETTINGS_MODEL.setEnum(orderItem, SETTINGS_MODEL.orderItem, "orderType", "DESC"); //$NON-NLS-1$ //$NON-NLS-2$
        orderItem.eSet(SETTINGS_MODEL.orderItem.getEStructuralFeature("use"), Boolean.FALSE); //$NON-NLS-1$

        EObject order = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.order);
        SETTINGS_MODEL.addTo(order, "items", orderItem); //$NON-NLS-1$

        String rendered = DcsStructureReader.renderOrder(order);
        assertTrue(rendered.contains("DESC")); //$NON-NLS-1$
        assertTrue("a disabled order item must be flagged", rendered.contains("[not used]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderOrderRendersTheAutoOrderMarker()
    {
        EObject auto = SETTINGS_MODEL.newItem(SETTINGS_MODEL.autoOrderItem);
        EObject order = SETTINGS_MODEL.newContainer(SETTINGS_MODEL.order);
        SETTINGS_MODEL.addTo(order, "items", auto); //$NON-NLS-1$

        String rendered = DcsStructureReader.renderOrder(order);
        assertTrue("a DataCompositionAuto* item must render the auto-order marker", //$NON-NLS-1$
            rendered.contains("_(auto order)_")); //$NON-NLS-1$
    }

    // ==================== dynamic EMF fixture for the access-restricted "settings" subtree ====================

    private static final SettingsLikeModel SETTINGS_MODEL = new SettingsLikeModel();

    /**
     * A tiny dynamic EMF metamodel reproducing just the feature names {@link DcsStructureReader} reads via
     * reflection off the (real, but access-restricted) {@code com._1c.g5.v8.dt.dcs.model.settings}
     * package: {@code items} / {@code field} / {@code left} / {@code right} / {@code comparisonType} /
     * {@code groupType} / {@code orderType} / {@code title} / {@code use}. A {@code field}/{@code left}
     * value is a REAL, ACCESSIBLE typed {@link DataCompositionField} (or an {@code mcore} {@code Value}) -
     * only the CONTAINERS (selection / filter / order / their items) are dynamic, exactly like
     * {@code FormStructureReaderTest}'s {@code FormLikeModel} stands in for the (also inaccessible)
     * form-model package.
     */
    private static final class SettingsLikeModel
    {
        final EClass selectedFields;
        final EClass selectedField;
        final EClass selectedFieldGroup;
        final EClass autoSelectedField;
        final EClass filter;
        final EClass filterItem;
        final EClass filterItemGroup;
        final EClass order;
        final EClass orderItem;
        final EClass autoOrderItem;

        SettingsLikeModel()
        {
            EcoreFactory factory = EcoreFactory.eINSTANCE;
            EPackage pkg = factory.createEPackage();
            pkg.setName("dcssettingslike"); //$NON-NLS-1$
            pkg.setNsPrefix("dcssettingslike"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/dcssettingslike"); //$NON-NLS-1$

            EEnum comparisonTypeEnum = enumOf(factory, "DataCompositionComparisonType", "EQUAL", "GREATER"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            EEnum groupTypeEnum = enumOf(factory, "DataCompositionFilterItemsGroupType", "AND_GROUP"); //$NON-NLS-1$ //$NON-NLS-2$
            EEnum sortDirectionEnum = enumOf(factory, "DataCompositionSortDirection", "ASC", "DESC"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            selectedField = newEClass(factory, "DataCompositionSelectedField"); //$NON-NLS-1$
            objectRef(factory, selectedField, "field"); //$NON-NLS-1$
            objectRef(factory, selectedField, "title"); //$NON-NLS-1$
            boolAttr(factory, selectedField, "use"); //$NON-NLS-1$

            selectedFields = newEClass(factory, "DataCompositionSelectedFields"); //$NON-NLS-1$
            manyObjectRef(factory, selectedFields, "items"); //$NON-NLS-1$

            selectedFieldGroup = newEClass(factory, "DataCompositionSelectedFieldGroup"); //$NON-NLS-1$
            objectRef(factory, selectedFieldGroup, "field"); //$NON-NLS-1$
            manyObjectRef(factory, selectedFieldGroup, "items"); //$NON-NLS-1$
            boolAttr(factory, selectedFieldGroup, "use"); //$NON-NLS-1$

            // Name only matters: isAutoItem() dispatches purely on the "DataCompositionAuto" EClass-name
            // prefix, so these two carry no features at all.
            autoSelectedField = newEClass(factory, "DataCompositionAutoSelectedField"); //$NON-NLS-1$
            autoOrderItem = newEClass(factory, "DataCompositionAutoOrderItem"); //$NON-NLS-1$

            filterItem = newEClass(factory, "DataCompositionFilterItem"); //$NON-NLS-1$
            objectRef(factory, filterItem, "left"); //$NON-NLS-1$
            manyObjectRef(factory, filterItem, "right"); //$NON-NLS-1$
            enumAttr(factory, filterItem, "comparisonType", comparisonTypeEnum); //$NON-NLS-1$
            boolAttr(factory, filterItem, "use"); //$NON-NLS-1$

            filterItemGroup = newEClass(factory, "DataCompositionFilterItemGroup"); //$NON-NLS-1$
            manyObjectRef(factory, filterItemGroup, "items"); //$NON-NLS-1$
            enumAttr(factory, filterItemGroup, "groupType", groupTypeEnum); //$NON-NLS-1$
            boolAttr(factory, filterItemGroup, "use"); //$NON-NLS-1$

            filter = newEClass(factory, "DataCompositionFilter"); //$NON-NLS-1$
            manyObjectRef(factory, filter, "items"); //$NON-NLS-1$

            orderItem = newEClass(factory, "DataCompositionOrderItem"); //$NON-NLS-1$
            objectRef(factory, orderItem, "field"); //$NON-NLS-1$
            enumAttr(factory, orderItem, "orderType", sortDirectionEnum); //$NON-NLS-1$
            boolAttr(factory, orderItem, "use"); //$NON-NLS-1$

            order = newEClass(factory, "DataCompositionOrder"); //$NON-NLS-1$
            manyObjectRef(factory, order, "items"); //$NON-NLS-1$

            pkg.getEClassifiers().add(comparisonTypeEnum);
            pkg.getEClassifiers().add(groupTypeEnum);
            pkg.getEClassifiers().add(sortDirectionEnum);
            pkg.getEClassifiers().add(selectedField);
            pkg.getEClassifiers().add(selectedFields);
            pkg.getEClassifiers().add(selectedFieldGroup);
            pkg.getEClassifiers().add(autoSelectedField);
            pkg.getEClassifiers().add(filterItem);
            pkg.getEClassifiers().add(filterItemGroup);
            pkg.getEClassifiers().add(filter);
            pkg.getEClassifiers().add(orderItem);
            pkg.getEClassifiers().add(autoOrderItem);
            pkg.getEClassifiers().add(order);
        }

        EObject newItem(EClass eClass)
        {
            return new DynamicEObjectImpl(eClass);
        }

        EObject newContainer(EClass eClass)
        {
            return new DynamicEObjectImpl(eClass);
        }

        void addItem(EObject container, EObject item)
        {
            addTo(container, "items", item); //$NON-NLS-1$
        }

        @SuppressWarnings("unchecked")
        void addTo(EObject owner, String featureName, EObject value)
        {
            ((List<EObject>)owner.eGet(owner.eClass().getEStructuralFeature(featureName))).add(value);
        }

        /** Sets a dynamic EEnum feature (declared on {@code declaringClass}) to the named literal. */
        void setEnum(EObject object, EClass declaringClass, String featureName, String literal)
        {
            EStructuralFeature feature = declaringClass.getEStructuralFeature(featureName);
            EEnumLiteral lit = ((EEnum)((EAttribute)feature).getEAttributeType())
                .getEEnumLiteral(literal);
            object.eSet(feature, lit.getInstance());
        }

        private static EClass newEClass(EcoreFactory factory, String name)
        {
            EClass eClass = factory.createEClass();
            eClass.setName(name);
            return eClass;
        }

        /** A single-valued, containment reference typed generically at {@code EObject} (any kind fits). */
        private static void objectRef(EcoreFactory factory, EClass owner, String name)
        {
            EReference reference = factory.createEReference();
            reference.setName(name);
            reference.setEType(EcorePackage.Literals.EOBJECT);
            reference.setContainment(true);
            owner.getEStructuralFeatures().add(reference);
        }

        /** A many-valued, containment reference typed generically at {@code EObject}. */
        private static void manyObjectRef(EcoreFactory factory, EClass owner, String name)
        {
            EReference reference = factory.createEReference();
            reference.setName(name);
            reference.setEType(EcorePackage.Literals.EOBJECT);
            reference.setContainment(true);
            reference.setUpperBound(-1);
            owner.getEStructuralFeatures().add(reference);
        }

        private static void boolAttr(EcoreFactory factory, EClass owner, String name)
        {
            EAttribute attribute = factory.createEAttribute();
            attribute.setName(name);
            attribute.setEType(EcorePackage.Literals.EBOOLEAN);
            owner.getEStructuralFeatures().add(attribute);
        }

        private static void enumAttr(EcoreFactory factory, EClass owner, String name, EEnum type)
        {
            EAttribute attribute = factory.createEAttribute();
            attribute.setName(name);
            attribute.setEType(type);
            owner.getEStructuralFeatures().add(attribute);
        }

        private static EEnum enumOf(EcoreFactory factory, String name, String... literals)
        {
            EEnum eEnum = factory.createEEnum();
            eEnum.setName(name);
            int value = 0;
            for (String literal : literals)
            {
                EEnumLiteral lit = factory.createEEnumLiteral();
                lit.setName(literal);
                lit.setLiteral(literal);
                lit.setValue(value++);
                eEnum.getELiterals().add(lit);
            }
            return eEnum;
        }
    }
}

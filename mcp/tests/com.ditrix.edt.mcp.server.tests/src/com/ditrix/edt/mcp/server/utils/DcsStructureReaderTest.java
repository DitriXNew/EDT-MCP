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
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;

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
        final EClass filter;
        final EClass filterItem;
        final EClass filterItemGroup;
        final EClass order;
        final EClass orderItem;

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
            pkg.getEClassifiers().add(filterItem);
            pkg.getEClassifiers().add(filterItemGroup);
            pkg.getEClassifiers().add(filter);
            pkg.getEClassifiers().add(orderItem);
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

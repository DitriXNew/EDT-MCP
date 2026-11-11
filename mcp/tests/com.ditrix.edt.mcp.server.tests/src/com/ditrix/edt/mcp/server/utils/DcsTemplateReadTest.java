/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.common.DataCompositionAreaTemplateType;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaGroupTemplate;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.schema.DcsPackage;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/**
 * #662: the read-only exposure of a schema's area templates and their grouping bindings. They
 * are counted and listed in the root summary and read under {@code type='schema'}, exactly like
 * data-set links, because the typed write vocabulary deliberately has no template type.
 *
 * <p>The area-template body classes live in a package this bundle does not import, so the
 * fixtures build them through the model's own EClasses.
 */
public class DcsTemplateReadTest
{
    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$
    // A Cyrillic template name, as real configurations carry: "МакетИтогов".
    private static final String RU_TEMPLATE =
        "МакетИтогов"; //$NON-NLS-1$

    @Test
    public void testSchemaSummaryCountsTemplatesAndGroupBindingsWithAddresses()
    {
        String markdown = success(render(templatedSchema(), ROOT, "schema")); //$NON-NLS-1$
        assertContains(markdown, "| Templates | 2 | Report.Sales#/templates |"); //$NON-NLS-1$
        assertContains(markdown, "| Group templates | 1 | Report.Sales#/groupTemplates |"); //$NON-NLS-1$
        assertContains(markdown,
            "| Group header templates | 2 | Report.Sales#/groupHeaderTemplates |"); //$NON-NLS-1$
        assertContains(markdown, "## Templates\n"); //$NON-NLS-1$
        assertContains(markdown, "| TotalRow | 1 row, 2 cells, 2 parameters | Report.Sales#/templates/TotalRow |"); //$NON-NLS-1$
        assertContains(markdown, "Report.Sales#/templates/" + RU_TEMPLATE); //$NON-NLS-1$
        assertContains(markdown, "## Group templates\n"); //$NON-NLS-1$
        assertContains(markdown, "| Report.Sales#/groupTemplates/0 | Totals · Header → TotalRow |"); //$NON-NLS-1$
        assertContains(markdown, "## Group header templates\n"); //$NON-NLS-1$
        assertContains(markdown,
            "| Report.Sales#/groupHeaderTemplates/0 | fields Amount1, Customer · OverallHeader → Missing |"); //$NON-NLS-1$
    }

    @Test
    public void testSchemaWithoutTemplatesCountsZeroAndPrintsNoTemplateSections()
    {
        String markdown = success(render(plainSchema(), ROOT, "schema")); //$NON-NLS-1$
        assertContains(markdown, "| Templates | 0 | Report.Sales#/templates |"); //$NON-NLS-1$
        assertContains(markdown, "| Group templates | 0 | Report.Sales#/groupTemplates |"); //$NON-NLS-1$
        assertContains(markdown, "| Group header templates | 0 | Report.Sales#/groupHeaderTemplates |"); //$NON-NLS-1$
        assertFalse(markdown, markdown.contains("## Templates\n")); //$NON-NLS-1$
        assertFalse(markdown, markdown.contains("## Group templates\n")); //$NON-NLS-1$
        assertFalse(markdown, markdown.contains("## Group header templates\n")); //$NON-NLS-1$

        String absent = success(render(null, ROOT, "schema")); //$NON-NLS-1$
        assertContains(absent, "| Templates | 0 | Report.Sales#/templates |"); //$NON-NLS-1$
        assertContains(absent, "| Group templates | 0 | Report.Sales#/groupTemplates |"); //$NON-NLS-1$
    }

    @Test
    public void testTemplateCollectionsPageUnderTheSchemaType()
    {
        DataCompositionSchema schema = templatedSchema();
        String templates = success(render(schema, ROOT + "#/templates", "schema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(templates, "# DCS collection: schema"); //$NON-NLS-1$
        assertContains(templates, "**Items:** 2"); //$NON-NLS-1$
        assertContains(templates, "| TotalRow | DataCompositionSchemaTemplateDescription | Report.Sales#/templates/TotalRow | 1 row, 2 cells, 2 parameters |"); //$NON-NLS-1$

        String bindings = success(render(schema, ROOT + "#/groupTemplates", "schema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(bindings, "# DCS collection: schema"); //$NON-NLS-1$
        assertContains(bindings, "**Items:** 1"); //$NON-NLS-1$
        assertContains(bindings, "| Report.Sales#/groupTemplates/0 | Totals · Header → TotalRow |"); //$NON-NLS-1$
    }

    @Test
    public void testAreaTemplateDrillDownSummarisesItsBodyParametersAndBindings()
    {
        String markdown = success(render(templatedSchema(), ROOT + "#/templates/TotalRow", "schema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(markdown, "# DCS node: DataCompositionSchemaTemplateDescription"); //$NON-NLS-1$
        assertContains(markdown, "| name | TotalRow |"); //$NON-NLS-1$
        assertContains(markdown, "## Template\n"); //$NON-NLS-1$
        assertContains(markdown, "| Body | DataCompositionAreaTemplate |"); //$NON-NLS-1$
        assertContains(markdown, "| Rows | 1 |"); //$NON-NLS-1$
        assertContains(markdown, "| Cells | 2 |"); //$NON-NLS-1$
        assertContains(markdown, "| Parameters | 2 |"); //$NON-NLS-1$
        assertContains(markdown, "format='xml'"); //$NON-NLS-1$
        assertContains(markdown,
            "| TotalNameParam | DataCompositionExpressionAreaParameter | Amount1 |"); //$NON-NLS-1$
        assertContains(markdown,
            "| DetailsParam | DataCompositionDetailsAreaParameter | (details: 1 field expression) |"); //$NON-NLS-1$
        assertContains(markdown, "## Grouping bindings\n"); //$NON-NLS-1$
        assertContains(markdown, "| Report.Sales#/groupTemplates/0 | Totals · Header → TotalRow |"); //$NON-NLS-1$
        assertContains(markdown, "| Report.Sales#/groupHeaderTemplates/1 | Totals2 · Footer → TotalRow |"); //$NON-NLS-1$
        // The first header binding names a template that does not exist, not this one.
        assertFalse(markdown, markdown.contains("Report.Sales#/groupHeaderTemplates/0")); //$NON-NLS-1$
        // The body is summarised, never dumped as an outline of unreadable addresses.
        assertFalse(markdown, markdown.contains("## Contained structure")); //$NON-NLS-1$
        assertFalse(markdown, markdown.contains("#/templates/TotalRow/template")); //$NON-NLS-1$

        String unbound = success(render(templatedSchema(), ROOT + "#/templates/" + RU_TEMPLATE, //$NON-NLS-1$
            "schema")); //$NON-NLS-1$
        assertContains(unbound, "| name | " + RU_TEMPLATE + " |"); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(unbound, "| Body | (none) |"); //$NON-NLS-1$
        assertContains(unbound, "| Rows | 0 |"); //$NON-NLS-1$
        assertContains(unbound, "_(no parameters)_"); //$NON-NLS-1$
        assertContains(unbound, "_(no grouping binds this template)_"); //$NON-NLS-1$
    }

    @Test
    public void testGroupBindingDrillDownNamesItsGroupingTypeAndTemplateAddress()
    {
        DataCompositionSchema schema = templatedSchema();
        String bound = success(render(schema, ROOT + "#/groupTemplates/0", "schema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(bound, "# DCS node: DataCompositionSchemaGroupTemplate"); //$NON-NLS-1$
        assertContains(bound, "| groupName | Totals |"); //$NON-NLS-1$
        assertContains(bound, "| templateType | Header |"); //$NON-NLS-1$
        assertContains(bound, "| template | TotalRow |"); //$NON-NLS-1$
        assertContains(bound, "## Grouping binding\n"); //$NON-NLS-1$
        assertContains(bound, "| Group fields | (none) |"); //$NON-NLS-1$
        assertContains(bound, "| Template address | Report.Sales#/templates/TotalRow |"); //$NON-NLS-1$
        assertFalse(bound, bound.contains("## Contained structure")); //$NON-NLS-1$

        String dangling = success(render(schema, ROOT + "#/groupHeaderTemplates/0", "schema")); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(dangling, "| templateType | OverallHeader |"); //$NON-NLS-1$
        assertContains(dangling, "| Group fields | Amount1, Customer |"); //$NON-NLS-1$
        assertContains(dangling, "| Template address | (no template named 'Missing' in this schema) |"); //$NON-NLS-1$
    }

    @Test
    public void testUnknownTemplateAndOutOfRangeBindingAreRefusedWithExistingKeys()
    {
        DataCompositionSchema schema = templatedSchema();
        DcsReadProjection.Result unknown = render(schema, ROOT + "#/templates/Nope", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(unknown.isSuccess());
        assertContains(unknown.error(), "Pointer segment 'Nope'"); //$NON-NLS-1$
        assertContains(unknown.error(), "TotalRow, " + RU_TEMPLATE); //$NON-NLS-1$

        DcsReadProjection.Result outOfRange = render(schema, ROOT + "#/groupTemplates/5", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(outOfRange.isSuccess());
        assertContains(outOfRange.error(), "Pointer segment '5'"); //$NON-NLS-1$
        assertContains(outOfRange.error(), "Existing keys/indices at that level: 0."); //$NON-NLS-1$
    }

    @Test
    public void testTemplateNodesTellTheCallerTheirReadTypeIsSchema()
    {
        DataCompositionSchema schema = templatedSchema();
        DcsReadProjection.Result template = render(schema, ROOT + "#/templates/TotalRow", "dataSet"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(template.isSuccess());
        assertContains(template.error(), "(its type is 'schema')"); //$NON-NLS-1$
        DcsReadProjection.Result binding = render(schema, ROOT + "#/groupTemplates/0", "grouping"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(binding.isSuccess());
        assertContains(binding.error(), "(its type is 'schema')"); //$NON-NLS-1$
        DcsReadProjection.Result collection = render(schema, ROOT + "#/groupHeaderTemplates", "parameter"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(collection.isSuccess());
        assertContains(collection.error(), "(its type is 'schema')"); //$NON-NLS-1$
    }

    @Test
    public void testReplaceRefusalAddressesTemplatesByTheSameNameTheReadPrints()
    {
        DataCompositionSchema schema = templatedSchema();
        List<DcsReadProjection.Unmodellable> refused =
            DcsReadProjection.unmodellableNodes(schema, ROOT, DcsReplaceTestCatalogues.schema());
        boolean byName = false;
        for (DcsReadProjection.Unmodellable node : refused)
        {
            byName |= (ROOT + "#/templates/TotalRow").equals(node.address()); //$NON-NLS-1$
        }
        assertTrue(refused.toString(), byName);
        // ...and that address resolves to the template it names.
        assertContains(success(render(schema, ROOT + "#/templates/TotalRow", "schema")), //$NON-NLS-1$ //$NON-NLS-2$
            "| name | TotalRow |"); //$NON-NLS-1$
    }

    // ----- fixtures -----

    private static DataCompositionSchema templatedSchema()
    {
        DataCompositionSchema schema = plainSchema();
        DataCompositionSchemaTemplateDescription totalRow =
            DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        totalRow.setName("TotalRow"); //$NON-NLS-1$
        EObject area = EcoreUtil.create(
            DcsPackage.Literals.DATA_COMPOSITION_SCHEMA_TEMPLATE_DESCRIPTION__TEMPLATE.getEReferenceType());
        EObject row = create(area, "DataCompositionAreaTemplateTableRow"); //$NON-NLS-1$
        list(row, "cells").add(create(area, "DataCompositionAreaTemplateTableCell")); //$NON-NLS-1$ //$NON-NLS-2$
        list(row, "cells").add(create(area, "DataCompositionAreaTemplateTableCell")); //$NON-NLS-1$ //$NON-NLS-2$
        list(area, "items").add(row); //$NON-NLS-1$
        totalRow.eSet(DcsPackage.Literals.DATA_COMPOSITION_SCHEMA_TEMPLATE_DESCRIPTION__TEMPLATE, area);
        EObject expression = create(area, "DataCompositionExpressionAreaParameter"); //$NON-NLS-1$
        set(expression, "name", "TotalNameParam"); //$NON-NLS-1$ //$NON-NLS-2$
        set(expression, "expression", "Amount1"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject details = create(area, "DataCompositionDetailsAreaParameter"); //$NON-NLS-1$
        set(details, "name", "DetailsParam"); //$NON-NLS-1$ //$NON-NLS-2$
        list(details, "fieldExpression").add( //$NON-NLS-1$
            create(area, "DataCompositionDetailsAreaParameterFieldExpression")); //$NON-NLS-1$
        List<EObject> parameters = list(totalRow, "parameters"); //$NON-NLS-1$
        parameters.add(expression);
        parameters.add(details);
        schema.getTemplates().add(totalRow);

        DataCompositionSchemaTemplateDescription russian =
            DcsFactory.eINSTANCE.createDataCompositionSchemaTemplateDescription();
        russian.setName(RU_TEMPLATE);
        schema.getTemplates().add(russian);

        DataCompositionSchemaGroupTemplate binding = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        binding.setGroupName("Totals"); //$NON-NLS-1$
        binding.setTemplateType(DataCompositionAreaTemplateType.HEADER);
        binding.setTemplate("TotalRow"); //$NON-NLS-1$
        schema.getGroupTemplates().add(binding);

        DataCompositionSchemaGroupTemplate header = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        header.getGroupFields().add(field("Amount1")); //$NON-NLS-1$
        header.getGroupFields().add(field("Customer")); //$NON-NLS-1$
        header.setTemplateType(DataCompositionAreaTemplateType.OVERALL_HEADER);
        header.setTemplate("Missing"); //$NON-NLS-1$
        schema.getGroupHeaderTemplates().add(header);

        DataCompositionSchemaGroupTemplate footer = DcsFactory.eINSTANCE.createDataCompositionSchemaGroupTemplate();
        footer.setGroupName("Totals2"); //$NON-NLS-1$
        footer.setTemplateType(DataCompositionAreaTemplateType.FOOTER);
        footer.setTemplate("TotalRow"); //$NON-NLS-1$
        schema.getGroupHeaderTemplates().add(footer);
        return schema;
    }

    private static DataCompositionSchema plainSchema()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaDataSetQuery dataSet = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales"); //$NON-NLS-1$
        dataSet.setQuery("SELECT 1 AS Amount1"); //$NON-NLS-1$
        DataCompositionSchemaDataSetField amount = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
        amount.setDataPath("Amount1"); //$NON-NLS-1$
        dataSet.getFields().add(amount);
        schema.getDataSets().add(dataSet);
        return schema;
    }

    private static DataCompositionField field(String value)
    {
        DataCompositionField field = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionField();
        field.setValue(value);
        return field;
    }

    private static EObject create(EObject sibling, String className)
    {
        return EcoreUtil.create((EClass)sibling.eClass().getEPackage().getEClassifier(className));
    }

    private static void set(EObject object, String feature, Object value)
    {
        object.eSet(object.eClass().getEStructuralFeature(feature), value);
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> list(EObject object, String feature)
    {
        return (List<EObject>)object.eGet(object.eClass().getEStructuralFeature(feature));
    }

    private static DcsReadProjection.Result render(DataCompositionSchema schema, String address,
        String type)
    {
        return DcsReadProjection.render(ROOT, TargetKind.REPORT_MAIN_DCS, schema,
            DcsAddress.parse(address).address(), type, "en", 100_000, 0); //$NON-NLS-1$
    }

    private static String success(DcsReadProjection.Result result)
    {
        assertTrue(result.error(), result.isSuccess());
        return result.markdown();
    }

    private static void assertContains(String text, String expected)
    {
        assertTrue("expected <" + expected + "> in:\n" + text, text.contains(expected)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

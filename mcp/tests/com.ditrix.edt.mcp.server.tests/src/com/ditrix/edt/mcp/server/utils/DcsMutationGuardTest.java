/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionPeriodAdditionType;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChart;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChartGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChartOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupFields;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.mcore.EnumValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;

/** Scoping of the unmodellable-content refusal. */
public class DcsMutationGuardTest
{
    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$

    /**
     * Content the writer cannot model (here a chart's nested output-parameter values) blocks a
     * replacement only when it is UNDER the target. The bare-root form of a concrete settings type
     * must therefore be scoped to that type's own node before it is checked: unscoped, a node
     * anywhere in the document counted as a descendant and refused a selection-only replacement
     * that could never have removed it.
     */
    @Test
    public void testUnmodellableChartContentBlocksOnlyWhenItIsUnderTheAddressedNode()
    {
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory factory =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = factory.createDataCompositionSettings();
        DataCompositionChart chart = factory.createDataCompositionChart();
        settings.getItems().add(chart);
        schema.setDefaultSettings(settings);
        assertNull("a fully modelled chart must not block a ROOT replacement", //$NON-NLS-1$
            DcsMutationGuard.replaceError(schema, address(ROOT), DcsReplaceTestCatalogues.schema()));

        DataCompositionChartOutputParameterValues output =
            factory.createDataCompositionChartOutputParameterValues();
        SettingsParameterValue chartType = factory.createSettingsParameterValue();
        chartType.getNestedParameterValues().add(factory.createSettingsParameterValue());
        output.getItems().add(chartType);
        chart.setOutputParameters(output);

        // Unscoped: the whole schema against a pointerless address - the nested values count.
        String whole = DcsMutationGuard.replaceError(schema, address(ROOT), DcsReplaceTestCatalogues.schema());
        assertNotNull("nested chart output values must block a ROOT replacement", whole); //$NON-NLS-1$
        assertTrue(whole, whole.contains("#/defaultSettings/items/0/outputParameters/items/0")); //$NON-NLS-1$

        // Scoped the way the tool now scopes it: the settings root, addressed at 'selection'.
        assertNull("the chart in the structure must NOT block a selection-only replacement", //$NON-NLS-1$
            DcsMutationGuard.replaceError(settings, address(ROOT + "#/selection"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$

        // ...and it still blocks when the address genuinely covers it.
        assertNotNull("content under the addressed node must still block", //$NON-NLS-1$
            DcsMutationGuard.replaceError(settings, address(ROOT + "#/items"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$
        assertNotNull("replacing the chart itself must still block", //$NON-NLS-1$
            DcsMutationGuard.replaceError(settings, address(ROOT + "#/items/0"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$
    }

    /**
     * A period-addition bound the ValueSpec writer cannot restate (an enum literal) blocks a
     * replace of the chart and its ancestors, while an authorable date bound does not.
     */
    @Test
    public void testUnauthorablePeriodAdditionBoundInAChartGroupBlocksReplace()
    {
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory factory =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = factory.createDataCompositionSettings();
        DataCompositionChart chart = factory.createDataCompositionChart();
        DataCompositionChartGroup point = factory.createDataCompositionChartGroup();
        DataCompositionGroupFields fields = factory.createDataCompositionGroupFields();
        DataCompositionGroupField field = factory.createDataCompositionGroupField();
        // A stored date bound has a date: a payload-less DateValue never comes back from Template.dcs.
        com._1c.g5.v8.dt.mcore.DateValue begin = McoreFactory.eINSTANCE.createDateValue();
        begin.setValue(com._1c.g5.v8.dt.mcore.util.Date.fromString("0001-01-01T00:00:00")); //$NON-NLS-1$
        field.setPeriodAdditionBegin(begin);
        fields.getItems().add(field);
        point.setGroupFields(fields);
        chart.getPoints().add(point);
        settings.getItems().add(chart);
        schema.setDefaultSettings(settings);
        assertNull("a date bound is authorable", //$NON-NLS-1$
            DcsMutationGuard.replaceError(schema, address(ROOT + "#/defaultSettings/items/0"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$

        EnumValue bound = McoreFactory.eINSTANCE.createEnumValue();
        bound.setValue(DataCompositionPeriodAdditionType.MONTH);
        field.setPeriodAdditionEnd(bound);

        for (String target : new String[] {"#/defaultSettings/items/0", //$NON-NLS-1$
            "#/defaultSettings/items/0/points/0", "#/defaultSettings"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String error = DcsMutationGuard.replaceError(schema, address(ROOT + target), DcsReplaceTestCatalogues.schema());
            assertNotNull(target, error);
            assertTrue(error, error.contains("EnumValue at Report.Sales#/defaultSettings/items/0" //$NON-NLS-1$
                + "/points/0/groupFields/items/0/periodAdditionEnd")); //$NON-NLS-1$
        }
        assertNull("a sibling replace does not reach the bound", //$NON-NLS-1$
            DcsMutationGuard.replaceError(schema, address(ROOT + "#/defaultSettings/selection"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$
    }

    /** A filter's left operand is written only through an untyped ValueSpec, like a period bound. */
    @Test
    public void testUnauthorableFilterLeftOperandInAChartGroupBlocksReplace()
    {
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory factory =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = factory.createDataCompositionSettings();
        DataCompositionChart chart = factory.createDataCompositionChart();
        DataCompositionChartGroup series = factory.createDataCompositionChartGroup();
        DataCompositionFilter filter = factory.createDataCompositionFilter();
        DataCompositionFilterItem item = factory.createDataCompositionFilterItem();
        item.setLeft(namedField("Amount")); //$NON-NLS-1$
        filter.getItems().add(item);
        series.setFilter(filter);
        chart.getSeries().add(series);
        settings.getItems().add(chart);
        schema.setDefaultSettings(settings);
        assertNull("a field operand is authorable", //$NON-NLS-1$
            DcsMutationGuard.replaceError(schema, address(ROOT + "#/defaultSettings/items/0"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$

        EnumValue left = McoreFactory.eINSTANCE.createEnumValue();
        left.setValue(DataCompositionPeriodAdditionType.MONTH);
        item.setLeft(left);

        String error = DcsMutationGuard.replaceError(schema,
            address(ROOT + "#/defaultSettings/items/0/series/0"), DcsReplaceTestCatalogues.schema()); //$NON-NLS-1$
        assertNotNull(error);
        assertTrue(error, error.contains("EnumValue at Report.Sales#/defaultSettings/items/0" //$NON-NLS-1$
            + "/series/0/filter/items/0/left")); //$NON-NLS-1$

        // The right operands go through the same untyped ValueSpec.
        item.setLeft(namedField("Amount")); //$NON-NLS-1$
        com._1c.g5.v8.dt.mcore.StringValue text = McoreFactory.eINSTANCE.createStringValue();
        text.setValue("Open"); //$NON-NLS-1$
        item.getRight().add(text);
        assertNull("a string operand is authorable", //$NON-NLS-1$
            DcsMutationGuard.replaceError(schema, address(ROOT + "#/defaultSettings/items/0"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$
        EnumValue right = McoreFactory.eINSTANCE.createEnumValue();
        right.setValue(DataCompositionPeriodAdditionType.MONTH);
        item.getRight().add(right);
        error = DcsMutationGuard.replaceError(schema,
            address(ROOT + "#/defaultSettings/items/0"), DcsReplaceTestCatalogues.schema()); //$NON-NLS-1$
        assertNotNull(error);
        assertTrue(error, error.contains("EnumValue at Report.Sales#/defaultSettings/items/0" //$NON-NLS-1$
            + "/series/0/filter/items/0/right")); //$NON-NLS-1$
    }

    /** The typed parameter body holds one value; a stored list would be truncated to it. */
    @Test
    public void testMultiValuedChartOutputParameterBlocksReplace()
    {
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory factory =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = factory.createDataCompositionSettings();
        DataCompositionChart chart = factory.createDataCompositionChart();
        DataCompositionChartOutputParameterValues output =
            factory.createDataCompositionChartOutputParameterValues();
        SettingsParameterValue parameter = factory.createSettingsParameterValue();
        parameter.setParameter(DcsReplaceTestCatalogues.parameter("Title"));
        com._1c.g5.v8.dt.dcs.model.core.LocalString title =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
        title.getContent().put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        parameter.getValues().add(title);
        output.getItems().add(parameter);
        chart.setOutputParameters(output);
        settings.getItems().add(chart);
        schema.setDefaultSettings(settings);
        assertNull("a single value is authorable", //$NON-NLS-1$
            DcsMutationGuard.replaceError(schema, address(ROOT + "#/defaultSettings/items/0"), DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$

        parameter.getValues().add(McoreFactory.eINSTANCE.createStringValue());
        String error = DcsMutationGuard.replaceError(schema,
            address(ROOT + "#/defaultSettings/items/0"), DcsReplaceTestCatalogues.schema()); //$NON-NLS-1$
        assertNotNull(error);
        assertTrue(error, error.contains("SettingsParameterValue at Report.Sales#/defaultSettings" //$NON-NLS-1$
            + "/items/0/outputParameters/items/0")); //$NON-NLS-1$
    }

    /** The typed parameter writer builds enums but no references or types. */
    @Test
    public void testSingleUnbuildableChartOutputParameterValueBlocksReplace()
    {
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory factory =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = factory.createDataCompositionSettings();
        DataCompositionChart chart = factory.createDataCompositionChart();
        DataCompositionChartOutputParameterValues output =
            factory.createDataCompositionChartOutputParameterValues();
        SettingsParameterValue parameter = factory.createSettingsParameterValue();
        parameter.setParameter(DcsReplaceTestCatalogues.parameter("ChartType"));
        try
        {
            parameter.getValues().add(org.eclipse.emf.ecore.util.EcoreUtil.copy(
                DcsReplaceTestCatalogues.load(DcsSettingsWriter.OutputParameterCatalogue.CHART,
                    com._1c.g5.v8.dt.platform.version.Version.V8_3_27, "en")
                    .findItem("ChartType").defValue()));
        }
        catch (com._1c.g5.v8.dt.dcs.path.DcsPathException e)
        {
            throw new AssertionError(e);
        }
        output.getItems().add(parameter);
        chart.setOutputParameters(output);
        settings.getItems().add(chart);
        schema.setDefaultSettings(settings);
        DcsAddress target = address(ROOT + "#/defaultSettings/items/0"); //$NON-NLS-1$
        assertNull("an enum is authorable", DcsMutationGuard.replaceError(schema, target, DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$

        parameter.getValues().set(0, McoreFactory.eINSTANCE.createTypeValue());
        assertNotNull("a TypeValue is not", DcsMutationGuard.replaceError(schema, target, DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$
        parameter.getValues().set(0, McoreFactory.eINSTANCE.createReferenceValue());
        assertNotNull("a ReferenceValue is not", DcsMutationGuard.replaceError(schema, target, DcsReplaceTestCatalogues.schema())); //$NON-NLS-1$
    }

    /** Schema input parameters are written from a full values array, so a list is authorable. */
    @Test
    public void testMultiValuedInputParameterDoesNotBlockSchemaReplace()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaParameter parameter = DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("P"); //$NON-NLS-1$
        com._1c.g5.v8.dt.dcs.model.core.InputParameters inputs =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createInputParameters();
        com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue item =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                .createDataCompositionParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Owner")); //$NON-NLS-1$
        for (String text : new String[] {"A", "B"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            com._1c.g5.v8.dt.mcore.StringValue value = McoreFactory.eINSTANCE.createStringValue();
            value.setValue(text);
            item.getValues().add(value);
        }
        inputs.getItems().add(item);
        parameter.setInputParameters(inputs);
        schema.getParameters().add(parameter);

        assertNull(DcsMutationGuard.replaceError(schema, address(ROOT), DcsReplaceTestCatalogues.schema()));
    }

    @Test
    public void testNestedInputParameterValuesDeclareTheUnsupportedReadWriteLimit()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSchemaParameter parameter = DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("P"); //$NON-NLS-1$
        com._1c.g5.v8.dt.dcs.model.core.InputParameters inputs =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createInputParameters();
        com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue item =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                .createDataCompositionParameterValue();
        item.getNestedParameterValues().add(
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
                .createDataCompositionParameterValue());
        inputs.getItems().add(item);
        parameter.setInputParameters(inputs);
        schema.getParameters().add(parameter);

        String error = DcsMutationGuard.replaceError(schema, address(ROOT), DcsReplaceTestCatalogues.schema());

        assertNotNull(error);
        assertTrue(error, error.contains("DataCompositionParameterValue")); //$NON-NLS-1$
        assertTrue(error, error.contains("#/parameters/P/inputParameters/items/0")); //$NON-NLS-1$
    }

    private static DcsAddress address(String raw)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(raw);
        assertTrue(raw, parsed.isSuccess());
        return parsed.address();
    }

    /** A stored field operand names its field: value(), the writer of the slot, refuses an empty one. */
    private static com._1c.g5.v8.dt.dcs.model.core.DataCompositionField namedField(String name)
    {
        com._1c.g5.v8.dt.dcs.model.core.DataCompositionField field =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        field.setValue(name);
        return field;
    }
}

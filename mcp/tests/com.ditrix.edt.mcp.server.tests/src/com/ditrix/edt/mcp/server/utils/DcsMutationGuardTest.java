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
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionChart;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;

/** Scoping of the unmodellable-content refusal. */
public class DcsMutationGuardTest
{
    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$

    /**
     * A chart the writer cannot model blocks a replacement only when it is UNDER the target. The
     * bare-root form of a concrete settings type must therefore be scoped to that type's own node
     * before it is checked: unscoped, a chart anywhere in the document counted as a descendant and
     * refused a selection-only replacement that could never have removed it.
     */
    @Test
    public void testAChartBlocksOnlyWhenItIsUnderTheAddressedNode()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionSettings();
        DataCompositionChart chart =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createDataCompositionChart();
        settings.getItems().add(chart);
        schema.setDefaultSettings(settings);

        // Unscoped: the whole schema against a pointerless address - the chart counts.
        String whole = DcsMutationGuard.replaceError(schema, address(ROOT));
        assertNotNull("a chart in the document must block a ROOT replacement", whole); //$NON-NLS-1$
        assertTrue(whole, whole.contains("Chart")); //$NON-NLS-1$

        // Scoped the way the tool now scopes it: the settings root, addressed at 'selection'.
        assertNull("a chart in the structure must NOT block a selection-only replacement", //$NON-NLS-1$
            DcsMutationGuard.replaceError(settings, address(ROOT + "#/selection"))); //$NON-NLS-1$

        // ...and it still blocks when the address genuinely covers it.
        assertNotNull("a chart under the addressed node must still block", //$NON-NLS-1$
            DcsMutationGuard.replaceError(settings, address(ROOT + "#/items"))); //$NON-NLS-1$
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

        String error = DcsMutationGuard.replaceError(schema, address(ROOT));

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
}

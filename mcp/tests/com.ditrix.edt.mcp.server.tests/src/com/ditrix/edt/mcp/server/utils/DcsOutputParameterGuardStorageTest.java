/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A replace dry run must apply the same storage rules as the holder's actual writer. */
public class DcsOutputParameterGuardStorageTest
{
    private static final String NAME = "ChartType.ScaleColor";
    private static final DcsFactory SETTINGS = DcsFactory.eINSTANCE;

    @Test
    public void testStoredAutomaticScaleColorBlocksEnclosingReplaceWithTheOutputWritersReason()
        throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(OutputParameterCatalogue.GROUP,
            Version.V8_3_27, "en");
        assertNotNull(catalogue.findItem(NAME));
        String writer = DcsSettingsWriter.applyOutputParameterItemForTest(
            SETTINGS.createSettingsParameterValue(), body("{color:'auto'}"), null, catalogue);
        assertNotNull("the actual output writer refuses this stored representation", writer);
        assertTrue(writer, writer.contains("EDT cannot reload that value"));

        var schema = DcsStoredXmlTestSupport.schema();
        var group = SETTINGS.createDataCompositionGroup();
        group.setOutputParameters(SETTINGS.createDataCompositionGroupOutputParameterValues());
        add(group.getOutputParameters(), McoreFactory.eINSTANCE.createColorValue());
        schema.getDefaultSettings().getItems().add(group);
        for (String target : List.of("Report.Sales", "Report.Sales#/defaultSettings",
            "Report.Sales#/defaultSettings/items/0"))
        {
            String guard = DcsMutationGuard.replaceError(schema, DcsAddress.parse(target).address(),
                DcsReplaceTestCatalogues.schema());
            assertNotNull(target + " must not discard an automatic ScaleColor", guard);
            assertTrue(guard, guard.contains("/defaultSettings/items/0/outputParameters/items/0"));
            assertTrue(guard, guard.contains(NAME));
            assertTrue(guard, guard.contains("EDT cannot reload that value"));
            assertTrue(guard, guard.contains("RGB color"));
            assertTrue(guard, guard.contains("action='update'"));
        }
        assertNull(((ColorValue)group.getOutputParameters().getItems().get(0).getValues().get(0)).getValue());
    }

    @Test
    public void testExplicitRgbScaleColorStillPassesTheOutputWriterAndEnclosingReplace() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(OutputParameterCatalogue.GROUP,
            Version.V8_3_27, "en");
        var item = SETTINGS.createSettingsParameterValue();
        assertNull(DcsSettingsWriter.applyOutputParameterItemForTest(item,
            body("{color:{red:12,green:34,blue:56}}"), null, catalogue));
        var schema = DcsStoredXmlTestSupport.schema();
        var group = SETTINGS.createDataCompositionGroup();
        group.setOutputParameters(SETTINGS.createDataCompositionGroupOutputParameterValues());
        group.getOutputParameters().getItems().add(item);
        schema.getDefaultSettings().getItems().add(group);
        assertNull(DcsStoredXmlTestSupport.guard(schema));
        assertNull(DcsMutationGuard.replaceError(schema,
            DcsAddress.parse("Report.Sales#/defaultSettings/items/0").address(),
            DcsReplaceTestCatalogues.schema()));
    }

    @Test
    public void testEveryOutputHolderUsesTheOutputSpecificDryRun()
    {
        List<ParameterValues> holders = List.of(SETTINGS.createDataCompositionOutputParameterValues(),
            SETTINGS.createDataCompositionGroupOutputParameterValues(),
            SETTINGS.createDataCompositionTableOutputParameterValues(),
            SETTINGS.createDataCompositionTableGroupOutputParameterValues(),
            SETTINGS.createDataCompositionChartOutputParameterValues(),
            SETTINGS.createDataCompositionChartGroupOutputParameterValues());
        var available = DcsReplaceTestCatalogues.available(NAME, "ScaleColorAlias",
            McoreFactory.eINSTANCE.createColorValue());
        for (int index = 0; index < holders.size(); index++)
        {
            ParameterValues holder = holders.get(index);
            OutputParameterCatalogue expected = OutputParameterCatalogue.values()[index];
            add(holder, McoreFactory.eINSTANCE.createColorValue());
            var context = new DcsWriterCatalogues(Version.V8_3_27, AppearanceCatalogue.SCHEMA, "en",
                (kind, version, language) -> {
                    assertSame(expected, kind);
                    return available;
                });
            String guard = DcsMutationGuard.replaceError(holder,
                DcsAddress.parse("Report.Sales").address(), context);
            assertNotNull(expected + " must use outputParameterValue", guard);
            assertTrue(guard, guard.contains("EDT cannot reload that value"));
        }
    }

    @Test
    public void testAppearanceKeepsItsOwnTypedWriterAndDoesNotInheritOutputStorageRules()
    {
        var available = DcsReplaceTestCatalogues.available(NAME, "ScaleColorAlias",
            McoreFactory.eINSTANCE.createColorValue());
        JsonObject body = new JsonObject();
        body.add(NAME, JsonParser.parseString("{color:'auto'}"));
        var written = DcsSettingsWriter.buildAppearanceForTest(body, null, null, available);
        assertNull(written.error, written.error);
        var context = new DcsWriterCatalogues(Version.V8_3_27, AppearanceCatalogue.SCHEMA, "en",
            (kind, version, language) -> {
                assertSame(AppearanceCatalogue.SCHEMA, kind);
                return available;
            });
        assertNull(DcsMutationGuard.replaceError(written.value,
            DcsAddress.parse("Report.Sales").address(), context));
    }

    private static void add(ParameterValues holder, ColorValue value)
    {
        var item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter(NAME));
        item.getValues().add(value);
        holder.getItems().add(item);
    }

    private static JsonObject body(String value)
    {
        return JsonParser.parseString("{parameter:{kind:'parameter',value:'" + NAME
            + "'},value:" + value + "}").getAsJsonObject();
    }
}

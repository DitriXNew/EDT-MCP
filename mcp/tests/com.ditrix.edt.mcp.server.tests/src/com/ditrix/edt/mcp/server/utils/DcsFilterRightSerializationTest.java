/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * #680: uses EDT's own XML shapes for both unsupported filter-right values.
 *
 * <p>Run with {@code -Dedtmcp.refreshDcsFixtures=true} to write the native serializations under
 * {@code target/} for refreshing the e2e fixture; an ordinary build writes nothing.</p>
 */
public class DcsFilterRightSerializationTest
{
    private static final String REFRESH_FIXTURES = "edtmcp.refreshDcsFixtures"; //$NON-NLS-1$

    /**
     * #680 after #682 round 5: both filter-right shapes EDT serializes are restated by value(): the
     * guard passes them after EDT's own XML, and the writer's valueList and standardBeginningDate
     * bodies rebuild values EDT saves to the same XML.
     */
    @Test
    public void testEdtSerializedFilterRightValuesRoundTripThroughTheWriter() throws Exception
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        schema.setDefaultSettings(settings);
        var group = DcsFactory.eINSTANCE.createDataCompositionGroup();
        settings.getItems().add(group);
        var filter = DcsFactory.eINSTANCE.createDataCompositionFilter();
        group.setFilter(filter);
        var list = McoreFactory.eINSTANCE.createTypedValueList();
        list.setValueType(McoreFactory.eINSTANCE.createTypeDescription());
        list.setLastId(1);
        var number = McoreFactory.eINSTANCE.createNumberValue();
        number.setValue(java.math.BigDecimal.ONE);
        var entry = McoreFactory.eINSTANCE.createTypedValueListItem();
        entry.setId(1);
        entry.setValue(number);
        list.getItems().add(entry);
        var beginning = McoreFactory.eINSTANCE.createStandardBeginningDateValue();
        beginning.setValue(McoreFactory.eINSTANCE.createStandardBeginningDate());
        beginning.getValue().setDate(com._1c.g5.v8.dt.mcore.util.Date.fromString("2026-01-01T00:00:00"));
        for (var value : java.util.List.of(list, beginning))
        {
            var item = DcsFactory.eINSTANCE.createDataCompositionFilterItem();
            var left = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
            left.setValue("Amount1");
            item.setLeft(left);
            item.getRight().add(value);
            filter.getItems().add(item);
        }
        byte[] xml = serialize(schema);
        DcsV8Serializer reader = new DcsV8Serializer((IDtProject)null, Version.V8_3_27, (IResourceLookup)null);
        DataCompositionSchema restored = reader.deserializeXML(new ByteArrayInputStream(xml));
        String missing = DcsXmlRoundTripComparator.firstMissingPath(new String(xml, StandardCharsets.UTF_8),
            new String(serialize(restored), StandardCharsets.UTF_8));
        assertNull("The native fixture must pass the XML channel's loss check: " + missing, missing);
        String error = DcsMutationGuard.replaceError(restored, DcsAddress.parse("Report.Sales").address(),
            DcsReplaceTestCatalogues.schema());
        assertNull("both EDT-serialized filter-right values are restatable", error);
        String[] bodies = {
            "{\"kind\":\"valueList\",\"value\":{\"lastId\":1,\"items\":[{\"id\":1,"
                + "\"value\":{\"kind\":\"number\",\"value\":1}}]}}",
            "{\"kind\":\"standardBeginningDate\",\"value\":{\"variant\":\"Custom\","
                + "\"date\":\"2026-01-01T00:00:00\"}}"};
        var filterItems = restored.getDefaultSettings().getItems().get(0);
        for (int i = 0; i < bodies.length; i++)
        {
            DcsSettingsWriter.ValueResult built = DcsSettingsWriter.buildValue(
                com.google.gson.JsonParser.parseString(bodies[i]), "right");
            assertNull(bodies[i], built.error);
            var stored = ((com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup)filterItems).getFilter()
                .getItems().get(i);
            assertTrue(bodies[i], org.eclipse.emf.ecore.util.EcoreUtil.equals(built.value,
                ((com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem)stored).getRight().get(0)));
        }
        // Source of the e2e fixture on request; no XML element names are invented by tests.
        refreshFixture("dcs-682-filter-right.xml", xml);
    }
    @Test
    public void testNativeCensusFixtureContainsDataParameterAndFontLosses() throws Exception
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        schema.setDefaultSettings(settings);
        var data = DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
        settings.setDataParameters(data);
        var first = DcsFactory.eINSTANCE.createSettingsParameterValue();
        first.setParameter(DcsReplaceTestCatalogues.parameter("P"));
        first.getValues().add(McoreFactory.eINSTANCE.createStringValue());
        first.getValues().add(McoreFactory.eINSTANCE.createStringValue());
        data.getItems().add(first);
        var second = DcsFactory.eINSTANCE.createSettingsParameterValue();
        second.setParameter(DcsReplaceTestCatalogues.parameter("Period"));
        var period = McoreFactory.eINSTANCE.createStandardPeriodValue();
        period.setValue(McoreFactory.eINSTANCE.createStandardPeriod());
        period.getValue().setStartDate(com._1c.g5.v8.dt.mcore.util.Date.fromString("2026-01-01T00:00:00"));
        period.getValue().setEndDate(com._1c.g5.v8.dt.mcore.util.Date.fromString("2026-01-31T00:00:00"));
        second.getValues().add(period);
        data.getItems().add(second);
        var output = DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues();
        settings.setOutputParameters(output);
        var unknown = DcsFactory.eINSTANCE.createSettingsParameterValue();
        unknown.setParameter(DcsReplaceTestCatalogues.parameter("Unknown"));
        unknown.getValues().add(McoreFactory.eINSTANCE.createStringValue());
        output.getItems().add(unknown);
        var appearance = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
        settings.setConditionalAppearance(appearance);
        var rule = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearanceItem();
        appearance.getItems().add(rule);
        var holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        rule.setAppearance(holder);
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Font"));
        var font = McoreFactory.eINSTANCE.createFontDef();
        font.setFaceName("Arial");
        font.setHeight(10.5f);
        font.setScale(90);
        var value = McoreFactory.eINSTANCE.createFontValue();
        value.setValue(font);
        item.getValues().add(value);
        holder.getItems().add(item);
        String refusal = DcsMutationGuard.replaceError(schema, DcsAddress.parse("Report.Sales").address(),
            DcsReplaceTestCatalogues.schema());
        assertNotNull(refusal);
        // Two values are restated by 'values', but these two hold no string; the custom period with
        // its dates is restated by a standardPeriod body.
        assertTrue(refusal, refusal.contains("value #0: the writer refuses its body"));
        assertFalse(refusal, refusal.contains("StandardPeriodValue"));
        assertTrue(refusal, refusal.contains("font height 10.5"));
        refreshFixture("dcs-682-census.xml", serialize(schema));
    }

    private static void refreshFixture(String name, byte[] xml) throws Exception
    {
        if (!Boolean.getBoolean(REFRESH_FIXTURES)) return;
        Path fixture = Path.of("target", name); //$NON-NLS-1$
        Files.createDirectories(fixture.getParent());
        Files.write(fixture, xml);
    }

    private static byte[] serialize(DataCompositionSchema schema) throws Exception
    {
        DcsV8Serializer serializer = new DcsV8Serializer((IDtProject)null, Version.V8_3_27, (IResourceLookup)null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        serializer.serializeXML(schema, output, serializer.createExportContext((IProject)null, Version.V8_3_27),
            new HashMap<String, UUID>(), "\n");
        return output.toByteArray();
    }

}

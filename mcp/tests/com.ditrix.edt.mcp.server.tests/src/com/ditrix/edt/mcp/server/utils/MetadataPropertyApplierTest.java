// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exact integer properties reject fractional values before preparing a mutation. */
public class MetadataPropertyApplierTest
{
    @Test
    public void exchangePlanNodeUsesUuidFactoryBeforeSettingTheFeature()
    {
        var plan = MdClassFactory.eINSTANCE.createExchangePlan();
        var feature = MetadataPropertyIntrospector.findFeature(plan, "thisNode");
        assertEquals(MetadataPropertyIntrospector.ValueKind.UUID, feature.valueKind);
        String value = "2BC60566-BC4B-4562-93C5-42512DD76D2F";
        var changes = new ArrayList<MetadataPropertyApplier.PreparedChange>();
        assertNull(prepare(plan, "thisNode", value, changes));
        assertEquals(UUID.fromString(value), changes.get(0).value());
        changes.get(0).applyTo(plan, null);
        assertEquals(UUID.fromString(value), plan.getThisNode());
        assertEquals(value.toLowerCase(java.util.Locale.ROOT), MetadataPropertyIntrospector.introspect(plan).stream()
            .filter(info -> info.name.equals("thisNode")).findFirst().orElseThrow().currentValue);
        for (String invalid : new String[] { "bad-uuid", "1-1-1-1-1", "", null })
        {
            changes.clear();
            String error = prepare(plan, "thisNode", invalid, changes);
            assertTrue(error, error.contains("Invalid UUID '" + invalid + "' for 'thisNode'"));
            assertTrue(error, error.contains("random UUID"));
            assertTrue(changes.isEmpty());
        }
    }

    @Test
    public void metadataIdentityAndOriginLinkAreReadOnly()
    {
        var plan = MdClassFactory.eINSTANCE.createExchangePlan();
        var changes = new ArrayList<MetadataPropertyApplier.PreparedChange>();
        for (String name : List.of("uuid", "extendedConfigurationObject"))
        {
            assertNull(MetadataPropertyIntrospector.findFeature(plan, name));
            assertFalse(MetadataPropertyIntrospector.introspect(plan).stream().anyMatch(info -> info.name.equals(name)));
            String error = prepare(plan, name, UUID.randomUUID().toString(), changes);
            assertTrue(error, error.contains("'" + name + "' is a read-only identifier"));
            assertTrue(error, error.contains("Remove it from properties"));
            assertTrue(changes.isEmpty());
        }
        String predefined = PredefinedWriter.parseProperties(List.of(property("id", UUID.randomUUID().toString())),
            true, new PredefinedWriter.ItemProps());
        assertTrue(predefined, predefined.contains("'id' is a read-only predefined item identifier"));
    }

    @Test
    public void fromBaseIsRejectedOnANativeType()
    {
        var changes = new ArrayList<MetadataPropertyApplier.PreparedChange>();
        String error = prepare(MdClassFactory.eINSTANCE.createCatalogAttribute(), "type", "fromBase", changes);
        assertTrue(error, error.contains("'fromBase' requires an adopted metadata"));
        assertTrue(error, error.contains("structured type value"));
        assertTrue(changes.isEmpty());
    }

    static String prepare(org.eclipse.emf.ecore.EObject target, String name, String value,
        List<MetadataPropertyApplier.PreparedChange> changes)
    {
        return MetadataPropertyApplier.prepare(new MetadataPropertyApplier.PrepareContext(null, null, null, null, null),
            target, null, property(name, value), changes, new MdNameNormalizer.Report(false), true);
    }

    private static JsonObject property(String name, String value)
    {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", name);
        prop.addProperty("value", value);
        return prop;
    }

    @Test public void integerPropertiesRetainExactValuesAndBounds()
    {
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), MetadataPropertyApplier.parseInteger("-2147483648.0"));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), MetadataPropertyApplier.parseInteger("2147483647.0"));
        assertEquals(Integer.valueOf(100), MetadataPropertyApplier.parseInteger("1e2"));
        assertEquals(Integer.valueOf(0), MetadataPropertyApplier.parseInteger("0.0"));
    }

    @Test public void invalidIntegersKeepTheModifyMetadataErrorAndPrepareNothing()
    {
        for (String raw : new String[] {null, "", "NaN", "Infinity", "1.5", "1.0000000000000001",
            "2147483647.00000001", "2147483648", "-2147483649"})
        {
            assertNull(raw, MetadataPropertyApplier.parseInteger(raw));
            var changes = new ArrayList<MetadataPropertyApplier.PreparedChange>();
            String result = MetadataPropertyApplier.prepareInteger("codeLength", raw, null, changes);
            assertEquals("'" + raw + "' is not a valid integer for 'codeLength'.",
                JsonParser.parseString(result).getAsJsonObject().get("error").getAsString());
            assertTrue(changes.isEmpty());
        }
    }
}

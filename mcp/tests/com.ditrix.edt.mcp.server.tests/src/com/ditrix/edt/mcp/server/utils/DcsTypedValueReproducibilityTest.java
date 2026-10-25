/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.*;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Enumerates real catalogue defaults, so adding a declared kind requires extending its inverse. */
public class DcsTypedValueReproducibilityTest
{
    @Test
    public void testEveryDeclaredKindInEveryRealCatalogueRoundTripsThroughItsWriter() throws Exception
    {
        Set<String> kinds = new HashSet<>();
        Set<String> unhandledKinds = new HashSet<>();
        int checked = 0;
        for (Version platform : List.of(Version.V8_3_27, Version.LATEST))
        {
            for (Enum<?> catalogue : allCatalogues())
            {
                DcsAvailableParameterCollection parameters = DcsReplaceTestCatalogues.load(catalogue,
                    platform, "en");
                assertTrue(platform + "." + catalogue, parameters.itemsCount() > 0);
                for (int i = 0; i < parameters.itemsCount(); i++)
                {
                    var parameter = parameters.getItemAt(i);
                    Value declared = parameter.defValue();
                    String kind = declared == null ? "null" : declared.eClass().getName();
                    kinds.add(kind);
                    if (Set.of("UsedDatabaseCopiesValue", "LineValue", "ChartTrendlinesValue").contains(kind))
                    {
                        // Real catalogue defaults that value() cannot construct.
                        // Verify refusal for every untyped emitter instead of inventing a new wire kind.
                        for (JsonElement body : untypedBodies())
                        {
                            var result = DcsSettingsWriter.buildTypedParameterValue(body, declared, null,
                                platform + "." + catalogue + "." + parameter.key(0));
                            assertNotNull(kind + " must remain unwritable: " + body, result.error);
                        }
                        assertNotNull(kind, DcsSettingsWriter.typedValueRefusal(declared, EcoreUtil.copy(declared)));
                        checked++;
                        continue;
                    }
                    JsonElement body = raw(declared);
                    if (body == null)
                    {
                        unhandledKinds.add(kind);
                        continue;
                    }
                    var result = DcsSettingsWriter.buildTypedParameterValue(body, declared, null,
                        platform + "." + catalogue + "." + parameter.key(0));
                    assertNull(platform + "." + catalogue + "." + parameter.key(0) + " (" + kind + "): " + result.error,
                        result.error);
                    assertNull(kind, DcsSettingsWriter.typedValueRefusal(declared, result.value));
                    if (declared != null)
                    {
                        Value foreign = declared instanceof StringValue ? McoreFactory.eINSTANCE.createNumberValue()
                            : McoreFactory.eINSTANCE.createStringValue();
                        assertNotNull(kind + " must refuse a foreign class",
                            DcsSettingsWriter.typedValueRefusal(declared, foreign));
                    }
                    checked++;
                }
            }
        }
        assertTrue("Audit new real catalogue kinds and add a round-trip or explicit refusal: " + unhandledKinds,
            unhandledKinds.isEmpty());
        System.out.println("Real catalogue default kinds: " + new java.util.TreeSet<>(kinds)
            + "; entries exercised: " + checked);
        assertTrue("Real catalogues must cover LocalString, EnumValue, FontValue and ColorValue: " + kinds,
            kinds.containsAll(List.of("LocalString", "EnumValue", "FontValue", "ColorValue")));
        assertTrue("The test must enumerate catalogue entries, not a hand-picked sample", checked > 50);
    }

    @Test
    public void testNullDeclaredTypeUsesOnlyUntypedValueSpecs()
    {
        var written = DcsSettingsWriter.buildTypedParameterValue(json("{\"kind\":\"number\",\"value\":42}"),
            null, null, "untyped");
        assertNull(written.error);
        assertNull(DcsSettingsWriter.typedValueRefusal(null, written.value));
        assertNotNull(DcsSettingsWriter.typedValueRefusal(null, McoreFactory.eINSTANCE.createReferenceValue()));
        assertNotNull(DcsSettingsWriter.typedValueRefusal(null, McoreFactory.eINSTANCE.createTypeValue()));
    }

    @Test
    public void testColorReferentAndEnumStructureAreChecked()
    {
        McoreFactory f = McoreFactory.eINSTANCE;
        ColorValue declared = f.createColorValue();
        ColorValue stored = f.createColorValue();
        // A color value with no color is EDT's automatic color ("auto" in Template.dcs).
        assertNull(DcsSettingsWriter.typedValueRefusal(declared, stored));
        ColorRef reference = f.createColorRef();
        stored.setValue(reference);
        assertNotNull(DcsSettingsWriter.typedValueRefusal(declared, stored));
        // #682 round 5: a referent is no longer enough - the reference must name a target the
        // writer rebuilds; an unnamed detached color names none.
        reference.setColor(f.createColorDef());
        assertNotNull(DcsSettingsWriter.typedValueRefusal(declared, stored));
        stored.setValue(f.createAutoColor());
        // An AutoColor is a ColorDef that EDT saves as black; 'auto' rebuilds the colorless value.
        assertNotNull(DcsSettingsWriter.typedValueRefusal(declared, stored));
        stored.setValue(f.createColorDef());
        assertNull(DcsSettingsWriter.typedValueRefusal(declared, stored));
        EnumValue enumeration = f.createEnumValue();
        enumeration.setValue(DataCompositionTotalPlacement.AUTO);
        assertNotNull(DcsSettingsWriter.typedValueRefusal(enumeration, f.createEnumValue()));
        assertNull(DcsSettingsWriter.typedValueRefusal(enumeration, EcoreUtil.copy(enumeration)));
    }

    @Test
    public void testEveryOutputHolderSelectsItsOwnCatalogue()
    {
        DcsFactory f = DcsFactory.eINSTANCE;
        List<ParameterValues> holders = List.of(f.createDataCompositionOutputParameterValues(),
            f.createDataCompositionGroupOutputParameterValues(), f.createDataCompositionTableOutputParameterValues(),
            f.createDataCompositionTableGroupOutputParameterValues(), f.createDataCompositionChartOutputParameterValues(),
            f.createDataCompositionChartGroupOutputParameterValues());
        OutputParameterCatalogue[] kinds = OutputParameterCatalogue.values();
        for (int i = 0; i < holders.size(); i++)
        {
            assertEquals(kinds[i], OutputParameterCatalogue.forHolder(holders.get(i)));
        }
        assertNull(OutputParameterCatalogue.forHolder(f.createDataCompositionDataParameterValues()));
    }

    private static List<Enum<?>> allCatalogues()
    {
        java.util.ArrayList<Enum<?>> result = new java.util.ArrayList<>();
        result.addAll(List.of(OutputParameterCatalogue.values()));
        result.addAll(List.of(AppearanceCatalogue.values()));
        return result;
    }

    private static JsonElement raw(Value declared)
    {
        if (declared instanceof LocalString) return json("{\"en\":\"Catalogue text\"}");
        if (declared instanceof EnumValue)
        {
            var literal = ((EnumValue)declared).getValue();
            assertNotNull("The real enum default must name an enumerator", literal);
            return new JsonPrimitive(literal.getLiteral());
        }
        if (declared instanceof ColorValue) return json("{\"color\":{\"red\":12,\"green\":34,\"blue\":56}}");
        if (declared instanceof FontValue) return json("{\"font\":{\"faceName\":\"Arial\",\"height\":12,\"bold\":true}}");
        if (declared instanceof StringValue) return json("{\"kind\":\"string\",\"value\":\"Catalogue text\"}");
        if (declared == null || declared instanceof NumberValue) return json("{\"kind\":\"number\",\"value\":42}");
        if (declared instanceof BooleanValue) return json("{\"kind\":\"boolean\",\"value\":true}");
        if (declared instanceof DateValue) return json("{\"kind\":\"date\",\"value\":\"2026-01-01T00:00:00\"}");
        if (declared instanceof NullValue) return json("{\"kind\":\"null\"}");
        if (declared instanceof DataCompositionField) return json("{\"kind\":\"field\",\"value\":\"Amount\"}");
        if (declared instanceof DataCompositionParameter) return json("{\"kind\":\"parameter\",\"value\":\"P\"}");
        return null; // The caller collects every unknown class, then fails with the complete set.
    }

    private static List<JsonElement> untypedBodies()
    {
        McoreFactory f = McoreFactory.eINSTANCE;
        return List.of(raw(f.createStringValue()), raw(f.createNumberValue()), raw(f.createBooleanValue()),
            raw(f.createDateValue()), raw(f.createNullValue()),
            raw(com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField()),
            raw(com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionParameter()),
            json("{\"kind\":\"auto\"}"));
    }

    private static JsonElement json(String text)
    {
        return JsonParser.parseString(text);
    }
}

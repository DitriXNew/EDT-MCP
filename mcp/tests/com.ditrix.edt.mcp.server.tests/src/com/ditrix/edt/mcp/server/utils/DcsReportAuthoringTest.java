/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardPeriodValue;
import com._1c.g5.v8.dt.mcore.StandardPeriodVariant;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** #665: platform types, native values, nested appearance and ordered insertion. */
public class DcsReportAuthoringTest
{
    private static final DcsPresentationParser.LanguageContext LANGUAGES =
        new DcsPresentationParser.LanguageContext(List.of("en", "ru"), "en");

    @Test public void standardPeriodTypeUsesThePlatformTypeInBothLanguages()
    {
        for (String kind : List.of("StandardPeriod", "СтандартныйПериод"))
        {
            var provider = mock(IEObjectProvider.class);
            var type = McoreFactory.eINSTANCE.createType();
            type.setName("StandardPeriod");
            doReturn(type).when(provider).createProxy(kind);
            var td = McoreFactory.eINSTANCE.createTypeDescription();
            assertNull(MetadataTypeBuilder.addType(td, json("{\"kind\":\"" + kind + "\"}"), kind,
                provider, null, false, MetadataTypeBuilder.TypeTarget.DCS_PARAMETER));
            assertSame(type, td.getTypes().get(0));
            assertNotNull("StandardPeriod must remain unavailable on ordinary metadata members",
                MetadataTypeBuilder.addType(McoreFactory.eINSTANCE.createTypeDescription(), json("{\"kind\":\"" + kind + "\"}"),
                    kind, provider, null, false, MetadataTypeBuilder.TypeTarget.METADATA));
        }
    }

    @Test public void standardPeriodSchemaDefaultsAndVariantValuesRoundTrip() throws Exception
    {
        for (String kind : List.of("StandardPeriod", "СтандартныйПериод"))
        {
            DataCompositionSchema schema = DcsStoredXmlTestSupport.schema();
            String value = "{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"ThisYear\"}}";
            var applied = DcsWriter.apply(schema, json("{\"parameters\":[{\"name\":\"Period\",\"valueType\":{\"types\":[{\"kind\":\""
                + kind + "\"}]},\"values\":[" + value + "]}]}"), spec -> {
                    var td = McoreFactory.eINSTANCE.createTypeDescription();
                    var type = McoreFactory.eINSTANCE.createType();
                    type.setName("StandardPeriod");
                    td.getTypes().add(type);
                    return DcsWriter.TypeResolution.of(td);
                });
            assertFalse(applied.error, applied.hasError());
            var planned = DcsSettingsWriter.planSchema(schema, "upsert", "variant", address(""),
                json("{\"name\":\"Main\",\"presentation\":\"Main\",\"settings\":{\"dataParameters\":{\"items\":[{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Period\"},\"value\":"
                    + value + "}]}}}"), LANGUAGES);
            assertTrue(planned.error(), planned.isSuccess());
            planned.plan().commit(schema);
            StandardPeriodValue stored = (StandardPeriodValue)schema.getParameters().get(0).getValues().get(0);
            assertEquals(StandardPeriodVariant.THIS_YEAR, stored.getValue().getVariant());
            assertNull(DcsWriter.declaredValueRefusal(schema.getParameters().get(0).getValueType(), stored, LANGUAGES));
            var rebuilt = DcsSettingsWriter.buildValue(DcsSettingsWriter.valueSpec(stored), "value");
            assertNull(rebuilt.error);
            assertTrue(EcoreUtil.equals(stored, rebuilt.value));
            String disk = DcsStoredXmlTestSupport.serialize(schema);
            assertTrue(disk, disk.contains("StandardPeriod") && disk.contains("ThisYear"));
            // The native serializer resolves parameter types through a project; value-only XML needs none.
            var nativeRead = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(
                DcsStoredXmlTestSupport.operand(false, stored)));
            assertTrue(EcoreUtil.equals(stored, DcsStoredXmlTestSupport.operands(nativeRead, false).get(0)));
            String outline = DcsReadProjection.renderSettingsOutline("Report.Sales#/defaultSettings", nativeRead.getDefaultSettings(), "en");
            var match = Pattern.compile("```json\\n(\\{[^\\n]+\\})\\n```").matcher(outline);
            assertTrue(outline, match.find());
            var fromGet = DcsSettingsWriter.buildValue(json(match.group(1)), "value");
            assertNull(fromGet.error);
            assertTrue(EcoreUtil.equals(stored, fromGet.value));
        }
    }

    @Test public void customStandardPeriodDatesAreRestated()
    {
        var built = DcsSettingsWriter.buildValue(json("{\"kind\":\"standardPeriod\",\"value\":{\"startDate\":\"2026-01-01T00:00:00\",\"endDate\":\"2026-12-31T23:59:59\"}}"), "value");
        assertNull(built.error);
        assertTrue(EcoreUtil.equals(built.value, DcsSettingsWriter.buildValue(DcsSettingsWriter.valueSpec(built.value), "value").value));
    }

    @Test public void bilingualBorderSidesUseNestedPlatformParametersAndGetBodyRoundTrips() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(DcsSettingsWriter.AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en");
        for (String key : List.of("BorderStyle.Left", "СтильГраницы.Слева", "BorderStyle.Right", "СтильГраницы.Справа"))
        {
            var built = DcsSettingsWriter.buildAppearanceForTest(json("{\"" + key
                + "\":{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}}}"), null, LANGUAGES, catalogue);
            assertNull(built.error);
            var parent = built.value.getItems().get(0);
            assertEquals("BorderStyle", parent.getParameter().getValue());
            assertEquals(1, parent.getNestedParameterValues().size());
            var child = parent.getNestedParameterValues().get(0);
            assertEquals(key.endsWith("Right") || key.endsWith("Справа") ? "BorderStyle.Right" : "BorderStyle.Left", child.getParameter().getValue());
            var schema = DcsStoredXmlTestSupport.schema();
            var holder = DcsStoredXmlTestSupport.SETTINGS.createDataCompositionConditionalAppearance();
            var rule = DcsStoredXmlTestSupport.SETTINGS.createDataCompositionConditionalAppearanceItem();
            rule.setAppearance(built.value);
            holder.getItems().add(rule);
            schema.getDefaultSettings().setConditionalAppearance(holder);
            String xml = DcsStoredXmlTestSupport.serialize(schema);
            assertTrue(xml, xml.contains("width=\"2\"") && xml.contains("Solid"));
            var read = DcsStoredXmlTestSupport.deserialize(xml);
            DataCompositionAppearance stored = read.getDefaultSettings().getConditionalAppearance().getItems().get(0).getAppearance();
            assertEquals(1, stored.getItems().get(0).getNestedParameterValues().size());
            String text = DcsReadProjection.renderSettingsOutline("Report.Sales#/defaultSettings", read.getDefaultSettings(), "en");
            var matcher = Pattern.compile("```json\\n(\\{[^\\n]+\\})\\n```").matcher(text);
            JsonObject body = null;
            while (matcher.find())
            {
                JsonObject candidate = json(matcher.group(1));
                if (candidate.has("items")) { body = candidate; break; }
            }
            assertNotNull(text, body);
            var rewritten = DcsSettingsWriter.buildAppearanceForTest(body, null, LANGUAGES, catalogue);
            assertNull(rewritten.error);
            assertTrue(EcoreUtil.equals(stored, rewritten.value));
            assertNull(DcsStoredXmlTestSupport.guard(read));
        }
    }

    @Test public void settingsParameterScaffoldingAndNestedValuesSurviveReadModifyWrite() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(DcsSettingsWriter.AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en");
        var built = DcsSettingsWriter.buildAppearanceForTest(json("""
            {"items":[{"parameter":{"kind":"parameter","value":"СтильГраницы"},"use":false,
            "viewMode":"Normal","userSettingID":"keep-parent","values":[{"kind":"line","value":{"width":2,"style":"Solid"}}],
            "nestedParameterValues":[{"parameter":{"kind":"parameter","value":"СтильГраницы.Слева"},"use":true,
            "viewMode":"Normal","userSettingID":"keep-child","values":[{"kind":"line","value":{"width":3,"style":"ThinDashed","gap":true}}]}]}]}
            """), null, LANGUAGES, catalogue);
        assertNull(built.error);
        var rewritten = DcsSettingsWriter.buildAppearanceForTest(DcsSettingsWriter.appearanceBody(built.value), null, LANGUAGES, catalogue);
        assertNull(rewritten.error);
        assertTrue(EcoreUtil.equals(built.value, rewritten.value));
    }

    @Test public void explicitItemsRefuseAFlattenedNestedLeaf() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(DcsSettingsWriter.AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en");
        for (String key : List.of("BorderStyle.Left", "СтильГраницы.Слева"))
        {
            var result = DcsSettingsWriter.buildAppearanceForTest(json("{\"items\":[{\"parameter\":{\"kind\":\"parameter\",\"value\":\""
                + key + "\"},\"values\":[{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}}]}]}"), null, LANGUAGES, catalogue);
            assertNotNull(key, result.error);
            assertTrue(result.error, result.error.contains(key) && result.error.contains("nestedParameterValues"));
        }
    }

    @Test public void aFontNextToANestedBorderKeepsTheWritableGetBody() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(DcsSettingsWriter.AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en");
        var built = DcsSettingsWriter.buildAppearanceForTest(json("""
            {"Font":{"font":{"faceName":"Arial","height":10,"bold":true}},
             "BorderStyle.Left":{"kind":"line","value":{"width":2,"style":"Solid"}}}
            """), null, LANGUAGES, catalogue);
        assertNull(built.error);
        JsonObject body = DcsSettingsWriter.appearanceBody(built.value);
        assertNotNull(body);
        assertTrue(body.toString(), body.toString().contains("\"faceName\":\"Arial\""));
        var rewritten = DcsSettingsWriter.buildAppearanceForTest(body, null, LANGUAGES, catalogue);
        assertNull(rewritten.error);
        assertTrue(EcoreUtil.equals(built.value, rewritten.value));
    }

    @Test public void invalidLinesAndUnknownNestedKeysGiveActionableRefusals() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(DcsSettingsWriter.AppearanceCatalogue.SCHEMA, Version.V8_3_27, "en");
        for (String bad : List.of("{\"width\":2,\"style\":\"NotAStyle665\"}", "{\"width\":1.5,\"style\":\"Solid\"}"))
        {
            var result = DcsSettingsWriter.buildValue(json("{\"kind\":\"line\",\"value\":" + bad + "}"), "value");
            assertNotNull(result.error);
            assertTrue(result.error, result.error.contains("Use") || result.error.contains("Pass"));
        }
        var result = DcsSettingsWriter.buildAppearanceForTest(json("{\"СтильГраницы.NoSide665\":{\"kind\":\"line\",\"value\":{\"width\":2,\"style\":\"Solid\"}}}"), null, LANGUAGES, catalogue);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("NoSide665") && result.error.contains("correct"));
    }

    @Test public void insertsAtFirstMiddleAndEndOfEveryOrderedSettingsCollection()
    {
        for (String type : List.of("filter", "selection", "order", "conditionalAppearance", "grouping"))
        {
            String item = switch (type) {
                case "filter" -> "{\"left\":{\"kind\":\"field\",\"value\":\"Amount\"},\"userSettingID\":\"keep\"}";
                case "selection", "order" -> "{\"field\":{\"kind\":\"field\",\"value\":\"Amount\"}}";
                case "grouping" -> "{\"kind\":\"grouping\",\"name\":\"Existing\",\"userSettingID\":\"keep\"}";
                default -> "{\"userSettingID\":\"keep\"}";
            };
            var initial = DcsSettingsWriter.planSettings(null, List.of(), "upsert", type, json("{\"items\":[" + item + "," + item.replace("Existing", "Existing2") + "]}"), LANGUAGES);
            assertTrue(type + ": " + initial.error(), initial.isSuccess());
            for (int position : List.of(1, 2, 3))
            {
                String inserted = item.replace("Amount", "Inserted").replace("Existing", "Inserted").replace("keep", "inserted");
                var result = DcsSettingsWriter.planSettings(initial.settings(), List.of(), "upsert", type,
                    json("{\"insertPosition\":" + position + ",\"items\":[" + inserted + "]}"), LANGUAGES);
                assertTrue(type + ": " + result.error(), result.isSuccess());
                String path = type.equals("grouping") ? "items" : type;
                var before = collection(initial.settings(), path);
                var after = collection(result.settings(), path);
                assertEquals(3, after.size());
                int old = 0;
                for (int i = 0; i < after.size(); i++)
                    if (i != position - 1) assertTrue(type, EcoreUtil.equals((org.eclipse.emf.ecore.EObject)before.get(old++), (org.eclipse.emf.ecore.EObject)after.get(i)));
                assertEquals(2, before.size());
            }
        }
    }

    @Test public void insertionRejectsBadPositionsAndExistingItemTargetsWithoutMutation()
    {
        var initial = DcsSettingsWriter.planSettings(null, List.of(), "upsert", "selection", json("{\"items\":[{\"kind\":\"auto\"}]}"), LANGUAGES).settings();
        for (String position : List.of("0", "3", "1.5", "null", "\"1\""))
        {
            var result = DcsSettingsWriter.planSettings(initial, List.of(), "upsert", "selection", json("{\"insertPosition\":" + position + ",\"items\":[{\"kind\":\"auto\"}]}"), LANGUAGES);
            assertFalse(position, result.isSuccess());
            assertTrue(result.error(), result.error().contains("insertPosition"));
            assertEquals(1, initial.getSelection().getItems().size());
        }
        var exact = DcsSettingsWriter.planSettings(initial, List.of("selection", "items", "0"), "upsert", "selection", json("{\"insertPosition\":1}"), LANGUAGES);
        assertFalse(exact.isSuccess());
    }

    @Test public void insertionIntoNestedItemsUsesTheSamePlannerAndPreservesSiblings()
    {
        var initial = DcsSettingsWriter.planSettings(null, List.of(), "upsert", "grouping",
            json("{\"items\":[{\"kind\":\"grouping\",\"name\":\"Parent\",\"items\":[{\"kind\":\"grouping\",\"name\":\"Old\",\"userSettingID\":\"keep\"}]}]}"), LANGUAGES);
        assertTrue(initial.error(), initial.isSuccess());
        var result = DcsSettingsWriter.planSettings(initial.settings(), List.of("items", "0", "items"),
            "upsert", "grouping", json("{\"insertPosition\":1,\"items\":[{\"kind\":\"grouping\",\"name\":\"New\"}]}"), LANGUAGES);
        assertTrue(result.error(), result.isSuccess());
        var parent = (com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup)result.settings().getItems().get(0);
        var first = (com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup)parent.getItems().get(0);
        var second = (com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup)parent.getItems().get(1);
        assertEquals("New", first.getName());
        assertEquals("Old", second.getName());
        assertEquals("keep", second.getUserSettingID());
    }

    private static java.util.List<?> collection(org.eclipse.emf.ecore.EObject object, String feature)
    {
        Object value = object.eGet(object.eClass().getEStructuralFeature(feature));
        if (value instanceof java.util.List<?>) return (java.util.List<?>)value;
        var holder = (org.eclipse.emf.ecore.EObject)value;
        return (java.util.List<?>)holder.eGet(holder.eClass().getEStructuralFeature("items"));
    }

    private static DcsAddress address(String suffix) { return DcsAddress.parse("Report.Sales" + suffix).address(); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
}

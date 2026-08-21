/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Headless model tests for the single report/dynamic-list settings implementation. */
public class DcsSettingsWriterTest
{
    private static final DcsPresentationParser.LanguageContext LANGUAGES =
        new DcsPresentationParser.LanguageContext(Arrays.asList("en", "uk")); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void testReportVariantAndDynamicListUseEquivalentSharedSettingsTree()
    {
        JsonObject settingsBody = settingsBody();
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        JsonObject variantBody = json("{\"name\":\"Operational\",\"presentation\":{\"EN\":\"Operational\"}}"); //$NON-NLS-1$
        variantBody.add("settings", settingsBody.deepCopy()); //$NON-NLS-1$

        DcsSettingsWriter.SchemaResult report = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "variant", address("Report.Sales"), variantBody, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.error(), report.isSuccess());
        report.plan().commit(schema);
        SettingsVariant variant = schema.getSettingsVariants().get(0);

        JsonObject dynamicBody = new JsonObject();
        dynamicBody.add("listSettings", settingsBody.deepCopy()); //$NON-NLS-1$
        DcsDynamicListWriter.Result dynamic = DcsDynamicListWriter.plan(null, "upsert", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            dynamicBody, null, LANGUAGES);
        assertTrue(dynamic.error(), dynamic.isSuccess());

        assertEquals("both owner adapters must produce the same typed settings tree", //$NON-NLS-1$
            DcsHash.compute(variant.getSettings()), DcsHash.compute(dynamic.plan().settings()));
        assertEquals("en", variant.getPresentation().getLocalValue().getContent().keySet() //$NON-NLS-1$
            .iterator().next());
    }

    @Test
    public void testRecursiveGroupsFilterGroupsFieldValueAndScaffolding()
    {
        JsonObject bilingualBody = settingsBody();
        String ukrainianSelection = MetadataLanguageUtils.cp(0x0412, 0x0438, 0x0431, 0x0456,
            0x0440); // Vybir
        bilingualBody.getAsJsonObject("selection") //$NON-NLS-1$
            .getAsJsonObject("userSettingPresentation") //$NON-NLS-1$
            .addProperty("UK", ukrainianSelection); //$NON-NLS-1$
        DataCompositionSettings settings = plan(bilingualBody);
        DataCompositionGroup outer = (DataCompositionGroup)settings.getItems().get(0);
        DataCompositionGroup inner = (DataCompositionGroup)outer.getItems().get(0);
        assertEquals("Outer", outer.getName()); //$NON-NLS-1$
        assertEquals("Inner", inner.getName()); //$NON-NLS-1$
        assertEquals("Customer", ((DataCompositionGroupField)outer.getGroupFields().getItems() //$NON-NLS-1$
            .get(0)).getField().getValue());

        DataCompositionSelectedField selected =
            (DataCompositionSelectedField)settings.getSelection().getItems().get(0);
        DataCompositionField field = selected.getField();
        assertEquals("Customer", field.getValue()); //$NON-NLS-1$

        DataCompositionFilterItemGroup and =
            (DataCompositionFilterItemGroup)settings.getFilter().getItems().get(0);
        DataCompositionFilterItemGroup or = (DataCompositionFilterItemGroup)and.getItems().get(1);
        assertEquals(2, and.getItems().size());
        assertEquals(1, or.getItems().size());
        assertEquals("selection", settings.getSelection().getUserSettingID()); //$NON-NLS-1$
        assertEquals("filter", settings.getFilter().getUserSettingID()); //$NON-NLS-1$
        assertEquals("order", settings.getOrder().getUserSettingID()); //$NON-NLS-1$
        assertEquals("appearance", settings.getConditionalAppearance().getUserSettingID()); //$NON-NLS-1$
        assertTrue(settings.getConditionalAppearance().getItems().isEmpty());
        assertEquals("Selection", settings.getSelection().getUserSettingPresentation() //$NON-NLS-1$
            .getLocalValue().getContent().get("en")); //$NON-NLS-1$
        assertEquals(ukrainianSelection, settings.getSelection().getUserSettingPresentation()
            .getLocalValue().getContent().get("uk")); //$NON-NLS-1$
        assertEquals(1, settings.getDataParameters().getItems().size());
        assertEquals(1, settings.getOutputParameters().getItems().size());
    }

    @Test
    public void testExactNestedFilterIndexUpdatesOnlyThatItem()
    {
        DataCompositionSchema schema = schemaWithVariant();
        DataCompositionSettings before = schema.getSettingsVariants().get(0).getSettings();
        DataCompositionFilterItemGroup andBefore =
            (DataCompositionFilterItemGroup)before.getFilter().getItems().get(0);
        DataCompositionFilterItem firstBefore = (DataCompositionFilterItem)andBefore.getItems().get(0);
        BigDecimal untouched = ((NumberValue)firstBefore.getRight().get(0)).getValue();

        JsonObject update = json("{\"kind\":\"item\",\"right\":[{\"kind\":\"number\",\"value\":99}]}"); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult result = DcsSettingsWriter.planSchema(schema, "update", //$NON-NLS-1$
            "filter", address("Report.Sales#/variants/Operational/settings/filter/items/0/items/1/items/0"), //$NON-NLS-1$ //$NON-NLS-2$
            update, LANGUAGES);
        assertTrue(result.error(), result.isSuccess());
        result.plan().commit(schema);

        DataCompositionFilterItemGroup andAfter = (DataCompositionFilterItemGroup)schema
            .getSettingsVariants().get(0).getSettings().getFilter().getItems().get(0);
        DataCompositionFilterItem firstAfter = (DataCompositionFilterItem)andAfter.getItems().get(0);
        DataCompositionFilterItemGroup orAfter = (DataCompositionFilterItemGroup)andAfter.getItems().get(1);
        DataCompositionFilterItem changed = (DataCompositionFilterItem)orAfter.getItems().get(0);
        assertEquals(untouched, ((NumberValue)firstAfter.getRight().get(0)).getValue());
        assertEquals(new BigDecimal("99"), ((NumberValue)changed.getRight().get(0)).getValue()); //$NON-NLS-1$
        assertEquals("Amount", ((DataCompositionField)changed.getLeft()).getValue()); //$NON-NLS-1$
    }

    @Test
    public void testBadEnumNamesValueAndListsAllowedPlatformLiterals()
    {
        JsonObject body = json("{\"filter\":{\"items\":[{\"left\":{\"kind\":\"field\",\"value\":\"Amount\"}," //$NON-NLS-1$
            + "\"comparisonType\":\"Sideways\"}]}}"); //$NON-NLS-1$
        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(null,
            java.util.Collections.emptyList(), "upsert", "userSettings", body, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("Sideways")); //$NON-NLS-1$
        assertTrue(result.error().contains("Equal")); //$NON-NLS-1$
        assertTrue(result.error().contains("platform literals")); //$NON-NLS-1$
    }

    @Test
    public void testConditionalAppearanceAcceptsScaffoldingButRefusesRules()
    {
        JsonObject body = json("{\"conditionalAppearance\":{\"items\":[{}]}}"); //$NON-NLS-1$
        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(null,
            java.util.Collections.emptyList(), "upsert", "userSettings", body, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("conditionalAppearance.items")); //$NON-NLS-1$
        assertTrue(result.error().contains("items:[]")); //$NON-NLS-1$
    }

    @Test
    public void testDynamicListUpdateCannotCreateItemsAndQueryTextCanBeCleared()
    {
        DynamicListExtInfo current = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DcsDynamicListWriter.Result missing = DcsDynamicListWriter.plan(current, "update", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"fields\":[{\"dataPath\":\"NewField\"}]}"), null, LANGUAGES); //$NON-NLS-1$
        assertFalse(missing.isSuccess());
        assertTrue(missing.error().contains("NewField")); //$NON-NLS-1$
        assertTrue(missing.error().contains("action='upsert'")); //$NON-NLS-1$

        DcsDynamicListWriter.Result clear = DcsDynamicListWriter.plan(current, "update", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"queryText\":\"\"}"), null, LANGUAGES); //$NON-NLS-1$
        assertTrue(clear.error(), clear.isSuccess());
        assertEquals("", clear.plan().queryText()); //$NON-NLS-1$
    }

    @Test
    public void testValidatedCommitPreservesExistingDynamicListSettingsIdentity()
    {
        DynamicListExtInfo extInfo = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DataCompositionSettings existing = plan(settingsBody());
        extInfo.setListSettings(existing);
        DcsSettingsWriter.SettingsResult changed = DcsSettingsWriter.planSettings(existing,
            Arrays.asList("selection"), "upsert", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            json("{\"userSettingID\":\"changed\"}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(changed.error(), changed.isSuccess());

        DcsSettingsWriter.commitSettings(existing, changed.settings());

        assertSame(existing, extInfo.getListSettings());
        assertEquals("changed", existing.getSelection().getUserSettingID()); //$NON-NLS-1$
        assertEquals(DcsHash.compute(changed.settings()), DcsHash.compute(existing));
    }

    private static DataCompositionSchema schemaWithVariant()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        JsonObject variant = json("{\"name\":\"Operational\"}"); //$NON-NLS-1$
        variant.add("settings", settingsBody()); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult result = DcsSettingsWriter.planSchema(schema, "upsert", //$NON-NLS-1$
            "variant", address("Report.Sales"), variant, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error(), result.isSuccess());
        result.plan().commit(schema);
        return schema;
    }

    private static DataCompositionSettings plan(JsonObject body)
    {
        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(null,
            java.util.Collections.emptyList(), "upsert", "userSettings", body, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error(), result.isSuccess());
        assertNotNull(result.settings());
        return result.settings();
    }

    private static JsonObject settingsBody()
    {
        return json("{" //$NON-NLS-1$
            + "\"itemsViewMode\":\"Normal\",\"itemsUserSettingID\":\"structure\"," //$NON-NLS-1$
            + "\"itemsUserSettingPresentation\":{\"EN\":\"Structure\"}," //$NON-NLS-1$
            + "\"items\":[{\"name\":\"Outer\",\"use\":true," //$NON-NLS-1$
            + "\"viewMode\":\"Normal\",\"userSettingID\":\"outer\"," //$NON-NLS-1$
            + "\"userSettingPresentation\":{\"EN\":\"Outer\"}," //$NON-NLS-1$
            + "\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}," //$NON-NLS-1$
            + "\"use\":true,\"groupType\":\"Items\",\"periodAdditionType\":\"None\"}]}," //$NON-NLS-1$
            + "\"items\":[{\"name\":\"Inner\",\"groupFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"field\":{\"kind\":\"field\",\"value\":\"Period\"},\"groupType\":\"Items\"}]}}]}]," //$NON-NLS-1$
            + "\"selection\":{\"viewMode\":\"Normal\",\"userSettingID\":\"selection\"," //$NON-NLS-1$
            + "\"userSettingPresentation\":{\"EN\":\"Selection\"},\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"field\",\"field\":{\"kind\":\"field\",\"value\":\"Customer\"},\"use\":true}," //$NON-NLS-1$
            + "{\"kind\":\"group\",\"field\":{\"kind\":\"field\",\"value\":\"Amounts\"}," //$NON-NLS-1$
            + "\"placement\":\"Horizontally\",\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Amount\"}}]}," //$NON-NLS-1$
            + "{\"kind\":\"auto\",\"use\":true}]}," //$NON-NLS-1$
            + "\"filter\":{\"viewMode\":\"Normal\",\"userSettingID\":\"filter\",\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"group\",\"groupType\":\"AndGroup\",\"items\":[" //$NON-NLS-1$
            + "{\"left\":{\"kind\":\"field\",\"value\":\"Quantity\"},\"comparisonType\":\"Greater\"," //$NON-NLS-1$
            + "\"right\":[{\"kind\":\"number\",\"value\":10}],\"use\":true}," //$NON-NLS-1$
            + "{\"kind\":\"group\",\"groupType\":\"OrGroup\",\"items\":[" //$NON-NLS-1$
            + "{\"left\":{\"kind\":\"field\",\"value\":\"Amount\"},\"comparisonType\":\"Equal\"," //$NON-NLS-1$
            + "\"right\":[{\"kind\":\"number\",\"value\":20}],\"use\":true}]}]}]}," //$NON-NLS-1$
            + "\"order\":{\"viewMode\":\"Normal\",\"userSettingID\":\"order\",\"items\":[" //$NON-NLS-1$
            + "{\"field\":{\"kind\":\"field\",\"value\":\"Customer\"},\"orderType\":\"Asc\",\"use\":true}," //$NON-NLS-1$
            + "{\"kind\":\"auto\",\"use\":true}]}," //$NON-NLS-1$
            + "\"conditionalAppearance\":{\"viewMode\":\"Normal\"," //$NON-NLS-1$
            + "\"userSettingID\":\"appearance\",\"items\":[]}," //$NON-NLS-1$
            + "\"dataParameters\":{\"items\":[{\"parameter\":{\"kind\":\"parameter\",\"value\":\"StartDate\"}," //$NON-NLS-1$
            + "\"value\":{\"kind\":\"string\",\"value\":\"2026-01-01\"},\"use\":true," //$NON-NLS-1$
            + "\"viewMode\":\"Normal\",\"userSettingID\":\"start\"}]}," //$NON-NLS-1$
            + "\"outputParameters\":{\"items\":[{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Title\"}," //$NON-NLS-1$
            + "\"value\":{\"kind\":\"string\",\"value\":\"Sales\"},\"use\":true}]}}" //$NON-NLS-1$
        );
    }

    private static JsonObject json(String source)
    {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    private static DcsAddress address(String source)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(source);
        assertTrue(parsed.failure() == null ? source : parsed.failure().message(), parsed.isSuccess());
        return parsed.address();
    }
}

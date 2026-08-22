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
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItemGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroupField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSelectedField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTable;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.UserField;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.platform.version.Version;
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
            dynamicBody, null, LANGUAGES, Version.LATEST);
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
    public void testConditionalAppearanceRuleIsAuthoredWithItsFieldsAndFilter()
    {
        JsonObject body = json("{\"conditionalAppearance\":{\"items\":[{\"use\":true," //$NON-NLS-1$
            + "\"selection\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Amount\"}}]}," //$NON-NLS-1$
            + "\"filter\":{\"items\":[{\"left\":{\"kind\":\"field\",\"value\":\"Amount\"}," //$NON-NLS-1$
            + "\"comparisonType\":\"Less\",\"right\":[{\"kind\":\"number\",\"value\":0}]}]}}]}}"); //$NON-NLS-1$
        DataCompositionSettings settings = plan(body);

        assertEquals(1, settings.getConditionalAppearance().getItems().size());
        DataCompositionConditionalAppearanceItem rule =
            settings.getConditionalAppearance().getItems().get(0);
        assertTrue(rule.isUse());
        assertEquals("Amount", ((DataCompositionAppearanceField)rule.getSelection().getItems() //$NON-NLS-1$
            .get(0)).getField().getValue());
        DataCompositionFilterItem condition = (DataCompositionFilterItem)rule.getFilter().getItems().get(0);
        assertEquals("Amount", ((DataCompositionField)condition.getLeft()).getValue()); //$NON-NLS-1$
        assertEquals(new BigDecimal("0"), ((NumberValue)condition.getRight().get(0)).getValue()); //$NON-NLS-1$
    }

    @Test
    public void testAppearanceBlockNeedsTheEdtRuntimeAndSaysSoInsteadOfAcceptingAnything()
    {
        // The accepted appearance keys come from the platform's own DcsAppearanceParameters, which
        // resolves mcore type proxies and therefore only loads inside a running EDT - not in this
        // headless fixture. What IS provable here is the safe degrade: the writer refuses the block
        // rather than waving unknown keys through. Whether a VALID key is accepted, and whether an
        // invalid one is named in the refusal, is provable only against a live workbench.
        JsonObject body = json("{\"conditionalAppearance\":{\"items\":[" //$NON-NLS-1$
            + "{\"appearance\":{\"NoSuchAppearanceKey\":true}}]}}"); //$NON-NLS-1$
        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(null,
            java.util.Collections.emptyList(), "upsert", "userSettings", body, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an appearance block must never be accepted unvalidated", result.isSuccess()); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("appearance")); //$NON-NLS-1$
    }

    @Test
    public void testDynamicListUpdateCannotCreateItemsAndQueryTextCanBeCleared()
    {
        DynamicListExtInfo current = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DcsDynamicListWriter.Result missing = DcsDynamicListWriter.plan(current, "update", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"fields\":[{\"dataPath\":\"NewField\"}]}"), null, LANGUAGES, Version.LATEST); //$NON-NLS-1$
        assertFalse(missing.isSuccess());
        assertTrue(missing.error().contains("NewField")); //$NON-NLS-1$
        assertTrue(missing.error().contains("action='upsert'")); //$NON-NLS-1$

        DcsDynamicListWriter.Result clear = DcsDynamicListWriter.plan(current, "update", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"queryText\":\"\"}"), null, LANGUAGES, Version.LATEST); //$NON-NLS-1$
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

    @Test
    public void testDynamicListReplaceReachesTheSettingsLayerButNotTheListsOwnTypes()
    {
        // The tool guide advertises replace/remove for dynamic lists, and the SHARED settings
        // writer implements them - but the dynamic-list planner used to refuse every action except
        // upsert/update, so the guide promised what the tool rejected and no test noticed. Settings
        // types below '#/listSettings' now go through; the list's OWN types stay on upsert/update,
        // because they have no authoritative-replacement semantics and accepting 'replace' there
        // would just be an update wearing the wrong label.
        DynamicListExtInfo current = FormFactory.eINSTANCE.createDynamicListExtInfo();
        current.setListSettings(plan(settingsBody()));

        DcsDynamicListWriter.Result settings = DcsDynamicListWriter.plan(current, "replace", //$NON-NLS-1$
            "selection", //$NON-NLS-1$
            address("Catalog.Products.Form.ListForm.Attribute.List#/listSettings/selection"), //$NON-NLS-1$
            json("{\"items\":[]}"), null, LANGUAGES, Version.LATEST); //$NON-NLS-1$
        assertTrue(settings.error(), settings.isSuccess());

        DcsDynamicListWriter.Result own = DcsDynamicListWriter.plan(current, "replace", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"queryText\":\"SELECT 1\"}"), null, LANGUAGES, Version.LATEST); //$NON-NLS-1$
        assertFalse(own.isSuccess());
        assertTrue(own.error(), own.error().contains("#/listSettings")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceOnAnIndexedSelectionItemResetsOmittedProperties()
    {
        // replace is documented as authoritative - omitted values reset, omitted collections clear.
        // The indexed path applied the body OVER the existing item instead of rebuilding it, so a
        // title the replace never mentioned survived it. That is an update, not a replacement.
        DataCompositionSettings settings = plan(json("{\"selection\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"field\",\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}," //$NON-NLS-1$
            + "\"title\":{\"EN\":\"Buyer\"},\"use\":true}]}}")); //$NON-NLS-1$
        DataCompositionSelectedField before =
            (DataCompositionSelectedField)settings.getSelection().getItems().get(0);
        assertTrue("the fixture must start with a title to lose", //$NON-NLS-1$
            before.getTitle() != null && before.getTitle().getLocalValue() != null
                && !before.getTitle().getLocalValue().getContent().isEmpty());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planDynamicList(settings,
            "replace", "selection", //$NON-NLS-1$ //$NON-NLS-2$
            address("Catalog.Products.Form.ListForm.Attribute.List" //$NON-NLS-1$
                + "#/listSettings/selection/items/0"), //$NON-NLS-1$
            json("{\"kind\":\"field\",\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}}"), //$NON-NLS-1$
            LANGUAGES, Version.LATEST);
        assertTrue(replaced.error(), replaced.isSuccess());

        DataCompositionSelectedField after =
            (DataCompositionSelectedField)replaced.settings().getSelection().getItems().get(0);
        assertTrue("a replace that omitted title must not keep the old one", //$NON-NLS-1$
            after.getTitle() == null || after.getTitle().getLocalValue() == null
                || after.getTitle().getLocalValue().getContent().isEmpty());
    }

    @Test
    public void testTypedReplaceAtTheBareRootKeepsSiblingSettings()
    {
        // The bare root plus a CONCRETE type is a documented convenience: the type's default path
        // is filled in for you. But the blank-settings decision was made while the path was still
        // empty, so action='replace' with type='selection' read as "replace the WHOLE settings" and
        // took filter, order, conditional appearance and data parameters with it. Only a type whose
        // default path is itself empty addresses the root.
        DataCompositionSettings current = plan(json("{" //$NON-NLS-1$
            + "\"selection\":{\"items\":[]}," //$NON-NLS-1$
            + "\"filter\":{\"items\":[],\"userSettingID\":\"keepme\"}," //$NON-NLS-1$
            + "\"order\":{\"items\":[]}}")); //$NON-NLS-1$
        assertNotNull("the fixture must carry a sibling to lose", current.getFilter()); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(current,
            java.util.Collections.emptyList(), "replace", "selection", //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        assertNotNull("a typed replace at the bare root must not discard sibling settings", //$NON-NLS-1$
            replaced.settings().getFilter());
        assertEquals("keepme", replaced.settings().getFilter().getUserSettingID()); //$NON-NLS-1$
        assertNotNull("order must survive a selection-only replace", //$NON-NLS-1$
            replaced.settings().getOrder());
    }

    @Test
    public void testReplaceOnAnIndexedUserFieldResetsOmittedProperties()
    {
        // Same defect as the indexed selection item, one collection over: the body was applied
        // OVER the existing user field, so a title the replace never mentioned survived it.
        DataCompositionSettings settings = plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"Margin\"," //$NON-NLS-1$
            + "\"title\":{\"EN\":\"Gross margin\"},\"use\":true}]}}")); //$NON-NLS-1$
        UserField before = settings.getUserFields().getItems().get(0);
        assertNotNull("the fixture must start with a title to lose", before.getTitle()); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("userFields", "items", "0"), "replace", "userField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"kind\":\"expression\",\"dataPath\":\"Margin\"}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());

        UserField after = replaced.settings().getUserFields().getItems().get(0);
        assertTrue("a replace that omitted title must not keep the old one", //$NON-NLS-1$
            after.getTitle() == null || after.getTitle().getLocalValue() == null
                || after.getTitle().getLocalValue().getContent().isEmpty());
    }

    @Test
    public void testReplaceAtACollectionAddressClearsItInsteadOfAppending()
    {
        // Resolving defaultPath before the blank-settings decision fixed sibling loss, but it also
        // meant a collection-addressed replace now starts from a COPY - and the structure applier
        // appended without clearing, so replacing the groupings added a second copy of each. The
        // address ends AT the collection, so replacing it must replace it.
        DataCompositionSettings current = plan(json("{\"items\":[" //$NON-NLS-1$
            + "{\"name\":\"Old\",\"use\":true}]}")); //$NON-NLS-1$
        assertEquals(1, current.getItems().size());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(current,
            java.util.Collections.emptyList(), "replace", "grouping", //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"items\":[{\"name\":\"New\",\"use\":true}]}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        assertEquals("a replace at the collection address must swap, not append", //$NON-NLS-1$
            1, replaced.settings().getItems().size());
    }

    @Test
    public void testReplaceOnAnIndexedFilterItemResetsOmittedProperties()
    {
        DataCompositionSettings settings = plan(json("{\"filter\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"item\",\"left\":{\"kind\":\"field\",\"value\":\"Amount\"}," //$NON-NLS-1$
            + "\"comparisonType\":\"Greater\"," //$NON-NLS-1$
            + "\"right\":[{\"kind\":\"number\",\"value\":10}],\"use\":true}]}}")); //$NON-NLS-1$
        DataCompositionFilterItem before =
            (DataCompositionFilterItem)settings.getFilter().getItems().get(0);
        assertFalse("the fixture must start with a right operand to lose", //$NON-NLS-1$
            before.getRight().isEmpty());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("filter", "items", "0"), "replace", "filter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"kind\":\"item\",\"left\":{\"kind\":\"field\",\"value\":\"Amount\"}}"), //$NON-NLS-1$
            LANGUAGES);
        assertTrue(replaced.error(), replaced.isSuccess());
        DataCompositionFilterItem after =
            (DataCompositionFilterItem)replaced.settings().getFilter().getItems().get(0);
        assertTrue("a replace that omitted the right operand must not keep the old one", //$NON-NLS-1$
            after.getRight().isEmpty());
    }

    @Test
    public void testReplaceOnAnIndexedUserFieldMustRestateItsDataPath()
    {
        // A rebuilt field starts empty, so an omitted dataPath would clear the identity rather
        // than keep it. Refused by name instead.
        DataCompositionSettings settings = plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"Margin\"}]}}")); //$NON-NLS-1$
        DcsSettingsWriter.SettingsResult refused = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("userFields", "items", "0"), "replace", "userField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"kind\":\"expression\"}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(refused.isSuccess());
        assertTrue(refused.error(), refused.error().contains("dataPath")); //$NON-NLS-1$
        assertTrue(refused.error(), refused.error().contains("action='update'")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceOnATableChildHolderResetsTheHoldersOwnScalars()
    {
        // A holder is not a collection - it carries viewMode, userSettingID and a presentation of
        // its own alongside its items. Addressing it and replacing it must reset those too, or
        // clearing the items leaves a half-replaced holder behind. The settings-level paths always
        // did this; the table CHILD path copied instead.
        DataCompositionSettings settings = plan(json("{\"items\":[{\"kind\":\"table\"," //$NON-NLS-1$
            + "\"name\":\"T\",\"selection\":{\"items\":[]," //$NON-NLS-1$
            + "\"viewMode\":\"Normal\",\"userSettingID\":\"keepnot\"}}]}")); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "selection"), "replace", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());

        DataCompositionTable table = (DataCompositionTable)replaced.settings().getItems().get(0);
        assertTrue("a replaced holder must not keep the userSettingID it was never given", //$NON-NLS-1$
            table.getSelection() == null || table.getSelection().getUserSettingID() == null
                || table.getSelection().getUserSettingID().isEmpty());
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

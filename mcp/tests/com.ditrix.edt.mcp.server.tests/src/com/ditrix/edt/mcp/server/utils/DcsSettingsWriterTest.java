/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
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
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionTableGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.dcs.model.settings.UserField;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.Structure;
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
    public void testSchemaBodyRefusesDuplicateVariantNaturalKeysBeforeApplyingEntries()
    {
        DataCompositionSchema schema = schemaWithVariant();
        String beforeHash = DcsHash.compute(schema);

        DcsSettingsWriter.SchemaResult result = DcsSettingsWriter.planSchema(schema, "replace", //$NON-NLS-1$
            "schema", address("Report.Sales"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"variants\":[{\"name\":\"Duplicate\"},{\"name\":\"Duplicate\"}]}"), //$NON-NLS-1$
            LANGUAGES);

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains(
            "body names variant natural key 'Duplicate' more than once")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("Keep exactly one entry")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
        assertEquals("Operational", schema.getSettingsVariants().get(0).getName()); //$NON-NLS-1$
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
    public void testPlainAttributeUpdateRefusesConversionButUpsertCanPlanIt()
    {
        DcsAddress attribute = address("Catalog.Products.Form.ListForm.Attribute.List"); //$NON-NLS-1$
        JsonObject query = json("{\"queryText\":\"SELECT Ref FROM Catalog.Products\"}"); //$NON-NLS-1$

        DcsDynamicListWriter.Result update = DcsDynamicListWriter.plan(null, "update", //$NON-NLS-1$
            "dynamicList", attribute, query, null, LANGUAGES, Version.LATEST); //$NON-NLS-1$
        assertFalse(update.isSuccess());
        assertTrue(update.error(), update.error().contains(attribute.rootFqn()));
        assertTrue(update.error(), update.error().contains("plain")); //$NON-NLS-1$
        assertTrue(update.error(), update.error().contains("action='upsert'")); //$NON-NLS-1$

        DcsDynamicListWriter.Result upsert = DcsDynamicListWriter.plan(null, "upsert", //$NON-NLS-1$
            "dynamicList", attribute, query, null, LANGUAGES, Version.LATEST); //$NON-NLS-1$
        assertTrue(upsert.error(), upsert.isSuccess());
        assertEquals("SELECT Ref FROM Catalog.Products", upsert.plan().queryText()); //$NON-NLS-1$
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

    @Test
    public void testFirstDynamicListSettingsCommitMaterializesCarrierThenCopiesEveryHolder()
    {
        JsonObject[] bodies = {
            json("{\"listSettings\":{\"userFields\":{\"items\":[{" //$NON-NLS-1$
                + "\"kind\":\"expression\",\"dataPath\":\"FirstField\"}]}}}"), //$NON-NLS-1$
            json("{\"listSettings\":{\"selection\":{\"items\":[{" //$NON-NLS-1$
                + "\"kind\":\"field\",\"field\":{\"kind\":\"field\"," //$NON-NLS-1$
                + "\"value\":\"FirstSelection\"}}]}}}") //$NON-NLS-1$
        };
        for (JsonObject body : bodies)
        {
            DynamicListExtInfo extInfo = FormFactory.eINSTANCE.createDynamicListExtInfo();
            DcsDynamicListWriter.Result planned = DcsDynamicListWriter.plan(extInfo, "upsert", //$NON-NLS-1$
                "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
                body, null, LANGUAGES, Version.LATEST);
            assertTrue(planned.error(), planned.isSuccess());
            assertNull(extInfo.getListSettings());

            DataCompositionSettings detachedPlan = planned.plan().settings();
            String attachedFqn = DcsDynamicListWriter.commitSettingsCarrierWithAttachment(extInfo,
                detachedPlan, current ->
                {
                    assertNotSame("attachment must receive an empty carrier, not the populated plan", //$NON-NLS-1$
                        detachedPlan, current.getListSettings());
                    // Real BM may replace the just-attached external-property instance. Reproduce
                    // that shape so the test fails if content is copied before attachment/refetch.
                    current.setListSettings(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
                        .eINSTANCE.createDataCompositionSettings());
                    return "Form.Attribute.List.ListSettings"; //$NON-NLS-1$
                });

            DataCompositionSettings committed = extInfo.getListSettings();
            assertEquals("Form.Attribute.List.ListSettings", attachedFqn); //$NON-NLS-1$
            assertNotNull(committed);
            assertNotSame("the populated plan must not be assigned as the external carrier", //$NON-NLS-1$
                detachedPlan, committed);
            assertNull(DcsModelComparison.firstDifference(detachedPlan, committed));
        }
        DynamicListExtInfo userFields = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DcsDynamicListWriter.Result fieldPlan = DcsDynamicListWriter.plan(userFields, "upsert", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            bodies[0], null, LANGUAGES, Version.LATEST);
        DcsDynamicListWriter.commitSettingsCarrier(userFields, fieldPlan.plan().settings(), null);
        assertEquals("FirstField", userFields.getListSettings().getUserFields().getItems().get(0) //$NON-NLS-1$
            .getDataPath());

        DynamicListExtInfo selection = FormFactory.eINSTANCE.createDynamicListExtInfo();
        DcsDynamicListWriter.Result selectionPlan = DcsDynamicListWriter.plan(selection, "upsert", //$NON-NLS-1$
            "dynamicList", address("Catalog.Products.Form.ListForm.Attribute.List"), //$NON-NLS-1$ //$NON-NLS-2$
            bodies[1], null, LANGUAGES, Version.LATEST);
        DcsDynamicListWriter.commitSettingsCarrier(selection, selectionPlan.plan().settings(), null);
        DataCompositionSelectedField item = (DataCompositionSelectedField)selection.getListSettings()
            .getSelection().getItems().get(0);
        assertEquals("FirstSelection", item.getField().getValue()); //$NON-NLS-1$
    }

    @Test
    public void testEffectiveSettingsComparisonNamesTheFirstMissingAuthoredFeature()
    {
        DataCompositionSettings expected = plan(json("{\"selection\":{\"items\":[]}}")); //$NON-NLS-1$
        DataCompositionSettings actual = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSettings();
        actual.setSelection(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionSelectedFields());

        // These are effective defaults on both sides. Whether BM records the assignments is a
        // storage detail and must not turn a successful attachment into an integrity refusal.
        actual.setItemsViewMode(expected.getItemsViewMode());
        actual.setItemsUserSettingID(expected.getItemsUserSettingID());
        assertNull(DcsModelComparison.firstDifference(expected, actual));

        actual.setSelection(null);
        String difference = DcsModelComparison.firstDifference(expected, actual);
        assertNotNull(difference);
        assertTrue(difference, difference.contains("root/selection")); //$NON-NLS-1$
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
    public void testExactStructureReplaceCanChangeBetweenGroupingAndTable()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"kind\":\"grouping\"," //$NON-NLS-1$
            + "\"name\":\"OldGroup\",\"groupFields\":{\"items\":[]}}]}")); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult table = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0"), "replace", "table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"kind\":\"table\",\"name\":\"NewTable\"}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(table.error(), table.isSuccess());
        assertTrue(table.settings().getItems().get(0) instanceof DataCompositionTable);

        DcsSettingsWriter.SettingsResult group = DcsSettingsWriter.planSettings(table.settings(),
            java.util.Arrays.asList("items", "0"), "replace", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"kind\":\"grouping\",\"name\":\"NewGroup\"}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(group.error(), group.isSuccess());
        assertTrue(group.settings().getItems().get(0) instanceof DataCompositionGroup);

        DcsSettingsWriter.SettingsResult unknown = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0"), "replace", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"kind\":\"chart\"}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(unknown.isSuccess());
        assertTrue(unknown.error(), unknown.error().contains("chart")); //$NON-NLS-1$
        assertTrue(unknown.error(), unknown.error().contains("grouping")); //$NON-NLS-1$
        assertTrue(unknown.error(), unknown.error().contains("table")); //$NON-NLS-1$
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

    @Test
    public void testTableAxisHolderAddressesReplaceAuthoritatively()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"kind\":\"table\",\"name\":\"T\"," //$NON-NLS-1$
            + "\"rows\":[{\"name\":\"Axis\"," //$NON-NLS-1$
            + "\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}}]}," //$NON-NLS-1$
            + "\"selection\":{\"userSettingID\":\"oldSelection\",\"items\":[]}," //$NON-NLS-1$
            + "\"filter\":{\"userSettingID\":\"oldFilter\",\"items\":[]}," //$NON-NLS-1$
            + "\"order\":{\"userSettingID\":\"oldOrder\",\"items\":[]}}]}]}")); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult selection = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "rows", "0", "selection"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "replace", "selection", json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(selection.error(), selection.isSuccess());

        DcsSettingsWriter.SettingsResult filter = DcsSettingsWriter.planSettings(selection.settings(),
            java.util.Arrays.asList("items", "0", "rows", "0", "filter"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "replace", "filter", json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(filter.error(), filter.isSuccess());

        DcsSettingsWriter.SettingsResult order = DcsSettingsWriter.planSettings(filter.settings(),
            java.util.Arrays.asList("items", "0", "rows", "0", "order"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "replace", "order", json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(order.error(), order.isSuccess());

        DcsSettingsWriter.SettingsResult groupFields = DcsSettingsWriter.planSettings(order.settings(),
            java.util.Arrays.asList("items", "0", "rows", "0", "groupFields"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "replace", "grouping", json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(groupFields.error(), groupFields.isSuccess());

        DataCompositionTable table = (DataCompositionTable)groupFields.settings().getItems().get(0);
        DataCompositionTableGroup axis = table.getRows().get(0);
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory factory =
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
        assertEquals(factory.createDataCompositionSelectedFields().getUserSettingID(),
            axis.getSelection().getUserSettingID());
        assertEquals(factory.createDataCompositionFilter().getUserSettingID(),
            axis.getFilter().getUserSettingID());
        assertEquals(factory.createDataCompositionOrder().getUserSettingID(),
            axis.getOrder().getUserSettingID());
        assertTrue(axis.getGroupFields().getItems().isEmpty());
    }

    @Test
    public void testRemoveCanReachTableAxisHoldersAndTheirItems()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"kind\":\"table\",\"name\":\"T\"," //$NON-NLS-1$
            + "\"rows\":[{\"name\":\"Axis\"," //$NON-NLS-1$
            + "\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}}]}," //$NON-NLS-1$
            + "\"selection\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Customer\"}}]}}]}]}")); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult selection = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "rows", "0", "selection"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "remove", "selection", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(selection.error(), selection.isSuccess());
        DataCompositionTable selectedTable =
            (DataCompositionTable)selection.settings().getItems().get(0);
        assertNull(selectedTable.getRows().get(0).getSelection());

        DcsSettingsWriter.SettingsResult groupField = DcsSettingsWriter.planSettings(selection.settings(),
            java.util.Arrays.asList("items", "0", "rows", "0", "groupFields", "items", "0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "remove", "grouping", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(groupField.error(), groupField.isSuccess());
        DataCompositionTable result = (DataCompositionTable)groupField.settings().getItems().get(0);
        assertTrue(result.getRows().get(0).getGroupFields().getItems().isEmpty());
    }

    @Test
    public void testReplaceOnUserFieldsHolderClearsItemsWhenBodyOmitsThem()
    {
        DataCompositionSettings settings = plan(json("{\"userFields\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"OldMargin\"," //$NON-NLS-1$
            + "\"detailExpression\":\"Amount - Cost\"}]}}")); //$NON-NLS-1$
        assertEquals(1, settings.getUserFields().getItems().size());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Collections.singletonList("userFields"), "replace", "userField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            json("{}"), LANGUAGES); //$NON-NLS-1$

        assertTrue(replaced.error(), replaced.isSuccess());
        assertNotNull(replaced.settings().getUserFields());
        assertTrue("a holder-addressed replace with {} must clear every old user field", //$NON-NLS-1$
            replaced.settings().getUserFields().getItems().isEmpty());
    }

    @Test
    public void testReplaceOnTableConditionalAppearanceStartsWithFreshHolder()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"kind\":\"table\"," //$NON-NLS-1$
            + "\"name\":\"T\",\"conditionalAppearance\":{\"items\":[]," //$NON-NLS-1$
            + "\"viewMode\":\"Normal\",\"userSettingID\":\"keepnot\"}}]}")); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "conditionalAppearance"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "replace", "conditionalAppearance", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(replaced.error(), replaced.isSuccess());
        DataCompositionTable table = (DataCompositionTable)replaced.settings().getItems().get(0);
        assertTrue("a table-child replacement must not keep holder scalars it omitted", //$NON-NLS-1$
            table.getConditionalAppearance() == null
                || table.getConditionalAppearance().getUserSettingID() == null
                || table.getConditionalAppearance().getUserSettingID().isEmpty());
    }

    @Test
    public void testTableChildUpdateRefusesAbsentHoldersWhileUpsertAndReplaceCreateThem()
    {
        DataCompositionSettings settings = plan(json(
            "{\"items\":[{\"kind\":\"table\",\"name\":\"T\"}]}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);

        DcsSettingsWriter.SettingsResult selection = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "selection"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "update", "selection", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        DcsSettingsWriter.SettingsResult appearance = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "conditionalAppearance"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "update", "conditionalAppearance", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(selection.isSuccess());
        assertTrue(selection.error(), selection.error().contains("items/0/selection")); //$NON-NLS-1$
        assertTrue(selection.error(), selection.error().contains("action='upsert'")); //$NON-NLS-1$
        assertFalse(appearance.isSuccess());
        assertTrue(appearance.error(),
            appearance.error().contains("items/0/conditionalAppearance")); //$NON-NLS-1$
        assertTrue(appearance.error(), appearance.error().contains("action='upsert'")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(settings));

        DcsSettingsWriter.SettingsResult upserted = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "selection"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "upsert", "selection", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(upserted.error(), upserted.isSuccess());
        assertNotNull(((DataCompositionTable)upserted.settings().getItems().get(0)).getSelection());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "conditionalAppearance"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "replace", "conditionalAppearance", json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(replaced.error(), replaced.isSuccess());
        assertNotNull(((DataCompositionTable)replaced.settings().getItems().get(0))
            .getConditionalAppearance());
    }

    @Test
    public void testReplaceAtAnItemsCollectionAddressClearsItFirst()
    {
        // '#/.../selection/items' is a collection address: the replacement replaces the list, it
        // does not queue up behind what is already there. The same was true of filter/items and
        // order/items, which dispatched straight to their append helpers.
        DataCompositionSettings settings = plan(json("{\"selection\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"field\",\"field\":{\"kind\":\"field\",\"value\":\"Old\"}}]}}")); //$NON-NLS-1$
        assertEquals(1, settings.getSelection().getItems().size());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("selection", "items"), "replace", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"kind\":\"field\",\"field\":{\"kind\":\"field\",\"value\":\"New\"}}"), //$NON-NLS-1$
            LANGUAGES);
        assertTrue(replaced.error(), replaced.isSuccess());
        assertEquals("a collection-addressed replace must swap the list, not append to it", //$NON-NLS-1$
            1, replaced.settings().getSelection().getItems().size());
    }

    @Test
    public void testReplaceOnAGroupFieldsHolderStartsItEmpty()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"name\":\"G\"," //$NON-NLS-1$
            + "\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\"," //$NON-NLS-1$
            + "\"value\":\"Old\"}}]}}]}")); //$NON-NLS-1$
        DataCompositionGroup group = (DataCompositionGroup)settings.getItems().get(0);
        assertEquals(1, group.getGroupFields().getItems().size());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "groupFields"), "replace", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"New\"}}]}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        DataCompositionGroup after = (DataCompositionGroup)replaced.settings().getItems().get(0);
        assertEquals("a replaced groupFields holder must not keep the old fields", //$NON-NLS-1$
            1, after.getGroupFields().getItems().size());
    }

    @Test
    public void testReplaceOnIndexedGroupFieldResetsEveryOmittedMember()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"name\":\"G\"," //$NON-NLS-1$
            + "\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Old\"}," //$NON-NLS-1$
            + "\"use\":false,\"groupType\":\"Items\",\"periodAdditionType\":\"None\"," //$NON-NLS-1$
            + "\"periodAdditionBegin\":{\"kind\":\"number\",\"value\":31337}," //$NON-NLS-1$
            + "\"periodAdditionEnd\":{\"kind\":\"number\",\"value\":31338}}]}}]}")); //$NON-NLS-1$
        DataCompositionGroup beforeGroup = (DataCompositionGroup)settings.getItems().get(0);
        DataCompositionGroupField before = (DataCompositionGroupField)beforeGroup.getGroupFields()
            .getItems().get(0);
        assertFalse(before.isUse());
        assertNotNull(before.getPeriodAdditionBegin());
        assertNotNull(before.getPeriodAdditionEnd());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "groupFields", "items", "0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "replace", "grouping", //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"field\":{\"kind\":\"field\",\"value\":\"New\"}}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());

        DataCompositionGroup afterGroup = (DataCompositionGroup)replaced.settings().getItems().get(0);
        DataCompositionGroupField after = (DataCompositionGroupField)afterGroup.getGroupFields()
            .getItems().get(0);
        DataCompositionGroupField defaults = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createDataCompositionGroupField();
        assertEquals("New", after.getField().getValue()); //$NON-NLS-1$
        assertEquals(defaults.isUse(), after.isUse());
        assertEquals(defaults.getGroupType(), after.getGroupType());
        assertEquals(defaults.getPeriodAdditionType(), after.getPeriodAdditionType());
        assertNull(after.getPeriodAdditionBegin());
        assertNull(after.getPeriodAdditionEnd());
    }

    @Test
    public void testRemoveChecksResolvedNodeTypeAndKeepsNestedLegitimateTargets()
    {
        DataCompositionSettings settings = plan(json("{\"selection\":{\"items\":[]},\"items\":[" //$NON-NLS-1$
            + "{\"name\":\"G\",\"groupFields\":{\"items\":[{\"field\":{\"kind\":\"field\"," //$NON-NLS-1$
            + "\"value\":\"Customer\"}}]},\"selection\":{\"items\":[]}}," //$NON-NLS-1$
            + "{\"kind\":\"table\",\"name\":\"T\",\"selection\":{\"items\":[]}}]}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);

        DcsSettingsWriter.SettingsResult refused = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "selection"), "remove", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(refused.isSuccess());
        assertTrue(refused.error(), refused.error().contains("type='grouping'")); //$NON-NLS-1$
        assertTrue(refused.error(), refused.error().contains("type='selection'")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(settings));

        DcsSettingsWriter.SettingsResult tableSelection = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "1", "selection"), "remove", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(tableSelection.error(), tableSelection.isSuccess());
        assertNull(((DataCompositionTable)tableSelection.settings().getItems().get(1)).getSelection());

        DcsSettingsWriter.SettingsResult groupFields = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0", "groupFields"), "remove", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(groupFields.error(), groupFields.isSuccess());
        assertNull(((DataCompositionGroup)groupFields.settings().getItems().get(0)).getGroupFields());

        DcsSettingsWriter.SettingsResult bareSelection = DcsSettingsWriter.planSettings(settings,
            java.util.Collections.singletonList("selection"), "remove", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            json("{}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(bareSelection.error(), bareSelection.isSuccess());
        assertNull(bareSelection.settings().getSelection());
    }

    @Test
    public void testMutationsCheckResolvedNodeTypeButUnresolvedUpsertStillCreates()
    {
        DataCompositionSettings settings = plan(json("{\"filter\":{\"items\":[" //$NON-NLS-1$
            + "{\"left\":{\"kind\":\"field\",\"value\":\"Amount\"}," //$NON-NLS-1$
            + "\"comparisonType\":\"Greater\",\"right\":[{\"kind\":\"number\"," //$NON-NLS-1$
            + "\"value\":10}],\"use\":true}]}}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);

        for (String action : java.util.Arrays.asList("upsert", "update", "replace")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            DcsSettingsWriter.SettingsResult refused = DcsSettingsWriter.planSettings(settings,
                java.util.Arrays.asList("filter", "items", "0"), action, "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                json("{\"use\":false}"), LANGUAGES); //$NON-NLS-1$

            assertFalse(refused.isSuccess());
            assertTrue(refused.error(), refused.error().contains("action='" + action + "'")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(refused.error(), refused.error().contains("type='selection'")); //$NON-NLS-1$
            assertTrue(refused.error(), refused.error().contains("type='filter'")); //$NON-NLS-1$
        }
        assertEquals(beforeHash, DcsHash.compute(settings));

        DataCompositionSettings structureSettings = plan(json("{\"items\":[{\"kind\":\"grouping\"," //$NON-NLS-1$
            + "\"name\":\"G\",\"groupFields\":{\"items\":[]}}]}")); //$NON-NLS-1$
        String structureHash = DcsHash.compute(structureSettings);
        DcsSettingsWriter.SettingsResult replacedAsTable = DcsSettingsWriter.planSettings(structureSettings,
            java.util.Arrays.asList("items", "0"), "replace", "table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"kind\":\"table\",\"name\":\"T\"}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replacedAsTable.error(), replacedAsTable.isSuccess());
        assertTrue(replacedAsTable.settings().getItems().get(0) instanceof DataCompositionTable);

        DcsSettingsWriter.SettingsResult updateAsTable = DcsSettingsWriter.planSettings(structureSettings,
            java.util.Arrays.asList("items", "0"), "update", "table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"kind\":\"table\",\"name\":\"T\"}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(updateAsTable.isSuccess());
        assertTrue(updateAsTable.error(), updateAsTable.error().contains("action='update'")); //$NON-NLS-1$
        assertTrue(updateAsTable.error(), updateAsTable.error().contains("type='table'")); //$NON-NLS-1$
        assertTrue(updateAsTable.error(), updateAsTable.error().contains("type='grouping'")); //$NON-NLS-1$
        assertEquals(structureHash, DcsHash.compute(structureSettings));

        DcsSettingsWriter.SettingsResult created = DcsSettingsWriter.planSettings(settings,
            java.util.Collections.singletonList("selection"), "upsert", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            json("{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Amount\"}}]}"), //$NON-NLS-1$
            LANGUAGES);
        assertTrue(created.error(), created.isSuccess());
        assertNotNull(created.settings().getSelection());
        assertEquals(1, created.settings().getSelection().getItems().size());
    }

    @Test
    public void testSettingsRootReplaceRefusesAdditionalPropertiesAndSupportedRootStillReplaces()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        DataCompositionSettings settings = plan(settingsBody());
        Structure additionalProperties = McoreFactory.eINSTANCE.createStructure();
        settings.setAdditionalProperties(additionalProperties);
        schema.setDefaultSettings(settings);
        DcsAddress target = address("Report.Sales#/defaultSettings"); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(schema);

        String refusal = DcsMutationGuard.replaceError(schema, target);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("additionalProperties")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains(additionalProperties.eClass().getName()));
        assertEquals(beforeHash, DcsHash.compute(schema));

        DataCompositionSchema supported = DcsFactory.eINSTANCE.createDataCompositionSchema();
        supported.setDefaultSettings(plan(settingsBody()));
        assertNull(DcsMutationGuard.replaceError(supported, target));
        DcsSettingsWriter.SchemaResult replaced = DcsSettingsWriter.planSchema(supported,
            "replace", "userSettings", target, //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"selection\":{\"items\":[]}}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        replaced.plan().commit(supported);
        assertNotNull(supported.getDefaultSettings().getSelection());
        assertNull(supported.getDefaultSettings().getFilter());
        assertNull(supported.getDefaultSettings().getOrder());
    }

    @Test
    public void testGroupFieldsHolderUpdateRefusesAppendingNestedFields()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"kind\":\"grouping\"," //$NON-NLS-1$
            + "\"name\":\"G\",\"groupFields\":{\"items\":[{\"field\":{" //$NON-NLS-1$
            + "\"kind\":\"field\",\"value\":\"Existing\"}}]}}]}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);

        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(settings,
            Arrays.asList("items", "0", "groupFields"), "update", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"items\":[{\"field\":{\"kind\":\"field\",\"value\":\"Created\"}}]}"), //$NON-NLS-1$
            LANGUAGES);

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("exact group-field item index")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("upsert")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(settings));
        DataCompositionGroup group = (DataCompositionGroup)settings.getItems().get(0);
        assertEquals(1, group.getGroupFields().getItems().size());
    }

    @Test
    public void testSelectionGroupUpdateRefusesAppendingNestedSelectionItems()
    {
        DataCompositionSettings settings = plan(json("{\"selection\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"group\",\"items\":[{\"kind\":\"field\",\"field\":{" //$NON-NLS-1$
            + "\"kind\":\"field\",\"value\":\"Existing\"}}]}]}}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);

        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(settings,
            Arrays.asList("selection", "items", "0"), "update", "selection", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"kind\":\"group\",\"items\":[{\"kind\":\"field\",\"field\":{" //$NON-NLS-1$
                + "\"kind\":\"field\",\"value\":\"Created\"}}]}"), LANGUAGES); //$NON-NLS-1$

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("exact selection item index")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("upsert")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(settings));
    }

    @Test
    public void testFilterGroupUpdateRefusesAppendingNestedFilterItems()
    {
        DataCompositionSettings settings = plan(json("{\"filter\":{\"items\":[" //$NON-NLS-1$
            + "{\"kind\":\"group\",\"items\":[{\"kind\":\"item\",\"left\":{" //$NON-NLS-1$
            + "\"kind\":\"field\",\"value\":\"Existing\"},\"comparisonType\":\"Equal\"}]}]}}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);

        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(settings,
            Arrays.asList("filter", "items", "0"), "update", "filter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"kind\":\"group\",\"items\":[{\"kind\":\"item\",\"left\":{" //$NON-NLS-1$
                + "\"kind\":\"field\",\"value\":\"Created\"},\"comparisonType\":\"Equal\"}]}"), //$NON-NLS-1$
            LANGUAGES);

        assertFalse(result.isSuccess());
        assertTrue(result.error(), result.error().contains("exact filter item index")); //$NON-NLS-1$
        assertTrue(result.error(), result.error().contains("upsert")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(settings));
    }

    @Test
    public void testDefaultSettingsRemoveChecksResolvedRootTypeBeforeClearingTree()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        schema.setDefaultSettings(plan(json("{\"filter\":{\"items\":[]}}"))); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(schema);

        DcsSettingsWriter.SchemaResult refused = DcsSettingsWriter.planSchema(schema, "remove", //$NON-NLS-1$
            "selection", address("Report.Sales#/defaultSettings"), json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(refused.isSuccess());
        assertTrue(refused.error(), refused.error().contains("type='selection'")); //$NON-NLS-1$
        assertTrue(refused.error(), refused.error().contains("type='userSettings'")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));
        assertNotNull(schema.getDefaultSettings());

        DcsSettingsWriter.SchemaResult removed = DcsSettingsWriter.planSchema(schema, "remove", //$NON-NLS-1$
            "userSettings", address("Report.Sales#/defaultSettings"), json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.error(), removed.isSuccess());
        removed.plan().commit(schema);
        assertNull(schema.getDefaultSettings());
    }

    @Test
    public void testVariantMutationsRefuseAmbiguousNaturalKeyAndUniqueRemoveStillWorks()
    {
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        for (int i = 0; i < 2; i++)
        {
            SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
                .createSettingsVariant();
            variant.setName("Duplicate"); //$NON-NLS-1$
            variant.setSettings(plan(json("{}"))); //$NON-NLS-1$
            schema.getSettingsVariants().add(variant);
        }
        String beforeHash = DcsHash.compute(schema);

        for (String action : Arrays.asList("update", "remove")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            DcsSettingsWriter.SchemaResult refused = DcsSettingsWriter.planSchema(schema, action,
                "variant", address("Report.Sales#/variants/Duplicate"), //$NON-NLS-1$ //$NON-NLS-2$
                json("{\"presentation\":{\"EN\":\"Changed\"}}"), LANGUAGES); //$NON-NLS-1$
            assertFalse(refused.isSuccess());
            assertTrue(refused.error(), refused.error().contains("Cannot " + action + " variant")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(refused.error(), refused.error().contains("matches 2 existing nodes")); //$NON-NLS-1$
            assertTrue(refused.error(), refused.error().contains("disambiguate")); //$NON-NLS-1$
        }
        DcsSettingsWriter.SchemaResult nested = DcsSettingsWriter.planSchema(schema, "update", //$NON-NLS-1$
            "selection", address("Report.Sales#/variants/Duplicate/settings/selection"), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"items\":[]}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(nested.isSuccess());
        assertTrue(nested.error(), nested.error().contains("matches 2 existing nodes")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(schema));

        DataCompositionSchema unique = schemaWithVariant();
        DcsSettingsWriter.SchemaResult removed = DcsSettingsWriter.planSchema(unique, "remove", //$NON-NLS-1$
            "variant", address("Report.Sales#/variants/Operational"), json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.error(), removed.isSuccess());
        removed.plan().commit(unique);
        assertTrue(unique.getSettingsVariants().isEmpty());
    }

    @Test
    public void testVariantNumericSelectorUsesNaturalKeyBeforeIndexFallback()
    {
        DataCompositionSchema unnamed = DcsFactory.eINSTANCE.createDataCompositionSchema();
        unnamed.getSettingsVariants().add(com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant());
        DcsSettingsWriter.SchemaResult unnamedRemoved = DcsSettingsWriter.planSchema(unnamed,
            "remove", "variant", address("Report.Sales#/variants/0"), json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(unnamedRemoved.error(), unnamedRemoved.isSuccess());
        unnamedRemoved.plan().commit(unnamed);
        assertTrue(unnamed.getSettingsVariants().isEmpty());

        DataCompositionSchema named = DcsFactory.eINSTANCE.createDataCompositionSchema();
        SettingsVariant zero = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        zero.setName("0"); //$NON-NLS-1$
        named.getSettingsVariants().add(zero);
        DcsSettingsWriter.SchemaResult namedRemoved = DcsSettingsWriter.planSchema(named,
            "remove", "variant", address("Report.Sales#/variants/0"), json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(namedRemoved.error(), namedRemoved.isSuccess());
        namedRemoved.plan().commit(named);
        assertTrue(named.getSettingsVariants().isEmpty());

        DataCompositionSchema conflicting = DcsFactory.eINSTANCE.createDataCompositionSchema();
        conflicting.getSettingsVariants().add(
            com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsVariant());
        SettingsVariant namedZero = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        namedZero.setName("0"); //$NON-NLS-1$
        conflicting.getSettingsVariants().add(namedZero);
        String beforeHash = DcsHash.compute(conflicting);
        DcsSettingsWriter.SchemaResult refused = DcsSettingsWriter.planSchema(conflicting,
            "remove", "variant", address("Report.Sales#/variants/0"), json("{}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(refused.isSuccess());
        assertTrue(refused.error(), refused.error().contains("selector '0' identifies 2")); //$NON-NLS-1$
        assertEquals(beforeHash, DcsHash.compute(conflicting));
    }

    @Test
    public void testWholeSettingsUpdateRefusesCreatingEveryMissingHolder()
    {
        DataCompositionSettings settings = plan(json("{}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);
        String[] holders = {"selection", "filter", "order", "conditionalAppearance", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dataParameters", "outputParameters", "userFields"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String holder : holders)
        {
            DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(settings,
                java.util.Collections.emptyList(), "update", "userSettings", //$NON-NLS-1$ //$NON-NLS-2$
                json("{\"" + holder + "\":{}}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(holder, result.isSuccess());
            assertTrue(result.error(), result.error().contains("body." + holder)); //$NON-NLS-1$
            assertTrue(result.error(), result.error().contains("action='upsert'")); //$NON-NLS-1$
            assertEquals(beforeHash, DcsHash.compute(settings));
        }

        DataCompositionSchema reportSchema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        reportSchema.setDefaultSettings(settings);
        DcsSettingsWriter.SchemaResult report = DcsSettingsWriter.planSchema(
            reportSchema, "update", "userSettings", //$NON-NLS-1$ //$NON-NLS-2$
            address("Report.Sales#/defaultSettings"), json("{\"selection\":{}}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(report.isSuccess());
        assertTrue(report.error(), report.error().contains("body.selection")); //$NON-NLS-1$

        DcsSettingsWriter.SettingsResult dynamic = DcsSettingsWriter.planDynamicList(settings,
            "update", "userSettings", //$NON-NLS-1$ //$NON-NLS-2$
            address("Catalog.Products.Form.List.Attribute.List#/listSettings"), //$NON-NLS-1$
            json("{\"filter\":{}}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(dynamic.isSuccess());
        assertTrue(dynamic.error(), dynamic.error().contains("body.filter")); //$NON-NLS-1$
    }

    @Test
    public void testGroupingUpdateRefusesCreatingEveryMissingHolder()
    {
        DataCompositionSettings settings = plan(json("{\"items\":[{\"name\":\"G\"}]}")); //$NON-NLS-1$
        String beforeHash = DcsHash.compute(settings);
        String[] holders = {"groupFields", "selection", "filter", "order", "outputParameters"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String holder : holders)
        {
            DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(settings,
                java.util.Arrays.asList("items", "0"), "update", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                json("{\"" + holder + "\":{}}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$

            assertFalse(holder, result.isSuccess());
            assertTrue(result.error(), result.error().contains(holder));
            assertTrue(result.error(), result.error().contains("action='upsert'")); //$NON-NLS-1$
            assertEquals(beforeHash, DcsHash.compute(settings));
        }

        DcsSettingsWriter.SettingsResult upserted = DcsSettingsWriter.planSettings(settings,
            java.util.Arrays.asList("items", "0"), "upsert", "grouping", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json("{\"selection\":{}}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(upserted.error(), upserted.isSuccess());
        assertNotNull(((DataCompositionGroup)upserted.settings().getItems().get(0)).getSelection());
    }

    @Test
    public void testEveryOtherCompositeUpdateRefusesCreatingMissingHolders()
    {
        DataCompositionSettings table = plan(json("{\"items\":[{\"kind\":\"table\"}]}")); //$NON-NLS-1$
        String tableHash = DcsHash.compute(table);
        for (String holder : Arrays.asList("selection", "conditionalAppearance", //$NON-NLS-1$ //$NON-NLS-2$
            "outputParameters")) //$NON-NLS-1$
        {
            DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(table,
                Arrays.asList("items", "0"), "update", "table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                json("{\"" + holder + "\":{}}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(holder, result.isSuccess());
            assertTrue(result.error(), result.error().contains(holder));
            assertEquals(tableHash, DcsHash.compute(table));
        }

        DataCompositionSettings axis = plan(json(
            "{\"items\":[{\"kind\":\"table\",\"rows\":[{}]}]}")); //$NON-NLS-1$
        String axisHash = DcsHash.compute(axis);
        for (String holder : Arrays.asList("groupFields", "selection", "filter", "order", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "conditionalAppearance", "outputParameters")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(axis,
                Arrays.asList("items", "0", "rows", "0"), "update", "table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                json("{\"" + holder + "\":{}}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(holder, result.isSuccess());
            assertTrue(result.error(), result.error().contains(holder));
            assertEquals(axisHash, DcsHash.compute(axis));
        }

        DataCompositionSettings appearance = plan(json(
            "{\"conditionalAppearance\":{\"items\":[{}]}}")); //$NON-NLS-1$
        String appearanceHash = DcsHash.compute(appearance);
        for (String holder : Arrays.asList("selection", "filter")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(appearance,
                Arrays.asList("conditionalAppearance", "items", "0"), "update", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "conditionalAppearance", json("{\"" + holder + "\":{}}"), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(holder, result.isSuccess());
            assertTrue(result.error(), result.error().contains(holder));
            assertEquals(appearanceHash, DcsHash.compute(appearance));
        }

        DataCompositionSettings userField = plan(json(
            "{\"userFields\":{\"items\":[{\"kind\":\"case\",\"dataPath\":\"Choice\"}]}}")); //$NON-NLS-1$
        String userFieldHash = DcsHash.compute(userField);
        DcsSettingsWriter.SettingsResult variants = DcsSettingsWriter.planSettings(userField,
            Arrays.asList("userFields", "items", "0"), "update", "userField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            json("{\"variants\":{}}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(variants.isSuccess());
        assertTrue(variants.error(), variants.error().contains("variants")); //$NON-NLS-1$
        assertEquals(userFieldHash, DcsHash.compute(userField));
    }

    @Test
    public void testExactConditionalAppearanceSelectionFieldCanBeUpdatedAndReplaced()
    {
        DataCompositionSettings settings = plan(json("{\"conditionalAppearance\":{\"items\":[" //$NON-NLS-1$
            + "{\"selection\":{\"items\":[{\"use\":false,\"field\":{" //$NON-NLS-1$
            + "\"kind\":\"field\",\"value\":\"Old\"}}]}}]}}")); //$NON-NLS-1$
        java.util.List<String> path = Arrays.asList("conditionalAppearance", "items", "0", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "selection", "items", "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        DcsSettingsWriter.SettingsResult updated = DcsSettingsWriter.planSettings(settings, path,
            "update", "conditionalAppearance", //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"field\":{\"kind\":\"field\",\"value\":\"Updated\"}}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(updated.error(), updated.isSuccess());
        DataCompositionConditionalAppearanceItem rule = updated.settings()
            .getConditionalAppearance().getItems().get(0);
        DataCompositionAppearanceField updatedField = (DataCompositionAppearanceField)
            rule.getSelection().getItems().get(0);
        assertEquals("Updated", updatedField.getField().getValue()); //$NON-NLS-1$
        assertFalse(updatedField.isUse());

        DcsSettingsWriter.SettingsResult replaced = DcsSettingsWriter.planSettings(updated.settings(),
            path, "replace", "conditionalAppearance", //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"field\":{\"kind\":\"field\",\"value\":\"Replaced\"}}"), LANGUAGES); //$NON-NLS-1$
        assertTrue(replaced.error(), replaced.isSuccess());
        DataCompositionAppearanceField replacedField = (DataCompositionAppearanceField)replaced
            .settings().getConditionalAppearance().getItems().get(0).getSelection().getItems().get(0);
        DataCompositionAppearanceField defaults = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory
            .eINSTANCE.createDataCompositionAppearanceField();
        assertEquals("Replaced", replacedField.getField().getValue()); //$NON-NLS-1$
        assertEquals(defaults.isUse(), replacedField.isUse());
    }

    @Test
    public void testReferencedUserFieldRemoveAndRenameAreGuardedInVariantAndDynamicList()
    {
        String reportRoot = "Report.UserFieldReferences"; //$NON-NLS-1$
        String reportTarget = reportRoot
            + "#/variants/Protected/settings/userFields/items/0"; //$NON-NLS-1$
        String reportReference = reportRoot
            + "#/variants/Protected/settings/selection/items/0"; //$NON-NLS-1$
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("Protected"); //$NON-NLS-1$
        variant.setSettings(settingsWithUserFields());
        schema.getSettingsVariants().add(variant);
        String schemaHash = DcsHash.compute(schema);

        DcsSettingsWriter.SchemaResult reportRemove = DcsSettingsWriter.planSchema(schema,
            "remove", "userField", address(reportTarget), null, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(reportRemove.isSuccess());
        assertTrue(reportRemove.error(), reportRemove.error().contains("userField 'ProtectedField'")); //$NON-NLS-1$
        assertTrue(reportRemove.error(), reportRemove.error().contains(reportReference));
        assertEquals(schemaHash, DcsHash.compute(schema));

        DcsSettingsWriter.SchemaResult reportRename = DcsSettingsWriter.planSchema(schema,
            "update", "userField", address(reportTarget), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"dataPath\":\"RenamedField\"}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(reportRename.isSuccess());
        assertTrue(reportRename.error(), reportRename.error().contains(reportReference));
        assertEquals(schemaHash, DcsHash.compute(schema));

        String listRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        String listTarget = listRoot + "#/listSettings/userFields/items/0"; //$NON-NLS-1$
        String listReference = listRoot + "#/listSettings/selection/items/0"; //$NON-NLS-1$
        DataCompositionSettings listSettings = settingsWithUserFields();
        String settingsHash = DcsHash.compute(listSettings);

        DcsSettingsWriter.SettingsResult listRemove = DcsSettingsWriter.planDynamicList(
            listSettings, "remove", "userField", address(listTarget), null, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(listRemove.isSuccess());
        assertTrue(listRemove.error(), listRemove.error().contains(listReference));
        assertEquals(settingsHash, DcsHash.compute(listSettings));

        DcsSettingsWriter.SettingsResult listRename = DcsSettingsWriter.planDynamicList(
            listSettings, "update", "userField", address(listTarget), //$NON-NLS-1$ //$NON-NLS-2$
            json("{\"dataPath\":\"RenamedField\"}"), LANGUAGES); //$NON-NLS-1$
        assertFalse(listRename.isSuccess());
        assertTrue(listRename.error(), listRename.error().contains(listReference));
        assertEquals(settingsHash, DcsHash.compute(listSettings));
    }

    @Test
    public void testUserFieldHolderReplacementRemovalAndExactRenameVerbsAreGuarded()
    {
        String reportRoot = "Report.UserFieldOmission"; //$NON-NLS-1$
        String settingsAddress = reportRoot + "#/variants/Protected/settings"; //$NON-NLS-1$
        String holderAddress = settingsAddress + "/userFields"; //$NON-NLS-1$
        String itemAddress = holderAddress + "/items/0"; //$NON-NLS-1$
        String referenceAddress = settingsAddress + "/selection/items/0"; //$NON-NLS-1$
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("Protected"); //$NON-NLS-1$
        variant.setSettings(settingsWithUserFields());
        schema.getSettingsVariants().add(variant);
        String beforeHash = DcsHash.compute(schema);

        JsonObject retainedOnly = json("{\"items\":[{\"kind\":\"expression\"," //$NON-NLS-1$
            + "\"dataPath\":\"FreeField\"}]}"); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult holderReplace = DcsSettingsWriter.planSchema(schema,
            "replace", "userField", address(holderAddress), retainedOnly, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(holderReplace.isSuccess());
        assertTrue(holderReplace.error(), holderReplace.error().contains(referenceAddress));

        DcsSettingsWriter.SchemaResult holderRemove = DcsSettingsWriter.planSchema(schema,
            "remove", "userField", address(holderAddress), null, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(holderRemove.isSuccess());
        assertTrue(holderRemove.error(), holderRemove.error().contains(referenceAddress));

        for (String action : new String[] {"upsert", "replace"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject rename = "replace".equals(action) //$NON-NLS-1$
                ? json("{\"kind\":\"expression\",\"dataPath\":\"RenamedField\"}") //$NON-NLS-1$
                : json("{\"dataPath\":\"RenamedField\"}"); //$NON-NLS-1$
            DcsSettingsWriter.SchemaResult refused = DcsSettingsWriter.planSchema(schema,
                action, "userField", address(itemAddress), rename, LANGUAGES); //$NON-NLS-1$
            assertFalse(action, refused.isSuccess());
            assertTrue(refused.error(), refused.error().contains(referenceAddress));
        }
        assertEquals(beforeHash, DcsHash.compute(schema));

        String listRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        String listHolder = listRoot + "#/listSettings/userFields"; //$NON-NLS-1$
        String listReference = listRoot + "#/listSettings/selection/items/0"; //$NON-NLS-1$
        DataCompositionSettings listSettings = settingsWithUserFields();
        DcsSettingsWriter.SettingsResult listReplace = DcsSettingsWriter.planDynamicList(
            listSettings, "replace", "userField", address(listHolder), retainedOnly, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(listReplace.isSuccess());
        assertTrue(listReplace.error(), listReplace.error().contains(listReference));
    }

    @Test
    public void testWholeSettingsVariantAndSchemaReplaceGuardRetainedDanglingReferences()
    {
        String root = "Report.AuthoritativeSettings"; //$NON-NLS-1$
        String settingsAddress = root + "#/variants/Protected/settings"; //$NON-NLS-1$
        String referenceAddress = settingsAddress + "/selection/items/0"; //$NON-NLS-1$
        JsonObject replacement = settingsReplacementKeepingProtectedReference();
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("Protected"); //$NON-NLS-1$
        variant.setSettings(settingsWithUserFields());
        schema.getSettingsVariants().add(variant);

        DcsSettingsWriter.SchemaResult settingsReplace = DcsSettingsWriter.planSchema(schema,
            "replace", "userSettings", address(settingsAddress), replacement, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(settingsReplace.isSuccess());
        assertTrue(settingsReplace.error(), settingsReplace.error().contains(referenceAddress));

        JsonObject variantBody = new JsonObject();
        variantBody.addProperty("name", "Protected"); //$NON-NLS-1$ //$NON-NLS-2$
        variantBody.add("settings", replacement.deepCopy()); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult variantReplace = DcsSettingsWriter.planSchema(schema,
            "replace", "variant", address(root + "#/variants/Protected"), variantBody, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(variantReplace.isSuccess());
        assertTrue(variantReplace.error(), variantReplace.error().contains(referenceAddress));

        DataCompositionSchema defaultSchema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        defaultSchema.setDefaultSettings(settingsWithUserFields());
        JsonObject schemaBody = new JsonObject();
        schemaBody.add("defaultSettings", replacement.deepCopy()); //$NON-NLS-1$
        DcsSettingsWriter.SchemaResult schemaReplace = DcsSettingsWriter.planSchema(defaultSchema,
            "replace", "schema", address(root), schemaBody, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(schemaReplace.isSuccess());
        assertTrue(schemaReplace.error(), schemaReplace.error().contains(
            root + "#/defaultSettings/selection/items/0")); //$NON-NLS-1$

        String listRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        DcsSettingsWriter.SettingsResult listReplace = DcsSettingsWriter.planDynamicList(
            settingsWithUserFields(), "replace", "userSettings", //$NON-NLS-1$ //$NON-NLS-2$
            address(listRoot + "#/listSettings"), replacement, LANGUAGES); //$NON-NLS-1$
        assertFalse(listReplace.isSuccess());
        assertTrue(listReplace.error(), listReplace.error().contains(
            listRoot + "#/listSettings/selection/items/0")); //$NON-NLS-1$

        DcsSettingsWriter.SchemaResult removeWholeSubtree = DcsSettingsWriter.planSchema(schema,
            "replace", "userSettings", address(settingsAddress), new JsonObject(), LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(removeWholeSubtree.error(), removeWholeSubtree.isSuccess());
    }

    @Test
    public void testUnreferencedUserFieldStillRemovesInVariantAndDynamicList()
    {
        String reportRoot = "Report.UnreferencedUserField"; //$NON-NLS-1$
        DataCompositionSchema schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        SettingsVariant variant = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE
            .createSettingsVariant();
        variant.setName("Free"); //$NON-NLS-1$
        variant.setSettings(settingsWithUserFields());
        schema.getSettingsVariants().add(variant);
        String reportTarget = reportRoot + "#/variants/Free/settings/userFields/items/1"; //$NON-NLS-1$

        DcsSettingsWriter.SchemaResult reportRemove = DcsSettingsWriter.planSchema(schema,
            "remove", "userField", address(reportTarget), null, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(reportRemove.error(), reportRemove.isSuccess());
        reportRemove.plan().commit(schema);
        assertEquals(1, schema.getSettingsVariants().get(0).getSettings().getUserFields()
            .getItems().size());

        String listRoot = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        DataCompositionSettings listSettings = settingsWithUserFields();
        DcsSettingsWriter.SettingsResult listRemove = DcsSettingsWriter.planDynamicList(
            listSettings, "remove", "userField", //$NON-NLS-1$ //$NON-NLS-2$
            address(listRoot + "#/listSettings/userFields/items/1"), null, LANGUAGES); //$NON-NLS-1$
        assertTrue(listRemove.error(), listRemove.isSuccess());
        assertEquals(1, listRemove.settings().getUserFields().getItems().size());
        assertEquals("ProtectedField", listRemove.settings().getUserFields().getItems().get(0) //$NON-NLS-1$
            .getDataPath());
    }

    private static DataCompositionSettings plan(JsonObject body)
    {
        DcsSettingsWriter.SettingsResult result = DcsSettingsWriter.planSettings(null,
            java.util.Collections.emptyList(), "upsert", "userSettings", body, LANGUAGES); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.error(), result.isSuccess());
        assertNotNull(result.settings());
        return result.settings();
    }

    private static DataCompositionSettings settingsWithUserFields()
    {
        return plan(json("{\"selection\":{\"items\":[{\"kind\":\"field\",\"field\":{" //$NON-NLS-1$
            + "\"kind\":\"field\",\"value\":\"ProtectedField\"}}]},\"userFields\":{" //$NON-NLS-1$
            + "\"items\":[{\"kind\":\"expression\",\"dataPath\":\"ProtectedField\"}," //$NON-NLS-1$
            + "{\"kind\":\"expression\",\"dataPath\":\"FreeField\"}]}}")); //$NON-NLS-1$
    }

    private static JsonObject settingsReplacementKeepingProtectedReference()
    {
        return json("{\"selection\":{\"items\":[{\"kind\":\"field\",\"field\":{" //$NON-NLS-1$
            + "\"kind\":\"field\",\"value\":\"ProtectedField\"}}]},\"userFields\":{" //$NON-NLS-1$
            + "\"items\":[{\"kind\":\"expression\",\"dataPath\":\"FreeField\"}]}}"); //$NON-NLS-1$
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

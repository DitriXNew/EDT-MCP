/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A replacement scans exactly the objects that its real planner rebuilds. */
public class DcsReplacementScopeGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final Version VERSION = Version.V8_3_27;
    private static final List<String> CONCRETE = List.of("grouping", "table", "chart", "selection",
        "filter", "dataParameter", "order", "conditionalAppearance", "userField", "outputParameter");

    @Test
    public void testBareVariantReplaceKeepsAnUnwritableSibling()
    {
        assertVariantScope("", "Main");
    }

    @Test
    public void testVariantCollectionAddressAlsoReplacesOnlyTheNamedMatch()
    {
        assertVariantScope("#/variants", "Main");
    }

    @Test
    public void testCreatingAVariantDoesNotScanExistingVariants()
    {
        assertVariantScope("", "New");
    }

    @Test
    public void testVariantNaturalKeysRemainCaseSensitive()
    {
        assertVariantScope("", "other");
    }

    @Test
    public void testTheVariantActuallyReplacedStillRefuses()
    {
        var schema = schema();
        addVariant(schema, "Main", badSettings());
        addVariant(schema, "Other", DcsFactory.eINSTANCE.createDataCompositionSettings());
        for (String pointer : List.of("", "#/variants", "#/variants/Main"))
        {
            String refusal = refusal(schema, "variant", ROOT + pointer,
                json("{name:'Main',presentation:'Main'}"));
            assertNotNull("the matched variant must remain guarded", refusal);
            assertTrue(refusal, refusal.contains("#/variants/Main/settings/additionalProperties"));
        }
    }

    @Test
    public void testConcreteTypesAtDefaultSettingsScanOnlyTheirDefaultPath()
    {
        assertConcreteScope("#/defaultSettings", false);
    }

    @Test
    public void testConcreteTypesAtVariantSettingsScanOnlyTheirDefaultPath()
    {
        assertConcreteScope("#/variants/Main/settings", false);
    }

    @Test
    public void testConcreteTypesAtNonContainmentListSettingsScanOnlyTheirDefaultPath()
    {
        assertConcreteScope("#/listSettings", true);
    }

    @Test
    public void testBareDynamicListReplaceIsRejectedBeforeItsSettingsPlanner()
    {
        var dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
        dynamic.setListSettings(badSettings());
        assertFalse("EDT declares this reference non-containment",
            ((org.eclipse.emf.ecore.EReference)dynamic.eClass().getEStructuralFeature("listSettings")).isContainment());
        var snapshot = EcoreUtil.copy(dynamic.getListSettings());
        var result = DcsDynamicListWriter.plan(dynamic, "replace", "dynamicList", address(ROOT),
            json("{listSettings:{}}"), null, null, VERSION);
        assertFalse("the alleged destructive route is not a supported operation", result.isSuccess());
        assertTrue(result.error(), result.error().contains("supports action='upsert' or 'update'"));
        assertTrue(EcoreUtil.equals(snapshot, dynamic.getListSettings()));
        assertNotNull("the supported whole-settings replace still reaches the non-containment root",
            refusal(dynamic, "userSettings", ROOT, json("{}")));
        assertNotNull(refusal(dynamic, "userSettings", ROOT + "#/listSettings", json("{}")));
    }

    @Test
    public void testExactNumericVariantPointerUsesTheWritersNaturalKeyLookup()
    {
        var schema = schema();
        addVariant(schema, "Main", badSettings());
        var snapshot = EcoreUtil.copy(schema.getSettingsVariants().get(0));
        var body = json("{presentation:'New'}");
        var target = address(ROOT + "#/variants/0");
        var plan = DcsSettingsWriter.planSchema(schema, "replace", "variant", target, body, null, VERSION);
        assertTrue(plan.error(), plan.isSuccess());
        assertNull("applyVariant creates the natural name 0 and keeps Main", refusal(schema, "variant", target.toString(), body));
        plan.plan().commit(schema);
        assertEquals(2, schema.getSettingsVariants().size());
        assertEquals("0", schema.getSettingsVariants().get(1).getName());
        assertTrue(EcoreUtil.equals(snapshot, schema.getSettingsVariants().get(0)));
    }

    private static void assertVariantScope(String pointer, String name)
    {
        var schema = schema();
        addVariant(schema, "Main", DcsFactory.eINSTANCE.createDataCompositionSettings());
        addVariant(schema, "Other", badSettings());
        var sibling = EcoreUtil.copy(schema.getSettingsVariants().get(1));
        var body = json("{name:'" + name + "',presentation:'Visible'}");
        var plan = DcsSettingsWriter.planSchema(schema, "replace", "variant", address(ROOT + pointer), body, null, VERSION);
        assertTrue(plan.error(), plan.isSuccess());
        assertNull("the writer keeps the unwritable sibling", refusal(schema, "variant", ROOT + pointer, body));
        plan.plan().commit(schema);
        assertTrue(EcoreUtil.equals(sibling, schema.getSettingsVariants().get(1)));
    }

    private static void assertConcreteScope(String pointer, boolean dynamicRoot)
    {
        for (String type : CONCRETE)
        {
            var settings = badSettings();
            var schema = schema();
            schema.setDefaultSettings(settings);
            addVariant(schema, "Main", settings);
            // Containment moves settings into the variant, so select a fresh default explicitly.
            schema.setDefaultSettings(badSettings());
            var dynamic = FormFactory.eINSTANCE.createDynamicListExtInfo();
            dynamic.setListSettings(badSettings());
            var body = json("{items:[]}");
            if (List.of("selection", "filter", "dataParameter", "order", "conditionalAppearance", "outputParameter").contains(type))
                body = json("{}");
            if (dynamicRoot)
            {
                var plan = DcsSettingsWriter.planDynamicList(dynamic.getListSettings(), "replace", type,
                    address(ROOT + pointer), body, null, VERSION);
                assertTrue(type + ": " + plan.error(), plan.isSuccess());
                assertTrue(EcoreUtil.equals(dynamic.getListSettings().getAdditionalProperties(),
                    plan.settings().getAdditionalProperties()));
            }
            else
            {
                var plan = DcsSettingsWriter.planSchema(schema, "replace", type, address(ROOT + pointer), body, null, VERSION);
                assertTrue(type + ": " + plan.error(), plan.isSuccess());
            }
            assertNull(type + " only rebuilds " + DcsSettingsWriter.defaultSettingsPath(type),
                refusal(dynamicRoot ? dynamic : schema, type, ROOT + pointer, body));
        }
    }

    /** Select the body-aware seam when present, and the actual base seam for red-on-base proof. */
    private static String refusal(EObject root, String type, String target, JsonObject body)
    {
        EObject settings = root instanceof DataCompositionSchema
            ? ((DataCompositionSchema)root).getDefaultSettings()
            : ((com._1c.g5.v8.dt.form.model.DynamicListExtInfo)root).getListSettings();
        try
        {
            Method method;
            try
            {
                method = DcsTool.class.getDeclaredMethod("replaceRefusal", EObject.class, EObject.class,
                    String.class, DcsAddress.class, DcsWriterCatalogues.class, JsonObject.class);
            }
            catch (NoSuchMethodException base)
            {
                return DcsTool.replaceRefusal(root, settings, type, address(target), DcsReplaceTestCatalogues.schema());
            }
            return (String)method.invoke(null, root, settings, type, address(target), DcsReplaceTestCatalogues.schema(), body);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }

    private static DataCompositionSchema schema()
    {
        return com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchema();
    }

    private static void addVariant(DataCompositionSchema schema, String name, DataCompositionSettings settings)
    {
        var variant = DcsFactory.eINSTANCE.createSettingsVariant();
        variant.setName(name);
        var presentation = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createPresentation();
        presentation.setValue(name);
        variant.setPresentation(presentation);
        variant.setSettings(settings);
        schema.getSettingsVariants().add(variant);
    }

    private static DataCompositionSettings badSettings()
    {
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        var structure = McoreFactory.eINSTANCE.createStructure();
        var property = McoreFactory.eINSTANCE.createStructureProperty();
        property.setName("Stored");
        var value = McoreFactory.eINSTANCE.createNumberValue();
        value.setValue(new BigDecimal("1e-39"));
        property.setValue(value);
        structure.getProperty().add(property);
        settings.setAdditionalProperties(structure);
        return settings;
    }

    private static DcsAddress address(String value) { return DcsAddress.parse(value).address(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}

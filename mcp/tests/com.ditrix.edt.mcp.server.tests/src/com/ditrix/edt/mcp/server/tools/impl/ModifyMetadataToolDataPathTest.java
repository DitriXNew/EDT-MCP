/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.Table;
import com.ditrix.edt.mcp.server.utils.ComposerDataTreeFake;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercises the actual prepare/apply pipeline, including its three rebind suggestions. */
public class ModifyMetadataToolDataPathTest
{
    private final ComposerDataTreeFake tree = new ComposerDataTreeFake();

    @After
    public void restoreService() throws Exception
    {
        tree.close();
    }

    @Test
    public void testFieldDataPathPreparesWithoutMutationAndRoundTrips() throws Exception
    {
        tree.path("K.Settings.Filter.RightValue", false).install();
        Form form = form();
        FormField field = field(form, "Value", "K.Settings.Filter.LeftValue");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(field, List.of(path("K.Settings.Filter.RightValue")), changes));
        assertEquals("preparation must not change the model", "K.Settings.Filter.LeftValue", stored(field));
        assertEquals(1, changes.size());
        assertEquals("dataPath", changes.get(0).change().featureName());
        changes.get(0).change().applyTo(field, null);
        assertEquals("K.Settings.Filter.RightValue", stored(field));
    }

    @Test
    public void testRussianDataPathRoutesThroughTheSamePredicate() throws Exception
    {
        String value = "K.\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438."
            + "\u041e\u0442\u0431\u043e\u0440.\u041f\u0440\u0430\u0432\u043e\u0435\u0417\u043d\u0430\u0447\u0435\u043d\u0438\u0435";
        String canonical = "K.Settings.Filter.RightValue";
        tree.alias(canonical, value, false).install();
        FormField field = field(form(), "Value", "K");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(field, List.of(path(value)), changes));
        changes.get(0).change().applyTo(field, null);
        assertEquals(canonical, stored(field));
    }

    @Test
    public void testInvalidDataPathRejectsTheWholeBatchBeforeWriting() throws Exception
    {
        tree.path("K", false).path("K.Settings", false).install();
        FormField field = field(form(), "Value", "K");
        JsonObject visible = JsonParser.parseString("{\"name\":\"visible\",\"value\":false}").getAsJsonObject();
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        String error = verdict(field, List.of(visible, path("K.Settings.Ghost.Use")), changes);
        assertNotNull(error);
        assertTrue(error, error.contains("K.Settings.Ghost.Use"));
        assertTrue(error, error.contains("first unresolved segment 'Ghost'"));
        assertTrue("no earlier property may escape a rejected batch", changes.isEmpty());
        assertEquals("K", stored(field));
    }

    @Test
    public void testModifyRefusesAnUnavailableTreeInsteadOfAccepting() throws Exception
    {
        tree.unavailable(false);
        FormField field = field(form(), "Value", "K");
        try
        {
            String error = verdict(field, List.of(path("K.Settings.Filter.Use")), new ArrayList<>());
            fail("the dataPath route must consult the unavailable EDT data tree: " + error);
        }
        catch (IllegalStateException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("IDataSourceInfoAssociationService"));
            assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("retry"));
        }
        assertEquals("K", stored(field));
    }

    @Test
    public void testEmptyTableDataPathCanBeAssignedWithoutInventingColumns() throws Exception
    {
        tree.path("K.Settings.Order", true).install();
        Form form = form();
        Table table = table(form, "Filter", "K.Settings.Filter");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(table, List.of(path("K.Settings.Order")), changes));
        changes.get(0).change().applyTo(table, null);
        assertEquals("K.Settings.Order", String.join(".", table.getDataPath().getSegments()));
        assertTrue(table.getItems().isEmpty());
    }

    @Test
    public void testTableWithColumnsRefusesRebindingWithActionableAdvice() throws Exception
    {
        tree.path("K.Settings.Order", true).install();
        Form form = form();
        Table table = table(form, "Filter", "K.Settings.Filter");
        FormField column = FormFactory.eINSTANCE.createFormField();
        column.setName("Use");
        column.setDataPath(ComposerDataTreeFake.dataPath("K.Settings.Filter.Use"));
        table.getItems().add(column);
        String error = verdict(table, List.of(path("K.Settings.Order")), new ArrayList<>());
        assertNotNull(error);
        assertTrue(error, error.contains("Filter"));
        assertTrue(error, error.contains("delete_metadata"));
        assertTrue(error, error.contains("columns"));
        assertTrue(error, error.contains("dataPath"));
        assertEquals("K.Settings.Filter", String.join(".", table.getDataPath().getSegments()));
        assertEquals("K.Settings.Filter.Use", stored(column));
    }

    @Test
    public void testRepeatedDataPathCannotRebindThroughAnIntermediateTableSource() throws Exception
    {
        tree.path("K.Settings.Order", true).install();
        Form form = form();
        var list = ComposerDataTreeFake.attribute(form, "List", "DynamicList");
        list.setExtInfo(FormFactory.eINSTANCE.createDynamicListExtInfo());
        Table table = table(form, "Filter", "K.Settings.Filter");
        JsonObject last = path("K.Settings.Order");
        last.addProperty("name", "DATAPATH");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        String error = verdict(table, List.of(path("List"), last), changes);
        assertNotNull("the intermediate dynamic-list binding would otherwise leave stale extInfo", error);
        assertTrue(error, error.contains("dataPath"));
        assertTrue(error, error.contains("only once"));
        assertTrue(error, error.contains("final path"));
        assertEquals("K.Settings.Filter", String.join(".", table.getDataPath().getSegments()));
        assertNull(table.getExtInfo());
        assertTrue("the rejected batch must prepare no changes for application", changes.isEmpty());
    }

    @Test
    public void testAConfiguredEmptyTableRefusesChangingItsBinding() throws Exception
    {
        tree.path("K.Settings.Order", true).install();
        Table table = table(form(), "Configured", "K.Settings.Filter");
        table.setExtInfo(FormFactory.eINSTANCE.createDynamicListTableExtInfo());
        var original = table.getExtInfo();
        String error = verdict(table, List.of(path("K.Settings.Order")), new ArrayList<>());
        assertNotNull(error);
        assertTrue(error, error.contains("Configured"));
        assertTrue(error, error.contains("create_metadata"));
        assertTrue(error, error.contains("extInfo"));
        assertEquals(original, table.getExtInfo());
        assertEquals("K.Settings.Filter", String.join(".", table.getDataPath().getSegments()));
    }

    @Test
    public void testReassigningTheSamePathDoesNotRequireDeletingColumns() throws Exception
    {
        tree.path("K.Settings.Filter", true).install();
        Table table = table(form(), "Filter", "K.Settings.Filter");
        table.getItems().add(FormFactory.eINSTANCE.createFormField());
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(table, List.of(path("K.Settings.Filter")), changes));
        changes.get(0).change().applyTo(table, null);
        assertEquals(1, table.getItems().size());
    }

    @Test
    public void testDynamicListFieldRebindingRegistersUseAlways()
    {
        Form form = form();
        var list = ComposerDataTreeFake.attribute(form, "List", "DynamicList");
        list.setExtInfo(FormFactory.eINSTANCE.createDynamicListExtInfo());
        FormField field = field(form, "Value", "K");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(field, List.of(path("List.Ref")), changes));
        changes.get(0).change().applyTo(field, null);
        assertEquals("List.Ref", stored(field));
        assertEquals(1, list.getNotDefaultUseAlwaysAttributes().size());
        assertEquals(List.of("List", "Ref"), list.getNotDefaultUseAlwaysAttributes().get(0).getSegments());
    }

    @Test
    public void testDynamicListFieldRebindingDropsTheOldUnusedUseAlwaysRegistration()
    {
        Form form = form();
        var list = ComposerDataTreeFake.attribute(form, "List", "DynamicList");
        list.setExtInfo(FormFactory.eINSTANCE.createDynamicListExtInfo());
        FormField field = field(form, "Value", "K");
        FormField other = field(form, "Other", "K");
        List<ModifyMetadataTool.HolderChange> changes = new ArrayList<>();
        assertNull(verdict(field, List.of(path("List.Ref")), changes));
        changes.get(0).change().applyTo(field, null);
        changes.clear();
        assertNull(verdict(other, List.of(path("List.Ref")), changes));
        changes.get(0).change().applyTo(other, null);
        changes.clear();
        assertEquals(1, list.getNotDefaultUseAlwaysAttributes().size());

        // Another field still shows List.Ref, so its registration stays.
        assertNull(verdict(field, List.of(path("List.Description")), changes));
        changes.get(0).change().applyTo(field, null);
        changes.clear();
        assertEquals(List.of(List.of("List", "Ref"), List.of("List", "Description")),
            list.getNotDefaultUseAlwaysAttributes().stream().map(p -> List.copyOf(p.getSegments())).toList());

        // The last field leaves List.Ref: nothing shows it any more, so it is no longer UseAlways.
        assertNull(verdict(other, List.of(path("List.Description")), changes));
        changes.get(0).change().applyTo(other, null);
        assertEquals(List.of(List.of("List", "Description")),
            list.getNotDefaultUseAlwaysAttributes().stream().map(p -> List.copyOf(p.getSegments())).toList());
    }

    @Test
    public void testEmptyAndNonStringDataPathValuesAreRefused()
    {
        FormField field = field(form(), "Value", "K");
        for (String json : List.of("null", "false", "17", "{}", "[]", "\"\"", "\"K..Settings\""))
        {
            JsonObject prop = new JsonObject();
            prop.addProperty("name", "dataPath");
            prop.add("value", JsonParser.parseString(json));
            String error = verdict(field, List.of(prop), new ArrayList<>());
            assertNotNull(json, error);
            assertTrue(error, error.contains("dataPath"));
            assertEquals("K", stored(field));
        }
    }

    @Test
    public void testDataPathOnUnsupportedItemNamesTheSupportedRoute()
    {
        Form form = form();
        var decoration = FormFactory.eINSTANCE.createDecoration();
        decoration.setName("Caption");
        form.getItems().add(decoration);
        String error = verdict(decoration, List.of(path("K")), new ArrayList<>());
        assertNotNull(error);
        assertTrue(error, error.contains("Field/Table"));
        assertTrue(error, error.contains("dataPath"));
    }

    @Test
    public void testAllThreeRetypeAdviceTextsNameTheImplementedRebindRoute() throws Exception
    {
        Form rows = ComposerDataTreeFake.form("Rows", "ValueTable");
        table(rows, "RowsTable", "Rows");
        assertAdvice("refuseRetypeThatOrphansColumns", rows.getAttributes().get(0), "String");

        Form below = ComposerDataTreeFake.form("Rows", "ValueTable");
        field(below, "Price", "Rows.Price");
        assertAdvice("refuseRetypeThatOrphansColumns", below.getAttributes().get(0), "String");

        Form composer = form();
        field(composer, "Use", "K.Settings.Filter.Use");
        assertAdvice("refuseRetypeThatOrphansItems", composer.getAttributes().get(0), "ValueTable");
    }

    private static void assertAdvice(String methodName, org.eclipse.emf.ecore.EObject attribute, String kind)
        throws Exception
    {
        Method method = ModifyMetadataTool.class.getDeclaredMethod(methodName,
            org.eclipse.emf.ecore.EObject.class, JsonObject.class);
        method.setAccessible(true);
        JsonObject prop = JsonParser.parseString("{\"name\":\"valueType\",\"value\":{\"types\":[{\"kind\":\""
            + kind + "\"}]}}").getAsJsonObject();
        String error = (String)method.invoke(null, attribute, prop);
        assertNotNull(error);
        assertTrue(error, error.contains("Field/Table FQN"));
        assertTrue(error, error.contains("properties:[{name:'dataPath'"));
        assertTrue(error, error.contains("remove its columns first"));
        assertFalse(error, error.contains("re-point them (modify_metadata with 'dataPath')"));
    }

    private static String verdict(org.eclipse.emf.ecore.EObject item, List<JsonObject> props,
        List<ModifyMetadataTool.HolderChange> changes)
    {
        return new ModifyMetadataTool().formRetypeVerdict(null, null, item, props,
            new MdNameNormalizer.Report(true), changes);
    }

    private static JsonObject path(String value)
    {
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "dataPath");
        prop.addProperty("value", value);
        return prop;
    }

    private static Form form()
    {
        return ComposerDataTreeFake.form("K", "DataCompositionSettingsComposer");
    }

    private static FormField field(Form form, String name, String path)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(name);
        field.setDataPath(ComposerDataTreeFake.dataPath(path));
        form.getItems().add(field);
        return field;
    }

    private static Table table(Form form, String name, String path)
    {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName(name);
        table.setDataPath(ComposerDataTreeFake.dataPath(path));
        form.getItems().add(table);
        return table;
    }

    private static String stored(FormField field)
    {
        return String.join(".", field.getDataPath().getSegments());
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.Table;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.Kind;

/** Regression coverage of the writer decisions behind issue #728. */
public class FormElementWriterComposerDataPathTest
{
    private final ComposerDataTreeFake tree = new ComposerDataTreeFake();

    @After
    public void restoreService() throws Exception
    {
        tree.close();
    }

    @Test
    public void testComposerTableHasNeitherLineNumberNorMetadataColumns() throws Exception
    {
        tree.path("K.Settings.Filter", true).install();
        Form form = form();
        for (boolean russian : new boolean[] {false, true})
        {
            String name = russian ? "FilterRu" : "Filter";
            assertNull(FormElementWriter.createTable(form, name, null, "K.Settings.Filter",
                List.of("WrongMetadataColumn"), null, null, russian, new String[1]));
            Table table = (Table)FormElementWriter.findFormItem(form, name);
            assertEquals("a platform collection has no generated columns", 0, table.getItems().size());
        }
    }

    @Test
    public void testFourComposerFilterColumnsResolveUnderTheirTable() throws Exception
    {
        tree.path("K", false).path("K.Settings", false).path("K.Settings.Filter", true);
        for (String column : List.of("Use", "LeftValue", "ComparisonType", "RightValue"))
        {
            tree.path("K.Settings.Filter." + column, false);
        }
        tree.install();
        Form form = form();
        assertNull(FormElementWriter.createTable(form, "Filter", null, "K.Settings.Filter",
            List.of(), null, null, false, new String[1]));
        for (String column : List.of("Use", "LeftValue", "ComparisonType", "RightValue"))
        {
            String path = "K.Settings.Filter." + column;
            assertNull(createField(form, column, "Filter", path));
            FormField field = (FormField)FormElementWriter.findFormItem(form, column);
            assertEquals(path, String.join(".", field.getDataPath().getSegments()));
            assertEquals(FormElementWriter.findFormItem(form, "Filter"), field.eContainer());
        }
    }

    @Test
    public void testRussianPathUsesTheSameDataTreeDecision() throws Exception
    {
        String head = "\u041a";
        String path = head + ".\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438."
            + "\u041e\u0442\u0431\u043e\u0440.\u041f\u0440\u0430\u0432\u043e\u0435\u0417\u043d\u0430\u0447\u0435\u043d\u0438\u0435";
        String canonical = head + ".Settings.Filter.RightValue";
        tree.alias(canonical, path, false).install();
        Form form = ComposerDataTreeFake.form(head, "DataCompositionSettingsComposer");
        assertNull(createField(form, "RightValue", null, path));
        assertEquals(canonical, String.join(".",
            ((FormField)FormElementWriter.findFormItem(form, "RightValue")).getDataPath().getSegments()));
    }

    @Test
    public void testOrderSelectionAndIndexedConditionalAppearanceUseTheTree() throws Exception
    {
        tree.install();
        Form form = form();
        int index = 0;
        for (String path : List.of("K.Settings.Order.Field", "K.Settings.Selection.Field",
            "K.Settings.ConditionalAppearance[0].Filter.Use", "K.Settings.Filter.Presentation",
            "K.Settings.Filter.ViewMode"))
        {
            tree.path(path, false);
            assertNull(createField(form, "Field" + index++, null, path));
            FormField field = (FormField)FormElementWriter.findFormItem(form, "Field" + (index - 1));
            assertEquals("normalization must preserve a requested row index", path,
                String.join(".", field.getDataPath().getSegments()));
        }
    }

    @Test
    public void testAcceptanceIsNotAComposerOrPropertyWhitelist() throws Exception
    {
        tree.path("Other.FutureCollection", true).path("Other.FutureCollection.NewProperty", false).install();
        Form form = ComposerDataTreeFake.form("Other", "FuturePlatformType");
        assertNull(FormElementWriter.createTable(form, "Future", null, "Other.FutureCollection",
            List.of(), null, null, false, new String[1]));
        assertNull(createField(form, "FutureField", "Future", "Other.FutureCollection.NewProperty"));
        assertTrue(((Table)FormElementWriter.findFormItem(form, "Future")).getItems().size() == 1);
    }

    @Test
    public void testUnresolvedFieldNamesFirstMissingSegmentAndWritesNothing() throws Exception
    {
        tree.path("K", false).path("K.Settings", false).install();
        Form form = form();
        String path = "K.Settings.Missing.Use";
        String error = createField(form, "Bad", null, path);
        assertNotNull(error);
        assertTrue(error, error.contains(path));
        assertTrue(error, error.contains("first unresolved segment 'Missing'"));
        assertTrue(error, error.contains("EDT"));
        assertTrue(form.getItems().isEmpty());
    }

    @Test
    public void testUnresolvedTableIsRefusedBeforeAnyItemIsCreated() throws Exception
    {
        tree.path("K", false).path("K.Settings", false).install();
        Form form = form();
        String error = FormElementWriter.createTable(form, "Bad", null, "K.Settings.Ghost",
            List.of(), null, null, false, new String[1]);
        assertNotNull(error);
        assertTrue(error, error.contains("first unresolved segment 'Ghost'"));
        assertTrue(form.getItems().isEmpty());
    }

    @Test
    public void testResolvedScalarCannotBeUsedAsTableRows() throws Exception
    {
        tree.path("K.Settings.Filter.Use", false).install();
        Form form = form();
        String error = FormElementWriter.createTable(form, "Bad", null, "K.Settings.Filter.Use",
            List.of(), null, null, false, new String[1]);
        assertNotNull(error);
        assertTrue(error, error.contains("K.Settings.Filter.Use"));
        assertTrue(error, error.contains("row source"));
        assertTrue(form.getItems().isEmpty());
    }

    @Test
    public void testUnavailableDataTreeRefusesFieldAndTableWithoutGuessing() throws Exception
    {
        for (boolean returnsNull : new boolean[] {false, true})
        {
            tree.unavailable(returnsNull);
            for (Kind kind : List.of(Kind.FIELD, Kind.TABLE))
            {
                Form form = form();
                String path = kind == Kind.TABLE ? "K.Settings.Filter" : "K.Settings.Filter.Use";
                try
                {
                    String error = kind == Kind.TABLE
                        ? FormElementWriter.createTable(form, "Bad", null, path, List.of(), null,
                            null, false, new String[1])
                        : createField(form, "Bad", null, path);
                    fail("a missing EDT service must raise an honest readiness error: " + error);
                }
                catch (IllegalStateException e)
                {
                    assertTrue(e.getMessage(), e.getMessage().contains(path));
                    assertTrue(e.getMessage(), e.getMessage().contains("IDataSourceInfoAssociationService"));
                    assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("retry"));
                }
                assertTrue(form.getItems().isEmpty());
            }
        }
    }

    @Test
    public void testNonMainObjectTabularSectionKeepsItsColumns() throws Exception
    {
        tree.tabularSection("Backup.Goods").install();
        Form form = ComposerDataTreeFake.form("Backup", "DocumentObject.Order");
        assertNull(FormElementWriter.createTable(form, "Goods", null, "Backup.Goods",
            List.of("Product"), null, null, false, new String[1]));
        assertNotNull(FormElementWriter.findFormItem(form, "GoodsLineNumber"));
        assertNotNull(FormElementWriter.findFormItem(form, "GoodsProduct"));
    }

    @Test
    public void testMainObjectTabularSectionStillHasItsStandardAndMetadataColumns()
    {
        Form form = ComposerDataTreeFake.form("Object", "DocumentObject.Order");
        form.getAttributes().get(0).setMain(true);
        assertNull(FormElementWriter.createTable(form, "Goods", null, "Object.Goods",
            List.of("Product"), null, null, false, new String[1]));
        assertNotNull(FormElementWriter.findFormItem(form, "GoodsLineNumber"));
        assertNotNull(FormElementWriter.findFormItem(form, "GoodsProduct"));
    }

    private static Form form()
    {
        return ComposerDataTreeFake.form("K", "DataCompositionSettingsComposer");
    }

    private static String createField(Form form, String name, String parent, String path)
    {
        return FormElementWriter.createMember(form, Kind.FIELD, name, parent, path,
            null, null, false, new String[1]);
    }
}

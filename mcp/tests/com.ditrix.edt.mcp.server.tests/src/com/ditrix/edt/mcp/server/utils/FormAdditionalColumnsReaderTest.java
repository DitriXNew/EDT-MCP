/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.FormAttributeColumn;

public class FormAdditionalColumnsReaderTest
{
    @Test
    public void testAdditionalSectionShowsOwnerPathNameTitleAndType() throws Exception
    {
        try (AdditionalColumnFixture f = new AdditionalColumnFixture())
        {
            FormAttributeColumn column = AdditionalColumnFixture.column("Extra", 4);
            column.getTitle().put("en", "Computed amount");
            column.setValueType(ComposerDataTreeFake.form("Amount", "Number").getAttributes().get(0).getValueType());
            f.entry("Object", "Goods").getColumns().add(column);
            String text = FormStructureReader.render("Catalog.Catalog.forms.ItemForm", f.form, "en");
            assertTrue(text, text.contains("## Additional columns"));
            assertTrue(text, text.contains("| Object | Object.Goods | Extra | Computed amount | Number |"));
            assertFalse(text, text.contains("## Attribute columns"));
        }
    }

    @Test
    public void testSectionDisappearsWhenTheLastEntryIsRemoved() throws Exception
    {
        try (AdditionalColumnFixture f = new AdditionalColumnFixture())
        {
            f.entry("Object", "Goods").getColumns().add(AdditionalColumnFixture.column("Extra", 4));
            assertTrue(render(f, 10).contains("## Additional columns"));
            f.owner.getAdditionalColumns().clear();
            assertFalse(render(f, 10).contains("## Additional columns"));
            assertFalse(FormStructureReader.render("CommonForm.F", null, "en").contains("## Additional columns"));
        }
    }

    @Test
    public void testAdditionalColumnsHaveTheirOwnCapAcrossEntries() throws Exception
    {
        try (AdditionalColumnFixture f = new AdditionalColumnFixture())
        {
            f.owner.getColumns().add(AdditionalColumnFixture.column("Direct", 4));
            f.entry("Object", "Goods").getColumns().add(AdditionalColumnFixture.column("FirstExtra", 5));
            f.entry("Object", "Items").getColumns().add(AdditionalColumnFixture.column("SecondExtra", 6));
            String text = render(f, 1);
            assertTrue(text, text.contains("## Additional columns"));
            assertTrue(text, text.contains("FirstExtra"));
            assertFalse(text, text.contains("SecondExtra"));
            assertTrue(text, text.contains("additional columns truncated: only the first 1"));
            assertTrue(text, text.contains("## Attribute columns"));
            assertTrue(text, text.contains("Direct"));
        }
    }

    private static String render(AdditionalColumnFixture f, int limit)
    {
        return FormStructureReader.render("Catalog.Catalog.forms.ItemForm", f.form, "en", limit);
    }
}

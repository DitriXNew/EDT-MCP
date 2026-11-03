/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.AdditionalColumnFixture;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Issue #737: create_metadata hands the parsed table token to the writer and reports the entry's path. */
public class CreateMetadataAdditionalColumnTest
{
    private AdditionalColumnFixture f;

    @Before
    public void setUp() throws Exception
    {
        f = new AdditionalColumnFixture();
    }

    @After
    public void tearDown()
    {
        f.close();
    }

    @Test
    public void testTheToolForwardsTheTableTokenAndEmitsTheCanonicalTablePath()
    {
        FormElementWriter.FormMemberRef ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "goods.Extra");
        String[] kind = new String[1];
        String[] tablePath = new String[1];
        // The owner rides in the parent slot exactly as createFormMember passes it; the binding is empty.
        String error = CreateMetadataTool.writeFormMember(f.form, ref, FormElementWriter.Kind.COLUMN,
            ref.ownerAttributeName, null, null, "en", "en", "Extra title", false, kind, tablePath);
        assertNull(error);
        assertEquals("Object.Goods", tablePath[0]);
        assertEquals(1, f.owner.getAdditionalColumns().size());
        assertTrue("the table token must not create a direct column", f.owner.getColumns().isEmpty());

        JsonObject result = JsonParser.parseString(CreateMetadataTool.formMemberResult(
            AdditionalColumnFixture.PREFIX + "Goods.Extra", kind[0], ref.name, true, tablePath[0]).toJson())
            .getAsJsonObject();
        assertEquals("Object.Goods", result.get("tablePath").getAsString());
        assertEquals("FormAttributeColumn", result.get("kind").getAsString());
    }

    @Test
    public void testOtherMembersCarryNoTablePath()
    {
        FormElementWriter.FormMemberRef ref = AdditionalColumnFixture.ref(
            "Catalog.Catalog.Form.ItemForm.Attribute.Total");
        String[] tablePath = new String[1];
        String error = CreateMetadataTool.writeFormMember(f.form, ref, FormElementWriter.Kind.ATTRIBUTE, null,
            null, null, null, "en", null, false, new String[1], tablePath);
        assertNull(error);
        assertNull(tablePath[0]);
        JsonObject result = JsonParser.parseString(CreateMetadataTool.formMemberResult(
            "Catalog.Catalog.Form.ItemForm.Attribute.Total", "FormAttribute", "Total", true, null).toJson())
            .getAsJsonObject();
        assertFalse(result.has("tablePath"));
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.AdditionalColumnFixture;
import com.google.gson.JsonParser;

public class AdditionalColumnToolContractTest
{
    @Test
    public void testCreateOutputSchemaDeclaresOptionalTablePath()
    {
        var schema = JsonParser.parseString(new CreateMetadataTool().getOutputSchema()).getAsJsonObject();
        var path = schema.getAsJsonObject("properties").get("tablePath");
        assertNotNull("create_metadata must declare its additional-column tablePath", path);
        assertEquals("string", path.getAsJsonObject().get("type").getAsString());
        for (var required : schema.getAsJsonArray("required"))
        {
            assertNotEquals("tablePath", required.getAsString());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAdditionalColumnErrorsAreScopedByTheOwningForm() throws Exception
    {
        String fqn = AdditionalColumnFixture.PREFIX + "Goods.Extra";
        var ref = AdditionalColumnFixture.ref(fqn);
        var deferred = new GetProjectErrorsTool.DeferredMember(fqn, fqn, ref);
        Method method = GetProjectErrorsTool.class.getDeclaredMethod("scopedByOwningForm",
            GetProjectErrorsTool.DeferredMember.class, List.class);
        method.setAccessible(true);
        List<String> result = (List<String>)method.invoke(null, deferred, List.of(fqn));
        assertEquals(List.of(fqn, "Catalog.Catalog.Form.ItemForm"), result);
    }
}

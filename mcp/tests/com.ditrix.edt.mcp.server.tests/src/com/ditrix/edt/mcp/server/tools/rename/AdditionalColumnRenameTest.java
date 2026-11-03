/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.FormAttributeColumn;
import com.ditrix.edt.mcp.server.utils.AdditionalColumnFixture;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;

public class AdditionalColumnRenameTest
{
    @Test
    public void testSiblingNameIsRefusedButDirectAndOtherEntryNamesAreAllowed() throws Exception
    {
        try (AdditionalColumnFixture f = new AdditionalColumnFixture())
        {
            FormAttributeColumn a = AdditionalColumnFixture.column("A", 4);
            var entry = f.entry("Object", "Goods");
            entry.getColumns().add(a);
            entry.getColumns().add(AdditionalColumnFixture.column("B", 5));
            f.owner.getColumns().add(AdditionalColumnFixture.column("Extra", 6));
            f.entry("Object", "Other").getColumns().add(AdditionalColumnFixture.column("OtherExtra", 7));
            var ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "Goods.A");
            assertNotNull(refusal(f, a, ref, "b"));
            assertNull(refusal(f, a, ref, "Extra"));
            assertNull(refusal(f, a, ref, "OtherExtra"));
            assertNull(refusal(f, a, ref, "a"));
        }
    }

    @Test
    public void testRenameAlsoRefusesRealTablePropertyAndRussianAlias() throws Exception
    {
        try (AdditionalColumnFixture f = new AdditionalColumnFixture())
        {
            FormAttributeColumn a = AdditionalColumnFixture.column("A", 4);
            f.entry("Object", "Goods").getColumns().add(a);
            var ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "Goods.A");
            for (String name : new String[] {"product", "\u0442\u043e\u0432\u0430\u0440"})
            {
                String error = refusal(f, a, ref, name);
                assertNotNull(error);
                assertTrue(error, error.contains(name));
                assertTrue(error, error.contains("Object.Goods"));
            }
        }
    }

    private static String refusal(AdditionalColumnFixture f, EObject member, FormMemberRef ref,
        String name) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod("duplicateNameRefusal",
            EObject.class, EObject.class, FormMemberRef.class, String.class);
        method.setAccessible(true);
        return (String)method.invoke(null, f.form, member, ref, name);
    }
}

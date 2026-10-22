/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.AdditionalColumnFixture;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;

/**
 * Issue #737 review, round 3: a nested additional-column address reaches delete with its whole table
 * path, and creation refuses it with an actionable message instead of writing under another table.
 */
public class AdditionalColumnNestedToolTest
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
    public void testANestedCreateIsRefusedAndWritesNothing()
    {
        FormElementWriter.FormMemberRef ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "Goods.Sub.Extra");
        String[] kind = new String[1];
        String[] tablePath = new String[1];
        String error = CreateMetadataTool.writeFormMember(f.form, ref, FormElementWriter.Kind.COLUMN,
            ref.ownerAttributeName, null, null, "en", "en", "Extra title", false, kind, tablePath);
        assertNotNull("a nested table path is not created by create_metadata", error); //$NON-NLS-1$
        assertTrue(error, error.contains("Nested table path 'Object.Goods.Sub'")); //$NON-NLS-1$
        assertTrue(error, error.contains("nested tables are not created by this tool")); //$NON-NLS-1$
        assertTrue(error, error.contains("'...Attribute.Object.Column.<TabularSection>.<Name>'")); //$NON-NLS-1$
        assertTrue("nothing may be written", f.owner.getAdditionalColumns().isEmpty()); //$NON-NLS-1$
        assertTrue(f.owner.getColumns().isEmpty());
        assertNull(tablePath[0]);
        assertNull(kind[0]);
    }

    @Test
    public void testDeleteChecksTheWholeNestedTablePathOnDisk() throws Exception
    {
        FormElementWriter.FormMemberRef ref = AdditionalColumnFixture.ref(AdditionalColumnFixture.PREFIX + "Sub.Deep.Extra");
        var check = new DeleteMetadataTool.AttributeDeleteOutcome().formCheck(ref);
        assertEquals("Object", check.get("attribute"));
        assertEquals("Extra", check.get("column"));
        assertEquals("Object.Sub.Deep", check.get("tablePath"));
        byte[] form = ("<form><attributes><name>Object</name>"
            + "<additionalColumns><tablePath><segments>Object</segments><segments>Sub</segments></tablePath>"
            + "<columns><name>Extra</name></columns></additionalColumns>"
            + "<additionalColumns><tablePath><segments>Object</segments><segments>Sub</segments>"
            + "<segments>Deep</segments></tablePath><columns><name>Other</name></columns></additionalColumns>"
            + "</attributes></form>").getBytes(StandardCharsets.UTF_8);
        String path = (String)check.get("tablePath");
        assertEquals("only the addressed nested entry is checked", DeleteMetadataTool.RegistrationState.ABSENT, //$NON-NLS-1$
            DeleteMetadataTool.formAttributeState(form, "Object", "Extra", path));
        assertEquals(DeleteMetadataTool.RegistrationState.PRESENT,
            DeleteMetadataTool.formAttributeState(form, "Object", "Other", path));
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.GuideLoader;

/**
 * Pins the reference grammar of the dcs ValueTypeSpec in both published copies (issue #920): every
 * {@code <Type>Ref} kind is accepted, and a literal {@code Ref} takes the object address or the reference
 * type name - so the formal spec must stay generic, with CatalogRef only as an example.
 */
public class DcsValueTypeSpecGuideTest
{
    private static final String GENERIC_REFERENCES =
        "{\"kind\":\"Ref\",\"ref\":\"<Type>.<Name>\"|\"<Type>Ref.<Name>\"} | {\"kind\":\"<Type>Ref\",\"ref\":\"<Name>\"}"; //$NON-NLS-1$

    private static final String EXAMPLE = "every `<Type>Ref` kind works the same way (DocumentRef, EnumRef"; //$NON-NLS-1$

    @Test
    public void testBundledGuideKeepsTheGenericReferenceKinds()
    {
        GuideLoader.clearCache();
        assertSpec(GuideLoader.load("dcs")); //$NON-NLS-1$
    }

    @Test
    public void testGeneratedDocumentKeepsTheSameGenericReferenceKinds() throws Exception
    {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); //$NON-NLS-1$
        while (root != null && !Files.isRegularFile(root.resolve("docs/tools/dcs.md"))) root = root.getParent(); //$NON-NLS-1$
        assertNotNull("repository root", root); //$NON-NLS-1$
        assertSpec(Files.readString(root.resolve("docs/tools/dcs.md"))); //$NON-NLS-1$
    }

    private static void assertSpec(String value)
    {
        assertTrue("the ValueTypeSpec must name the generic <Type>Ref reference kinds", //$NON-NLS-1$
            value.contains(GENERIC_REFERENCES));
        assertTrue("the guide must say every <Type>Ref kind works, with CatalogRef only as an example", //$NON-NLS-1$
            value.replaceAll("\\s+", " ").contains(EXAMPLE)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

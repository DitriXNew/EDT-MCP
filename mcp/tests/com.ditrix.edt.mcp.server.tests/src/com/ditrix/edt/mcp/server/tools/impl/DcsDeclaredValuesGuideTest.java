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

/** Pins the declared-value guard's documented contract in both published copies. */
public class DcsDeclaredValuesGuideTest
{
    private static final String TEXT = "A schema parameter's defaults (`values`) and available values "
        + "(`availableValues`), including available values on data-set and calculated fields, are also "
        + "dry-run through their declared-value writer using the containing parameter or field's "
        + "`valueType`. A `replace` is refused if that writer rejects a value or cannot rebuild it exactly.";

    @Test
    public void testBundledGuideExplainsTheDeclaredValueDryRun()
    {
        GuideLoader.clearCache();
        assertText(GuideLoader.load("dcs"));
    }

    @Test
    public void testGeneratedDocumentExplainsTheSameDeclaredValueDryRun() throws Exception
    {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/tools/dcs.md"))) root = root.getParent();
        assertNotNull("repository root", root);
        assertText(Files.readString(root.resolve("docs/tools/dcs.md")));
    }

    private static void assertText(String value)
    {
        String normalized = value.replaceAll("\\s+", " ");
        assertTrue("the declared defaults and available-values guard must be documented", normalized.contains(TEXT));
        assertFalse(normalized.contains("not checked by this refusal"));
    }
}

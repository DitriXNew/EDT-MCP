/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Pins the screenshot guide to the refusal actually emitted by TemplateScreenshotHelper. */
public class TextTemplateGuideTest
{
    @Test public void testScreenshotGuideUsesActualNonSpreadsheetRefusal() throws Exception
    {
        try (var stream = GetTemplateScreenshotTool.class.getResourceAsStream("/guides/get_template_screenshot.md"))
        {
            assertNotNull(stream);
            String guide = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(guide.contains("no spreadsheet editor page"));
            assertTrue(guide.contains("is not a SpreadsheetDocument template (its content is"));
            assertTrue(guide.contains("Only SpreadsheetDocument (print form) templates can be rendered to an image."));
        }
    }
}

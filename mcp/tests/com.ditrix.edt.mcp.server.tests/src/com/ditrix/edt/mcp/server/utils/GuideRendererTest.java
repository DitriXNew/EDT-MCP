/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tests for {@link GuideRenderer}.
 * <p>
 * The renderer is pure (no EDT/registry dependency): it formats a tool's static
 * contract (name + description + input schema + extended guide) into Markdown.
 * These tests drive it with anonymous {@link IMcpTool}s, so they cover the title,
 * the parameter table (including the enum "one of:" rendering and the required
 * marker), the optional "## Guide" section, the empty-schema fallback and the
 * malformed-schema graceful skip.
 */
public class GuideRendererTest
{
    /**
     * A minimal anonymous tool wired from constructor arguments, so each test can
     * declare exactly the name/description/schema/guide it needs.
     */
    private static IMcpTool tool(final String name, final String description, final String schema, final String guide)
    {
        return new IMcpTool()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public String getDescription()
            {
                return description;
            }

            @Override
            public String getInputSchema()
            {
                return schema;
            }

            @Override
            public String execute(Map<String, String> params)
            {
                return ""; //$NON-NLS-1$
            }

            @Override
            public String getGuide()
            {
                return guide;
            }
        };
    }

    @Test
    public void testRendersTitleDescriptionParamsAndGuide()
    {
        // Two properties: "mode" (required, with an enum) and "limit" (optional integer).
        String schema = "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"mode\":{\"type\":\"string\",\"description\":\"the mode\",\"enum\":[\"fast\",\"safe\"]}," //$NON-NLS-1$
            + "\"limit\":{\"type\":\"integer\",\"description\":\"max rows\"}" //$NON-NLS-1$
            + "},\"required\":[\"mode\"]}"; //$NON-NLS-1$
        String guide = "Worked example: call it twice."; //$NON-NLS-1$

        String md = GuideRenderer.render(tool("demo_tool", "A demo tool.", schema, guide)); //$NON-NLS-1$ //$NON-NLS-2$

        // Title and description.
        assertTrue(md.contains("# demo_tool")); //$NON-NLS-1$
        assertTrue(md.contains("A demo tool.")); //$NON-NLS-1$

        // Parameter table header and both parameter names.
        assertTrue(md.contains("## Parameters")); //$NON-NLS-1$
        assertTrue(md.contains("Parameter")); //$NON-NLS-1$
        assertTrue(md.contains("mode")); //$NON-NLS-1$
        assertTrue(md.contains("limit")); //$NON-NLS-1$

        // Enum is rendered as "one of: fast, safe".
        assertTrue(md.contains("one of: fast, safe")); //$NON-NLS-1$

        // Required marker for the required param, em dash for the optional one.
        assertTrue(md.contains("yes")); //$NON-NLS-1$
        assertTrue(md.contains("—")); //$NON-NLS-1$

        // Extended guide section.
        assertTrue(md.contains("## Guide")); //$NON-NLS-1$
        assertTrue(md.contains("Worked example: call it twice.")); //$NON-NLS-1$
    }

    @Test
    public void testNoPropertiesEmitsNoParameters()
    {
        String schema = "{\"type\":\"object\",\"properties\":{},\"required\":[]}"; //$NON-NLS-1$

        String md = GuideRenderer.render(tool("empty_tool", "Takes nothing.", schema, "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(md.contains("# empty_tool")); //$NON-NLS-1$
        assertTrue(md.contains("## Parameters")); //$NON-NLS-1$
        assertTrue(md.contains("No parameters.")); //$NON-NLS-1$
        // No table header row when there are no parameters.
        assertFalse(md.contains("| Parameter |")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyGuideOmitsGuideSection()
    {
        String schema = "{\"type\":\"object\",\"properties\":{},\"required\":[]}"; //$NON-NLS-1$

        String md = GuideRenderer.render(tool("no_guide_tool", "desc", schema, "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(md.contains("## Guide")); //$NON-NLS-1$
    }

    @Test
    public void testMalformedSchemaIsSkippedGracefully()
    {
        // A malformed schema must not throw: the table is skipped, the rest renders.
        String md = GuideRenderer.render(tool("bad_tool", "desc", "{not valid json", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(md.contains("# bad_tool")); //$NON-NLS-1$
        assertTrue(md.contains("## Parameters")); //$NON-NLS-1$
        assertTrue(md.contains("No parameters.")); //$NON-NLS-1$
    }

    @Test
    public void testTableCellWithPipeIsEscaped()
    {
        // A description containing a pipe must be escaped so it cannot break the table.
        String schema = "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
            + "\"q\":{\"type\":\"string\",\"description\":\"a|b\"}" //$NON-NLS-1$
            + "},\"required\":[]}"; //$NON-NLS-1$

        String md = GuideRenderer.render(tool("esc_tool", "desc", schema, "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(md.contains("a\\|b")); //$NON-NLS-1$
    }
}

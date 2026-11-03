/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideLoader;
import com.ditrix.edt.mcp.server.utils.OutputSizeGuard;

/**
 * A default {@code get_tool_guide} call returns the full rendered guide verbatim, and the protocol
 * handler then applies {@link OutputSizeGuard#cap} to that text with nothing wrapped around it. A guide
 * over {@link OutputSizeGuard#MAX_CONTENT_CHARS} therefore reaches the client cut short with an
 * {@code [OUTPUT TRUNCATED]} tail, losing its last sections. The generated docs never show this: they are
 * read through {@code resources/read}, which is not capped (issue #737 review).
 */
public class GuideOutputSizeTest
{
    /** What a full get_tool_guide answer adds around the rendered guide before the cap: nothing. */
    private static final int WRAPPER_OVERHEAD = 0;

    private final GetToolGuideTool guideTool = new GetToolGuideTool();
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        registry.clear();
        BuiltInToolRegistrar.registerAll(registry);
        GuideLoader.clearCache();
    }

    @After
    public void tearDown()
    {
        registry.clear();
        GuideLoader.clearCache();
    }

    private String fullGuide(String toolName)
    {
        Map<String, String> params = new HashMap<>();
        params.put("toolName", toolName); //$NON-NLS-1$
        return guideTool.execute(params);
    }

    @Test
    public void testTheModifyMetadataGuideFitsTheOutputCap()
    {
        String guide = fullGuide("modify_metadata"); //$NON-NLS-1$
        assertTrue("the real bundled guide must be rendered", guide.length() > 90_000);
        int budget = OutputSizeGuard.MAX_CONTENT_CHARS - WRAPPER_OVERHEAD;
        System.out.println("get_tool_guide('modify_metadata'): " + guide.length() + " of " + budget //$NON-NLS-1$ //$NON-NLS-2$
            + " characters"); //$NON-NLS-1$
        assertTrue("get_tool_guide('modify_metadata') renders " + guide.length() + " characters, over the " //$NON-NLS-1$ //$NON-NLS-2$
            + budget + "-character cap: the answer would end in [OUTPUT TRUNCATED]", guide.length() <= budget); //$NON-NLS-1$
        assertSame("the cap must leave the default answer untouched", guide, OutputSizeGuard.cap(guide));
    }

    @Test
    public void testNoRegisteredGuideIsTruncatedByDefault()
    {
        List<String> over = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String guide = fullGuide(tool.getName());
            if (guide.length() > OutputSizeGuard.MAX_CONTENT_CHARS - WRAPPER_OVERHEAD)
            {
                over.add(tool.getName() + '=' + guide.length());
            }
        }
        assertTrue("guides over the get_tool_guide output cap: " + over, over.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testEveryGuideListsItsAdditionalColumnsSectionInTheSummary()
    {
        for (String toolName : List.of("create_metadata", "modify_metadata", "delete_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "get_metadata_details", "rename_metadata_object")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Map<String, String> params = new HashMap<>();
            params.put("toolName", toolName); //$NON-NLS-1$
            params.put("mode", "summary"); //$NON-NLS-1$ //$NON-NLS-2$
            String summary = guideTool.execute(params);
            assertTrue(toolName + " summary must list the level-2 section: " + summary, //$NON-NLS-1$
                summary.contains("Additional columns")); //$NON-NLS-1$
            params.remove("mode"); //$NON-NLS-1$
            params.put("section", "Additional columns"); //$NON-NLS-1$ //$NON-NLS-2$
            String section = guideTool.execute(params);
            assertFalse(toolName + ": " + section, section.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(toolName + ": " + section, section.contains("Column.<TabularSection>.<Name>")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideLoader;
import com.ditrix.edt.mcp.server.utils.GuideSections;
import com.ditrix.edt.mcp.server.utils.OutputSizeGuard;

/** Keeps merge headroom without losing the instructions reached through a shorter introduction. */
public class ModifyMetadataGuideHeadroomTest
{
    @Before
    public void setUp()
    {
        McpToolRegistry.getInstance().clear();
        GuideLoader.clearCache();
        McpToolRegistry.getInstance().register(new ModifyMetadataTool());
    }

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
        GuideLoader.clearCache();
    }

    @Test
    public void fullGuideLeavesRoomForConcurrentGuideChanges()
    {
        String guide = fullGuide();
        System.out.println("MODIFY_METADATA_HEADROOM_GUIDE_CHARS=" + guide.length());
        assertTrue("must measure the actual full guide", guide.length() > 90_000);
        int budget = OutputSizeGuard.MAX_CONTENT_CHARS - 300;
        assertTrue("full modify_metadata guide must leave 300 characters of merge headroom: "
            + guide.length() + " > " + budget, guide.length() <= budget);
    }

    @Test
    public void introductionLinksToTheCompleteMembershipInstructions()
    {
        String guide = fullGuide();
        String introduction = GuideLoader.load("modify_metadata").lines().findFirst().orElseThrow();
        Matcher pointer = Pattern.compile("\\[Editing a membership list \\(content\\)\\]\\((#[^)]+)\\)")
            .matcher(introduction);
        assertTrue("the introduction must link to the membership instructions", pointer.find());
        GuideSections.Section section = GuideSections.parse(guide).find(pointer.group(1));
        assertNotNull("the membership link must resolve: " + pointer.group(1), section);
        assertEquals("Editing a membership list (content)", section.heading());
        String membership = section.text();
        for (String kind : List.of("CommonAttribute", "ExchangePlan", "Catalog", "Document", "Subsystem"))
        {
            assertTrue("the linked section must document " + kind,
                membership.contains("**" + kind + "** (`" + kind + ".<Name>`"));
        }
        assertTrue("nested subsystem FQNs must remain documented",
            membership.contains("`Subsystem.<Parent>.Subsystem.<Child>`"));
        assertTrue("membership edits must retain their dedicated payload constraint",
            membership.contains("`content` payload instead of `properties`"));
    }

    private static String fullGuide()
    {
        // Match StyleColorGuideTest: no section, query or mode on the actual execute path.
        return new GetToolGuideTool().execute(Map.of("toolName", "modify_metadata"));
    }
}

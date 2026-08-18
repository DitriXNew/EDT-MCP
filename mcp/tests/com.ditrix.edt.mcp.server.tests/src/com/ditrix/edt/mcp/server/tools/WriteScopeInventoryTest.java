/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;

/**
 * Ratchet: every tool that inherits the post-write export barrier must have had its write scope
 * thought about (issue #408).
 * <p>
 * The barrier waits for what the call says it wrote. A tool that says nothing keeps the pre-#408
 * behaviour - wait for the project it was asked about - which is safe but is a GUESS, and the whole
 * point of #408 is that guessing produced five different wrong answers in five tools. This test
 * cannot check that a tool declares correctly; what it can check is that no writing tool arrives
 * without anybody deciding, which is the failure mode that actually happened.
 * <p>
 * Deliberately a registry sweep rather than a source scan. This repository already has source
 * scanners, and they document what they cannot see - a token in a comment, a dead branch, an
 * indirect superclass. A ratchet that a comment can satisfy proves nothing; a table that a new
 * subclass is missing from cannot be satisfied by accident.
 * <p>
 * <b>What it does NOT catch</b>, so nobody reads it as more than it is: it cannot tell whether an
 * entry declares CORRECTLY - only that somebody decided - and it sees only tools that inherit the
 * barrier. A tool that implements {@code IMcpTool} directly and writes anyway is invisible to it,
 * and one such tool already exists ({@code build_external_objects}); those tools have no export
 * barrier at all, which is a separate gap and not one this table can close.
 */
public class WriteScopeInventoryTest
{
    /**
     * How each tool inheriting the barrier answers "where did I write", by fully qualified class
     * name. Every entry is a decision somebody made, and adding a subclass without adding it here
     * fails the build.
     */
    private static final Set<String> CLASSIFIED = new HashSet<>(Arrays.asList(
        // DECLARES by exporting: every export goes through BmTransactions.forceExportToDisk, which
        // records the project - so these declare simply by doing the write.
        "com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool", //$NON-NLS-1$
        "com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool", //$NON-NLS-1$
        // DECLARES by exporting, plus an explicit statement for the branches that write without
        // submitting an export of their own, or that succeed while queuing nothing at all.
        "com.ditrix.edt.mcp.server.tools.impl.AdoptMetadataObjectTool", //$NON-NLS-1$
        "com.ditrix.edt.mcp.server.tools.impl.ResyncToDiskTool", //$NON-NLS-1$
        "com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool", //$NON-NLS-1$
        // CANNOT DECLARE: EDT's quick-fix extension point reports nothing about what the fix
        // touched, so this one states that outright and names what to wait for instead. It is the
        // only member of this class, and a second one should be argued for, not assumed.
        "com.ditrix.edt.mcp.server.tools.impl.ApplyQuickFixTool")); //$NON-NLS-1$

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void everyToolThatInheritsTheExportBarrierIsClassified()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> unclassified = new ArrayList<>();
        List<String> inheritors = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (!AbstractMetadataWriteTool.class.isAssignableFrom(tool.getClass()))
            {
                continue;
            }
            // By FULLY QUALIFIED name: a later subclass that happens to share a simple name with
            // a classified one would otherwise be counted as decided about, which is the one way
            // this table could be satisfied by accident.
            String className = tool.getClass().getName();
            inheritors.add(className);
            if (!CLASSIFIED.contains(className))
            {
                unclassified.add(className);
            }
        }

        assertTrue("the sweep found no write tools at all, so it would pass vacuously", //$NON-NLS-1$
            inheritors.size() >= 6);
        assertTrue("Tools inheriting the export barrier with no decision about their write scope: " //$NON-NLS-1$
            + unclassified + ". Decide what the tool tells the barrier - it declares by calling " //$NON-NLS-1$
            + "BmTransactions.forceExportToDisk, or explicitly via WriteScope (queuedNothing / " //$NON-NLS-1$
            + "wrote / cascadedInto / undeterminable) - then add it to CLASSIFIED with the reason.", //$NON-NLS-1$
            unclassified.isEmpty());
    }

    @Test
    public void theClassificationTableNamesOnlyRealWriteTools()
    {
        // The other direction, so the table cannot rot into a list of tools that no longer exist:
        // a stale entry would silently keep the ratchet from firing if the name were reused.
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        Set<String> registered = new HashSet<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (AbstractMetadataWriteTool.class.isAssignableFrom(tool.getClass()))
            {
                registered.add(tool.getClass().getName());
            }
        }

        List<String> stale = new ArrayList<>(CLASSIFIED);
        stale.removeAll(registered);
        assertTrue("Classified names that are not registered write tools any more: " + stale, //$NON-NLS-1$
            stale.isEmpty());
    }
}

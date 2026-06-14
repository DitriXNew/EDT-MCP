/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/**
 * Adoption ratchet for CLAUDE.md rule #4: project/workspace resolution must go
 * through the shared {@code ProjectContext}, NOT a fresh
 * {@code ResourcesPlugin.getWorkspace()...} copy. The refactor is mid-flight, so a
 * fixed set of tools still hand-roll it; this test pins that set as an allow-list
 * and FAILS the build if any OTHER {@code tools/impl} tool starts referencing
 * {@code ResourcesPlugin} (a new copy), or if an allow-listed tool stops (it has
 * been migrated and must leave the list — the list may only SHRINK toward zero).
 * <p>
 * Detection is a bytecode scan: a class that calls {@code ResourcesPlugin.getWorkspace()}
 * carries the constant-pool reference {@code org/eclipse/core/resources/ResourcesPlugin},
 * so the simple substring {@code "ResourcesPlugin"} in the class file is a reliable
 * signal (an unused import leaves no bytecode trace, so this does not false-positive
 * on imports). This is the rule-#4 analogue of {@link BuiltInToolTestCoverageTest}.
 * <p>
 * Scope note: only the tool's own {@code <SimpleName>.class} is scanned. A tool that
 * used {@code ResourcesPlugin} EXCLUSIVELY inside an anonymous inner class (a separate
 * {@code <SimpleName>$1.class}) would slip past — none do today (all offenders call it
 * directly), and a source review / grep is the backstop for that corner.
 */
public class ProjectContextAdoptionRatchetTest
{
    /** Marker of a hand-rolled workspace lookup, in JVM-internal (slash) form. */
    private static final String MARKER = "ResourcesPlugin"; //$NON-NLS-1$

    /**
     * Simple names of {@code tools/impl} tools that STILL hand-roll
     * {@code ResourcesPlugin.getWorkspace()} pending migration to {@code ProjectContext}.
     * RATCHET: this list may only SHRINK. A new tool that references
     * {@code ResourcesPlugin} fails {@link #noNewResourcesPluginCopiesInToolsImpl};
     * an entry that has been migrated (no longer references it) fails
     * {@link #allowListHasNoStaleOrAlreadyMigratedEntries}. As tools move to
     * {@code ProjectContext}, delete them from here until the set is EMPTY.
     */
    private static final Set<String> KNOWN_DIRECT_WORKSPACE_ACCESS = new HashSet<>(Arrays.asList(
        "GetConfigurationPropertiesTool", //$NON-NLS-1$
        "GetModuleStructureTool", //$NON-NLS-1$
        "ImportConfigurationFromXmlTool", //$NON-NLS-1$
        "ListProjectsTool")); //$NON-NLS-1$

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void noNewResourcesPluginCopiesInToolsImpl()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            Class<?> cls = tool.getClass();
            if (!cls.getName().contains(".tools.impl.")) //$NON-NLS-1$
            {
                continue; // rule #4 scopes to tools/impl
            }
            String simpleName = cls.getSimpleName();
            if (referencesResourcesPlugin(cls) && !KNOWN_DIRECT_WORKSPACE_ACCESS.contains(simpleName))
            {
                offenders.add(simpleName);
            }
        }
        assertTrue("tools/impl tools referencing ResourcesPlugin.getWorkspace() but NOT on the " //$NON-NLS-1$
            + "migration allow-list (rule #4 - resolve via the shared ProjectContext, do not add a " //$NON-NLS-1$
            + "new ResourcesPlugin.getWorkspace() copy): " + offenders, //$NON-NLS-1$
            offenders.isEmpty());
    }

    @Test
    public void allowListHasNoStaleOrAlreadyMigratedEntries()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        Set<String> registeredImplSimpleNames = new HashSet<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            Class<?> cls = tool.getClass();
            if (cls.getName().contains(".tools.impl.")) //$NON-NLS-1$
            {
                registeredImplSimpleNames.add(cls.getSimpleName());
            }
        }

        // (1) Every allow-list entry must be a real registered tools/impl tool.
        List<String> unknown = new ArrayList<>();
        for (String name : KNOWN_DIRECT_WORKSPACE_ACCESS)
        {
            if (!registeredImplSimpleNames.contains(name))
            {
                unknown.add(name);
            }
        }
        assertTrue("allow-list names that are not registered tools/impl tools (typo / removed tool?): " //$NON-NLS-1$
            + unknown, unknown.isEmpty());

        // (2) Tighten the ratchet: an allow-listed tool that NO LONGER references
        // ResourcesPlugin has been migrated and must be removed from the list.
        List<String> migrated = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            Class<?> cls = tool.getClass();
            String simpleName = cls.getSimpleName();
            if (KNOWN_DIRECT_WORKSPACE_ACCESS.contains(simpleName) && !referencesResourcesPlugin(cls))
            {
                migrated.add(simpleName);
            }
        }
        assertTrue("allow-list entries that NO LONGER reference ResourcesPlugin (migrated to " //$NON-NLS-1$
            + "ProjectContext) - remove them to tighten the ratchet toward zero: " + migrated, //$NON-NLS-1$
            migrated.isEmpty());
    }

    /** True if the class file carries a reference to ResourcesPlugin (a getWorkspace() call). */
    private static boolean referencesResourcesPlugin(Class<?> cls)
    {
        String resource = cls.getSimpleName() + ".class"; //$NON-NLS-1$
        try (InputStream in = cls.getResourceAsStream(resource))
        {
            if (in == null)
            {
                fail("cannot read bytecode for " + cls.getName() //$NON-NLS-1$
                    + " (getResourceAsStream returned null) - the ratchet cannot verify rule #4"); //$NON-NLS-1$
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1)
            {
                buf.write(chunk, 0, n);
            }
            // ISO-8859-1 maps each byte to a char 1:1, so an ASCII substring search over
            // the decoded class file finds the UTF-8 constant-pool entry verbatim.
            String body = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
            return body.contains(MARKER);
        }
        catch (IOException e)
        {
            fail("failed reading bytecode for " + cls.getName() + ": " + e.getMessage()); //$NON-NLS-1$
            return false; // unreachable
        }
    }
}

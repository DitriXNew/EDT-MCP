/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/** Project resolution in tools, including nested classes, must use ProjectContext. */
public class ProjectContextAdoptionRatchetTest
{
    /** Marker of a hand-rolled workspace lookup, in JVM-internal (slash) form. */
    private static final String MARKER = "ResourcesPlugin"; //$NON-NLS-1$

    private static final Set<String> KNOWN_DIRECT_WORKSPACE_ACCESS = Set.of();

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
        var bundle = org.osgi.framework.FrameworkUtil.getBundle(cls);
        String directory = cls.getPackageName().replace('.', '/') + "/";
        var entries = bundle.getEntryPaths(directory);
        org.junit.Assert.assertNotNull("missing tool package " + directory, entries);
        boolean scanned = false;
        boolean found = false;
        while (entries.hasMoreElements())
        {
            String entry = entries.nextElement();
            String filename = entry.substring(entry.lastIndexOf('/') + 1);
            if (!filename.equals(cls.getSimpleName() + ".class")
                && !(filename.startsWith(cls.getSimpleName() + "$") && filename.endsWith(".class")))
            {
                continue;
            }
            scanned = true;
            try (InputStream in = bundle.getEntry(entry).openStream())
            {
                found |= new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains(MARKER);
            }
            catch (IOException e)
            {
                throw new AssertionError("cannot scan " + entry, e);
            }
        }
        org.junit.Assert.assertTrue("must scan the production tool " + cls.getName(), scanned);
        return found;
    }
}

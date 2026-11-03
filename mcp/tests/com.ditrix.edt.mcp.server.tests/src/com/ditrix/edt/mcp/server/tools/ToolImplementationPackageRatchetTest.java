/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.tools.impl.CreateInfobaseTool;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;

/** Every top-level production class under tools/impl must implement IMcpTool, including inherited implementations. */
public class ToolImplementationPackageRatchetTest
{
    @Test
    public void everyImplementationClassIsAToolEvenWhenNotRegistered() throws Exception
    {
        Bundle production = FrameworkUtil.getBundle(IMcpTool.class);
        assertNotNull("the production bundle must be present", production);
        var classes = new ArrayList<Class<?>>();
        collect(production, "com/ditrix/edt/mcp/server/tools/impl/", classes);
        assertTrue("positive control: scan the production classes rather than an empty directory", classes.size() > 50);
        assertTrue(classes.contains(CreateInfobaseTool.class));
        assertEquals("Move helpers to utils/ or abstract tool bases to tools/base/", List.of(), violations(classes));
    }

    @Test
    public void rejectsAHelperAndAcceptsAnInheritedToolImplementation()
    {
        assertEquals(List.of(StandaloneServerSupport.class.getName()),
            violations(List.of(StandaloneServerSupport.class, InheritedTool.class)));
    }

    private static List<String> violations(List<Class<?>> classes)
    {
        return classes.stream().filter(type -> !IMcpTool.class.isAssignableFrom(type))
            .map(Class::getName).sorted().toList();
    }

    private static void collect(Bundle production, String directory, List<Class<?>> classes) throws Exception
    {
        // getEntryPaths scans the host bundle alone; findEntries would include this test fragment's classes.
        var entries = production.getEntryPaths(directory);
        assertNotNull("missing production package: " + directory, entries);
        while (entries.hasMoreElements())
        {
            String entry = entries.nextElement();
            if (entry.endsWith("/"))
            {
                collect(production, entry, classes);
            }
            else if (entry.endsWith(".class") && !entry.contains("$") && !entry.endsWith("package-info.class"))
            {
                String name = entry.replaceFirst("^/", "").replace('/', '.');
                classes.add(production.loadClass(name.substring(0, name.length() - ".class".length())));
            }
        }
    }

    private static final class InheritedTool extends CreateInfobaseTool { }
}

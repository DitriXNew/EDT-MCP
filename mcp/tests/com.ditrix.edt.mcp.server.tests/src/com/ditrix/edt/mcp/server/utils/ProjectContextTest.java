/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ProjectContext}.
 * <p>
 * Covers the {@code null}/empty short-circuit, which resolves without touching
 * the workspace and is therefore reachable headlessly. Resolving a real project
 * name needs a live workspace (the EDT workbench) and is covered by the E2E
 * suite via the tools that use this resolver.
 */
public class ProjectContextTest
{
    @Test
    public void testNullNameIsEmptyContext()
    {
        ProjectContext ctx = ProjectContext.of(null);
        assertNull(ctx.project());
        assertNull(ctx.name());
        assertFalse(ctx.exists());
        assertFalse(ctx.isOpen());
    }

    @Test
    public void testEmptyNameIsEmptyContext()
    {
        ProjectContext ctx = ProjectContext.of(""); //$NON-NLS-1$
        assertNull(ctx.project());
        assertEquals("", ctx.name()); //$NON-NLS-1$
        assertFalse(ctx.exists());
        assertFalse(ctx.isOpen());
    }

    @Test
    public void testExistsAndIsOpenAreFalseWithoutProject()
    {
        // exists()/isOpen() must never NPE on the empty context.
        assertFalse(ProjectContext.of(null).exists());
        assertFalse(ProjectContext.of(null).isOpen());
    }

    /**
     * A project with NO configuration is refused differently depending on WHY it has none: an
     * external-objects project has none by construction, and telling the caller that (plus which
     * tools do work there) is the difference between "something broke" and "ask a different
     * question" - issue #309.
     */
    @Test
    public void testNoConfigurationMessageNamesTheExternalObjectsKind()
    {
        String external = ProjectContext.noConfigurationMessage("Reports", true); //$NON-NLS-1$
        assertTrue(external, external.contains("Reports")); //$NON-NLS-1$
        assertTrue(external, external.contains("EXTERNAL-OBJECTS")); //$NON-NLS-1$
        assertTrue(external, external.contains("get_metadata_objects")); //$NON-NLS-1$
    }

    @Test
    public void testNoConfigurationMessageStaysGenericForAConfigurationProject()
    {
        String generic = ProjectContext.noConfigurationMessage("MyConfig", false); //$NON-NLS-1$
        assertEquals("Could not get configuration for project: MyConfig", generic); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

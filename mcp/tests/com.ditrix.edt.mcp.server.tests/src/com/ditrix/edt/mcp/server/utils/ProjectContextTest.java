/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

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
}

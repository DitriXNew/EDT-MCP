/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests for {@link ProjectStateChecker#buildingErrorOrNull(String)}.
 * <p>
 * Covers the {@code null}/empty short-circuit, which resolves WITHOUT touching the
 * workspace (the same constraint {@code ProjectContextTest} works under). The point of
 * {@code buildingErrorOrNull} is that it refuses ONLY the transient BUILDING state: a
 * null/empty/missing project must NOT yield a misleading "building, retry" message but
 * fall through to the caller's required-argument / value-naming error. The BUILDING and
 * project-not-found branches need a live workspace and are covered by the e2e suite
 * (the metadata write/rename "nonexistent project" tests assert the value-naming error,
 * not a build message).
 */
public class ProjectStateCheckerTest
{
    @Test
    public void buildingErrorOrNullIsNullForNullName()
    {
        // null name short-circuits to null before any workspace access.
        assertNull(ProjectStateChecker.buildingErrorOrNull((String) null));
    }

    @Test
    public void buildingErrorOrNullIsNullForEmptyName()
    {
        // empty name short-circuits to null before any workspace access.
        assertNull(ProjectStateChecker.buildingErrorOrNull(""));
    }
}

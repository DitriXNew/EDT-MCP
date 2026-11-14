/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * #922 (Codex P2 on #928): the reload wait runs outside the branch lock, so another checkout can move the repository
 * meanwhile; the switch must then not answer success for a branch the repository is no longer on.
 */
public class SwitchGitBranchMovedAwayTest
{
    @Test
    public void theMovedAwayErrorNamesBothBranchesAndTheWayOut()
    {
        String error = SwitchGitBranchTool.movedAwayError("feature", "master"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(error, error.contains("\"success\":false") || error.contains("\"success\": false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains("'feature'")); //$NON-NLS-1$
        assertTrue(error, error.contains("on 'master' now")); //$NON-NLS-1$
        assertTrue(error, error.contains("list_git_branches")); //$NON-NLS-1$
    }
}

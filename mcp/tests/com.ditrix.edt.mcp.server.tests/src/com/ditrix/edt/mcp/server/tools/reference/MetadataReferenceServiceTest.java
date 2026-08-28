/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Tests the project qualification of BSL reference rows. An ADOPTED copy in an extension keeps the
 * base module's relative path, so once a search widens its target set to adopted copies, a base row
 * and an extension row can share the whole deduplication key (relative path plus line) and silently
 * merge - and even with different lines they would render as one module.
 */
public class MetadataReferenceServiceTest
{
    @Test
    public void projectNameIsTakenFromAPlatformResourcePath()
    {
        assertEquals("Base.tests", MetadataReferenceService.extractProjectName( //$NON-NLS-1$
            "/Base.tests/src/CommonModules/Calc/Module.bsl")); //$NON-NLS-1$
        assertEquals("Base", MetadataReferenceService.extractProjectName( //$NON-NLS-1$
            "/Base/src/CommonModules/Calc/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void pathWithoutAProjectSegmentYieldsNoName()
    {
        assertNull(MetadataReferenceService.extractProjectName(null));
        assertNull(MetadataReferenceService.extractProjectName(
            "CommonModules/Calc/Module.bsl")); //$NON-NLS-1$
        assertNull(MetadataReferenceService.extractProjectName(
            "/src/CommonModules/Calc/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void aRowFromTheSearchedProjectIsNotQualified()
    {
        assertEquals("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
            MetadataReferenceService.qualifyWithProject("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
                "Base", "Base")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRowFromAnotherProjectKeepsThatProjectInItsIdentity()
    {
        // Same relative path and the same line in both projects: without the prefix these two share
        // the entire deduplication key and one of them disappears.
        assertEquals("Base.tests/CommonModules/Calc/Module.bsl", //$NON-NLS-1$
            MetadataReferenceService.qualifyWithProject("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
                "Base.tests", "Base")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnknownProjectLeavesTheRowUnchanged()
    {
        assertEquals("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
            MetadataReferenceService.qualifyWithProject("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
                null, "Base")); //$NON-NLS-1$
        assertEquals("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
            MetadataReferenceService.qualifyWithProject("CommonModules/Calc/Module.bsl", //$NON-NLS-1$
                "Base.tests", null)); //$NON-NLS-1$
    }
}

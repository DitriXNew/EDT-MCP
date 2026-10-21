/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

/** Configuration refusals expose a stopped context without changing the external-objects contract. */
public class ProjectContextNoConfigurationMessageTest
{
    @Test
    public void aConfigurationRefusalWithoutAReasonStaysByteIdentical()
    {
        assertEquals("Could not get configuration for project: X",
            ProjectContext.noConfigurationMessage("X", false, null));
    }

    @Test
    public void aConfigurationRefusalAppendsTheSharedReason()
    {
        assertEquals("Could not get configuration for project: X. <reason>",
            ProjectContext.noConfigurationMessage("X", false, "<reason>"));
    }

    @Test
    public void anExternalObjectsRefusalIgnoresTheContextReason()
    {
        assertEquals(ProjectContext.noConfigurationMessage("Reports", true),
            ProjectContext.noConfigurationMessage("Reports", true, "<reason>"));
    }

    @Test
    public void anExternalScopeNeverReadsTheContext()
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenThrow(new AssertionError("External scopes must not inspect context state"));
        MetadataScope scope = MetadataScope.ofExternalObjectProject(project, null, null);
        assertEquals(ProjectContext.noConfigurationMessage("Reports", true),
            ProjectContext.noConfigurationMessage("Reports", project, scope));
    }

    @Test
    public void aConfigurationScopeWithoutAContextReasonKeepsTheGenericRefusal()
    {
        IProject missing = mock(IProject.class);
        assertEquals("Could not get configuration for project: Demo",
            ProjectContext.noConfigurationMessage("Demo", missing, MetadataScope.ofConfiguration(null)));
    }

}

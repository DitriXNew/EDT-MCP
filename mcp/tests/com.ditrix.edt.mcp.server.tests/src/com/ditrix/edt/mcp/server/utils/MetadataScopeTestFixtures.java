/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Test-only access to external-object scopes that normally require a live EDT project. */
public final class MetadataScopeTestFixtures
{
    private MetadataScopeTestFixtures()
    {
        // Prevent instantiation because the fixture has no state.
    }

    /** Builds a started external-object scope containing the supplied roots. */
    public static MetadataScope externalObjects(MdObject... objects)
    {
        IExternalObjectProject project = mock(IExternalObjectProject.class);
        when(project.getExternalObjects()).thenReturn(Arrays.asList(objects));
        return MetadataScope.ofExternalObjectProject(null, null, project);
    }

    /** Builds an external-object scope whose EDT lifecycle never exposed its roots. */
    public static MetadataScope unavailableExternalObjects()
    {
        return MetadataScope.ofExternalObjectProject(null, null, null);
    }
}

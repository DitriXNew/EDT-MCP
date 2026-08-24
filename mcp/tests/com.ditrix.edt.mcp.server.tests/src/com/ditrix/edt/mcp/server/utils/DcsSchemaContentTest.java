/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.platform.version.Version;

/** Readiness tests for DCS content materialization services. */
public class DcsSchemaContentTest
{
    @Test
    public void testNullPlatformVersionIsNotReadyAndTellsCallerToRetry()
    {
        IV8Project project = Mockito.mock(IV8Project.class);
        Mockito.doReturn(null).when(project).getVersion();

        DcsSchemaContent.Services services = DcsSchemaContent.resolveServices(
            Mockito.mock(IBmModel.class), Mockito.mock(ITopObjectFqnGenerator.class),
            Mockito.mock(IModelObjectFactory.class), project);

        assertFalse(services.isSuccess());
        assertTrue(services.error(), services.error().contains("platform version")); //$NON-NLS-1$
        assertTrue(services.error(), services.error().contains("finish loading")); //$NON-NLS-1$
        assertTrue(services.error(), services.error().contains("retry")); //$NON-NLS-1$
    }

    @Test
    public void testResolvedPlatformVersionIsRetainedByReadyServices()
    {
        IV8Project project = Mockito.mock(IV8Project.class);
        Mockito.doReturn(Version.LATEST).when(project).getVersion();

        DcsSchemaContent.Services services = DcsSchemaContent.resolveServices(
            Mockito.mock(IBmModel.class), Mockito.mock(ITopObjectFqnGenerator.class),
            Mockito.mock(IModelObjectFactory.class), project);

        assertTrue(services.error(), services.isSuccess());
        assertSame(Version.LATEST, services.version());
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Pins the original content-writer service contract, including refusal order and wording. */
public class MetadataWriteServicesTest
{
    private Field plugin;
    private Object previous;
    private Activator activator;
    private IBmModelManager models;
    private IV8ProjectManager projects;
    private IModelObjectFactory factory;

    @Before public void setUp() throws Exception
    {
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previous = plugin.get(null);
        activator = mock(Activator.class);
        models = mock(IBmModelManager.class);
        projects = mock(IV8ProjectManager.class);
        factory = mock(IModelObjectFactory.class);
        plugin.set(null, activator);
    }

    @After public void tearDown() throws Exception { plugin.set(null, previous); }

    @Test public void missingServicesAreCheckedBeforeProjectAndModel() throws Exception
    {
        for (int missing = 0; missing < 3; missing++)
        {
            when(activator.getBmModelManager()).thenReturn(missing == 0 ? null : models);
            when(activator.getModelObjectFactory()).thenReturn(missing == 1 ? null : factory);
            when(activator.getV8ProjectManager()).thenReturn(missing == 2 ? null : projects);
            MetadataWriteServices result = MetadataWriteServices.resolve(mock(IProject.class));
            assertEquals(ToolResult.error("The metadata write services are not available "
                + "(IBmModelManager / IModelObjectFactory / IV8ProjectManager). "
                + "Ensure the EDT workbench is fully started, then retry.").toJson(), result.error);
            assertNull(result.model);
        }
        verifyZeroInteractions(models, projects);
    }

    @Test public void projectAndModelRefusalsKeepBothLanguageNames() throws Exception
    {
        services();
        for (String name : new String[] {"Configuration", "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f"})
        {
            IProject project = mock(IProject.class);
            when(project.getName()).thenReturn(name);
            assertEquals(ToolResult.error("No 1C project for: " + name).toJson(), MetadataWriteServices.resolve(project).error);
            IV8Project v8 = mock(IV8Project.class);
            when(projects.getProject(project)).thenReturn(v8);
            assertEquals(ToolResult.error("BM model not available for project: " + name).toJson(),
                MetadataWriteServices.resolve(project).error);
        }
    }

    @Test public void readyServicesRetainFactoryModelAndActualPlatformVersion() throws Exception
    {
        services();
        IProject project = mock(IProject.class);
        IV8Project v8 = mock(IV8Project.class);
        IBmModel model = mock(IBmModel.class);
        when(projects.getProject(project)).thenReturn(v8);
        when(models.getModel(project)).thenReturn(model);
        when(v8.getVersion()).thenReturn(Version.V8_3_24);
        MetadataWriteServices result = MetadataWriteServices.resolve(project);
        assertNull(result.error);
        assertSame(model, result.model);
        assertSame(factory, result.factory);
        assertSame(Version.V8_3_24, result.version);
    }

    private void services()
    {
        when(activator.getBmModelManager()).thenReturn(models);
        when(activator.getModelObjectFactory()).thenReturn(factory);
        when(activator.getV8ProjectManager()).thenReturn(projects);
    }
}

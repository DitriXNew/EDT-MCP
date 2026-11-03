/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static com.ditrix.edt.mcp.server.utils.AutomatedTestingTestSupport.assertTestingSchema;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Drives execute through save so its response confirms the persisted testing attributes. */
public class CreateLaunchConfigTestingOutputTest
{
    private final List<Runnable> restores = new ArrayList<>();
    private ILaunchConfigurationWorkingCopy copy;

    @Before
    public void setUp() throws Exception
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature")).thenReturn(true);
        IWorkspace workspace = (IWorkspace)mock(Class.forName("org.eclipse.core.internal.resources.Workspace"));
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(workspace.getRoot()).thenReturn(root);
        when(root.getProject("TestingProject")).thenReturn(project);
        Field customizerField = ResourcesPlugin.class.getDeclaredField("workspaceInitCustomizer");
        customizerField.setAccessible(true);
        Object customizer = customizerField.get(ResourcesPlugin.getPlugin());
        replace(customizer, customizer.getClass().getDeclaredField("workspace"), workspace);

        Activator activator = mock(Activator.class);
        IApplicationManager applications = mock(IApplicationManager.class);
        when(applications.getApplication(project, "TestingApplication"))
            .thenReturn(Optional.of(mock(IApplication.class)));
        when(activator.getApplicationManager()).thenReturn(applications);
        replace(null, Activator.class.getDeclaredField("plugin"), activator);

        ILaunchManager manager = mock(ILaunchManager.class);
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(manager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID)).thenReturn(type);
        when(manager.getLaunchConfigurations()).thenReturn(new ILaunchConfiguration[0]);
        when(manager.getLaunchConfigurations(type)).thenReturn(new ILaunchConfiguration[0]);
        copy = mock(ILaunchConfigurationWorkingCopy.class);
        when(type.newInstance(null, "Testing client")).thenReturn(copy);
        ILaunchConfiguration saved = mock(ILaunchConfiguration.class);
        when(saved.getName()).thenReturn("Testing client");
        when(copy.doSave()).thenReturn(saved);
        DebugPlugin debugPlugin = mock(DebugPlugin.class);
        when(debugPlugin.getLaunchManager()).thenReturn(manager);
        Field singleton = null;
        for (Field field : DebugPlugin.class.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == DebugPlugin.class)
            {
                singleton = field;
                break;
            }
        }
        if (singleton == null)
        {
            throw new AssertionError("The headless DebugPlugin singleton must be replaceable");
        }
        replace(null, singleton, debugPlugin);
    }

    @After
    public void tearDown()
    {
        for (int index = restores.size() - 1; index >= 0; index--)
        {
            restores.get(index).run();
        }
    }

    @Test
    public void testOutputSchemaDeclaresNativeModeEnumAndIntegerPort()
    {
        assertTestingSchema(new CreateLaunchConfigTool().getOutputSchema());
    }

    @Test
    public void testCreatedClientEchoesNormalizedModeAndExplicitPort() throws Exception
    {
        JsonObject result = create("testclient", "1540");
        assertEquals("TESTCLIENT", result.get("automatedTestingMode").getAsString());
        assertEquals(1540, result.get("automatedTestingPort").getAsInt());
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_MODE, "TESTCLIENT");
        verify(copy).setAttribute(LaunchConfigUtils.ATTR_AUTOMATED_TESTING_PORT_NUMBER, 1540);
    }

    @Test
    public void testCreatedClientWithoutPortEchoesModeOnly() throws Exception
    {
        JsonObject result = create("TESTCLIENT", null);
        assertEquals("TESTCLIENT", result.get("automatedTestingMode").getAsString());
        assertFalse("Only an explicit port is persisted and echoed", result.has("automatedTestingPort"));
    }

    @Test
    public void testCreatedManagerEchoesModeWithoutPort() throws Exception
    {
        JsonObject result = create("TESTMANAGER", null);
        assertEquals("TESTMANAGER", result.get("automatedTestingMode").getAsString());
        assertFalse(result.has("automatedTestingPort"));
    }

    private JsonObject create(String mode, String port) throws Exception
    {
        Map<String, String> params = new HashMap<>(Map.of("projectName", "TestingProject",
            "applicationId", "TestingApplication", "name", "Testing client", "automatedTestingMode", mode));
        if (port != null)
        {
            params.put("automatedTestingPort", port);
        }
        JsonObject result = JsonParser.parseString(new CreateLaunchConfigTool().execute(params)).getAsJsonObject();
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("created", result.get("action").getAsString());
        assertEquals("Testing client", result.get("name").getAsString());
        verify(copy).doSave();
        assertTrue("The created configuration must confirm its persisted testing mode", result.has("automatedTestingMode"));
        return result;
    }

    private void replace(Object target, Field field, Object value) throws Exception
    {
        field.setAccessible(true);
        Object previous = field.get(target);
        restores.add(() -> {
            try
            {
                field.set(target, previous);
            }
            catch (IllegalAccessException e)
            {
                throw new AssertionError(e);
            }
        });
        field.set(target, value);
    }
}

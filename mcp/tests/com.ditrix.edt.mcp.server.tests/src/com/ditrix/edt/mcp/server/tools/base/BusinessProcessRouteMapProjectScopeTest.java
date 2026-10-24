/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonParser;

/** Exercises the real FQN/root check before the route-map handler requests model services. */
public class BusinessProcessRouteMapProjectScopeTest
{
    private static final String FQN = "BusinessProcess.X";
    private Field pluginField;
    private Object previousPlugin;
    private Activator activator;
    private Configuration configuration;
    private IProject externalProject;
    private MetadataScope externalScope;

    @Before
    public void setUp() throws Exception
    {
        pluginField = Activator.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        previousPlugin = pluginField.get(null);
        activator = mock(Activator.class);
        pluginField.set(null, activator);
        when(activator.getBmModelManager()).thenThrow(
            new AssertionError("Wrong-project refusal must precede access to BM model services"));

        configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("TestConfiguration");
        var process = MdClassFactory.eINSTANCE.createBusinessProcess();
        process.setName("X");
        configuration.getBusinessProcesses().add(process);

        externalProject = mock(IProject.class);
        when(externalProject.getName()).thenReturn("ExternalObjects");
        var externalObjects = mock(IExternalObjectProject.class);
        var processor = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        processor.setName("ExtProc");
        when(externalObjects.getExternalObjects()).thenReturn(List.of(processor));
        var projects = mock(IV8ProjectManager.class);
        when(projects.getProject(externalProject)).thenReturn(externalObjects);
        when(activator.getV8ProjectManager()).thenReturn(projects);
        externalScope = MetadataScope.of(externalProject, configuration);
    }

    @After
    public void tearDown() throws Exception
    {
        pluginField.set(null, previousPlugin);
    }

    @Test
    public void testGetRefusesAnExternalObjectsProjectBeforeModelAccess()
    {
        assertWrongProject("get");
    }

    @Test
    public void testReplaceRefusesAnExternalObjectsProjectBeforeModelAccess()
    {
        assertWrongProject("replace");
    }

    @Test
    public void testRemoveRefusesAnExternalObjectsProjectBeforeModelAccess()
    {
        assertWrongProject("remove");
    }

    @Test
    public void testAConfigurationProjectStillResolvesTheBusinessProcess()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("TestConfiguration");
        new ScopedTool(project, configuration, MetadataScope.ofConfiguration(configuration)).assertResolves();
        verify(activator, never()).getBmModelManager();
    }

    private void assertWrongProject(String action)
    {
        Map<String, String> params = new HashMap<>(Map.of(
            "projectName", "ExternalObjects", "fqn", FQN, "action", action));
        if (action.equals("replace"))
        {
            params.put("points", "[]");
            params.put("connections", "[]");
        }
        var tool = new ScopedTool(externalProject, configuration, externalScope);
        var result = JsonParser.parseString(tool.run(params)).getAsJsonObject();
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("'BusinessProcess.X' cannot be addressed in project 'ExternalObjects'. "
            + "Project 'ExternalObjects' is an EXTERNAL-OBJECTS project: it holds only "
            + "ExternalDataProcessor / ExternalReport objects (ExtProc), never a BusinessProcess. "
            + "Address that type in the configuration project it belongs to.", result.get("error").getAsString());
        verify(activator, never()).getBmModelManager();
    }

    /** Supplies the project context only; the two-argument resolver remains production code. */
    private static final class ScopedTool extends BusinessProcessRouteMapTool
    {
        private final IProject project;
        private final Configuration configuration;
        private final MetadataScope scope;

        ScopedTool(IProject project, Configuration configuration, MetadataScope scope)
        {
            this.project = project;
            this.configuration = configuration;
            this.scope = scope;
        }

        @Override
        protected ProjectContext resolveProjectAndScope(String projectName)
        {
            assertEquals(project.getName(), projectName);
            var context = new ProjectContext();
            context.project = project;
            context.config = configuration;
            context.scope = scope;
            return context;
        }

        void assertResolves()
        {
            var context = resolveProjectAndScope(project.getName(), FQN);
            assertFalse(context.error, context.hasError());
            assertSame(project, context.project);
            assertSame(configuration, context.config);
            assertSame(scope, context.scope);
        }

        String run(Map<String, String> params)
        {
            return executeOnUiThread(params);
        }
    }
}

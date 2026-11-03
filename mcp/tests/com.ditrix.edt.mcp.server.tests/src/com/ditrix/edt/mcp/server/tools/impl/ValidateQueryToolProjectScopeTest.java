/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.validation.IResourceValidator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.google.gson.JsonParser;

/** Exercises EDT's actual resource-to-project lookup, independently of the UI and parser. */
public class ValidateQueryToolProjectScopeTest
{
    private Field pluginField;
    private Object previousPlugin;
    private IProject project;
    private IDtProject dtProject;
    private IExtensionProject extension;
    private Activator activator;
    private Object previousProvider;

    @Before
    public void setUp() throws Exception
    {
        pluginField = Activator.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        previousPlugin = pluginField.get(null);
        activator = mock(Activator.class);
        pluginField.set(null, activator);
        project = mock(IProject.class);
        when(project.getName()).thenReturn("MyExt");
        dtProject = mock(IDtProject.class);
        extension = mock(IExtensionProject.class);
        when(extension.getDtProject()).thenReturn(dtProject);
        IProject base = mock(IProject.class);
        when(base.getName()).thenReturn("Base");
        when(extension.getParentProject()).thenReturn(base);
        IDtProjectManager dtManager = mock(IDtProjectManager.class);
        IV8ProjectManager v8Manager = mock(IV8ProjectManager.class);
        when(activator.getDtProjectManager()).thenReturn(dtManager);
        when(activator.getV8ProjectManager()).thenReturn(v8Manager);
        when(dtManager.getDtProject(project)).thenReturn(dtProject);
        when(v8Manager.getProject(dtProject)).thenReturn(extension);
        previousProvider = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap().get("qldcs");
    }

    @After
    public void tearDown() throws Exception
    {
        pluginField.set(null, previousPlugin);
        var providers = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap();
        if (previousProvider == null)
        {
            providers.remove("qldcs");
        }
        else
        {
            providers.put("qldcs", previousProvider);
        }
    }

    @Test
    public void extensionOwnScopeDoesNotDependOnUriModelLookup() throws Exception
    {
        assertSame(extension, ExtensionOriginUtils.resolveV8Project(project));
        assertResourceContext(extension, false);
    }

    @Test
    public void extensionOwnScopeIsAlsoBoundInDcsMode() throws Exception
    {
        assertResourceContext(extension, true);
    }

    @Test
    public void regularProjectRetainsItsOwnScope() throws Exception
    {
        IV8Project regular = mock(IV8Project.class);
        when(regular.getDtProject()).thenReturn(dtProject);
        assertResourceContext(regular, false);
    }

    private void assertResourceContext(IV8Project v8Project, boolean dcsMode) throws Exception
    {
        TestQlResource resource = new TestQlResource();
        resource.setURI(URI.createPlatformResourceURI("/MyExt/query.qldcs", true));
        Class<?> lookupClass = FrameworkUtil.getBundle(IResourceLookup.class).loadClass(
            "com._1c.g5.v8.dt.internal.core.platform.ResourceLookup");
        IResourceLookup lookup = (IResourceLookup)lookupClass.getConstructor().newInstance();
        Field modelManager = lookupClass.getDeclaredField("modelManager");
        modelManager.setAccessible(true);
        modelManager.set(lookup, mock(IBmModelManager.class));
        assertNull("URI-only lookup has no own project index", lookup.getDtProject(resource));

        ValidateQueryTool.configureResource(resource, v8Project, dcsMode);

        assertSame("The own metadata index must belong to the requested project",
            dtProject, lookup.getDtProject(resource));
        assertTrue(resource.isParentScopeAware());
        assertEquals(dcsMode, resource.preComputeAnnounceAlias());
        assertEquals(dcsMode, resource.getOptions().get("DcsValidationModeOption"));
    }

    @Test
    public void validationProducerBindsTheExtensionBeforeLoadingAndValidating() throws Exception
    {
        IBmModel model = mock(IBmModel.class);
        IBmModelManager models = mock(IBmModelManager.class);
        when(activator.getBmModelManager()).thenReturn(models);
        when(models.getModel(dtProject)).thenReturn(model);
        boolean[] reading = {false};
        when(model.executeReadonlyTask(any())).thenAnswer(invocation -> {
            reading[0] = true;
            try
            {
                return ((IBmTask<?>)invocation.getArgument(0)).execute(null, null);
            }
            finally
            {
                reading[0] = false;
            }
        });
        TestQlResource resource = new TestQlResource();
        ResourceSet resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("qldcs", (Resource.Factory)uri -> {
            resource.setURI(uri);
            return resource;
        });
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        IResourceSetProvider sets = mock(IResourceSetProvider.class);
        IResourceValidator validator = mock(IResourceValidator.class);
        when(provider.get(IResourceSetProvider.class)).thenReturn(sets);
        when(provider.get(IResourceValidator.class)).thenReturn(validator);
        when(sets.get(project)).thenReturn(resources);
        IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap().put("qldcs", provider);
        resource.onLoad = () -> {
            assertTrue("Parsing reads metadata inside a BM boundary", reading[0]);
            assertSame(dtProject, resource.getDtProject());
            assertTrue(resource.isParentScopeAware());
        };
        when(validator.validate(eq(resource), any(), any())).thenAnswer(invocation -> {
            assertTrue("Semantic validation reads metadata inside a BM boundary", reading[0]);
            return List.of();
        });

        String json = new ValidateQueryTool().doValidateQuery(project,
            "SELECT E.ExtValue FROM Catalog.tests_ExtOnly AS E", false);

        var result = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(json, result.get("success").getAsBoolean());
        assertTrue(json, result.get("valid").getAsBoolean());
        assertEquals("Project 'MyExt' (extension + base configuration 'Base')",
            result.get("validationScope").getAsString());
        verify(validator).validate(eq(resource), any(), any());
        assertTrue(resource.unloaded);
        assertTrue(resources.getResources().isEmpty());
    }

    @Test
    public void unavailableOwnModelIsAnActionableError()
    {
        String json = new ValidateQueryTool().doValidateQuery(project, "SELECT 1", false);
        var result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(json, result.get("success").getAsBoolean());
        assertTrue(json, result.get("error").getAsString().contains("MyExt"));
        assertTrue(json, result.get("error").getAsString().contains("list_projects"));
    }

    @Test
    public void extensionResultNamesBothProjects()
    {
        String scope = ValidateQueryTool.validationScope(project);
        assertEquals("Project 'MyExt' (extension + base configuration 'Base')", scope);
        var result = JsonParser.parseString(ValidateQueryTool.buildResult(List.of(), false, scope))
            .getAsJsonObject();
        assertEquals(scope, result.get("validationScope").getAsString());
        assertTrue(result.get("valid").getAsBoolean());
    }

    /** Keeps real resource lifecycle and project storage; substitutes the parser and UI cache. */
    private static final class TestQlResource extends QlDcsResource
    {
        private boolean parentScope;
        private boolean unloaded;
        private Runnable onLoad = () -> { };

        @Override
        public void setParentScopeAware(boolean value)
        {
            parentScope = value;
        }

        @Override
        public boolean isParentScopeAware()
        {
            return parentScope;
        }

        @Override
        public void setPreComputeAnnounceAlias(boolean value)
        {
            preComputeAnnounceAlias = value;
        }

        @Override
        protected void doLoad(InputStream stream, Map<?, ?> options)
        {
            onLoad.run();
        }

        @Override
        protected void doUnload()
        {
            unloaded = true;
        }
    }
}

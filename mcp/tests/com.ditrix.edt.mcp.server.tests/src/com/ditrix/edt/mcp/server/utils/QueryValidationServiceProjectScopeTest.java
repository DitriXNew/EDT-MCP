/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.validation.IResourceValidator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFactory;
import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;
import com._1c.g5.v8.dt.ql.model.QlFactory;
import com._1c.g5.v8.dt.ql.typesystem.IDynamicDbViewFieldComputer;
import com.ditrix.edt.mcp.server.Activator;
import com.google.gson.JsonParser;

/** Exercises the shared producer and EDT's actual resource-to-project lookup, without a display. */
public class QueryValidationServiceProjectScopeTest
{
    private static final String QUERY = "SELECT E.ExtValue FROM Catalog.tests_ExtOnly AS E";
    private Field pluginField;
    private Object previousPlugin;
    private Object previousProvider;
    private IProject project;
    private IDtProject dtProject;
    private IExtensionProject extension;
    private IV8ProjectManager v8Manager;
    private IBmModelManager models;
    private boolean reading;
    private final List<TestQlResource> loaded = new ArrayList<>();
    private final ResourceSet resources = new ResourceSetImpl();
    private final QueryValidationService service = new QueryValidationService()
    {
        @Override
        protected void dispatch(Runnable work) { work.run(); }
    };

    @Before
    public void setUp() throws Exception
    {
        pluginField = Activator.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        previousPlugin = pluginField.get(null);
        previousProvider = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap().get("qldcs");
        Activator activator = mock(Activator.class);
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
        v8Manager = mock(IV8ProjectManager.class);
        when(activator.getDtProjectManager()).thenReturn(dtManager);
        when(activator.getV8ProjectManager()).thenReturn(v8Manager);
        when(dtManager.getDtProject(project)).thenReturn(dtProject);
        when(v8Manager.getProject(dtProject)).thenReturn(extension);
        models = mock(IBmModelManager.class);
        when(activator.getBmModelManager()).thenReturn(models);
        IBmModel model = mock(IBmModel.class);
        when(models.getModel(dtProject)).thenReturn(model);
        when(model.executeReadonlyTask(any())).thenAnswer(invocation -> {
            reading = true;
            try { return ((IBmTask<?>)invocation.getArgument(0)).execute(null, null); }
            finally { reading = false; }
        });

        Class<?> lookupClass = FrameworkUtil.getBundle(IResourceLookup.class).loadClass(
            "com._1c.g5.v8.dt.internal.core.platform.ResourceLookup");
        IResourceLookup lookup = (IResourceLookup)lookupClass.getConstructor().newInstance();
        Field lookupModels = lookupClass.getDeclaredField("modelManager");
        lookupModels.setAccessible(true);
        lookupModels.set(lookup, mock(IBmModelManager.class));
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("qldcs", (Resource.Factory)uri -> {
            TestQlResource resource = new TestQlResource();
            resource.setURI(uri);
            assertNull("The synthetic URI alone supplies no own project index", lookup.getDtProject(resource));
            resource.onLoad = () -> {
                assertTrue("Loading must run inside the BM read boundary", reading);
                assertSame("EDT must resolve the requested own index before parsing",
                    dtProject, lookup.getDtProject(resource));
                assertTrue(resource.isParentScopeAware());
                var table = QlFactory.eINSTANCE.createSinglePartQuerySchemaTable();
                table.setTableName("Catalog.tests_ExtOnly");
                var source = QlFactory.eINSTANCE.createQuerySchemaTable();
                source.setTable(table);
                resource.getContents().add(source);
            };
            loaded.add(resource);
            return resource;
        });
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        IResourceSetProvider sets = mock(IResourceSetProvider.class);
        IResourceValidator validator = mock(IResourceValidator.class);
        var computer = mock(IDynamicDbViewFieldComputer.class);
        when(provider.get(IResourceSetProvider.class)).thenReturn(sets);
        when(provider.get(IResourceValidator.class)).thenReturn(validator);
        when(provider.get(IDynamicDbViewFieldComputer.class)).thenReturn(computer);
        when(sets.get(project)).thenReturn(resources);
        when(validator.validate(any(), any(), any())).thenAnswer(invocation -> {
            assertTrue("Native validation must run inside the BM read boundary", reading);
            return List.of();
        });
        when(computer.computeDbView(any())).thenAnswer(invocation -> {
            assertTrue("The source census must run inside the BM read boundary", reading);
            return DbViewFactory.eINSTANCE.createDbViewTableDef();
        });
        var field = DbViewFactory.eINSTANCE.createDbViewFieldFieldDef();
        field.setName("ExtValue");
        field.setNameRu("ExtValue");
        when(computer.computeFieldsBySemanticObject(any())).thenReturn(List.of(field));
        IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap().put("qldcs", provider);
    }

    @After
    public void tearDown() throws Exception
    {
        pluginField.set(null, previousPlugin);
        var providers = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap();
        if (previousProvider == null) providers.remove("qldcs");
        else providers.put("qldcs", previousProvider);
    }

    @Test
    public void extensionOwnScopeIsBoundBeforeLoadInBothModes()
    {
        for (boolean dcsMode : new boolean[] {false, true})
        {
            var result = service.validate(project, QUERY, dcsMode);
            assertEquals(result.toJson(), QueryValidationService.State.VALID, result.state());
            assertTrue(result.complete());
            assertEquals(dcsMode, result.values().get("dcsMode"));
            TestQlResource resource = loaded.get(loaded.size() - 1);
            assertEquals(dcsMode, resource.getOptions().get(QlDcsResource.DCS_VALIDATION_MODE_OPTION));
            assertEquals(dcsMode, resource.preComputeAnnounceAlias());
            assertEquals("Project 'MyExt' (extension + base configuration 'Base')",
                result.values().get("validationScope"));
            assertTrue(result.values().get("sources").toString().contains("ExtValue"));
        }
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().allMatch(r -> r.unloaded && QUERY.equals(r.text)));
        assertTrue(resources.getResources().isEmpty());
        verify(models, never()).getModel(project);
    }

    @Test
    public void regularProjectRetainsItsOwnScope()
    {
        IV8Project regular = mock(IV8Project.class);
        when(regular.getDtProject()).thenReturn(dtProject);
        when(v8Manager.getProject(dtProject)).thenReturn(regular);
        var result = service.validate(project, QUERY, false);
        assertEquals(result.toJson(), QueryValidationService.State.VALID, result.state());
        assertEquals("Project 'MyExt'", result.values().get("validationScope"));
    }

    @Test
    public void unavailableOwnModelDoesNotFallBackToAnotherProject()
    {
        when(models.getModel(dtProject)).thenReturn(null);
        when(models.getModel(project)).thenReturn(mock(IBmModel.class));
        var result = service.validate(project, QUERY, false);
        assertFalse(result.available());
        assertFalse(result.complete());
        assertEquals(QueryValidationService.State.PARTIAL, result.state());
        assertTrue(result.toJson().contains("MyExt"));
        assertTrue(result.toJson().contains("list_projects"));
        assertTrue(loaded.isEmpty());
        verify(models, never()).getModel(project);
    }

    @Test
    public void dcsAndDynamicListPreWriteGatesUseTheBoundSharedValidator()
    {
        var dcs = JsonParser.parseString("{\"query\":\"" + QUERY + "\"}").getAsJsonObject();
        var dynamicList = JsonParser.parseString("{\"queryText\":\"" + QUERY + "\"}").getAsJsonObject();
        var queries = new ArrayList<>(QueryWriteValidation.dcs("dataSet", dcs, null));
        queries.addAll(QueryWriteValidation.dynamicList("dynamicList", dynamicList));
        var batch = QueryWriteValidation.validate(queries,
            query -> service.validate(project, query.text(), query.dcsMode()));
        assertNull(batch.errorJson());
        assertEquals(2, batch.results().size());
        assertEquals(true, batch.results().get(0).get("dcsMode"));
        assertEquals(false, batch.results().get(1).get("dcsMode"));
        for (var result : batch.results())
        {
            assertEquals("VALID", result.get("validationState"));
            assertEquals(true, result.get("validationComplete"));
            assertEquals("Project 'MyExt' (extension + base configuration 'Base')", result.get("validationScope"));
        }
        assertEquals(2, loaded.size());
        assertTrue(resources.getResources().isEmpty());
    }

    /** Real EMF resource lifecycle and project storage; substitutes only parser and UI cache. */
    private static final class TestQlResource extends QlDcsResource
    {
        private boolean parentScope;
        private boolean unloaded;
        private String text;
        private Runnable onLoad = () -> { };

        @Override
        public void setParentScopeAware(boolean value) { parentScope = value; }

        @Override
        public boolean isParentScopeAware() { return parentScope; }

        @Override
        public void setPreComputeAnnounceAlias(boolean value) { preComputeAnnounceAlias = value; }

        @Override
        protected void doLoad(InputStream stream, Map<?, ?> options) throws IOException
        {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            onLoad.run();
        }

        @Override
        protected void doUnload() { unloaded = true; }
    }
}

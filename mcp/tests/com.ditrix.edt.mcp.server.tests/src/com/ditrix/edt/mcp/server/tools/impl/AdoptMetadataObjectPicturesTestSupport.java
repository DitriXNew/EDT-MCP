/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.After;
import org.junit.Before;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared real-EMF fixture with mocked EDT service and transaction boundaries. */
public abstract class AdoptMetadataObjectPicturesTestSupport
{
    protected static final MdClassFactory MD = MdClassFactory.eINSTANCE;
    protected static final FormFactory FORM = FormFactory.eINSTANCE;
    protected static final String FORM_FQN = "Catalog.Goods.Form.Item"; //$NON-NLS-1$
    protected final IProject baseProject = mock(IProject.class);
    protected final IProject extensionProject = mock(IProject.class);
    protected final IExtensionProject target = mock(IExtensionProject.class);
    protected final IModelObjectAdopter adopter = mock(IModelObjectAdopter.class);
    protected final IBmModel baseModel = mock(IBmModel.class);
    protected final IBmModel extensionModel = mock(IBmModel.class);
    protected final IBmTransaction baseTx = mock(IBmTransaction.class);
    protected final IBmTransaction extensionTx = mock(IBmTransaction.class);
    protected final IBmModelManager models = mock(IBmModelManager.class);
    protected final IDtProject dtExtension = mock(IDtProject.class);
    protected final Map<EObject, EObject> copies = new IdentityHashMap<>();
    protected final List<EObject> adoptedSources = new ArrayList<>();
    protected final List<List<EObject>> batches = new ArrayList<>();
    protected final Configuration config = MD.createConfiguration();
    protected final Catalog goods = MD.createCatalog();
    protected final CatalogForm mdForm = MD.createCatalogForm();
    protected final CommonPicture logo = MD.createCommonPicture();
    protected final Form form = FORM.createForm();
    protected EObject baseReadRoot;
    protected EObject extensionReadRoot;
    protected Field activatorField;
    protected Object previousActivator;
    protected Field wiringField;
    protected Object previousWiring;
    protected WriteScope scope;
    protected String rawResponse;
    protected TestTool tool;

    @Before
    public void setUp() throws Exception
    {
        config.setName("Base"); //$NON-NLS-1$
        goods.setName("Goods"); //$NON-NLS-1$
        mdForm.setName("Item"); //$NON-NLS-1$
        logo.setName("Logo"); //$NON-NLS-1$
        config.getCatalogs().add(goods);
        goods.getForms().add(mdForm);
        config.getCommonPictures().add(logo);
        mdForm.setForm(form);
        baseReadRoot = mdForm;

        when(baseProject.getName()).thenReturn("Base"); //$NON-NLS-1$
        when(extensionProject.getName()).thenReturn("tests"); //$NON-NLS-1$
        when(target.getProject()).thenReturn(extensionProject);
        when(target.getParentProject()).thenReturn(baseProject);
        IV8ProjectManager projects = mock(IV8ProjectManager.class);
        when(projects.getProjects(IExtensionProject.class)).thenReturn(List.of(target));

        Activator activator = mock(Activator.class);
        when(activator.getV8ProjectManager()).thenReturn(projects);
        when(activator.getBmModelManager()).thenReturn(models);
        when(models.getModel(baseProject)).thenReturn(baseModel);
        when(models.getModel(extensionProject)).thenReturn(extensionModel);
        when(baseTx.getObjectById(anyLong())).thenAnswer(inv -> baseReadRoot);
        when(extensionTx.getObjectById(anyLong())).thenAnswer(inv -> extensionReadRoot);
        when(baseModel.executeReadonlyTask(any())).thenAnswer(inv ->
            ((IBmTask<?>)inv.getArgument(0)).execute(baseTx, null));
        when(extensionModel.executeReadonlyTask(any())).thenAnswer(inv ->
            ((IBmTask<?>)inv.getArgument(0)).execute(extensionTx, null));

        Configuration extConfig = mock(Configuration.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)extConfig).bmGetFqn()).thenReturn("Configuration"); //$NON-NLS-1$
        IConfigurationProvider configurations = mock(IConfigurationProvider.class);
        when(configurations.getConfiguration(extensionProject)).thenReturn(extConfig);
        when(activator.getConfigurationProvider()).thenReturn(configurations);
        IDtProjectManager dtProjects = mock(IDtProjectManager.class);
        when(dtProjects.getDtProject(extensionProject)).thenReturn(dtExtension);
        when(activator.getDtProjectManager()).thenReturn(dtProjects);
        when(models.forceExport(eq(dtExtension), anyList())).thenReturn(true);

        when(adopter.isAdoptable(any())).thenReturn(true);
        when(adopter.isAdopted(any(), eq(target))).thenAnswer(inv -> copies.containsKey(inv.getArgument(0)));
        when(adopter.getAdopted(any(), eq(target))).thenAnswer(inv -> copies.get(inv.getArgument(0)));
        when(adopter.adoptAndAttach(any(EObject.class), eq(target), any())).thenAnswer(inv ->
            adoptSource(inv.getArgument(0)));
        when(adopter.adoptAndAttach(anyList(), eq(target), any())).thenAnswer(inv -> {
            List<EObject> sources = inv.getArgument(0);
            batches.add(List.copyOf(sources));
            List<EObject> result = new ArrayList<>();
            for (EObject source : sources)
            {
                if (!copies.containsKey(source))
                {
                    result.add(adoptSource(source));
                }
            }
            return result;
        });

        // Replace the two registries only for this test and restore them in @After. ServiceAccess
        // still performs its real lookup; no production seam is needed for a red-on-base run.
        activatorField = Activator.class.getDeclaredField("plugin"); //$NON-NLS-1$
        activatorField.setAccessible(true);
        previousActivator = activatorField.get(null);
        activatorField.set(null, activator);
        installAdopterService();
        tool = new TestTool(baseProject, config);
    }

    @SuppressWarnings("unchecked")
    protected void installAdopterService() throws Exception
    {
        Class<?> wiringType = ServiceAccess.class.getClassLoader()
            .loadClass("com._1c.g5.internal.wiring.WiringPlugin"); //$NON-NLS-1$
        Plugin wiring = (Plugin)wiringType.getConstructor().newInstance();
        // OSGi's signed interfaces cannot host Mockito-generated classes in this runtime.
        ServiceReference<IModelObjectAdopter> reference = osgiProxy(ServiceReference.class,
            (proxy, method, args) -> proxyObjectMethod(proxy, method.getName(), args));
        BundleContext context = osgiProxy(BundleContext.class, (proxy, method, args) -> {
            switch (method.getName())
            {
            case "getServiceReferences": //$NON-NLS-1$
                return args[0] == IModelObjectAdopter.class ? List.of(reference) : List.of();
            case "getService": //$NON-NLS-1$
                return adopter;
            case "ungetService": //$NON-NLS-1$
                return true;
            default:
                return proxyObjectMethod(proxy, method.getName(), args);
            }
        });
        Bundle bundle = osgiProxy(Bundle.class, (proxy, method, args) ->
            "getBundleContext".equals(method.getName()) ? context //$NON-NLS-1$
                : proxyObjectMethod(proxy, method.getName(), args));
        Field pluginBundle = Plugin.class.getDeclaredField("bundle"); //$NON-NLS-1$
        pluginBundle.setAccessible(true);
        pluginBundle.set(wiring, bundle);
        wiringField = wiringType.getDeclaredField("plugin"); //$NON-NLS-1$
        wiringField.setAccessible(true);
        previousWiring = wiringField.get(null);
        wiringField.set(null, wiring);
    }

    protected static <T> T osgiProxy(Class<T> type, InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(AdoptMetadataObjectPicturesTestSupport.class.getClassLoader(),
            new Class<?>[] {type}, handler));
    }

    protected static Object proxyObjectMethod(Object proxy, String name, Object[] args)
    {
        switch (name)
        {
        case "hashCode": //$NON-NLS-1$
            return System.identityHashCode(proxy);
        case "equals": //$NON-NLS-1$
            return proxy == args[0];
        case "toString": //$NON-NLS-1$
            return "Adopter OSGi test boundary"; //$NON-NLS-1$
        default:
            throw new AssertionError("Unexpected OSGi boundary call: " + name); //$NON-NLS-1$
        }
    }

    @After
    public void restoreRegistries() throws Exception
    {
        try
        {
            if (wiringField != null)
            {
                wiringField.set(null, previousWiring);
            }
        }
        finally
        {
            if (activatorField != null)
            {
                activatorField.set(null, previousActivator);
            }
        }
    }

    protected JsonObject run(String fqn) throws Exception
    {
        scope = new WriteScope();
        String[] response = {null};
        WriteScope.runWithScope(scope, () -> {
            try
            {
                response[0] = tool.executeOnUiThread(Map.of("projectName", "Base", "fqn", fqn)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            catch (Exception e)
            {
                throw new AssertionError("The tool must report a structured error", e); //$NON-NLS-1$
            }
        });
        rawResponse = response[0];
        return JsonParser.parseString(rawResponse).getAsJsonObject();
    }

    protected void assertRefusal(JsonObject result, String badValue, String recovery) throws Exception
    {
        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains(badValue));
        assertTrue(error, error.contains(recovery));
        assertTrue(adoptedSources.isEmpty());
        verify(adopter, never()).adoptAndAttach(any(EObject.class), eq(target), any());
        verify(adopter, never()).adoptAndAttach(anyList(), eq(target), any());
        verify(models, never()).forceExport(any(), anyList());
    }

    protected static void assertNames(JsonObject result, String key, String... expected)
    {
        JsonObject dependencies = result.getAsJsonObject("dependencies"); //$NON-NLS-1$
        assertTrue("The tool must report picture dependencies: " + result, dependencies != null); //$NON-NLS-1$
        JsonArray values = dependencies.getAsJsonObject("commonPictures").getAsJsonArray(key); //$NON-NLS-1$
        List<String> actual = new ArrayList<>();
        values.forEach(value -> actual.add(value.getAsString()));
        assertEquals(List.of(expected), actual);
    }

    protected CommonPicture picture(String name)
    {
        CommonPicture picture = MD.createCommonPicture();
        picture.setName(name);
        config.getCommonPictures().add(picture);
        return picture;
    }

    protected static PictureRef ref(Picture picture)
    {
        PictureRef ref = (PictureRef)EcoreUtil.create(McorePackage.Literals.PICTURE_REF);
        ref.eSet(McorePackage.Literals.PICTURE_REF__PICTURE, picture);
        return ref;
    }

    protected static FormField field(String name, Picture picture)
    {
        FormField field = FORM.createFormField();
        field.setName(name);
        field.setHeaderPicture(ref(picture));
        return field;
    }

    protected static EObject exportedCopy(String fqn)
    {
        MdObject copy = mock(MdObject.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)copy).bmGetTopObject()).thenReturn((IBmObject)copy);
        when(((IBmObject)copy).bmGetFqn()).thenReturn(fqn);
        return copy;
    }

    protected static EObject exportedPicture(String fqn)
    {
        CommonPicture copy = mock(CommonPicture.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)copy).bmGetTopObject()).thenReturn((IBmObject)copy);
        when(((IBmObject)copy).bmGetFqn()).thenReturn(fqn);
        return copy;
    }

    protected static final class TestTool extends AdoptMetadataObjectTool
    {
        protected final ProjectContext context;

        TestTool(IProject project, Configuration config) throws Exception
        {
            var constructor = ProjectContext.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            context = constructor.newInstance();
            context.project = project;
            context.config = config;
            context.scope = MetadataScope.ofConfiguration(config);
        }

        @Override
        protected ProjectContext resolveProjectAndConfig(String projectName)
        {
            return context;
        }
    }

    protected EObject adoptSource(EObject source)
    {
        adoptedSources.add(source);
        EObject copy = source instanceof CommonPicture
            ? exportedPicture(MetadataNodeResolver.addressOf((MdObject)source))
            : exportedCopy("Catalog.Goods"); //$NON-NLS-1$
        copies.put(source, copy);
        return copy;
    }
}

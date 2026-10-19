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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonForm;
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

/**
 * Runs the real UI-thread dispatch headlessly: only EDT services/transaction boundaries are mocked.
 * The form, nested items, PictureRefs and bilingual metadata resolution use the real EMF models.
 * This class also runs against the base tool, without depending on any new production method.
 */
public class AdoptMetadataObjectPicturesTest
{
    private static final MdClassFactory MD = MdClassFactory.eINSTANCE;
    private static final FormFactory FORM = FormFactory.eINSTANCE;
    private static final String FORM_FQN = "Catalog.Goods.Form.Item"; //$NON-NLS-1$
    private final IProject baseProject = mock(IProject.class);
    private final IProject extensionProject = mock(IProject.class);
    private final IExtensionProject target = mock(IExtensionProject.class);
    private final IModelObjectAdopter adopter = mock(IModelObjectAdopter.class);
    private final IBmModel baseModel = mock(IBmModel.class);
    private final IBmModel extensionModel = mock(IBmModel.class);
    private final IBmTransaction baseTx = mock(IBmTransaction.class);
    private final IBmTransaction extensionTx = mock(IBmTransaction.class);
    private final IBmModelManager models = mock(IBmModelManager.class);
    private final IDtProject dtExtension = mock(IDtProject.class);
    private final Map<EObject, EObject> copies = new IdentityHashMap<>();
    private final List<EObject> adoptedSources = new ArrayList<>();
    private final Configuration config = MD.createConfiguration();
    private final Catalog goods = MD.createCatalog();
    private final CatalogForm mdForm = MD.createCatalogForm();
    private final CommonPicture logo = MD.createCommonPicture();
    private final Form form = FORM.createForm();
    private EObject baseReadRoot;
    private EObject extensionReadRoot;
    private Field activatorField;
    private Object previousActivator;
    private Field wiringField;
    private Object previousWiring;
    private WriteScope scope;
    private TestTool tool;

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
        when(adopter.adoptAndAttach(any(EObject.class), eq(target), any())).thenAnswer(inv -> {
            EObject source = inv.getArgument(0);
            adoptedSources.add(source);
            EObject copy = exportedCopy(source instanceof CommonPicture
                ? MetadataNodeResolver.addressOf((MdObject)source) : "Catalog.Goods"); //$NON-NLS-1$
            copies.put(source, copy);
            return copy;
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
    private void installAdopterService() throws Exception
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

    private static <T> T osgiProxy(Class<T> type, InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(AdoptMetadataObjectPicturesTest.class.getClassLoader(),
            new Class<?>[] {type}, handler));
    }

    private static Object proxyObjectMethod(Object proxy, String name, Object[] args)
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

    @Test
    public void testFormAdoptsNestedPicturesOnceAndExportsEveryTopObject() throws Exception
    {
        FormGroup group = FORM.createFormGroup();
        form.getItems().add(group);
        group.getItems().add(field("First", logo)); //$NON-NLS-1$
        group.getItems().add(field("Second", logo)); //$NON-NLS-1$
        JsonObject result = run(FORM_FQN);

        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(List.of(mdForm, logo), adoptedSources);
        assertNames(result, "found", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(models).forceExport(dtExtension,
            List.of("Catalog.Goods", "Configuration", "CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of("tests"), scope.writtenProjects()); //$NON-NLS-1$
        assertTrue(scope.exportedTopObjects("tests").contains("CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(baseModel, never()).execute(any());
        verify(extensionModel, never()).execute(any());
    }

    @Test
    public void testOwnerCollectsPicturesAcrossItsForms() throws Exception
    {
        form.getItems().add(field("First", logo)); //$NON-NLS-1$
        CatalogForm second = MD.createCatalogForm();
        second.setName("List"); //$NON-NLS-1$
        goods.getForms().add(second);
        Form secondContent = FORM.createForm();
        second.setForm(secondContent);
        CommonPicture other = picture("Other"); //$NON-NLS-1$
        secondContent.getItems().add(field("Other", other)); //$NON-NLS-1$
        secondContent.getItems().add(field("Duplicate", logo)); //$NON-NLS-1$
        baseReadRoot = goods;

        JsonObject result = run("Catalog.Goods"); //$NON-NLS-1$
        assertEquals(List.of(goods, logo, other), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo", "CommonPicture.Other"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCommonFormIncludesItsRootPicture() throws Exception
    {
        CommonForm common = MD.createCommonForm();
        common.setName("Dashboard"); //$NON-NLS-1$
        common.setForm(form);
        config.getCommonForms().add(common);
        form.setCreateButtonsGroupPicture(ref(logo));
        baseReadRoot = common;

        JsonObject result = run("CommonForm.Dashboard"); //$NON-NLS-1$
        assertEquals(List.of(common, logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAlreadyAdoptedFormRepairsPicturesFromTheExtensionCopy() throws Exception
    {
        form.getItems().add(field("BaseOnly", logo)); //$NON-NLS-1$
        CatalogForm copy = MD.createCatalogForm();
        Form editedContent = FORM.createForm();
        copy.setForm(editedContent);
        CommonPicture edited = picture("Edited"); //$NON-NLS-1$
        editedContent.getItems().add(field("Changed", edited)); //$NON-NLS-1$
        copies.put(mdForm, copy);
        extensionReadRoot = copy;

        JsonObject result = run(FORM_FQN);
        assertEquals("alreadyAdopted", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(edited), adoptedSources);
        assertNames(result, "found", "CommonPicture.Edited"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted", "CommonPicture.Edited"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(scope.statedNothingQueued());
        assertEquals(List.of("tests"), scope.writtenProjects()); //$NON-NLS-1$
    }

    @Test
    public void testExistingPictureIsReportedAndNeverReadopted() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        copies.put(logo, exportedCopy("CommonPicture.Logo")); //$NON-NLS-1$
        extensionReadRoot = mdForm;

        JsonObject result = run(FORM_FQN);
        assertNames(result, "alreadyPresent", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "adopted"); //$NON-NLS-1$
        assertTrue(adoptedSources.isEmpty());
        assertTrue(scope.statedNothingQueued());
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testExtensionOwnedPictureNeedsNoBaseCounterpart() throws Exception
    {
        CommonPicture nativePicture = MD.createCommonPicture();
        nativePicture.setName("ExtensionLogo"); //$NON-NLS-1$
        Configuration nativeConfig = MD.createConfiguration();
        nativeConfig.getCommonPictures().add(nativePicture);
        CatalogForm copy = MD.createCatalogForm();
        Form edited = FORM.createForm();
        edited.getItems().add(field("Native", nativePicture)); //$NON-NLS-1$
        copy.setForm(edited);
        copies.put(mdForm, copy);
        extensionReadRoot = copy;
        when(extensionTx.getTopObjectByFqn("Configuration")).thenReturn((IBmObject)nativeConfig); //$NON-NLS-1$

        JsonObject result = run(FORM_FQN);
        assertTrue(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertNames(result, "alreadyPresent", "CommonPicture.ExtensionLogo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(adoptedSources.isEmpty());
        verify(models, never()).forceExport(any(), anyList());
    }

    @Test
    public void testAdoptedCopyProxyIsReadByUriInTheExtensionTransaction() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        CatalogForm proxy = MD.createCatalogForm();
        URI uri = URI.createURI("bm:/extension#Catalog.Goods.Form.Item"); //$NON-NLS-1$
        ((InternalEObject)proxy).eSetProxyURI(uri);
        copies.put(mdForm, proxy);
        when(extensionTx.getObjectByUri(uri)).thenReturn((IBmObject)mdForm);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(extensionTx, atLeastOnce()).getObjectByUri(uri);
    }

    @Test
    public void testUnreadableAdoptedFormRefusesInsteadOfPretendingNoDependencies() throws Exception
    {
        when(adopter.isAdopted(mdForm, target)).thenReturn(true);
        assertRefusal(run(FORM_FQN), FORM_FQN, "get_metadata_details"); //$NON-NLS-1$
    }

    @Test
    public void testCommonPictureProxyIsResolvedBeforeItsNameIsRead() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI("bm:/base#CommonPicture.Logo")); //$NON-NLS-1$
        PictureRef resolving = mock(PictureRef.class, withSettings().extraInterfaces(InternalEObject.class));
        when(resolving.eClass()).thenReturn(McorePackage.Literals.PICTURE_REF);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false)).thenReturn(proxy);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true)).thenReturn(logo);
        FormField field = FORM.createFormField();
        field.setHeaderPicture(resolving);
        form.getItems().add(field);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(mdForm, logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGenericPictureProxyCanResolveToACommonPicture() throws Exception
    {
        Picture generic = mock(Picture.class, withSettings().extraInterfaces(InternalEObject.class));
        when(generic.eIsProxy()).thenReturn(true);
        PictureRef resolving = mock(PictureRef.class, withSettings().extraInterfaces(InternalEObject.class));
        when(resolving.eClass()).thenReturn(McorePackage.Literals.PICTURE_REF);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, false)).thenReturn(generic);
        when(resolving.eGet(McorePackage.Literals.PICTURE_REF__PICTURE, true)).thenReturn(logo);
        FormField item = FORM.createFormField();
        item.setHeaderPicture(resolving);
        form.getItems().add(item);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(mdForm, logo), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnresolvedCommonPictureRefusesBeforeAnyAdoption() throws Exception
    {
        CommonPicture proxy = MD.createCommonPicture();
        String uri = "bm:/base#CommonPicture.Missing"; //$NON-NLS-1$
        ((InternalEObject)proxy).eSetProxyURI(URI.createURI(uri));
        form.getItems().add(field("Broken", proxy)); //$NON-NLS-1$

        JsonObject result = run(FORM_FQN);
        assertRefusal(result, uri, "list_common_pictures"); //$NON-NLS-1$
        assertNames(result, "unresolved", uri); //$NON-NLS-1$
    }

    @Test
    public void testMissingBasePictureRefusesBeforeAnyAdoption() throws Exception
    {
        CommonPicture absent = MD.createCommonPicture();
        absent.setName("Absent"); //$NON-NLS-1$
        form.getItems().add(field("Broken", absent)); //$NON-NLS-1$
        assertRefusal(run(FORM_FQN), "CommonPicture.Absent", "modify_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonAdoptablePictureRefusesBeforeTheFormIsWritten() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        when(adopter.isAdoptable(logo)).thenReturn(false);
        when(adopter.isAdopted(logo, target)).thenThrow(new IllegalArgumentException("not adoptable")); //$NON-NLS-1$
        assertRefusal(run(FORM_FQN), "CommonPicture.Logo", "modify_metadata"); //$NON-NLS-1$ //$NON-NLS-2$
        verify(adopter, never()).isAdopted(logo, target);
    }

    @Test
    public void testPlatformPictureProxiesAreSkippedAlongsideCommonPictures() throws Exception
    {
        form.getItems().add(field("Common", logo)); //$NON-NLS-1$
        for (String prefix : List.of("Std", "StdExt")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Picture platform = (Picture)EcoreUtil.create(McorePackage.Literals.PICTURE_REF);
            ((InternalEObject)platform).eSetProxyURI(URI.createURI("platform:/Pictures/" + prefix + "#/Change")); //$NON-NLS-1$ //$NON-NLS-2$
            form.getItems().add(field(prefix, platform));
        }

        JsonObject result = run(FORM_FQN);
        assertNames(result, "found", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNames(result, "unresolved"); //$NON-NLS-1$
        assertEquals(List.of(mdForm, logo), adoptedSources);
    }

    @Test
    public void testPlatformCascadeIsNotAdoptedAgainButIsExportedAndReported() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        when(adopter.isAdopted(logo, target)).thenReturn(false, true);
        CommonPicture cascaded = (CommonPicture)exportedPicture("CommonPicture.Logo"); //$NON-NLS-1$
        when(adopter.getAdopted(logo, target)).thenReturn(cascaded);

        JsonObject result = run(FORM_FQN);
        assertEquals(List.of(mdForm), adoptedSources);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(scope.exportedTopObjects("tests").contains("CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPartialFailureReportsCompletedPicturesAndExportsThem() throws Exception
    {
        CommonPicture other = picture("Other"); //$NON-NLS-1$
        form.getItems().add(field("First", logo)); //$NON-NLS-1$
        form.getItems().add(field("Second", other)); //$NON-NLS-1$
        doThrow(new IllegalStateException("Picture service failed")) //$NON-NLS-1$
            .when(adopter).adoptAndAttach(eq(other), eq(target), any());

        JsonObject result = run(FORM_FQN);
        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains(FORM_FQN));
        assertTrue(error, error.contains("CommonPicture.Logo")); //$NON-NLS-1$
        assertTrue(error, error.contains("get_metadata_details")); //$NON-NLS-1$
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(scope.hasRecordedWrite());
        assertTrue(scope.exportedTopObjects("tests").contains("CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRepairPersistenceReflectsThePictureExportResult() throws Exception
    {
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;
        when(models.forceExport(eq(dtExtension), anyList())).thenReturn(false);

        JsonObject result = run(FORM_FQN);
        assertNames(result, "adopted", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.get("persisted").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testBilingualFormAddressKeepsPictureProgrammaticNameNotSynonym() throws Exception
    {
        String russianGoods = "\u0422\u043e\u0432\u0430\u0440\u044b"; //$NON-NLS-1$
        String russianItem = "\u042d\u043b\u0435\u043c\u0435\u043d\u0442"; //$NON-NLS-1$
        String russianLogo = "\u041b\u043e\u0433\u043e\u0442\u0438\u043f"; //$NON-NLS-1$
        goods.setName(russianGoods);
        mdForm.setName(russianItem);
        logo.setName(russianLogo);
        logo.getSynonym().put("ru", "Displayed picture"); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("Logo", logo)); //$NON-NLS-1$
        String english = "Catalog." + russianGoods + ".Form." + russianItem; //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = run(english);
        assertNames(result, "adopted", "CommonPicture." + russianLogo); //$NON-NLS-1$ //$NON-NLS-2$
        copies.put(mdForm, mdForm);
        extensionReadRoot = mdForm;

        String russian = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a." //$NON-NLS-1$
            + russianGoods + ".\u0424\u043e\u0440\u043c\u0430." + russianItem; //$NON-NLS-1$
        JsonObject again = run(russian);
        assertNames(again, "alreadyPresent", "CommonPicture." + russianLogo); //$NON-NLS-1$ //$NON-NLS-2$
        verify(adopter, times(1)).adoptAndAttach(eq(logo), eq(target), any());
    }

    @Test
    public void testOutputSchemaAndDescriptionDeclareAutomaticDependencies()
    {
        assertTrue(new AdoptMetadataObjectTool().getDescription().contains("referenced form common pictures")); //$NON-NLS-1$
        JsonObject schema = JsonParser.parseString(new AdoptMetadataObjectTool().getOutputSchema()).getAsJsonObject();
        assertTrue(schema.getAsJsonObject("properties").has("dependencies")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(new AdoptMetadataObjectTool().getInputSchema().contains("includeReferencedPictures")); //$NON-NLS-1$
    }

    private JsonObject run(String fqn) throws Exception
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
        return JsonParser.parseString(response[0]).getAsJsonObject();
    }

    private void assertRefusal(JsonObject result, String badValue, String recovery) throws Exception
    {
        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains(badValue));
        assertTrue(error, error.contains(recovery));
        assertTrue(adoptedSources.isEmpty());
        verify(adopter, never()).adoptAndAttach(any(EObject.class), eq(target), any());
        verify(models, never()).forceExport(any(), anyList());
    }

    private static void assertNames(JsonObject result, String key, String... expected)
    {
        JsonObject dependencies = result.getAsJsonObject("dependencies"); //$NON-NLS-1$
        assertTrue("The tool must report picture dependencies: " + result, dependencies != null); //$NON-NLS-1$
        JsonArray values = dependencies.getAsJsonObject("commonPictures").getAsJsonArray(key); //$NON-NLS-1$
        List<String> actual = new ArrayList<>();
        values.forEach(value -> actual.add(value.getAsString()));
        assertEquals(List.of(expected), actual);
    }

    private CommonPicture picture(String name)
    {
        CommonPicture picture = MD.createCommonPicture();
        picture.setName(name);
        config.getCommonPictures().add(picture);
        return picture;
    }

    private static PictureRef ref(Picture picture)
    {
        PictureRef ref = (PictureRef)EcoreUtil.create(McorePackage.Literals.PICTURE_REF);
        ref.eSet(McorePackage.Literals.PICTURE_REF__PICTURE, picture);
        return ref;
    }

    private static FormField field(String name, Picture picture)
    {
        FormField field = FORM.createFormField();
        field.setName(name);
        field.setHeaderPicture(ref(picture));
        return field;
    }

    private static EObject exportedCopy(String fqn)
    {
        MdObject copy = mock(MdObject.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)copy).bmGetTopObject()).thenReturn((IBmObject)copy);
        when(((IBmObject)copy).bmGetFqn()).thenReturn(fqn);
        return copy;
    }

    private static EObject exportedPicture(String fqn)
    {
        CommonPicture copy = mock(CommonPicture.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)copy).bmGetTopObject()).thenReturn((IBmObject)copy);
        when(((IBmObject)copy).bmGetFqn()).thenReturn(fqn);
        return copy;
    }

    private static final class TestTool extends AdoptMetadataObjectTool
    {
        private final ProjectContext context;

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
}

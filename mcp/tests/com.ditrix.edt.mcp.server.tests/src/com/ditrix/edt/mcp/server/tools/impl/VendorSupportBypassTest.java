/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.xdto.model.Import;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.xdto.model.XdtoFactory;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;

/**
 * The three ways around the vendor-support guard (#642) that a write could take: a module path
 * that resolves into another project, an XDTO namespace change that cascades into a locked
 * sibling package, and a quick fix on a locked object. The platform verdict is mocked here; the
 * real one on a support file is proved by {@code test_vendor_support_guard.py}.
 */
public class VendorSupportBypassTest
{
    private static final String OLD_NS = "http://old/ns"; //$NON-NLS-1$
    private static final String NEW_NS = "http://new/ns"; //$NON-NLS-1$

    private IModelEditingSupport support;

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ==================== write_module_source: the file's own project ====================

    @Test
    public void testModulePathInsideTheNamedProjectPasses()
    {
        IProject project = project("Cfg"); //$NON-NLS-1$
        IFile file = mock(IFile.class);
        when(file.getProject()).thenReturn(project);

        assertNull(WriteModuleSourceTool.outsideProjectError(project, file, "CommonModules/A/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testModulePathResolvingIntoAnotherProjectIsRefused()
    {
        IProject named = project("ExternalObjects"); //$NON-NLS-1$
        IProject owner = project("SupportedConfiguration"); //$NON-NLS-1$
        IFile file = mock(IFile.class);
        when(file.getProject()).thenReturn(owner);

        String error = WriteModuleSourceTool.outsideProjectError(named, file,
            "D:/ws/SupportedConfiguration/src/CommonModules/Locked/Module.bsl"); //$NON-NLS-1$

        assertNotNull("an absolute path into another project must not be judged as the named one", error); //$NON-NLS-1$
        assertTrue(error, error.contains("is a file of project 'SupportedConfiguration', not of 'ExternalObjects'")); //$NON-NLS-1$
        assertTrue(error, error.contains("Nothing was changed")); //$NON-NLS-1$
        assertTrue(error, error.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testModulePathResolvingToNoFileIsAnErrorNotACrash()
    {
        String error = WriteModuleSourceTool.outsideProjectError(project("Cfg"), null, "C:/nowhere/Module.bsl"); //$NON-NLS-1$

        assertNotNull(error);
        assertTrue(error, error.contains("File not found: C:/nowhere/Module.bsl")); //$NON-NLS-1$
    }

    // ==================== modify_metadata: the XDTO namespace cascade ====================

    @Test
    public void testReferencesNamespaceDetectsWithoutChangingAnything()
    {
        Package content = XdtoFactory.eINSTANCE.createPackage();
        Import dependency = XdtoFactory.eINSTANCE.createImport();
        dependency.setNamespace(OLD_NS);
        content.getDependencies().add(dependency);
        Property property = XdtoWriter.createProperty(content.getProperties(), "P"); //$NON-NLS-1$
        property.setType(qname(OLD_NS, "T")); //$NON-NLS-1$

        assertTrue(XdtoWriter.referencesNamespace(content, OLD_NS));
        assertFalse(XdtoWriter.referencesNamespace(content, NEW_NS));
        assertEquals("detection must not rewrite the import", OLD_NS, dependency.getNamespace()); //$NON-NLS-1$
        assertEquals("detection must not rewrite the QName", OLD_NS, property.getType().getNsUri()); //$NON-NLS-1$
    }

    @Test
    public void testReferencesNamespaceSeesAQNameOnlyReference()
    {
        Package content = XdtoFactory.eINSTANCE.createPackage();
        Property property = XdtoWriter.createProperty(content.getProperties(), "P"); //$NON-NLS-1$
        property.setRef(qname(OLD_NS, "R")); //$NON-NLS-1$

        assertTrue("a ref QName alone is a reference the cascade would rewrite", //$NON-NLS-1$
            XdtoWriter.referencesNamespace(content, OLD_NS));
    }

    @Test
    public void testLockedReferrerIsNamedAndEditableOrUnrelatedAreNot()
    {
        XDTOPackage lockedReferrer = xdtoPackage("LockedRef"); //$NON-NLS-1$
        XDTOPackage openReferrer = xdtoPackage("OpenRef"); //$NON-NLS-1$
        XDTOPackage lockedUnrelated = xdtoPackage("LockedOther"); //$NON-NLS-1$
        XDTOPackage noContent = xdtoPackage("Empty"); //$NON-NLS-1$
        java.util.Map<XDTOPackage, Package> contents = new java.util.HashMap<>();
        contents.put(lockedReferrer, importing(OLD_NS));
        contents.put(openReferrer, importing(OLD_NS));
        contents.put(lockedUnrelated, importing("http://third/ns")); //$NON-NLS-1$
        List<XDTOPackage> locked = Arrays.asList(lockedReferrer, lockedUnrelated, noContent);

        List<String> names = ModifyMetadataTool.lockedReferrers(
            Arrays.asList(lockedReferrer, openReferrer, lockedUnrelated, noContent), OLD_NS, contents::get,
            pkg -> !locked.contains(pkg));

        assertEquals(Collections.singletonList("XDTOPackage.LockedRef"), names); //$NON-NLS-1$
        assertEquals("the pre-check must not rewrite the locked sibling", OLD_NS, //$NON-NLS-1$
            contents.get(lockedReferrer).getDependencies().get(0).getNamespace());
    }

    @Test
    public void testCascadeRefusalNamesEveryLockedPackageAndTheWayOut()
    {
        String refusal = VendorSupportGuard.cascadeRefusal("XDTOPackage.Open", "changed", //$NON-NLS-1$ //$NON-NLS-2$
            "its namespace change would rewrite the XDTO packages that reference '" + OLD_NS + "':", //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList("XDTOPackage.A", "XDTOPackage.B")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(refusal, refusal.startsWith("'XDTOPackage.Open' cannot be changed: its namespace change")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("XDTOPackage.A, XDTOPackage.B, which vendor support does not allow")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
    }

    // ==================== apply_quick_fix: the marker's object ====================

    @Test
    public void testQuickFixOnALockedObjectIsRefused()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog locked = MdClassFactory.eINSTANCE.createCatalog();
        when(support.canEdit(locked, EditingMode.DIRECT)).thenReturn(false);

        String refusal = ApplyQuickFixTool.markerTargetRefusal(markerOn(locked),
            MetadataScope.ofConfiguration(config), "Catalog.Locked"); //$NON-NLS-1$

        assertNotNull("a fix would change a locked object", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'Catalog.Locked' cannot be fixed:")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("under vendor support")); //$NON-NLS-1$
    }

    @Test
    public void testQuickFixOnAnEditableObjectPasses()
    {
        Catalog open = MdClassFactory.eINSTANCE.createCatalog();

        assertNull(ApplyQuickFixTool.markerTargetRefusal(markerOn(open),
            MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()), "Catalog.Open")); //$NON-NLS-1$
    }

    @Test
    public void testQuickFixWhoseObjectDoesNotResolveFailsClosed()
    {
        String refusal = ApplyQuickFixTool.markerTargetRefusal(markerOn(null),
            MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()), "Catalog.Gone"); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("could not be resolved")); //$NON-NLS-1$
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testQuickFixWhoseProviderThrowsFailsClosed()
    {
        Marker marker = mock(Marker.class);
        when(marker.provideObject(any(Function.class))).thenThrow(new IllegalStateException("model busy")); //$NON-NLS-1$

        String refusal = ApplyQuickFixTool.markerTargetRefusal(marker,
            MetadataScope.ofConfiguration(MdClassFactory.eINSTANCE.createConfiguration()), "Catalog.X"); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Cannot check whether 'Catalog.X' may be fixed")); //$NON-NLS-1$
    }

    @Test
    public void testQuickFixInAnExternalObjectsProjectIsNeverAsked()
    {
        Marker marker = mock(Marker.class);
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(
            MdClassFactory.eINSTANCE.createConfiguration(),
            MdClassFactory.eINSTANCE.createExternalDataProcessor());
        ApplyQuickFixTool.MarkerMatch match =
            new ApplyQuickFixTool.MarkerMatch(marker, "check", null, null, "message"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(ApplyQuickFixTool.vendorSupportRefusal(mock(IProject.class), external, match));
        verifyZeroInteractions(marker, support);
    }

    // ==================== helpers ====================

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static QName qname(String ns, String name)
    {
        QName qname = McoreFactory.eINSTANCE.createQName();
        qname.setNsUri(ns);
        qname.setName(name);
        return qname;
    }

    private static XDTOPackage xdtoPackage(String name)
    {
        XDTOPackage pkg = MdClassFactory.eINSTANCE.createXDTOPackage();
        pkg.setName(name);
        return pkg;
    }

    private static Package importing(String ns)
    {
        Package content = XdtoFactory.eINSTANCE.createPackage();
        Import dependency = XdtoFactory.eINSTANCE.createImport();
        dependency.setNamespace(ns);
        content.getDependencies().add(dependency);
        return content;
    }

    /** A marker whose provider hands {@code object} (possibly {@code null}) to the callback. */
    @SuppressWarnings("unchecked")
    private static Marker markerOn(EObject object)
    {
        Marker marker = mock(Marker.class);
        when(marker.provideObject(any(Function.class)))
            .thenAnswer(invocation -> ((Function<EObject, Object>)invocation.getArgument(0)).apply(object));
        return marker;
    }
}

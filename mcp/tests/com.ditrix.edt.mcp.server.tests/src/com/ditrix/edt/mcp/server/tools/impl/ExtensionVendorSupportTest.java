/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupportFactory;

/**
 * Vendor support in a configuration extension (#642): an adoption into an extension with support
 * settings is refused outright, because the adopter may change objects that cannot be listed
 * before it runs; and a quick fix whose variant may delete needs the delete permission as well.
 */
public class ExtensionVendorSupportTest
{
    private static final String EXTENSION = "SalesExtension"; //$NON-NLS-1$
    private static final String WEIGHT = "Catalog.Products.Attribute.Weight"; //$NON-NLS-1$

    private IModelEditingSupport support;
    private Configuration base;
    private Configuration extensionRoot;
    private Catalog products;

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
        base = MdClassFactory.eINSTANCE.createConfiguration();
        extensionRoot = MdClassFactory.eINSTANCE.createConfiguration();
        products = MdClassFactory.eINSTANCE.createCatalog();
        products.setName("Products"); //$NON-NLS-1$
        base.getCatalogs().add(products);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ==================== adopt_metadata_object: one rule, no receiver walk ====================

    @Test
    public void testAnExtensionWithoutSupportSettingsTakesAdoptions()
    {
        assertFalse(VendorSupportGuard.underVendorSupport(extensionRoot));
        assertNull(VendorSupportGuard.adoptionRefusal(EXTENSION, WEIGHT,
            () -> VendorSupportGuard.underVendorSupport(extensionRoot)));
    }

    @Test
    public void testAnExtensionWithSupportSettingsRefusesEveryAdoption()
    {
        extensionRoot.setDistributionSettings(DistributionSupportFactory.eINSTANCE.createDistributionSupport());
        // Every object EDT's own check could be asked about says "editable": the rule must not ask them.
        assertTrue(VendorSupportGuard.underVendorSupport(extensionRoot));

        String refusal = VendorSupportGuard.adoptionRefusal(EXTENSION, WEIGHT,
            () -> VendorSupportGuard.underVendorSupport(extensionRoot));

        assertNotNull("support anywhere in the extension refuses, whatever the receiver says", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'" + WEIGHT + "' cannot be adopted: the extension '" //$NON-NLS-1$ //$NON-NLS-2$
            + EXTENSION + "' is under vendor support")); //$NON-NLS-1$
        assertTrue("the refusal says why the receiver is not asked", //$NON-NLS-1$
            refusal.contains("cannot be listed before it runs")); //$NON-NLS-1$
        assertTrue(refusal.contains("Nothing was changed.")); //$NON-NLS-1$
        assertFalse("an extension cannot adopt into itself - no adopt advice", //$NON-NLS-1$
            refusal.contains("adopt_metadata_object")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnresolvedSupportLinkStillRefuses()
    {
        DistributionSupport settings = DistributionSupportFactory.eINSTANCE.createDistributionSupport();
        ((InternalEObject)settings).eSetProxyURI(URI.createURI("bm://ext/Configuration.distr#/")); //$NON-NLS-1$
        extensionRoot.setDistributionSettings(settings);

        assertTrue("EDT ignores an unresolved link; failing closed does not", //$NON-NLS-1$
            VendorSupportGuard.underVendorSupport(extensionRoot));
    }

    @Test
    public void testAnUnreadableSupportStateRefuses()
    {
        String refusal = VendorSupportGuard.adoptionRefusal(EXTENSION, WEIGHT, () -> {
            throw new IllegalStateException("model is rebuilding"); //$NON-NLS-1$
        });

        assertNotNull("an unanswerable check refuses", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("Cannot check whether '" + WEIGHT + "' may be adopted")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("the support settings of the extension '" + EXTENSION //$NON-NLS-1$
            + "' could not be read")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingExtensionRootRefuses()
    {
        assertNotNull(VendorSupportGuard.adoptionRefusal(EXTENSION, WEIGHT,
            () -> VendorSupportGuard.underVendorSupport(null)));
    }

    // ==================== apply_quick_fix: a variant may delete ====================

    @Test
    public void testAQuickFixOnAnEditableButUndeletableObjectIsRefused()
    {
        when(support.canDelete(products, EditingMode.DIRECT)).thenReturn(false);

        String refusal = ApplyQuickFixTool.markerTargetRefusal(markerOn(products),
            MetadataScope.ofConfiguration(base), "Catalog.Products"); //$NON-NLS-1$

        assertNotNull("a 'Remove object' variant would delete it", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'Catalog.Products' cannot be fixed: vendor support does not " //$NON-NLS-1$
            + "allow deleting")); //$NON-NLS-1$
    }

    @Test
    public void testAQuickFixOnAnEditableAndDeletableObjectPasses()
    {
        assertNull(ApplyQuickFixTool.markerTargetRefusal(markerOn(products), MetadataScope.ofConfiguration(base),
            "Catalog.Products")); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Marker markerOn(EObject object)
    {
        Marker marker = mock(Marker.class);
        when(marker.provideObject(any(Function.class)))
            .thenAnswer(invocation -> ((Function<EObject, Object>)invocation.getArgument(0)).apply(object));
        return marker;
    }
}

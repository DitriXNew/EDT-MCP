/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * A configuration extension can have vendor support of its own (EDT's DistributionSupportManager
 * answers for extension projects too): adoption is judged on the extension object that receives
 * the copy, and a quick fix whose variant may delete needs the delete permission as well (#642).
 */
public class ExtensionVendorSupportTest
{
    private IModelEditingSupport support;
    private Configuration base;
    private Configuration extensionRoot;
    private Catalog products;
    private CatalogAttribute weight;
    private Map<EObject, EObject> adopted;

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
        weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        products.getAttributes().add(weight);
        base.getCatalogs().add(products);
        adopted = new HashMap<>();
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ==================== adopt_metadata_object: the receiving object ====================

    @Test
    public void testAMemberOfAnAdoptedObjectIsReceivedByTheAdoptedCopy()
    {
        Catalog adoptedProducts = MdClassFactory.eINSTANCE.createCatalog();
        adopted.put(products, adoptedProducts);

        assertSame("EDT adds the child to the existing adopted parent - that is what changes", //$NON-NLS-1$
            adoptedProducts, AdoptMetadataObjectTool.adoptionReceiver(weight, adopted::get, extensionRoot));
    }

    @Test
    public void testAMemberOfANotYetAdoptedObjectIsReceivedByTheExtensionRoot()
    {
        assertSame(extensionRoot, AdoptMetadataObjectTool.adoptionReceiver(weight, adopted::get, extensionRoot));
    }

    @Test
    public void testATopObjectIsReceivedByTheExtensionRoot()
    {
        assertSame(extensionRoot, AdoptMetadataObjectTool.adoptionReceiver(products, adopted::get, extensionRoot));
    }

    @Test
    public void testANestedSubsystemIsReceivedByItsAdoptedParentSubsystem()
    {
        Subsystem sales = MdClassFactory.eINSTANCE.createSubsystem();
        Subsystem orders = MdClassFactory.eINSTANCE.createSubsystem();
        sales.getSubsystems().add(orders);
        orders.setParentSubsystem(sales);
        Subsystem adoptedSales = MdClassFactory.eINSTANCE.createSubsystem();
        adopted.put(sales, adoptedSales);

        assertSame(adoptedSales, AdoptMetadataObjectTool.adoptionReceiver(orders, adopted::get, extensionRoot));
    }

    @Test
    public void testALockedAdoptedParentRefusesTheAdoption()
    {
        Catalog adoptedProducts = MdClassFactory.eINSTANCE.createCatalog();
        adopted.put(products, adoptedProducts);
        when(support.canEdit(adoptedProducts, EditingMode.DIRECT)).thenReturn(false);
        EObject receiver = AdoptMetadataObjectTool.adoptionReceiver(weight, adopted::get, extensionRoot);

        String refusal = VendorSupportGuard.refusalFor(receiver, MetadataScope.ofConfiguration(extensionRoot),
            "its adopted parent 'Products'", "Catalog.Products.Attribute.Weight", "adopted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull("an editable extension root must not hide the locked adopted parent", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'Catalog.Products.Attribute.Weight' cannot be adopted: " //$NON-NLS-1$
            + "its adopted parent 'Products' is under vendor support")); //$NON-NLS-1$
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

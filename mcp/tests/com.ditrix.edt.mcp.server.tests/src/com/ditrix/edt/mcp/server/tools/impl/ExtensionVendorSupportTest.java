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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
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

/**
 * Vendor support around extensions (#642): an adoption writes only into the extension, which EDT
 * never locks, so it is not checked; a quick fix whose variant may delete needs the delete
 * permission as well as the edit one.
 */
public class ExtensionVendorSupportTest
{
    private IModelEditingSupport support;
    private Configuration base;
    private Catalog products;

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
        base = MdClassFactory.eINSTANCE.createConfiguration();
        products = MdClassFactory.eINSTANCE.createCatalog();
        products.setName("Products"); //$NON-NLS-1$
        base.getCatalogs().add(products);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    // ==================== adopt_metadata_object: support does not apply ====================

    @Test
    public void testAdoptionNeverConsultsVendorSupport() throws IOException
    {
        // EDT reads support only for a NATIVE configuration root; an extension's root is ADOPTED,
        // so nothing an adoption writes can be locked and the tool has nothing to ask.
        byte[] bytes;
        try (InputStream in = AdoptMetadataObjectTool.class.getResourceAsStream("AdoptMetadataObjectTool.class")) //$NON-NLS-1$
        {
            assertNotNull("the compiled tool must be readable", in); //$NON-NLS-1$
            bytes = in.readAllBytes();
        }
        assertFalse("adopt_metadata_object must not refuse on vendor support", //$NON-NLS-1$
            new String(bytes, StandardCharsets.ISO_8859_1).contains(
                VendorSupportGuard.class.getName().replace('.', '/')));
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

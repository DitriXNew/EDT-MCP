/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Method;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.ENamedElement;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;

/** Top-only BM access, referenceable child addresses and the shared delete labels. */
public class BmObjectAddressTest
{
    @Test
    public void testNullObjectsHaveNoAddressOrLabel()
    {
        assertNull(BmObjectAddress.topFqnOrNull(null));
        assertNull(BmObjectAddress.metadataAddressOrNull(null));
        assertNull(BmObjectAddress.uriOrNull(null));
        assertNull(BmObjectAddress.labelOrNull(null));
    }

    @Test
    public void testTopFqnComesDirectlyFromTheTopObject()
    {
        IBmObject top = top();
        assertEquals("Catalog.Goods", BmObjectAddress.topFqnOrNull(top)); //$NON-NLS-1$
        verify(top, never()).bmGetTopObject();
    }

    @Test
    public void testContainedFqnComesFromItsOwner()
    {
        IBmObject child = child();
        assertEquals("Catalog.Goods", BmObjectAddress.topFqnOrNull(child)); //$NON-NLS-1$
        verify(child, never()).bmGetFqn();
    }

    @Test
    public void testTransientObjectHasNoTopFqn()
    {
        IBmObject object = mock(IBmObject.class);
        when(object.bmGetTopObject()).thenThrow(new RuntimeException(
            "bmGetTopObject may not be invoked on a transient object")); //$NON-NLS-1$
        assertNull(BmObjectAddress.topFqnOrNull(object));
        assertNull(BmObjectAddress.metadataAddressOrNull(object));
    }

    @Test
    public void testDetachedTopHasNoFqn()
    {
        IBmObject object = top();
        when(object.bmGetFqn()).thenThrow(new RuntimeException("attached BM objects only")); //$NON-NLS-1$
        assertNull(BmObjectAddress.topFqnOrNull(object));
        assertNull(BmObjectAddress.metadataAddressOrNull(object));
    }

    @Test
    public void testMissingOwnerHasNoTopFqn()
    {
        assertNull(BmObjectAddress.topFqnOrNull(mock(IBmObject.class)));
    }

    @Test
    public void testContainedBmUriKeepsTheContainmentPath()
    {
        String uri = "bm://TestConfiguration/Catalog.Goods#/attributes:List/extInfo"; //$NON-NLS-1$
        assertEquals(uri, BmObjectAddress.uriOrNull(withUri(uri)));
    }

    @Test
    public void testTopBmUriIsReferenceable()
    {
        String uri = "bm://TestConfiguration/Catalog.Goods#/"; //$NON-NLS-1$
        assertEquals(uri, BmObjectAddress.uriOrNull(withUri(uri)));
    }

    @Test
    public void testNonBmUriIsNotAnAddress()
    {
        assertNull(BmObjectAddress.uriOrNull(withUri("file:/detached.model#child"))); //$NON-NLS-1$
    }

    @Test
    public void testUnreferenceableBmFragmentIsNotAnAddress()
    {
        assertNull(BmObjectAddress.uriOrNull(withUri("bm://TestConfiguration/Catalog.Goods#/-1"))); //$NON-NLS-1$
    }

    @Test
    public void testMissingUriIsNotAnAddress()
    {
        assertNull(BmObjectAddress.uriOrNull(mock(IBmObject.class)));
    }

    @Test
    public void testUnavailableUriIsNotAnAddress()
    {
        IBmObject object = mock(IBmObject.class);
        when(object.bmGetUri()).thenThrow(new RuntimeException("detached")); //$NON-NLS-1$
        assertNull(BmObjectAddress.uriOrNull(object));
    }

    @Test
    public void testLabelOfTopIsItsFqn()
    {
        assertEquals("Catalog.Goods", BmObjectAddress.labelOrNull(top())); //$NON-NLS-1$
    }

    @Test
    public void testMetadataChildLabelIncludesItsLocalName()
    {
        IBmObject child = mock(IBmObject.class, withSettings().extraInterfaces(MdObject.class));
        when(((MdObject)child).getName()).thenReturn("Weight"); //$NON-NLS-1$
        IBmObject owner = top();
        when(child.bmGetTopObject()).thenReturn(owner);
        assertEquals("Catalog.Goods (Weight)", BmObjectAddress.labelOrNull(child)); //$NON-NLS-1$
    }

    @Test
    public void testEcoreChildLabelIncludesItsLocalName()
    {
        IBmObject child = mock(IBmObject.class, withSettings().extraInterfaces(ENamedElement.class));
        when(((ENamedElement)child).getName()).thenReturn("Weight"); //$NON-NLS-1$
        IBmObject owner = top();
        when(child.bmGetTopObject()).thenReturn(owner);
        assertEquals("Catalog.Goods (Weight)", BmObjectAddress.labelOrNull(child)); //$NON-NLS-1$
    }

    @Test
    public void testUnnamedChildLabelIsTheOwner()
    {
        assertEquals("Catalog.Goods", BmObjectAddress.labelOrNull(child())); //$NON-NLS-1$
    }

    @Test
    public void testTransientNamedChildLabelFallsBackToItsLocalName()
    {
        IBmObject child = mock(IBmObject.class, withSettings().extraInterfaces(MdObject.class));
        when(((MdObject)child).getName()).thenReturn("Weight"); //$NON-NLS-1$
        when(child.bmGetTopObject()).thenThrow(new RuntimeException("transient")); //$NON-NLS-1$
        assertEquals("Weight", BmObjectAddress.labelOrNull(child)); //$NON-NLS-1$
    }

    @Test
    public void testDeleteDelegatePreservesTheSharedLabel() throws Exception
    {
        IBmObject child = mock(IBmObject.class, withSettings().extraInterfaces(MdObject.class));
        when(((MdObject)child).getName()).thenReturn("Weight"); //$NON-NLS-1$
        IBmObject owner = top();
        when(child.bmGetTopObject()).thenReturn(owner);
        Method method = DeleteMetadataTool.class.getDeclaredMethod("bmFqnSafe", IBmObject.class); //$NON-NLS-1$
        method.setAccessible(true);
        assertEquals("Catalog.Goods (Weight)", method.invoke(null, child)); //$NON-NLS-1$
        assertEquals(BmObjectAddress.labelOrNull(child), method.invoke(null, child));
    }

    private static IBmObject top()
    {
        IBmObject object = mock(IBmObject.class);
        when(object.bmIsTop()).thenReturn(true);
        when(object.bmGetFqn()).thenReturn("Catalog.Goods"); //$NON-NLS-1$
        return object;
    }

    private static IBmObject child()
    {
        IBmObject object = mock(IBmObject.class);
        when(object.bmGetFqn()).thenThrow(new RuntimeException("top objects only")); //$NON-NLS-1$
        IBmObject owner = top();
        when(object.bmGetTopObject()).thenReturn(owner);
        return object;
    }

    private static IBmObject withUri(String uri)
    {
        IBmObject object = mock(IBmObject.class);
        when(object.bmGetUri()).thenReturn(URI.createURI(uri));
        return object;
    }
}

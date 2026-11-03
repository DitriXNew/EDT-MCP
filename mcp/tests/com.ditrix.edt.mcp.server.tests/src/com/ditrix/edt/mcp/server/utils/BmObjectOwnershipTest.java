/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;

/** Store-less objects require BM's CREATED status and the current namespace/transaction identity. */
public class BmObjectOwnershipTest
{
    private final IBmTransaction tx = mock(IBmTransaction.class);
    private final IBmObject object = mock(IBmObject.class);
    private IBmNamespace namespace;
    private IBmPlatformTransaction platform;
    private IBmNamespaceDataStore primary;

    @Before
    public void setUp() throws Exception
    {
        namespace = BmCreationTestFixture.namespace();
        platform = BmCreationTestFixture.platform();
        primary = BmCreationTestFixture.store();
        when(tx.getNamespace()).thenReturn(namespace);
        when(tx.getPlatformTransaction()).thenReturn(platform);
        when(namespace.getPrimaryStore()).thenReturn(primary);
        when(object.bmGetNamespace()).thenReturn(namespace);
        when(object.bmGetId()).thenReturn(103079215104L);
    }

    @Test
    public void testCreatedInTheCurrentWriteIsPrimaryBeforeStoreAssignment() throws Exception
    {
        status("CREATED");
        assertTrue(BmObjectOwnership.isPrimary(tx, object));
    }

    @Test
    public void testNullStoreDoesNotAdmitExistingRemovedOrRestoredObjects() throws Exception
    {
        for (String status : new String[] {"INTACT", "MODIFIED", "REMOVED", "RESTORED"})
        {
            status(status);
            assertFalse(status, BmObjectOwnership.isPrimary(tx, object));
        }
    }

    @Test
    public void testForeignNamespaceCannotUseThisWriteCreationFlag() throws Exception
    {
        status("CREATED");
        when(object.bmGetNamespace()).thenReturn(BmCreationTestFixture.namespace());
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
        verify(platform, never()).getContainingDataStore(any());
    }

    @Test
    public void testAnotherTransactionsCreatedObjectIsRefused() throws Exception
    {
        IBmTransaction another = mock(IBmTransaction.class);
        IBmPlatformTransaction anotherPlatform = BmCreationTestFixture.platform();
        when(another.getNamespace()).thenReturn(namespace);
        when(another.getPlatformTransaction()).thenReturn(anotherPlatform);
        IBmObject created = BmCreationTestFixture.object(another, object.bmGetId(), "CREATED");
        when(tx.toTransactionObject(object)).thenReturn(created);
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
    }

    @Test
    public void testReadTransactionCannotClaimUnstoredContent() throws Exception
    {
        status("CREATED");
        when(tx.isReadOnly()).thenReturn(true);
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
        verify(tx, never()).toTransactionObject(any());
    }

    @Test
    public void testCommittedPrimaryAndSecondaryStoresRemainDistinct()
    {
        when(platform.getContainingDataStore(object)).thenReturn(primary);
        assertTrue(BmObjectOwnership.isPrimary(tx, object));
        when(platform.getContainingDataStore(object)).thenReturn(mock(IBmNamespaceDataStore.class));
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
        when(object.bmGetNamespace()).thenReturn(null);
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
    }

    @Test
    public void testUnresolvedOrDetachedObjectsCannotClaimOwnership()
    {
        when(object.bmGetId()).thenReturn(IBmObject.BM_NULL_ID);
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
        when(object.bmGetId()).thenReturn(103079215104L);
        when(object.eIsProxy()).thenReturn(true);
        assertFalse(BmObjectOwnership.isPrimary(tx, object));
    }

    private void status(String flag) throws Exception
    {
        IBmObject created = BmCreationTestFixture.object(tx, object.bmGetId(), flag);
        when(tx.toTransactionObject(object)).thenReturn(created);
    }
}

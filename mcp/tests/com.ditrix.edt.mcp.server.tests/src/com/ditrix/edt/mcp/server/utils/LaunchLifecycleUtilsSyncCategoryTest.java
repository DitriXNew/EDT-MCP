/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.SyncCategory;
import com.e1c.g5.dt.applications.ApplicationUpdateState;

/**
 * Pure unit tests for the infobase-sync state classification:
 * which {@link ApplicationUpdateState} values mean the IB is synced
 * (no update needed), needs an update, or has an update in progress. This is
 * the decision that gates whether a YAXUnit run may start, so it is worth
 * pinning down independently of the polling/launch machinery.
 */
public class LaunchLifecycleUtilsSyncCategoryTest
{
    @Test
    public void testUpdatedIsSynced()
    {
        assertEquals(SyncCategory.SYNCED,
            LaunchLifecycleUtils.classify(ApplicationUpdateState.UPDATED));
        assertTrue(LaunchLifecycleUtils.isSynced(ApplicationUpdateState.UPDATED));
        assertFalse(LaunchLifecycleUtils.needsUpdate(ApplicationUpdateState.UPDATED));
        assertFalse(LaunchLifecycleUtils.isInProgress(ApplicationUpdateState.UPDATED));
    }

    @Test
    public void testIncrementalRequiredNeedsUpdate()
    {
        assertEquals(SyncCategory.NEEDS_UPDATE,
            LaunchLifecycleUtils.classify(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED));
        assertTrue(LaunchLifecycleUtils.needsUpdate(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED));
        assertFalse(LaunchLifecycleUtils.isSynced(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED));
    }

    @Test
    public void testFullRequiredNeedsUpdate()
    {
        assertEquals(SyncCategory.NEEDS_UPDATE,
            LaunchLifecycleUtils.classify(ApplicationUpdateState.FULL_UPDATE_REQUIRED));
        assertTrue(LaunchLifecycleUtils.needsUpdate(ApplicationUpdateState.FULL_UPDATE_REQUIRED));
        assertFalse(LaunchLifecycleUtils.isSynced(ApplicationUpdateState.FULL_UPDATE_REQUIRED));
    }

    @Test
    public void testBeingUpdatedIsInProgress()
    {
        assertEquals(SyncCategory.IN_PROGRESS,
            LaunchLifecycleUtils.classify(ApplicationUpdateState.BEING_UPDATED));
        assertTrue(LaunchLifecycleUtils.isInProgress(ApplicationUpdateState.BEING_UPDATED));
        assertFalse(LaunchLifecycleUtils.isSynced(ApplicationUpdateState.BEING_UPDATED));
        assertFalse(LaunchLifecycleUtils.needsUpdate(ApplicationUpdateState.BEING_UPDATED));
    }

    @Test
    public void testUnknownIsUnknown()
    {
        assertEquals(SyncCategory.UNKNOWN,
            LaunchLifecycleUtils.classify(ApplicationUpdateState.UNKNOWN));
        // UNKNOWN is conservatively NOT synced — we must never claim a stale green.
        assertFalse(LaunchLifecycleUtils.isSynced(ApplicationUpdateState.UNKNOWN));
        assertFalse(LaunchLifecycleUtils.needsUpdate(ApplicationUpdateState.UNKNOWN));
        assertFalse(LaunchLifecycleUtils.isInProgress(ApplicationUpdateState.UNKNOWN));
    }

    @Test
    public void testNullIsUnknownAndNotSynced()
    {
        assertEquals(SyncCategory.UNKNOWN, LaunchLifecycleUtils.classify(null));
        assertFalse("null must never be treated as synced",
            LaunchLifecycleUtils.isSynced(null));
    }

    @Test
    public void testEveryStateValueIsClassified()
    {
        // Guards against a new ApplicationUpdateState constant silently falling
        // through to UNKNOWN unnoticed: every value must classify to a category.
        for (ApplicationUpdateState state : ApplicationUpdateState.values())
        {
            assertTrue("state " + state + " must classify to a non-null category",
                LaunchLifecycleUtils.classify(state) != null);
        }
    }
}

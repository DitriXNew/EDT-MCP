/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ApplicationUpdatePolicy.Outcome;
import com.ditrix.edt.mcp.server.utils.ApplicationUpdatePolicy.Result;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;

/**
 * Tests the single canonical interpretation of {@link ApplicationUpdateState} in
 * {@link ApplicationUpdatePolicy}. The crux is that
 * {@code INCREMENTAL_UPDATE_REQUIRED} after an incremental update is a SUCCESS
 * (the cosmetic extension state — the changes ARE published), while only
 * {@code FULL_UPDATE_REQUIRED} genuinely needs a full update.
 */
public class ApplicationUpdatePolicyTest
{
    // ── Pre-launch path (classifyPostUpdate, always incremental) ──────────────

    @Test
    public void testPostUpdate_Updated_isSuccess()
    {
        Result r = ApplicationUpdatePolicy.classifyPostUpdate(ApplicationUpdateState.UPDATED);
        assertEquals(Outcome.SUCCESS, r.outcome());
        assertTrue(r.isSuccess());
    }

    @Test
    public void testPostUpdate_IncrementalRequired_isSuccess()
    {
        // The fix: this is the cosmetic state for an extension-bearing config; the
        // changes ARE published, so it must NOT be treated as a failure.
        Result r = ApplicationUpdatePolicy.classifyPostUpdate(
            ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        assertEquals(Outcome.SUCCESS, r.outcome());
        assertTrue(r.isSuccess());
    }

    @Test
    public void testPostUpdate_FullRequired_needsFullAndIsActionable()
    {
        Result r = ApplicationUpdatePolicy.classifyPostUpdate(
            ApplicationUpdateState.FULL_UPDATE_REQUIRED);
        assertEquals(Outcome.NEEDS_FULL_UPDATE, r.outcome());
        assertFalse(r.isSuccess());
        // Existing LaunchLifecycleUtilsUpdateTest pins these tokens in the surfaced error.
        assertTrue("must surface the state", r.message().contains("FULL_UPDATE_REQUIRED"));
        assertTrue("must point at update_database", r.message().contains("update_database"));
        assertTrue("must name the remedy", r.message().contains("fullUpdate=true"));
    }

    @Test
    public void testPostUpdate_BeingUpdated_isInProgress()
    {
        Result r = ApplicationUpdatePolicy.classifyPostUpdate(
            ApplicationUpdateState.BEING_UPDATED);
        assertEquals(Outcome.IN_PROGRESS, r.outcome());
        assertFalse(r.isSuccess());
    }

    @Test
    public void testPostUpdate_Null_isFailed()
    {
        Result r = ApplicationUpdatePolicy.classifyPostUpdate(null);
        assertEquals(Outcome.FAILED, r.outcome());
        assertFalse(r.isSuccess());
    }

    // ── Explicit path (classifyExplicitUpdate, requested type matters) ─────────

    @Test
    public void testExplicitIncremental_IncrementalRequired_isSuccess()
    {
        Result r = ApplicationUpdatePolicy.classifyExplicitUpdate(
            ApplicationUpdateType.INCREMENTAL, ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        assertEquals(Outcome.SUCCESS, r.outcome());
    }

    @Test
    public void testExplicitFull_IncrementalRequired_isFailed()
    {
        // A FULL update should leave the IB UPDATED; still asking for an incremental
        // means the full update did not settle — that IS a failure.
        Result r = ApplicationUpdatePolicy.classifyExplicitUpdate(
            ApplicationUpdateType.FULL, ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        assertEquals(Outcome.FAILED, r.outcome());
    }

    @Test
    public void testExplicitIncremental_FullRequired_needsFull()
    {
        Result r = ApplicationUpdatePolicy.classifyExplicitUpdate(
            ApplicationUpdateType.INCREMENTAL, ApplicationUpdateState.FULL_UPDATE_REQUIRED);
        assertEquals(Outcome.NEEDS_FULL_UPDATE, r.outcome());
        assertTrue(r.message().contains("fullUpdate=true"));
    }

    @Test
    public void testExplicitFull_FullRequired_isFailed()
    {
        Result r = ApplicationUpdatePolicy.classifyExplicitUpdate(
            ApplicationUpdateType.FULL, ApplicationUpdateState.FULL_UPDATE_REQUIRED);
        assertEquals(Outcome.FAILED, r.outcome());
    }

    @Test
    public void testExplicitFull_Updated_isSuccess()
    {
        Result r = ApplicationUpdatePolicy.classifyExplicitUpdate(
            ApplicationUpdateType.FULL, ApplicationUpdateState.UPDATED);
        assertEquals(Outcome.SUCCESS, r.outcome());
    }
}

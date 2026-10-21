/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/** The readiness wait must finish on the request thread before the scan enters syncExec. */
public class FindReferencesToolSettleTest
{
    @Test
    public void settlesForTenSecondsBeforeHandingTheScanToTheUiRunner()
    {
        List<String> events = new ArrayList<>();
        Thread caller = Thread.currentThread();
        FindReferencesTool tool = new FindReferencesTool((name, budget) -> {
            assertSame(caller, Thread.currentThread());
            events.add("settle:" + name + ":" + budget);
            return null;
        }, runnable -> events.add("ui"));

        tool.execute(Map.of("projectName", "P", "objectFqn", "Catalog.X"));

        assertEquals(10_000L, FindReferencesTool.SEARCH_SCOPE_SETTLE_TIMEOUT_MS);
        assertEquals(List.of("settle:P:10000", "ui"), events);
    }

    @Test
    public void missingArgumentsNeverReachTheSettlerOrTheUiRunner()
    {
        FindReferencesTool tool = new FindReferencesTool((name, budget) -> {
            fail("invalid input settled");
            return null;
        }, runnable -> fail("invalid input reached the UI"));
        assertTrue(tool.execute(Map.of("projectName", "P")).contains("objectFqn is required"));
        assertTrue(tool.execute(Map.of("objectFqn", "Catalog.X")).contains("projectName is required"));
    }

    /**
     * The injected-settler tests above prove the ORDER; this proves what the public constructor - the
     * one the registry uses - actually wired: only ProjectStateChecker.awaitSearchScopeReady answers a
     * blank name with an undetermined snapshot (a no-op settler has nothing to return).
     */
    @Test
    public void thePublicConstructorWiresTheRealSearchScopeWait()
    {
        FindReferencesTool.SearchScopeSettler settler = new FindReferencesTool().searchScopeSettler();
        SearchDependenciesResult blank = settler.settle("", FindReferencesTool.SEARCH_SCOPE_SETTLE_TIMEOUT_MS);
        assertNotNull("the public constructor must wire the real search-scope wait", blank);
        assertFalse(blank.isDetermined());
        SearchDependenciesResult missing = settler.settle(null, 0);
        assertNotNull(missing);
        assertFalse(missing.isDetermined());
    }
}

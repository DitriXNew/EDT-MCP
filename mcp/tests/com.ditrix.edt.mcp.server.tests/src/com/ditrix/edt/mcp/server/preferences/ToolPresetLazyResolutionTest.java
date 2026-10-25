/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

/** First-use decisions apply before IStartup and are computed/published exactly once. */
public class ToolPresetLazyResolutionTest extends ToolPresetSessionTestSupport
{
    @Test
    public void testFirstIsToolEnabledAppliesLaunchPresetWithoutEarlyStartup() throws Exception
    {
        configureLaunch("analysis-only");
        assertNull("launch selection must remain lazy", sessionHolder());
        assertFalse("bridge and manual-start calls must use the launch preset immediately",
            registry.isToolEnabled("create_project"));
        assertTrue("launch selection replaces stored exclusions", service.isToolEnabled("list_projects"));
        assertEquals(ToolPreset.ANALYSIS_ONLY, activePreset());
        assertEquals(1, environmentReads.get());
        assertEquals(List.of(ENV_PRESET), environmentNames);
    }

    @Test
    public void testUnknownIdWarnsOnceAtFirstResolution() throws Exception
    {
        configureLaunch("unknown-launch-preset");
        assertTrue("configuration alone must not warn", presetWarnings().isEmpty());
        for (int i = 0; i < 5; i++)
        {
            assertTrue(service.isToolEnabled("create_project"));
            assertEquals(storedDisabled(), service.getDisabledTools());
            assertNull(activePreset());
        }
        assertEquals("invalid values are resolved once, too", 1, environmentReads.get());
        assertEquals("one WARNING for the entire launch", 1, presetWarnings().size());
        assertEquals(IStatus.WARNING, presetWarnings().get(0).getSeverity());
    }

    @Test
    public void testUnsetBlankAndCustomResolveOnlyOnce() throws Exception
    {
        for (String value : new String[] {null, "", "  ", "custom", " CUSTOM "})
        {
            configureLaunch(value);
            assertEquals(storedDisabled(), service.getDisabledTools());
            Object first = sessionHolder();
            assertNotNull("a stored-selection launch still needs a resolved holder", first);
            for (int i = 0; i < 5; i++)
            {
                assertNull(activePreset());
                assertEquals(storedDisabled(), service.getDisabledTools());
                assertSame(first, sessionHolder());
            }
            assertEquals("one environment read for " + value, 1, environmentReads.get());
        }
        assertTrue(presetWarnings().isEmpty());
    }

    @Test
    public void testEveryPresetIdIsCaseInsensitiveAndTrimmed() throws Exception
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            configureLaunch("  " + preset.getId().toUpperCase(Locale.ROOT) + "  ");
            assertEquals(preset == ToolPreset.CUSTOM ? storedDisabled() : preset.getDisabledTools(),
                service.getDisabledTools());
            assertEquals(preset == ToolPreset.CUSTOM ? null : preset, activePreset());
            assertEquals(1, environmentReads.get());
        }
        assertTrue("case variants are valid IDs", presetWarnings().isEmpty());
    }

    @Test
    public void testResolvedPresetIsReusedForTheLaunch() throws Exception
    {
        configureLaunch("analysis-only");
        assertFalse(service.isToolEnabled("create_project"));
        Object first = sessionHolder();
        for (int i = 0; i < 5; i++)
        {
            assertEquals(ToolPreset.ANALYSIS_ONLY, activePreset());
            assertFalse(service.isToolEnabled("clean_project"));
            assertSame("resolution must not clear or replace the published holder", first, sessionHolder());
        }
        assertEquals(1, environmentReads.get());
    }

    @Test
    public void testConcurrentFirstUsePublishesOnlyTheCompletedDecision() throws Exception
    {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        setEnvironment(new Function<String, String>()
        {
            @Override
            public String apply(String name)
            {
                environmentReads.incrementAndGet();
                environmentNames.add(name);
                resolving.countDown();
                try
                {
                    if (!release.await(5, TimeUnit.SECONDS))
                    {
                        throw new AssertionError("test did not release the environment reader");
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return "analysis-only";
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> decisions = new ArrayList<>();
        try
        {
            decisions.add(workers.submit(() -> service.isToolEnabled("create_project")));
            assertTrue("first decision must resolve without earlyStartup", resolving.await(3, TimeUnit.SECONDS));
            assertNull("no partial holder may be published while resolution is in progress", sessionHolder());
            for (int i = 0; i < 3; i++)
            {
                decisions.add(workers.submit(() -> service.isToolEnabled("create_project")));
            }
            release.countDown();
            for (Future<Boolean> decision : decisions)
            {
                assertFalse("every concurrent reader must see the completed launch selection",
                    decision.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, environmentReads.get());
            assertNotNull(sessionHolder());
        }
        finally
        {
            release.countDown();
            workers.shutdownNow();
            assertTrue("worker cleanup", workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

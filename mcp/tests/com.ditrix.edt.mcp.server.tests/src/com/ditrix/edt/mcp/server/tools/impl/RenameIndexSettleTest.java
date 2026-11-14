/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

/**
 * The rename pre-flight waits for the workspace build BEFORE it settles the derived data, so a
 * module written right before the rename is in the BSL index the rename's reference finder reads
 * (#935): {@link RenameMetadataObjectTool#indexedThenSettled} and the production wiring.
 */
public class RenameIndexSettleTest
{
    private static final String PROJECT = "Project935"; //$NON-NLS-1$

    @Test
    public void testTheBuildIsAwaitedBeforeTheCascadeSettle()
    {
        List<String> order = new ArrayList<>();
        RenameMetadataObjectTool.CascadeSettler settler = RenameMetadataObjectTool.indexedThenSettled(
            timeout -> {
                order.add("build"); //$NON-NLS-1$
                return true;
            },
            (project, timeout) -> {
                order.add("cascade:" + project); //$NON-NLS-1$
                return null;
            });

        assertNull(settler.settle(PROJECT, 60_000));
        assertEquals(List.of("build", "cascade:" + PROJECT), order); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testACascadeRefusalIsPassedThrough()
    {
        RenameMetadataObjectTool.CascadeSettler settler = RenameMetadataObjectTool.indexedThenSettled(
            timeout -> true, (project, timeout) -> "still building"); //$NON-NLS-1$

        assertEquals("still building", settler.settle(PROJECT, 60_000)); //$NON-NLS-1$
    }

    @Test
    public void testABuildStillPendingRefusesWithoutSettlingOrRenaming()
    {
        List<String> cascade = new ArrayList<>();
        RenameMetadataObjectTool.CascadeSettler settler = RenameMetadataObjectTool.indexedThenSettled(
            timeout -> false, (project, timeout) -> {
                cascade.add(project);
                return null;
            });

        String refusal = settler.settle(PROJECT, 60_000);

        assertNotNull("a pending build must refuse the rename, not let it run on a stale index", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("Project is building: the workspace build that indexes the BSL modules")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("within 60s")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("write_module_source")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was renamed.")); //$NON-NLS-1$
        assertTrue(refusal, refusal.endsWith("Please wait and retry.")); //$NON-NLS-1$
        assertTrue("the cascade settle must not run once the build wait failed", cascade.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testTheBuildGetsTheWholeBudgetAndTheCascadeWhatIsLeft()
    {
        AtomicLong buildBudget = new AtomicLong(-1);
        AtomicLong cascadeBudget = new AtomicLong(-1);
        RenameMetadataObjectTool.CascadeSettler settler = RenameMetadataObjectTool.indexedThenSettled(
            timeout -> {
                buildBudget.set(timeout);
                sleep(400);
                return true;
            },
            (project, timeout) -> {
                cascadeBudget.set(timeout);
                return null;
            });

        settler.settle(PROJECT, 10_000);

        assertEquals(10_000, buildBudget.get());
        assertTrue("the cascade shares the deadline: " + cascadeBudget.get(), //$NON-NLS-1$
            cascadeBudget.get() >= 0 && cascadeBudget.get() <= 9_650);
    }

    @Test
    public void testTheCascadeBudgetNeverGoesNegative()
    {
        AtomicLong cascadeBudget = new AtomicLong(-1);
        RenameMetadataObjectTool.CascadeSettler settler = RenameMetadataObjectTool.indexedThenSettled(
            timeout -> {
                sleep(150);
                return true;
            },
            (project, timeout) -> {
                cascadeBudget.set(timeout);
                return null;
            });

        settler.settle(PROJECT, 50);

        assertEquals(0, cascadeBudget.get());
    }

    @Test
    public void testTheProductionSettlerWaitsForAPendingWorkspaceBuild()
    {
        // A job in the auto-build family, scheduled with a delay exactly like the AutoBuildJob a
        // module write leaves behind. The project does not exist, so the derived-data settle that
        // follows returns at once and the elapsed time is the build wait alone.
        Job build = new Job("test #935 auto-build stand-in") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                RenameIndexSettleTest.sleep(300);
                return Status.OK_STATUS;
            }

            @Override
            public boolean belongsTo(Object family)
            {
                return family == ResourcesPlugin.FAMILY_AUTO_BUILD;
            }
        };
        build.setSystem(true);
        build.schedule(600);
        long start = System.nanoTime();

        String refusal = RenameMetadataObjectTool.productionSettler().settle("NoSuchProject935", 30_000); //$NON-NLS-1$

        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNull(refusal);
        assertNotNull("the production pre-flight returned before the pending build had run", build.getResult()); //$NON-NLS-1$
        assertTrue("waited " + elapsed + " ms", elapsed >= 800); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}

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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings.ParameterDef;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.ProjectCleanInfo;

/**
 * Tests for {@link CleanProjectTool}.
 * <p>
 * {@code projectName} is optional (absent = clean all EDT projects) and both
 * branches go through {@code ProjectStateChecker} / the live clean-build
 * lifecycle, so there is no argument-validation branch reachable before live
 * access. This is a destructive tool — the tests assert the static contract only
 * and never invoke {@code execute()}; cleaning is covered by the E2E suite.
 * <p>
 * The clean-build phase is the exception: it is reachable through the
 * {@link CleanProjectTool.ICleanAction} seam, so the deadline that keeps a wedged platform
 * call from holding the MCP request open forever (issue #349) is tested here with a
 * controllable action and no live workspace.
 */
public class CleanProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("clean_project", new CleanProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CleanProjectTool.NAME, new CleanProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CleanProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CleanProjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new CleanProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue("the clean-build bound must be reachable from the wire", //$NON-NLS-1$
            schema.contains("\"timeout\"")); //$NON-NLS-1$
    }

    /**
     * Deadline for the wedged-clean test. The deadline starts when the job is scheduled, so it
     * must stay well above the job-start latency — a job cancelled before it ran would never set
     * the current project, and the "names the project" assertion would have nothing to see.
     */
    private static final long SHORT_TIMEOUT_MS = 2000;

    /** Ceiling on the wedged action — finite, so a lost deadline fails instead of hanging the suite. */
    private static final long WEDGE_CEILING_MS = 60_000;

    /** Bound that a bounded call must beat comfortably. */
    private static final long SANE_RETURN_MS = 30_000;

    @Test
    public void testWedgedCleanFailsOnItsDeadlineWithAnActionableError() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        List<ProjectCleanInfo> projects =
            Collections.singletonList(new ProjectCleanInfo(null, null, "Demo")); //$NON-NLS-1$
        try
        {
            long startMs = System.currentTimeMillis();
            String error = CleanProjectTool.runCleanPhase(projects, SHORT_TIMEOUT_MS,
                (info, monitor) -> {
                    started.countDown();
                    awaitQuietly(release);
                });
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Asserted first: a stalled scheduler must report as "the clean never started", not as
            // "the error text is wrong".
            assertTrue("the clean action must have started for this test to judge its message", //$NON-NLS-1$
                started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));

            // Without the deadline this returns only when the action's own ceiling expires.
            assertTrue("a wedged clean must fail on its deadline, not on the action's ceiling (waited " //$NON-NLS-1$
                + elapsedMs + "ms)", elapsedMs < SANE_RETURN_MS); //$NON-NLS-1$
            assertNotNull("a missed deadline must produce an error payload", error); //$NON-NLS-1$
            assertTrue("the error must say what did not finish: " + error, //$NON-NLS-1$
                error.contains("Clean build did not finish within")); //$NON-NLS-1$
            assertTrue("the error must name the project it was cleaning: " + error, //$NON-NLS-1$
                error.contains("Demo")); //$NON-NLS-1$
            assertTrue("the error must say the model may still be rebuilding: " + error, //$NON-NLS-1$
                error.contains("list_projects")); //$NON-NLS-1$
            assertTrue("the error must name the lever that raises the bound: " + error, //$NON-NLS-1$
                error.contains("'timeout'")); //$NON-NLS-1$
            assertTrue("a running clean has an unknown mutation outcome, so isolation must reset: " //$NON-NLS-1$
                + error, error.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * Cleaning ALL projects must stop at the deadline, not walk on to the next project after the
     * caller already got its timeout answer. Without the cancellation check the loop would clean
     * the second project long after the MCP call returned.
     */
    @Test
    public void testDeadlineStopsTheLoopInsteadOfCleaningTheNextProject() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicBoolean secondWasCleaned = new AtomicBoolean(false);
        List<ProjectCleanInfo> projects = Arrays.asList(
            new ProjectCleanInfo(null, null, "First"), //$NON-NLS-1$
            new ProjectCleanInfo(null, null, "Second")); //$NON-NLS-1$
        try
        {
            String error = CleanProjectTool.runCleanPhase(projects, SHORT_TIMEOUT_MS, (info, monitor) -> {
                if ("Second".equals(info.name)) //$NON-NLS-1$
                {
                    secondWasCleaned.set(true);
                    return;
                }
                firstStarted.countDown();
                awaitQuietly(release);
            });

            assertTrue("the first clean must have started", //$NON-NLS-1$
                firstStarted.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
            assertNotNull("a wedged first project must still produce the timeout error", error); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }

        // Give the abandoned job a chance to misbehave: once released, an unguarded loop would
        // proceed to the second project. The guard must stop it even though the job is still alive.
        Thread.sleep(500);
        assertTrue("the deadline must stop the loop, not let it clean the next project after the " //$NON-NLS-1$
            + "call already returned", !secondWasCleaned.get()); //$NON-NLS-1$
    }

    @Test
    public void testCleanThatFinishesReportsNoError()
    {
        AtomicBoolean cleaned = new AtomicBoolean(false);
        List<ProjectCleanInfo> projects =
            Collections.singletonList(new ProjectCleanInfo(null, null, "Demo")); //$NON-NLS-1$

        String error = CleanProjectTool.runCleanPhase(projects, WEDGE_CEILING_MS,
            (info, monitor) -> cleaned.set(true));

        assertTrue("the action must have run", cleaned.get()); //$NON-NLS-1$
        assertNull("a completed clean phase reports no error", error); //$NON-NLS-1$
    }

    @Test
    public void testCleanFailureIsReportedWithItsOwnMessage()
    {
        List<ProjectCleanInfo> projects =
            Collections.singletonList(new ProjectCleanInfo(null, null, "Demo")); //$NON-NLS-1$

        String error = CleanProjectTool.runCleanPhase(projects, WEDGE_CEILING_MS, (info, monitor) -> {
            throw new CoreException(new Status(IStatus.ERROR, "test", "refresh refused")); //$NON-NLS-1$ //$NON-NLS-2$
        });

        assertNotNull("a failing clean must produce an error payload", error); //$NON-NLS-1$
        assertTrue("the platform's own message must survive: " + error, //$NON-NLS-1$
            error.contains("refresh refused")); //$NON-NLS-1$
        assertTrue("a real failure must not be dressed up as a timeout: " + error, //$NON-NLS-1$
            !error.contains("did not finish within")); //$NON-NLS-1$
        assertTrue("a clean action that threw may already have changed the model: " + error, //$NON-NLS-1$
            error.contains("\"mutationOutcomeUnknown\":true")); //$NON-NLS-1$
    }

    /**
     * The configurable default and the tool's accepted range are two statements of one rule.
     * Pinned in both directions so moving either one alone reddens.
     */
    @Test
    public void testConfigurableTimeoutMatchesTheToolsOwnBounds()
    {
        List<ParameterDef> params =
            ToolParameterSettings.getInstance().getParametersForTool(CleanProjectTool.NAME);
        assertEquals("clean_project publishes exactly one configurable parameter", 1, params.size()); //$NON-NLS-1$
        ParameterDef timeout = params.get(0);
        assertEquals(CleanProjectTool.KEY_TIMEOUT, timeout.getName());
        assertEquals("the configured default must be the tool's own default", //$NON-NLS-1$
            CleanProjectTool.DEFAULT_CLEAN_TIMEOUT_SECONDS, timeout.getDefaultValue());

        assertEquals("the settings minimum must be the smallest value the tool accepts", //$NON-NLS-1$
            timeout.getMinValue(), CleanProjectTool.clampTimeoutSeconds(timeout.getMinValue()));
        assertEquals("anything below it is raised to the minimum", //$NON-NLS-1$
            timeout.getMinValue(), CleanProjectTool.clampTimeoutSeconds(timeout.getMinValue() - 1));
        assertEquals("the settings maximum must be the largest value the tool accepts", //$NON-NLS-1$
            timeout.getMaxValue(), CleanProjectTool.clampTimeoutSeconds(timeout.getMaxValue()));
        assertEquals("anything above it is lowered to the maximum", //$NON-NLS-1$
            timeout.getMaxValue(), CleanProjectTool.clampTimeoutSeconds(timeout.getMaxValue() + 1));
    }

    /**
     * The wire parameter must actually reach the deadline. A tool that declared {@code timeout}
     * in its schema but never read it would pass every other test here — this one fails.
     */
    @Test
    public void testWireTimeoutIsTheOneThatBoundsTheClean()
    {
        Map<String, String> params = new HashMap<>();
        params.put(CleanProjectTool.KEY_TIMEOUT, "600"); //$NON-NLS-1$
        assertEquals("an explicit timeout must be the bound that is used", //$NON-NLS-1$
            600_000L, CleanProjectTool.resolveCleanTimeoutMs(params));

        assertEquals("no argument falls back to the configured default", //$NON-NLS-1$
            CleanProjectTool.DEFAULT_CLEAN_TIMEOUT_SECONDS * 1000L,
            CleanProjectTool.resolveCleanTimeoutMs(new HashMap<>()));
    }

    /**
     * Out-of-range values are clamped, not rejected (the same contract as terminate_launch), and
     * the schema says so. Pinned here so a silent change of that contract is visible.
     */
    @Test
    public void testOutOfRangeWireTimeoutIsClampedIntoTheAcceptedRange()
    {
        List<ParameterDef> params =
            ToolParameterSettings.getInstance().getParametersForTool(CleanProjectTool.NAME);
        ParameterDef def = params.get(0);

        Map<String, String> tooSmall = new HashMap<>();
        tooSmall.put(CleanProjectTool.KEY_TIMEOUT, String.valueOf(def.getMinValue() - 1));
        assertEquals("a value below the range is raised to the minimum, not rejected", //$NON-NLS-1$
            def.getMinValue() * 1000L, CleanProjectTool.resolveCleanTimeoutMs(tooSmall));

        Map<String, String> tooLarge = new HashMap<>();
        tooLarge.put(CleanProjectTool.KEY_TIMEOUT, String.valueOf(def.getMaxValue() + 1));
        assertEquals("a value above the range is lowered to the maximum, not rejected", //$NON-NLS-1$
            def.getMaxValue() * 1000L, CleanProjectTool.resolveCleanTimeoutMs(tooLarge));

        assertTrue("the schema must not promise rejection when the tool clamps", //$NON-NLS-1$
            new CleanProjectTool().getInputSchema().contains("clamped")); //$NON-NLS-1$
    }

    /**
     * Same defect as the rename's (issue #365 review), in the tool this deadline shipped with: a
     * clean the deadline caught while it was still QUEUED never started, so the timeout text's
     * "EDT may still be working on it, so the model can be mid-rebuild" would send the caller
     * polling a project nothing ever touched. A new outcome must not fall through to that text —
     * nor, worse, past the switch into the no-error path, which would report a clean that never
     * happened as a success.
     */
    @Test
    public void testCleanThatNeverStartedSaysNothingWasCleaned()
    {
        // A UNIQUE project name, so the name-matching listener below cannot reach into a foreign
        // job: the job name is derived from it, and 'Demo' is used by the other tests here.
        String projectName = "NeverStarted" + System.nanoTime(); //$NON-NLS-1$
        String jobName = CleanProjectTool.NAME + ": clean build " + projectName; //$NON-NLS-1$
        AtomicBoolean cleaned = new AtomicBoolean(false);
        AtomicBoolean held = new AtomicBoolean(false);
        IJobChangeListener sleeper = new JobChangeAdapter()
        {
            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                if (jobName.equals(event.getJob().getName()))
                {
                    held.set(event.getJob().sleep());
                }
            }
        };
        List<ProjectCleanInfo> projects =
            Collections.singletonList(new ProjectCleanInfo(null, null, projectName));
        Job.getJobManager().addJobChangeListener(sleeper);
        String error;
        try
        {
            error = CleanProjectTool.runCleanPhase(projects, SHORT_TIMEOUT_MS,
                (info, monitor) -> cleaned.set(true));
        }
        finally
        {
            Job.getJobManager().removeJobChangeListener(sleeper);
        }

        // Asserted first, and about the LISTENER rather than only the effect: an ambient scheduler
        // stall would also leave the clean unrun, and the test would pass without ever producing
        // the scenario it claims to judge.
        assertTrue("this test must be the reason the clean was held, not ambient scheduler luck", //$NON-NLS-1$
            held.get());
        assertTrue("the clean must have been held before it started", !cleaned.get()); //$NON-NLS-1$
        assertNotNull("a clean that never started must NOT be reported as a success", error); //$NON-NLS-1$
        assertTrue("it must say the clean did not START: " + error, //$NON-NLS-1$
            error.contains("did not START")); //$NON-NLS-1$
        assertTrue("it must say nothing was cleaned: " + error, //$NON-NLS-1$
            error.contains("NOTHING was cleaned")); //$NON-NLS-1$
        assertTrue("it must not send the caller polling a project it never touched: " + error, //$NON-NLS-1$
            !error.contains("mid-rebuild")); //$NON-NLS-1$
    }

    /**
     * Blocks until released or the wedge ceiling expires — the stand-in for a platform call
     * that stopped making progress.
     *
     * @param release the latch the test releases in its finally block
     */
    private static void awaitQuietly(CountDownLatch release)
    {
        try
        {
            release.await(WEDGE_CEILING_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}

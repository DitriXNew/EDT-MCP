/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.CleanCollection;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.IDtProjectSettle;
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

    // ==================== A project EDT never started (issue #647) ===============================

    @Test
    public void testNamedProjectEdtNeverStartedIsRefusedWithTheRecovery()
    {
        // The clean would refresh the files and schedule a CLEAN_BUILD, then SKIP both waits -
        // not exhaust them, skip them, because the DtProject they wait on does not exist - and
        // still answer "Clean and revalidation completed."
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectNamedObservation("Demo", null, null, true, Boolean.TRUE, //$NON-NLS-1$
            NEVER_SETTLES, collection);

        assertNotNull("an un-started EDT project must be refused, not queued", collection.error); //$NON-NLS-1$
        assertTrue("the refusal must name the project: " + collection.error, //$NON-NLS-1$
            collection.error.contains("Demo")); //$NON-NLS-1$
        assertTrue("the refusal must say EDT has not started it: " + collection.error, //$NON-NLS-1$
            collection.error.contains("EDT has not started this project")); //$NON-NLS-1$
        assertTrue("the refusal must offer the recovery: " + collection.error, //$NON-NLS-1$
            collection.error.contains("list_projects"));  //$NON-NLS-1$
        assertTrue("nothing may be queued for cleaning", collection.projectsToClean.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testNamedProjectWithADtProjectStillProceedsToTheClean()
    {
        // The mirror: "always refuse" would break every healthy clean, and only this notices.
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectNamedObservation("Demo", null, dtProjectStub(), true, //$NON-NLS-1$
            Boolean.TRUE, NEVER_SETTLES, collection);

        assertNull("a started EDT project must not be refused: " + collection.error, //$NON-NLS-1$
            collection.error);
        assertEquals("the project must be queued for cleaning", 1, //$NON-NLS-1$
            collection.projectsToClean.size());
        assertEquals("and reported under its own name", Collections.singletonList("Demo"), //$NON-NLS-1$ //$NON-NLS-2$
            collection.projectNamesList);
    }

    @Test
    public void testAPlainEclipseProjectIsNotRefusedByTheUnstartedGuard()
    {
        // Out of scope exactly as before: a project with no BM-model nature never had a DtProject,
        // so its absence says nothing.
        assertNull("a project without a BM-model nature must not be refused", //$NON-NLS-1$
            CleanProjectTool.unstartedProjectRefusalOrNull("Plain", true, false, Boolean.FALSE)); //$NON-NLS-1$
    }

    @Test
    public void testAnUnavailableDtProjectManagerIsNotEvidenceOfAnUnstartedProject()
    {
        // "We could not ask" is not "the answer was no": refusing here would turn a missing
        // service into a permanent refusal for every project.
        assertNull("a manager that could not be asked must not produce the refusal", //$NON-NLS-1$
            CleanProjectTool.unstartedProjectRefusalOrNull("Demo", false, false, Boolean.TRUE)); //$NON-NLS-1$
    }

    @Test
    public void testCleanAllSkipsTheUnstartedProjectInsteadOfCountingIt()
    {
        // projectsCleaned used to count the REQUESTED list. A project EDT never started is not in
        // the DT-project enumeration at all, so 'clean all' answered "completed" over a workspace
        // it had not touched in full and never said which project it left out.
        List<String> cleaned = new ArrayList<>(Arrays.asList("Alpha", "Beta")); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> skipped = CleanProjectTool.unstartedProjectNames(cleaned,
            Arrays.asList("Alpha", "Beta", "Gamma")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("only the project without a DT project may be skipped", //$NON-NLS-1$
            Collections.singletonList("Gamma"), skipped); //$NON-NLS-1$

        String result = CleanProjectTool.cleanCompletedResult(cleaned, skipped);
        assertTrue("projectsCleaned must count the two that were cleaned: " + result, //$NON-NLS-1$
            result.contains("\"projectsCleaned\": 2") || result.contains("\"projectsCleaned\":2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the message must name the skipped project: " + result, //$NON-NLS-1$
            result.contains("Gamma")); //$NON-NLS-1$
        assertTrue("and say EDT has not started it: " + result, //$NON-NLS-1$
            result.contains("EDT has not started")); //$NON-NLS-1$
    }

    @Test
    public void testCleanAllWithNothingSkippedKeepsTheOriginalMessage()
    {
        String result = CleanProjectTool.cleanCompletedResult(
            Collections.singletonList("Alpha"), Collections.emptyList()); //$NON-NLS-1$
        assertTrue("a run with nothing skipped keeps the plain completion message: " + result, //$NON-NLS-1$
            result.contains("Clean and revalidation completed.")); //$NON-NLS-1$
        assertFalse("and must not invent a skipped-projects clause: " + result, //$NON-NLS-1$
            result.contains("Skipped")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsTheUnstartedProjectRefusal()
    {
        String guide = new CleanProjectTool().getGuide();
        assertTrue("the guide must document the un-started refusal: " + guide, //$NON-NLS-1$
            guide.contains("has never STARTED")); //$NON-NLS-1$
        assertTrue("and say a clean-all skips such a project: " + guide, //$NON-NLS-1$
            guide.contains("left out of `projectsCleaned`")); //$NON-NLS-1$
    }

    @Test
    public void testARestartingContextIsSettledBeforeTheProjectIsRefused()
    {
        // DtProjectManager REMOVES its entry while a context is disposed, so a project whose
        // context is restarting - the state clean_project itself leaves projects in - answers
        // null for a moment. Refusing on that instant turns a transient into a hard
        // "Not an EDT project".
        IDtProject appearing = dtProjectStub();
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectNamedObservation("Demo", null, null, true, Boolean.TRUE, //$NON-NLS-1$
            project -> appearing, collection);

        assertNull("a DT project that appears during the settle must not be refused: " //$NON-NLS-1$
            + collection.error, collection.error);
        assertEquals("the settled project must be queued for cleaning", 1, //$NON-NLS-1$
            collection.projectsToClean.size());
    }

    @Test
    public void testAPlainProjectWithoutADtProjectNeverPaysTheSettle()
    {
        // The settle exists for the path that would otherwise REFUSE. A plain Eclipse project is
        // never refused (no BM-model nature, so no DtProject was ever expected), and paying ten
        // seconds before cleaning it anyway is a wait for a verdict that cannot come.
        AtomicInteger settles = new AtomicInteger();
        IDtProjectSettle counting = project -> {
            settles.incrementAndGet();
            return null;
        };
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectNamedObservation("Plain", null, null, true, Boolean.FALSE, //$NON-NLS-1$
            counting, collection);

        assertEquals("a plain project must never invoke the settle", 0, settles.get()); //$NON-NLS-1$
        assertNull("and must not be refused: " + collection.error, collection.error); //$NON-NLS-1$
        assertEquals("it is cleaned as before", 1, collection.projectsToClean.size()); //$NON-NLS-1$
    }

    @Test
    public void testAProjectWhoseNaturesCouldNotBeReadNeverPaysTheSettle()
    {
        // Unreadable natures are 'unknown', and unknown is not refused either (see
        // unstartedProjectRefusalOrNull) - so the settle has nothing to buy here.
        AtomicInteger settles = new AtomicInteger();
        IDtProjectSettle counting = project -> {
            settles.incrementAndGet();
            return null;
        };
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectNamedObservation("Unknown", null, null, true, null, //$NON-NLS-1$
            counting, collection);

        assertEquals("a project with unreadable natures must never invoke the settle", 0, //$NON-NLS-1$
            settles.get());
        assertNull("and must not be refused: " + collection.error, collection.error); //$NON-NLS-1$
    }

    @Test
    public void testAnUnstartedV8ProjectPaysTheSettleExactlyOnce()
    {
        // The positive control for the two pins above: the settle IS still paid on the one path
        // it exists for, and exactly once.
        AtomicInteger settles = new AtomicInteger();
        IDtProjectSettle counting = project -> {
            settles.incrementAndGet();
            return null;
        };
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectNamedObservation("Demo", null, null, true, Boolean.TRUE, //$NON-NLS-1$
            counting, collection);

        assertEquals("an un-started V8 project must be settled once before the refusal", 1, //$NON-NLS-1$
            settles.get());
        assertNotNull("and refused when the settle finds nothing", collection.error); //$NON-NLS-1$
    }

    @Test
    public void testResolvedNamedProjectReachesTheUnstartedRefusal()
    {
        // The wiring pin for the named branch: the nature read and the refusal are reached from
        // the same entry point production uses once ProjectContext has turned a NAME into a
        // handle. Reverting the call below leaves this red.
        IProject project = projectStub("Demo", true, //$NON-NLS-1$
            "com._1c.g5.v8.dt.core.V8ConfigurationNature"); //$NON-NLS-1$
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectResolvedNamedProject("Demo", project, true, true, //$NON-NLS-1$
            managerStub(project, null), NEVER_SETTLES, collection);

        assertNotNull("an open V8 project without a DT project must be refused", //$NON-NLS-1$
            collection.error);
        assertTrue("the refusal must name the project: " + collection.error, //$NON-NLS-1$
            collection.error.contains("Demo")); //$NON-NLS-1$
        assertTrue("nothing may be queued for cleaning", collection.projectsToClean.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testResolvedNamedProjectWithoutAV8NatureIsStillCleaned()
    {
        // The mirror that keeps the refusal from swallowing every project: a plain Eclipse
        // project never had a DT project and is out of scope exactly as before.
        IProject project = projectStub("Plain", true); //$NON-NLS-1$
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectResolvedNamedProject("Plain", project, true, true, //$NON-NLS-1$
            managerStub(project, null), NEVER_SETTLES, collection);

        assertNull("a project with no BM-model nature must not be refused: " + collection.error, //$NON-NLS-1$
            collection.error);
        assertEquals("it must still be queued", 1, collection.projectsToClean.size()); //$NON-NLS-1$
    }

    @Test
    public void testCleanAllScanNamesTheOpenV8ProjectEdtNeverStarted()
    {
        // The wiring pin for the clean-all branch. The enumeration production works from is over
        // DT PROJECTS, so a project EDT never started is not in it at all: without the workspace
        // scan, clean-all answers "completed" without ever mentioning it.
        IProject started = projectStub("Alpha", true, //$NON-NLS-1$
            "com._1c.g5.v8.dt.core.V8ConfigurationNature"); //$NON-NLS-1$
        IProject unstarted = projectStub("Gamma", true, //$NON-NLS-1$
            "com._1c.g5.v8.dt.core.V8ConfigurationNature"); //$NON-NLS-1$
        CleanCollection collection = new CleanCollection();
        CleanProjectTool.collectAllEdtProjects(managerStub(started, dtProjectStub(started)),
            collection, () -> new IProject[] { started, unstarted });

        assertEquals("only the DT project may be queued for cleaning", //$NON-NLS-1$
            Collections.singletonList("Alpha"), collection.projectNamesList); //$NON-NLS-1$
        assertEquals("the open V8 project EDT never started must be reported as skipped", //$NON-NLS-1$
            Collections.singletonList("Gamma"), collection.skippedUnstarted); //$NON-NLS-1$
    }

    /** A settle that never finds a DT project, so a pin measures the decision and not a sleep. */
    private static final IDtProjectSettle NEVER_SETTLES = project -> null;

    /**
     * A workspace project handle that answers only what the decisions under test read: its name,
     * whether it is open, and the natures its description carries.
     *
     * @param name     the project name
     * @param open     whether it reports itself open
     * @param natures  the nature ids its description carries
     * @return the stub
     */
    private static IProject projectStub(String name, boolean open, String... natures)
    {
        IProjectDescription description = (IProjectDescription)Proxy.newProxyInstance(
            IProjectDescription.class.getClassLoader(), new Class<?>[] { IProjectDescription.class },
            (proxy, method, args) -> "getNatureIds".equals(method.getName()) //$NON-NLS-1$
                ? natures : defaultAnswer(proxy, method, args));
        return (IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(),
            new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getName": //$NON-NLS-1$
                    return name;
                case "isOpen": //$NON-NLS-1$
                    return Boolean.valueOf(open);
                case "getDescription": //$NON-NLS-1$
                    return description;
                default:
                    return defaultAnswer(proxy, method, args);
                }
            });
    }

    /**
     * A DT project manager that knows at most one project.
     *
     * @param project   the workspace project it is asked about
     * @param dtProject what it answers for that project ({@code null} = EDT never started it)
     * @return the stub
     */
    private static IDtProjectManager managerStub(IProject project, IDtProject dtProject)
    {
        return (IDtProjectManager)Proxy.newProxyInstance(IDtProjectManager.class.getClassLoader(),
            new Class<?>[] { IDtProjectManager.class }, (proxy, method, args) -> {
                if ("getDtProject".equals(method.getName()) && args != null && args.length == 1) //$NON-NLS-1$
                {
                    return args[0] == project ? dtProject : null;
                }
                if ("getDtProjects".equals(method.getName())) //$NON-NLS-1$
                {
                    return dtProject == null ? Collections.emptyList()
                        : Collections.singletonList(dtProject);
                }
                return defaultAnswer(proxy, method, args);
            });
    }

    /**
     * A DT project that only has to be non-null: the decision under test is "did EDT report one",
     * nothing about the object itself.
     *
     * @return a do-nothing {@link IDtProject}
     */
    private static IDtProject dtProjectStub()
    {
        return dtProjectStub(null);
    }

    /**
     * A DT project that reports {@code workspaceProject} as its workspace project.
     *
     * @param workspaceProject the workspace project it belongs to (may be {@code null})
     * @return the stub
     */
    private static IDtProject dtProjectStub(IProject workspaceProject)
    {
        return (IDtProject)Proxy.newProxyInstance(IDtProject.class.getClassLoader(),
            new Class<?>[] { IDtProject.class },
            (proxy, method, args) -> "getWorkspaceProject".equals(method.getName()) //$NON-NLS-1$
                ? workspaceProject : defaultAnswer(proxy, method, args));
    }

    /**
     * The answer a stub gives for everything the decision under test does not read: a benign
     * zero/null, with the three {@link Object} methods a proxy must implement itself.
     *
     * @param proxy  the proxy instance
     * @param method the invoked method
     * @param args   its arguments (may be {@code null})
     * @return the benign answer
     */
    private static Object defaultAnswer(Object proxy, java.lang.reflect.Method method, Object[] args)
    {
        switch (method.getName())
        {
        case "toString": //$NON-NLS-1$
            return "stub(" + method.getDeclaringClass().getSimpleName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        case "hashCode": //$NON-NLS-1$
            return Integer.valueOf(System.identityHashCode(proxy));
        case "equals": //$NON-NLS-1$
            return Boolean.valueOf(proxy == args[0]);
        default:
            break;
        }
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (returnType == int.class)
        {
            return Integer.valueOf(0);
        }
        return null;
    }
}

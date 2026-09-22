/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.core.lifecycle.ProjectStartType;
import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportRunner;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.StartOutcome;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.StartPhase;
import com.ditrix.edt.mcp.server.utils.BoundedJob;

/**
 * Tests for {@link ImportConfigurationFromXmlTool}.
 * <p>
 * Two layers run headless. The first is the tool metadata, the JSON schema and the
 * argument/path-validation guards that fire BEFORE the workspace project lookup, using only
 * {@code java.nio.file}.
 * <p>
 * The second is the whole import contract, driven through the
 * {@code executeWith(params, runner, lifecycle, budget)} seam: the import itself and EDT's project
 * lifecycle are injected, so the concurrency guard, the ORDER of the post-import steps and both
 * verdicts the answer can carry ({@code state: ready} / {@code state: importing}) are exercised
 * without an EDT CLI API and without importing anything. The real import is covered by the E2E
 * suite.
 */
public class ImportConfigurationFromXmlToolTest
{
    /** Budget for a start that is expected to run out, short enough to keep the suite quick. */
    private static final long SHORT_START_BUDGET_MS = 1500;

    /** Budget for a start that is expected to succeed at once; generous so a slow box still passes. */
    private static final long AMPLE_START_BUDGET_MS = 30_000;

    /** Bound on any latch this test waits on, so a broken run fails instead of hanging the suite. */
    private static final long SANE_WAIT_MS = 30_000;

    /**
     * Bound on "the blocked refresh is still polling its monitor". Finite and short: a refresh
     * that HONOURED the deadline's cancel stops polling at once, and that is the fact this bound
     * turns into a failure instead of a hang.
     */
    private static final long REFRESH_PROGRESS_WAIT_MS = 5_000;

    @Test
    public void testName()
    {
        assertEquals("import_configuration_from_xml", new ImportConfigurationFromXmlTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        // import_configuration_from_xml is an action tool with no round-trip ID and
        // no machine-structured payload, so it returns MARKDOWN (see the
        // "Response format policy" in edt-mcp-tool-conventions).
        assertEquals(ResponseType.MARKDOWN, new ImportConfigurationFromXmlTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ImportConfigurationFromXmlTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive per-tool detail moved out of the always-loaded
        // description/schema into the on-demand getGuide() channel.
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertNotNull(guide);
        assertFalse("getGuide() must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // A keyword that was migrated from the schema's parameter prose.
        assertTrue("guide must explain auto-detect behaviour", //$NON-NLS-1$
            guide.contains("auto-detect")); //$NON-NLS-1$
        assertTrue("guide must document the new-project constraint", //$NON-NLS-1$
            guide.contains("already exist")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ImportConfigurationFromXmlTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"importPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectNature\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"xmlVersion\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new ImportConfigurationFromXmlTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("importPath must be required", tail.contains("\"importPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("projectNature must NOT be required", //$NON-NLS-1$
            tail.contains("\"projectNature\",") || tail.contains(",\"projectNature\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("xmlVersion must NOT be required", //$NON-NLS-1$
            tail.contains("\"xmlVersion\",") || tail.contains(",\"xmlVersion\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument & path validation (returns before the workspace lookup) ===========

    @Test
    public void testExecuteMissingImportPathIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NewConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportConfigurationFromXmlTool().execute(params);
        assertTrue("missing importPath must produce 'importPath is required'", //$NON-NLS-1$
            result.contains("importPath is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("importPath", "C:/tmp/xml-src"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportConfigurationFromXmlTool().execute(params);
        assertTrue("missing projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteNonExistentImportPathIsError()
    {
        // A source path that does not exist is rejected on disk, before the
        // workspace project lookup — reachable headless.
        String nonExistent = System.getProperty("java.io.tmpdir") //$NON-NLS-1$
            + "/import-does-not-exist-" + UUID.randomUUID(); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("importPath", nonExistent); //$NON-NLS-1$
        params.put("projectName", "NewConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ImportConfigurationFromXmlTool().execute(params);
        assertTrue("a non-existent importPath must be rejected", //$NON-NLS-1$
            result.contains("does not exist")); //$NON-NLS-1$
        assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExecuteImportPathIsAFileIsError() throws IOException
    {
        // A source path that is a file (not a directory) is rejected on disk,
        // before the workspace project lookup.
        Path tempFile = Files.createTempFile("import-cfg-not-a-dir", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("importPath", tempFile.toString()); //$NON-NLS-1$
            params.put("projectName", "NewConfig"); //$NON-NLS-1$ //$NON-NLS-2$
            String result = new ImportConfigurationFromXmlTool().execute(params);
            assertTrue("a file importPath must be rejected as not a directory", //$NON-NLS-1$
                result.contains("is not a directory")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== The post-import project start (issue #647) ==============================

    @Test
    public void testRefreshHappensBeforeTheFirstLifecycleCall()
    {
        // The CLI API imports with refreshLocal DISABLED, so the workspace tree still shows the
        // pre-import state when the import returns. Starting the project first would hand EDT
        // that stale tree; the refresh has to come before anything lifecycle-related is asked.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        ImportConfigurationFromXmlTool.startImportedProject(projectHandle(), "Imported", //$NON-NLS-1$
            lifecycle, AMPLE_START_BUDGET_MS);

        int refreshAt = lifecycle.calls.indexOf(RecordingLifecycle.REFRESH);
        assertTrue("refreshLocal must have been performed: " + lifecycle.calls, refreshAt >= 0); //$NON-NLS-1$
        int firstLifecycleAt = lifecycle.firstIndexOfLifecycleCall();
        assertTrue("a lifecycle call must have been made: " + lifecycle.calls, //$NON-NLS-1$
            firstLifecycleAt >= 0);
        assertTrue("refreshLocal must precede every lifecycle call, got " + lifecycle.calls, //$NON-NLS-1$
            refreshAt < firstLifecycleAt);
    }

    @Test
    public void testStartRequestIsExactlyOneCleanImportForThatProject()
    {
        // CLEAN_IMPORT is the start type EDT's own headless import command uses, and it is the
        // only one addProjectContextIntoStartQueue honours for a project that already carries a
        // latch - EXISTING_DATA_IMPORT (what a close/open produces) is dropped outright.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        IProject project = projectHandle();
        ImportConfigurationFromXmlTool.startImportedProject(project, "Imported", lifecycle, //$NON-NLS-1$
            AMPLE_START_BUDGET_MS);

        assertEquals("exactly one start must be issued", 1, lifecycle.startRequests.size()); //$NON-NLS-1$
        Collection<WorkspaceProjectStartRequest> requests = lifecycle.startRequests.get(0);
        assertEquals("the start must name exactly one project", 1, requests.size()); //$NON-NLS-1$
        WorkspaceProjectStartRequest request = requests.iterator().next();
        assertSame("the start must be for the imported project", project, //$NON-NLS-1$
            request.getWorkspaceProject());
        assertEquals("the start type must be CLEAN_IMPORT", ProjectStartType.CLEAN_IMPORT, //$NON-NLS-1$
            request.getStartType());
    }

    @Test
    public void testAnAlreadyStartedProjectIsNeverRestarted()
    {
        // startWorkspaceProjects begins by STOPPING the contexts of dependent projects, so issuing
        // it for a project EDT already started is destructive work for nothing. Pinning the
        // ABSENCE: without the gate this test is the only thing that notices.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.started = true;
        ImportConfigurationFromXmlTool.startImportedProject(projectHandle(), "Imported", //$NON-NLS-1$
            lifecycle, AMPLE_START_BUDGET_MS);

        assertTrue("no start may be issued for an already-started project, got " //$NON-NLS-1$
            + lifecycle.calls, lifecycle.startRequests.isEmpty());
    }

    @Test
    public void testPermitImportIsNeverCalledOnTheHappyPath()
    {
        // permitImport flips the latch's startAllowed, which makes manuallyLockedProjectsExist()
        // false and lets EDT's watchdog schedule DefaultContextsStartJob - which issues its OWN
        // startWorkspaceProjects for the same project, behind the back of the !isStarted gate.
        // EDT's own headless import command does not call it either. Pinning the ABSENCE.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        ImportConfigurationFromXmlTool.startImportedProject(projectHandle(), "Imported", //$NON-NLS-1$
            lifecycle, AMPLE_START_BUDGET_MS);

        assertTrue("a start must have been issued: " + lifecycle.calls, //$NON-NLS-1$
            lifecycle.calls.contains(RecordingLifecycle.START));
        assertFalse("permitImport must NOT be called on the happy path, got " + lifecycle.calls, //$NON-NLS-1$
            lifecycle.calls.contains(RecordingLifecycle.PERMIT));
    }

    @Test
    public void testPermitImportIsTheRecoveryWhenTheStartRequestFails()
    {
        // The only case where releasing the latch helps: the start request threw, so EDT's
        // watchdog is the one remaining route to a started project - and it cannot take it while
        // the latch is blocked, which also keeps every OTHER project in the workspace frozen.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startFailure = new IllegalStateException("orchestrator refused"); //$NON-NLS-1$
        ImportConfigurationFromXmlTool.startImportedProject(projectHandle(), "Imported", //$NON-NLS-1$
            lifecycle, AMPLE_START_BUDGET_MS);

        int startAt = lifecycle.calls.indexOf(RecordingLifecycle.START);
        int permitAt = lifecycle.calls.indexOf(RecordingLifecycle.PERMIT);
        assertTrue("the start must have been attempted: " + lifecycle.calls, startAt >= 0); //$NON-NLS-1$
        assertTrue("permitImport must be called after a failed start: " + lifecycle.calls, //$NON-NLS-1$
            permitAt > startAt);
    }

    @Test
    public void testTheDeadlineDoesNotCancelTheWorkspaceRefresh()
        throws Exception
    {
        // THE defect this shape exists to prevent. BoundedJob cancels the job on expiry, which
        // only flags its monitor - the body keeps running. A refresh that honoured that flag
        // would unwind, the start request would never be issued, and the answer would still tell
        // the caller to poll for a project nothing is starting: #647, reinstated and hidden.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        lifecycle.blockRefreshUntilReleased();
        AtomicReference<StartOutcome> outcome = new AtomicReference<>();
        Thread caller = new Thread(() -> outcome.set(ImportConfigurationFromXmlTool
            .startImportedProject(projectHandle(), "Imported", lifecycle, SHORT_START_BUDGET_MS)), //$NON-NLS-1$
            "import-647-refresh"); //$NON-NLS-1$
        caller.setDaemon(true);
        caller.start();
        caller.join(SANE_WAIT_MS);

        assertFalse("the bounded wait must return while the refresh is still running", //$NON-NLS-1$
            caller.isAlive());
        StartOutcome observed = outcome.get();
        assertNotNull("the bounded wait must have produced an outcome", observed); //$NON-NLS-1$
        assertEquals("a refresh that is still running must be reported as such", //$NON-NLS-1$
            StartPhase.REFRESHING, observed.phase);

        String answer = ImportConfigurationFromXmlTool.renderImportAnswer("Imported", //$NON-NLS-1$
            java.nio.file.Paths.get("C:/dump"), false, observed, SHORT_START_BUDGET_MS); //$NON-NLS-1$
        assertTrue("the answer must say the refresh is still running: " + answer, //$NON-NLS-1$
            answer.contains("the workspace refresh of the imported files was STILL running")); //$NON-NLS-1$
        assertTrue("and that it will issue the start request when it finishes: " + answer, //$NON-NLS-1$
            answer.contains("issues the start request when it finishes")); //$NON-NLS-1$

        // BoundedJob calls job.cancel() BEFORE returning, so by the time the join above came
        // back the cancel has already happened. Waiting for the refresh to poll its monitor twice
        // MORE therefore proves it SURVIVED that cancel - as opposed to merely being released
        // before it noticed, which is what a release issued straight after the join would test.
        int polled = lifecycle.refreshPolls.get();
        assertTrue("the refresh must keep polling after the deadline cancelled the job", //$NON-NLS-1$
            lifecycle.awaitRefreshPolls(polled + 2, REFRESH_PROGRESS_WAIT_MS));
        assertFalse("the monitor handed to the refresh must never report cancellation", //$NON-NLS-1$
            lifecycle.refreshSawCancel.get());

        // And it really does finish its work: release it, and the start MUST follow.
        lifecycle.releaseRefresh();
        assertTrue("the refresh must run to completion after the deadline", //$NON-NLS-1$
            lifecycle.awaitStartIssued(SANE_WAIT_MS));
        assertTrue("the start request must be issued once the refresh finished, got " //$NON-NLS-1$
            + lifecycle.calls, lifecycle.calls.contains(RecordingLifecycle.START));
    }

    @Test
    public void testAStartThatNeverRanIsAnErrorNotAPromiseToPoll()
    {
        // NOT_RUN / TIMED_OUT_BEFORE_START mean the job never entered the work, so NOTHING asked
        // EDT to start the project. Reporting "importing" there tells the caller to poll for a
        // state that can never arrive.
        String answer = ImportConfigurationFromXmlTool.renderImportAnswer("Imported", //$NON-NLS-1$
            java.nio.file.Paths.get("C:/dump"), false, //$NON-NLS-1$
            new StartOutcome(StartPhase.NOT_SCHEDULED, null), SHORT_START_BUDGET_MS);

        assertTrue("a start that never ran must be an error: " + answer, //$NON-NLS-1$
            answer.contains("\"success\": false") || answer.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("it must say the start could not be scheduled: " + answer, //$NON-NLS-1$
            answer.contains("the start could not be scheduled")); //$NON-NLS-1$
        assertTrue("it must forbid a re-import and name the manual recovery: " + answer, //$NON-NLS-1$
            answer.contains("do NOT re-import it; close and reopen the project in EDT")); //$NON-NLS-1$
        assertTrue("the import already ran, so it is an after-mutation error: " + answer, //$NON-NLS-1$
            answer.contains("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void testOnlyTheOutcomesThatNeverEnteredTheWorkCountAsNeverRan()
    {
        assertTrue("NOT_RUN never entered the work", //$NON-NLS-1$
            ImportConfigurationFromXmlTool.startNeverRan(BoundedJob.Outcome.NOT_RUN));
        assertTrue("TIMED_OUT_BEFORE_START never entered the work", //$NON-NLS-1$
            ImportConfigurationFromXmlTool.startNeverRan(BoundedJob.Outcome.TIMED_OUT_BEFORE_START));
        assertFalse("TIMED_OUT means the work IS running - it must stay 'importing'", //$NON-NLS-1$
            ImportConfigurationFromXmlTool.startNeverRan(BoundedJob.Outcome.TIMED_OUT));
        assertFalse("COMPLETED ran the work", //$NON-NLS-1$
            ImportConfigurationFromXmlTool.startNeverRan(BoundedJob.Outcome.COMPLETED));
    }

    @Test
    public void testAnIssuedStartSaysSoRatherThanBlamingTheRefresh()
    {
        // The two 'importing' bodies must not be interchangeable: the recovery differs, and a
        // caller told "the refresh is still running" about an issued start would wait on the
        // wrong thing.
        String answer = ImportConfigurationFromXmlTool.renderImportAnswer("Imported", //$NON-NLS-1$
            java.nio.file.Paths.get("C:/dump"), false, //$NON-NLS-1$
            new StartOutcome(StartPhase.START_ISSUED, null), SHORT_START_BUDGET_MS);

        assertTrue("it must say the start request was issued: " + answer, //$NON-NLS-1$
            answer.contains("the start request was issued and EDT is still starting")); //$NON-NLS-1$
        assertFalse("and must not blame the refresh: " + answer, //$NON-NLS-1$
            answer.contains("workspace refresh")); //$NON-NLS-1$
    }

    @Test
    public void testAStartThatNeverCompletesIsReportedAsImportingNotReady() throws IOException
    {
        // The files ARE imported and the project name IS taken, so this is a success - but one
        // that says the project is not usable yet and names the tool to poll. Reporting 'ready'
        // here is exactly the lie issue #647 is about.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.started = false;
        lifecycle.startsOnRequest = false;
        String answer = runImport(lifecycle, SHORT_START_BUDGET_MS);

        assertTrue("an import that landed is still a success: " + answer, //$NON-NLS-1$
            answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("front matter must say the project is still starting: " + answer, //$NON-NLS-1$
            answer.contains("state: importing")); //$NON-NLS-1$
        assertTrue("front matter must say the project is NOT ready: " + answer, //$NON-NLS-1$
            answer.contains("projectReady: false")); //$NON-NLS-1$
        assertTrue("the answer must name the tool to poll: " + answer, //$NON-NLS-1$
            answer.contains("Poll `list_projects` until this project reports `ready`")); //$NON-NLS-1$
        assertTrue("and say the start request was issued, not that the refresh is pending: " //$NON-NLS-1$
            + answer, answer.contains("the start request was issued")); //$NON-NLS-1$
    }

    @Test
    public void testAStartedProjectWithADtProjectIsReportedAsReady() throws IOException
    {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        lifecycle.dtProject = true;
        String answer = runImport(lifecycle, AMPLE_START_BUDGET_MS);

        assertTrue("front matter must report the started project as ready: " + answer, //$NON-NLS-1$
            answer.contains("state: ready")); //$NON-NLS-1$
        assertTrue("front matter must carry projectReady: true: " + answer, //$NON-NLS-1$
            answer.contains("projectReady: true")); //$NON-NLS-1$
        assertTrue("the body must say EDT started the project: " + answer, //$NON-NLS-1$
            answer.contains("- Project state: EDT has started this project.")); //$NON-NLS-1$
        assertTrue("and warn that list_projects can still say 'building': " + answer, //$NON-NLS-1$
            answer.contains("may still report it `building`")); //$NON-NLS-1$
        assertFalse("a ready project must not be described as still importing: " + answer, //$NON-NLS-1$
            answer.contains("importing")); //$NON-NLS-1$
    }

    @Test
    public void testAStartedProjectWithoutADtProjectIsNotReported() throws IOException
    {
        // isStarted alone is not the fact the caller needs: list_projects reads the DtProject, and
        // that is what 'not_available' means. A start without one is still 'importing'.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        lifecycle.dtProject = false;
        String answer = runImport(lifecycle, SHORT_START_BUDGET_MS);

        assertTrue("without a DtProject the project is not ready: " + answer, //$NON-NLS-1$
            answer.contains("state: importing")); //$NON-NLS-1$
    }

    @Test
    public void testAMissingProjectAfterAReturnedImportIsAnErrorNotASuccess() throws IOException
    {
        // The old refresh helper returned silently when the project handle did not exist, and the
        // caller was told 'success' about a project that is not there.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.projectExists = false;
        String answer = runImport(lifecycle, SHORT_START_BUDGET_MS);

        assertTrue("a missing project must be an error: " + answer, //$NON-NLS-1$
            answer.contains("\"success\": false") || answer.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must say the workspace holds no such project: " + answer, //$NON-NLS-1$
            answer.contains("the workspace holds no project of that name")); //$NON-NLS-1$
        assertTrue("the import already ran, so the error must be marked as after-mutation: " //$NON-NLS-1$
            + answer, answer.contains("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void testAFailedStartIsAnErrorThatForbidsARetryOfTheImport() throws IOException
    {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startFailure = new IllegalStateException("orchestrator refused"); //$NON-NLS-1$
        String answer = runImport(lifecycle, AMPLE_START_BUDGET_MS);

        assertTrue("a failed start must be an error: " + answer, //$NON-NLS-1$
            answer.contains("\"success\": false") || answer.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must carry the platform failure: " + answer, //$NON-NLS-1$
            answer.contains("orchestrator refused")); //$NON-NLS-1$
        assertTrue("the error must forbid a re-import, the name being taken: " + answer, //$NON-NLS-1$
            answer.contains("do NOT re-run the import")); //$NON-NLS-1$
    }

    @Test
    public void testStartOutcomeCarriesTheFailureItObserved()
    {
        // The outcome type is what keeps "the start failed" apart from "the start is still
        // running": BoundedJob reports no failure for a timed-out run, and a timed-out start is
        // not a failed one.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startFailure = new IllegalStateException("boom"); //$NON-NLS-1$
        StartOutcome outcome = ImportConfigurationFromXmlTool.startImportedProject(projectHandle(),
            "Imported", lifecycle, AMPLE_START_BUDGET_MS); //$NON-NLS-1$
        assertNotNull("a start that threw must surface its failure", outcome.failure); //$NON-NLS-1$
        assertEquals("a start that threw is FAILED, not merely not-ready", StartPhase.FAILED, //$NON-NLS-1$
            outcome.phase);
    }

    // ==================== A refresh that fails: no start was ever requested =======================

    @Test
    public void testARefreshFailureReleasesTheLatchAndReportsThatNoStartWasRequested()
    {
        // A failed refresh leaves the project with NO start request issued and the latch still
        // blocked - the state issue #647 is about, reached through a different door. The same
        // recovery as after a failed start: release the latch so EDT's own watchdog can start it.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.refreshFailure = refreshRefused();
        StartOutcome outcome = ImportConfigurationFromXmlTool.startImportedProject(projectHandle(),
            "Imported", lifecycle, AMPLE_START_BUDGET_MS); //$NON-NLS-1$

        assertEquals("a refresh that threw is REFRESH_FAILED, not a start failure", //$NON-NLS-1$
            StartPhase.REFRESH_FAILED, outcome.phase);
        assertSame("the outcome must carry the refresh's own failure", lifecycle.refreshFailure, //$NON-NLS-1$
            outcome.failure);
        int refreshAt = lifecycle.calls.indexOf(RecordingLifecycle.REFRESH);
        int permitAt = lifecycle.calls.indexOf(RecordingLifecycle.PERMIT);
        assertTrue("permitImport must be called after the failed refresh: " + lifecycle.calls, //$NON-NLS-1$
            permitAt > refreshAt);
        assertFalse("no start may be requested after a failed refresh: " + lifecycle.calls, //$NON-NLS-1$
            lifecycle.calls.contains(RecordingLifecycle.START));
    }

    @Test
    public void testARefreshFailureAnswerSaysTheRefreshFailedBeforeAnyStart()
    {
        // The FAILED text tells the caller to poll list_projects until ready. After a refresh
        // failure nothing asked EDT to start anything, so that polling could only succeed by the
        // watchdog's grace - the answer has to say the refresh failed and send the caller to the
        // manual recovery, with the polling as the secondary "may recover" it really is.
        String answer = ImportConfigurationFromXmlTool.renderImportAnswer("Imported", //$NON-NLS-1$
            java.nio.file.Paths.get("C:/dump"), false, //$NON-NLS-1$
            new StartOutcome(StartPhase.REFRESH_FAILED, refreshRefused()), SHORT_START_BUDGET_MS);

        assertTrue("a failed refresh must be an error: " + answer, //$NON-NLS-1$
            answer.contains("\"success\": false") || answer.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("it must say the refresh failed before any start was requested: " + answer, //$NON-NLS-1$
            answer.contains("the workspace refresh failed before any start was requested")); //$NON-NLS-1$
        assertFalse("and must NOT use the start-failure text, whose recovery is polling: " //$NON-NLS-1$
            + answer, answer.contains("could not be asked to start")); //$NON-NLS-1$
        assertTrue("the error must carry the platform failure: " + answer, //$NON-NLS-1$
            answer.contains("refresh refused")); //$NON-NLS-1$
        assertTrue("it must forbid a re-import, the name being taken: " + answer, //$NON-NLS-1$
            answer.contains("do NOT re-import it")); //$NON-NLS-1$
        assertTrue("it must name the manual recovery: " + answer, //$NON-NLS-1$
            answer.contains("close and reopen the project in EDT")); //$NON-NLS-1$
        assertTrue("list_projects is only the secondary 'may recover on its own' hint: " + answer, //$NON-NLS-1$
            answer.contains("may also recover on its own") && answer.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the import already ran, so it is an after-mutation error: " + answer, //$NON-NLS-1$
            answer.contains("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void testAStartFailureAnswerStillSaysEdtCouldNotBeAskedToStart()
    {
        // The other half of the split: a start that threw keeps its own text and its own
        // recovery, and must not borrow the refresh one.
        String answer = ImportConfigurationFromXmlTool.renderImportAnswer("Imported", //$NON-NLS-1$
            java.nio.file.Paths.get("C:/dump"), false, //$NON-NLS-1$
            new StartOutcome(StartPhase.FAILED, new IllegalStateException("orchestrator refused")), //$NON-NLS-1$
            SHORT_START_BUDGET_MS);

        assertTrue("a failed start must say EDT could not be asked to start it: " + answer, //$NON-NLS-1$
            answer.contains("EDT could not be asked to start it")); //$NON-NLS-1$
        assertFalse("and must not describe it as a refresh failure: " + answer, //$NON-NLS-1$
            answer.contains("the workspace refresh failed before any start was requested")); //$NON-NLS-1$
    }

    // ==================== A failure after the deadline has nowhere to go but the log ==============

    @Test
    public void testARefreshFailureAfterTheDeadlineIsLoggedWithTheProjectAndTheStep()
        throws Exception
    {
        // The caller has already been answered 'state: importing' and BoundedJob never reads its
        // failure holder on the timed-out path, so a refresh that throws AFTER the deadline would
        // otherwise vanish - and the caller would poll for a start that is never coming.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.blockRefreshUntilReleased();
        lifecycle.refreshFailure = refreshRefused();
        String projectName = uniqueProjectName();
        LogRecorder log = LogRecorder.start();
        try
        {
            StartOutcome observed = ImportConfigurationFromXmlTool.startImportedProject(
                ResourcesPlugin.getWorkspace().getRoot().getProject(projectName), projectName,
                lifecycle, SHORT_START_BUDGET_MS);
            assertEquals("the wait must have run out while the refresh was still running", //$NON-NLS-1$
                StartPhase.REFRESHING, observed.phase);
            assertTrue("nothing may be logged while the refresh is merely slow: " //$NON-NLS-1$
                + log.entriesNaming(projectName), log.entriesNaming(projectName).isEmpty());

            lifecycle.releaseRefresh();
            IStatus entry = log.awaitEntryNaming(projectName, SANE_WAIT_MS);
            assertNotNull("a refresh that throws after the deadline must be logged", entry); //$NON-NLS-1$
            assertEquals("and logged as an ERROR", IStatus.ERROR, entry.getSeverity()); //$NON-NLS-1$
            assertTrue("the log line must name the step: " + entry.getMessage(), //$NON-NLS-1$
                entry.getMessage().contains("while refreshing the workspace")); //$NON-NLS-1$
            assertTrue("and say the caller had already been answered: " + entry.getMessage(), //$NON-NLS-1$
                entry.getMessage().contains("had already answered the caller")); //$NON-NLS-1$
            assertSame("the throwable itself must be attached", lifecycle.refreshFailure, //$NON-NLS-1$
                entry.getException());
        }
        finally
        {
            lifecycle.releaseRefresh();
            log.stop();
        }
    }

    @Test
    public void testAStartFailureAfterTheDeadlineIsLoggedWithItsOwnStep() throws Exception
    {
        // The step in the log line is the one that failed, not the one the deadline caught: a
        // refresh that outlived the wait and then a start request that threw is a START failure.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.blockRefreshUntilReleased();
        lifecycle.startFailure = new IllegalStateException("orchestrator refused"); //$NON-NLS-1$
        String projectName = uniqueProjectName();
        LogRecorder log = LogRecorder.start();
        try
        {
            StartOutcome observed = ImportConfigurationFromXmlTool.startImportedProject(
                ResourcesPlugin.getWorkspace().getRoot().getProject(projectName), projectName,
                lifecycle, SHORT_START_BUDGET_MS);
            assertEquals("the wait must have run out while the refresh was still running", //$NON-NLS-1$
                StartPhase.REFRESHING, observed.phase);

            lifecycle.releaseRefresh();
            IStatus entry = log.awaitEntryNaming(projectName, SANE_WAIT_MS);
            assertNotNull("a start that throws after the deadline must be logged", entry); //$NON-NLS-1$
            assertTrue("the log line must name the START step: " + entry.getMessage(), //$NON-NLS-1$
                entry.getMessage().contains("while asking EDT to start it")); //$NON-NLS-1$
            assertFalse("and not blame the refresh, which had returned: " + entry.getMessage(), //$NON-NLS-1$
                entry.getMessage().contains("while refreshing the workspace")); //$NON-NLS-1$
            assertSame("the throwable itself must be attached", lifecycle.startFailure, //$NON-NLS-1$
                entry.getException());
        }
        finally
        {
            lifecycle.releaseRefresh();
            log.stop();
        }
    }

    @Test
    public void testAFailureBeforeTheDeadlineIsReportedToTheCallerNotLogged() throws Exception
    {
        // Before the deadline the failure has somewhere to go: the caller, as the FAILED /
        // REFRESH_FAILED verdict. Logging it as well would double-report every ordinary failure
        // and make the late-failure log line meaningless.
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.refreshFailure = refreshRefused();
        String projectName = uniqueProjectName();
        LogRecorder log = LogRecorder.start();
        try
        {
            StartOutcome observed = ImportConfigurationFromXmlTool.startImportedProject(
                ResourcesPlugin.getWorkspace().getRoot().getProject(projectName), projectName,
                lifecycle, AMPLE_START_BUDGET_MS);
            assertEquals("the failure must reach the caller as the verdict", //$NON-NLS-1$
                StartPhase.REFRESH_FAILED, observed.phase);
            assertTrue("and must not be logged as a late failure: " + log.entriesNaming(projectName), //$NON-NLS-1$
                log.entriesNaming(projectName).isEmpty());
        }
        finally
        {
            log.stop();
        }
    }

    @Test
    public void testLateFailureMessageNamesTheProjectAndEachStep()
    {
        // One message, three steps; each must be told apart, and the project must be in all of
        // them - that is what lets an operator match the log line to the answer the caller got.
        String refreshing = ImportConfigurationFromXmlTool.lateFailureMessage("Imported", false, //$NON-NLS-1$
            StartPhase.REFRESHING, SHORT_START_BUDGET_MS);
        String asking = ImportConfigurationFromXmlTool.lateFailureMessage("Imported", true, //$NON-NLS-1$
            StartPhase.REFRESHING, SHORT_START_BUDGET_MS);
        String waiting = ImportConfigurationFromXmlTool.lateFailureMessage("Imported", true, //$NON-NLS-1$
            StartPhase.START_ISSUED, SHORT_START_BUDGET_MS);

        for (String message : new String[] { refreshing, asking, waiting })
        {
            assertTrue("every late-failure line must name the project: " + message, //$NON-NLS-1$
                message.contains("'Imported'")); //$NON-NLS-1$
            assertTrue("and the tool: " + message, //$NON-NLS-1$
                message.contains(ImportConfigurationFromXmlTool.NAME));
        }
        assertTrue(refreshing, refreshing.contains("while refreshing the workspace")); //$NON-NLS-1$
        assertTrue(asking, asking.contains("while asking EDT to start it")); //$NON-NLS-1$
        assertTrue(waiting, waiting.contains("while waiting for EDT to start it")); //$NON-NLS-1$
        assertFalse("the three steps must not collapse into one text", //$NON-NLS-1$
            refreshing.equals(asking) || asking.equals(waiting));
    }

    // ==================== The concurrency guard ==================================================

    @Test
    public void testASecondImportOfTheSameProjectIsRefusedWhileTheFirstRuns() throws Exception
    {
        // The "project already exists" guard cannot see a project the first call has not created
        // yet, so without this claim both calls pass it and race inside EDT - the second one's
        // setUpProject fails and the FIRST one's rollback deletes the project (issue #647).
        BlockingRunner runner = new BlockingRunner();
        String name = uniqueProjectName();
        Path source = temporaryDirectory();
        AtomicReference<String> firstAnswer = new AtomicReference<>();
        Thread first = startImportThread(runner, source, name, firstAnswer);
        try
        {
            assertTrue("the first import must have entered the CLI call", //$NON-NLS-1$
                runner.entered.await(SANE_WAIT_MS, TimeUnit.MILLISECONDS));

            String second = ImportConfigurationFromXmlTool.executeWith(
                params(source, name), new BlockingRunner(), new RecordingLifecycle(),
                SHORT_START_BUDGET_MS);
            assertTrue("a concurrent import of the same name must be refused: " + second, //$NON-NLS-1$
                second.contains("is already in progress")); //$NON-NLS-1$
            assertTrue("the refusal must name the project: " + second, second.contains(name)); //$NON-NLS-1$
            assertTrue("the refusal must steer the caller to list_projects: " + second, //$NON-NLS-1$
                second.contains("list_projects")); //$NON-NLS-1$
        }
        finally
        {
            runner.release.countDown();
            first.join(SANE_WAIT_MS);
        }
    }

    @Test
    public void testAConcurrentImportOfADifferentProjectIsNotRefused() throws Exception
    {
        BlockingRunner runner = new BlockingRunner();
        Path source = temporaryDirectory();
        AtomicReference<String> firstAnswer = new AtomicReference<>();
        Thread first = startImportThread(runner, source, uniqueProjectName(), firstAnswer);
        try
        {
            assertTrue("the first import must have entered the CLI call", //$NON-NLS-1$
                runner.entered.await(SANE_WAIT_MS, TimeUnit.MILLISECONDS));

            RecordingLifecycle lifecycle = new RecordingLifecycle();
            lifecycle.startsOnRequest = true;
            String other = ImportConfigurationFromXmlTool.executeWith(
                params(source, uniqueProjectName()), new ImmediateRunner(), lifecycle,
                AMPLE_START_BUDGET_MS);
            assertFalse("an import of a DIFFERENT project must not be refused: " + other, //$NON-NLS-1$
                other.contains("is already in progress")); //$NON-NLS-1$
            assertTrue("it must run to the normal answer: " + other, //$NON-NLS-1$
                other.contains("status: success")); //$NON-NLS-1$
        }
        finally
        {
            runner.release.countDown();
            first.join(SANE_WAIT_MS);
        }
    }

    @Test
    public void testTheClaimIsReleasedSoTheSameNameCanBeImportedAgain() throws Exception
    {
        // A claim that is never released turns one failed import into a permanently unusable
        // project name for the rest of the EDT session.
        BlockingRunner runner = new BlockingRunner();
        String name = uniqueProjectName();
        Path source = temporaryDirectory();
        AtomicReference<String> firstAnswer = new AtomicReference<>();
        Thread first = startImportThread(runner, source, name, firstAnswer);
        assertTrue("the first import must have entered the CLI call", //$NON-NLS-1$
            runner.entered.await(SANE_WAIT_MS, TimeUnit.MILLISECONDS));
        runner.release.countDown();
        first.join(SANE_WAIT_MS);
        assertFalse("the first import must have finished", first.isAlive()); //$NON-NLS-1$

        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startsOnRequest = true;
        String again = ImportConfigurationFromXmlTool.executeWith(params(source, name),
            new ImmediateRunner(), lifecycle, AMPLE_START_BUDGET_MS);
        assertFalse("the name must be importable again once the first import finished: " + again, //$NON-NLS-1$
            again.contains("is already in progress")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteGoesThroughTheSeamThatHoldsTheGuard() throws Exception
    {
        // The wiring pin. execute() is a one-line delegation to executeWith, and the concurrency
        // claim lives inside it: an execute() that bypassed the seam (for instance by keeping the
        // old close/open/refresh body) could not produce this refusal at all.
        BlockingRunner runner = new BlockingRunner();
        String name = uniqueProjectName();
        Path source = temporaryDirectory();
        AtomicReference<String> firstAnswer = new AtomicReference<>();
        Thread first = startImportThread(runner, source, name, firstAnswer);
        try
        {
            assertTrue("the first import must have entered the CLI call", //$NON-NLS-1$
                runner.entered.await(SANE_WAIT_MS, TimeUnit.MILLISECONDS));

            String viaExecute = new ImportConfigurationFromXmlTool().execute(params(source, name));
            assertTrue("execute() must reach the seam's concurrency guard: " + viaExecute, //$NON-NLS-1$
                viaExecute.contains("is already in progress")); //$NON-NLS-1$
        }
        finally
        {
            runner.release.countDown();
            first.join(SANE_WAIT_MS);
        }
    }

    // ==================== The guide states the behaviour the tool now has =========================

    @Test
    public void testGuideDescribesTheExplicitProjectStart()
    {
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertTrue("the guide must say the tool starts the imported project: " + guide, //$NON-NLS-1$
            guide.contains("asks EDT to start the project")); //$NON-NLS-1$
        assertTrue("the guide must pair each reported state with its projectReady value", //$NON-NLS-1$
            guide.contains("`state: ready` with `projectReady: true`") //$NON-NLS-1$
                && guide.contains("`state: importing` with `projectReady: false`")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNoLongerPromisesACloseOpenRefresh()
    {
        // The old note promised a close/open/refresh AND asserted it was enough. Both halves were
        // wrong: a batched close+open emits no OPEN delta, so nothing started the project.
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertFalse("the close/open/refresh claim must be gone from the guide: " + guide, //$NON-NLS-1$
            guide.contains("close/open/refresh")); //$NON-NLS-1$
    }

    @Test
    public void testGuideStatesTheSameStartBudgetTheCodeUses()
    {
        // One number, two places. A budget changed in the code and not in the guide would leave
        // the caller waiting on a promise the tool no longer makes.
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        String seconds = String.valueOf(ImportConfigurationFromXmlTool.PROJECT_START_BUDGET_MS / 1000L);
        assertTrue("the guide must state the start-wait budget of " + seconds + " seconds: " //$NON-NLS-1$ //$NON-NLS-2$
            + guide, guide.contains(seconds + "-second wait") || guide.contains(seconds + " seconds")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideSaysTheLatchIsReleasedOnlyWhenThePostImportStepFails()
    {
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertTrue("the guide must say releasing the latch would arm a competing start: " //$NON-NLS-1$
            + guide, guide.contains("second, competing start")); //$NON-NLS-1$
        assertTrue("and that it is released only if the post-import step fails: " + guide, //$NON-NLS-1$
            guide.contains("only if that post-import step itself fails")); //$NON-NLS-1$
        assertTrue("naming both halves of that step, since both release it: " + guide, //$NON-NLS-1$
            guide.contains("the workspace refresh or the start request")); //$NON-NLS-1$
    }

    @Test
    public void testGuideWarnsThatReadyCanStillReadAsBuilding()
    {
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertTrue("the guide must separate 'context started' from 'indexed': " + guide, //$NON-NLS-1$
            guide.contains("is about the CONTEXT, not about indexing")); //$NON-NLS-1$
    }

    @Test
    public void testGuideStatesTheWorstCaseWallClock()
    {
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertTrue("the guide must warn callers with transport limits: " + guide, //$NON-NLS-1$
            guide.contains("Budget the client-side call timeout")); //$NON-NLS-1$
        assertTrue("and say the import itself is unbounded: " + guide, //$NON-NLS-1$
            guide.contains("unbounded on the platform")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsTheConcurrentImportRefusal()
    {
        String guide = new ImportConfigurationFromXmlTool().getGuide();
        assertTrue("the guide must document the one-import-per-name refusal: " + guide, //$NON-NLS-1$
            guide.contains("already in progress")); //$NON-NLS-1$
    }

    // ==================== helpers ================================================================

    /**
     * Runs the whole tool with a fake import and the supplied lifecycle.
     *
     * @param lifecycle the lifecycle to drive
     * @param budgetMs the start budget
     * @return the tool's answer
     * @throws IOException when the temporary source directory cannot be created
     */
    private static String runImport(IImportLifecycle lifecycle, long budgetMs) throws IOException
    {
        return ImportConfigurationFromXmlTool.executeWith(
            params(temporaryDirectory(), uniqueProjectName()), new ImmediateRunner(), lifecycle,
            budgetMs);
    }

    private static Map<String, String> params(Path importPath, String projectName)
    {
        Map<String, String> params = new HashMap<>();
        params.put("importPath", importPath.toString()); //$NON-NLS-1$
        params.put("projectName", projectName); //$NON-NLS-1$
        return params;
    }

    /**
     * A directory that exists, so the path guards pass and the run reaches the import.
     *
     * @return the directory
     * @throws IOException when it cannot be created
     */
    private static Path temporaryDirectory() throws IOException
    {
        Path dir = Files.createTempDirectory("import-cfg-e647-"); //$NON-NLS-1$
        dir.toFile().deleteOnExit();
        return dir;
    }

    /** A project name no workspace can already hold, so the existence guard passes. */
    private static String uniqueProjectName()
    {
        return "ZZImport647" + UUID.randomUUID().toString().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A project HANDLE for a project that is never created - no workspace mutation. */
    private static IProject projectHandle()
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(uniqueProjectName());
    }

    /** The platform refusing a refresh, as the tool sees it. */
    private static CoreException refreshRefused()
    {
        return new CoreException(new Status(IStatus.ERROR, "test", "refresh refused")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Records what the bundle logs while attached - the same {@link ILogListener} seam the other
     * headless log pins in this suite use. Entries are matched by the project name they carry, so
     * a neighbouring test logging in the same window cannot satisfy or spoil a pin.
     */
    private static final class LogRecorder
    {
        private final ILog log;
        private final List<IStatus> recorded = Collections.synchronizedList(new ArrayList<>());
        private final ILogListener listener = (status, plugin) -> recorded.add(status);

        private LogRecorder(ILog log)
        {
            this.log = log;
        }

        static LogRecorder start()
        {
            Bundle bundle = FrameworkUtil.getBundle(ImportConfigurationFromXmlTool.class);
            assertNotNull("the log can only be observed from inside OSGi; without the bundle a " //$NON-NLS-1$
                + "'nothing was logged' pin would pass by seeing nothing at all", bundle); //$NON-NLS-1$
            LogRecorder recorder = new LogRecorder(Platform.getLog(bundle));
            recorder.log.addLogListener(recorder.listener);
            return recorder;
        }

        void stop()
        {
            log.removeLogListener(listener);
        }

        List<IStatus> entriesNaming(String projectName)
        {
            List<IStatus> ours = new ArrayList<>();
            synchronized (recorded)
            {
                for (IStatus status : recorded)
                {
                    if (status.getMessage() != null && status.getMessage().contains(projectName))
                    {
                        ours.add(status);
                    }
                }
            }
            return ours;
        }

        IStatus awaitEntryNaming(String projectName, long timeoutMs) throws InterruptedException
        {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline)
            {
                List<IStatus> ours = entriesNaming(projectName);
                if (!ours.isEmpty())
                {
                    return ours.get(0);
                }
                Thread.sleep(10);
            }
            return null;
        }
    }

    private static Thread startImportThread(IImportRunner runner, Path source, String projectName,
        AtomicReference<String> answer)
    {
        Thread thread = new Thread(() -> answer.set(ImportConfigurationFromXmlTool.executeWith(
            params(source, projectName), runner, new RecordingLifecycle(), SHORT_START_BUDGET_MS)),
            "import-647-first"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** An import that returns at once and touches nothing. */
    private static final class ImmediateRunner implements IImportRunner
    {
        @Override
        public Object importApi()
        {
            return this;
        }

        @Override
        public void runImport(Object api, Path importPath, String projectName, String projectNature,
            String xmlVersion)
        {
            // No import: the tests are about what happens AFTER one returns.
        }
    }

    /** An import that parks inside the CLI call until released, so the claim is observable. */
    private static final class BlockingRunner implements IImportRunner
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Object importApi()
        {
            return this;
        }

        @Override
        public void runImport(Object api, Path importPath, String projectName, String projectNature,
            String xmlVersion) throws InterruptedException
        {
            entered.countDown();
            assertTrue("the blocked import must be released by the test", //$NON-NLS-1$
                release.await(SANE_WAIT_MS, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Records the post-import step in the order it happened and answers as configured. Thread-safe
     * because the step runs in a {@link com.ditrix.edt.mcp.server.utils.BoundedJob} while the
     * caller reads the state back.
     */
    private static final class RecordingLifecycle implements IImportLifecycle
    {
        static final String REFRESH = "refreshLocal"; //$NON-NLS-1$
        static final String PERMIT = "permitImport"; //$NON-NLS-1$
        static final String IS_STARTED = "isStarted"; //$NON-NLS-1$
        static final String START = "startWorkspaceProjects"; //$NON-NLS-1$
        static final String HAS_DT = "hasDtProject"; //$NON-NLS-1$

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Collection<WorkspaceProjectStartRequest>> startRequests =
            Collections.synchronizedList(new ArrayList<>());

        volatile boolean projectExists = true;
        volatile boolean started;
        volatile boolean startsOnRequest;
        volatile boolean dtProject = true;
        volatile RuntimeException startFailure;

        /** Thrown by the refresh once it is done waiting (at once, when nothing blocks it). */
        volatile CoreException refreshFailure;

        /** Set when the refresh must park until {@link #releaseRefresh()}. */
        private volatile CountDownLatch refreshRelease;

        /** Counts down when the start request has been issued (or deliberately skipped). */
        private final CountDownLatch startIssued = new CountDownLatch(1);

        /** Whether the monitor handed to the refresh ever reported cancellation. */
        final AtomicBoolean refreshSawCancel = new AtomicBoolean();

        /** How many times the blocked refresh has asked its monitor whether it was cancelled. */
        final AtomicInteger refreshPolls = new AtomicInteger();

        void blockRefreshUntilReleased()
        {
            refreshRelease = new CountDownLatch(1);
        }

        void releaseRefresh()
        {
            CountDownLatch latch = refreshRelease;
            if (latch != null)
            {
                latch.countDown();
            }
        }

        boolean awaitStartIssued(long timeoutMs) throws InterruptedException
        {
            return startIssued.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        boolean awaitRefreshPolls(int target, long timeoutMs) throws InterruptedException
        {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline)
            {
                if (refreshPolls.get() >= target)
                {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }

        int firstIndexOfLifecycleCall()
        {
            synchronized (calls)
            {
                for (int i = 0; i < calls.size(); i++)
                {
                    String call = calls.get(i);
                    if (IS_STARTED.equals(call) || START.equals(call) || PERMIT.equals(call))
                    {
                        return i;
                    }
                }
            }
            return -1;
        }

        @Override
        public boolean projectExists(IProject project)
        {
            return projectExists;
        }

        @Override
        public void refreshLocal(IProject project, IProgressMonitor monitor) throws CoreException
        {
            calls.add(REFRESH);
            CountDownLatch latch = refreshRelease;
            if (latch != null)
            {
                awaitRefreshRelease(latch, monitor);
            }
            CoreException failure = refreshFailure;
            if (failure != null)
            {
                throw failure;
            }
        }

        /**
         * A well-behaved platform call: it polls its monitor and unwinds when cancelled. That is
         * exactly what must NOT happen here, so the monitor the tool hands it decides whether this
         * refresh survives the deadline.
         *
         * <p>The monitor is polled FIRST and the release waited on second. The other order lets a
         * release that lands inside the same wait window end the loop before the cancel is ever
         * seen - which made the deadline pin pass against a refresh that DID honour the deadline.
         */
        private void awaitRefreshRelease(CountDownLatch latch, IProgressMonitor monitor)
        {
            try
            {
                while (true)
                {
                    if (monitor.isCanceled())
                    {
                        refreshSawCancel.set(true);
                        throw new OperationCanceledException();
                    }
                    refreshPolls.incrementAndGet();
                    if (latch.await(20, TimeUnit.MILLISECONDS))
                    {
                        return;
                    }
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void permitImport(IProject project)
        {
            calls.add(PERMIT);
        }

        @Override
        public boolean isStarted(IProject project)
        {
            calls.add(IS_STARTED);
            return started;
        }

        @Override
        public void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
            IProgressMonitor monitor)
        {
            calls.add(START);
            startRequests.add(requests);
            if (startFailure != null)
            {
                throw startFailure;
            }
            started = startsOnRequest;
            startIssued.countDown();
        }

        @Override
        public boolean hasDtProject(IProject project)
        {
            calls.add(HAS_DT);
            return dtProject;
        }
    }
}

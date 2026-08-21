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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.Backend;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.ComparisonException;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.Launch;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.LaunchRequest;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.Progress;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.StopOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonEngine;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry.ReleaseOutcome;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonTreeReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless contract tests for {@link CompareConfigurationsTool}, against a stub backend.
 *
 * <p>The tool's own promises are what is pinned here, and each of them has a way of being
 * broken silently: a launch that blocks looks like a slow launch; a second launch that queues
 * looks like an accepted launch; a comparison that failed looks like a comparison that is still
 * running, because the platform's status enum has no FAILED literal at all.</p>
 */
public class CompareConfigurationsToolTest
{
    private static final Pattern JOB_ID_ROW =
        Pattern.compile("(?m)^\\| jobId \\| ([^|]+) \\|$"); //$NON-NLS-1$

    private BackgroundJobs jobs;
    private StubBackend backend;
    private CompareConfigurationsTool tool;

    @Before
    public void setUp()
    {
        jobs = new BackgroundJobs(20, 2);
        backend = new StubBackend();
        tool = new CompareConfigurationsTool(backend, jobs);
    }

    @After
    public void tearDown()
    {
        backend.finish();
        jobs.close();
    }

    @Test
    public void testStaticContract()
    {
        assertEquals("compare_configurations", tool.getName()); //$NON-NLS-1$
        assertEquals(CompareConfigurationsTool.NAME, tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        // MARKDOWN tools carry content, not structuredContent, so they declare no outputSchema.
        assertNull(tool.getOutputSchema());

        String description = tool.getDescription();
        // The load-bearing protocol facts must be in the DESCRIPTION: InputSchemaCompactor
        // strips parameter prose that is not on its allowlist, so a fact stated only there
        // would never reach the client.
        assertTrue(description.contains("jobId")); //$NON-NLS-1$
        assertTrue(description.contains("get_job_status")); //$NON-NLS-1$
        assertTrue(description.contains("cancel_job")); //$NON-NLS-1$
        assertTrue(description.contains("ONE")); //$NON-NLS-1$
        assertTrue(description.contains("WHOLE")); //$NON-NLS-1$
        assertTrue(description.contains("get_tool_guide('compare_configurations')")); //$NON-NLS-1$

        ToolAnnotations annotations = tool.getAnnotations();
        // Not read-only: the call takes EDT's single comparison slot. Not destructive: nothing
        // in the caller's project is touched.
        assertEquals(Boolean.FALSE, annotations.getReadOnlyHint());
        assertEquals(Boolean.FALSE, annotations.getDestructiveHint());
    }

    @Test
    public void testSchemaDeclaresEveryParameterTheToolReads()
    {
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        for (String declared : new String[] {"projectName", "otherRevision", "ancestorRevision", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "mergeRulesFile", "waitSeconds", "limit", "changedOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "releaseComparisonId"}) //$NON-NLS-1$
        {
            assertTrue("inputSchema must declare " + declared, properties.has(declared)); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheReleaseFormValidatesAgainstTheSchema()
    {
        // The three launch parameters must NOT be in 'required'. This tool answers a second
        // call shape - releaseComparisonId alone - and it is the ONLY reachable way to give a
        // finished comparison's session back, because cancel_job answers ALREADY_TERMINAL by
        // then. A schema-validating client obeying a required list that shape cannot satisfy
        // could never make that call at all.
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        String required = schema.getAsJsonArray("required").toString(); //$NON-NLS-1$
        assertFalse(required.contains("projectName")); //$NON-NLS-1$
        assertFalse(required.contains("otherRevision")); //$NON-NLS-1$
        assertFalse(required.contains("ancestorRevision")); //$NON-NLS-1$
        assertFalse(required.contains("scope")); //$NON-NLS-1$
        // Not required is not the same as optional, and the prose has to say which: a launch
        // without them is still refused at runtime (testMissingArgumentsAreActionable pins the
        // refusal), so each of the three says when it is needed.
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        for (String launchParameter : new String[] {"projectName", "otherRevision", //$NON-NLS-1$ //$NON-NLS-2$
            "ancestorRevision"}) //$NON-NLS-1$
        {
            assertContains(properties.getAsJsonObject(launchParameter).get("description") //$NON-NLS-1$
                .getAsString(), "Required unless releaseComparisonId is given."); //$NON-NLS-1$
        }
    }

    @Test
    public void testStartReturnsAJobIdWhileTheComparisonIsStillRunning() throws Exception
    {
        backend.keepRunning();

        long before = System.currentTimeMillis();
        String result = tool.execute(request(Map.of("waitSeconds", "0"))); //$NON-NLS-1$ //$NON-NLS-2$
        long elapsed = System.currentTimeMillis() - before;

        assertTrue("the launch must not wait for the comparison, took " + elapsed + " ms", //$NON-NLS-1$ //$NON-NLS-2$
            elapsed < 5_000L);
        assertContains(result, "**Pending:**"); //$NON-NLS-1$
        assertContains(result, "get_job_status"); //$NON-NLS-1$
        String jobId = jobId(result);
        assertTrue(backend.awaitStarted());
        assertEquals(BackgroundJobs.Status.RUNNING, jobs.get(jobId).getStatus());
    }

    @Test
    public void testASecondLaunchIsRefusedNamingTheLiveComparisonAndIsNeverQueued()
    {
        backend.setActiveComparisonId("cmp-live-7"); //$NON-NLS-1$

        String result = tool.execute(request(Map.of()));

        String error = errorMessage(result);
        assertContains(error, "cmp-live-7"); //$NON-NLS-1$
        assertContains(error, "cancel_job"); //$NON-NLS-1$
        assertContains(error, "refused rather than queued"); //$NON-NLS-1$
        // "Never queued" is the claim, so the proof is that nothing was handed to the engine.
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAFinishedComparisonReturnsTheRenderedReport() throws Exception
    {
        backend.setReport("# Comparison: TestConfiguration\n\nCONFLICT (changed on both sides)"); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: done"); //$NON-NLS-1$
        assertContains(result, "# Comparison: TestConfiguration"); //$NON-NLS-1$
        assertContains(result, "CONFLICT (changed on both sides)"); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownRevisionFailsTheJobNamingTheValueAndTheFix()
    {
        backend.failStartWith("otherRevision 'no-such-branch' does not resolve to a commit in " //$NON-NLS-1$
            + "this project's repository. Use list_git_branches to see the branches, or pass " //$NON-NLS-1$
            + "a tag or a full commit id."); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("otherRevision", "no-such-branch", //$NON-NLS-1$ //$NON-NLS-2$
            "waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertContains(result, "no-such-branch"); //$NON-NLS-1$
        assertContains(result, "list_git_branches"); //$NON-NLS-1$
    }

    @Test
    public void testAFailureCauseIsReportedAsFailedRatherThanStillRunning()
    {
        // The platform enum has NO failed literal: a failed comparison keeps its last status
        // forever. A loop that trusted the status alone would render this as "running" until
        // the job's own two-hour budget expired.
        backend.setPollAnswer(Progress.failed("Cannot open repository: the index is locked")); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertContains(result, "Cannot open repository: the index is locked"); //$NON-NLS-1$
        assertFalse("a failed comparison must not be published as running:\n" + result, //$NON-NLS-1$
            result.contains("| status | running |")); //$NON-NLS-1$
        // The session must not be left behind when the comparison dies.
        assertEquals(1, backend.releases());
    }

    @Test
    public void testAnUnreadableStatusTickDoesNotEndAHealthyComparison() throws Exception
    {
        // A tick on which EDT reported NO status - the read threw, or it briefly could not
        // answer for the handle. That is an absence of information, not a verdict: the next
        // tick answers normally and the comparison finishes. Treating one such tick as fatal
        // stops a comparison that was never in trouble, and stops it irreversibly.
        backend.queuePollAnswers(
            Progress.unknown("reading the status from EDT failed: service went away")); //$NON-NLS-1$
        backend.setReport("# Comparison: TestConfiguration"); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: done"); //$NON-NLS-1$
        assertContains(result, "# Comparison: TestConfiguration"); //$NON-NLS-1$
        // Nothing was stopped and nothing was given back: the comparison ran to its own end.
        assertEquals(0, backend.cancels());
        assertEquals(0, backend.releases());
    }

    @Test
    public void testAStatusThatStaysUnreadableFailsWithoutQuotingAStatusEdtNeverGave()
    {
        // Same absence, but it never clears. The job does have to end - a comparison nobody can
        // read must not sit on EDT's single slot for two hours - and the message must say what
        // was observed: EDT reported nothing. Crediting the platform with having reported a
        // status is how a caller ends up chasing a literal that was never on the wire.
        backend.setPollAnswer(Progress.unknown("EDT answered no status for this comparison, " //$NON-NLS-1$
            + "which its manager does when it no longer holds the session")); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "20"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertContains(result, "gave no status"); //$NON-NLS-1$
        assertContains(result, "no longer holds the session"); //$NON-NLS-1$
        assertFalse("an absence must not be reported as something EDT said:\n" + result, //$NON-NLS-1$
            result.contains("EDT reported comparison status")); //$NON-NLS-1$
        assertFalse("the tool's own placeholder must never be quoted as a platform status:\n" //$NON-NLS-1$
            + result, result.contains("'starting'")); //$NON-NLS-1$
        // The slot goes back: cancel() stops the comparison AND releases its session.
        assertEquals(1, backend.cancels());
    }

    @Test
    public void testCancellingTheJobStopsTheComparison() throws Exception
    {
        backend.keepRunning();
        String result = tool.execute(request(Map.of("waitSeconds", "0"))); //$NON-NLS-1$ //$NON-NLS-2$
        String jobId = jobId(result);
        assertTrue(backend.awaitStarted());

        CancellationResult cancellation = jobs.cancel(jobId);

        assertEquals(1, backend.cancels());
        assertEquals(CancellationOutcome.TERMINATED, cancellation.getOutcome());
        assertContains(cancellation.getDetail(), backend.lastComparisonId());
    }

    @Test
    public void testACancellationEdtNoLongerHeldIsNotReportedAsAVerifiedStop() throws Exception
    {
        // TERMINATED is the registry's word for "the owning tool stopped the committed work",
        // and a caller reading it stops looking. Here nothing was stopped: EDT had already let
        // the handle go, so there was nothing to stop at all.
        backend.keepRunning();
        backend.answerCancelWith(StopOutcome.NOTHING_TO_STOP);
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult cancellation = jobs.cancel(jobId);

        assertEquals(CancellationOutcome.ALREADY_COMMITTED, cancellation.getOutcome());
        assertContains(cancellation.getDetail(), "no longer held comparison"); //$NON-NLS-1$
        assertContains(cancellation.getDetail(), "nothing to stop"); //$NON-NLS-1$
        assertContains(cancellation.getDetail(), backend.lastComparisonId());
    }

    @Test
    public void testACancellationThatNeverReachedEdtSaysTheSlotMayStillBeHeld() throws Exception
    {
        // The comparison service was not registered at that moment, so the stop request never
        // reached EDT. The comparison may well still be running and holding EDT's single slot,
        // and the caller is the only one who can go and end it - so the detail has to say so
        // rather than close the matter with a verified stop.
        backend.keepRunning();
        backend.answerCancelWith(StopOutcome.SERVICE_UNAVAILABLE);
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult cancellation = jobs.cancel(jobId);

        assertEquals(CancellationOutcome.ALREADY_COMMITTED, cancellation.getOutcome());
        assertContains(cancellation.getDetail(), "comparison service was not available"); //$NON-NLS-1$
        assertContains(cancellation.getDetail(), "could NOT be stopped"); //$NON-NLS-1$
        assertContains(cancellation.getDetail(), "single comparison slot"); //$NON-NLS-1$
    }

    @Test
    public void testAJobEndedByTheToolSaysWhetherTheComparisonWasActuallyStopped()
    {
        // Its own test, and its own literal. Every branch that ends a job by stopping the
        // comparison - this one, the expired job budget, and a cancellation that arrives during
        // a slow launch - now words its sentence from the SAME stop verdict, so pinning one of
        // them pins the wording all three share. (The budget branch cannot be reached from a
        // unit test: its bound is the job's two hours.) They used to claim the stop
        // unconditionally, after a call that can fail to reach EDT at all.
        backend.answerCancelWith(StopOutcome.SERVICE_UNAVAILABLE);
        backend.setPollAnswer(Progress.unknown("EDT answered no status for this comparison")); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "20"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertContains(result, "could NOT be stopped"); //$NON-NLS-1$
        assertFalse("a stop that never reached EDT must not be reported as done:\n" + result, //$NON-NLS-1$
            result.contains("has been stopped and its session released")); //$NON-NLS-1$
    }

    @Test
    public void testMissingArgumentsAreActionable()
    {
        assertContains(tool.execute(Map.of()), "projectName is required"); //$NON-NLS-1$
        assertContains(tool.execute(Map.of("projectName", "TestConfiguration")), //$NON-NLS-1$ //$NON-NLS-2$
            "otherRevision is required"); //$NON-NLS-1$
        assertContains(tool.execute(Map.of("projectName", "TestConfiguration", //$NON-NLS-1$ //$NON-NLS-2$
            "otherRevision", "origin/main")), "ancestorRevision is required"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAnUnknownProjectIsAStructuredErrorAndTakesNoComparisonSlot()
    {
        backend.failPrecheckWith("Project not found: Nope. Use list_projects to see available " //$NON-NLS-1$
            + "projects."); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("projectName", "Nope"))); //$NON-NLS-1$ //$NON-NLS-2$

        String error = errorMessage(result);
        assertContains(error, "Project not found: Nope"); //$NON-NLS-1$
        assertContains(error, "list_projects"); //$NON-NLS-1$
        // The check is worth doing early exactly because EDT runs one comparison at a time: a
        // typo that took the slot and then failed would block the next honest attempt.
        assertEquals(0, backend.starts());
    }

    @Test
    public void testABlankRevisionIsRefusedRatherThanComparedAgainstNothing()
    {
        String result = tool.execute(request(Map.of("otherRevision", "   "))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "non-blank"); //$NON-NLS-1$
        assertContains(result, "list_git_branches"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAnOutOfRangeWaitIsActionable()
    {
        String result = tool.execute(request(Map.of("waitSeconds", "600"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "waitSeconds must be an integer from 0 to 25"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAnUnreadableMergeRulesFileIsRefusedBeforeAnythingIsStarted()
    {
        String result = tool.execute(request(Map.of("mergeRulesFile", //$NON-NLS-1$
            "no-such-directory-cc-test/rules.xml"))); //$NON-NLS-1$

        assertContains(result, "mergeRulesFile"); //$NON-NLS-1$
        assertContains(result, "no-such-directory-cc-test"); //$NON-NLS-1$
        // Refused BEFORE the launch on purpose: EDT runs one comparison at a time, so a typo
        // that took the slot and then failed would block the next honest attempt too.
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAReadableMergeRulesFileReachesTheBackend() throws Exception
    {
        Path rules = Files.createTempFile("compare-rules", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            tool.execute(request(Map.of("mergeRulesFile", rules.toString(), //$NON-NLS-1$
                "waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

            LaunchRequest seen = backend.lastRequest();
            assertNotNull(seen);
            assertEquals(rules.toString(), seen.getMergeRulesFile());
        }
        finally
        {
            Files.deleteIfExists(rules);
        }
    }

    @Test
    public void testScopeLimitAndFilterReachTheBackendVerbatim()
    {
        tool.execute(request(Map.of("scope", "[\"Catalog.Goods\",\"Document.Order\"]", //$NON-NLS-1$ //$NON-NLS-2$
            "limit", "7", "changedOnly", "false", "waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        LaunchRequest seen = backend.lastRequest();
        assertNotNull(seen);
        assertEquals(List.of("Catalog.Goods", "Document.Order"), seen.getScope()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(7, seen.getLimit());
        assertFalse(seen.isChangedOnly());
    }

    @Test
    public void testAnOmittedScopeIsAWholeConfigurationRequestNotARefusal()
    {
        tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        LaunchRequest seen = backend.lastRequest();
        assertNotNull(seen);
        // Empty, not refused: the platform treats an empty scope as "compare everything", and
        // that is the decided behaviour for an omitted scope.
        assertTrue(seen.getScope().isEmpty());
        assertTrue(seen.isChangedOnly());
        assertEquals(1, backend.starts());
    }

    @Test
    public void testTheDescriptionSaysHowToFreeTheSlotOfAFinishedComparison()
    {
        // Its own test rather than one more line in the static contract: JUnit stops a
        // method at the first failed assertion, so a fact bundled with others is only
        // checked while every fact above it holds.
        String description = tool.getDescription();
        assertTrue(description.contains("releaseComparisonId")); //$NON-NLS-1$
        // The protocol fact that makes it necessary: cancel_job stops working the moment
        // the comparison finishes, and a caller told only about cancel_job is stranded.
        assertTrue(description.contains("FINISHED")); //$NON-NLS-1$
    }

    @Test
    public void testAFinishedComparisonKeepsItsSessionSoTheTreeStaysReadable()
    {
        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: done"); //$NON-NLS-1$
        // Deliberate, and the reason the release below has to exist: the session outlives
        // the job because get_comparison_node reads it. Releasing it here would make every
        // expand of the report that was just handed to the caller fail.
        assertEquals(0, backend.releases());
    }

    @Test
    public void testReleaseComparisonIdClosesTheComparisonAndStartsNothing()
    {
        String result = tool.execute(Map.of("releaseComparisonId", "cmp-4")); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "**Released:**"); //$NON-NLS-1$
        assertContains(result, "cmp-4"); //$NON-NLS-1$
        assertEquals(1, backend.releases());
        assertEquals("cmp-4", backend.lastReleased()); //$NON-NLS-1$
        // A release is not a launch: the three launch parameters are not even read, which
        // is why this form is answered before they are demanded.
        assertEquals(0, backend.starts());
    }

    /**
     * A release that could not stop the comparison must not say the slot is free.
     * <p>
     * The registry used to drop its record, swallow whatever the stop threw and answer {@code true}
     * regardless, so this branch printed "EDT's single comparison slot is free again" over a
     * comparison that may still be running - and that sentence is the one the caller acts on.
     */
    @Test
    public void testAReleaseThatStoppedNothingDoesNotSayTheSlotIsFree()
    {
        backend.answerReleaseWith(ReleaseOutcome.STOP_FAILED);

        String result = tool.execute(Map.of("releaseComparisonId", "cmp-4")); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "cmp-4"); //$NON-NLS-1$
        assertContains(result, "stop NOT confirmed"); //$NON-NLS-1$
        assertFalse("a release that stopped nothing must not report a free slot:\n" + result, //$NON-NLS-1$
            result.contains("slot is free again")); //$NON-NLS-1$
        assertContains(result, "Do NOT assume"); //$NON-NLS-1$
    }

    @Test
    public void testReleasingAComparisonNobodyHoldsIsRefusedNamingTheOnesThatExist()
    {
        backend.refuseRelease();
        backend.setLiveComparisonIds(List.of("cmp-9")); //$NON-NLS-1$

        String result = tool.execute(Map.of("releaseComparisonId", "cmp-nope")); //$NON-NLS-1$ //$NON-NLS-2$

        String error = errorMessage(result);
        assertContains(error, "cmp-nope"); //$NON-NLS-1$
        // "There was nothing to release" and "the comparison you named is closed" are
        // different facts: a caller acting on the second would believe a slot was freed
        // that somebody else still holds.
        assertContains(error, "cmp-9"); //$NON-NLS-1$
    }

    // ==================== The tree walk ====================

    /**
     * A top object can hang BELOW a containment node, and the report has to see it.
     * <p>
     * {@code Compare.xcore} gives {@code ComparisonNode} two child collections - {@code refers
     * TopComparisonNode[] topChildren} and {@code contains ContainmentComparisonNode[]
     * containmentChildren} - and the walk used to descend only the first. A comparison whose top
     * objects sit under their collection's containment node then collected ZERO nodes, and the
     * report said the comparison had found nothing rather than that the walk had looked nowhere.
     */
    @Test
    public void testATopObjectUnderAContainmentNodeIsStillReported()
    {
        ComparisonNode root = mock(ComparisonNode.class);
        ComparisonNode containment = mock(ComparisonNode.class);
        TopComparisonNode top = mock(TopComparisonNode.class);
        withChildren(root, containment);
        withChildren(containment, top);
        // The narrow walk's own view of this tree: empty at every level, all the way down.
        when(root.getTopChildren()).thenReturn(new BasicEList<TopComparisonNode>());
        when(containment.getTopChildren()).thenReturn(new BasicEList<TopComparisonNode>());
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(100, false);

        CompareConfigurationsTool.EngineBackend.collectTopNodes(root, collector);

        assertEquals("the top object below the containment node must be in the report", 1, //$NON-NLS-1$
            collector.getTotal());
    }

    /** The control: a top object hanging directly off the root is still reported. */
    @Test
    public void testATopObjectDirectlyUnderTheRootIsStillReported()
    {
        ComparisonNode root = mock(ComparisonNode.class);
        TopComparisonNode top = mock(TopComparisonNode.class);
        withChildren(root, top);
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(100, false);

        CompareConfigurationsTool.EngineBackend.collectTopNodes(root, collector);

        assertEquals(1, collector.getTotal());
    }

    // ==================== The production backend with no EDT ====================

    /**
     * Without EDT's comparison service there is nothing to stop, and the verdict says so instead of
     * reporting a stop. This is the branch the facade now also reaches from the OTHER direction -
     * a service that vanishes mid-call throws rather than returning quietly - and both land here.
     */
    @Test
    public void testTheProductionBackendReportsAStopItCouldNotPerform()
    {
        ComparisonEngine.uninstall();

        assertEquals(StopOutcome.SERVICE_UNAVAILABLE,
            new CompareConfigurationsTool.EngineBackend().cancel("cmp-1")); //$NON-NLS-1$
    }

    /** And a launch it could not perform is a refusal, in the shared wording. */
    @Test
    public void testTheProductionBackendRefusesToLaunchWithoutTheService()
    {
        ComparisonEngine.uninstall();

        try
        {
            new CompareConfigurationsTool.EngineBackend().start(new LaunchRequest(
                "TestConfiguration", "HEAD", "HEAD~1", null, null, 100, true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            org.junit.Assert.fail("a launch with no comparison service must be refused"); //$NON-NLS-1$
        }
        catch (ComparisonException e)
        {
            assertContains(e.getMessage(), "comparison service is not available"); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheRefusalNamesTheRemedyThatWorksForAFinishedComparison()
    {
        backend.setActiveComparisonId("cmp-live-7"); //$NON-NLS-1$

        String error = errorMessage(tool.execute(request(Map.of())));

        // cancel_job alone is not an answer: the comparison in the way may have FINISHED,
        // and a finished comparison's job is terminal, so its handler never runs.
        assertContains(error, "releaseComparisonId"); //$NON-NLS-1$
    }

    @Test
    public void testAFinishedComparisonStillHoldsTheSlotThoughEdtReportsNoActiveBatch()
    {
        // The measured platform fact: ComparisonManager's job calls comparisonFinished(batch)
        // straight after the comparison, on the normal AND the throwing path, and that sets the
        // active batch to null - so hasActiveComparison() goes false the moment a comparison
        // FINISHES. The session is still open: it owns the virtual project and the private BM
        // store, and every nodeId already handed to the caller resolves against it. Gating the
        // registry's answer on EDT's flag reported that as "nothing is running", let a second
        // comparison start on top of the first, and made the refusal below unreachable.
        String live = CompareConfigurationsTool.resolveActiveComparisonId("cmp-finished-3", //$NON-NLS-1$
            false);
        assertEquals("cmp-finished-3", live); //$NON-NLS-1$

        backend.setActiveComparisonId(live);
        String error = errorMessage(tool.execute(request(Map.of())));

        assertContains(error, "cmp-finished-3"); //$NON-NLS-1$
        assertContains(error, "releaseComparisonId"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testASlotTakenByAComparisonThisServerCannotNameIsStillARefusal()
    {
        // The other half of the same question, and the ONLY thing EDT's flag is still asked:
        // a comparison started from the workbench holds the slot under no id of ours. An empty
        // id, not null - collapsing it into null would report an occupied workbench as an idle
        // one and the launch would then fail on the platform's assertion instead of a sentence.
        assertEquals("", CompareConfigurationsTool.resolveActiveComparisonId(null, true)); //$NON-NLS-1$

        backend.setActiveComparisonId(""); //$NON-NLS-1$
        String error = errorMessage(tool.execute(request(Map.of())));

        assertContains(error, "did not start"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAnIdleWorkbenchWithNoRegisteredSessionLetsALaunchThrough()
    {
        // Both sources say no, so the launch proceeds. Without this the two tests above would
        // be satisfied by a method that answered "occupied" unconditionally.
        assertNull(CompareConfigurationsTool.resolveActiveComparisonId(null, false));
    }

    @Test
    public void testACancellationArrivingDuringASlowLaunchStopsWhatTheLaunchStarted()
        throws Exception
    {
        // The launch is held open for longer than any private wait this tool used to keep
        // (two seconds), which is ordinary on a real repository: two git revision
        // resolutions, a project lookup and an optional rules file all happen before the
        // comparison id exists. The old handler gave up there, reported "there was nothing
        // to stop", and left the comparison holding EDT's single slot with the job already
        // terminal - so nothing could reach it again.
        backend.keepRunning();
        backend.blockStart();
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStartEntered());

        AtomicReference<CancellationResult> cancellation = new AtomicReference<>();
        Thread canceller = new Thread(() -> cancellation.set(jobs.cancel(jobId)));
        canceller.start();
        Thread.sleep(2_500L);
        backend.releaseStart();
        canceller.join(TimeUnit.SECONDS.toMillis(30));

        assertNotNull("the cancellation must have finished", cancellation.get()); //$NON-NLS-1$
        assertEquals(1, backend.cancels());
        assertEquals(backend.lastComparisonId(), backend.lastCancelled());
        assertEquals(CancellationOutcome.TERMINATED, cancellation.get().getOutcome());
    }

    // === helpers ===

    private static Map<String, String> request(Map<String, String> overrides)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestConfiguration"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("otherRevision", "origin/main"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ancestorRevision", "v1.0"); //$NON-NLS-1$ //$NON-NLS-2$
        params.putAll(overrides);
        return params;
    }

    /**
     * @return a validated request against the fixture project, for the paths that drive
     *     {@code runComparison} directly instead of going through the job registry
     */
    private static LaunchRequest launchRequest()
    {
        return new LaunchRequest("TestConfiguration", "origin/main", "v1.0", null, null, 100, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            true);
    }

    /**
     * A reporter with a real budget, because the poll loop is bounded by nothing else once the job
     * is committed - a test that gave it an unbounded one would hang instead of failing.
     *
     * @param budgetMillis how long the work may take
     * @return the reporter
     */
    private static ProgressReporter reporter(long budgetMillis)
    {
        long deadline = System.currentTimeMillis() + budgetMillis;
        return new ProgressReporter()
        {
            @Override
            public void add(String message)
            {
                // The progress journal is not what these tests are about.
            }

            @Override
            public long remainingMillis()
            {
                return deadline - System.currentTimeMillis();
            }
        };
    }

    private static String jobId(String rendered)
    {
        Matcher matcher = JOB_ID_ROW.matcher(rendered);
        assertTrue("no jobId row in:\n" + rendered, matcher.find()); //$NON-NLS-1$
        return matcher.group(1).trim();
    }

    /**
     * @param parent the node to give children to
     * @param children the children, in order
     */
    private static void withChildren(ComparisonNode parent, ComparisonNode... children)
    {
        EList<ComparisonNode> list = new BasicEList<>();
        for (ComparisonNode child : children)
        {
            list.add(child);
        }
        when(parent.<ComparisonNode> getChildren()).thenReturn(list);
    }

    private static void assertContains(String haystack, String needle)
    {
        assertTrue("expected to find '" + needle + "' in:\n" + haystack, //$NON-NLS-1$ //$NON-NLS-2$
            haystack != null && haystack.contains(needle));
    }

    /**
     * @param result a tool result that must be a structured error
     * @return its error message
     */
    private static String errorMessage(String result)
    {
        JsonObject payload = JsonParser.parseString(result).getAsJsonObject();
        assertFalse("expected a structured error, got:\n" + result, //$NON-NLS-1$
            payload.get("success").getAsBoolean()); //$NON-NLS-1$
        return payload.get("error").getAsString(); //$NON-NLS-1$
    }

    // ==================== Two intents in one call ====================

    @Test
    public void testReleaseCombinedWithALaunchIsRefusedAndDoesNeither()
    {
        Map<String, String> params = new HashMap<>();
        params.put("releaseComparisonId", "cmp-4"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("otherRevision", "release"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ancestorRevision", "base"); //$NON-NLS-1$ //$NON-NLS-2$

        String message = errorMessage(tool.execute(params));

        assertTrue(message, message.contains("releaseComparisonId")); //$NON-NLS-1$
        assertTrue(message, message.contains("projectName")); //$NON-NLS-1$
        // Neither half may happen: reporting a freed slot while silently dropping the launch is
        // exactly the shape the sibling tools of this change refuse.
        assertEquals(0, backend.releases());
        assertEquals(0, backend.starts());
    }

    @Test
    public void testReleaseCombinedWithAScopeOnlyIsAlsoRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("releaseComparisonId", "cmp-4"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("scope", "[\"Catalog.Products\"]"); //$NON-NLS-1$ //$NON-NLS-2$

        String message = errorMessage(tool.execute(params));

        assertTrue(message, message.contains("scope")); //$NON-NLS-1$
        assertEquals(0, backend.releases());
    }

    @Test
    public void testReleaseWithOnlyAPollingKnobIsStillARelease()
    {
        Map<String, String> params = new HashMap<>();
        params.put("releaseComparisonId", "cmp-4"); //$NON-NLS-1$ //$NON-NLS-2$
        // waitSeconds and limit shape how an answer is returned, not what is launched, so a client
        // that always sends them must not be refused a release.
        params.put("waitSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(tool.execute(params), "**Released:**"); //$NON-NLS-1$
        assertEquals(1, backend.releases());
    }

    // ==================== The project must live inside the work tree ====================

    @Test
    public void testAProjectOutsideTheWorkTreeIsRefusedNamingBothPaths() throws Exception
    {
        Path workTree = Files.createTempDirectory("cmp-worktree"); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("cmp-outside"); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.requireProjectInsideWorkTree("Demo", outside, workTree); //$NON-NLS-1$
            org.junit.Assert.fail("expected a refusal for a project outside the work tree"); //$NON-NLS-1$
        }
        catch (ComparisonException e)
        {
            String message = e.getMessage();
            assertTrue(message, message.contains("Demo")); //$NON-NLS-1$
            assertTrue(message, message.contains(outside.toRealPath().toString()));
            assertTrue(message, message.contains(workTree.toRealPath().toString()));
        }
        finally
        {
            Files.deleteIfExists(workTree);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void testAProjectInsideTheWorkTreeIsAccepted() throws Exception
    {
        Path workTree = Files.createTempDirectory("cmp-worktree"); //$NON-NLS-1$
        Path inside = Files.createDirectory(workTree.resolve("project")); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.requireProjectInsideWorkTree("Demo", inside, workTree); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(inside);
            Files.deleteIfExists(workTree);
        }
    }

    @Test
    public void testAnUnknownWorkTreeIsNotTreatedAsAViolation() throws Exception
    {
        Path outside = Files.createTempDirectory("cmp-outside"); //$NON-NLS-1$
        try
        {
            // "Could not ask" is not "outside": a resolver that produced no work tree must not
            // make the launch fail with a path claim nobody measured.
            CompareConfigurationsTool.requireProjectInsideWorkTree("Demo", outside, null); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(outside);
        }
    }

    /** A comparison backend that answers from the test instead of from EDT. */
    private static final class StubBackend implements Backend
    {
        private final AtomicReference<String> activeComparisonId = new AtomicReference<>();
        private final AtomicReference<String> lastComparisonId = new AtomicReference<>();
        private final AtomicReference<LaunchRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<String> startFailure = new AtomicReference<>();
        private final AtomicReference<String> precheckFailure = new AtomicReference<>();
        private final AtomicReference<Progress> pollAnswer =
            new AtomicReference<>(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$
        /** Answers handed out in order before the standing one, so a tick can differ from its
         * neighbours - which is the only way to tell "tolerated once" from "ignored always". */
        private final ConcurrentLinkedQueue<Progress> pollAnswers = new ConcurrentLinkedQueue<>();
        private final AtomicReference<String> lastCancelled = new AtomicReference<>();
        private final AtomicReference<String> lastReleased = new AtomicReference<>();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final AtomicReference<StopOutcome> cancelOutcome =
            new AtomicReference<>(StopOutcome.STOPPED);
        private final AtomicInteger releases = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch startGate = new CountDownLatch(1);
        private volatile boolean blockStart;
        private final AtomicReference<Launch> handOverOnPoll = new AtomicReference<>();
        private final AtomicReference<Launch> requestStopDuringStart = new AtomicReference<>();
        private final AtomicReference<ReleaseOutcome> releaseOutcome =
            new AtomicReference<>(ReleaseOutcome.RELEASED);
        private volatile List<String> liveComparisonIds = List.of();
        private final List<String> reports = new ArrayList<>();
        private volatile String report = "# Comparison: TestConfiguration"; //$NON-NLS-1$

        @Override
        public String precheck(LaunchRequest request)
        {
            return precheckFailure.get();
        }

        @Override
        public String activeComparisonId()
        {
            return activeComparisonId.get();
        }

        @Override
        public String start(LaunchRequest request) throws ComparisonException
        {
            lastRequest.set(request);
            Launch arriving = requestStopDuringStart.getAndSet(null);
            if (arriving != null)
            {
                // The cancellation lands while the launch is in flight - after the launch's
                // pre-start check, which is the only way the duty can still be outstanding once
                // the comparison exists.
                arriving.requestStop();
            }
            startEntered.countDown();
            if (blockStart)
            {
                try
                {
                    startGate.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
            String failure = startFailure.get();
            if (failure != null)
            {
                started.countDown();
                throw new ComparisonException(failure);
            }
            String id = "cmp-" + starts.incrementAndGet(); //$NON-NLS-1$
            lastComparisonId.set(id);
            started.countDown();
            return id;
        }

        @Override
        public Progress poll(String comparisonId)
        {
            Launch handOver = handOverOnPoll.getAndSet(null);
            if (handOver != null)
            {
                // The cancellation handler runs out of time HERE - after the launch's previous
                // check and before its next one. That is the only placement in which the old
                // two-flag protocol lost the request, and no real thread schedule can be made to
                // hit it on purpose.
                handOver.handOverStop();
            }
            Progress queued = pollAnswers.poll();
            return queued == null ? pollAnswer.get() : queued;
        }

        @Override
        public String report(String comparisonId, LaunchRequest request)
        {
            reports.add(comparisonId);
            return report;
        }

        @Override
        public StopOutcome cancel(String comparisonId)
        {
            lastCancelled.set(comparisonId);
            cancels.incrementAndGet();
            return cancelOutcome.get();
        }

        @Override
        public ReleaseOutcome release(String comparisonId)
        {
            lastReleased.set(comparisonId);
            releases.incrementAndGet();
            return releaseOutcome.get();
        }

        @Override
        public List<String> liveComparisonIds()
        {
            return liveComparisonIds;
        }

        void setActiveComparisonId(String id)
        {
            activeComparisonId.set(id);
        }

        /** Makes the next stop attempt observe {@code outcome} instead of a verified stop. */
        void answerCancelWith(StopOutcome outcome)
        {
            cancelOutcome.set(outcome);
        }

        void setPollAnswer(Progress progress)
        {
            pollAnswer.set(progress);
        }

        /**
         * @param launch the launch whose cancellation handler gives up during the next poll
         */
        void handOverDuringFirstPoll(Launch launch)
        {
            handOverOnPoll.set(launch);
        }

        /**
         * @param launch the launch a cancellation arrives for while it is being handed to EDT
         */
        void requestStopDuringStart(Launch launch)
        {
            requestStopDuringStart.set(launch);
        }

        /**
         * @param answers the first ticks' answers, in order; later ticks get the standing one
         */
        void queuePollAnswers(Progress... answers)
        {
            pollAnswers.addAll(List.of(answers));
        }

        void setReport(String text)
        {
            report = text;
        }

        void failStartWith(String message)
        {
            startFailure.set(message);
        }

        void failPrecheckWith(String message)
        {
            precheckFailure.set(message);
        }

        /** Makes the comparison never finish, so the job stays running for the caller. */
        void keepRunning()
        {
            pollAnswer.set(Progress.running("COMPARISON_PROCESS_INITIALIZATION_FINISHED")); //$NON-NLS-1$
        }

        /** Makes the launch itself take longer than any wait the tool keeps of its own. */
        void blockStart()
        {
            blockStart = true;
        }

        /** Lets the held launch finish and publish its comparison id. */
        void releaseStart()
        {
            startGate.countDown();
        }

        /** Makes release() report that nothing was registered under the id. */
        void refuseRelease()
        {
            releaseOutcome.set(ReleaseOutcome.NOT_REGISTERED);
        }

        /**
         * @param outcome what the next release attempt observes
         */
        void answerReleaseWith(ReleaseOutcome outcome)
        {
            releaseOutcome.set(outcome);
        }

        void setLiveComparisonIds(List<String> ids)
        {
            liveComparisonIds = ids;
        }

        /**
         * Lets a kept-running job end, so the worker thread is not left sleeping. Opens the
         * launch gate too: a test that held one open must not strand its worker.
         */
        void finish()
        {
            startGate.countDown();
            pollAnswer.set(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$
        }

        boolean awaitStarted() throws InterruptedException
        {
            return started.await(10, TimeUnit.SECONDS);
        }

        /** @return {@code true} once the worker is INSIDE the launch, not merely past it */
        boolean awaitStartEntered() throws InterruptedException
        {
            return startEntered.await(10, TimeUnit.SECONDS);
        }

        int starts()
        {
            return starts.get();
        }

        int cancels()
        {
            return cancels.get();
        }

        int releases()
        {
            return releases.get();
        }

        String lastComparisonId()
        {
            return lastComparisonId.get();
        }

        String lastCancelled()
        {
            return lastCancelled.get();
        }

        String lastReleased()
        {
            return lastReleased.get();
        }

        LaunchRequest lastRequest()
        {
            return lastRequest.get();
        }
    }

    // ============ A stop is TWO operations, and the verdict is built from both ============

    /**
     * The defect: {@code EngineBackend.cancel} assigned the session hand-back's answer to nothing
     * and returned {@code STOPPED} regardless. A service that disappeared between the cancel and
     * the hand-back therefore reached {@code cancel_job} as TERMINATED plus "its temporary
     * workspace released" - over a hand-back that had not completed.
     */
    @Test
    public void testAStopWhoseHandBackFailedIsNotClaimedAsAFullStop()
    {
        assertEquals(StopOutcome.STOPPED_NOT_RELEASED,
            CompareConfigurationsTool.stopVerdict(ReleaseOutcome.STOP_FAILED));
    }

    /**
     * The three controls, each in its own assertion because each is a different reason. In
     * particular ALREADY_GONE is the ORDINARY answer after a successful cancel - the cancel is what
     * made EDT forget the handle - so reporting it as a failed stop would turn every successful
     * cancellation into a warning.
     */
    @Test
    public void testAStopWhoseHandBackSucceededOrHadNothingLeftToDoIsAFullStop()
    {
        assertEquals(StopOutcome.STOPPED,
            CompareConfigurationsTool.stopVerdict(ReleaseOutcome.RELEASED));
        assertEquals(StopOutcome.STOPPED,
            CompareConfigurationsTool.stopVerdict(ReleaseOutcome.ALREADY_GONE));
        assertEquals(StopOutcome.STOPPED,
            CompareConfigurationsTool.stopVerdict(ReleaseOutcome.NOT_REGISTERED));
    }

    /**
     * And the consumer half: {@code cancel_job} must not publish the verdict the registry turns
     * into TERMINATED, and must not repeat the sentence a caller stops reading at.
     */
    @Test
    public void testAStopWhoseHandBackFailedIsNotPublishedAsAVerifiedTermination() throws Exception
    {
        backend.keepRunning();
        backend.answerCancelWith(StopOutcome.STOPPED_NOT_RELEASED);
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult result = jobs.cancel(jobId);

        assertEquals(CancellationOutcome.ALREADY_COMMITTED, result.getOutcome());
        assertContains(result.getDetail(), "was stopped, but handing its session back here did " //$NON-NLS-1$
            + "NOT complete"); //$NON-NLS-1$
        assertFalse("the workspace was not confirmed released, so it must not be claimed: " //$NON-NLS-1$
            + result.getDetail(),
            result.getDetail().contains("its temporary workspace released")); //$NON-NLS-1$
    }

    // ============ A session that disappeared is not a cancellation EDT performed ============

    /**
     * The defect: the poll read the handle and the batch through two separate lookups, each of
     * which re-asks EDT, and turned either one coming back empty into
     * {@code Progress.cancelled} - so the job answered "**Cancelled:** ... was stopped before it
     * finished" for a comparison the platform had never reported cancelling. A disappearance has
     * several causes and this job witnessed none of them.
     */
    @Test
    public void testASessionThatDisappearedIsReportedAsItselfNotAsAnEdtCancellation()
        throws Exception
    {
        backend.setPollAnswer(Progress.gone("Its session is no longer registered here.")); //$NON-NLS-1$

        String rendered = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(rendered, "can no longer be read"); //$NON-NLS-1$
        assertContains(rendered, "was ended outside it"); //$NON-NLS-1$
        assertFalse("nobody asked this job to stop, so no cancellation may be claimed:\n" //$NON-NLS-1$
            + rendered, rendered.contains("**Cancelled:**")); //$NON-NLS-1$
        assertFalse(rendered.contains("was stopped before it finished")); //$NON-NLS-1$
    }

    /**
     * The control: EDT's OWN cancelled status is still reported as a cancellation. Without this the
     * test above would be satisfied by a tool that had simply stopped saying "cancelled" anywhere.
     */
    @Test
    public void testAStatusEdtReportsAsCancelledIsStillACancellation()
    {
        backend.setPollAnswer(Progress.cancelled("EDT reported the comparison as cancelled.")); //$NON-NLS-1$

        String rendered = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(rendered, "was stopped before it finished"); //$NON-NLS-1$
        assertContains(rendered, "EDT reported the comparison as cancelled."); //$NON-NLS-1$
    }

    /**
     * The other side of the same rule: when THIS job's own cancellation is what ended the
     * comparison, the disappearance IS reported as a cancellation - the launch has first-hand
     * evidence, which is exactly what it lacked above.
     */
    @Test
    public void testASessionThatDisappearedAfterOurOwnCancellationIsReportedAsCancelled()
        throws Exception
    {
        Launch launch = new Launch();
        launch.requestStop();
        assertTrue(launch.claimPendingStop());
        backend.setPollAnswer(Progress.gone("Its session is no longer registered here.")); //$NON-NLS-1$

        Object rendered = tool.runComparison(launchRequest(), reporter(60_000L), launch);

        assertContains(String.valueOf(rendered), "**Cancelled:**"); //$NON-NLS-1$
        assertContains(String.valueOf(rendered), "was stopped before it finished"); //$NON-NLS-1$
    }

    // ============ A launch EDT has not started yet is not an unreadable one ============

    /**
     * The defect: {@code startComparison} only SCHEDULES the launch, so until Eclipse runs it EDT
     * lists no handle and answers no status - and every one of those ticks was counted against the
     * three-second unreadable budget. A scheduler busy with a build for longer than that got a
     * correctly queued comparison CANCELLED, reported as an error reading its status.
     */
    @Test
    public void testAComparisonEdtHasNotStartedYetIsNotCancelledAsUnreadable() throws Exception
    {
        Progress[] starting = new Progress[CompareConfigurationsTool.MAX_UNREADABLE_TICKS + 2];
        for (int tick = 0; tick < starting.length; tick++)
        {
            starting[tick] = Progress.starting("EDT has accepted the comparison and has not " //$NON-NLS-1$
                + "listed it yet, so it answers no status for it"); //$NON-NLS-1$
        }
        backend.queuePollAnswers(starting);
        backend.setPollAnswer(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$
        backend.setReport("# Comparison: TestConfiguration"); //$NON-NLS-1$

        Object rendered = tool.runComparison(launchRequest(), reporter(120_000L), new Launch());

        assertContains(String.valueOf(rendered), "# Comparison: TestConfiguration"); //$NON-NLS-1$
        assertEquals("a queued comparison must not be cancelled for not having started yet", 0, //$NON-NLS-1$
            backend.cancels());
    }

    /**
     * The control: an UNREADABLE run of the same length still ends the comparison, so the test
     * above is not passed by a loop that stopped counting anything.
     */
    @Test
    public void testAnUnreadableRunOfTheSameLengthStillEndsTheComparison()
    {
        Progress[] unreadable = new Progress[CompareConfigurationsTool.MAX_UNREADABLE_TICKS + 2];
        for (int tick = 0; tick < unreadable.length; tick++)
        {
            unreadable[tick] = Progress.unknown("EDT answered no status for this comparison"); //$NON-NLS-1$
        }
        backend.queuePollAnswers(unreadable);
        backend.setPollAnswer(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$

        try
        {
            tool.runComparison(launchRequest(), reporter(120_000L), new Launch());
            org.junit.Assert.fail("a comparison nobody can read must not be waited out"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertContains(e.getMessage(), "could not be read"); //$NON-NLS-1$
            assertEquals(1, backend.cancels());
        }
    }

    // ============ The duty to stop is never owed by nobody ============

    /**
     * The defect, and the interleaving it needs: the launch looked ONCE, just before its poll loop,
     * found the handler still holding the duty and moved on; microseconds later that handler ran
     * out of time, wrote its flag back and returned "the launch is stopping it". The duty was then
     * owed by nobody, {@code cancel_job} promised a stop nobody performed, and the comparison kept
     * EDT's single slot.
     *
     * <p>Reproduced by placing the hand-over BETWEEN two of the launch's own checks - which is
     * only possible by driving the loop with a {@link Launch} this test holds - and it is why the
     * launch now asks on every tick instead of once.</p>
     */
    @Test
    public void testAStopHandedToTheLaunchAfterItLookedIsStillPerformed()
    {
        Launch launch = new Launch();
        backend.keepRunning();
        // The cancellation arrives DURING the launch, so the launch's pre-start check cannot see
        // it and the comparison really does get started...
        backend.requestStopDuringStart(launch);
        // ...and the hand-over lands during the FIRST poll: after the launch has already looked
        // once and found the duty still the handler's, and before its next look.
        backend.handOverDuringFirstPoll(launch);

        try
        {
            tool.runComparison(launchRequest(), reporter(4_000L), launch);
            org.junit.Assert.fail("the cancellation must end the job"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertContains(e.getMessage(), "ran out of time waiting for the launch"); //$NON-NLS-1$
            assertEquals(1, backend.cancels());
            assertEquals(backend.lastComparisonId(), backend.lastCancelled());
        }
    }

    /** A duty the handler still holds is left alone: racing it would downgrade a verified stop. */
    @Test
    public void testADutyTheHandlerStillHoldsIsNotTakenByTheLaunch()
    {
        Launch launch = new Launch();
        launch.requestStop();

        assertFalse("the handler owns it, so the launch must not", launch.claimHandedOverStop()); //$NON-NLS-1$
        assertTrue("and the handler can still hand it over", launch.handOverStop()); //$NON-NLS-1$
        assertTrue("after which the launch takes it", launch.claimHandedOverStop()); //$NON-NLS-1$
        assertFalse("exactly once", launch.claimHandedOverStop()); //$NON-NLS-1$
        assertFalse("and nobody else may claim it either", launch.claimPendingStop()); //$NON-NLS-1$
    }

    /**
     * A handler whose duty somebody has already TAKEN cannot hand it over, so it promises nothing
     * of its own - the state has no "owed by nobody" to fall into.
     */
    @Test
    public void testADutyAlreadyTakenCannotBeHandedOver()
    {
        Launch launch = new Launch();
        launch.requestStop();
        assertTrue(launch.claimPendingStop());

        assertFalse(launch.handOverStop());
        assertFalse(launch.claimHandedOverStop());
    }

    /** With no cancellation outstanding there is no duty to take, in either form. */
    @Test
    public void testNothingIsClaimableWhileNoCancellationHasArrived()
    {
        Launch launch = new Launch();

        assertFalse(launch.stopWasRequested());
        assertFalse(launch.claimPendingStop());
        assertFalse(launch.claimHandedOverStop());
        assertFalse(launch.handOverStop());
    }
}

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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.Backend;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.ComparisonException;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.Launch;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.LaunchRequest;
import com.ditrix.edt.mcp.server.tools.impl.CompareConfigurationsTool.Progress;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonEngine;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonFailures;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonScopeBuilder;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonTreeReport;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec;
import com.ditrix.edt.mcp.server.utils.compare.PlatformAnswer;
import com.ditrix.edt.mcp.server.utils.compare.SlotClaim;
import com.ditrix.edt.mcp.server.utils.compare.SlotClaims;
import com.ditrix.edt.mcp.server.utils.compare.SlotHandback;
import com.ditrix.edt.mcp.server.utils.compare.SlotHandback.Ending;
import com.ditrix.edt.mcp.server.utils.compare.SlotHandbacks;
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

    /**
     * The one sentence the starting-budget outcome may print about a cancellation - and only where
     * the hand-back's owner WITHHELD the hand-back, which is the only state in which anything
     * observed that the comparison was not cancelled.
     */
    private static final String NOT_CANCELLED_CLAIM =
        "The comparison was NOT cancelled and this is NOT its result."; //$NON-NLS-1$

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
        assertEquals(1, backend.handBacks());
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
        assertEquals(0, backend.handBacks());
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
        // The slot goes back: the single exit hands it back and says what that achieved.
        assertEquals(1, backend.handBacks());
    }

    @Test
    public void testCancellingTheJobStopsTheComparison() throws Exception
    {
        backend.keepRunning();
        String result = tool.execute(request(Map.of("waitSeconds", "0"))); //$NON-NLS-1$ //$NON-NLS-2$
        String jobId = jobId(result);
        assertTrue(backend.awaitStarted());

        CancellationResult cancellation = jobs.cancel(jobId);

        assertEquals(1, backend.handBacks());
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
        backend.answerHandBackWith(SlotHandback.Verdict.ALREADY_FREE);
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult cancellation = jobs.cancel(jobId);

        // ALREADY_FREE is a free slot, so the registry's TERMINATED is correct here - what the
        // detail may not do is claim a stop that did not happen.
        assertEquals(CancellationOutcome.TERMINATED, cancellation.getOutcome());
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
        backend.answerHandBackWith(SlotHandback.Verdict.UNREACHABLE);
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult cancellation = jobs.cancel(jobId);

        assertEquals(CancellationOutcome.ALREADY_COMMITTED, cancellation.getOutcome());
        assertContains(cancellation.getDetail(), "could not be asked"); //$NON-NLS-1$
        assertContains(cancellation.getDetail(), "was NOT ended"); //$NON-NLS-1$
        assertContains(cancellation.getDetail(), "single comparison slot"); //$NON-NLS-1$
    }

    @Test
    public void testAJobEndedByTheToolSaysWhetherTheComparisonWasActuallyStopped()
    {
        // Its own test, and its own literal. Every ending that gives the slot back - this one,
        // the expired job budget, a comparison EDT never started, a cancellation that arrives
        // during a slow launch - now words its slot sentence from the SAME hand-back value, so
        // pinning one of them pins the wording they all share. (The budget branch cannot be
        // reached from a unit test: its bound is the job's two hours.) They used to claim the stop
        // unconditionally, after a call that can fail to reach EDT at all.
        backend.answerHandBackWith(SlotHandback.Verdict.UNREACHABLE);
        backend.setPollAnswer(Progress.unknown("EDT answered no status for this comparison")); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "20"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertContains(result, "was NOT ended"); //$NON-NLS-1$
        assertFalse("a stop that never reached EDT must not be reported as done:\n" + result, //$NON-NLS-1$
            result.contains("temporary workspace released")); //$NON-NLS-1$
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
        // ABSOLUTE on purpose: a relative path is refused one check earlier, for being relative,
        // so a relative spelling here would pin the wrong refusal and leave this branch uncovered.
        String missing = Paths.get("no-such-directory-cc-test", "rules.xml") //$NON-NLS-1$ //$NON-NLS-2$
            .toAbsolutePath().toString();

        String result = tool.execute(request(Map.of("mergeRulesFile", missing))); //$NON-NLS-1$

        assertContains(result, "mergeRulesFile"); //$NON-NLS-1$
        assertContains(result, "no-such-directory-cc-test"); //$NON-NLS-1$
        assertContains(result, "does not exist or cannot be read"); //$NON-NLS-1$
        // Refused BEFORE the launch on purpose: EDT runs one comparison at a time, so a typo
        // that took the slot and then failed would block the next honest attempt too.
        assertEquals(0, backend.starts());
    }

    // ============ mergeRulesFile is ABSOLUTE, like the two path parameters of merge_rules ============
    //
    // Paths.get(value) never fails on a relative path and Files.isReadable answers for whatever it
    // happens to name under the working directory of the EDT PROCESS - the install directory, or
    // wherever a launcher started it. So a relative path that IS readable there passed the check
    // and was handed on in the caller's own spelling; the MCP client that wrote it means its OWN
    // directory, so the comparison silently applies a different file's decisions.

    /**
     * The readable case is the one that mattered: an unreadable relative path was already refused
     * (for the wrong reason), while a readable one was ACCEPTED and started a comparison against
     * rules nobody named. The file is created in the process's working directory precisely so
     * that the relative spelling resolves to something real.
     */
    @Test
    public void testARelativeMergeRulesFileIsRefusedEvenWhenItNamesAReadableFile() throws Exception
    {
        Path workingDirectory = Paths.get("").toAbsolutePath(); //$NON-NLS-1$
        Path readable = Files.createTempFile(workingDirectory, "cc-relative-rules", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String relative = workingDirectory.relativize(readable).toString();
            assertFalse("the fixture must be a RELATIVE spelling of a readable file", //$NON-NLS-1$
                Paths.get(relative).isAbsolute());
            assertTrue("and it must really be readable from the working directory", //$NON-NLS-1$
                Files.isReadable(Paths.get(relative)));

            String result = tool.execute(request(Map.of("mergeRulesFile", relative))); //$NON-NLS-1$

            assertContains(result, "mergeRulesFile"); //$NON-NLS-1$
            assertContains(result, "ABSOLUTE"); //$NON-NLS-1$
            assertContains(result, relative);
            assertEquals(0, backend.starts());
        }
        finally
        {
            Files.deleteIfExists(readable);
        }
    }

    /**
     * The refusal has to say WHY a relative path is not merely inconvenient, or the caller reads
     * it as a style rule and passes one again.
     */
    @Test
    public void testTheRelativeMergeRulesRefusalNamesWhatItWouldHaveResolvedAgainst()
    {
        String result = tool.execute(request(Map.of("mergeRulesFile", "rules.xml"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "working directory of the EDT process"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    /**
     * The same refusal text as {@code merge_rules} gives its own path parameters, because it is
     * the same rule: a caller taught it once must not meet a second wording for it.
     */
    @Test
    public void testTheRelativeRefusalIsTheOneMergeRulesGivesForItsOwnPaths()
    {
        String fromCompare = tool.execute(request(Map.of("mergeRulesFile", "rules.xml"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(ComparisonFailures
            .relativePath("mergeRulesFile", "rules.xml", Paths.get("rules.xml")).toJson(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fromCompare);
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

    // ============ readable is not the same as usable ============
    //
    // Files.isReadable answers one question - may this process open it - and two things that are
    // not merge-rules files answer it with "yes": a directory, and a file with any extension at
    // all. Both used to pass and be handed to the platform, whose own deserializeMergeSettings
    // reads no other name - '.xml' or '.zip' on EDT 2026.1, '.zip' alone on 2026.2. The
    // comparison then failed deep inside the launch, holding EDT's single comparison slot, over a
    // file the caller had been told was checked.
    //
    // The accepted set stays the UNION of the two versions. Narrowing it to '.zip' would refuse,
    // on every EDT, a file half of them read perfectly well, and an '.xml' handed to a 2026.2
    // launch fails there with the platform's own assertion, which names the file.

    @Test
    public void testAReadableDirectoryIsNotAMergeRulesFile() throws Exception
    {
        Path directory = Files.createTempDirectory("compare-rules-dir"); //$NON-NLS-1$
        try
        {
            assertTrue("the fixture must really be readable", Files.isReadable(directory)); //$NON-NLS-1$

            String result = tool.execute(request(Map.of("mergeRulesFile", //$NON-NLS-1$
                directory.toString())));

            assertContains(result, "mergeRulesFile"); //$NON-NLS-1$
            assertContains(result, "is not a file"); //$NON-NLS-1$
            assertEquals(0, backend.starts());
        }
        finally
        {
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testAMergeRulesFileWithAnotherExtensionIsRefusedBeforeAnythingIsStarted()
        throws Exception
    {
        Path notRules = Files.createTempFile("compare-rules", ".txt"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String result = tool.execute(request(Map.of("mergeRulesFile", //$NON-NLS-1$
                notRules.toString())));

            assertContains(result, "mergeRulesFile"); //$NON-NLS-1$
            assertContains(result, ".xml"); //$NON-NLS-1$
            assertContains(result, ".zip"); //$NON-NLS-1$
            // Refused BEFORE the launch, like every other check here: a file EDT's reader will
            // not open must not cost the single comparison slot to find out.
            assertEquals(0, backend.starts());
        }
        finally
        {
            Files.deleteIfExists(notRules);
        }
    }

    @Test
    public void testAZipMergeRulesFileStillReachesTheBackend() throws Exception
    {
        // The control: the accepted set is the platform reader's, which is BOTH containers. A
        // check narrowed to '.xml' would refuse the very file the comparison editor saves.
        Path rules = Files.createTempFile("compare-rules", ".zip"); //$NON-NLS-1$ //$NON-NLS-2$
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
    public void testTheExtensionRefusalUsesTheCodecsOwnRule() throws Exception
    {
        // The rule belongs to the platform's reader and lives in one place. This pins that the
        // tool's verdict and the codec's predicate cannot drift apart: whatever the codec calls
        // readable is what the tool lets through.
        Path notRules = Files.createTempFile("compare-rules", ".rules"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            assertFalse("the fixture must be one the codec refuses", //$NON-NLS-1$
                MergeRulesCodec.hasReadableExtension(notRules));

            String result = tool.execute(request(Map.of("mergeRulesFile", //$NON-NLS-1$
                notRules.toString())));

            assertContains(result, "must end in"); //$NON-NLS-1$
            assertEquals(0, backend.starts());
        }
        finally
        {
            Files.deleteIfExists(notRules);
        }
    }

    @Test
    public void testTheExtensionRefusalNamesWhichEdtReadsWhich() throws Exception
    {
        // The claim this message used to make - "EDT's own merge-settings reader accepts those
        // two names" - stopped being true when 2026.2 dropped the xml branch. A caller who reads
        // it and renames a file to '.xml' has been sent to a container their EDT will refuse.
        Path notRules = Files.createTempFile("compare-rules", ".rules"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String result = tool.execute(request(Map.of("mergeRulesFile", //$NON-NLS-1$
                notRules.toString())));

            assertContains(result, "EDT 2026.2 reads a zip alone"); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(notRules);
        }
    }

    // ============ mergeObjectsContent is a scope filter, not a "compare more" switch ============
    //
    // Measured from com._1c.g5.v8.dt.md.compare (16.0.0 on EDT 2026.1.2 and 16.0.1 on 2026.2.0,
    // identical here): MdCompareUtils.isExcludeObjectsContentFeature EXCLUDES a feature from the
    // comparison when the setting is on and neither compared object's qualified name is under an
    // entry of the scope. An empty scope has no entries, so with the setting on a
    // whole-configuration run drops every plain feature of every object - module text, forms,
    // templates - and reports each top object as identical. The setting therefore follows the
    // scope instead of being pinned on.

    /**
     * The guide is the caller-facing half of the same claim the report prints, and it drifted the
     * same way: it said a node outside the scope "was never compared feature by feature". The
     * predicate spares a containment-many collection of {@code MdObject}s and is applied per
     * feature, so such a node WAS compared - on everything it left in.
     */
    @Test
    public void testTheGuideDoesNotSayAScopedNodeWasNeverComparedFeatureByFeature()
    {
        assertFalse("the guide may not deny a comparison that did take place", //$NON-NLS-1$
            tool.getGuide().contains("never compared feature by feature")); //$NON-NLS-1$
    }

    @Test
    public void testTheGuideNamesTheCarveOutTheExclusionIsBoundedBy()
    {
        // The positive half: without it the pin above would pass on a guide that had simply
        // dropped the paragraph, which would take the caveat away instead of narrowing it.
        assertTrue("the guide has to say WHICH features can be excluded", //$NON-NLS-1$
            tool.getGuide().contains("spares an object's containment-many collections of")); //$NON-NLS-1$
    }

    @Test
    public void testAWholeConfigurationComparisonDoesNotSetMergeObjectsContent()
    {
        ComparisonProcessSettings settings =
            CompareConfigurationsTool.EngineBackend.settingsFor(wholeConfigurationScope());

        assertFalse("with an empty scope this setting excludes every object's own features, so " //$NON-NLS-1$
            + "the comparison would report identical for objects it never looked inside", //$NON-NLS-1$
            settings.isMergeObjectsContent());
    }

    @Test
    public void testAScopedComparisonSetsMergeObjectsContent()
    {
        ComparisonScope scoped =
            ComparisonScopeBuilder.build(List.of("Catalog.Products")).scope(); //$NON-NLS-1$

        assertTrue("a scoped run is what the setting was written for: the tree still spans the " //$NON-NLS-1$
            + "whole configuration, and only the named objects are compared with their content", //$NON-NLS-1$
            CompareConfigurationsTool.EngineBackend.settingsFor(scoped).isMergeObjectsContent());
    }

    @Test
    public void testTheScopeMovesThatOneSettingAndNothingElse()
    {
        // Everything else in the settings is fixed, and a change that moved one of them with the
        // scope would be invisible to the two assertions above.
        for (ComparisonScope scope : List.of(wholeConfigurationScope(),
            ComparisonScopeBuilder.build(List.of("Catalog.Products")).scope())) //$NON-NLS-1$
        {
            ComparisonProcessSettings settings =
                CompareConfigurationsTool.EngineBackend.settingsFor(scope);

            assertEquals(MatchingStrategy.UUID_THEN_NAME, settings.getMatchingStrategy());
            assertTrue("BSL module structure is parsed either way", //$NON-NLS-1$
                settings.isParseBslModuleStructure());
            assertTrue("nobody is at the keyboard to answer an external merge tool's window", //$NON-NLS-1$
                settings.isAvoidExternalMergeToolSupport());
        }
    }

    /**
     * The scope object a whole-configuration launch hands the handle - empty on every side, and a
     * FRESH instance rather than the shared mutable {@code ComparisonScope.EMPTY_SCOPE}.
     *
     * @return the scope
     */
    private static ComparisonScope wholeConfigurationScope()
    {
        return new ComparisonScope(Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList());
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

    /**
     * A scope whose elements are all non-primitive parses to an EMPTY list, and an empty scope is
     * the platform's spelling of "compare the whole configuration" - so the broken request used to
     * be answered with the heaviest run the tool can start, holding EDT's single slot for minutes.
     * Each fact is pinned in its own method: JUnit stops a method at its first failed assertion,
     * so bundling them would only ever load-bear on the first.
     */
    @Test
    public void testAScopeOfOnlyNonStringElementsStartsNothing()
    {
        String result = tool.execute(request(Map.of("scope", "[null]", //$NON-NLS-1$ //$NON-NLS-2$
            "waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        errorMessage(result);
        assertEquals(0, backend.starts());
        assertNull(backend.lastRequest());
    }

    @Test
    public void testAScopeOfOnlyNonStringElementsNamesTheOffendingPosition()
    {
        String error = errorMessage(tool.execute(request(Map.of("scope", "[null]", //$NON-NLS-1$ //$NON-NLS-2$
            "waitSeconds", "10")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(error, "Scope entry #1"); //$NON-NLS-1$
    }

    @Test
    public void testAScopeRefusalSaysWhatTheDroppedEntryWouldHaveMeant()
    {
        String error = errorMessage(tool.execute(request(Map.of("scope", "[null]", //$NON-NLS-1$ //$NON-NLS-2$
            "waitSeconds", "10")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(error, "WHOLE CONFIGURATION"); //$NON-NLS-1$
    }

    @Test
    public void testAScopeRefusalNamesTheKindThatWasFoundWithoutEchoingIt()
    {
        // The kind, never the element: echoing caller text back into a Markdown answer is a defect
        // family this tree has already paid for once.
        String error = errorMessage(tool.execute(request(Map.of("scope", "[{\"fqn\":\"x\"}]", //$NON-NLS-1$ //$NON-NLS-2$
            "waitSeconds", "10")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(error, "is an object"); //$NON-NLS-1$
        assertFalse("the offending element must not be quoted back: " + error, //$NON-NLS-1$
            error.contains("fqn")); //$NON-NLS-1$
    }

    @Test
    public void testABrokenEntryAfterAGoodOneIsRefusedAtItsOwnPosition()
    {
        // The dangerous case is not only the all-broken array: one dropped element silently
        // NARROWS the comparison instead of widening it, which is just as wrong a report.
        String error = errorMessage(tool.execute(request(Map.of("scope", //$NON-NLS-1$
            "[\"Catalog.Products\", 42]", "waitSeconds", "10")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertContains(error, "Scope entry #2"); //$NON-NLS-1$
        assertContains(error, "is a number"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testACommaSeparatedScopeOfNothingButSeparatorsStartsNothing()
    {
        // The same silent drop in the other parse branch: extractArrayArgument keeps no empty
        // part, so ",," arrives as no scope at all and would have compared everything.
        String error = errorMessage(tool.execute(request(Map.of("scope", ",,", //$NON-NLS-1$ //$NON-NLS-2$
            "waitSeconds", "10")))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(error, "names no object"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    @Test
    public void testAnExplicitlyEmptyScopeArrayIsStillAWholeConfigurationRequest()
    {
        // The negative control that keeps the check from overreaching: '[]' dropped NOTHING, so it
        // is read as the omitted scope it looks like rather than refused.
        tool.execute(request(Map.of("scope", "[]", "waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        LaunchRequest seen = backend.lastRequest();
        assertNotNull(seen);
        assertTrue(seen.getScope().isEmpty());
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
        // expand of the report that was just handed to the caller fail. It is also the ONE
        // ending that keeps the comparison open, so this is the pin that stops the single exit
        // from handing the slot back on all of them.
        assertEquals(0, backend.handBacks());
    }

    @Test
    public void testReleaseComparisonIdClosesTheComparisonAndStartsNothing()
    {
        String result = tool.execute(Map.of("releaseComparisonId", "cmp-4")); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "**Released:**"); //$NON-NLS-1$
        assertContains(result, "cmp-4"); //$NON-NLS-1$
        assertEquals(1, backend.handBacks());
        assertEquals("cmp-4", backend.lastHandedBack()); //$NON-NLS-1$
        // The caller has finished reading it; nobody cancelled anything, and EDT's own record of
        // the hand-back says so.
        assertEquals(Ending.CLOSED, backend.lastEnding());
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
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_FREED);

        String result = tool.execute(Map.of("releaseComparisonId", "cmp-4")); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "cmp-4"); //$NON-NLS-1$
        assertContains(result, "**Not released:**"); //$NON-NLS-1$
        assertFalse("a release that stopped nothing must not report a free slot:\n" + result, //$NON-NLS-1$
            result.contains("slot is free again")); //$NON-NLS-1$
        assertContains(result, "do NOT assume"); //$NON-NLS-1$
        // The record is KEPT now, and the caller has to be told so - it is what makes retrying
        // possible at all, and the previous wording sent them looking in the workbench instead.
        assertContains(result, "still registered"); //$NON-NLS-1$
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

    // ======== The report names the COMMIT, not only the expression that named it ========

    /** A full commit id, the shape {@code GitRevisionResolver} hands back. */
    private static final String OTHER_COMMIT = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"; //$NON-NLS-1$

    /** A DIFFERENT one, so a report that used one side's id for both could not pass. */
    private static final String ANCESTOR_COMMIT = "9876543210fedcba9876543210fedcba98765432"; //$NON-NLS-1$

    @Test
    public void testAMovingRevisionIsLabelledWithTheCommitItResolvedTo()
    {
        // 'vendor/2.5.14' names a different commit next week, and the comparison did not run
        // against the tag - it ran against the commit the tag resolved to at launch, which is
        // what the git data source descriptors were handed. A report that echoed the expression
        // alone can be neither reproduced nor checked against the state it was taken from.
        LaunchRequest request = movingRequest();
        request.recordResolvedRevisions(OTHER_COMMIT, ANCESTOR_COMMIT);

        assertEquals("vendor/2.5.14 (" + OTHER_COMMIT + ")", request.otherRevisionLabel()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("v1.0 (" + ANCESTOR_COMMIT + ")", request.ancestorRevisionLabel()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEachSideKeepsItsOwnCommit()
    {
        // Two distinct ids on purpose: a wiring that carried one side's id into both labels would
        // render a perfectly plausible report of a comparison that never happened.
        LaunchRequest request = movingRequest();
        request.recordResolvedRevisions(OTHER_COMMIT, ANCESTOR_COMMIT);

        assertFalse("the other side must not carry the ancestor's commit", //$NON-NLS-1$
            request.otherRevisionLabel().contains(ANCESTOR_COMMIT));
        assertFalse("nor the ancestor the other side's", //$NON-NLS-1$
            request.ancestorRevisionLabel().contains(OTHER_COMMIT));
    }

    @Test
    public void testARevisionNothingResolvedIsReportedExactlyAsAsked()
    {
        // Nothing was recorded, so nothing may be invented. The label degrades to what the caller
        // actually wrote rather than to an id nobody observed.
        assertEquals("vendor/2.5.14", movingRequest().otherRevisionLabel()); //$NON-NLS-1$
        assertEquals("v1.0", movingRequest().ancestorRevisionLabel()); //$NON-NLS-1$
    }

    @Test
    public void testACallerWhoNamedTheCommitItselfIsNotToldItTwice()
    {
        // A fixed revision resolves to itself, and 'a1b2… (a1b2…)' states nothing the first
        // printing did not. The upper-cased side proves the comparison is on the id and not on
        // its spelling, and that the canonical form is the one printed.
        LaunchRequest request = new LaunchRequest("TestConfiguration", OTHER_COMMIT, //$NON-NLS-1$
            "9876543210FEDCBA9876543210FEDCBA98765432", null, null, 100, false); //$NON-NLS-1$
        request.recordResolvedRevisions(OTHER_COMMIT, ANCESTOR_COMMIT);

        assertEquals(OTHER_COMMIT, request.otherRevisionLabel());
        assertEquals("the resolved id is the canonical spelling of what was asked for", //$NON-NLS-1$
            ANCESTOR_COMMIT, request.ancestorRevisionLabel());
    }

    @Test
    public void testTheRenderedReportCarriesTheResolvedCommits()
    {
        // Out of the request and into the text a caller reads, through the one place a header is
        // built. A label that existed on the request but never reached the report would fix
        // nothing.
        LaunchRequest request = movingRequest();
        request.recordResolvedRevisions(OTHER_COMMIT, ANCESTOR_COMMIT);

        String report = ComparisonTreeReport.render(
            CompareConfigurationsTool.headerFor("cmp-1", request, "finished", true), //$NON-NLS-1$ //$NON-NLS-2$
            new ComparisonScope(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList()),
            new ComparisonTreeReport.Collector(100, false));

        assertContains(report, "vendor/2.5.14 (" + OTHER_COMMIT + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(report, "v1.0 (" + ANCESTOR_COMMIT + ")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** @return a request whose two revisions are both MOVING expressions */
    private static LaunchRequest movingRequest()
    {
        return new LaunchRequest("TestConfiguration", "vendor/2.5.14", "v1.0", null, null, 100, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            false);
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

        // Nothing is registered with no facade installed, so the honest answer is that the id
        // names nothing here - and NOT that a slot was freed.
        SlotHandback handback =
            new CompareConfigurationsTool.EngineBackend().handBack("cmp-1", Ending.CANCELLED); //$NON-NLS-1$

        assertEquals(SlotHandback.Verdict.NOT_REGISTERED, handback.verdict());
        assertFalse("nothing was stopped, so no slot may be claimed free", handback.slotIsFree()); //$NON-NLS-1$
    }

    /** And a launch it could not perform is a refusal, in the shared wording. */
    @Test
    public void testTheProductionBackendRefusesToLaunchWithoutTheService()
    {
        ComparisonEngine.uninstall();

        try
        {
            new CompareConfigurationsTool.EngineBackend().start(
                new LaunchRequest("TestConfiguration", "HEAD", "HEAD~1", null, null, 100, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    true),
                SlotClaims.granted("cmp-x-1")); //$NON-NLS-1$
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
        // the slot is taken under no id of ours. An empty id, not null - collapsing it into null
        // would report an occupied workbench as an idle one and the launch would then fail on the
        // platform's assertion instead of a sentence.
        assertEquals("", CompareConfigurationsTool.resolveActiveComparisonId(null, true)); //$NON-NLS-1$

        backend.setActiveComparisonId(""); //$NON-NLS-1$
        String error = errorMessage(tool.execute(request(Map.of())));

        assertContains(error, "refused rather than queued"); //$NON-NLS-1$
        assertEquals(0, backend.starts());
    }

    /**
     * The nameless refusal states an OBSERVATION and not a cause.
     * <p>
     * It used to say the comparison had been started from EDT's own interface and to send the
     * caller at its comparison editor. That is one of two states. The other is EDT holding the
     * flag for a comparison whose background job was cancelled before it began - measured, see
     * {@code ComparisonFailures.alreadyRunning} - and in that one there is no comparison, no
     * editor, and nothing this bundle can call to withdraw the flag. A caller who followed the old
     * advice went looking for a window that does not exist and then retried into the same wall.
     */
    @Test
    public void testTheNamelessRefusalDoesNotAssertACauseItDidNotObserve()
    {
        backend.setActiveComparisonId(""); //$NON-NLS-1$

        String error = errorMessage(tool.execute(request(Map.of())));

        assertContains(error, "no comparison started through this server is registered"); //$NON-NLS-1$
        assertContains(error, "two causes"); //$NON-NLS-1$
    }

    /** Both ways out are named, because neither one covers the other state. */
    @Test
    public void testTheNamelessRefusalNamesTheWayOutOfBothStates()
    {
        backend.setActiveComparisonId(""); //$NON-NLS-1$

        String error = errorMessage(tool.execute(request(Map.of())));

        assertContains(error, "closing its comparison editor"); //$NON-NLS-1$
        assertContains(error, "restarting EDT"); //$NON-NLS-1$
    }

    /**
     * And it says why the second way out is the only one there: a caller told merely to restart
     * EDT reads it as a workaround and looks for a better one. There is none - the flag is not
     * reachable through the comparison manager's public surface.
     */
    @Test
    public void testTheNamelessRefusalSaysTheFlagCannotBeWithdrawnFromHere()
    {
        backend.setActiveComparisonId(""); //$NON-NLS-1$

        String error = errorMessage(tool.execute(request(Map.of())));

        assertContains(error, "no way to withdraw the flag"); //$NON-NLS-1$
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
        assertEquals(1, backend.handBacks());
        assertEquals(backend.lastComparisonId(), backend.lastHandedBack());
        assertEquals(CancellationOutcome.TERMINATED, cancellation.get().getOutcome());
    }

    // ============ Every ending goes through ONE exit ============

    /**
     * The finding, and the one a re-check on every tick cannot fix: the terminal branch read the
     * report and returned WITHOUT taking an outstanding cancellation. A hand-over that landed while
     * the comparison was finishing was then owed by nobody - and the caller had already been told
     * "the request stands and the launch takes it at its next check", of which there was none.
     * <p>
     * The hand-over lands on the very tick that answers FINISHED: after the loop's look, before its
     * ending is turned into an answer. That placement is the whole test, and no real thread
     * schedule can be made to hit it on purpose.
     */
    @Test
    public void testACancellationHandedOverOnTheFinishingTickIsStillHonoured()
    {
        Launch launch = new Launch();
        backend.requestStopDuringStart(launch);
        backend.handOverDuringFirstPoll(launch);
        backend.setPollAnswer(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$
        backend.setReport("# Comparison: TestConfiguration"); //$NON-NLS-1$

        try
        {
            tool.runComparison(launchRequest(), reporter(60_000L), launch);
            org.junit.Assert.fail("an outstanding cancellation must not be dropped"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertContains(e.getMessage(), "was cancelled"); //$NON-NLS-1$
            assertEquals("the comparison must actually be ended, not merely mentioned", 1, //$NON-NLS-1$
                backend.handBacks());
            assertEquals(Ending.CANCELLED, backend.lastEnding());
        }
    }

    /**
     * The control, and the reason the test above is not satisfied by a tool that stopped returning
     * reports: with NO cancellation outstanding the same finished comparison hands nothing back and
     * the report is returned. This is the one ending that keeps EDT's slot.
     */
    @Test
    public void testAFinishedComparisonNobodyCancelledKeepsItsSlotAndReturnsTheReport()
        throws Exception
    {
        backend.setPollAnswer(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$
        backend.setReport("# Comparison: TestConfiguration"); //$NON-NLS-1$

        Object rendered = tool.runComparison(launchRequest(), reporter(60_000L), new Launch());

        assertContains(String.valueOf(rendered), "# Comparison: TestConfiguration"); //$NON-NLS-1$
        assertEquals(0, backend.handBacks());
    }

    /**
     * The finding: the cancelled branch called the hand-back and threw its answer away, so a
     * comparison EDT had reported as cancelled was published as "**Cancelled:** ..." whether or not
     * the slot had actually come back. Nothing in that sentence could tell the caller to look.
     * <p>
     * It fails on the previous code by construction: there the hand-back's answer reached no
     * expression at all, so no verdict could change the text.
     */
    @Test
    public void testAPlatformCancellationWhoseHandBackFailedDoesNotClaimTheSlotIsFree()
        throws Exception
    {
        backend.setPollAnswer(Progress.cancelled("EDT reported the comparison as cancelled.")); //$NON-NLS-1$
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_FREED);

        String rendered = String.valueOf(
            tool.runComparison(launchRequest(), reporter(60_000L), new Launch()));

        assertContains(rendered, "**Cancelled:**"); //$NON-NLS-1$
        assertContains(rendered, "did NOT complete"); //$NON-NLS-1$
        assertContains(rendered, "do NOT assume"); //$NON-NLS-1$
        assertFalse("the slot was not confirmed free, so it must not be claimed free:\n" //$NON-NLS-1$
            + rendered, rendered.contains("slot is free again")); //$NON-NLS-1$
    }

    /** The control: the same ending with a hand-back that worked says the slot IS free. */
    @Test
    public void testAPlatformCancellationWhoseHandBackWorkedSaysTheSlotIsFree() throws Exception
    {
        backend.setPollAnswer(Progress.cancelled("EDT reported the comparison as cancelled.")); //$NON-NLS-1$

        String rendered = String.valueOf(
            tool.runComparison(launchRequest(), reporter(60_000L), new Launch()));

        assertContains(rendered, "**Cancelled:**"); //$NON-NLS-1$
        assertContains(rendered, "slot is free again"); //$NON-NLS-1$
    }

    /**
     * The same rule on the failed ending: a comparison that died still holds EDT's slot until the
     * hand-back succeeds, and a failed hand-back may not be silent there either.
     */
    @Test
    public void testAFailedComparisonWhoseHandBackFailedSaysTheSlotMayStillBeHeld()
    {
        backend.setPollAnswer(Progress.failed("Cannot open repository")); //$NON-NLS-1$
        backend.answerHandBackWith(SlotHandback.Verdict.UNREACHABLE);

        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertContains(result, "Cannot open repository"); //$NON-NLS-1$
        assertContains(result, "was NOT ended"); //$NON-NLS-1$
        assertContains(result, "may still hold"); //$NON-NLS-1$
    }

    /**
     * The ending decides which verb EDT records, and it is a property of the ending rather than an
     * argument the site picks. A comparison that ended by ITSELF is closed, not cancelled.
     */
    @Test
    public void testAComparisonThatEndedByItselfIsClosedRatherThanCancelled()
    {
        backend.setPollAnswer(Progress.failed("Cannot open repository")); //$NON-NLS-1$

        tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Ending.CLOSED, backend.lastEnding());
    }

    /** ...while a comparison THIS job ends early is recorded as a cancellation. */
    @Test
    public void testAComparisonThisJobEndsEarlyIsRecordedAsACancellation()
    {
        backend.setPollAnswer(Progress.unknown("EDT answered no status for this comparison")); //$NON-NLS-1$

        tool.execute(request(Map.of("waitSeconds", "20"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Ending.CANCELLED, backend.lastEnding());
    }

    // ============ "Could not read it" is not "somebody ended it" ============

    /**
     * The finding: the tree read resolved its view with {@code orElse(null)}, so a service that
     * could not be asked produced the refusal for a comparison EDT had ENDED - telling the caller
     * their comparison was destroyed when it was merely unreadable for a moment.
     */
    @Test
    public void testAViewTheServiceCouldNotBeAskedForIsNotReportedAsAnEndedComparison()
    {
        String message = CompareConfigurationsTool.unreadableTreeMessage(
            PlatformAnswer.unavailable(), "cmp-7"); //$NON-NLS-1$

        assertNotNull(message);
        assertContains(message, "could not be asked"); //$NON-NLS-1$
        assertContains(message, "still registered"); //$NON-NLS-1$
        assertFalse("nothing was established about the comparison: " + message, //$NON-NLS-1$
            message.contains("ended outside this server")); //$NON-NLS-1$
    }

    /**
     * The control: EDT ANSWERING that it no longer knows the handle is still reported as a
     * comparison ended elsewhere. Without this the test above would pass on a tool that had simply
     * stopped saying it.
     */
    @Test
    public void testAViewEdtAnsweredNothingForIsStillAnEndedComparison()
    {
        String message = CompareConfigurationsTool.unreadableTreeMessage(
            PlatformAnswer.of(null), "cmp-7"); //$NON-NLS-1$

        assertNotNull(message);
        assertContains(message, "ended outside this server"); //$NON-NLS-1$
    }

    /** And an answer that carries a view is not a refusal at all. */
    @Test
    public void testAReadableViewIsNotARefusal()
    {
        assertNull(CompareConfigurationsTool.unreadableTreeMessage(
            PlatformAnswer.of("a readable tree"), "cmp-7")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ A service gap is not a failed comparison ============

    /**
     * The finding: the production poll asked {@code ComparisonEngine.get()}, which also reports
     * "unavailable" while EDT's comparison service is momentarily unregistered, and answered FAILED
     * before a single question had been asked. The failed ending then ended a HEALTHY comparison -
     * and, because the hand-back could not reach the platform either, left it running with its
     * record dropped and no id able to address it.
     * <p>
     * Here the facade is installed and the service is absent, which is exactly that gap. The poll
     * must produce a reading the loop can absorb - one tick of a budget - and not a verdict.
     */
    @Test
    public void testAMomentaryServiceGapIsNotReportedAsAFailedComparison()
    {
        ComparisonEngine.install(() -> null);
        try
        {
            String comparisonId = ComparisonSessionRegistry.shared().register(
                new ComparisonProcessHandle(new NamedDataSource("Demo"), //$NON-NLS-1$
                    new NamedDataSource("Other"), ComparisonScope.EMPTY_SCOPE), //$NON-NLS-1$
                new CompareMergeProcessBatch(List.of()));

            Progress reading = new CompareConfigurationsTool.EngineBackend().poll(comparisonId);

            assertFalse("a gap in the service says nothing about the comparison", //$NON-NLS-1$
                reading.isFailed());
            assertFalse("and it is not evidence the session has gone either", reading.isGone()); //$NON-NLS-1$
            assertTrue("it is a reading the loop absorbs, on one of its budgets", //$NON-NLS-1$
                reading.isStarting() || reading.isUnknown());
        }
        finally
        {
            ComparisonEngine.uninstall();
        }
    }

    // ==================== A launch that never reached EDT ====================

    /**
     * The reservation is made BEFORE the batch is handed to EDT, so a hand-over that fails has to
     * decide what becomes of it - and {@code ServiceUnavailableException} is the one failure that
     * SETTLES the question: the facade throws it precisely because nothing reached the platform.
     * <p>
     * The reservation used to go through the ordinary hand-back, which cannot settle anything -
     * with the service gone it could not ask EDT either, so it answered UNREACHABLE and
     * deliberately KEPT the record. That record then named EDT's single comparison slot as taken by
     * a comparison that had never started, and every later launch was refused by it until the idle
     * TTL expired.
     */
    @Test
    public void testALaunchThatNeverReachedEdtLeavesNoReservationBehind()
    {
        AtomicReference<IComparisonManager> service =
            new AtomicReference<>(mock(IComparisonManager.class));
        ComparisonEngine.install(service::get);
        try
        {
            ComparisonEngine engine = ComparisonEngine.attached().orElseThrow();
            // The service disappears between the availability check a launch makes and the
            // hand-over itself - the gap this branch exists for.
            service.set(null);

            try
            {
                CompareConfigurationsTool.registerAndHandOver(engine,
                    engine.sessions().claimSlot("Demo"), comparisonHandle(), //$NON-NLS-1$
                    new CompareMergeProcessBatch(List.of()));
                fail("a launch that reached nothing must not report success"); //$NON-NLS-1$
            }
            catch (ComparisonException expected)
            {
                assertNotNull(expected.getMessage());
            }

            assertEquals("nothing started, so nothing may still be registered as holding the slot", //$NON-NLS-1$
                0, engine.sessions().size());
        }
        finally
        {
            ComparisonEngine.uninstall();
        }
    }

    /** The same failure must SAY that the reservation is gone, not merely drop it silently. */
    @Test
    public void testALaunchThatNeverReachedEdtSaysTheReservationIsWithdrawn()
    {
        AtomicReference<IComparisonManager> service =
            new AtomicReference<>(mock(IComparisonManager.class));
        ComparisonEngine.install(service::get);
        try
        {
            ComparisonEngine engine = ComparisonEngine.attached().orElseThrow();
            service.set(null);

            try
            {
                CompareConfigurationsTool.registerAndHandOver(engine,
                    engine.sessions().claimSlot("Demo"), comparisonHandle(), //$NON-NLS-1$
                    new CompareMergeProcessBatch(List.of()));
                fail("a launch that reached nothing must not report success"); //$NON-NLS-1$
            }
            catch (ComparisonException expected)
            {
                assertTrue("the message must say the launch never reached EDT: " //$NON-NLS-1$
                    + expected.getMessage(), expected.getMessage().contains("never reached EDT")); //$NON-NLS-1$
                assertTrue("and that the reservation is withdrawn: " + expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("withdrawn")); //$NON-NLS-1$
            }
        }
        finally
        {
            ComparisonEngine.uninstall();
        }
    }

    /**
     * A launch EDT REACHED and refused - which is what a comparison started from EDT's own
     * interface between the slot check and the hand-over produces - leaves no reservation behind
     * once EDT itself answers that it is not running it.
     * <p>
     * It used to. The rollback went through the ordinary hand-back, which is built for NOT
     * KNOWING: a comparison EDT reports no status for answers {@code NOT_STARTED_YET} and that
     * verdict deliberately KEEPS the record, because ending a comparison EDT has merely scheduled
     * costs EDT its comparison support until it is restarted. That is right when nobody refused
     * anything, and wrong here: the launch was refused and is never going to begin, so the kept
     * record named EDT's single slot as taken by a comparison that did not exist and refused every
     * later launch until the idle TTL expired.
     */
    @Test
    public void testALaunchEdtRefusedAndReportsItIsNotRunningLeavesNoReservation()
    {
        IComparisonManager manager = mock(IComparisonManager.class);
        doThrow(new IllegalStateException("EDT says no")).when(manager).startComparison(any()); //$NON-NLS-1$
        // The mock answers NO status for the handle, which is EDT saying it is not running this
        // comparison - the reading the withdrawal rests on. Left at the default deliberately: a
        // stubbed status would be pinning the stub rather than the rollback.
        ComparisonEngine.install(() -> manager);
        try
        {
            ComparisonEngine engine = ComparisonEngine.attached().orElseThrow();

            try
            {
                CompareConfigurationsTool.registerAndHandOver(engine,
                    engine.sessions().claimSlot("Demo"), comparisonHandle(), //$NON-NLS-1$
                    new CompareMergeProcessBatch(List.of()));
                fail("a refused launch must not report success"); //$NON-NLS-1$
            }
            catch (ComparisonException expected)
            {
                assertTrue("the message must say EDT refused it: " + expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("EDT refused to start the comparison")); //$NON-NLS-1$
                assertTrue("and that the registration is withdrawn: " + expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("registration here is withdrawn")); //$NON-NLS-1$
                // The OTHER withdrawal says this, and saying it here would be a lie: EDT was
                // reached and answered. Pinned separately because both sentences carry the
                // "withdrawn" clause, so the assertion above cannot tell the two apart.
                assertFalse("EDT was reached and refused, so this is not a launch that never " //$NON-NLS-1$
                    + "reached it: " + expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("never reached EDT")); //$NON-NLS-1$
            }

            assertEquals("a launch EDT refused must not go on holding EDT's single slot here", //$NON-NLS-1$
                0, engine.sessions().size());
        }
        finally
        {
            ComparisonEngine.uninstall();
        }
    }

    /**
     * The control for the branch above: what settles the withdrawal is EDT's own answer, not the
     * caller's throw. With EDT reporting the comparison as UNDER WAY, the refusal is still a
     * refusal but nothing says the comparison is not running - so the ordinary hand-back runs, and
     * this one really does end something on the platform.
     */
    @Test
    public void testALaunchEdtReachedAndRefusedIsHandedBackRatherThanWithdrawn()
    {
        IComparisonManager manager = mock(IComparisonManager.class);
        doThrow(new IllegalStateException("EDT says no")).when(manager).startComparison(any()); //$NON-NLS-1$
        // EDT reports the comparison as under way, so the hand-back is not withheld: the point
        // here is WHICH rollback runs, not the start guard.
        when(manager.getStatus(any()))
            .thenReturn(ComparisonProcessStatus.COMPARISON_PROCESS_INITIALIZATION_STARTED);
        ComparisonEngine.install(() -> manager);
        try
        {
            ComparisonEngine engine = ComparisonEngine.attached().orElseThrow();

            try
            {
                CompareConfigurationsTool.registerAndHandOver(engine,
                    engine.sessions().claimSlot("Demo"), comparisonHandle(), //$NON-NLS-1$
                    new CompareMergeProcessBatch(List.of()));
                fail("a refused launch must not report success"); //$NON-NLS-1$
            }
            catch (ComparisonException expected)
            {
                assertTrue("the message must say EDT refused it: " + expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("EDT refused to start the comparison")); //$NON-NLS-1$
                assertFalse("a reached platform is not a launch that never reached EDT: " //$NON-NLS-1$
                    + expected.getMessage(), expected.getMessage().contains("never reached EDT")); //$NON-NLS-1$
            }
        }
        finally
        {
            ComparisonEngine.uninstall();
        }
    }

    private static ComparisonProcessHandle comparisonHandle()
    {
        return new ComparisonProcessHandle(new NamedDataSource("Demo"), //$NON-NLS-1$
            new NamedDataSource("Other"), ComparisonScope.EMPTY_SCOPE); //$NON-NLS-1$
    }

    /**
     * The control: with NO facade installed at all - the bundle not started - there is nothing to
     * absorb, and the poll says the comparison cannot be reached rather than inventing a phase.
     */
    @Test
    public void testAComparisonWithNoFacadeAtAllIsReportedAsUnreachable()
    {
        ComparisonEngine.uninstall();

        Progress reading = new CompareConfigurationsTool.EngineBackend().poll("cmp-1"); //$NON-NLS-1$

        assertTrue(reading.isGone());
        assertFalse(reading.isFailed());
    }

    /** A data source that answers only the one method the handle asks it for. */
    private static final class NamedDataSource
        implements com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor
    {
        private final String name;

        NamedDataSource(String name)
        {
            this.name = name;
        }

        @Override
        public String getProjectName()
        {
            return name;
        }
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
        assertEquals(0, backend.handBacks());
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
        assertEquals(0, backend.handBacks());
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
        assertEquals(1, backend.handBacks());
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


    // ============ The canonical paths must reach the PLATFORM, not only the guard ============

    /**
     * The paths the two git data sources are built from all come back in their real form.
     * <p>
     * Canonicalising for the guard alone left the descriptors holding the original spellings, and
     * the platform's git source has to subtract the work tree out of the project path to find the
     * project inside the repository. Two spellings of one place do not subtract, both git sides
     * read empty, and the comparison reports every object as added on main - the very outcome the
     * guard was added to prevent, reached past it.
     */
    @Test
    public void testEveryDescriptorPathComesBackCanonicalNotOnlyTheGuardedOne() throws Exception
    {
        Path workTree = Files.createTempDirectory("cmp-worktree").toRealPath(); //$NON-NLS-1$
        Path project = Files.createDirectory(workTree.resolve("project")); //$NON-NLS-1$
        try
        {
            // The same three places, each reached by a detour instead of directly - what a
            // workspace location recorded one way and a repository discovered another way look
            // like to a path comparison.
            CompareConfigurationsTool.GitSidePaths sides = CompareConfigurationsTool.gitSidePaths(
                "Demo", workTree.resolve("project").resolve("..").resolve("project"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                workTree.resolve("project").resolve(".."), //$NON-NLS-1$
                workTree.resolve(".")); //$NON-NLS-1$

            assertEquals("the project path handed to both git sources must be canonical", //$NON-NLS-1$
                project.toRealPath(), sides.projectPath());
            assertEquals("the other side's work tree must be canonical too", workTree, //$NON-NLS-1$
                sides.otherWorkTree());
            assertEquals("and the ancestor's, or the two git sides disagree about where the " //$NON-NLS-1$
                + "repository is and only one of them finds the project", workTree, //$NON-NLS-1$
                sides.ancestorWorkTree());
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(workTree);
        }
    }

    @Test
    public void testTheAncestorWorkTreeIsCanonicalisedIndependentlyOfTheOther() throws Exception
    {
        // Its own test: the guard only ever looks at the OTHER side, so an ancestor left raw
        // would pass every check the guard makes and still read nothing.
        Path workTree = Files.createTempDirectory("cmp-worktree").toRealPath(); //$NON-NLS-1$
        Path project = Files.createDirectory(workTree.resolve("project")); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.GitSidePaths sides = CompareConfigurationsTool.gitSidePaths(
                "Demo", project, workTree, workTree.resolve("project").resolve("..")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertEquals(workTree, sides.ancestorWorkTree());
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(workTree);
        }
    }

    @Test
    public void testAWorkTreeNobodyResolvedStaysUnknownRatherThanBecomingAPath() throws Exception
    {
        // A resolver that produced no work tree is not a path to canonicalise, and turning it
        // into one would hand the platform a directory nobody named.
        Path workTree = Files.createTempDirectory("cmp-worktree").toRealPath(); //$NON-NLS-1$
        Path project = Files.createDirectory(workTree.resolve("project")); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.GitSidePaths sides =
                CompareConfigurationsTool.gitSidePaths("Demo", project, null, null); //$NON-NLS-1$

            assertNull(sides.otherWorkTree());
            assertNull(sides.ancestorWorkTree());
            assertEquals(project.toRealPath(), sides.projectPath());
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(workTree);
        }
    }

    @Test
    public void testTheGuardStillFiresThroughTheOnePlaceThatBuildsThePaths() throws Exception
    {
        // The canonicalisation and the refusal are one call, so a launch cannot get the paths
        // without also getting the check.
        Path workTree = Files.createTempDirectory("cmp-worktree").toRealPath(); //$NON-NLS-1$
        Path outside = Files.createTempDirectory("cmp-outside").toRealPath(); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.gitSidePaths("Demo", outside, workTree, workTree); //$NON-NLS-1$
            org.junit.Assert.fail("expected a refusal for a project outside the work tree"); //$NON-NLS-1$
        }
        catch (ComparisonException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains(outside.toString()));
        }
        finally
        {
            Files.deleteIfExists(workTree);
            Files.deleteIfExists(outside);
        }
    }

    // ============ The slot is claimed before the launch prepares, and given back ============

    /**
     * The launch TAKES the slot rather than finding it free, and hands that same claim to the
     * start. Preparation is where a launch spends its time - two git revisions, the project lookup,
     * the batch - and it used to run between the reading that said "free" and the registration that
     * acted on it, so two launches could both read "free" and both register.
     */
    @Test
    public void testTheSlotIsClaimedAndTheClaimIsWhatTheLaunchStartsUnder()
    {
        tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("exactly one claim per launch", 1, backend.claims()); //$NON-NLS-1$
        assertNotNull("and the start must be handed it, not left to mint an id of its own", //$NON-NLS-1$
            backend.lastClaim());
        assertTrue(backend.lastClaim().granted());
        assertEquals("a claim that became a comparison is not still standing", //$NON-NLS-1$
            List.of(), backend.standingClaims());
    }

    /**
     * A launch that failed before EDT gives its OWN claim back. Without this the slot stays held by
     * a launch that no longer exists, and every later one is refused - the leak in the shape it
     * takes once a claim is what holds the slot.
     */
    @Test
    public void testALaunchThatFailedBeforeEdtWithdrawsItsOwnClaim()
    {
        backend.failStartWith("otherRevision 'no-such-branch' does not resolve to a commit."); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "# Background job: failed"); //$NON-NLS-1$
        assertEquals("the claim this launch took is the claim this launch gives back", //$NON-NLS-1$
            1, backend.withdrawnClaims().size());
        assertEquals("so the slot is free for the next launch", //$NON-NLS-1$
            List.of(), backend.standingClaims());
    }

    /**
     * ...and the control that keeps that from being a blanket rollback: a launch that REACHED EDT
     * withdraws nothing. Its claim became the session, and dropping that record while this server
     * does not know what became of the comparison is the one thing the hand-back exists to prevent.
     */
    @Test
    public void testALaunchThatReachedEdtWithdrawsNothing()
    {
        tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("nothing may be withdrawn once the batch has left this process", //$NON-NLS-1$
            List.of(), backend.withdrawnClaims());
    }

    /**
     * A refused claim reaches the caller in the OWNER's own words. The two refusals - a comparison
     * that is open, a launch that is still starting - have different remedies, and a caller
     * re-wording either of them is how one situation comes to be described two ways.
     */
    @Test
    public void testARefusedClaimIsReportedWithTheOwnersOwnSentence()
    {
        backend.refuseClaimWith(ToolResult.error("Another compare_configurations call has " //$NON-NLS-1$
            + "already claimed EDT's single comparison slot for 'Erp'.")); //$NON-NLS-1$

        String result = tool.execute(request(Map.of("waitSeconds", "10"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(result, "already claimed EDT's single comparison slot for 'Erp'."); //$NON-NLS-1$
        assertEquals("nothing may be started on a slot this launch does not hold", //$NON-NLS-1$
            0, backend.starts());
    }

    // ============ Both sides must name ONE repository, and the project must be in it ============

    /**
     * The ancestor side was never checked at all: the guard only ever asked about the OTHER work
     * tree, so a project provably outside the ANCESTOR's repository passed and that data source was
     * built anyway. It reads nothing there, and the report calls every object "added since the
     * ancestor" - the exact outcome the guard exists to prevent, on the side it did not look at.
     * <p>
     * The other side is deliberately unresolved here, because that is the only arrangement in which
     * the ancestor check is the one thing that can fire: with both work trees known they must be
     * the SAME tree to get past the mismatch guard, and a project inside one is inside the other.
     */
    @Test
    public void testAProjectOutsideTheAncestorWorkTreeIsRefusedToo() throws Exception
    {
        Path ancestorTree = Files.createTempDirectory("cmp-ancestor").toRealPath(); //$NON-NLS-1$
        Path project = Files.createTempDirectory("cmp-project").toRealPath(); //$NON-NLS-1$
        try
        {
            // Nothing to say about the other side, and the old guard asked about nothing else.
            CompareConfigurationsTool.requireProjectInsideWorkTree("Demo", project, null); //$NON-NLS-1$

            CompareConfigurationsTool.gitSidePaths("Demo", project, null, ancestorTree); //$NON-NLS-1$
            org.junit.Assert.fail("expected a refusal naming the ancestor work tree"); //$NON-NLS-1$
        }
        catch (ComparisonException e)
        {
            String message = e.getMessage();
            assertTrue(message, message.contains(project.toString()));
            assertTrue("the ancestor work tree must be named: " + message, //$NON-NLS-1$
                message.contains(ancestorTree.toString()));
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(ancestorTree);
        }
    }

    /**
     * Two revisions resolved independently can answer with two DIFFERENT repositories - the
     * project's binding changed between the two calls, or one resolved through a linked worktree.
     * Reading one side out of each is a report whose every difference is an artefact of the
     * pairing, so the mismatch is refused and BOTH paths are named: "the ancestor read nothing" and
     * "the ancestor read another repository" are fixed differently.
     */
    @Test
    public void testTwoDifferentWorkTreesAreRefusedNamingBoth() throws Exception
    {
        Path otherTree = Files.createTempDirectory("cmp-other").toRealPath(); //$NON-NLS-1$
        Path ancestorTree = Files.createTempDirectory("cmp-ancestor").toRealPath(); //$NON-NLS-1$
        Path project = Files.createDirectory(otherTree.resolve("project")); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.gitSidePaths("Demo", project, otherTree, ancestorTree); //$NON-NLS-1$
            org.junit.Assert.fail("expected a refusal for two different work trees"); //$NON-NLS-1$
        }
        catch (ComparisonException e)
        {
            String message = e.getMessage();
            // The MISMATCH must be what is reported, not the per-side check that fires behind it.
            // Without this line the test passes on a build with no mismatch guard at all: the
            // project lives under the other work tree, so the "not inside the work tree" refusal
            // for the ANCESTOR happens to quote both paths as well.
            assertTrue("the refusal must name the mismatch itself: " + message, //$NON-NLS-1$
                message.contains("resolved to DIFFERENT git work trees")); //$NON-NLS-1$
            assertTrue(message, message.contains("Demo")); //$NON-NLS-1$
            assertTrue("the other side's path must be named: " + message, //$NON-NLS-1$
                message.contains(otherTree.toString()));
            assertTrue("and the ancestor's, or the reader cannot see WHICH two: " + message, //$NON-NLS-1$
                message.contains(ancestorTree.toString()));
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(otherTree);
            Files.deleteIfExists(ancestorTree);
        }
    }

    /**
     * The mismatch is decided in REAL form, like every other path question here: two spellings of
     * one work tree are one work tree, and refusing them would turn the fix into a false refusal.
     */
    @Test
    public void testTwoSpellingsOfOneWorkTreeAreNotAMismatch() throws Exception
    {
        Path workTree = Files.createTempDirectory("cmp-worktree").toRealPath(); //$NON-NLS-1$
        Path project = Files.createDirectory(workTree.resolve("project")); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.GitSidePaths sides = CompareConfigurationsTool.gitSidePaths(
                "Demo", project, workTree, workTree.resolve("project").resolve("..")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertEquals(sides.otherWorkTree(), sides.ancestorWorkTree());
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(workTree);
        }
    }

    /**
     * One side that resolved to no work tree at all is a gap in what this server could see, not a
     * mismatch. Refusing on it would fail launches on the strength of a difference nobody measured
     * - the same collapse the per-side guard already declines to make.
     */
    @Test
    public void testASideWithNoWorkTreeIsNotAMismatch() throws Exception
    {
        Path workTree = Files.createTempDirectory("cmp-worktree").toRealPath(); //$NON-NLS-1$
        Path project = Files.createDirectory(workTree.resolve("project")); //$NON-NLS-1$
        try
        {
            CompareConfigurationsTool.requireOneWorkTree("Demo", workTree, null); //$NON-NLS-1$
            CompareConfigurationsTool.requireOneWorkTree("Demo", null, workTree); //$NON-NLS-1$
            CompareConfigurationsTool.requireOneWorkTree("Demo", null, null); //$NON-NLS-1$

            CompareConfigurationsTool.GitSidePaths sides =
                CompareConfigurationsTool.gitSidePaths("Demo", project, null, workTree); //$NON-NLS-1$

            assertEquals(workTree, sides.ancestorWorkTree());
        }
        finally
        {
            Files.deleteIfExists(project);
            Files.deleteIfExists(workTree);
        }
    }

    // ============ An empty local registry is not a statement about EDT ============

    @Test
    public void testReleasingAnUnknownIdDoesNotClaimEdtIsIdleWhenItWasNotAsked()
    {
        backend.refuseRelease();
        backend.setLiveComparisonIds(List.of());
        backend.setEdtHasActiveComparison(PlatformAnswer.unavailable());

        String message = errorMessage(tool.execute(Map.of("releaseComparisonId", "cmp-nope"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("could not be asked")); //$NON-NLS-1$
        assertFalse("an unasked platform must not be reported as an idle one: " + message, //$NON-NLS-1$
            message.contains("EDT reports none running")); //$NON-NLS-1$
    }

    @Test
    public void testReleasingAnUnknownIdSaysWhenEdtItselfHoldsTheSlot()
    {
        // The window this exists for: a comparison started from the workbench occupies EDT's one
        // slot and is never registered here, so an empty local list proves nothing about it.
        backend.refuseRelease();
        backend.setLiveComparisonIds(List.of());
        backend.setEdtHasActiveComparison(PlatformAnswer.of(Boolean.TRUE));

        String message = errorMessage(tool.execute(Map.of("releaseComparisonId", "cmp-nope"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("started outside this server")); //$NON-NLS-1$
        assertFalse("and it must not invite a launch the platform will refuse: " + message, //$NON-NLS-1$
            message.contains("start one with compare_configurations")); //$NON-NLS-1$
    }

    @Test
    public void testReleasingAnUnknownIdMaySayNothingRunsWhenEdtAnsweredSo()
    {
        backend.refuseRelease();
        backend.setLiveComparisonIds(List.of());
        backend.setEdtHasActiveComparison(PlatformAnswer.of(Boolean.FALSE));

        String message = errorMessage(tool.execute(Map.of("releaseComparisonId", "cmp-nope"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("EDT reports none running")); //$NON-NLS-1$
    }

    /** A comparison backend that answers from the test instead of from EDT. */
    private static final class StubBackend implements Backend
    {
        private final AtomicReference<String> activeComparisonId = new AtomicReference<>();
        private final AtomicReference<PlatformAnswer<Boolean>> edtHasActiveComparison =
            new AtomicReference<>(PlatformAnswer.of(Boolean.FALSE));
        private final AtomicReference<String> lastComparisonId = new AtomicReference<>();
        private final AtomicReference<LaunchRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<String> startFailure = new AtomicReference<>();
        private final AtomicReference<String> precheckFailure = new AtomicReference<>();
        private final AtomicReference<Progress> pollAnswer =
            new AtomicReference<>(Progress.finished("COMPARISON_PROCESS_FINISHED")); //$NON-NLS-1$
        /** Answers handed out in order before the standing one, so a tick can differ from its
         * neighbours - which is the only way to tell "tolerated once" from "ignored always". */
        private final ConcurrentLinkedQueue<Progress> pollAnswers = new ConcurrentLinkedQueue<>();
        private final AtomicReference<String> lastHandedBack = new AtomicReference<>();
        private final AtomicReference<Ending> lastEnding = new AtomicReference<>();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger handBacks = new AtomicInteger();
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicReference<ToolResult> claimRefusal = new AtomicReference<>();
        private final AtomicReference<SlotClaim> lastClaim = new AtomicReference<>();
        private final List<String> standingClaims = Collections.synchronizedList(new ArrayList<>());
        private final List<String> withdrawnClaims =
            Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch startGate = new CountDownLatch(1);
        private volatile boolean blockStart;
        private final AtomicReference<Launch> handOverOnPoll = new AtomicReference<>();
        private final AtomicReference<Launch> requestStopDuringStart = new AtomicReference<>();
        private final AtomicReference<SlotHandback.Verdict> handBackVerdict =
            new AtomicReference<>(SlotHandback.Verdict.FREED);
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
        public SlotClaim claimSlot(LaunchRequest request)
        {
            claims.incrementAndGet();
            ToolResult refusal = claimRefusal.get();
            if (refusal != null)
            {
                return SlotClaims.refused(refusal);
            }
            // Minted like the registry mints one, and REMEMBERED: a claim that is handed out and
            // never given back is the leak this whole construction is about, so the test can see
            // both halves.
            String id = "claim-" + claims.get(); //$NON-NLS-1$
            standingClaims.add(id);
            return SlotClaims.granted(id);
        }

        @Override
        public void withdrawClaim(SlotClaim claim)
        {
            withdrawnClaims.add(claim.comparisonId());
            standingClaims.remove(claim.comparisonId());
        }

        @Override
        public String start(LaunchRequest request, SlotClaim claim) throws ComparisonException
        {
            lastClaim.set(claim);
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
            // The claim becomes the session, exactly as adoptClaim does it: from here on there is
            // nothing to withdraw, and a test that saw a withdrawal here would be seeing a record
            // dropped over a comparison that HAD reached the platform.
            standingClaims.remove(claim.comparisonId());
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
        public SlotHandback handBack(String comparisonId, Ending ending)
        {
            lastHandedBack.set(comparisonId);
            lastEnding.set(ending);
            handBacks.incrementAndGet();
            return SlotHandbacks.of(handBackVerdict.get(), comparisonId);
        }

        @Override
        public List<String> liveComparisonIds()
        {
            return liveComparisonIds;
        }

        @Override
        public PlatformAnswer<Boolean> edtHasActiveComparison()
        {
            return edtHasActiveComparison.get();
        }

        void setEdtHasActiveComparison(PlatformAnswer<Boolean> answer)
        {
            edtHasActiveComparison.set(answer);
        }

        void setActiveComparisonId(String id)
        {
            activeComparisonId.set(id);
        }

        /** Makes the next claim be refused with {@code refusal}, as a taken slot would. */
        void refuseClaimWith(ToolResult refusal)
        {
            claimRefusal.set(refusal);
        }

        /** @return the claims handed out and neither adopted nor withdrawn */
        List<String> standingClaims()
        {
            return List.copyOf(standingClaims);
        }

        /** @return the claims given back, in order */
        List<String> withdrawnClaims()
        {
            return List.copyOf(withdrawnClaims);
        }

        /** @return how many claims were asked for */
        int claims()
        {
            return claims.get();
        }

        /** @return the claim the last start was given, or {@code null} */
        SlotClaim lastClaim()
        {
            return lastClaim.get();
        }

        /** Makes the next hand-back observe {@code verdict} instead of a freed slot. */
        void answerHandBackWith(SlotHandback.Verdict verdict)
        {
            handBackVerdict.set(verdict);
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

        /** Makes the hand-back report that nothing was registered under the id. */
        void refuseRelease()
        {
            handBackVerdict.set(SlotHandback.Verdict.NOT_REGISTERED);
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

        int handBacks()
        {
            return handBacks.get();
        }

        String lastComparisonId()
        {
            return lastComparisonId.get();
        }

        String lastHandedBack()
        {
            return lastHandedBack.get();
        }

        Ending lastEnding()
        {
            return lastEnding.get();
        }

        LaunchRequest lastRequest()
        {
            return lastRequest.get();
        }
    }

    // ============ Ending a comparison is ONE operation with ONE verdict ============

    /**
     * {@code cancel_job} must not publish the verdict the registry turns into TERMINATED when the
     * hand-back did not complete, and must not repeat the sentence a caller stops reading at.
     * <p>
     * It reads the verdict through {@code slotIsFree()} rather than by listing the literals it
     * considers good. That is the point of the predicate: the two sites that split the verdicts
     * themselves split them slightly differently, and one of them counted a failed hand-back as a
     * stop.
     */
    @Test
    public void testAStopWhoseHandBackFailedIsNotPublishedAsAVerifiedTermination() throws Exception
    {
        backend.keepRunning();
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_FREED);
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult result = jobs.cancel(jobId);

        assertEquals(CancellationOutcome.ALREADY_COMMITTED, result.getOutcome());
        assertContains(result.getDetail(), "did NOT complete"); //$NON-NLS-1$
        assertFalse("the workspace was not confirmed released, so it must not be claimed: " //$NON-NLS-1$
            + result.getDetail(),
            result.getDetail().contains("temporary workspace released")); //$NON-NLS-1$
    }

    /** The positive control: a hand-back that freed the slot IS a verified termination. */
    @Test
    public void testAStopWhoseHandBackSucceededIsPublishedAsATermination() throws Exception
    {
        backend.keepRunning();
        String jobId = jobId(tool.execute(request(Map.of("waitSeconds", "0")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(backend.awaitStarted());

        CancellationResult result = jobs.cancel(jobId);

        assertEquals(CancellationOutcome.TERMINATED, result.getOutcome());
        assertContains(result.getDetail(), "temporary workspace"); //$NON-NLS-1$
        assertEquals("a cancellation is recorded on the platform as one", Ending.CANCELLED, //$NON-NLS-1$
            backend.lastEnding());
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
            backend.handBacks());
    }

    /**
     * The defect, and it is the whole branch's own defect mirrored: EDT accepted the batch and its
     * scheduled job had still not been listed once when the starting budget ran out, so the call
     * ended with a FAILURE. But the comparison is not ended by that branch and must not be -
     * cancelling a batch that is still waiting to run removes the Eclipse job before the
     * platform's own "the slot is free" step ever executes, and EDT then reports a comparison as
     * active until it is restarted, which is precisely why the hand-back's owner withholds it. So
     * the caller was told nothing came of a comparison that then started, took EDT's single slot
     * under the very id it had been told led nowhere, and refused its next launch.
     */
    @Test
    public void testAStartingBudgetThatRanOutIsAnOutcomeWithAnIdNotAFailure() throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        // What the registry answers for a comparison EDT has not begun: nothing was asked of the
        // platform and the record is KEPT.
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_STARTED_YET);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        String text = String.valueOf(rendered);
        assertContains(text, "**Not started:**"); //$NON-NLS-1$
        assertContains(text, backend.lastComparisonId());
    }

    /**
     * The half the caller ACTS on: the id is only useful with a way to use it, and the way out is
     * the hand-back's own sentence rather than a second wording of it here.
     */
    @Test
    public void testAStartingBudgetThatRanOutHandsBackTheWayToFreeTheSlot() throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_STARTED_YET);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        assertContains(String.valueOf(rendered), "releaseComparisonId"); //$NON-NLS-1$
    }

    /**
     * Its own test and its own literal: the ending must not be dressed up as a stop either. The
     * hand-back was WITHHELD, so claiming the comparison was cancelled would be the same defect
     * pointing the other way.
     */
    @Test
    public void testAStartingBudgetThatRanOutClaimsNoCancellation() throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_STARTED_YET);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        String text = String.valueOf(rendered);
        assertFalse("a comparison nobody ended must not be reported as stopped:\n" + text, //$NON-NLS-1$
            text.contains("**Cancelled:**")); //$NON-NLS-1$
        assertFalse(text.contains("was stopped before it finished")); //$NON-NLS-1$
    }

    /**
     * The positive control for the pair below, and its own literal: when the hand-back's owner
     * DID withhold - the one verdict that means EDT had not begun the comparison, so nothing was
     * asked of the platform - "not cancelled" is a reading and must still be published. Without
     * this pin, a branch that had simply stopped making the claim in every case would pass.
     */
    @Test
    public void testAStartingBudgetWhoseHandBackWasWithheldStatesTheComparisonWasNotCancelled()
        throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_STARTED_YET);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        assertContains(String.valueOf(rendered),
            "The comparison was NOT cancelled and this is NOT its result."); //$NON-NLS-1$
    }

    /**
     * The defect, and it is this branch's own defect pointing the other way. The hand-back is
     * requested with {@link Ending#CANCELLED} like every other early ending, and EDT can begin the
     * comparison inside the one poll interval between the last STARTING answer and that request.
     * When it does, the hand-back really cancels it and answers FREED - and the wording that
     * claimed "the comparison was NOT cancelled" unconditionally then stood immediately before the
     * hand-back's own sentence saying the comparison had been ended and the slot released. One
     * answer, two halves, contradicting each other.
     *
     * <p>Pinned as an ABSENCE, because the defect is a claim that must not be made: a test that
     * only checked for the neutral wording would be passed by a branch that printed both.</p>
     */
    @Test
    public void testAStartingBudgetWhoseHandBackEndedTheComparisonClaimsNoAbsenceOfCancellation()
        throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        // EDT began it in the race window, so the hand-back it was asked for CANCELLED it.
        backend.answerHandBackWith(SlotHandback.Verdict.FREED);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        String text = String.valueOf(rendered);
        assertFalse("the hand-back ended the comparison, so 'NOT cancelled' is a claim nothing " //$NON-NLS-1$
            + "observed - and it contradicts the sentence right after it:\n" + text, //$NON-NLS-1$
            text.contains("NOT cancelled")); //$NON-NLS-1$
    }

    /**
     * Its own literal: dropping the claim must not drop the ANSWER. What became of the comparison
     * is the hand-back's own sentence, and the outcome still has to publish it verbatim - a branch
     * that fell silent would pass the absence pin above and tell the caller nothing.
     */
    @Test
    public void testAStartingBudgetWhoseHandBackEndedTheComparisonPublishesWhatItDid()
        throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.FREED);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        assertContains(String.valueOf(rendered),
            "was ended and its temporary workspace released"); //$NON-NLS-1$
    }

    /**
     * And its own literal for the other half: the outcome is still NOT a result, whichever way the
     * hand-back went. That is the fact the whole branch exists to carry, and it is not the fact
     * the fix removed.
     */
    @Test
    public void testAStartingBudgetWhoseHandBackEndedTheComparisonStillClaimsNoResult()
        throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.FREED);

        Object rendered = quickTool().runComparison(launchRequest(), reporter(120_000L),
            new Launch());

        String text = String.valueOf(rendered);
        assertContains(text, "**Not started:**"); //$NON-NLS-1$
        assertContains(text, "This is NOT its result"); //$NON-NLS-1$
    }

    /**
     * The fork itself, asked of EVERY verdict rather than of the two that happened to be written
     * down. Pinning {@code NOT_STARTED_YET} and {@code FREED} alone leaves six verdicts unpinned,
     * and an implementation that neutralised the wording for {@code FREED} only - the shape the
     * pair above describes - keeps answering "the comparison was NOT cancelled" for
     * {@code ALREADY_FREE}, {@code NOT_REGISTERED}, {@code NOT_FREED}, {@code LAUNCH_REFUSED},
     * {@code NEVER_STARTED} and {@code UNREACHABLE}, and passes both.
     * <p>
     * The claim is a READING, and exactly one verdict is that reading: the one that means nothing
     * was asked of the platform BECAUSE EDT had not begun the comparison. The expectation is
     * therefore taken from {@link SlotHandback#platformHasNotBegun()} itself rather than from a
     * verdict name copied beside it - and the count is pinned too, so widening that predicate
     * cannot quietly widen this claim with it.
     * <p>
     * Every verdict IS reachable here: the value the branch reads comes from the hand-back's owner,
     * and the stub answers as the owner does, so none of the eight has to be declared unreachable.
     */
    @Test
    public void testTheNotCancelledClaimIsMadeForTheWITHHELDVerdictAndNoOther() throws Exception
    {
        List<String> wrong = new ArrayList<>();
        int readings = 0;
        for (SlotHandback.Verdict verdict : SlotHandback.Verdict.values())
        {
            backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
            backend.answerHandBackWith(verdict);

            String text = String.valueOf(quickTool().runComparison(launchRequest(),
                reporter(120_000L), new Launch()));

            boolean withheld = SlotHandbacks.of(verdict, "probe").platformHasNotBegun(); //$NON-NLS-1$
            readings += withheld ? 1 : 0;
            if (text.contains(NOT_CANCELLED_CLAIM) != withheld)
            {
                wrong.add(verdict + (withheld ? " must claim it and does not" //$NON-NLS-1$
                    : " claims it and nothing observed it")); //$NON-NLS-1$
            }
        }

        assertEquals("exactly one verdict is a reading of 'not cancelled'; if that changed, this " //$NON-NLS-1$
            + "test's expectation moved with it and is no longer pinning anything", 1, readings); //$NON-NLS-1$
        assertTrue("'" + NOT_CANCELLED_CLAIM + "' may stand only where the hand-back was " //$NON-NLS-1$ //$NON-NLS-2$
            + "WITHHELD: " + wrong, wrong.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Its own literal, over the same eight: dropping the claim must never drop the ANSWER. What
     * became of the comparison is the hand-back's own sentence, and the branch publishes it
     * verbatim for every verdict - a wording that fell silent on the six that were unpinned would
     * leave the caller holding an id and no account of it.
     */
    @Test
    public void testEveryHandBackVerdictHasItsOwnAnswerPublishedVerbatim() throws Exception
    {
        List<String> silent = new ArrayList<>();
        for (SlotHandback.Verdict verdict : SlotHandback.Verdict.values())
        {
            backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
            backend.answerHandBackWith(verdict);

            String text = String.valueOf(quickTool().runComparison(launchRequest(),
                reporter(120_000L), new Launch()));

            String sentence = SlotHandbacks.of(verdict, backend.lastHandedBack()).sentence();
            if (!text.contains(sentence))
            {
                silent.add(verdict.name());
            }
        }

        assertTrue("the slot half of the answer is the hand-back's own sentence, published as it " //$NON-NLS-1$
            + "stands, and these verdicts lost it: " + silent, silent.isEmpty()); //$NON-NLS-1$
    }

    /**
     * The owner is still ASKED - the branch withholds nothing itself. Without this pin the fix
     * would also be passed by a branch that stopped consulting the hand-back at all, which is how
     * a comparison EDT had begun in the meantime would be left running with nobody accounting for
     * it.
     */
    @Test
    public void testAStartingBudgetThatRanOutStillAsksTheHandBackOwner() throws Exception
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_STARTED_YET);

        quickTool().runComparison(launchRequest(), reporter(120_000L), new Launch());

        assertEquals(1, backend.handBacks());
    }

    /**
     * How {@code get_job_status} reads it, which is the question the outcome exists to answer: the
     * job is DONE rather than FAILED, and its result is neither a comparison report nor an error.
     */
    @Test
    public void testAStartingBudgetThatRanOutCompletesTheJobWithoutClaimingAReport()
    {
        backend.queuePollAnswers(startingTicks(CompareConfigurationsTool.MAX_STARTING_TICKS));
        backend.answerHandBackWith(SlotHandback.Verdict.NOT_STARTED_YET);
        backend.setReport("# Comparison: TestConfiguration"); //$NON-NLS-1$

        String rendered = quickTool().execute(request(Map.of("waitSeconds", "20"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertContains(rendered, "# Background job: done"); //$NON-NLS-1$
        assertContains(rendered, "**Not started:**"); //$NON-NLS-1$
        assertFalse("the job did not fail, so it must not be rendered as failed:\n" + rendered, //$NON-NLS-1$
            rendered.contains("# Background job: failed")); //$NON-NLS-1$
        assertFalse("nothing was compared, so no report may be claimed:\n" + rendered, //$NON-NLS-1$
            rendered.contains("# Comparison: TestConfiguration")); //$NON-NLS-1$
    }

    /**
     * The tool with the poll interval shortened, so the one-minute starting budget is reached in
     * milliseconds. Everything else - the tick counts, the endings, the sentences - is production.
     *
     * @return the tool under test
     */
    private CompareConfigurationsTool quickTool()
    {
        return new CompareConfigurationsTool(backend, jobs, 1L);
    }

    /**
     * @param count how many ticks to answer
     * @return that many "EDT has accepted it and has not listed it yet" answers
     */
    private static Progress[] startingTicks(int count)
    {
        Progress[] answers = new Progress[count];
        for (int tick = 0; tick < count; tick++)
        {
            answers[tick] = Progress.starting("EDT has accepted the comparison and has not " //$NON-NLS-1$
                + "listed it yet, so it answers no status for it"); //$NON-NLS-1$
        }
        return answers;
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
            assertEquals(1, backend.handBacks());
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
            assertEquals(1, backend.handBacks());
            assertEquals(backend.lastComparisonId(), backend.lastHandedBack());
            assertEquals(Ending.CANCELLED, backend.lastEnding());
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

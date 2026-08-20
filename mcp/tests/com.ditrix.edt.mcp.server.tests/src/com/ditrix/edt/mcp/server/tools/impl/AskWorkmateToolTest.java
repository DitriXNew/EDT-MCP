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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.ProgressListener;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.WorkmateResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Headless async contract and actionable-error tests for {@link AskWorkmateTool}. */
public class AskWorkmateToolTest
{
    private static final Pattern JOB_ID_ROW =
        Pattern.compile("(?m)^\\| jobId \\| ([^|]+) \\|$"); //$NON-NLS-1$

    private final List<BackgroundJobs> registries = new ArrayList<>();

    @After
    public void tearDown()
    {
        for (BackgroundJobs registry : registries)
        {
            registry.close();
        }
    }

    @Test
    public void testStaticContract()
    {
        AskWorkmateTool tool = tool(stubReturning("answer", null)); //$NON-NLS-1$
        assertEquals(AskWorkmateTool.NAME, tool.getName());
        assertEquals("ask_workmate", tool.getName()); //$NON-NLS-1$
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('ask_workmate')")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("background")); //$NON-NLS-1$

        ToolAnnotations annotations = tool.getAnnotations();
        assertEquals(Boolean.FALSE, annotations.getReadOnlyHint());
        assertEquals(Boolean.FALSE, annotations.getIdempotentHint());
        // Not a guess: Workmate's loop can edit metadata and BSL, and workmateTool mode can
        // run arbitrary JShell code. Clients gate confirmation on this hint.
        assertEquals(Boolean.TRUE, annotations.getDestructiveHint());
        assertEquals(Boolean.TRUE, annotations.getOpenWorldHint());
    }

    @Test
    public void testSchemaDeclaresStartModesAndWaitBudgets()
    {
        JsonObject schema = JsonParser.parseString(
            tool(stubReturning("answer", null)).getInputSchema()) //$NON-NLS-1$
            .getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(properties.has("question")); //$NON-NLS-1$
        assertFalse(properties.has("jobId")); //$NON-NLS-1$
        assertTrue(properties.has("projectName")); //$NON-NLS-1$
        assertTrue(properties.has("maxToolRounds")); //$NON-NLS-1$
        assertTrue(properties.has("skillName")); //$NON-NLS-1$
        assertTrue(properties.has("timeoutSeconds")); //$NON-NLS-1$
        assertTrue(properties.has("waitSeconds")); //$NON-NLS-1$
        assertTrue(properties.has("workmateTool")); //$NON-NLS-1$
        assertTrue(properties.has("workmateArgs")); //$NON-NLS-1$
        assertTrue(properties.has("mode")); //$NON-NLS-1$
        assertTrue(properties.has("shareMcpTools")); //$NON-NLS-1$
        assertEquals(10, properties.size());

        // The mode description must warn that chat answers never come back through
        // MCP, otherwise a caller picks 'chat' expecting a returned answer.
        String modeDescription = properties.getAsJsonObject("mode") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(modeDescription.contains("answer")); //$NON-NLS-1$
        assertTrue(modeDescription.contains("chat")); //$NON-NLS-1$
        assertTrue(modeDescription.contains("NOT returned here")); //$NON-NLS-1$

        // The default skill is what makes Workmate run its tool loop at all, so the
        // schema must name it rather than say "Workmate's default".
        String skillDescription = properties.getAsJsonObject("skillName") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(skillDescription.contains(WorkmateGateway.DEFAULT_SKILL));
        assertTrue(skillDescription.contains("answers from the model alone")); //$NON-NLS-1$

        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        assertEquals(0, required.size());
        String timeoutDescription = properties.getAsJsonObject("timeoutSeconds") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$
        String waitDescription = properties.getAsJsonObject("waitSeconds") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(timeoutDescription.contains("Total wall-clock budget")); //$NON-NLS-1$
        // Both halves of the promise, because half of it would mislead: the budget fails the
        // job, EXCEPT once the request has reached Workmate and can no longer be taken back.
        assertTrue(timeoutDescription.contains("the job is failed")); //$NON-NLS-1$
        assertTrue(timeoutDescription.contains("cannot be taken back")); //$NON-NLS-1$
        assertTrue(waitDescription.contains("this start call")); //$NON-NLS-1$
        assertTrue(waitDescription.contains("0 to 45")); //$NON-NLS-1$
    }

    @Test
    public void testMissingAndBlankStartArgumentsAreActionable()
    {
        AskWorkmateTool tool = tool(stubReturning("unused", null)); //$NON-NLS-1$
        String missing = tool.execute(Collections.emptyMap());
        assertErrorContains(missing, "requires a non-empty question", "workmateTool", //$NON-NLS-1$ //$NON-NLS-2$
            "get_job_status"); //$NON-NLS-1$

        String blankQuestion = tool.execute(params("question", "   ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorContains(blankQuestion, "non-whitespace", "retry ask_workmate"); //$NON-NLS-1$ //$NON-NLS-2$

    }

    @Test
    public void testIntegerValidationIsActionable()
    {
        AskWorkmateTool tool = tool(stubReturning("unused", null)); //$NON-NLS-1$
        Map<String, String> params = params("question", "q"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("maxToolRounds", "zero"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorContains(tool.execute(params), "maxToolRounds", "positive integer"); //$NON-NLS-1$ //$NON-NLS-2$

        params.remove("maxToolRounds"); //$NON-NLS-1$
        params.put("timeoutSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorContains(tool.execute(params), "timeoutSeconds", "omit it to use the default"); //$NON-NLS-1$ //$NON-NLS-2$

        // A job owns a worker for its whole budget, so an unbounded value would let a few
        // calls park the pool until EDT restarts.
        params.put("timeoutSeconds", String.valueOf(Integer.MAX_VALUE)); //$NON-NLS-1$
        assertErrorContains(tool.execute(params), "timeoutSeconds", //$NON-NLS-1$ //$NON-NLS-2$
            "1 to " + AskWorkmateTool.MAX_TIMEOUT_SECONDS); //$NON-NLS-1$
        params.put("timeoutSeconds", //$NON-NLS-1$
            String.valueOf(AskWorkmateTool.MAX_TIMEOUT_SECONDS + 1));
        assertErrorContains(tool.execute(params), "timeoutSeconds", "holds a worker"); //$NON-NLS-1$ //$NON-NLS-2$

        params.remove("timeoutSeconds"); //$NON-NLS-1$
        params.put("waitSeconds", "46"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorContains(tool.execute(params), "waitSeconds", "0 to 45"); //$NON-NLS-1$ //$NON-NLS-2$

        params.put("waitSeconds", "-1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertErrorContains(tool.execute(params), "waitSeconds", "return immediately"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStartReturnsRunningWithoutWaitingForSlowGateway() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AskWorkmateTool tool = tool(blockingGateway(entered, release,
            new WorkmateResponse("later", null))); //$NON-NLS-1$
        Map<String, String> start = params("question", "slow question"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("timeoutSeconds", "10"); //$NON-NLS-1$ //$NON-NLS-2$

        long startedAt = System.nanoTime();
        String result = tool.execute(start);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        try
        {
            assertTrue("start call blocked for " + elapsedMs + " ms", elapsedMs < 500); //$NON-NLS-1$ //$NON-NLS-2$
            assertJobStatus(result, "running"); //$NON-NLS-1$
            assertFalse(extractJobId(result).isBlank());
            assertTrue("gateway did not start in the background", //$NON-NLS-1$
                entered.await(1, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testSharedStatusToolReturnsCompletedAnswerReasoningProgressAndCount() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkmateResponse response = new WorkmateResponse("The answer", //$NON-NLS-1$
            "Because of the metadata model.", Integer.valueOf(3)); //$NON-NLS-1$
        AskWorkmateTool tool = tool(blockingGateway(entered, release, response));

        Map<String, String> start = params("question", "q"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String started = tool.execute(start);
        String jobId = extractJobId(started);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        release.countDown();

        String done = pollLatestJob(jobId, 1);
        assertJobStatus(done, "done"); //$NON-NLS-1$
        assertTrue(done.contains("| assistantMessages | 3 |")); //$NON-NLS-1$
        assertTrue(done.contains("## Answer\n\nThe answer")); //$NON-NLS-1$
        assertTrue(done.contains("## Reasoning\n\nBecause of the metadata model.")); //$NON-NLS-1$
        assertContains(done, "Accepted the question", "Located the 1C:Workmate plugin", //$NON-NLS-1$ //$NON-NLS-2$
            "Obtained the Workmate conversation facade", "Sent the request to Workmate", //$NON-NLS-1$ //$NON-NLS-2$
            "Received the Workmate response"); //$NON-NLS-1$
    }

    @Test
    public void testTotalBudgetMovesSlowJobToFailed() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AskWorkmateTool tool = tool(blockingGateway(entered, release,
            new WorkmateResponse("too late", null))); //$NON-NLS-1$
        Map<String, String> start = params("question", "slow"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("timeoutSeconds", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String jobId = extractJobId(tool.execute(start));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        String failed = pollLatestJob(jobId, 2);
        release.countDown();
        assertJobStatus(failed, "failed"); //$NON-NLS-1$
        assertContains(failed, "total timeoutSeconds budget of 1 seconds", //$NON-NLS-1$
            "larger timeoutSeconds", "network status"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testWaitSecondsBoundsOnlyOneCall() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AskWorkmateTool tool = tool(blockingGateway(entered, release,
            new WorkmateResponse("later", null))); //$NON-NLS-1$
        Map<String, String> start = params("question", "slow"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("waitSeconds", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("timeoutSeconds", "20"); //$NON-NLS-1$ //$NON-NLS-2$

        long startedAt = System.nanoTime();
        String result = tool.execute(start);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        try
        {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertJobStatus(result, "running"); //$NON-NLS-1$
            assertTrue("waitSeconds=1 held the call for " + elapsedMs + " ms", //$NON-NLS-1$ //$NON-NLS-2$
                elapsedMs < 2500);
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testMissingWorkmateBundleErrorIsActionable()
    {
        GatewayException failure = GatewayException.notInstalled(
            "required OSGi bundle 'com.e1c.edt.ai' was not found"); //$NON-NLS-1$
        String result = executeWithFailure(failure);
        assertJobFailedContains(result, "1C:Workmate is not installed", //$NON-NLS-1$
            "com.e1c.edt.ai", "Install New Software", "https://code.1c.ai/plugin/", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "restart EDT", "retry ask_workmate"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDisabledWorkmateErrorNamesPreferenceFix()
    {
        String result = executeWithFailure(
            GatewayException.disabled("com.e1c.edt.ai.ISettings.isEnabled() is false")); //$NON-NLS-1$
        assertJobFailedContains(result, "installed but switched off", "ISettings.isEnabled", //$NON-NLS-1$ //$NON-NLS-2$
            "Window > Preferences > 1C:Workmate", "retry ask_workmate"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingWorkmateAccessKeyNamesPreferenceFix()
    {
        String result = executeWithFailure(GatewayException.noClientToken(
            "com.e1c.edt.ai.ISettings.hasClientToken() is false")); //$NON-NLS-1$
        assertJobFailedContains(result, "has no valid access key", "ISettings.hasClientToken", //$NON-NLS-1$ //$NON-NLS-2$
            "1C ITS portal", "Preferences > 1C:Workmate > User Token", //$NON-NLS-1$ //$NON-NLS-2$
            "retry ask_workmate"); //$NON-NLS-1$
    }

    @Test
    public void testIncompatibleWorkmateErrorNamesMissingMemberAndFix()
    {
        String missing = "field 'com.e1c.edt.ai.ui.BaseActivator.injectorRef' was not found"; //$NON-NLS-1$
        String result = executeWithFailure(GatewayException.incompatible(missing));
        assertJobFailedContains(result, "Incompatible 1C:Workmate version or structure", //$NON-NLS-1$
            missing, "compatible with 1.0.5", "update EDT-MCP's Workmate adapter"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInstalledButNotInitializedErrorIsActionable()
    {
        String result = executeWithFailure(
            GatewayException.notReady("BaseActivator.getDefault() returned null")); //$NON-NLS-1$
        assertJobFailedContains(result, "installed but not initialized", "Open Workmate", //$NON-NLS-1$ //$NON-NLS-2$
            "wait for it to initialize", "retry ask_workmate"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGatewayTimeoutErrorNamesActualTotalBoundAndFix()
    {
        AskWorkmateTool tool = tool(stubThrowing(GatewayException.timedOut()));
        Map<String, String> params = params("question", "q"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("timeoutSeconds", "7"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = tool.execute(params);
        assertJobFailedContains(result, "within 7 seconds", "larger timeoutSeconds", //$NON-NLS-1$ //$NON-NLS-2$
            "network status"); //$NON-NLS-1$
    }

    /**
     * A timeout AFTER the request was sent must not carry the "retry with a bigger budget"
     * advice: Workmate may still be running it, and its tools change this configuration, so a
     * blind retry runs the same edits twice. The message has to say check first, then start.
     */
    @Test
    public void testTimeoutAfterDispatchWarnsInsteadOfInvitingAPlainRetry()
    {
        AskWorkmateTool tool = tool(stubThrowing(GatewayException.timedOutAfterDispatch()));
        Map<String, String> params = params("question", "q"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("timeoutSeconds", "7"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);
        assertJobFailedContains(result, "within 7 seconds", "already been sent", //$NON-NLS-1$ //$NON-NLS-2$
            "may still be working on it", "Do NOT", "get_project_errors"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testWorkmateReturnedFailureIsActionable()
    {
        String result = executeWithFailure(
            GatewayException.callFailed("HTTP 401 Unauthorized")); //$NON-NLS-1$
        assertJobFailedContains(result, "failed to answer", "HTTP 401 Unauthorized", //$NON-NLS-1$ //$NON-NLS-2$
            "sign-in", "network", "settings", "retry ask_workmate"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testARejectedToolNameIsReportedAsItStands()
    {
        // Found on the stand, not in a unit test: wrapping this message produced "failed to
        // answer: 1C:Workmate knows no tool named ... again.. Check Workmate sign-in, network,
        // and settings" - a stutter, a doubled stop, and irrelevant advice for a typo.
        String detail = "1C:Workmate knows no tool named 'Nope' and rejected the call " //$NON-NLS-1$
            + "without running anything. Check the name and call ask_workmate again."; //$NON-NLS-1$
        String result = executeWithFailure(GatewayException.unknownTool(detail));
        assertJobFailedContains(result, "knows no tool named", "without running anything"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no second, unrelated diagnosis is bolted on", //$NON-NLS-1$
            result.contains("sign-in")); //$NON-NLS-1$
        assertFalse("and no doubled full stop where the two used to meet", //$NON-NLS-1$
            result.contains("again..")); //$NON-NLS-1$
    }

    @Test
    public void testAVerbatimDetailIsStillMadeWholeBeforeItIsReported()
    {
        // Passing a detail through unwrapped puts the burden of it being a sentence HERE, not
        // on whoever raised it: a blank one would otherwise surface as a class name.
        String blank = executeWithFailure(GatewayException.unknownTool("   ")); //$NON-NLS-1$
        assertJobFailedContains(blank, "rejected the requested tool name", //$NON-NLS-1$
            "call ask_workmate again"); //$NON-NLS-1$
        assertFalse("a blank detail must never degrade to an exception class name", //$NON-NLS-1$
            blank.contains("WorkmateJobException")); //$NON-NLS-1$

        String unpunctuated =
            executeWithFailure(GatewayException.unknownTool("no such tool here")); //$NON-NLS-1$
        assertJobFailedContains(unpunctuated, "no such tool here."); //$NON-NLS-1$
    }

    @Test
    public void testAFailureAfterDispatchNeverAsksForARetry()
    {
        // The detail says the turn had already run; the wrapper must not undo that by
        // appending "then retry ask_workmate", which is what the ordinary failure says.
        String result = executeWithFailure(GatewayException.failedAfterDispatch(
            "1C:Workmate failed while continuing the conversation (boom).")); //$NON-NLS-1$
        assertJobFailedContains(result, "failed after the request had been sent", //$NON-NLS-1$
            "Do NOT simply repeat", "get_project_errors", "start a new ask_workmate job"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("a dispatched failure must never invite a blind retry", //$NON-NLS-1$
            result.contains("then retry ask_workmate")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyAnswerMovesJobToFailed()
    {
        AskWorkmateTool nullText = tool(stubReturning(null, "reasoning")); //$NON-NLS-1$
        String result = nullText.execute(params("question", "q")); //$NON-NLS-1$ //$NON-NLS-2$
        assertJobFailedContains(result, "returned an empty answer", "signed in", //$NON-NLS-1$ //$NON-NLS-2$
            "configured", "new ask_workmate job"); //$NON-NLS-1$ //$NON-NLS-2$

        AskWorkmateTool nullResponse = tool(stubReturningResponse(null));
        assertJobFailedContains(nullResponse.execute(params("question", "q")), //$NON-NLS-1$ //$NON-NLS-2$
            "returned an empty answer", "new ask_workmate job"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFastSuccessReturnsDoneMarkdownWithOptionalReasoning()
    {
        AskWorkmateTool plain = tool(stubReturning("The answer", "  ")); //$NON-NLS-1$ //$NON-NLS-2$
        String plainResult = plain.execute(params("question", "q")); //$NON-NLS-1$ //$NON-NLS-2$
        assertJobStatus(plainResult, "done"); //$NON-NLS-1$
        assertTrue(plainResult.contains("## Answer\n\nThe answer")); //$NON-NLS-1$
        assertFalse(plainResult.contains("## Reasoning")); //$NON-NLS-1$

        AskWorkmateTool reasoned = tool(stubReturning(
            "The answer", "Because of the metadata model.")); //$NON-NLS-1$ //$NON-NLS-2$
        String reasonedResult = reasoned.execute(params("question", "q")); //$NON-NLS-1$ //$NON-NLS-2$
        assertJobStatus(reasonedResult, "done"); //$NON-NLS-1$
        assertTrue(reasonedResult.contains(
            "## Reasoning\n\nBecause of the metadata model.")); //$NON-NLS-1$
    }

    @Test
    public void testQuestionCarriesTheBridgePreambleUnlessTheCallerOptsOut()
    {
        AtomicReference<String> sent = new AtomicReference<>();
        Map<String, String> params = params("question", "Which catalogs exist?"); //$NON-NLS-1$ //$NON-NLS-2$
        tool(questionCapturingGateway(sent)).execute(params);

        String withPreamble = sent.get();
        assertTrue(withPreamble.contains("edt.mcp.bridge=v1")); //$NON-NLS-1$
        assertTrue(withPreamble.contains("java.util.function.BiFunction")); //$NON-NLS-1$
        assertTrue(withPreamble.contains("jshell_edt_canonical_imports")); //$NON-NLS-1$
        // The question itself must survive verbatim and come last.
        assertTrue(withPreamble.endsWith("Question:\nWhich catalogs exist?")); //$NON-NLS-1$

        params.put("shareMcpTools", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        tool(questionCapturingGateway(sent)).execute(params);
        assertEquals("Which catalogs exist?", sent.get()); //$NON-NLS-1$
    }

    @Test
    public void testPreambleListsRegisteredToolNamesAndPointsAtTheGuideTool()
    {
        McpToolRegistry.getInstance().clear();
        McpToolRegistry.getInstance().register(new NamedProbeTool("zzz_last")); //$NON-NLS-1$
        McpToolRegistry.getInstance().register(new NamedProbeTool("aaa_first")); //$NON-NLS-1$
        try
        {
            AtomicReference<String> sent = new AtomicReference<>();
            tool(questionCapturingGateway(sent)).execute(params("question", "q")); //$NON-NLS-1$ //$NON-NLS-2$

            String preamble = sent.get();
            // Names only - the full specifications are far too large to prepend.
            assertTrue(preamble.contains("aaa_first, zzz_last")); //$NON-NLS-1$
            assertFalse(preamble.contains("Probe for the preamble catalogue")); //$NON-NLS-1$
            assertTrue(preamble.contains("get_tool_guide")); //$NON-NLS-1$
            // Delegating to this very tool is allowed and explained, not filtered out; what
            // keeps it safe is the concurrency bound, not hiding the tool.
            assertTrue(preamble.contains("sub-agent")); //$NON-NLS-1$
            assertTrue(preamble.contains("one level deep")); //$NON-NLS-1$
        }
        finally
        {
            McpToolRegistry.getInstance().clear();
        }
    }

    /**
     * With no project named there is nothing truthful to put in projectName, and the example
     * exists to be RUN: a placeholder would make Workmate's very first bridge call fail with
     * "project not found" instead of proving the bridge works. So the example becomes the
     * discovery call that takes no arguments at all.
     */
    @Test
    public void testPreambleWithoutAProjectShowsAnExampleThatRunsAsWritten()
    {
        AtomicReference<String> sent = new AtomicReference<>();
        tool(questionCapturingGateway(sent)).execute(params("question", "q")); //$NON-NLS-1$ //$NON-NLS-2$

        String preamble = sent.get();
        assertFalse("a placeholder would be executed verbatim: " + preamble, //$NON-NLS-1$
            preamble.contains("<project>")); //$NON-NLS-1$
        assertTrue(preamble.contains("mcp.apply(\"list_projects\", \"{}\")")); //$NON-NLS-1$
        assertFalse("no projectName argument can be honest here", //$NON-NLS-1$
            preamble.contains("projectName")); //$NON-NLS-1$
    }

    /**
     * The project name lands inside a JSON string inside a Java string literal in the
     * snippet Workmate is told to run, so a quote or a backslash in it has to survive two
     * levels of escaping or the snippet does not compile.
     */
    @Test
    public void testProjectNameIsEscapedForTheNestedJavaAndJsonLiteral()
    {
        // Asserted by DECODING rather than by matching escape soup: undo the Java string
        // literal, parse the JSON, and the name must come back exactly as it went in.
        assertEquals("He said \"no\"", decodeProjectArgument("He said \"no\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("C:\\Temp", decodeProjectArgument("C:\\Temp")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("TestConfiguration", decodeProjectArgument("TestConfiguration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads back the project name from the snippet the preamble tells Workmate to run.
     * Goes straight at the preamble: reaching it through {@code execute} would need an OPEN
     * EDT project, which a headless test has no way to provide.
     */
    private static String decodeProjectArgument(String projectName)
    {
        String preamble = AskWorkmateTool.mcpBridgePreamble(projectName);
        int start = preamble.indexOf("\"{"); //$NON-NLS-1$
        assertTrue("no arguments literal in: " + preamble, start >= 0); //$NON-NLS-1$
        int end = preamble.indexOf("}\"", start); //$NON-NLS-1$
        assertTrue("unterminated arguments literal in: " + preamble, end > start); //$NON-NLS-1$

        // Level 1: the Java string literal the snippet contains.
        String javaLiteralBody = preamble.substring(start + 1, end + 1);
        String json = javaLiteralBody.replace("\\\"", "\"").replace("\\\\", "\\"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Level 2: the JSON arguments object itself.
        return JsonParser.parseString(json).getAsJsonObject()
            .get("projectName").getAsString(); //$NON-NLS-1$
    }

    /**
     * Workmate may delegate a sub-question to this tool, and a parent job holds a worker
     * while its child needs one, so the pool has to be bounded rather than trusted.
     */
    @Test
    public void testConcurrentJobsAreBoundedWithAnActionableRefusal() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AskWorkmateTool tool = tool(blockingGateway(entered, release,
            new WorkmateResponse("late", null))); //$NON-NLS-1$
        try
        {
            // A job counts from the moment it is accepted, whether it got a worker or is
            // queued behind one - which is the point: the pool is what must not fill up.
            for (int i = 0; i < AskWorkmateTool.MAX_CONCURRENT_JOBS; i++)
            {
                Map<String, String> params = params("question", "q" + i); //$NON-NLS-1$ //$NON-NLS-2$
                params.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
                assertJobStatus(tool.execute(params), "running"); //$NON-NLS-1$
            }

            Map<String, String> overflow = params("question", "one too many"); //$NON-NLS-1$ //$NON-NLS-2$
            overflow.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
            assertErrorContains(tool.execute(overflow), "jobs running", //$NON-NLS-1$ //$NON-NLS-2$
                "Poll the jobId", "instead of nesting deeper"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testUnsupportedModeIsActionable()
    {
        AskWorkmateTool tool = tool(stubReturning("unused", null)); //$NON-NLS-1$
        Map<String, String> params = params("question", "q"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("mode", "tool"); //$NON-NLS-1$ //$NON-NLS-2$
        // 'tool' is the tempting wrong guess: the direct tool mode is selected by the
        // workmateTool parameter, so the error has to say exactly that.
        assertErrorContains(tool.execute(params), "Unsupported mode 'tool'", //$NON-NLS-1$ //$NON-NLS-2$
            "'answer'", "'chat'", "pass workmateTool instead"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testChatModeHandsOffAndSaysTheAnswerStaysInTheChatPanel()
    {
        AtomicReference<String> pushed = new AtomicReference<>();
        String result = tool(chatGateway(pushed)).execute(modeParams("chat", "find the usages")); //$NON-NLS-1$ //$NON-NLS-2$

        assertJobStatus(result, "done"); //$NON-NLS-1$
        assertEquals("find the usages", pushed.get()); //$NON-NLS-1$
        // The handoff answer must not read like a real answer to the question.
        assertContains(result, "chat panel", "mode='answer'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The chat reads the project's own .workmate rules, so it does not need the preamble and
     * must not be handed a wall of instructions by default - but a project without those rules
     * has no other way to learn about the bridge, so an explicit request still works.
     */
    @Test
    public void testChatModeLeavesOutTheBridgePreambleUnlessItIsAskedFor()
    {
        AtomicReference<String> pushed = new AtomicReference<>();
        Map<String, String> params = modeParams("chat", "Which catalogs exist?"); //$NON-NLS-1$ //$NON-NLS-2$
        tool(chatGateway(pushed)).execute(params);
        assertEquals("Which catalogs exist?", pushed.get()); //$NON-NLS-1$

        params.put("shareMcpTools", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        tool(chatGateway(pushed)).execute(params);
        assertTrue(pushed.get().contains("edt.mcp.bridge=v1")); //$NON-NLS-1$
        assertTrue(pushed.get().endsWith("Question:\nWhich catalogs exist?")); //$NON-NLS-1$
    }

    /**
     * Once the chat hand-off is claimed the question CANNOT be taken back. Reporting the
     * budget as a failure there would invite a retry, and the retry would ask Workmate the
     * same question a second time - so the job waits for the real outcome instead.
     */
    @Test
    public void testCommittedChatHandoffOutlivingItsBudgetIsNotReportedAsFailed() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AskWorkmateTool tool = tool(new WorkmateGateway()
        {
            @Override
            public void pushToChat(IProject project, String question, ProgressListener progress)
                throws GatewayException
            {
                assertTrue(progress.onTryCommit());
                entered.countDown();
                try
                {
                    release.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                progress.onProgress("Delivered the question to the Workmate chat view."); //$NON-NLS-1$
            }
        });

        Map<String, String> start = modeParams("chat", "find the usages"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        start.put("timeoutSeconds", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String jobId = extractJobId(tool.execute(start));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        String afterBudget = pollLatestJob(jobId, 2);
        assertJobStatus(afterBudget, "running"); //$NON-NLS-1$
        assertContains(afterBudget, "expired after the request was already handed over"); //$NON-NLS-1$

        release.countDown();
        assertJobStatus(pollLatestJob(jobId, 5), "done"); //$NON-NLS-1$
    }

    @Test
    public void testChatModeFailureIsReportedAsAFailedJob()
    {
        AskWorkmateTool tool = tool(new WorkmateGateway()
        {
            @Override
            public void pushToChat(IProject project, String question, ProgressListener progress)
                throws GatewayException
            {
                throw GatewayException.notReady("the chat view is not open yet"); //$NON-NLS-1$
            }
        });
        assertJobFailedContains(tool.execute(modeParams("chat", "q")), //$NON-NLS-1$ //$NON-NLS-2$
            "the chat view is not open yet"); //$NON-NLS-1$
    }

    @Test
    public void testWorkmateToolModePassesNameAndArgumentsThroughUntouched()
    {
        AtomicReference<String> receivedName = new AtomicReference<>();
        AtomicReference<String> receivedArgs = new AtomicReference<>();
        Map<String, String> params = params("workmateTool", "JShellSession"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("workmateArgs", "{\"scope\":\"eclipse\"}"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool(toolGateway(receivedName, receivedArgs, "session-1")) //$NON-NLS-1$
            .execute(params);

        assertJobStatus(result, "done"); //$NON-NLS-1$
        assertEquals("JShellSession", receivedName.get()); //$NON-NLS-1$
        assertEquals("{\"scope\":\"eclipse\"}", receivedArgs.get()); //$NON-NLS-1$
        assertTrue(result.contains("## Answer\n\nsession-1")); //$NON-NLS-1$
    }

    @Test
    public void testWorkmateToolModeNeedsNoArgumentsAndNoQuestion()
    {
        AtomicReference<String> receivedName = new AtomicReference<>();
        AtomicReference<String> receivedArgs = new AtomicReference<>();
        String result = tool(toolGateway(receivedName, receivedArgs, "catalogue")) //$NON-NLS-1$
            .execute(params("workmateTool", "JShellManual")); //$NON-NLS-1$ //$NON-NLS-2$

        assertJobStatus(result, "done"); //$NON-NLS-1$
        assertEquals("JShellManual", receivedName.get()); //$NON-NLS-1$
        assertNull(receivedArgs.get());
    }

    @Test
    public void testBlankWorkmateToolIsActionable()
    {
        AskWorkmateTool tool = tool(stubReturning("unused", null)); //$NON-NLS-1$
        assertErrorContains(tool.execute(params("workmateTool", "   ")), //$NON-NLS-1$ //$NON-NLS-2$
            "workmateTool must name a Workmate tool", "JShellSession"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testWorkmateToolFailureIsReportedAsAFailedJob()
    {
        AskWorkmateTool tool = tool(new WorkmateGateway()
        {
            @Override
            public String callWorkmateTool(String toolName, String argsJson, long timeoutMillis,
                ProgressListener progress) throws GatewayException
            {
                throw GatewayException.callFailed("JShell rejected the manual_ids"); //$NON-NLS-1$
            }
        });
        assertJobFailedContains(tool.execute(params("workmateTool", "JShell")), //$NON-NLS-1$ //$NON-NLS-2$
            "JShell rejected the manual_ids"); //$NON-NLS-1$
    }

    /** Minimal registered tool, so the preamble has a real catalogue to render. */
    private static final class NamedProbeTool implements IMcpTool
    {
        private final String name;

        private NamedProbeTool(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "Probe for the preamble catalogue"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\",\"properties\":{}}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public ResponseType getResponseType()
        {
            return ResponseType.TEXT;
        }
    }

    private static Map<String, String> modeParams(String mode, String question)
    {
        Map<String, String> params = params("question", question); //$NON-NLS-1$
        params.put("mode", mode); //$NON-NLS-1$
        return params;
    }

    private static WorkmateGateway questionCapturingGateway(AtomicReference<String> sent)
    {
        return new WorkmateGateway()
        {
            @Override
            public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
                String skillName, long timeoutMillis, ProgressListener progress)
            {
                reportRealisticProgress(progress);
                sent.set(question);
                return new WorkmateResponse("ok", null); //$NON-NLS-1$
            }
        };
    }

    private static WorkmateGateway chatGateway(AtomicReference<String> pushed)
    {
        return new WorkmateGateway()
        {
            @Override
            public void pushToChat(IProject project, String question, ProgressListener progress)
            {
                progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$
                pushed.set(question);
                progress.onProgress("Handed the question to the Workmate chat."); //$NON-NLS-1$
            }
        };
    }

    private static WorkmateGateway toolGateway(AtomicReference<String> name,
        AtomicReference<String> args, String answer)
    {
        return new WorkmateGateway()
        {
            @Override
            public String callWorkmateTool(String toolName, String argsJson, long timeoutMillis,
                ProgressListener progress)
            {
                progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$
                name.set(toolName);
                args.set(argsJson);
                progress.onProgress("Ran the Workmate tool."); //$NON-NLS-1$
                return answer;
            }
        };
    }

    private AskWorkmateTool tool(WorkmateGateway gateway)
    {
        BackgroundJobs registry = new BackgroundJobs(20, 2);
        registries.add(registry);
        return new AskWorkmateTool(gateway, registry);
    }

    private String pollLatestJob(String jobId, int waitSeconds)
    {
        BackgroundJobs registry = registries.get(registries.size() - 1);
        Map<String, String> params = params("jobId", jobId); //$NON-NLS-1$
        params.put("waitSeconds", Integer.toString(waitSeconds)); //$NON-NLS-1$
        return new GetJobStatusTool(registry).execute(params);
    }

    private String executeWithFailure(GatewayException failure)
    {
        return tool(stubThrowing(failure)).execute(params("question", "q")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static WorkmateGateway stubReturning(String text, String reasoning)
    {
        return stubReturningResponse(new WorkmateResponse(text, reasoning));
    }

    private static WorkmateGateway stubReturningResponse(WorkmateResponse response)
    {
        return new WorkmateGateway()
        {
            @Override
            public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
                String skillName, long timeoutMillis, ProgressListener progress)
            {
                reportRealisticProgress(progress);
                return response;
            }
        };
    }

    private static WorkmateGateway stubThrowing(GatewayException failure)
    {
        return new WorkmateGateway()
        {
            @Override
            public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
                String skillName, long timeoutMillis, ProgressListener progress)
                throws GatewayException
            {
                throw failure;
            }
        };
    }

    private static WorkmateGateway blockingGateway(CountDownLatch entered,
        CountDownLatch release, WorkmateResponse response)
    {
        return new WorkmateGateway()
        {
            @Override
            public WorkmateResponse ask(IProject project, String question, Integer maxToolRounds,
                String skillName, long timeoutMillis, ProgressListener progress)
                throws GatewayException
            {
                progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$
                progress.onProgress("Obtained the Workmate conversation facade."); //$NON-NLS-1$
                progress.onProgress("Sent the request to Workmate."); //$NON-NLS-1$
                entered.countDown();
                try
                {
                    release.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw GatewayException.callFailed("the waiting thread was interrupted"); //$NON-NLS-1$
                }
                progress.onProgress("Received the Workmate response."); //$NON-NLS-1$
                return response;
            }
        };
    }

    private static void reportRealisticProgress(ProgressListener progress)
    {
        progress.onProgress("Located the 1C:Workmate plugin."); //$NON-NLS-1$
        progress.onProgress("Obtained the Workmate conversation facade."); //$NON-NLS-1$
        progress.onProgress("Sent the request to Workmate."); //$NON-NLS-1$
        progress.onProgress("Received the Workmate response."); //$NON-NLS-1$
    }

    private static Map<String, String> params(String key, String value)
    {
        Map<String, String> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private static String extractJobId(String markdown)
    {
        Matcher matcher = JOB_ID_ROW.matcher(markdown);
        assertTrue("Expected a jobId row in: " + markdown, matcher.find()); //$NON-NLS-1$
        return matcher.group(1).trim();
    }

    private static void assertJobStatus(String markdown, String status)
    {
        assertNotNull(markdown);
        assertTrue("Expected status '" + status + "' in: " + markdown, //$NON-NLS-1$ //$NON-NLS-2$
            markdown.startsWith("# Background job: " + status)); //$NON-NLS-1$
        assertTrue(markdown.contains("| status | " + status + " |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(markdown.contains("| elapsed |")); //$NON-NLS-1$
        assertTrue(markdown.contains("## Progress")); //$NON-NLS-1$
    }

    private static void assertJobFailedContains(String markdown, String... fragments)
    {
        assertJobStatus(markdown, "failed"); //$NON-NLS-1$
        assertTrue(markdown.contains("## Error")); //$NON-NLS-1$
        assertContains(markdown, fragments);
    }

    private static void assertContains(String value, String... fragments)
    {
        for (String fragment : fragments)
        {
            assertTrue("Expected text to contain '" + fragment + "': " + value, //$NON-NLS-1$ //$NON-NLS-2$
                value.contains(fragment));
        }
    }

    private static void assertErrorContains(String json, String... fragments)
    {
        assertNotNull(json);
        assertTrue(json.contains("\"success\":false")); //$NON-NLS-1$
        assertContains(json, fragments);
        assertFalse("An actionable error must not leak a stack trace: " + json, //$NON-NLS-1$
            json.contains("\tat ")); //$NON-NLS-1$
    }
}

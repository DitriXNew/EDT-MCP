/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for the post-write disk-export barrier in {@link AbstractMetadataWriteTool} (issues #406 and
 * #408).
 * <p>
 * The behaviour under test is the DECISION: a metadata write whose {@code .mdo} export has not
 * reached disk must not answer "done", because the two files a top-object change touches are
 * exported as independent tasks, so the working tree passes through a state where the configuration
 * references an object whose file is already gone.
 * <p>
 * Since #408 the barrier no longer re-derives WHERE the write went from the response; the call
 * states it through a {@link WriteScope} while it runs. These tests therefore drive
 * {@link AbstractMetadataWriteTool#awaitDiskExport} with an explicit scope - {@code execute} would
 * marshal onto the SWT UI thread, which no headless test has. The export environment is stubbed
 * through the package-visible seam, which is also what lets the false-refusal cases be asserted at
 * all: "the wait could not observe anything" has to be distinguishable from "the wait observed a
 * pending export".
 */
public class AbstractMetadataWriteToolTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String EXTENSION = "TestConfiguration.tests"; //$NON-NLS-1$

    /** Records what the barrier asked about, in order, so a wait on the WRONG project is visible. */
    private static final class RecordingEnvironment implements AbstractMetadataWriteTool.IExportEnvironment
    {
        private final DiskExportState answer;
        final List<String> asked = new ArrayList<>();
        String askedFor;
        long deadlineMs;
        int calls;

        RecordingEnvironment(DiskExportState answer)
        {
            this.answer = answer;
        }

        @Override
        public DiskExportState waitForDiskExport(String projectName, long timeoutMs)
        {
            asked.add(projectName);
            askedFor = projectName;
            deadlineMs = timeoutMs;
            calls++;
            return answer;
        }
    }

    /** A minimal concrete write tool; only the barrier's inputs matter here. */
    private static class StubTool extends AbstractMetadataWriteTool
    {
        private final RecordingEnvironment environment;

        Boolean sawDrainEstablished;

        StubTool(RecordingEnvironment environment)
        {
            this.environment = environment;
        }

        @Override
        protected IExportEnvironment exportEnvironment()
        {
            return environment;
        }

        @Override
        protected String refreshAfterExportAwait(Map<String, String> params, String result,
            boolean drainEstablished)
        {
            sawDrainEstablished = Boolean.valueOf(drainEstablished);
            return result;
        }

        @Override
        public String getName()
        {
            return "stub_write_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "stub"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        protected String executeOnUiThread(Map<String, String> params)
        {
            return ToolResult.success().toJson();
        }
    }

    private static Map<String, String> params(String projectName)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", projectName); //$NON-NLS-1$
        return params;
    }

    private static String successJson()
    {
        return ToolResult.success().put("action", "executed").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A scope that said nothing at all - the compatibility case. */
    private static WriteScope silent()
    {
        return new WriteScope();
    }

    private static WriteScope wroteIn(String... projectNames)
    {
        WriteScope scope = new WriteScope();
        for (String projectName : projectNames)
        {
            scope.wrote(projectName);
        }
        return scope;
    }

    /** @return the {@code writtenProjects} member, or {@code null} when it is absent */
    private static List<String> publishedProjects(String json)
    {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!object.has(WriteScope.RESULT_MEMBER))
        {
            return null;
        }
        List<String> projects = new ArrayList<>();
        JsonArray array = object.getAsJsonArray(WriteScope.RESULT_MEMBER);
        for (int i = 0; i < array.size(); i++)
        {
            projects.add(array.get(i).getAsString());
        }
        return projects;
    }

    @Test
    public void testPendingExportTurnsASuccessIntoAnActionableRefusal()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String answer =
            new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), wroteIn(PROJECT));

        assertTrue("a pending export must not be reported as success", //$NON-NLS-1$
            answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the pending-export refusal follows a committed model write", //$NON-NLS-1$
            answer.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
        // The caller's next move depends on knowing nothing was undone - a refusal that let them
        // assume a rollback would be worse than the raw success it replaced.
        assertTrue("the refusal must say nothing was rolled back: " + answer, //$NON-NLS-1$
            answer.contains("Nothing was rolled back")); //$NON-NLS-1$
        assertTrue("the refusal must warn against committing the tree: " + answer, //$NON-NLS-1$
            answer.contains("Do not commit")); //$NON-NLS-1$
        assertTrue("the refusal must name the project: " + answer, answer.contains(PROJECT)); //$NON-NLS-1$
        assertTrue("the refusal must name a way forward: " + answer, //$NON-NLS-1$
            answer.contains("resync_to_disk")); //$NON-NLS-1$
    }

    @Test
    public void testDrainedExportReturnsTheToolsOwnPayloadPlusItsWriteScope()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        String result = successJson();

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), result, wroteIn(PROJECT));

        assertTrue("a drained export must not rewrite the tool's own members: " + answer, //$NON-NLS-1$
            answer.contains("\"action\":\"executed\"") && answer.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Collections.singletonList(PROJECT), publishedProjects(answer));
        assertEquals(1, environment.calls);
    }

    @Test
    public void testTheWaitIsGivenABoundedDeadlineAndTheRefusalQuotesTheSameOne()
    {
        // Two things at once: the barrier must not be handed an unbounded wait, and the number it
        // waits for must be the number its refusal names - a message quoting a deadline the code
        // does not use is how an operator gets sent to look in the wrong place.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String answer =
            new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), wroteIn(PROJECT));

        // Close to the declared 60s, not merely "finite": an unbounded or wildly large deadline
        // would pass a `> 0` check while defeating the unattended-safety reason the bound exists.
        // Not an exact equality, because the budget is shared across the awaited set and is handed
        // out as time REMAINING - the clock can advance a millisecond before the first hand-out.
        assertTrue("the export wait must get very nearly the declared 60s deadline, got " //$NON-NLS-1$
            + environment.deadlineMs, environment.deadlineMs > 59_000L && environment.deadlineMs <= 60_000L);
        assertTrue("the refusal must quote the deadline the barrier actually used: " + answer, //$NON-NLS-1$
            answer.contains("60s")); //$NON-NLS-1$
    }

    @Test
    public void testUnobservableExportDoesNotRefuse()
    {
        // The guard against a false refusal: "no derived-data service / not a DT project" is not
        // evidence that anything is pending, and refusing on it would break healthy callers. This
        // is why the seam is tri-state and not a boolean.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.UNOBSERVABLE);

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), wroteIn(PROJECT));

        assertTrue("an unobservable export state must not produce a refusal: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
    }

    @Test
    public void testAnErrorAfterARecordedWriteIsMarkedAndNeverWaitedOn()
    {
        // An error is a well-formed JSON object too. Waiting on one would spend the whole deadline
        // on a call that wrote nothing, and then re-report a rejected argument as a disk problem.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = ToolResult.error("Node not found: Catalog.Nope").toJson(); //$NON-NLS-1$

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), result, wroteIn(PROJECT));
        assertTrue(answer.contains("\"mutationCommitted\":true")); //$NON-NLS-1$
        assertTrue(answer.contains("Node not found: Catalog.Nope")); //$NON-NLS-1$
        assertEquals("an error result must not reach the export wait", 0, environment.calls); //$NON-NLS-1$
    }

    @Test
    public void testACallThatStatesItQueuedNothingIsNeverWaitedOn()
    {
        // A preview has no export of its own; making it wait would only let unrelated background
        // export work refuse it.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        WriteScope scope = new WriteScope();
        scope.queuedNothing();

        StubTool tool = new StubTool(environment);
        String answer = tool.awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals("a preview must not reach the export wait", 0, environment.calls); //$NON-NLS-1$
        // "Nothing was queued" is ESTABLISHED, not unknown: the post-wait step is entitled to run
        // work that only makes sense once the queue is behind it.
        assertEquals(Boolean.TRUE, tool.sawDrainEstablished);
        // ... and the caller is told so with an empty list, which is a finding.
        assertEquals(Collections.emptyList(), publishedProjects(answer));
    }

    @Test
    public void testSayingNothingAtAllIsNotTheSameAsSayingNothingWasQueued()
    {
        // The distinction the whole contract turns on. A tool that never states its scope is
        // UNKNOWN, and unknown keeps the pre-#408 behaviour - wait for the project it was asked
        // about - because assuming either extreme silently changes what a tool does.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), silent());

        assertEquals("a silent call must still be waited for, as before #408", 1, environment.calls); //$NON-NLS-1$
        assertEquals(PROJECT, environment.askedFor);
        assertNull("an unknown scope must publish NOTHING - an empty list would claim a finding " //$NON-NLS-1$
            + "the tool never made: " + answer, publishedProjects(answer)); //$NON-NLS-1$
    }

    @Test
    public void testAnActualWriteBeatsAnEarlierStatementThatNothingWasQueued()
    {
        // resync_to_disk really is written this way: it reports "already in sync, nothing to
        // export" and only THEN has its dangling-reference cleanup re-export Configuration.mdo. If
        // the first statement won, that export would never be waited for.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        WriteScope scope = new WriteScope();
        scope.queuedNothing();
        scope.wrote(PROJECT);

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals(1, environment.calls);
        assertEquals(Collections.singletonList(PROJECT), publishedProjects(answer));
    }

    @Test
    public void testTheWaitFollowsTheProjectTheToolActuallyWroteTo()
    {
        // adopt_metadata_object takes the BASE configuration by contract and writes into the
        // EXTENSION. A barrier keyed on projectName would wait for a project with nothing queued
        // and pass while the real target is still exporting.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);

        String answer =
            new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), wroteIn(EXTENSION));

        assertEquals("the barrier must wait for the project that was written, not the one asked for", //$NON-NLS-1$
            EXTENSION, environment.askedFor);
        assertEquals(Collections.singletonList(EXTENSION), publishedProjects(answer));
    }

    @Test
    public void testACascadeParticipantIsWaitedForButCanNeverRefuse()
    {
        // The delete cascade: EDT's refactoring cleans the references held by dependent extensions,
        // we never submitted anything there, and the set is "every open extension of the target" -
        // what EDT SCANS. Awaiting it under the strict grade would let an unrelated wedged export in
        // an untouched extension fail a healthy delete, which is why the grade exists.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        WriteScope scope = new WriteScope();
        scope.cascadedInto(EXTENSION);

        StubTool tool = new StubTool(environment);
        String answer = tool.awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals("the participant must still be waited for", 1, environment.calls); //$NON-NLS-1$
        assertEquals(EXTENSION, environment.askedFor);
        assertTrue("a stall in a project we never submitted to must not refuse the call: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals("but it must not be reported as established either", Boolean.FALSE, //$NON-NLS-1$
            tool.sawDrainEstablished);
        // "The platform MAY have written here" must not be published under a name that says "wrote".
        assertEquals(Collections.emptyList(), publishedProjects(answer));
    }

    @Test
    public void testWrittenProjectsAreWaitedForBeforeCascadeParticipants()
    {
        // One budget covers the whole set, so the order decides who gets it: the projects a stall
        // can actually be blamed on must be settled before the ones it cannot.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        WriteScope scope = new WriteScope();
        scope.cascadedInto(EXTENSION);
        scope.wrote(PROJECT);

        new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals(Arrays.asList(PROJECT, EXTENSION), environment.asked);
    }

    @Test
    public void testAProjectBothWrittenAndCascadedIntoIsWaitedForOnceUnderTheStrongerGrade()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        WriteScope scope = new WriteScope();
        scope.wrote(PROJECT);
        scope.cascadedInto(PROJECT);

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals("one project, one wait", 1, environment.calls); //$NON-NLS-1$
        assertTrue("and the grade that can refuse must win: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testAWaitIsNotStartedOnASliceTooSmallToBeWorthIt()
    {
        // A slice too small to drain anything is not a cheap wait but a harmful one: the platform
        // wait it starts cannot be cancelled, while the per-project claim that keeps a SECOND
        // un-cancellable wait from being scheduled lapses after 3x its own timeout. So a leftover
        // fraction of the shared budget must buy nothing rather than buy that. Before #408 the loop
        // clamped the remainder to 1ms and started the wait anyway.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        StubTool tool = new StubTool(environment)
        {
            @Override
            protected long exportDeadlineMs()
            {
                return 900L;
            }
        };

        String answer = tool.awaitDiskExport(params(PROJECT), successJson(), wroteIn(PROJECT));

        assertEquals("a slice below the floor must not start a wait at all", 0, environment.calls); //$NON-NLS-1$
        assertTrue("and not starting a wait is not a refusal: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals("but nothing was established about that project either", Boolean.FALSE, //$NON-NLS-1$
            tool.sawDrainEstablished);
    }

    @Test
    public void testTheFloorDoesNotFireOnAFullBudget()
    {
        // The other half of the guard: with the real budget every declared project is still asked,
        // so the floor cannot quietly turn the barrier off.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);

        new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), wroteIn(PROJECT, EXTENSION));

        assertEquals(Arrays.asList(PROJECT, EXTENSION), environment.asked);
    }

    @Test
    public void testAnUnreadableResultIsNotTurnedIntoARefusal()
    {
        // A payload we cannot parse is not evidence of a disk problem, and the mutation already
        // happened: degrade to "do not gate", never to a refusal built on a guess.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = "not json at all"; //$NON-NLS-1$

        assertSame(result, new StubTool(environment).awaitDiskExport(params(PROJECT), result, wroteIn(PROJECT)));
        assertEquals(0, environment.calls);
        assertNull(environment.askedFor);
    }

    @Test
    public void testAMissingProjectNameSkipsTheWaitInsteadOfRefusing()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = successJson();

        assertSame(result, new StubTool(environment).awaitDiskExport(new HashMap<>(), result, silent()));
        assertEquals(0, environment.calls);
    }

    @Test
    public void testAnUndeterminableScopeWaitsItsFallbackAndPublishesNothing()
    {
        // apply_quick_fix: EDT's fix extension point reports nothing about what the fix touched, so
        // the classification stays - but it must not reach the caller as a claim about writes.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        WriteScope scope = new WriteScope();
        scope.undeterminable("the platform does not say", Collections.singletonList(PROJECT)); //$NON-NLS-1$

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals(1, environment.calls);
        assertEquals(PROJECT, environment.askedFor);
        assertNull("'I could not tell' must not be published as 'I wrote nowhere': " + answer, //$NON-NLS-1$
            publishedProjects(answer));
    }

    @Test
    public void testAnUndeterminableScopeWithAnEmptyFallbackSkipsTheWait()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        WriteScope scope = new WriteScope();
        scope.undeterminable("a module fix queues no .mdo export", Collections.<String> emptyList()); //$NON-NLS-1$

        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), scope);

        assertEquals("the classification must be honoured, not the projectName default", 0, //$NON-NLS-1$
            environment.calls);
        assertNull(publishedProjects(answer));
    }

    @Test
    public void testThePublishedListIsSortedAndDeduplicated()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);

        String answer = new StubTool(environment)
            .awaitDiskExport(params(PROJECT), successJson(), wroteIn(EXTENSION, PROJECT, EXTENSION));

        assertEquals(Arrays.asList(PROJECT, EXTENSION), publishedProjects(answer));
    }

    @Test
    public void testARefusalCarriesNoWriteScope()
    {
        // The member describes a successful call's writes; a refusal is not one, and bolting the
        // list onto it would suggest the refusal is a report about those projects.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);

        String answer =
            new StubTool(environment).awaitDiskExport(params(PROJECT), successJson(), wroteIn(PROJECT));

        assertNull(publishedProjects(answer));
    }

    /** Overrides ONLY what the base class leaves abstract, plus the seam - nothing else. */
    private static final class InheritedDefaultsTool extends AbstractMetadataWriteTool
    {
        private final RecordingEnvironment environment;

        InheritedDefaultsTool(RecordingEnvironment environment)
        {
            this.environment = environment;
        }

        @Override
        protected IExportEnvironment exportEnvironment()
        {
            return environment;
        }

        @Override
        public String getName()
        {
            return "inherited_defaults_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "stub"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        protected String executeOnUiThread(Map<String, String> params)
        {
            return ToolResult.success().toJson();
        }
    }

    @Test
    public void testAWriteToolThatOverridesNothingStillGetsTheBarrier()
    {
        // The reason the barrier lives in the base class rather than at the ~34 export call sites:
        // a tool added later inherits it without doing anything. This pins the default that makes
        // that true - a call that states nothing is still waited for, on the project it was asked
        // about, exactly as before #408.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        InheritedDefaultsTool plain = new InheritedDefaultsTool(environment);

        String answer = plain.awaitDiskExport(params(PROJECT), successJson(), silent());

        assertFalse("a tool that overrides nothing must still refuse on a pending export", //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals("the inherited default must consult the export wait exactly once", 1, //$NON-NLS-1$
            environment.calls);
        assertEquals("the inherited default must wait for projectName", PROJECT, //$NON-NLS-1$
            environment.askedFor);
    }
}

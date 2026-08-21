/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.PotentialMergeProblemDescription;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonNodeRenderer;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonScopeBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Contract tests for {@link GetComparisonNodeTool}, driven through a stub read port - no EDT
 * comparison engine is started anywhere here.
 *
 * <p>Two of these tests are the reason the tool has a wait loop at all. The comparison tree is
 * built LAZILY, so a node the engine has not reached reads back with no children; a tool that
 * rendered that state would report "no differences" for a subtree nobody compared. The tool
 * therefore asks the engine to prioritize the node and waits on THAT NODE's status, and when the
 * wait expires it says so. Both halves are pinned: dropping the prioritize call fails
 * {@link #testLazyNodeIsPrioritizedAndThenWaitedOn}, and dropping the re-read of the status fails
 * it too, because the render would still carry the not-finished notice.</p>
 */
public class GetComparisonNodeToolTest
{
    /** {@code \u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.\u0422\u043e\u0432\u0430\u0440\u044b.\u0424\u043e\u0440\u043c\u0430.\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430} - a DEEP Russian FQN, written in escapes. */
    private static final String RUSSIAN_FORM_FQN =
        "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a" //$NON-NLS-1$
            + ".\u0422\u043e\u0432\u0430\u0440\u044b" //$NON-NLS-1$
            + ".\u0424\u043e\u0440\u043c\u0430" //$NON-NLS-1$
            + ".\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430"; //$NON-NLS-1$

    /** {@code \u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f} - the Russian spelling of the configuration root. */
    private static final String RUSSIAN_CONFIGURATION =
        "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f"; //$NON-NLS-1$

    /** The all-English symlink the engine matches against: only the STRUCTURAL segments translate. */
    private static final String CANONICAL_FORM_SYMLINK =
        "Catalog.\u0422\u043e\u0432\u0430\u0440\u044b" //$NON-NLS-1$
            + ".Form.\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430"; //$NON-NLS-1$

    // ==================== Tool surface ====================

    @Test
    public void testToolIdentity()
    {
        GetComparisonNodeTool tool = new GetComparisonNodeTool(new StubSource());
        assertEquals("get_comparison_node", tool.getName()); //$NON-NLS-1$
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testSchemaDeclaresEveryAddressingParameter()
    {
        JsonObject schema = JsonParser.parseString(new GetComparisonNodeTool(new StubSource())
            .getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        for (String key : Arrays.asList("comparisonId", "objectFqn", "nodeId", "side", "depth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "limit", "waitSeconds")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("inputSchema must declare '" + key + "'", properties.has(key)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ==================== Addressing ====================

    @Test
    public void testUnknownComparisonIsRefusedAndNamesTheLiveOnes()
    {
        StubSource source = new StubSource();
        source.known.add("cmp-7"); //$NON-NLS-1$

        String result = call(source, args("comparisonId", "cmp-404", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products")); //$NON-NLS-1$

        assertError(result);
        assertTrue("the refusal must name the id the caller passed: " + result, //$NON-NLS-1$
            result.contains("cmp-404")); //$NON-NLS-1$
        assertTrue("and the ids that ARE live, so the caller can recover: " + result, //$NON-NLS-1$
            result.contains("cmp-7")); //$NON-NLS-1$
        assertTrue("and the tool that starts one: " + result, //$NON-NLS-1$
            result.contains("compare_configurations")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownObjectFqnNamesTheAddressThatMissed()
    {
        StubSource source = knownSource();
        source.node = null;

        // waitSeconds=0: with a FINISHED tree there is nothing to wait for, and the budget is
        // spent before the first retry - the shape of a real expiry without making the test sleep.
        String result = call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Nonexistent", "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertError(result);
        assertTrue("the refusal must name the FQN that matched nothing: " + result, //$NON-NLS-1$
            result.contains("Catalog.Nonexistent")); //$NON-NLS-1$
    }

    /**
     * The comparison engine matches an ALL-ENGLISH symlink and has no bilingual branch, so a deep
     * Russian FQN must arrive already translated in EVERY structural segment. A first-segment-only
     * normalisation would ask for {@code Catalog.\u0422\u043e\u0432\u0430\u0440\u044b.\u0424\u043e\u0440\u043c\u0430.\u0424\u043e\u0440\u043c\u0430\u042d\u043b\u0435\u043c\u0435\u043d\u0442\u0430}, which matches nothing
     * at all - silently, because a lookup that finds no node is not an error on the engine's side.
     */
    @Test
    public void testDeepRussianFqnIsCanonicalisedBeforeTheLookup()
    {
        StubSource source = knownSource();

        call(source, args("comparisonId", "cmp-1", "objectFqn", RUSSIAN_FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("every STRUCTURAL segment must be English and every Name kept verbatim", //$NON-NLS-1$
            CANONICAL_FORM_SYMLINK, source.requestedSymlink);
    }

    @Test
    public void testEnglishFqnIsPassedThroughUnchanged()
    {
        StubSource source = knownSource();

        call(source, args("comparisonId", "cmp-1", "objectFqn", "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("Catalog.Products.Form.ItemForm", source.requestedSymlink); //$NON-NLS-1$
    }

    /**
     * The configuration root is the one address that is NOT a metadata type, so the shared metadata
     * canonicaliser copies its Russian spelling through verbatim. Scoping and expanding therefore
     * have to share one entry point, or a comparison scoped with the Russian root token cannot be
     * expanded by the very spelling that scoped it.
     */
    @Test
    public void testTheConfigurationRootResolvesTheSameWayAsItScopes()
    {
        StubSource source = knownSource();

        call(source, args("comparisonId", "cmp-1", "objectFqn", RUSSIAN_CONFIGURATION)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the engine's root symlink is English", "Configuration", //$NON-NLS-1$ //$NON-NLS-2$
            source.requestedSymlink);
        assertEquals("and it must be the SAME spelling compare_configurations scopes with", //$NON-NLS-1$
            ComparisonScopeBuilder.build(Collections.singletonList(RUSSIAN_CONFIGURATION))
                .symlinks().get(0),
            source.requestedSymlink);
    }

    @Test
    public void testNeitherAddressIsRefusedNamingBothWays()
    {
        String result = call(knownSource(), args("comparisonId", "cmp-1")); //$NON-NLS-1$ //$NON-NLS-2$

        assertError(result);
        assertTrue(result.contains("objectFqn")); //$NON-NLS-1$
        assertTrue(result.contains("nodeId")); //$NON-NLS-1$
    }

    @Test
    public void testBothAddressesAreRefusedRatherThanGuessed()
    {
        String result = call(knownSource(), args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertError(result);
        assertTrue("the refusal must show both addresses it was given: " + result, //$NON-NLS-1$
            result.contains("Catalog.Products") && result.contains("42")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNodeIdAddressesTheNodeDirectly()
    {
        StubSource source = knownSource();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse("addressing by node id is a happy path: " + result, isError(result)); //$NON-NLS-1$
        assertEquals(Long.valueOf(42L), source.requestedNodeId);
    }

    @Test
    public void testNonNumericNodeIdIsRefused()
    {
        String result = call(knownSource(), args("comparisonId", "cmp-1", "nodeId", "abc")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        assertTrue(result.contains("abc")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownSideIsRefusedNamingTheAcceptedOnes()
    {
        String result = call(knownSource(), args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products", "side", "sideways")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertError(result);
        assertTrue(result.contains("sideways")); //$NON-NLS-1$
        assertTrue(result.contains("main")); //$NON-NLS-1$
        assertTrue(result.contains("other")); //$NON-NLS-1$
        assertTrue(result.contains("ancestor")); //$NON-NLS-1$
    }

    @Test
    public void testSideIsPassedThroughToTheLookup()
    {
        StubSource source = knownSource();

        call(source, args("comparisonId", "cmp-1", "objectFqn", "Catalog.Products", "side", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "ancestor")); //$NON-NLS-1$

        assertEquals(ComparisonSide.COMMON_ANCESTOR, source.requestedSide);
    }

    // ==================== The lazy tree ====================

    /**
     * The load-bearing test: an unfinished node is prioritized and then WAITED ON, and the render
     * reflects the status the wait actually observed.
     */
    @Test
    public void testLazyNodeIsPrioritizedAndThenWaitedOn()
    {
        StubSource source = knownSource();
        source.statuses.addAll(Arrays.asList(ComparisonNodeStatus.UNFINISHED,
            ComparisonNodeStatus.UNFINISHED, ComparisonNodeStatus.FINISHED));

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("the unfinished node must be raised with the engine, exactly once", //$NON-NLS-1$
            Collections.singletonList(Long.valueOf(42L)), source.prioritized);
        assertTrue("the status must be re-read until it settles, not read once", //$NON-NLS-1$
            source.statusCalls >= 3);
        assertFalse("having WAITED until FINISHED, the answer must not claim it is unfinished: " //$NON-NLS-1$
            + result, result.contains(ComparisonNodeRenderer.NOT_FINISHED_NOTICE));
    }

    @Test
    public void testFinishedNodeIsNotPrioritized()
    {
        // The control for the test above: a node that is already finished needs no nudge, so
        // "prioritize was called" there is a statement about the UNFINISHED branch, not about the
        // tool calling prioritize unconditionally.
        StubSource source = knownSource();
        source.statuses.add(ComparisonNodeStatus.FINISHED);

        call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue("a finished node must not be re-prioritized", source.prioritized.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testExpiredWaitReportsUnfinishedAndNeverNoDifferences()
    {
        StubSource source = knownSource();
        source.statuses.add(ComparisonNodeStatus.UNFINISHED);

        // waitSeconds=0: the budget is spent before the first poll, which is the shape of a real
        // expiry without making the test sleep.
        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an expired wait must SAY the subtree is unfinished: " + result, //$NON-NLS-1$
            result.contains(ComparisonNodeRenderer.NOT_FINISHED_NOTICE));
        assertFalse("and must never pass an uncompared subtree off as identical: " + result, //$NON-NLS-1$
            result.toLowerCase().contains("no differences")); //$NON-NLS-1$
    }

    /**
     * The tree is lazy in a second way: a node the engine has not built is not "unfinished", it is
     * ABSENT, so the address resolves to nothing at all. Answering "the object may not exist" to
     * that is a verdict about the caller's address that nothing observed supports - and it is the
     * answer a caller gets by expanding an in-scope object right after the launch.
     */
    @Test
    public void testANodeTheEngineHasNotBuiltYetIsNotCalledNonexistent()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatus = ComparisonNodeStatus.UNFINISHED;

        String result = call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products", "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertError(result);
        String message = errorMessage(result);
        assertTrue("the refusal must say the tree is still being built: " + message, //$NON-NLS-1$
            message.contains("still being built")); //$NON-NLS-1$
        assertTrue("and must name the two facts it is keeping apart: " + message, //$NON-NLS-1$
            message.contains("not compared yet")); //$NON-NLS-1$
        assertFalse("it must NOT tell the caller the object may not exist: " + message, //$NON-NLS-1$
            message.contains("may not exist on that side")); //$NON-NLS-1$
        assertTrue("and must name the knob that fixes it: " + message, //$NON-NLS-1$
            message.contains("waitSeconds")); //$NON-NLS-1$
    }

    /**
     * The control for the test above, and the reason the distinction is worth making: on a FINISHED
     * tree an address that matches nothing really is an address that matches nothing, and the
     * refusal says so plainly rather than sending the caller off to wait.
     */
    @Test
    public void testOnAFinishedTreeAnAddressThatMatchesNothingIsStillCalledAbsent()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatus = ComparisonNodeStatus.FINISHED;

        String message = errorMessage(call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Nonexistent", "waitSeconds", "0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a finished tree gives a definitive answer: " + message, //$NON-NLS-1$
            message.contains("may not exist on that side")); //$NON-NLS-1$
        assertFalse("and must not blame a tree that has finished: " + message, //$NON-NLS-1$
            message.contains("still being built")); //$NON-NLS-1$
    }

    /**
     * The address is RETRIED while the budget lasts, so a node that surfaces a moment after the
     * call arrives is expanded rather than refused.
     */
    @Test
    public void testTheAddressIsRetriedUntilTheNodeSurfaces()
    {
        StubSource source = knownSource();
        source.treeStatus = ComparisonNodeStatus.UNFINISHED;
        // Invisible to the first two lookups, then there: the engine reached it while we waited.
        source.nodeVisibleAfterLookups = 2;

        String result = call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products", "waitSeconds", "5")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("a node that surfaced within the budget must be expanded: " + result, //$NON-NLS-1$
            isError(result));
        assertTrue("the lookup must have been retried, not asked once", source.lookups > 1); //$NON-NLS-1$
    }

    // ==================== The read boundary ====================

    /**
     * The status the DOCUMENT is built from is read inside the boundary that renders it, not
     * carried over from the wait.
     * <p>
     * Here the wait observes {@code Finished} and the render's own read observes {@code Unfinished}
     * - which is what EDT reports once it has begun comparing the subtree again. Rendering from the
     * wait's snapshot printed "No differences" over a tree that was being rebuilt; rendering from
     * the reading taken beside the node says the subtree is not finished.
     */
    @Test
    public void testTheDocumentIsBuiltFromTheStatusReadBesideTheNode()
    {
        StubSource source = knownSource();
        source.statuses.addAll(Arrays.asList(ComparisonNodeStatus.FINISHED,
            ComparisonNodeStatus.UNFINISHED));

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("the wait settled at once, so exactly two status reads happened", 2, //$NON-NLS-1$
            source.statusCalls);
        assertTrue("the render must report what IT read, not what the wait had seen: " + result, //$NON-NLS-1$
            result.contains(ComparisonNodeRenderer.NOT_FINISHED_NOTICE));
        assertFalse("and must never pass a subtree being rebuilt off as identical: " + result, //$NON-NLS-1$
            result.toLowerCase().contains("no differences")); //$NON-NLS-1$
    }

    /**
     * The node status is a MODEL read - the platform resolves the id against the comparison's own
     * BM store and reads a feature off the node it finds - so it belongs inside the comparison's
     * read boundary, exactly like every other node read here (CLAUDE.md don't #1). The wait loop
     * performs one such read per poll, and reading them outside the boundary would put more than a
     * hundred unbounded reads in a single call.
     */
    @Test
    public void testEveryNodeStatusIsReadInsideTheComparisonReadBoundary()
    {
        StubSource source = knownSource();
        source.statuses.addAll(Arrays.asList(ComparisonNodeStatus.UNFINISHED,
            ComparisonNodeStatus.UNFINISHED, ComparisonNodeStatus.FINISHED));

        call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("the wait must actually have polled - otherwise this test proves nothing", 4, //$NON-NLS-1$
            source.statusCalls);
        // The counting pin: one boundary to locate the node, one per status poll, one to render -
        // and the render reads the status AGAIN inside its own boundary, which is why the poll
        // count is one higher than the number of polls. A status read lifted OUT of the boundary -
        // which is what this call used to do, 125 times per call at worst - drops the middle term.
        assertEquals("each status poll must open its own read boundary", //$NON-NLS-1$
            source.statusCalls + 1, source.reads);
        assertEquals("and none of them may be observed with no boundary open", 0, //$NON-NLS-1$
            source.statusReadsOutsideTheBoundary);
    }

    /**
     * The per-read comparison context is RELEASED when the read ends. It is not the read boundary's
     * transaction: the context factory the reader uses sets only a data-source context and never a
     * comparison transaction, so closing it releases the per-side data-source readers and cannot
     * touch the transaction the boundary owns. Leaving it open stranded those readers on every
     * expand call.
     */
    @Test
    public void testTheComparisonContextIsReleasedOncePerRead()
    {
        AtomicInteger released = new AtomicInteger();

        String answer = GetComparisonNodeTool.runThenRelease(new StubAccess(new StubSource()),
            access -> "read", released::incrementAndGet); //$NON-NLS-1$

        assertEquals("read", answer); //$NON-NLS-1$
        assertEquals("the context must be released exactly once", 1, released.get()); //$NON-NLS-1$
    }

    /**
     * And released when the task THROWS, which is the case a plain "close it afterwards" misses:
     * a node that the platform refuses to resolve is the ordinary way this read ends badly.
     */
    @Test
    public void testTheComparisonContextIsReleasedWhenTheTaskThrows()
    {
        AtomicInteger released = new AtomicInteger();
        RuntimeException thrown = new IllegalStateException("node is gone"); //$NON-NLS-1$

        try
        {
            GetComparisonNodeTool.runThenRelease(new StubAccess(new StubSource()), access -> {
                throw thrown;
            }, released::incrementAndGet);
            fail("the failure must reach the caller, not be swallowed by the release"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertSame(thrown, e);
        }
        assertEquals("a failed read must still release its context, exactly once", 1, //$NON-NLS-1$
            released.get());
    }

    // ==================== Platform failures ====================

    /**
     * A platform exception thrown with NO message must still produce a readable refusal. EMF and BM
     * throw such exceptions routinely, and a refusal built from {@code getMessage()} renders the
     * literal "Could not expand the comparison node: null." - the same defect class this repository
     * fixed in {@code update_database}. The shared {@code ComparisonFailures.describe} names the
     * exception type instead, so the reply says what happened and never carries a bare "null" or a
     * sentence that stops at its own colon.
     */
    @Test
    public void testMessagelessPlatformFailureIsDescribedRatherThanRenderedAsNull()
    {
        StubSource source = knownSource();
        source.readFailure = new IllegalStateException();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        String message = errorMessage(result);
        assertFalse("a message-less failure must never render the literal 'null': " + message, //$NON-NLS-1$
            message.contains("null")); //$NON-NLS-1$
        assertFalse("nor leave the sentence stopping at its own colon: " + message, //$NON-NLS-1$
            message.contains(": .")); //$NON-NLS-1$
        assertTrue("the refusal must name what the platform threw: " + message, //$NON-NLS-1$
            message.contains("IllegalStateException")); //$NON-NLS-1$
        assertTrue("and still tell the caller how to recover: " + message, //$NON-NLS-1$
            message.contains("compare_configurations")); //$NON-NLS-1$
    }

    /**
     * The control for the test above: a failure that DOES carry text keeps that text, so the fix is
     * "describe the failure" and not "hide the failure behind a fixed sentence".
     */
    @Test
    public void testPlatformFailureWithTextKeepsThatText()
    {
        StubSource source = knownSource();
        source.readFailure = new IllegalStateException("comparison store was closed"); //$NON-NLS-1$

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        assertTrue("the platform's own words must survive: " + result, //$NON-NLS-1$
            errorMessage(result).contains("comparison store was closed")); //$NON-NLS-1$
    }

    @Test
    public void testOutOfRangeWaitSecondsIsRefused()
    {
        String result = call(knownSource(), args("comparisonId", "cmp-1", "nodeId", "42", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "waitSeconds", "600")); //$NON-NLS-1$ //$NON-NLS-2$

        assertError(result);
        assertTrue(result.contains("waitSeconds")); //$NON-NLS-1$
    }

    // ==================== nodeId is an id, not a number ====================

    @Test
    public void testScientificNotationIsRefusedRatherThanResolvedToANode()
    {
        // "1e3" is not a node id anybody printed. Parsing it as a number would silently address
        // node 1000 - a node that plausibly exists, so the caller would read the wrong node and
        // never learn that the id they sent was not understood.
        String result = call(knownSource(), args("comparisonId", "cmp-1", "nodeId", "1e3")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        assertTrue(errorMessage(result), errorMessage(result).contains("whole number")); //$NON-NLS-1$
    }

    @Test
    public void testAnIdBeyondExactDoublePrecisionReachesTheEngineUnchanged()
    {
        // 2^53 + 1 is a perfectly good long and a perfectly good BM id - it is only DOUBLE that
        // cannot hold it. Parsing through double silently rounded it to 9007199254740992, which is
        // itself a plausible id, so the tool expanded a NEIGHBOURING node while reporting success.
        // Read back off the refusal, which quotes the id the tool actually looked for: the stub
        // records only the LAST lookup, and the render path looks the resolved node up again.
        StubSource source = knownSource();
        source.node = null;

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "9007199254740993", "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertError(result);
        String message = errorMessage(result);
        assertTrue("the id must be used exactly as it was printed: " + message, //$NON-NLS-1$
            message.contains("9007199254740993")); //$NON-NLS-1$
        assertFalse("and must never be rounded to a neighbouring id: " + message, //$NON-NLS-1$
            message.contains("9007199254740992")); //$NON-NLS-1$
    }

    @Test
    public void testAFractionalIdIsRefused()
    {
        assertError(call(knownSource(), args("comparisonId", "cmp-1", "nodeId", "12.5"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testATrailingPointZeroIsStillTheSameId()
    {
        // A client that renders a JSON number as "42.0" means id 42, and that stays accepted.
        StubSource source = knownSource();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42.0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(result, isError(result));
        assertEquals(Long.valueOf(42L), source.requestedNodeId);
    }

    // ==================== Helpers ====================

    private static String call(StubSource source, Map<String, String> params)
    {
        return new GetComparisonNodeTool(source).execute(params);
    }

    private static Map<String, String> args(String... keyValues)
    {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2)
        {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private static StubSource knownSource()
    {
        StubSource source = new StubSource();
        source.known.add("cmp-1"); //$NON-NLS-1$
        TopComparisonNode node = mock(TopComparisonNode.class);
        when(node.bmGetId()).thenReturn(Long.valueOf(42L));
        source.node = node;
        return source;
    }

    private static boolean isError(String result)
    {
        try
        {
            JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();
            return parsed.has("success") && !parsed.get("success").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            // A successful call renders Markdown, which is not JSON at all.
            return false;
        }
    }

    private static void assertError(String result)
    {
        assertTrue("expected a structured error, got: " + result, isError(result)); //$NON-NLS-1$
    }

    /** The caller-facing sentence of a structured error, isolated from the JSON envelope. */
    private static String errorMessage(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    /** Records what the tool asked the engine for, and answers from a scripted fixture. */
    private static final class StubSource
        implements GetComparisonNodeTool.NodeSource
    {
        private final List<String> known = new ArrayList<>();
        private final List<Long> prioritized = new ArrayList<>();
        private final List<ComparisonNodeStatus> statuses = new ArrayList<>();
        private ComparisonNode node;
        private ComparisonNodeStatus treeStatus = ComparisonNodeStatus.FINISHED;
        private int nodeVisibleAfterLookups;
        private int lookups;
        private RuntimeException readFailure;
        private String requestedSymlink;
        private ComparisonSide requestedSide;
        private Long requestedNodeId;
        private int statusCalls;
        private int statusReadsOutsideTheBoundary;
        private int reads;
        private boolean insideRead;

        @Override
        public boolean isKnown(String comparisonId)
        {
            return known.contains(comparisonId);
        }

        @Override
        public List<String> knownComparisonIds()
        {
            return known;
        }

        @Override
        public void prioritize(String comparisonId, List<Long> nodeIds)
        {
            prioritized.addAll(nodeIds);
        }

        @Override
        public <T> T read(String comparisonId, GetComparisonNodeTool.ReadTask<T> task)
        {
            if (readFailure != null)
            {
                throw readFailure;
            }
            reads++;
            insideRead = true;
            try
            {
                return task.run(new StubAccess(this));
            }
            finally
            {
                insideRead = false;
            }
        }

        /** The node, once it has been looked for often enough to have "surfaced". */
        ComparisonNode visibleNode()
        {
            lookups++;
            return lookups > nodeVisibleAfterLookups ? node : null;
        }

        /** Answers the scripted status, recording whether the read boundary was open at the time. */
        ComparisonNodeStatus nextStatus()
        {
            statusCalls++;
            if (!insideRead)
            {
                statusReadsOutsideTheBoundary++;
            }
            if (statuses.isEmpty())
            {
                return ComparisonNodeStatus.FINISHED;
            }
            return statuses.get(Math.min(statusCalls - 1, statuses.size() - 1));
        }
    }

    /** The in-boundary lookups, answering from the {@link StubSource}'s fixture. */
    private static final class StubAccess
        implements GetComparisonNodeTool.TreeAccess
    {
        private final StubSource source;

        StubAccess(StubSource source)
        {
            this.source = source;
        }

        @Override
        public ComparisonNode topNode(String symlink, ComparisonSide side)
        {
            source.requestedSymlink = symlink;
            source.requestedSide = side;
            return source.visibleNode();
        }

        @Override
        public ComparisonNode node(long nodeId)
        {
            source.requestedNodeId = Long.valueOf(nodeId);
            return source.visibleNode();
        }

        @Override
        public ComparisonNodeStatus topNodeStatus(long topNodeId)
        {
            return source.nextStatus();
        }

        @Override
        public ComparisonNodeStatus treeStatus()
        {
            // Deliberately NOT routed through nextStatus(): the tree's own status is a different
            // question from the addressed node's, and folding them together would make the
            // boundary-counting pins above measure something other than the wait.
            return source.treeStatus;
        }

        @Override
        public IComparedObjects<?> comparedObjects(ComparisonNode node)
        {
            return null;
        }

        @Override
        public List<PotentialMergeProblemDescription> potentialProblems(long nodeId)
        {
            return Collections.emptyList();
        }
    }
}

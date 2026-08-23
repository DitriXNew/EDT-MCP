/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import com.ditrix.edt.mcp.server.utils.compare.ComparisonFailures;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonNodeRenderer;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonScopeBuilder;
import com.ditrix.edt.mcp.server.utils.compare.PlatformAnswer;
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

    /**
     * The defect: an address that resolves to nothing in a FINISHED tree is an ordinary way to
     * call this tool - a mistyped FQN, a node id from another comparison, an object outside the
     * scope - and the loop retried it every 200 ms until the whole of {@code waitSeconds} was
     * gone, to produce the answer it already had on the first look. A finished tree builds no
     * further nodes, so nothing about the answer could change.
     */
    @Test
    public void testAMissingAddressInAFinishedTreeIsSettledByOneLook()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatus = ComparisonNodeStatus.FINISHED;

        call(source, args("comparisonId", "cmp-1", "objectFqn", "Catalog.Nonexistent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "waitSeconds", "5")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a finished tree cannot produce the node later, so looking again is waiting " //$NON-NLS-1$
            + "for nothing", 1, source.lookups); //$NON-NLS-1$
    }

    /** Its own literal: stopping early must not change WHAT is answered, only how long it takes. */
    @Test
    public void testAMissingAddressInAFinishedTreeStillGetsTheAbsentAnswer()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatus = ComparisonNodeStatus.FINISHED;

        String message = errorMessage(call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Nonexistent", "waitSeconds", "5"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a finished tree gives a definitive answer: " + message, //$NON-NLS-1$
            message.contains("may not exist on that side")); //$NON-NLS-1$
    }

    /**
     * The control, and the distinction the fix must not blur: "the tree is finished" and "this
     * node is not built yet" are different readings. A node that never surfaces inside an
     * UNFINISHED tree is still waited for - that wait is the whole reason the retry exists, and a
     * fix that ended the loop on any miss would have deleted it.
     */
    @Test
    public void testAMissingAddressInAnUnfinishedTreeIsStillWaitedFor()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatus = ComparisonNodeStatus.UNFINISHED;

        call(source, args("comparisonId", "cmp-1", "objectFqn", "Catalog.Products", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "waitSeconds", "1")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a tree still being built may yet produce the node: " + source.lookups, //$NON-NLS-1$
            source.lookups > 1);
    }

    /**
     * The defect: {@code attemptLocate} read the absence and the tree status in ONE boundary, and
     * then only the absence left the method - so the refusal opened a SECOND boundary and asked
     * the tree status again. The verdict, which is the whole point of the pair, was therefore
     * assembled out of two instants.
     *
     * <p>The counterexample, and it is ordinary rather than exotic: the last look sees "no node,
     * tree UNFINISHED", the engine then builds that very node and finishes the tree, and the
     * second read sees FINISHED. The caller was told the object does not exist - about a node that
     * by then does. Answering from the snapshot says "still being built", which is what was
     * actually observed.</p>
     */
    @Test
    public void testATreeThatFinishesWhileTheRefusalIsWordedDoesNotTurnItIntoAnAbsence()
    {
        StubSource source = knownSource();
        source.node = null;
        // First boundary: not built yet. Second boundary, if anyone opens one: finished.
        source.treeStatuses.add(ComparisonNodeStatus.UNFINISHED);
        source.treeStatuses.add(ComparisonNodeStatus.FINISHED);

        String message = errorMessage(call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products", "waitSeconds", "0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the refusal must report the tree the look actually saw: " + message, //$NON-NLS-1$
            message.contains("still being built")); //$NON-NLS-1$
        assertFalse("a node absent from an unfinished tree is not a node that does not exist: " //$NON-NLS-1$
            + message, message.contains("may not exist on that side")); //$NON-NLS-1$
    }

    /**
     * Its own literal, and the pin that makes the one above impossible to satisfy by luck: the
     * whole refusal costs exactly ONE read boundary. Counting lookups cannot see this - a second
     * {@code source.read} that asks only for the tree status performs no lookup at all - which is
     * how the second boundary lived beside a lookup pin that stayed green.
     */
    @Test
    public void testAnAddressThatResolvedToNothingIsJudgedInsideOneBoundary()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatus = ComparisonNodeStatus.FINISHED;

        call(source, args("comparisonId", "cmp-1", "objectFqn", "Catalog.Nonexistent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the absence and the tree status are one observation, so the verdict they " //$NON-NLS-1$
            + "produce must cost one boundary", 1, source.reads); //$NON-NLS-1$
        assertEquals("and the tree status must be read once, inside it", 1, //$NON-NLS-1$
            source.treeStatusCalls);
    }

    /**
     * The control: on a tree that really is FINISHED the answer is unchanged. Without it the two
     * pins above would also be passed by a refusal that had simply stopped saying "does not exist".
     */
    @Test
    public void testASnapshotOfAFinishedTreeStillGivesTheDefinitiveAnswer()
    {
        StubSource source = knownSource();
        source.node = null;
        source.treeStatuses.add(ComparisonNodeStatus.FINISHED);

        String message = errorMessage(call(source, args("comparisonId", "cmp-1", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Nonexistent", "waitSeconds", "0"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a finished tree gives a definitive answer: " + message, //$NON-NLS-1$
            message.contains("may not exist on that side")); //$NON-NLS-1$
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

    // ============ "could not ask EDT" is not "EDT no longer knows this comparison" ============

    /**
     * The two readings have opposite remedies - wait and read the SAME comparison again, or start
     * a new one - and the port used to fold them together with an {@code orElse(null)} on the
     * view. The caller was then told their comparison had been ended outside this server because
     * EDT's comparison service happened to be unregistered for an instant, while the lease was
     * still held, the nodeIds still resolved and it still held EDT's single slot.
     */
    @Test
    public void testAServiceGapIsReportedAsRetryableAndNotAsALostComparison()
    {
        StubSource source = knownSource();
        source.readFailure = new GetComparisonNodeTool.ComparisonUnreadableException(
            ComparisonFailures.readUnavailable("cmp-1")); //$NON-NLS-1$

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        String message = errorMessage(result);
        assertTrue("the refusal must say the comparison is still registered: " + message, //$NON-NLS-1$
            message.contains("still registered")); //$NON-NLS-1$
        assertFalse("and must NOT claim it was ended outside this server: " + message, //$NON-NLS-1$
            message.contains("ended outside this server")); //$NON-NLS-1$
    }

    /** The control: EDT's own answer that it no longer knows the handle keeps saying so. */
    @Test
    public void testEdtSayingItNoLongerHoldsTheComparisonIsReportedAsThat()
    {
        StubSource source = knownSource();
        source.readFailure = new GetComparisonNodeTool.ComparisonUnreadableException(
            ComparisonFailures.sessionGone("cmp-1")); //$NON-NLS-1$

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        String message = errorMessage(result);
        assertTrue("EDT's own answer must be reported as itself: " + message, //$NON-NLS-1$
            message.contains("ended outside this server")); //$NON-NLS-1$
    }

    /**
     * A worded refusal is published as it is. The generic failure branch appends "Check the
     * comparison is still alive ... or start a new one", which is exactly the wrong advice for the
     * retryable reading, so it must not be reached.
     */
    @Test
    public void testAWordedRefusalIsNotWrappedInTheGenericFailureSentence()
    {
        StubSource source = knownSource();
        source.readFailure = new GetComparisonNodeTool.ComparisonUnreadableException(
            ComparisonFailures.readUnavailable("cmp-1")); //$NON-NLS-1$

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse("the generic wrapper must not be applied: " + result, //$NON-NLS-1$
            errorMessage(result).contains("Could not expand the comparison node")); //$NON-NLS-1$
    }

    /**
     * The decision itself, over the three answers the platform can give. Pinned on the shared
     * function rather than on either tool: both tools that read a tree now ask it, and it is the
     * place where "could not ask" stopped being rendered as "no longer there".
     */
    @Test
    public void testTheUnreadableTreeDecisionKeepsTheThreeAnswersApart()
    {
        assertNull("an answered, non-null view is a readable tree", //$NON-NLS-1$
            ComparisonFailures.unreadableTree(PlatformAnswer.of("a tree"), "cmp-1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an unavailable answer is the retryable refusal", //$NON-NLS-1$
            ComparisonFailures.unreadableTree(PlatformAnswer.unavailable(), "cmp-1") //$NON-NLS-1$
                .toJson().contains("still registered")); //$NON-NLS-1$
        assertTrue("an answered null is EDT saying it no longer holds it", //$NON-NLS-1$
            ComparisonFailures.unreadableTree(PlatformAnswer.of(null), "cmp-1") //$NON-NLS-1$
                .toJson().contains("ended outside this server")); //$NON-NLS-1$
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

    /**
     * This pinned the OPPOSITE rule until its premise was measured and found false.
     * <p>
     * It refused scientific notation because "1e3 is not a node id anybody printed". Something
     * does print ids that way, and it is our own transport: Gson renders every JSON number through
     * {@code Double.toString()}, so a client sending the JSON integer 4294967296 - exactly what the
     * schema asks for and exactly what the report hands out - delivers "4.294967296E9" to the tool.
     * The rule therefore made every node id at or above 10^7 unaddressable, and said so in a
     * message naming a string the caller had never written. Verified live on the stand.
     * <p>
     * The honest rule keeps the refusal that has a reason behind it (see the two tests below on
     * fractional values and on ids past 2^53) and drops the one that only fought the transport.
     * JSON has no separate integer type, so "1e3" IS a spelling of 1000 and reading it as node 1000
     * is correct rather than a guess.
     */
    @Test
    public void testScientificNotationResolvesToTheNumberItSpells()
    {
        StubSource source = knownSource();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "1e3")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(result, isError(result));
        assertEquals(Long.valueOf(1000L), source.requestedNodeId);
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
        /** What EDT answers about its single slot; unavailable when it cannot be asked at all. */
        private PlatformAnswer<Boolean> edtActive = PlatformAnswer.of(Boolean.FALSE);
        private final List<Long> prioritized = new ArrayList<>();
        private final List<ComparisonNodeStatus> statuses = new ArrayList<>();
        private ComparisonNode node;
        private ComparisonNodeStatus treeStatus = ComparisonNodeStatus.FINISHED;
        /** Tree statuses answered in order, the last one repeating; empty falls back to the field. */
        private final List<ComparisonNodeStatus> treeStatuses = new ArrayList<>();
        private int treeStatusCalls;
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
        public PlatformAnswer<Boolean> edtHasActiveComparison()
        {
            return edtActive;
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

        /**
         * The TREE's status, answered from a script so it can differ between two boundaries - which
         * is the only way to reproduce a node that appears while a refusal is being worded.
         *
         * @return the status this reading sees
         */
        ComparisonNodeStatus nextTreeStatus()
        {
            treeStatusCalls++;
            if (treeStatuses.isEmpty())
            {
                return treeStatus;
            }
            return treeStatuses.get(Math.min(treeStatusCalls - 1, treeStatuses.size() - 1));
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
            if (source.requestedNodeId == null)
            {
                // The FIRST lookup, which is the one the caller's id produced. The tool looks the
                // node up again by its own bmGetId() afterwards, and recording that instead would
                // report the fixture's id back to every test no matter what was asked for.
                source.requestedNodeId = Long.valueOf(nodeId);
            }
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
            return source.nextTreeStatus();
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

    // ============ An empty local list is not "nothing is running" ============

    /**
     * The defect: the refusal rendered an empty list of LOCAL comparison ids as "none is running
     * right now" - a claim about EDT that this list cannot support. A comparison started from the
     * workbench holds EDT's single slot under no id of ours and never appears in it, and the very
     * same sentence came out when the platform had not been asked at all.
     */
    @Test
    public void testAnUnknownIdDoesNotClaimNothingIsRunningWhenEdtSaysOtherwise()
    {
        StubSource source = new StubSource();
        source.edtActive = PlatformAnswer.of(Boolean.TRUE);

        String result = call(source, args("comparisonId", "cmp-404", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products")); //$NON-NLS-1$

        assertError(result);
        assertFalse("EDT reports a comparison running, so 'none is running' is false: " + result, //$NON-NLS-1$
            result.contains("none is running")); //$NON-NLS-1$
        assertTrue(result, result.contains("no comparison started through this server is " //$NON-NLS-1$
            + "registered")); //$NON-NLS-1$
        assertTrue("the slot IS taken, and only EDT can end what took it: " + result, //$NON-NLS-1$
            result.contains("started outside this server")); //$NON-NLS-1$
    }

    /** A platform that could not be asked is a third case, and is not reported as a "no". */
    @Test
    public void testAnUnknownIdSaysSoWhenEdtCouldNotBeAskedAtAll()
    {
        StubSource source = new StubSource();
        source.edtActive = PlatformAnswer.unavailable();

        String result = call(source, args("comparisonId", "cmp-404", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products")); //$NON-NLS-1$

        assertError(result);
        assertFalse(result, result.contains("none is running")); //$NON-NLS-1$
        assertTrue(result, result.contains("could not be asked")); //$NON-NLS-1$
    }

    /**
     * The control: when EDT itself answers that nothing is running, the refusal may say so. Without
     * this the two tests above would be satisfied by a tool that had stopped mentioning EDT.
     */
    @Test
    public void testAnUnknownIdMaySayNothingIsRunningWhenEdtAnsweredThat()
    {
        StubSource source = new StubSource();
        source.edtActive = PlatformAnswer.of(Boolean.FALSE);

        String result = call(source, args("comparisonId", "cmp-404", "objectFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Products")); //$NON-NLS-1$

        assertError(result);
        assertTrue(result, result.contains("EDT reports none running")); //$NON-NLS-1$
    }

    // ============ A node id survives the wire's own rendering of a JSON number ============

    /**
     * The defect, measured live: the report hands out node ids like 4294967296, a client sends it
     * as the JSON integer the schema asks for, Gson renders every JSON number through
     * {@code Double.toString()} - and the tool was handed {@code "4.294967296E9"} and refused it as
     * "not a whole number". The caller had typed the right number; the refusal named a string it
     * had never written and could do nothing about. Every id at or above 10^7 was unaddressable.
     */
    @Test
    public void testANodeIdRenderedAsAJsonNumberIsStillTheSameNode()
    {
        StubSource source = knownSource();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "4.294967296E9")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse("a node id the report itself hands out must be addressable: " + result, //$NON-NLS-1$
            isError(result));
        assertEquals("and it must address THAT node, not a neighbour", //$NON-NLS-1$
            Long.valueOf(4294967296L), source.requestedNodeId);
    }

    /** The small end of the same rendering: Gson writes the id 1 as "1.0". */
    @Test
    public void testASmallNodeIdRenderedWithADecimalPointIsStillTheSameNode()
    {
        StubSource source = knownSource();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "1.0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(result, isError(result));
        assertEquals(Long.valueOf(1L), source.requestedNodeId);
    }

    /**
     * The refusal that MUST survive: past 2^53 the double that carried the number could not hold it,
     * so the digits handed to us may name a neighbouring node that is itself perfectly plausible.
     * The true id is already lost, and expanding its neighbour would answer a question nobody asked.
     */
    @Test
    public void testANodeIdTooLargeForTheDoubleThatCarriedItIsRefusedRatherThanRounded()
    {
        StubSource source = knownSource();

        String result = call(source, args("comparisonId", "cmp-1", "nodeId", "9.007199254740994E15")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertError(result);
        assertNull("nothing may be looked up on a rounded id", source.requestedNodeId); //$NON-NLS-1$
    }

    /** And the controls: a fractional id and a non-number are still not ids. */
    @Test
    public void testAFractionalOrNonNumericNodeIdIsStillRefused()
    {
        StubSource fractional = knownSource();
        assertError(call(fractional, args("comparisonId", "cmp-1", "nodeId", "12.5"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(fractional.requestedNodeId);

        StubSource text = knownSource();
        assertError(call(text, args("comparisonId", "cmp-1", "nodeId", "abc"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(text.requestedNodeId);
    }
}

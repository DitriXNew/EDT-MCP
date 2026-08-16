/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.Diagnostics;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.Edge;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.Expansion;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.LevelExpander;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.Node;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.NodeFlag;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal.Result;

/**
 * Tests for {@link CallGraphTraversal}.
 * <p>
 * The engine exists precisely so the bounds of a transitive call-hierarchy walk can be proven
 * without a live workbench: every test here drives a fake graph, so the depth bound, the
 * report-once rule, the node budget, the time budget and the completeness accounting are each
 * observable in isolation and each fail if their mechanism is removed.
 * <p>
 * The cycle tests deliberately separate two bounds that are easy to conflate. The DEPTH bound is
 * what ends the walk - a mutation that removes report-once still terminates. What report-once buys
 * is that each method appears ONCE: without it, a cyclic or re-converging graph re-emits the same
 * nodes level after level and the result grows with the number of PATHS. The JUnit timeouts stay as
 * a backstop for an unbounded-loop regression, but the assertions - not the clock - are what pin
 * the behaviour.
 */
public class CallGraphTraversalTest
{
    /** Never expires - the default for tests that are not about the time budget. */
    private static final BooleanSupplier NEVER_EXPIRES = () -> false;

    // ==================== depth bound ====================

    @Test
    public void testDepthBoundStopsAtRequestedLevel()
    {
        // a -> b -> c -> d, asked for 2 levels: b and c are reported, d is never discovered.
        Result result = walk(chain("root", "b", "c", "d"), 2, 100); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(keys("b", "c"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, levelOf(result, "b")); //$NON-NLS-1$
        assertEquals(2, levelOf(result, "c")); //$NON-NLS-1$
    }

    @Test
    public void testNodeAtDepthBoundIsFlaggedSoTheAgentKnowsMoreExists()
    {
        Result result = walk(chain("root", "b", "c", "d"), 2, 100); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull("a node that WAS expanded carries no flag", flagOf(result, "b")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the boundary node says its own callers were not looked for", //$NON-NLS-1$
            NodeFlag.DEPTH_LIMIT, flagOf(result, "c")); //$NON-NLS-1$
    }

    @Test
    public void testReachingTheDepthBoundDoesNotMakeTheResultIncomplete()
    {
        // The caller ASKED for 2 levels. Answering exactly that is not a truncation, and calling it
        // one would train an agent to ignore the word "incomplete" where it does matter (budget/time).
        Result result = walk(chain("root", "b", "c", "d"), 2, 100); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(result.isComplete());
    }

    @Test
    public void testExhaustingTheGraphBeforeTheDepthBoundLeavesNoFlags()
    {
        Result result = walk(chain("root", "b"), 5, 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(keys("b"), keysOf(result)); //$NON-NLS-1$
        assertNull(flagOf(result, "b")); //$NON-NLS-1$
        assertTrue(result.isComplete());
    }

    // ==================== report-once / cycles ====================

    @Test(timeout = 10_000)
    public void testTwoNodeCycleTerminatesAndReportsEachMethodOnce()
    {
        // root -> b -> root. This one is caught by the ROOT rule (the root is reportable but never
        // expandable), not by report-once - it survives a mutation that disables report-once, and
        // testLongerCycleTerminates is the test that does not.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("b")); //$NON-NLS-1$ //$NON-NLS-2$
        graph.put("b", Arrays.asList("root")); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = walk(graph, 5, 100);
        assertEquals(keys("b", "root"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the root re-reached is reported once, never expanded again", //$NON-NLS-1$
            NodeFlag.RECURSIVE, flagOf(result, "root")); //$NON-NLS-1$
    }

    @Test(timeout = 10_000)
    public void testLongerCycleTerminates()
    {
        // root -> b -> c -> d -> b: the cycle does NOT pass through the root, so the root rule
        // cannot collapse it - only report-once can. Disabling report-once makes this walk re-emit
        // b and c on later levels, which is exactly what the key assertion below catches.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("b")); //$NON-NLS-1$ //$NON-NLS-2$
        graph.put("b", Arrays.asList("c")); //$NON-NLS-1$ //$NON-NLS-2$
        graph.put("c", Arrays.asList("d")); //$NON-NLS-1$ //$NON-NLS-2$
        graph.put("d", Arrays.asList("b")); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = walk(graph, 5, 100);
        assertEquals(keys("b", "c", "d"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the edge back into the cycle is collapsed, and counted", 1, //$NON-NLS-1$
            result.getRepeatEdges());
        assertTrue("collapsing a re-convergence loses nothing, so the result stays complete", //$NON-NLS-1$
            result.isComplete());
    }

    @Test(timeout = 10_000)
    public void testSelfRecursiveRootIsReportedOnceLikeASingleHopSearchWould()
    {
        // A method that calls itself IS one of its own callers, and a single-hop search shows that
        // row. The transitive walk must not lose it just because the walk starts there.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("root")); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = walk(graph, 3, 100);
        assertEquals(keys("root"), keysOf(result)); //$NON-NLS-1$
        assertEquals(NodeFlag.RECURSIVE, flagOf(result, "root")); //$NON-NLS-1$
        assertEquals(1, levelOf(result, "root")); //$NON-NLS-1$
    }

    @Test
    public void testDagReconvergenceReportsTheMethodOnceAndCountsTheRepeatEdge()
    {
        // root -> {b, c}; both b and c call into d. d is one method, reported once.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("b", "c")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        graph.put("b", Arrays.asList("d")); //$NON-NLS-1$ //$NON-NLS-2$
        graph.put("c", Arrays.asList("d")); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = walk(graph, 3, 100);
        assertEquals(keys("b", "c", "d"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, result.getRepeatEdges());
        assertTrue(result.isComplete());
    }

    // ==================== witness chain ====================

    @Test
    public void testParentIndexRebuildsTheChainBackToTheRoot()
    {
        Result result = walk(chain("root", "b", "c"), 3, 100); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Node b = nodeOf(result, "b"); //$NON-NLS-1$
        Node c = nodeOf(result, "c"); //$NON-NLS-1$
        assertEquals("a level-1 node points at the root, which has no row", -1, b.getParentIndex()); //$NON-NLS-1$
        assertEquals("c was reached via b", b.getIndex(), c.getParentIndex()); //$NON-NLS-1$
    }

    @Test
    public void testEmissionOrderFollowsTheExpanderSoTheResultIsReproducible()
    {
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("x", "y", "z")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        Result result = walk(graph, 2, 100);
        assertEquals(keys("x", "y", "z"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(0, nodeOf(result, "x").getIndex()); //$NON-NLS-1$
        assertEquals(2, nodeOf(result, "z").getIndex()); //$NON-NLS-1$
    }

    // ==================== node budget ====================

    @Test
    public void testNodeBudgetStopsDiscovery()
    {
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("a", "b", "c", "d")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        Result result = walk(graph, 3, 2);
        assertEquals(keys("a", "b"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNodeBudgetMakesTheResultIncompleteBecauseTheWalkItselfWasCut()
    {
        // This is the distinction the header has to carry: a budget that cuts the WALK leaves the
        // true size unknown, unlike a limit that only cuts the rendering of a known total.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("a", "b", "c")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        Result result = walk(graph, 3, 2);
        assertTrue(result.isBudgetExhausted());
        assertFalse(result.isComplete());
    }

    @Test
    public void testBudgetReachedExactlyOnTheLastEdgeIsNotReportedAsATruncation()
    {
        // Boundary: the budget is spent but nothing was dropped, so nothing is unknown.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("a", "b")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Result result = walk(graph, 1, 2);
        assertEquals(keys("a", "b"), keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the budget was met, not exceeded", result.isBudgetExhausted()); //$NON-NLS-1$
        assertTrue(result.isComplete());
    }

    @Test
    public void testNodesLeftUnexpandedByTheBudgetSayWhy()
    {
        // Budget 2 lets a and b be discovered at level 1 but leaves nothing for their callers, so
        // the next level never runs: both rows must carry the budget flag rather than look like
        // methods that genuinely have no callers.
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("root", Arrays.asList("a", "b")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        graph.put("a", Arrays.asList("deep1")); //$NON-NLS-1$ //$NON-NLS-2$
        graph.put("b", Arrays.asList("deep2")); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = walk(graph, 3, 2);
        assertEquals(NodeFlag.NODE_BUDGET, flagOf(result, "a")); //$NON-NLS-1$
        assertEquals(NodeFlag.NODE_BUDGET, flagOf(result, "b")); //$NON-NLS-1$
        assertFalse(result.isComplete());
    }

    // ==================== time budget ====================

    @Test
    public void testTimeBudgetStopsTheWalkAndFlagsWhatWasNotExpanded()
    {
        // The clock is injected, so this proves the mechanism without sleeping: expire on the
        // second level check.
        AtomicInteger checks = new AtomicInteger();
        BooleanSupplier expired = () -> checks.incrementAndGet() > 1;

        Result result = CallGraphTraversal.traverse(root(), 5, 100, expired,
            fakeExpander(chain("root", "b", "c", "d"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals(keys("b"), keysOf(result)); //$NON-NLS-1$
        assertEquals(NodeFlag.TIME_LIMIT, flagOf(result, "b")); //$NON-NLS-1$
        assertTrue(result.isTimedOut());
        assertFalse(result.isComplete());
    }

    @Test
    public void testAnExpanderThatGivesUpMidLevelMakesTheResultIncomplete()
    {
        // The expander searched only part of the level. Nothing marks WHERE the missing edges would
        // have been, so the only honest report is "not complete".
        LevelExpander cutShort = (frontier, budget, expired) -> new Expansion(
            Collections.singletonList(new Edge(frontier.get(0), "b", "b", true)), null, true); //$NON-NLS-1$ //$NON-NLS-2$

        Result result = CallGraphTraversal.traverse(root(), 3, 100, NEVER_EXPIRES, cutShort);
        assertTrue(result.isSearchCutShort());
        assertFalse(result.isComplete());
    }

    // ==================== completeness accounting ====================

    @Test
    public void testAnUnreadableFileForcesIncomplete()
    {
        assertFalse("a file that was never read may have held a caller", //$NON-NLS-1$
            withDiagnostics(diag -> diag.addUnreadableFile()).isComplete());
    }

    @Test
    public void testAnUnparsableModuleForcesIncomplete()
    {
        assertFalse(withDiagnostics(diag -> diag.addUnloadableModule()).isComplete());
    }

    @Test
    public void testAFailedEnumerationForcesIncomplete()
    {
        assertFalse(withDiagnostics(diag -> diag.markEnumerationFailed()).isComplete());
    }

    @Test
    public void testAnUnverifiedModuleForcesIncomplete()
    {
        // The module DID load - a parser that recovers from a syntax error still hands back a
        // syntax tree, and a resource with no parse evidence hands back one nobody can vouch for.
        // Either way a call may be missing from the tree the search walks: "loaded" is not
        // "searched", and absence of evidence is not evidence of a clean parse.
        assertFalse(withDiagnostics(diag -> diag.addUnverifiedModule()).isComplete());
    }

    @Test
    public void testDiagnosticsAreAggregatedAcrossLevels()
    {
        // Each level reports its own failures; the result must carry their sum, not the last one.
        LevelExpander failing = (frontier, budget, expired) -> {
            Diagnostics diag = new Diagnostics();
            diag.addUnreadableFile();
            String parent = frontier.get(0).getKey();
            List<Edge> edges = new ArrayList<>();
            if ("root".equals(parent)) //$NON-NLS-1$
            {
                edges.add(new Edge(frontier.get(0), "b", "b", true)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new Expansion(edges, diag, false);
        };

        Result result = CallGraphTraversal.traverse(root(), 2, 100, NEVER_EXPIRES, failing);
        assertEquals(2, result.getDiagnostics().getUnreadableFiles());
        assertFalse(result.isComplete());
    }

    @Test
    public void testEveryDiagnosticKindIsCarriedIndependently()
    {
        // Each counter has to survive the fold on its own: a merge that dropped one kind would
        // silently restore "complete" for exactly the failure it forgot.
        Diagnostics diag = new Diagnostics();
        diag.addUnreadableFile();
        diag.addUnloadableModule();
        diag.addUnverifiedModule();
        diag.markEnumerationFailed();

        Diagnostics folded = new Diagnostics();
        folded.add(diag);
        folded.add(diag);

        assertEquals(2, folded.getUnreadableFiles());
        assertEquals(2, folded.getUnloadableModules());
        assertEquals(2, folded.getUnverifiedModules());
        assertTrue(folded.isEnumerationFailed());
        assertTrue(folded.hasFailures());
    }

    @Test
    public void testACleanWalkIsComplete()
    {
        Result result = walk(chain("root", "b"), 3, 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.getDiagnostics().hasFailures());
        assertTrue(result.isComplete());
    }

    // ==================== unexpandable nodes ====================

    @Test
    public void testAnUnexpandableNodeIsFlaggedAndNeverExpanded()
    {
        // Module-level code has no method name to search for at the next level. It is a real caller
        // (it must be reported) but a dead end (it must not look like one with no callers).
        LevelExpander expander = (frontier, budget, expired) -> {
            if (!"root".equals(frontier.get(0).getKey())) //$NON-NLS-1$
            {
                fail("an unexpandable node must never be handed back to the expander"); //$NON-NLS-1$
            }
            return new Expansion(
                Collections.singletonList(new Edge(frontier.get(0), "top", "top", false)), null, false); //$NON-NLS-1$ //$NON-NLS-2$
        };

        Result result = CallGraphTraversal.traverse(root(), 5, 100, NEVER_EXPIRES, expander);
        assertEquals(keys("top"), keysOf(result)); //$NON-NLS-1$
        assertEquals(NodeFlag.NOT_EXPANDABLE, flagOf(result, "top")); //$NON-NLS-1$
        assertTrue("a dead end is not a truncation", result.isComplete()); //$NON-NLS-1$
    }

    // ==================== argument guards ====================

    @Test
    public void testDepthBelowOneIsRejected()
    {
        try
        {
            CallGraphTraversal.traverse(root(), 0, 10, NEVER_EXPIRES, fakeExpander(chain("root"))); //$NON-NLS-1$
            fail("expected IllegalArgumentException"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage().contains("at least 1")); //$NON-NLS-1$
        }
    }

    @Test
    public void testBudgetBelowOneIsRejected()
    {
        try
        {
            CallGraphTraversal.traverse(root(), 2, 0, NEVER_EXPIRES, fakeExpander(chain("root"))); //$NON-NLS-1$
            fail("expected IllegalArgumentException"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage().contains("at least 1")); //$NON-NLS-1$
        }
    }

    @Test
    public void testNullKeyIsRejected()
    {
        try
        {
            new Node(null, "payload", true); //$NON-NLS-1$
            fail("expected IllegalArgumentException"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(e.getMessage().contains("key")); //$NON-NLS-1$
        }
    }

    // ==================== helpers ====================

    /**
     * Builds the traversal root used by every test.
     *
     * @return a fresh root node keyed {@code "root"}
     */
    private static Node root()
    {
        return new Node("root", "root", true); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs a traversal over a fake graph with no time pressure.
     *
     * @param graph key to the keys it leads to
     * @param maxDepth the depth bound
     * @param budget the node budget
     * @return the traversal result
     */
    private static Result walk(Map<String, List<String>> graph, int maxDepth, int budget)
    {
        return CallGraphTraversal.traverse(root(), maxDepth, budget, NEVER_EXPIRES, fakeExpander(graph));
    }

    /**
     * Runs a one-level traversal whose expander reports the given diagnostics.
     *
     * @param report populates the level's diagnostics
     * @return the traversal result
     */
    private static Result withDiagnostics(java.util.function.Consumer<Diagnostics> report)
    {
        LevelExpander expander = (frontier, budget, expired) -> {
            Diagnostics diag = new Diagnostics();
            report.accept(diag);
            return new Expansion(Collections.emptyList(), diag, false);
        };
        return CallGraphTraversal.traverse(root(), 2, 100, NEVER_EXPIRES, expander);
    }

    /**
     * An expander that answers from a fixed adjacency map, emitting every frontier node's neighbours
     * in declaration order. Payload is the key itself.
     *
     * @param graph key to the keys it leads to
     * @return the fake expander
     */
    private static LevelExpander fakeExpander(Map<String, List<String>> graph)
    {
        return (frontier, budget, expired) -> {
            List<Edge> edges = new ArrayList<>();
            for (Node node : frontier)
            {
                for (String child : graph.getOrDefault(node.getKey(), Collections.emptyList()))
                {
                    edges.add(new Edge(node, child, child, true));
                }
            }
            return new Expansion(edges, null, false);
        };
    }

    /**
     * Builds a straight chain {@code a -> b -> c ...} as an adjacency map.
     *
     * @param keys the chain in order, starting at the root key
     * @return the adjacency map
     */
    private static Map<String, List<String>> chain(String... keys)
    {
        Map<String, List<String>> graph = new HashMap<>();
        for (int i = 0; i + 1 < keys.length; i++)
        {
            graph.put(keys[i], Arrays.asList(keys[i + 1]));
        }
        return graph;
    }

    /**
     * The result's node keys in emission order.
     *
     * @param result the traversal result
     * @return the keys
     */
    private static List<String> keysOf(Result result)
    {
        List<String> out = new ArrayList<>();
        for (Node node : result.getNodes())
        {
            out.add(node.getKey());
        }
        return out;
    }

    /**
     * Shorthand for an expected key list.
     *
     * @param keys the expected keys in order
     * @return the list
     */
    private static List<String> keys(String... keys)
    {
        return Arrays.asList(keys);
    }

    /**
     * Finds a node by key, failing the test when it is absent.
     *
     * @param result the traversal result
     * @param key the key to find
     * @return the node
     */
    private static Node nodeOf(Result result, String key)
    {
        for (Node node : result.getNodes())
        {
            if (key.equals(node.getKey()))
            {
                return node;
            }
        }
        fail("no node keyed '" + key + "' in " + keysOf(result)); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /**
     * The flag of the node with the given key.
     *
     * @param result the traversal result
     * @param key the key to find
     * @return the flag, or {@code null} when the node was expanded normally
     */
    private static NodeFlag flagOf(Result result, String key)
    {
        return nodeOf(result, key).getFlag();
    }

    /**
     * The discovery level of the node with the given key.
     *
     * @param result the traversal result
     * @param key the key to find
     * @return the level
     */
    private static int levelOf(Result result, String key)
    {
        return nodeOf(result, key).getLevel();
    }
}

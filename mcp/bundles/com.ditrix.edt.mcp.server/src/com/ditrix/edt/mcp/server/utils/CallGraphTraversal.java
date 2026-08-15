/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded breadth-first traversal of a call graph, with no knowledge of what a "call" is.
 * <p>
 * This engine owns exactly the parts of a transitive call-hierarchy walk that are easy to get
 * wrong and hard to test through a live workbench: the depth bound, the report-once rule, the node
 * budget, the cooperative time bound, and the accounting that decides whether the answer may be
 * called complete. It deliberately contains no Eclipse, EMF or BSL types, so every one of those
 * bounds is unit-testable headlessly with a fake {@link LevelExpander}.
 * <p>
 * <b>What bounds what.</b> Termination is guaranteed by the DEPTH bound - levels are finite, so the
 * walk ends whatever the graph looks like. The report-once rule bounds the WIDTH: without it a
 * cyclic or re-converging graph re-emits the same nodes at every level, and the result grows with
 * the number of PATHS instead of the number of nodes. Both matter, and conflating them would
 * misdescribe what a mutation of either actually breaks.
 * <p>
 * <b>Levels, not nodes.</b> The engine expands one whole BFS level per {@link LevelExpander}
 * call rather than one node at a time. That is not a detail: for the caller search, one level is
 * one pass over the project's sources, so a level-batched walk costs one project read pass per
 * LEVEL instead of one per discovered node.
 * <p>
 * <b>Completeness is never assumed.</b> An expander that failed to read or parse part of the
 * search space reports that through {@link Diagnostics}; the engine folds it into
 * {@link Result#isComplete()} so a partial search can never be rendered as a complete one. Only
 * the depth bound is exempt: stopping at the requested depth is the answer to the question that
 * was asked, not a truncation of it, so nodes left at the boundary are flagged
 * {@link NodeFlag#DEPTH_LIMIT} while the result stays complete <i>through that depth</i>.
 */
public final class CallGraphTraversal
{
    private CallGraphTraversal()
    {
        // Utility class - no instantiation
    }

    /**
     * Why a discovered node was not expanded any further.
     * <p>
     * An unflagged node was expanded - but "expanded" is only as complete as the walk itself: when
     * the result reports the walk cut short (budget, time, or something it could not read), the
     * search of an unflagged node may have been cut mid-way too, and nothing marks the individual
     * row. The HEADER is the authority on completeness; the flags say which specific nodes were
     * never expanded at all.
     */
    public enum NodeFlag
    {
        /**
         * The node sits at the requested depth bound, so its own callers were never looked for.
         * The result is still complete through the requested depth; raising {@code depth} is what
         * reveals more.
         */
        DEPTH_LIMIT,

        /** The node budget was exhausted before this node could be expanded. */
        NODE_BUDGET,

        /** The time budget was exhausted before this node could be expanded. */
        TIME_LIMIT,

        /** The expander declared this node inherently unexpandable (nothing to recurse into). */
        NOT_EXPANDABLE,

        /**
         * The node is the traversal root, re-reached through the graph. It is reported once, so a
         * self-recursive root is as visible as it is in a single-hop search, but it is never
         * expanded a second time.
         */
        RECURSIVE
    }

    /**
     * One node of the traversal. Identity is the {@code key}; the {@code payload} is opaque to the
     * engine and carries whatever the caller needs in order to render or expand the node.
     * <p>
     * The key must already be folded to whatever case-insensitivity the domain requires - the
     * engine compares keys with plain {@link Object#equals}, and a domain that folds inconsistently
     * would silently visit the same node twice.
     */
    public static final class Node
    {
        private final String key;
        private final Object payload;
        private final boolean expandable;

        private int level;
        private int index = -1;
        private int parentIndex = -1;
        private NodeFlag flag;

        /**
         * Creates a node.
         *
         * @param key the traversal identity, already case-folded by the domain (must not be {@code null})
         * @param payload the caller's opaque data for this node (may be {@code null})
         * @param expandable {@code false} when the domain knows there is nothing to recurse into
         */
        public Node(String key, Object payload, boolean expandable)
        {
            if (key == null)
            {
                throw new IllegalArgumentException("node key must not be null"); //$NON-NLS-1$
            }
            this.key = key;
            this.payload = payload;
            this.expandable = expandable;
        }

        /**
         * Returns the traversal identity of this node.
         *
         * @return the key, never {@code null}
         */
        public String getKey()
        {
            return key;
        }

        /**
         * Returns the caller's opaque payload.
         *
         * @return the payload, may be {@code null}
         */
        public Object getPayload()
        {
            return payload;
        }

        /**
         * Returns the BFS level this node was first discovered at ({@code 1} = a direct neighbour
         * of the root). The root itself is level {@code 0} and is never part of the result.
         *
         * @return the discovery level
         */
        public int getLevel()
        {
            return level;
        }

        /**
         * Returns the zero-based position of this node in {@link Result#getNodes()}.
         *
         * @return the emission index, or {@code -1} for the root (which is never emitted)
         */
        public int getIndex()
        {
            return index;
        }

        /**
         * Returns the emission index of the node that first led here, so a caller can rebuild the
         * witness chain back to the root.
         *
         * @return the parent's emission index, or {@code -1} when the parent is the root
         */
        public int getParentIndex()
        {
            return parentIndex;
        }

        /**
         * Returns why this node was not expanded further.
         *
         * @return the flag, or {@code null} when the node was expanded normally
         */
        public NodeFlag getFlag()
        {
            return flag;
        }
    }

    /**
     * One discovered edge: {@code parent} calls (or is called by, depending on the domain's
     * direction) the node identified by {@code key}.
     */
    public static final class Edge
    {
        private final Node parent;
        private final String key;
        private final Object payload;
        private final boolean expandable;

        /**
         * Creates an edge.
         *
         * @param parent the frontier node this edge was found from (must not be {@code null})
         * @param key the discovered node's traversal identity (must not be {@code null})
         * @param payload the discovered node's opaque payload (may be {@code null})
         * @param expandable {@code false} when there is nothing to recurse into from the discovered node
         */
        public Edge(Node parent, String key, Object payload, boolean expandable)
        {
            if (parent == null)
            {
                throw new IllegalArgumentException("edge parent must not be null"); //$NON-NLS-1$
            }
            if (key == null)
            {
                throw new IllegalArgumentException("edge key must not be null"); //$NON-NLS-1$
            }
            this.parent = parent;
            this.key = key;
            this.payload = payload;
            this.expandable = expandable;
        }
    }

    /**
     * What an expander could NOT search. Every counter here is a reason the result may be missing
     * real edges, so any non-zero value forces {@link Result#isComplete()} to {@code false}.
     * <p>
     * This exists because the surrounding code's failure paths are all silent: an unreadable file,
     * a failed workspace enumeration and an unloadable module each end as "found nothing here",
     * which is indistinguishable from a genuine absence of callers unless it is counted.
     */
    public static final class Diagnostics
    {
        private int unreadableFiles;
        private int unloadableModules;
        private int unverifiedModules;
        private boolean enumerationFailed;

        /**
         * Records a source file whose text could not be read, so it never became a candidate.
         */
        public void addUnreadableFile()
        {
            unreadableFiles++;
        }

        /**
         * Records a candidate module that could not be loaded or parsed, so it was never searched.
         */
        public void addUnloadableModule()
        {
            unloadableModules++;
        }

        /**
         * Records a module whose syntax tree cannot be vouched for as a faithful reading of its
         * source - either the parser recovered from a syntax error, or there was no parse evidence
         * to consult at all. Both mean the same thing to an AST search: a call may be missing from
         * the tree it walks, so the module cannot be reported as fully searched.
         */
        public void addUnverifiedModule()
        {
            unverifiedModules++;
        }

        /**
         * Returns the number of modules whose syntax tree could not be vouched for.
         *
         * @return the count, never negative
         */
        public int getUnverifiedModules()
        {
            return unverifiedModules;
        }

        /**
         * Records that enumerating the search space itself failed, so an unknown remainder of it
         * was never even considered.
         */
        public void markEnumerationFailed()
        {
            enumerationFailed = true;
        }

        /**
         * Returns the number of source files that could not be read.
         *
         * @return the count, never negative
         */
        public int getUnreadableFiles()
        {
            return unreadableFiles;
        }

        /**
         * Returns the number of candidate modules that could not be loaded or parsed.
         *
         * @return the count, never negative
         */
        public int getUnloadableModules()
        {
            return unloadableModules;
        }

        /**
         * Returns whether enumerating the search space failed at least once.
         *
         * @return {@code true} when part of the search space was never considered
         */
        public boolean isEnumerationFailed()
        {
            return enumerationFailed;
        }

        /**
         * Returns whether anything at all went unsearched.
         *
         * @return {@code true} when this result may be missing real edges
         */
        public boolean hasFailures()
        {
            return unreadableFiles > 0 || unloadableModules > 0 || unverifiedModules > 0
                || enumerationFailed;
        }

        /**
         * Folds another accumulator's counters into this one.
         *
         * @param other the diagnostics to absorb (ignored when {@code null})
         */
        public void add(Diagnostics other)
        {
            if (other == null)
            {
                return;
            }
            unreadableFiles += other.unreadableFiles;
            unloadableModules += other.unloadableModules;
            unverifiedModules += other.unverifiedModules;
            enumerationFailed |= other.enumerationFailed;
        }
    }

    /**
     * The outcome of expanding one level: the edges that were found, what could not be searched,
     * and whether the expander gave up before searching everything it was asked to.
     */
    public static final class Expansion
    {
        private final List<Edge> edges;
        private final Diagnostics diagnostics;
        private final boolean cutShort;

        /**
         * Creates an expansion outcome.
         *
         * @param edges the discovered edges in the order they should be emitted (may be {@code null} for none)
         * @param diagnostics what could not be searched (may be {@code null} for a clean pass)
         * @param cutShort {@code true} when the expander stopped before searching everything it was
         *            asked to (for example on the caller's own time or budget signal)
         */
        public Expansion(List<Edge> edges, Diagnostics diagnostics, boolean cutShort)
        {
            this.edges = edges == null ? Collections.emptyList() : edges;
            this.diagnostics = diagnostics;
            this.cutShort = cutShort;
        }
    }

    /**
     * Expands one whole BFS level. Implementations do the domain work (and only the domain work):
     * find every edge leading out of {@code frontier}, and report what they could not search.
     */
    public interface LevelExpander
    {
        /**
         * Finds every edge leading out of the given frontier.
         * <p>
         * Returning edges in a deterministic order is the implementation's responsibility: the
         * engine emits them in the order given, so a stable expander yields a stable result.
         *
         * @param frontier the nodes to expand, never empty and never modified by the engine
         * @param remainingBudget how many NEW nodes the engine can still accept; always positive.
         *            An expander may use it to stop early, and must then set {@code cutShort}
         * @param expired the engine's cooperative time signal; an expander that honours it between
         *            units of work must set {@code cutShort} when it stops because of it
         * @return the level's outcome, never {@code null}
         */
        Expansion expand(List<Node> frontier, int remainingBudget, BooleanSupplier expired);
    }

    /**
     * The finished traversal: the discovered nodes in emission order plus everything needed to
     * describe honestly how far the search actually got.
     */
    public static final class Result
    {
        private final List<Node> nodes;
        private final int repeatEdges;
        private final boolean budgetExhausted;
        private final boolean timedOut;
        private final boolean searchCutShort;
        private final Diagnostics diagnostics;

        private Result(List<Node> nodes, int repeatEdges, boolean budgetExhausted,
            boolean timedOut, boolean searchCutShort, Diagnostics diagnostics)
        {
            this.nodes = nodes;
            this.repeatEdges = repeatEdges;
            this.budgetExhausted = budgetExhausted;
            this.timedOut = timedOut;
            this.searchCutShort = searchCutShort;
            this.diagnostics = diagnostics;
        }

        /**
         * Returns the discovered nodes in emission order (BFS order), each at the lowest level it
         * was reachable at. Every node appears exactly once.
         *
         * @return an unmodifiable list, never {@code null}
         */
        public List<Node> getNodes()
        {
            return nodes;
        }

        /**
         * Returns how many edges pointed at a node that had already been reported. This is graph
         * re-convergence (including genuine recursion) collapsed by the report-once rule - it is NOT a
         * count of cycles, and it never means anything was lost.
         *
         * @return the number of collapsed repeat edges, never negative
         */
        public int getRepeatEdges()
        {
            return repeatEdges;
        }

        /**
         * Returns whether the node budget stopped the search. When {@code true} the traversal
         * itself was cut, so the true size of the answer is unknown - not merely unrendered.
         *
         * @return {@code true} when the node budget was hit
         */
        public boolean isBudgetExhausted()
        {
            return budgetExhausted;
        }

        /**
         * Returns whether the time budget stopped the search.
         *
         * @return {@code true} when the time budget was hit
         */
        public boolean isTimedOut()
        {
            return timedOut;
        }

        /**
         * Returns whether an expander gave up before searching everything it was asked to.
         *
         * @return {@code true} when at least one level was searched only partially
         */
        public boolean isSearchCutShort()
        {
            return searchCutShort;
        }

        /**
         * Returns what could not be searched.
         *
         * @return the aggregated diagnostics, never {@code null}
         */
        public Diagnostics getDiagnostics()
        {
            return diagnostics;
        }

        /**
         * Whether the result is complete <i>through the requested depth</i>.
         * <p>
         * Reaching the depth bound does NOT make a result incomplete: the caller asked for that
         * depth, and the boundary nodes say so themselves via {@link NodeFlag#DEPTH_LIMIT}. Every
         * other stop - budget, time, a cut-short level, or anything the expander could not read or
         * parse - does, because each of those means real edges may be missing with no marker where
         * they would have been.
         *
         * @return {@code true} when nothing was missed within the requested depth
         */
        public boolean isComplete()
        {
            return !budgetExhausted && !timedOut && !searchCutShort && !diagnostics.hasFailures();
        }
    }

    /**
     * Walks the call graph breadth-first from {@code root}, bounded by depth, node count and time.
     * <p>
     * The root is never part of the result: it is the thing being asked about, not an answer. A root
     * that is re-reached through the graph still gets exactly one row (with
     * {@link NodeFlag#RECURSIVE}) - matching what a single-hop search would show - but is never
     * expanded again, because its own callers are what this traversal is already computing.
     *
     * @param root the node to start from (must not be {@code null})
     * @param maxDepth how many levels to expand; must be at least 1
     * @param nodeBudget the maximum number of nodes to discover; must be at least 1
     * @param expired the cooperative time signal, consulted before each level (must not be {@code null})
     * @param expander the domain's level expander (must not be {@code null})
     * @return the traversal result, never {@code null}
     */
    public static Result traverse(Node root, int maxDepth, int nodeBudget, BooleanSupplier expired,
        LevelExpander expander)
    {
        if (root == null || expired == null || expander == null)
        {
            throw new IllegalArgumentException("root, expired and expander must not be null"); //$NON-NLS-1$
        }
        if (maxDepth < 1 || nodeBudget < 1)
        {
            throw new IllegalArgumentException("maxDepth and nodeBudget must be at least 1"); //$NON-NLS-1$
        }

        List<Node> nodes = new ArrayList<>();
        Set<String> emitted = new HashSet<>();

        Diagnostics diagnostics = new Diagnostics();
        List<Node> frontier = new ArrayList<>();
        frontier.add(root);

        Walk walk = new Walk();
        int level = 1;
        for (; level <= maxDepth && !frontier.isEmpty(); level++)
        {
            if (nodes.size() >= nodeBudget)
            {
                walk.budgetExhausted = true;
                flagAll(frontier, NodeFlag.NODE_BUDGET);
                frontier = Collections.emptyList();
                break;
            }
            if (expired.getAsBoolean())
            {
                walk.timedOut = true;
                flagAll(frontier, NodeFlag.TIME_LIMIT);
                frontier = Collections.emptyList();
                break;
            }

            Expansion expansion =
                expander.expand(Collections.unmodifiableList(frontier), nodeBudget - nodes.size(), expired);
            diagnostics.add(expansion.diagnostics);
            walk.searchCutShort |= expansion.cutShort;

            frontier = absorb(expansion, level, nodeBudget, nodes, emitted, root.key, walk);
        }

        // Anything still queued when the depth bound ran out is the boundary of the answer, not a
        // truncation of it: it is flagged, and completeness is unaffected.
        if (!frontier.isEmpty())
        {
            flagAll(frontier, NodeFlag.DEPTH_LIMIT);
        }

        return new Result(Collections.unmodifiableList(nodes), walk.repeatEdges,
            walk.budgetExhausted, walk.timedOut, walk.searchCutShort, diagnostics);
    }

    /**
     * Mutable running state of one traversal, bundled so {@link #absorb} can report back without a
     * multi-value return.
     */
    private static final class Walk
    {
        int repeatEdges;
        boolean budgetExhausted;
        boolean timedOut;
        boolean searchCutShort;
    }

    /**
     * Folds one level's edges into the result: emits each not-yet-reported node once, assigns its
     * level and witness parent, and returns the nodes that are eligible to be expanded next.
     *
     * @param expansion the level's outcome
     * @param level the level being absorbed ({@code 1} = direct neighbours of the root)
     * @param nodeBudget the maximum number of nodes to discover
     * @param nodes the emission-ordered accumulator (appended to)
     * @param emitted keys already reported (updated); reporting each node exactly once is what
     *            keeps a cyclic or re-converging graph to one row per node
     * @param rootKey the traversal root's key - the one node that is reportable but not expandable
     * @param walk the running counters and stop flags (updated)
     * @return the next frontier, never {@code null}
     */
    private static List<Node> absorb(Expansion expansion, int level, int nodeBudget, List<Node> nodes,
        Set<String> emitted, String rootKey, Walk walk)
    {
        List<Node> next = new ArrayList<>();
        for (Edge edge : expansion.edges)
        {
            if (emitted.contains(edge.key))
            {
                // Already reported at its lowest level. Collapsing it is what keeps one node to
                // one row on a cyclic or re-converging graph (the depth bound is what ends the
                // walk); it is counted, never silently dropped.
                walk.repeatEdges++;
                continue;
            }
            if (nodes.size() >= nodeBudget)
            {
                // The budget cuts the SEARCH, not just the rendering: whatever is behind these
                // dropped edges is now unknowable, which isComplete() reports.
                walk.budgetExhausted = true;
                break;
            }

            Node node = new Node(edge.key, edge.payload, edge.expandable);
            node.level = level;
            node.index = nodes.size();
            node.parentIndex = edge.parent.index;
            nodes.add(node);
            emitted.add(node.key);

            if (rootKey.equals(node.key))
            {
                // The graph led back to the very method being asked about. It is reported (a
                // single-hop search would show it too) but never expanded: the root's own callers
                // are what this whole traversal is already computing.
                node.flag = NodeFlag.RECURSIVE;
            }
            else if (!node.expandable)
            {
                node.flag = NodeFlag.NOT_EXPANDABLE;
            }
            else
            {
                next.add(node);
            }
        }
        return next;
    }

    /**
     * Marks every node of a frontier that was never expanded with the reason it was not. The root
     * carries no flag because it is not part of the result.
     *
     * @param frontier the unexpanded nodes
     * @param flag the reason they were not expanded
     */
    private static void flagAll(List<Node> frontier, NodeFlag flag)
    {
        for (Node node : frontier)
        {
            if (node.index >= 0 && node.flag == null)
            {
                node.flag = flag;
            }
        }
    }
}

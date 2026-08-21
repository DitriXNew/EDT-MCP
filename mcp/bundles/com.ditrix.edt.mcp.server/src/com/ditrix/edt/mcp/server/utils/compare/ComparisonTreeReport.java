/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;

/**
 * Turns one finished three-way comparison tree into the Markdown a caller reads.
 * <p>
 * Split in two on purpose, because the two halves run in different places:
 * <ul>
 * <li>{@link Collector#accept(TopComparisonNode)} touches the platform nodes and must run
 * INSIDE the comparison's own read task - the nodes are {@code IBmObject}s of the
 * comparison's private BM store, so they are not valid outside it. It copies each node into
 * a plain {@link Node} and keeps the counters;</li>
 * <li>{@link #render(Header, ComparisonScope, Collector)} is pure text assembly over those
 * copies plus the {@link ComparisonScope}, which is an ordinary object owned by the handle
 * and safe to read afterwards.</li>
 * </ul>
 * <p>
 * Three honesty rules are built into the rendering and are what its tests pin:
 * <ol>
 * <li><b>Requested scope is not the compared scope.</b> {@link ComparisonScope#getInputScope}
 * is what the caller asked for; {@link ComparisonScope#getScope} additionally contains
 * everything the engine pulled in by itself. The report shows the first as "Requested" and
 * the second's delta - {@link ComparisonScope#getExtendedScope} - separately, with the
 * engine's own reason for each addition. Rendering the extended scope as the requested one
 * would report objects the caller never named as objects the caller chose.</li>
 * <li><b>An unfinished node is not an equal node.</b> The tree is lazy: a node whose
 * {@link ComparisonNodeStatus} is not {@code FINISHED} has not been compared yet, so it is
 * rendered as {@link Change#NOT_COMPARED} and never counted as, or described with, an
 * absence of differences.</li>
 * <li><b>An empty tree is not an equal tree.</b> A comparison that produced no top node at
 * all compared nothing, so no claim about the sides can be derived from it. That case is
 * reachable without any failure: {@link ComparisonScopeBuilder} validates only the LEADING
 * type token of a scope entry, so a name that exists on none of the three sides is a legal
 * scope that selects nothing - and a tree that was never built answers the same way. The
 * report says what it observed instead of asserting agreement.</li>
 * </ol>
 * <p>
 * Never routes a label through {@code ComparisonUtils.getLabel()}: that delegates to a
 * function branching on {@code Locale.getDefault()}, which would make the wire text depend
 * on the machine EDT happens to run on.
 */
public final class ComparisonTreeReport
{
    /** Page size used when a caller names none. */
    public static final int DEFAULT_LIMIT = 100;

    /** Largest page a caller may ask for. */
    public static final int MAX_LIMIT = 1000;

    private static final String WHOLE_CONFIGURATION = "whole configuration (nothing requested)"; //$NON-NLS-1$

    private static final String NONE = "—"; //$NON-NLS-1$

    private ComparisonTreeReport()
    {
        // Utility class
    }

    /**
     * How one top object differs across the three sides.
     * <p>
     * Decoded from {@link ComparisonFlags} and the node's own one-sided markers, never
     * guessed from the symlinks: a symlink is present on a side the object does not exist on
     * whenever the engine matched it by name.
     */
    public enum Change
    {
        /** The platform itself marked the node as changed on both sides. */
        CONFLICT("CONFLICT (changed on both sides)", true), //$NON-NLS-1$
        /** Present on the other side only, and absent from the common ancestor. */
        ADDED_ON_OTHER("added on other", true), //$NON-NLS-1$
        /** Present on the main side only, and absent from the common ancestor. */
        ADDED_ON_MAIN("added on main", true), //$NON-NLS-1$
        /** Present on the other side and the ancestor, so the main side dropped it. */
        DELETED_ON_MAIN("deleted on main", true), //$NON-NLS-1$
        /** Present on the main side and the ancestor, so the other side dropped it. */
        DELETED_ON_OTHER("deleted on other", true), //$NON-NLS-1$
        /** Present in the common ancestor only. */
        ONLY_IN_ANCESTOR("deleted on both sides", true), //$NON-NLS-1$
        /** Both sides moved away from the ancestor without the platform calling it a conflict. */
        CHANGED_ON_BOTH("changed on both sides", true), //$NON-NLS-1$
        /** Only the other side moved away from the ancestor. */
        CHANGED_ON_OTHER("changed on other", true), //$NON-NLS-1$
        /** Only the main side moved away from the ancestor. */
        CHANGED_ON_MAIN("changed on main", true), //$NON-NLS-1$
        /** The two sides differ, but not relative to the ancestor in a way the flags name. */
        DIFFERS("differs between main and other", true), //$NON-NLS-1$
        /** Compared, and equal on every side. */
        IDENTICAL("identical", false), //$NON-NLS-1$
        /** Not compared yet - the tree is lazy, so this is NOT a statement about equality. */
        NOT_COMPARED("not compared yet", true); //$NON-NLS-1$

        private final String label;
        private final boolean differs;

        Change(String label, boolean differs)
        {
            this.label = label;
            this.differs = differs;
        }

        /** @return the stable wire text for this state */
        public String label()
        {
            return label;
        }

        /**
         * @return {@code true} when this state must survive the {@code changedOnly} filter;
         *     {@link #NOT_COMPARED} does, because dropping it would silently present an
         *     uncompared subtree as an equal one
         */
        public boolean differs()
        {
            return differs;
        }
    }

    /** One top comparison node, copied out of the comparison's BM store into plain data. */
    public static final class Node
    {
        private final long nodeId;
        private final String mainSymlink;
        private final String otherSymlink;
        private final String ancestorSymlink;
        private final Change change;
        private final String status;

        /**
         * @param nodeId the node's BM id, the handle {@code get_comparison_node} takes
         * @param mainSymlink qualified name on the main side, or {@code null}
         * @param otherSymlink qualified name on the other side, or {@code null}
         * @param ancestorSymlink qualified name in the common ancestor, or {@code null}
         * @param change the decoded three-sided state
         * @param status the platform's own node-status literal
         */
        public Node(long nodeId, String mainSymlink, String otherSymlink, String ancestorSymlink,
            Change change, String status)
        {
            this.nodeId = nodeId;
            this.mainSymlink = mainSymlink;
            this.otherSymlink = otherSymlink;
            this.ancestorSymlink = ancestorSymlink;
            this.change = change;
            this.status = status;
        }

        /** @return the node's BM id */
        public long getNodeId()
        {
            return nodeId;
        }

        /** @return qualified name on the main side, or {@code null} */
        public String getMainSymlink()
        {
            return mainSymlink;
        }

        /** @return qualified name on the other side, or {@code null} */
        public String getOtherSymlink()
        {
            return otherSymlink;
        }

        /** @return qualified name in the common ancestor, or {@code null} */
        public String getAncestorSymlink()
        {
            return ancestorSymlink;
        }

        /** @return the decoded three-sided state */
        public Change getChange()
        {
            return change;
        }

        /** @return the platform's own node-status literal */
        public String getStatus()
        {
            return status;
        }
    }

    /** Fixed facts about the run, rendered above the tree. */
    public static final class Header
    {
        private final String comparisonId;
        private final String projectName;
        private final String otherRevision;
        private final String ancestorRevision;
        private final String state;

        /**
         * @param comparisonId this plugin's id for the live comparison session
         * @param projectName the project whose working tree is the main side
         * @param otherRevision the git revision compared against
         * @param ancestorRevision the git revision used as the common ancestor
         * @param state the comparison's own reported state
         */
        public Header(String comparisonId, String projectName, String otherRevision,
            String ancestorRevision, String state)
        {
            this.comparisonId = comparisonId;
            this.projectName = projectName;
            this.otherRevision = otherRevision;
            this.ancestorRevision = ancestorRevision;
            this.state = state;
        }
    }

    /**
     * Accumulates the tree while it is still readable, keeping WHOLE counters and at most
     * {@code limit} rows.
     * <p>
     * The counters are deliberately independent of the page: a report that truncated its
     * totals along with its list would answer "how much changed?" with "as much as fits".
     */
    public static final class Collector
    {
        private final int limit;
        private final boolean changedOnly;
        private final List<Node> rows = new ArrayList<>();

        private int total;
        private int matching;
        private int differing;
        private int conflicts;
        private int notCompared;

        /**
         * @param limit largest number of rows to keep; clamped into {@code [1, MAX_LIMIT]}
         * @param changedOnly keep only nodes whose state is not {@link Change#IDENTICAL}
         */
        public Collector(int limit, boolean changedOnly)
        {
            this.limit = Pagination.clampLimit(limit, MAX_LIMIT);
            this.changedOnly = changedOnly;
        }

        /**
         * Copies one platform node into the report. Call only inside the comparison's read
         * task.
         *
         * @param node the platform node (ignored when {@code null})
         */
        public void accept(TopComparisonNode node)
        {
            if (node == null)
            {
                return;
            }
            accept(read(node));
        }

        /**
         * Adds one already-copied node.
         *
         * @param node the copied node (ignored when {@code null})
         */
        public void accept(Node node)
        {
            if (node == null)
            {
                return;
            }
            total++;
            Change change = node.getChange();
            if (change == Change.CONFLICT)
            {
                conflicts++;
            }
            if (change == Change.NOT_COMPARED)
            {
                notCompared++;
            }
            else if (change.differs())
            {
                differing++;
            }
            if (changedOnly && !change.differs())
            {
                return;
            }
            matching++;
            if (rows.size() < limit)
            {
                rows.add(node);
            }
        }

        /** @return every top node seen, whether or not it was kept */
        public int getTotal()
        {
            return total;
        }

        /** @return nodes with a difference, EXCLUDING the ones not compared yet */
        public int getDiffering()
        {
            return differing;
        }

        /** @return nodes the platform marked as changed on both sides */
        public int getConflicts()
        {
            return conflicts;
        }

        /** @return nodes whose subtree the lazy engine has not finished */
        public int getNotCompared()
        {
            return notCompared;
        }

        /** @return nodes that passed the filter, whether or not they fit the page */
        public int getMatching()
        {
            return matching;
        }

        /** @return the kept rows, at most {@code limit} of them */
        public List<Node> getRows()
        {
            return Collections.unmodifiableList(rows);
        }
    }

    /**
     * Copies one platform node, decoding its three-sided state.
     *
     * @param node the platform node
     * @return the plain copy
     */
    public static Node read(TopComparisonNode node)
    {
        ComparisonNodeStatus status = node.getComparisonStatus();
        return new Node(node.bmGetId(), node.getMainSymlink(), node.getOtherSymlink(),
            node.getCommonAncestorSymlink(), decode(node, status),
            status == null ? "unknown" : status.getLiteral()); //$NON-NLS-1$
    }

    /**
     * Decides what happened to one top object.
     * <p>
     * The order is the contract. An unfinished node is answered first, because every later
     * question would be answered from flags the engine has not filled in yet. A conflict is
     * answered next, from {@code hasDoubleChanges()} - the platform's own verdict, not a
     * count of changed sides. Then presence, then change relative to the ancestor.
     *
     * @param node the platform node
     * @param status the node's comparison status (may be {@code null})
     * @return the decoded state
     */
    private static Change decode(TopComparisonNode node, ComparisonNodeStatus status)
    {
        if (status != ComparisonNodeStatus.FINISHED)
        {
            return Change.NOT_COMPARED;
        }
        ComparisonFlags flags = node.getComparisonFlags();
        if (flags == null)
        {
            flags = ComparisonFlags.NO_FLAGS;
        }
        if (flags.hasDoubleChanges())
        {
            return Change.CONFLICT;
        }
        if (node.isOneSideNode())
        {
            return decodeOneSided(node.getNodeSide(), node.isAncestorObjectExists());
        }
        boolean changedOnMain = flags.hasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        boolean changedOnOther =
            flags.hasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);
        if (changedOnMain && changedOnOther)
        {
            return Change.CHANGED_ON_BOTH;
        }
        if (changedOnOther)
        {
            return Change.CHANGED_ON_OTHER;
        }
        if (changedOnMain)
        {
            return Change.CHANGED_ON_MAIN;
        }
        if (flags.hasChanged(ComparisonSide.MAIN, ComparisonSide.OTHER))
        {
            return Change.DIFFERS;
        }
        return Change.IDENTICAL;
    }

    /**
     * Names the one-sided case, which the ancestor turns into an addition or a deletion.
     *
     * @param side the side the object exists on (may be {@code null})
     * @param ancestorExists whether the common ancestor still has the object
     * @return the decoded state
     */
    private static Change decodeOneSided(ComparisonSide side, boolean ancestorExists)
    {
        if (side == ComparisonSide.OTHER)
        {
            return ancestorExists ? Change.DELETED_ON_MAIN : Change.ADDED_ON_OTHER;
        }
        if (side == ComparisonSide.MAIN)
        {
            return ancestorExists ? Change.DELETED_ON_OTHER : Change.ADDED_ON_MAIN;
        }
        return Change.ONLY_IN_ANCESTOR;
    }

    /**
     * Renders the whole report.
     *
     * @param header fixed facts about the run
     * @param scope the comparison's scope (may be {@code null} when the handle reported none)
     * @param collector the accumulated tree
     * @return Markdown
     */
    public static String render(Header header, ComparisonScope scope, Collector collector)
    {
        StringBuilder out = new StringBuilder();
        out.append("# Comparison: ").append(header.projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("comparisonId", header.comparisonId); //$NON-NLS-1$
        summary.put("project", header.projectName); //$NON-NLS-1$
        summary.put("main", "working tree of " + header.projectName); //$NON-NLS-1$ //$NON-NLS-2$
        summary.put("other", header.otherRevision); //$NON-NLS-1$
        summary.put("ancestor", header.ancestorRevision); //$NON-NLS-1$
        summary.put("state", header.state); //$NON-NLS-1$
        out.append(MarkdownUtils.keyValueTable("Field", "Value", summary)); //$NON-NLS-1$ //$NON-NLS-2$

        appendScope(out, scope, collector.limit);
        appendNodes(out, scope, collector);
        return out.toString();
    }

    /**
     * Renders the requested scope and, separately, whatever the engine added to it.
     *
     * @param out the report being assembled
     * @param scope the comparison's scope (may be {@code null})
     * @param limit largest number of qualified names to list per side
     */
    private static void appendScope(StringBuilder out, ComparisonScope scope, int limit)
    {
        out.append("\n## Scope\n\n"); //$NON-NLS-1$
        if (scope == null)
        {
            out.append("The comparison reported no scope, so what it covered is unknown.\n"); //$NON-NLS-1$
            return;
        }
        out.append(MarkdownUtils.tableHeader("Side", "Requested", "Added by the engine")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (ComparisonSide side : ComparisonSide.values())
        {
            // getInputScope, NOT getScope: getScope also carries what the engine pulled in by
            // itself, and presenting that as the caller's request is the lie this report exists
            // to avoid.
            List<String> requested = scope.getInputScope(side);
            Map<String, List<String>> added = scope.getExtendedScope(side);
            out.append(MarkdownUtils.tableRow(sideName(side), describeRequested(requested, limit),
                describeAdded(added, limit)));
        }

        StringBuilder reasons = new StringBuilder();
        for (ComparisonSide side : ComparisonSide.values())
        {
            Map<String, List<String>> added = scope.getExtendedScope(side);
            if (added == null)
            {
                continue;
            }
            // Sorted, because the platform hands this back as a HashMap: an unordered report
            // would change between two runs of the same comparison for no reason.
            for (Map.Entry<String, List<String>> entry : new TreeMap<>(added).entrySet())
            {
                reasons.append("- `").append(sideName(side)).append("` / `") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(entry.getKey()).append("` — ") //$NON-NLS-1$
                    .append(String.join("; ", entry.getValue() == null //$NON-NLS-1$
                        ? Collections.<String> emptyList() : entry.getValue()))
                    .append('\n');
            }
        }
        if (reasons.length() > 0)
        {
            out.append("\nWhy the engine added a qualified name of its own:\n\n") //$NON-NLS-1$
                .append(reasons);
        }
    }

    /**
     * @param requested the qualified names the caller asked for on one side
     * @param limit largest number to list
     * @return the cell text, saying so when nothing was requested
     */
    private static String describeRequested(List<String> requested, int limit)
    {
        // An empty input scope is not a refusal and not an empty comparison: the platform
        // treats it as "compare everything", so say that rather than printing nothing.
        if (requested == null || requested.isEmpty())
        {
            return WHOLE_CONFIGURATION;
        }
        return join(requested, limit);
    }

    /**
     * @param added the engine's own additions on one side
     * @param limit largest number to list
     * @return the cell text
     */
    private static String describeAdded(Map<String, List<String>> added, int limit)
    {
        if (added == null || added.isEmpty())
        {
            return NONE;
        }
        return join(new ArrayList<>(new TreeMap<>(added).keySet()), limit);
    }

    /**
     * @param values the values to list
     * @param limit largest number to list
     * @return the values, comma separated, with the whole count kept when truncated
     */
    private static String join(List<String> values, int limit)
    {
        int shown = Math.min(values.size(), limit);
        String text = String.join(", ", values.subList(0, shown)); //$NON-NLS-1$
        return text + Pagination.truncationNotice(shown, values.size());
    }

    /**
     * @param side the comparison side
     * @return its stable lower-case wire name
     */
    private static String sideName(ComparisonSide side)
    {
        if (side == ComparisonSide.MAIN)
        {
            return "main"; //$NON-NLS-1$
        }
        if (side == ComparisonSide.OTHER)
        {
            return "other"; //$NON-NLS-1$
        }
        return "ancestor"; //$NON-NLS-1$
    }

    /**
     * Renders the counters and the page of top nodes.
     *
     * @param out the report being assembled
     * @param scope the comparison's scope (may be {@code null}), used only to tell a run that
     *     named objects from a whole-configuration run when nothing was compared
     * @param collector the accumulated tree
     */
    private static void appendNodes(StringBuilder out, ComparisonScope scope, Collector collector)
    {
        out.append("\n## Top objects\n\n"); //$NON-NLS-1$
        out.append("**Total:** ").append(collector.getTotal()).append(" top nodes — ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(collector.getDiffering()).append(" with differences, ") //$NON-NLS-1$
            .append(collector.getConflicts()).append(" conflicts, ") //$NON-NLS-1$
            .append(collector.getNotCompared()).append(" not compared yet") //$NON-NLS-1$
            .append(Pagination.truncationNotice(collector.getRows().size(), collector.getMatching()))
            .append("\n\n"); //$NON-NLS-1$

        if (collector.getRows().isEmpty())
        {
            // Never the words "no differences" unless the tree actually answered. Two separate
            // absences are asked about first, and both would otherwise be rendered as equality:
            // a tree that produced no node at all, and a lazy tree that has not answered yet.
            if (collector.getTotal() == 0)
            {
                appendNothingCompared(out, scope);
            }
            else if (collector.getNotCompared() > 0)
            {
                out.append("Nothing to show yet: ").append(collector.getNotCompared()) //$NON-NLS-1$
                    .append(" top nodes are still being compared. Poll the job again.\n"); //$NON-NLS-1$
            }
            else
            {
                out.append("The comparison found no differences between the two revisions ") //$NON-NLS-1$
                    .append("and the working tree.\n"); //$NON-NLS-1$
            }
            return;
        }

        out.append(MarkdownUtils.tableHeader("nodeId", "Main", "Other", "Ancestor", "Change", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Node status")); //$NON-NLS-1$
        for (Node node : collector.getRows())
        {
            out.append(MarkdownUtils.tableRow(Long.toString(node.getNodeId()),
                cell(node.getMainSymlink()), cell(node.getOtherSymlink()),
                cell(node.getAncestorSymlink()), node.getChange().label(), node.getStatus()));
        }
    }

    /**
     * Says that the tree carried no top node, WITHOUT deriving equality from that absence.
     * <p>
     * Two different runs end here and the caller has to be able to tell them apart, because the
     * next step differs: a scope that named objects and matched none of them is a name to fix,
     * while a whole-configuration run that produced nothing is a comparison to look at.
     *
     * @param out the report being assembled
     * @param scope the comparison's scope (may be {@code null})
     */
    private static void appendNothingCompared(StringBuilder out, ComparisonScope scope)
    {
        out.append("The comparison reported no top nodes at all, so nothing was compared. ") //$NON-NLS-1$
            .append("That is an absence of data, NOT a statement that the sides agree.\n"); //$NON-NLS-1$
        if (hasRequestedScope(scope))
        {
            out.append('\n')
                .append("The requested scope matched no object in this comparison. Only the ") //$NON-NLS-1$
                .append("leading type token of a scope entry is validated, so a qualified name ") //$NON-NLS-1$
                .append("that exists on none of the three sides is a legal scope that selects ") //$NON-NLS-1$
                .append("nothing: check the names in the Requested column above with ") //$NON-NLS-1$
                .append("get_metadata_objects.\n"); //$NON-NLS-1$
        }
    }

    /**
     * @param scope the comparison's scope (may be {@code null})
     * @return {@code true} when the caller named at least one object on at least one side; an
     *     empty input scope on every side is the platform's "compare everything", not a request
     */
    private static boolean hasRequestedScope(ComparisonScope scope)
    {
        if (scope == null)
        {
            return false;
        }
        for (ComparisonSide side : ComparisonSide.values())
        {
            List<String> requested = scope.getInputScope(side);
            if (requested != null && !requested.isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @param symlink a qualified name that may be absent on this side
     * @return the cell text
     */
    private static String cell(String symlink)
    {
        return symlink == null || symlink.isEmpty() ? NONE : symlink;
    }
}

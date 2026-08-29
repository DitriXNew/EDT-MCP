/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com._1c.g5.v8.dt.compare.core.ComparisonScope;
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
 * <li>{@link #render(Header, ScopeSnapshot, Collector)} is pure text assembly over those
 * copies plus a {@link ScopeSnapshot} - and that is a COPY of the handle's scope taken in the
 * same read, not the handle's own object. The scope is not safe to read afterwards: the engine
 * extends it in place while the run proceeds, so a table rendered from it later lists objects
 * the rows beside it were never collected under. See {@link ScopeSnapshot}.</li>
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
 * rendered as {@link ComparisonNodeState#NOT_COMPARED} and never counted as, or described
 * with, an absence of differences.</li>
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

    /** One top comparison node, copied out of the comparison's BM store into plain data. */
    public static final class Node
    {
        private final long nodeId;
        private final String mainSymlink;
        private final String otherSymlink;
        private final String ancestorSymlink;
        private final ComparisonNodeState change;
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
            ComparisonNodeState change, String status)
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
        public ComparisonNodeState getChange()
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
        private final boolean globalScope;

        /**
         * @param comparisonId this plugin's id for the live comparison session
         * @param projectName the project whose working tree is the main side
         * @param otherRevision the git revision compared against
         * @param ancestorRevision the git revision used as the common ancestor
         * @param state the comparison's own reported state
         * @param globalScope the SESSION's own answer to "does this run cover the whole
         *     configuration", as it computed it once in its constructor. It is carried here
         *     rather than recomputed from the scope object, because the scope object does not
         *     stand still: the engine extends it while the run proceeds, so a report asking it
         *     afterwards would describe a whole-configuration run as a scoped one as soon as one
         *     dependency had been pulled in. See {@code ComparisonScopeBuilder#isGlobalScope},
         *     which is the same question asked BEFORE the session exists.
         */
        public Header(String comparisonId, String projectName, String otherRevision,
            String ancestorRevision, String state, boolean globalScope)
        {
            this.comparisonId = comparisonId;
            this.projectName = projectName;
            this.otherRevision = otherRevision;
            this.ancestorRevision = ancestorRevision;
            this.state = state;
            this.globalScope = globalScope;
        }
    }

    /**
     * The comparison's scope as ONE reading saw it, copied out of the live {@link ComparisonScope}
     * the handle owns.
     *
     * <h2>Why a copy, and why it is taken where the tree is walked</h2>
     * {@code ComparisonProcessHandle.getFullScope()} hands back the very object the engine
     * extends. Read off {@code ComparisonScope} 29.0.0: {@code extendScope} puts the added name
     * into the {@code HashMap} that {@link ComparisonScope#getExtendedScope} returns and appends
     * the reason to the {@code List} already under that name - both in place, neither copied,
     * neither wrapped unmodifiable. So a report that asked the handle AFTER walking the tree
     * listed a scope the rows below it were never collected under and presented the two as one
     * picture; and iterating that map while the engine writes to it is not merely inconsistent,
     * it is a plain {@code HashMap} read during someone else's {@code put}.
     * <p>
     * The copy is therefore taken inside the SAME comparison read that copies the rows, and it is
     * the copy the report renders. Whatever that boundary is worth for the nodes it is worth for
     * this; and an immutable copy taken there cannot grow afterwards whatever the boundary is
     * worth.
     * <p>
     * {@link ComparisonScope#getInputScope} is the one half that would not have needed defending -
     * that list is built once in the constructor, wrapped {@code Collections.unmodifiableList},
     * and never touched by {@code extendScope}. It is copied anyway, because the point is that
     * the two halves of one reading cannot come from two instants: they arrive together or not
     * at all.
     *
     * <h2>The two halves stay apart</h2>
     * {@link #requested(ComparisonSide)} is what the caller asked for and
     * {@link #added(ComparisonSide)} is what the engine pulled in by itself, copied from the two
     * accessors that answer exactly those questions. Neither is
     * {@link ComparisonScope#getScope}, which is the two merged: rendering that as the request
     * would report objects the caller never named as objects the caller chose.
     */
    public static final class ScopeSnapshot
    {
        /** What a handle that reported no scope at all copies to. */
        private static final ScopeSnapshot ABSENT = new ScopeSnapshot(null, null);

        private final Map<ComparisonSide, List<String>> requested;

        private final Map<ComparisonSide, Map<String, List<String>>> added;

        private ScopeSnapshot(Map<ComparisonSide, List<String>> requested,
            Map<ComparisonSide, Map<String, List<String>>> added)
        {
            this.requested = requested;
            this.added = added;
        }

        /**
         * Copies one live scope. Call from inside the comparison read whose tree the report
         * describes, and nowhere else.
         *
         * @param scope the handle's live scope, or {@code null} when it reported none
         * @return the copy; {@link #isPresent()} answers {@code false} for a {@code null} scope
         */
        public static ScopeSnapshot copyOf(ComparisonScope scope)
        {
            if (scope == null)
            {
                return ABSENT;
            }
            Map<ComparisonSide, List<String>> requestedCopy = new EnumMap<>(ComparisonSide.class);
            Map<ComparisonSide, Map<String, List<String>>> addedCopy =
                new EnumMap<>(ComparisonSide.class);
            for (ComparisonSide side : ComparisonSide.values())
            {
                // getInputScope, NOT getScope: getScope also carries what the engine pulled in by
                // itself, and presenting that as the caller's request is the lie this report
                // exists to avoid.
                requestedCopy.put(side, copyNames(scope.getInputScope(side)));
                addedCopy.put(side, copyAdditions(scope.getExtendedScope(side)));
            }
            return new ScopeSnapshot(requestedCopy, addedCopy);
        }

        /**
         * @return {@code true} when a scope was reported at all; {@code false} says the handle
         *     carried none, which is not the same as a scope that named nothing
         */
        public boolean isPresent()
        {
            return requested != null;
        }

        /**
         * @param side the side
         * @return the qualified names the CALLER asked for on that side; never {@code null}, and
         *     empty is the platform's "compare everything" rather than a refusal
         */
        public List<String> requested(ComparisonSide side)
        {
            return requested == null ? Collections.emptyList()
                : requested.getOrDefault(side, Collections.emptyList());
        }

        /**
         * @param side the side
         * @return what the ENGINE added on that side, each qualified name with the engine's own
         *     reasons for it; never {@code null}
         */
        public Map<String, List<String>> added(ComparisonSide side)
        {
            return added == null ? Collections.emptyMap()
                : added.getOrDefault(side, Collections.emptyMap());
        }

        /**
         * @param names the platform's list (may be {@code null})
         * @return an immutable copy
         */
        private static List<String> copyNames(List<String> names)
        {
            return names == null || names.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(names));
        }

        /**
         * Copies the additions of one side, REASONS INCLUDED.
         * <p>
         * The value lists are copied and not shared. {@code extendScope} appends the reason to the
         * list already under a name, so a copy that kept the platform's lists would keep growing
         * after the reading was taken - and the map would look copied while the deepest thing the
         * report prints was still live.
         *
         * @param additions the live map (may be {@code null} or the platform's empty map)
         * @return an immutable copy, in the platform's own iteration order; the report sorts what
         *     it prints, because a {@code HashMap}'s order is not one
         */
        private static Map<String, List<String>> copyAdditions(Map<String, List<String>> additions)
        {
            if (additions == null || additions.isEmpty())
            {
                return Collections.emptyMap();
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : additions.entrySet())
            {
                copy.put(entry.getKey(), copyNames(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
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
        private int notReported;

        /**
         * @param limit largest number of rows to keep; clamped into {@code [1, MAX_LIMIT]}
         * @param changedOnly keep only nodes whose state is not
         *     {@link ComparisonNodeState#IDENTICAL}
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
            ComparisonNodeState change = node.getChange();
            if (change == ComparisonNodeState.CONFLICT)
            {
                conflicts++;
            }
            if (change == ComparisonNodeState.NOT_COMPARED)
            {
                notCompared++;
            }
            if (change == ComparisonNodeState.NOT_REPORTED)
            {
                notReported++;
            }
            // THE TWO QUESTIONS ARE ASKED SEPARATELY, and the state answers each in its own
            // words. One predicate served both, and the answer that keeps a node the engine gave
            // no verdict for visible under changedOnly - which it must be - also added it to the
            // count of differences, so the total claimed a finding the row beside it says was
            // never made. See ComparisonNodeState: they part company on exactly the two absences.
            if (change.establishesDifference())
            {
                differing++;
            }
            if (changedOnly && !change.survivesChangedOnly())
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

        /**
         * @return nodes whose state ESTABLISHES a difference - so neither the ones not compared
         *     yet nor the ones the engine returned no verdict for, since neither is an answer
         */
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

        /**
         * @return nodes the engine returned no verdict for - counted on their own, because they
         *     are listed like every other row and belong to none of the counts beside it
         */
        public int getNotReported()
        {
            return notReported;
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
     * <p>
     * The state is decoded by {@link ComparisonNodeState}, which is also what
     * {@link ComparisonNodeRenderer} renders when the caller expands one of these rows. Deciding it
     * here as well is what let the two documents disagree - see that enum for the case they
     * disagreed on.
     *
     * @param node the platform node
     * @return the plain copy
     */
    public static Node read(TopComparisonNode node)
    {
        ComparisonNodeStatus status = node.getComparisonStatus();
        return new Node(node.bmGetId(), node.getMainSymlink(), node.getOtherSymlink(),
            node.getCommonAncestorSymlink(), ComparisonNodeState.decode(node, status),
            status == null ? "unknown" : status.getLiteral()); //$NON-NLS-1$
    }

    /**
     * Renders the whole report.
     *
     * @param header fixed facts about the run
     * @param scope the comparison's scope AS ONE READING SAW IT - never the handle's live object;
     *     {@code null} reads the same as a snapshot of a handle that reported none
     * @param collector the accumulated tree
     * @return Markdown
     */
    public static String render(Header header, ScopeSnapshot scope, Collector collector)
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

        appendScope(out, scope, collector.limit, header.globalScope);
        appendNodes(out, scope, collector);
        return out.toString();
    }

    /**
     * Renders the requested scope and, separately, whatever the engine added to it.
     *
     * @param out the report being assembled
     * @param scope the scope as the tree's own reading saw it (may be {@code null})
     * @param limit largest number of qualified names to list per side - in the table cells AND in
     *     the reasons below them, which describe the same names
     * @param globalScope the session's own answer to whether the run covered everything
     */
    private static void appendScope(StringBuilder out, ScopeSnapshot scope, int limit,
        boolean globalScope)
    {
        out.append("\n## Scope\n\n"); //$NON-NLS-1$
        if (scope == null || !scope.isPresent())
        {
            out.append("The comparison reported no scope, so what it covered is unknown.\n"); //$NON-NLS-1$
            return;
        }
        out.append(MarkdownUtils.tableHeader("Side", "Requested", "Added by the engine")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (ComparisonSide side : ComparisonSide.values())
        {
            // Two accessors, deliberately never merged: the snapshot keeps the caller's
            // request and the engine's own additions apart, because presenting the second as the
            // first is the lie this report exists to avoid.
            List<String> requested = scope.requested(side);
            Map<String, List<String>> added = scope.added(side);
            out.append(MarkdownUtils.tableRow(sideName(side), describeRequested(requested, limit),
                describeAdded(added, limit)));
        }

        // Bounded by the SAME limit as the cell above, and by the same count per side, so the
        // bullets explain exactly the names the table listed. Unbounded, this loop printed one
        // line per addition per side while the cell beside it was already truncated: a comparison
        // with plentiful dependencies answers with thousands of lines to a report that was asked
        // for one, which defeats the report's own limit. The truncation is NAMED rather than
        // silent - a list that simply stops reads as the whole of what the engine did.
        StringBuilder reasons = new StringBuilder();
        int shown = 0;
        int total = 0;
        for (ComparisonSide side : ComparisonSide.values())
        {
            Map<String, List<String>> added = scope.added(side);
            total += added.size();
            int shownForSide = 0;
            // Sorted, because the platform hands this back as a HashMap: an unordered report
            // would change between two runs of the same comparison for no reason.
            for (Map.Entry<String, List<String>> entry : new TreeMap<>(added).entrySet())
            {
                if (shownForSide >= limit)
                {
                    break;
                }
                shownForSide++;
                shown++;
                reasons.append("- `").append(sideName(side)).append("` / `") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(entry.getKey()).append("` — ") //$NON-NLS-1$
                    .append(joinReasons(entry.getValue(), limit))
                    .append('\n');
            }
        }
        if (reasons.length() > 0)
        {
            out.append("\nWhy the engine added a qualified name of its own") //$NON-NLS-1$
                .append(Pagination.truncationNotice(shown, total))
                .append(":\n\n") //$NON-NLS-1$
                .append(reasons);
        }
        out.append(contentClause(globalScope));
    }

    /**
     * What a SCOPED comparison did not compare, said out loud.
     *
     * <h2>Why the report has to say it</h2>
     * A scope does not narrow the TREE, it narrows what is compared inside it.
     * {@code compare_configurations} turns the platform's {@code mergeObjectsContent} setting on
     * for a scoped run, and {@code MdCompareUtils.isExcludeObjectsContentFeature} then excludes an
     * object's own features from the comparison whenever that object's qualified name is not under
     * an entry of the comparison's scope. Such a node is still matched - it is still reported as
     * added or deleted - and it can land in the table as {@code identical} having been compared
     * without the features that were excluded. That word means "compared, and equal" everywhere
     * else in this report, and there is no way to tell the two apart from the row itself.
     * <p>
     * The clause says that and no more. The exclusion is applied per FEATURE and spares a
     * containment-many collection of {@code MdObject}s, so a row outside the scope is NOT a row
     * nothing was looked at under: what the caveat withdraws from {@code identical} there is the
     * claim that the excluded features were compared, not the whole of the row.
     * <p>
     * Emitted ONLY for a scoped comparison, because it is only true of one: a whole-configuration
     * run has the setting off and compares content everywhere, and printing the caveat there would
     * describe a limit that was not applied.
     * <p>
     * Which of the two it was is READ FROM THE HEADER, not recomputed from the scope object. The
     * scope object is extended by the engine as the run proceeds, so recomputing here answers a
     * question about the scope as it ended up rather than about the setting the launch chose - and
     * the caveat describes that setting.
     *
     * @param globalScope the session's own saved answer: the run covered the whole configuration
     * @return the clause, or an empty string for a whole-configuration comparison
     */
    private static String contentClause(boolean globalScope)
    {
        if (globalScope)
        {
            return ""; //$NON-NLS-1$
        }
        return "\n> **Content was compared INSIDE THE SCOPE ONLY.** For an object in the scope " //$NON-NLS-1$
            + "above, EDT compared its own features - module text, form and template content, " //$NON-NLS-1$
            + "every plain property. Everywhere else those features were EXCLUDED from the " //$NON-NLS-1$
            + "comparison, feature by feature and sparing an object's containment-many " //$NON-NLS-1$
            + "collections of metadata objects: such an object is still matched, so it is still " //$NON-NLS-1$
            + "reported as added or deleted. What `identical` does NOT establish for a node " //$NON-NLS-1$
            + "outside the scope is that the excluded features were compared. Add that object " //$NON-NLS-1$
            + "to `scope`, or omit `scope` entirely, to have its content compared.\n"; //$NON-NLS-1$
    }

    /**
     * The reasons ONE added qualified name carries, bounded by the same limit as the bullet list
     * around it and named when it is cut, for the same reason that list is.
     * <p>
     * The outer limit does not bound this one, and the two are not the same quantity. The engine
     * reports an addition once, with a reason PER requested object that pulled it in, so a common
     * dependency of a large request - one module referenced by a thousand requested objects - is a
     * SINGLE bullet carrying a thousand reasons. The list around it was already cut to the limit
     * while that one line ran past every other section of the report: the report's own limit
     * undone one level further in.
     *
     * @param reasons why the engine added the name, as the platform reported it (may be
     *     {@code null}, which is how the platform spells no reasons at all)
     * @param limit largest number of reasons to list
     * @return the reasons, semicolon separated, carrying the whole count when it was cut
     */
    private static String joinReasons(List<String> reasons, int limit)
    {
        if (reasons == null || reasons.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        int shown = Math.min(reasons.size(), limit);
        return String.join("; ", reasons.subList(0, shown)) //$NON-NLS-1$
            + Pagination.truncationNotice(shown, reasons.size());
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
     * @param scope the scope as the tree's own reading saw it (may be {@code null}), used only
     *     to tell a run that named objects from a whole-configuration run when nothing was
     *     compared
     * @param collector the accumulated tree
     */
    private static void appendNodes(StringBuilder out, ScopeSnapshot scope, Collector collector)
    {
        out.append("\n## Top objects\n\n"); //$NON-NLS-1$
        // The last two numbers are spelled with the STATES' own labels, the same text the rows
        // below carry in their Change cell, so a reader can add up what the table shows. A node
        // the engine gave no verdict for is listed and counted here, and in "with differences" it
        // is not: that count is what the comparison FOUND, and nothing was found about this node.
        out.append("**Total:** ").append(collector.getTotal()).append(" top nodes — ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(collector.getDiffering()).append(" with differences, ") //$NON-NLS-1$
            .append(collector.getConflicts()).append(" conflicts, ") //$NON-NLS-1$
            .append(collector.getNotCompared()).append(' ')
            .append(ComparisonNodeState.NOT_COMPARED.label()).append(", ") //$NON-NLS-1$
            .append(collector.getNotReported()).append(' ')
            .append(ComparisonNodeState.NOT_REPORTED.label())
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
     * @param scope the scope as the tree's own reading saw it (may be {@code null})
     */
    private static void appendNothingCompared(StringBuilder out, ScopeSnapshot scope)
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
     * @param scope the scope as the tree's own reading saw it (may be {@code null})
     * @return {@code true} when the caller named at least one object on at least one side; an
     *     empty input scope on every side is the platform's "compare everything", not a request
     */
    private static boolean hasRequestedScope(ScopeSnapshot scope)
    {
        if (scope == null || !scope.isPresent())
        {
            return false;
        }
        for (ComparisonSide side : ComparisonSide.values())
        {
            if (!scope.requested(side).isEmpty())
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

/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.common.StringUtils;
import com._1c.g5.v8.dt.compare.core.PotentialMergeProblemDescription;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.SymlinkComparisonNode;
import com._1c.g5.v8.dt.form.compare.FormComparisonNode;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.compare.SupportStateReader.SupportState;

/**
 * Renders ONE expanded comparison-tree node to Markdown: the three-way property table, the per-side
 * form structure, the module section list, the vendor-support state, the child outline and the
 * POTENTIAL problems the engine reported for the subtree.
 *
 * <p>Three properties of this renderer are load-bearing and are each pinned by a test.</p>
 *
 * <ol>
 * <li><b>An unfinished subtree is reported as unfinished.</b> The comparison tree is LAZY
 * ({@code Unfinished} / {@code HasUnfinishedChildren} / {@code Finished}), so a node whose status is
 * not {@code Finished} has an empty or partial child list for a reason that has nothing to do with
 * the objects being equal. Every place that would otherwise say {@link #NO_DIFFERENCES} says
 * {@link #NOT_DETERMINED} instead while the node is unfinished, and the document opens with
 * {@link #NOT_FINISHED_NOTICE}. Rendering "no differences" over an uncompared subtree is the exact
 * lie this design exists to prevent.</li>
 * <li><b>Every label this class COMPUTES is locale-free.</b> Names come from the raw symlink
 * segment and from {@link StringUtils#nameToText}. The platform's own node labeller is deliberately
 * NOT used: it routes through a label function that branches on {@code Locale.getDefault()}, which
 * would make the English-only tool surface render Russian on a Russian EDT - the same defect already
 * fixed in {@code MetadataReferenceService.getFeatureLabel}. The claim covers what this class
 * WRITES, and there is exactly one thing it does not write: a
 * {@link PotentialMergeProblemDescription} is a bare pair of strings that the PLATFORM builds from
 * its own NLS bundles after a {@code Locale.getDefault()} lookup (read off
 * {@code MdObjectComparisonParticipant} on 2026.2.0+289, which uses the same lookup to pick Russian
 * type names), and the value holder carries no code or kind that could be rendered in their place.
 * Those two columns are therefore reproduced verbatim, their language follows the workbench, and the
 * table SAYS so in {@link #PLATFORM_TEXT_NOTICE} instead of letting the reader assume otherwise. The
 * locale-free identity of such a row is its {@code Node id} column.</li>
 * <li><b>Reads happen inside the caller's boundary.</b> Every accessor here touches comparison-tree
 * nodes, which live in the comparison's OWN BM store; the caller must already be inside
 * {@code ComparisonEngine.read(...)}. This class opens no transaction of its own and holds no
 * platform service.</li>
 * </ol>
 *
 * <p>Support state is read by {@link SupportStateReader} from the child nodes the platform actually
 * builds - see its javadoc for why the top-node accessor named in the 2025.2 javadoc is not used.</p>
 */
public final class ComparisonNodeRenderer
{
    /** Rendered when the node is finished and nothing differs. NEVER rendered for an unfinished node. */
    public static final String NO_DIFFERENCES = "No differences"; //$NON-NLS-1$

    /** Rendered in place of {@link #NO_DIFFERENCES} while the node's own status is not {@code Finished}. */
    public static final String NOT_DETERMINED = "Not determined yet (subtree not finished)"; //$NON-NLS-1$

    /**
     * The cell for a property whose value could not be READ on that side.
     * <p>
     * It is not an empty cell, and that is the whole point: an empty cell is this document's way of
     * saying "no value there", so rendering a failed read as one turns a gap in what this server
     * could see into a statement about the configuration - the exact substitution the unfinished
     * guard and the one-side guard exist to prevent, one level further down.
     */
    public static final String UNREADABLE = "_(could not be read)_"; //$NON-NLS-1$

    /** Rendered when the engine attached no comparison verdict to a node at all. */
    public static final String NO_VERDICT = "Not reported by the engine"; //$NON-NLS-1$

    /** Opening notice for a node the engine has not finished comparing. */
    public static final String NOT_FINISHED_NOTICE = "Subtree not finished"; //$NON-NLS-1$

    /**
     * Rendered with the problem table, and only with it. The two text columns are the platform's own
     * NLS wording, which makes that table the ONE part of this document whose language follows the
     * workbench locale rather than the tool - see the class javadoc for why it cannot be replaced.
     */
    public static final String PLATFORM_TEXT_NOTICE =
        "Problem and Details below are EDT's OWN diagnostic text, reproduced verbatim: the platform " //$NON-NLS-1$
            + "builds them from its NLS bundles under the workbench locale, so on a Russian EDT they " //$NON-NLS-1$
            + "read in Russian and two workbenches word the same problem differently. The Node id " //$NON-NLS-1$
            + "column is the locale-free identity of the row."; //$NON-NLS-1$

    /** Heading of the POTENTIAL-problem section; the word POTENTIAL is part of the contract. */
    private static final String POTENTIAL_HEADING = "## Potential problems\n\n"; //$NON-NLS-1$

    /** Suffix stripped from an EClass name before it is turned into a human kind label. */
    private static final String NODE_SUFFIX = "ComparisonNode"; //$NON-NLS-1$

    /** The three sides, in the order every table renders them. */
    private static final ComparisonSide[] SIDES =
        {ComparisonSide.MAIN, ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR};

    private ComparisonNodeRenderer()
    {
        // Utility class
    }

    /**
     * The narrow read port the renderer needs from the comparison session. Kept to two methods so a
     * unit test can drive the renderer over a stub node graph with no EDT present.
     */
    public interface NodeAccess
    {
        /**
         * The main / other / common-ancestor objects matched onto {@code node}.
         *
         * @param node the node to resolve
         * @return the compared objects, or {@code null} when the node carries none
         */
        IComparedObjects<?> comparedObjects(ComparisonNode node);

        /**
         * The POTENTIAL problems the engine recorded for one node.
         *
         * @param nodeId the node id
         * @return the descriptions, never {@code null}
         */
        List<PotentialMergeProblemDescription> potentialProblems(long nodeId);
    }

    /** Everything about the CALL that the rendered document reports back to the caller. */
    public static final class Request
    {
        /** The comparison this node belongs to. */
        public final String comparisonId;
        /** How the caller addressed the node (an FQN or a node id), used as the document heading. */
        public final String address;
        /** The side the caller addressed the node from. */
        public final ComparisonSide side;
        /** The node's OWN status, as observed after the bounded wait; may be {@code null}. */
        public final ComparisonNodeStatus status;
        /** How many child levels to descend (at least 1). */
        public final int depth;
        /** Maximum rows per table. */
        public final int limit;
        /** Language code for the form snapshot's titles, or {@code null} for the configuration default. */
        public final String language;

        /**
         * @param comparisonId the comparison id
         * @param address how the caller addressed the node
         * @param side the addressed side
         * @param status the node's own comparison status (may be {@code null} when unknown)
         * @param depth child levels to descend
         * @param limit maximum rows per table
         * @param language language code for the form snapshot (may be {@code null})
         */
        public Request(String comparisonId, String address, ComparisonSide side,
            ComparisonNodeStatus status, int depth, int limit, String language)
        {
            this.comparisonId = comparisonId == null ? "" : comparisonId; //$NON-NLS-1$
            this.address = address == null ? "" : address; //$NON-NLS-1$
            this.side = side == null ? ComparisonSide.MAIN : side;
            this.status = status;
            this.depth = Math.max(1, depth);
            this.limit = Math.max(1, limit);
            this.language = language;
        }
    }

    /**
     * Renders the expanded node.
     *
     * @param request the call description (never {@code null})
     * @param node the resolved comparison node (never {@code null})
     * @param access the read port; may be {@code null}, in which case the property and
     *            potential-problem sections degrade to an explicit "not read" line rather than to a
     *            silent empty table
     * @return the Markdown document
     */
    public static String render(Request request, ComparisonNode node, NodeAccess access)
    {
        boolean finished = request.status == ComparisonNodeStatus.FINISHED;
        StringBuilder sb = new StringBuilder();
        sb.append("# Comparison node: ").append(request.address).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!finished)
        {
            appendNotFinishedNotice(sb, request.status);
        }
        appendSummary(sb, request, node, finished);
        appendProperties(sb, request, node, access, finished);
        appendSupport(sb, node);
        appendFormStructure(sb, request, node, access);
        appendModuleSections(sb, request, node, finished);
        appendChildren(sb, request, node, finished);
        appendPotentialProblems(sb, request, node, access, finished);
        return sb.toString();
    }

    // ==================== Notice + summary ====================

    private static void appendNotFinishedNotice(StringBuilder sb, ComparisonNodeStatus status)
    {
        sb.append("> **").append(NOT_FINISHED_NOTICE).append("** - the engine has not finished ") //$NON-NLS-1$ //$NON-NLS-2$
            .append("comparing this node (status: ").append(statusText(status)) //$NON-NLS-1$
            .append("). Everything below is PARTIAL: an empty table here means \"not compared yet\", ") //$NON-NLS-1$
            .append("not \"the sides agree\". Call the tool again with a larger waitSeconds.\n\n"); //$NON-NLS-1$
    }

    private static void appendSummary(StringBuilder sb, Request request, ComparisonNode node,
        boolean finished)
    {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Comparison", request.comparisonId); //$NON-NLS-1$
        fields.put("Node id", Long.toString(nodeId(node))); //$NON-NLS-1$
        fields.put("Kind", kindOf(node)); //$NON-NLS-1$
        fields.put("Addressed side", sideLabel(request.side)); //$NON-NLS-1$
        for (ComparisonSide side : SIDES)
        {
            fields.put(sideLabel(side), dashIfEmpty(symlinkOf(node, side)));
        }
        fields.put("Node status", statusText(request.status)); //$NON-NLS-1$
        fields.put("State", stateOf(node, finished)); //$NON-NLS-1$
        sb.append(MarkdownUtils.keyValueTable("Field", "Value", fields)).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Properties ====================

    private static void appendProperties(StringBuilder sb, Request request, ComparisonNode node,
        NodeAccess access, boolean finished)
    {
        sb.append("## Properties\n\n"); //$NON-NLS-1$
        if (access == null)
        {
            sb.append("_(compared objects were not read for this call)_\n\n"); //$NON-NLS-1$
            return;
        }
        IComparedObjects<?> objects = access.comparedObjects(node);
        EObject[] sides = new EObject[SIDES.length];
        int presentSides = 0;
        for (int i = 0; i < SIDES.length; i++)
        {
            sides[i] = asEObject(objects, SIDES[i]);
            presentSides += sides[i] == null ? 0 : 1;
        }
        if (presentSides == 0)
        {
            sb.append("_(this node carries no compared model objects)_\n\n"); //$NON-NLS-1$
            return;
        }

        Map<String, String[]> rows = new LinkedHashMap<>();
        for (int i = 0; i < sides.length; i++)
        {
            collectProperties(rows, sides[i], i);
        }

        List<String> differing = new ArrayList<>();
        List<String> undetermined = new ArrayList<>();
        List<String> equal = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : rows.entrySet())
        {
            switch (compare(entry.getValue(), sides))
            {
                case DIFFERENT:
                    differing.add(entry.getKey());
                    break;
                case UNDETERMINED:
                    undetermined.add(entry.getKey());
                    break;
                default:
                    equal.add(entry.getKey());
                    break;
            }
        }
        // Differing rows first: with a limit in play, the rows that carry the answer must survive
        // truncation. Undetermined rows come next, ahead of the equal ones, because a row nobody
        // could read is the second thing a reader needs and the one thing a silent truncation
        // would turn into agreement. Within each group the model's own feature order is preserved.
        List<String> ordered = new ArrayList<>(differing);
        ordered.addAll(undetermined);
        ordered.addAll(equal);

        int total = ordered.size();
        int shown = Math.min(total, request.limit);
        sb.append("**Properties:** ").append(total).append(" (").append(differing.size()) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" differing"); //$NON-NLS-1$
        if (!undetermined.isEmpty())
        {
            sb.append(", ").append(undetermined.size()).append(" not readable"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(')').append(Pagination.truncationNotice(shown, total)).append("\n\n"); //$NON-NLS-1$

        if (total == 0)
        {
            sb.append("_(no comparable properties on this node)_\n\n"); //$NON-NLS-1$
            return;
        }

        sb.append(MarkdownUtils.tableHeader("Property", "Main", "Other", "Ancestor")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (int i = 0; i < shown; i++)
        {
            String name = ordered.get(i);
            String[] values = rows.get(name);
            sb.append(MarkdownUtils.tableRow(label(name), values[0], values[1], values[2]));
        }
        sb.append('\n');
        if (differing.isEmpty())
        {
            // "Nothing differs" is a claim about a COMPARISON, and one object is not a comparison:
            // with a single side present the other columns are empty because the object is absent,
            // not because the sides agree. Saying "no differences" there is the same lie the
            // unfinished guard exists to prevent, one level down.
            if (presentSides < 2)
            {
                sb.append("_Only one side carries this object, so its properties have nothing to " //$NON-NLS-1$
                    + "be compared against._\n\n"); //$NON-NLS-1$
            }
            else if (!undetermined.isEmpty())
            {
                // The same lie again, one step subtler: none of the rows that COULD be read
                // differ, but some could not be read at all, and "no differences" would cover
                // both with one word.
                sb.append("_No differences among the properties that could be read; ") //$NON-NLS-1$
                    .append(undetermined.size()).append(" could not be read on at least one " //$NON-NLS-1$
                        + "side and are not claimed either way._\n\n"); //$NON-NLS-1$
            }
            else
            {
                sb.append('_').append(finished ? NO_DIFFERENCES : NOT_DETERMINED)
                    .append(" in the compared properties._\n\n"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Adds {@code source}'s assignable properties into {@code rows} under column {@code index}. A
     * feature the side does not carry stays {@code null} and renders as an empty cell - which is also
     * how a side whose object is absent renders, and the summary table above says which case it is.
     * <p>
     * A property the introspector could not READ is the one case that does NOT render as an empty
     * cell: it gets {@link #UNREADABLE}. Both arrive from the introspector as a {@code null}
     * current value - the read is guarded so that one dangling proxy cannot abort the whole object
     * - and folding them together published a failure as an absence, which on a three-column
     * comparison also made two unreadable sides look like agreement.
     */
    private static void collectProperties(Map<String, String[]> rows, EObject source, int index)
    {
        if (source == null)
        {
            return;
        }
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(source))
        {
            String[] cells = rows.get(info.name);
            if (cells == null)
            {
                cells = new String[] {"", "", ""}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                rows.put(info.name, cells);
            }
            if (info.readFailed)
            {
                cells[index] = UNREADABLE;
            }
            else
            {
                cells[index] = info.currentValue == null ? "" : info.currentValue; //$NON-NLS-1$
            }
        }
    }

    /** What one property row establishes about the sides that carry an object. */
    private enum RowState
    {
        /** Two sides that were both READ carry different values. */
        DIFFERENT,
        /** Nothing disagrees, but at least one side could not be read, so nothing is established. */
        UNDETERMINED,
        /** Every side that carries an object was read and they all agree. */
        SAME
    }

    /**
     * What a row establishes, as THREE answers rather than two.
     * <p>
     * A difference between two sides that were both read is established whatever happened on the
     * third, so an unreadable side never hides a real difference. What it does prevent is the
     * OPPOSITE claim: with a side unreadable, "these agree" is not something anybody observed, and
     * the two-answer version reported exactly that - it compared the placeholder for a failed read
     * with the placeholder for an absent value and found them equal.
     *
     * @param values the three rendered cells
     * @param sides the three compared objects, {@code null} where the side has none
     * @return what the row establishes
     */
    private static RowState compare(String[] values, EObject[] sides)
    {
        Set<String> readable = new LinkedHashSet<>();
        boolean anyUnreadable = false;
        for (int i = 0; i < sides.length; i++)
        {
            if (sides[i] == null)
            {
                continue;
            }
            if (UNREADABLE.equals(values[i]))
            {
                anyUnreadable = true;
            }
            else
            {
                readable.add(values[i] == null ? "" : values[i]); //$NON-NLS-1$
            }
        }
        if (readable.size() > 1)
        {
            return RowState.DIFFERENT;
        }
        return anyUnreadable ? RowState.UNDETERMINED : RowState.SAME;
    }

    // ==================== Support state ====================

    private static void appendSupport(StringBuilder sb, ComparisonNode node)
    {
        SupportState state = SupportStateReader.read(node);
        if (state == null || state.isEmpty())
        {
            return;
        }
        sb.append("## Support settings\n\n"); //$NON-NLS-1$
        if (!state.parentConfigurationName.isEmpty())
        {
            sb.append("**Parent configuration:** ").append(state.parentConfigurationName) //$NON-NLS-1$
                .append("\n\n"); //$NON-NLS-1$
        }
        sb.append(MarkdownUtils.tableHeader("Setting", "Main", "Other", "Ancestor")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (state.hasUserMode())
        {
            sb.append(MarkdownUtils.tableRow("User support mode", state.mainUserMode, //$NON-NLS-1$
                state.otherUserMode, state.ancestorUserMode));
        }
        if (state.hasParentMode())
        {
            sb.append(MarkdownUtils.tableRow("Parent support mode", state.mainParentMode, //$NON-NLS-1$
                state.otherParentMode, state.ancestorParentMode));
        }
        sb.append('\n');
    }

    // ==================== Form structure ====================

    private static void appendFormStructure(StringBuilder sb, Request request, ComparisonNode node,
        NodeAccess access)
    {
        if (!(node instanceof FormComparisonNode) || access == null)
        {
            return;
        }
        IComparedObjects<?> objects = access.comparedObjects(node);
        for (ComparisonSide side : SIDES)
        {
            EObject form = asEObject(objects, side);
            if (form == null)
            {
                continue;
            }
            sb.append("## Form structure (").append(sideLabel(side)).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            // The SHARED form reader, on the per-side model object the comparison already resolved.
            // Its FQN-based entry point is deliberately not used: it addresses our workspace project,
            // not the comparison's virtual one, so it would render the wrong side's form.
            //
            // The caller's row limit is HANDED DOWN. This document promises "maximum rows per
            // table", and these are its tables too - up to three of them per node, one per side.
            // Without the limit the reader applied only its own MAX_NODES guard, so limit=1 still
            // produced every attribute, command, parameter and event handler the form has and an
            // item outline of up to 5000 lines, in a section the caller had asked to keep small.
            sb.append(FormStructureReader.render(request.address, form, request.language,
                request.limit));
            sb.append('\n');
        }
    }

    // ==================== Module sections ====================

    private static void appendModuleSections(StringBuilder sb, Request request, ComparisonNode node,
        boolean finished)
    {
        if (!(node instanceof BslModuleComparisonNode))
        {
            return;
        }
        sb.append("## Module sections\n\n"); //$NON-NLS-1$
        List<ComparisonNode> flat = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        boolean[] truncated = new boolean[1];
        for (BslModuleSectionComparisonNode section : ((BslModuleComparisonNode)node).getChildren())
        {
            flatten(section, 1, request.depth, request.limit, flat, depths, truncated);
        }
        if (flat.isEmpty())
        {
            sb.append('_').append(finished ? NO_DIFFERENCES : NOT_DETERMINED)
                .append(" in the module sections._\n\n"); //$NON-NLS-1$
            return;
        }
        // The rows are built FIRST so the count above them is a count of rows actually rendered:
        // `flat` may carry a node that is not a section, and a header that promised more rows than
        // the table holds is the same kind of wrong number as a capped count read as a total.
        StringBuilder rows = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < flat.size(); i++)
        {
            if (!(flat.get(i) instanceof BslModuleSectionComparisonNode))
            {
                continue;
            }
            BslModuleSectionComparisonNode section = (BslModuleSectionComparisonNode)flat.get(i);
            rows.append(MarkdownUtils.tableRow(depths.get(i).toString(),
                sectionType(section), dashIfEmpty(section.getName(ComparisonSide.MAIN)),
                dashIfEmpty(section.getName(ComparisonSide.OTHER)),
                dashIfEmpty(section.getName(ComparisonSide.COMMON_ANCESTOR)),
                stateOf(section, finished)));
            shown++;
        }
        // `flatten` raises the flag when a section was DECLINED, and until this line nothing read
        // it: the table was cut and looked whole, while the child outline and the problem table
        // beside it both announce the very same cap. A module whose sections were cut is exactly
        // the case where a reader concludes "that procedure is not in the module".
        sb.append("**Sections shown:** ").append(shown) //$NON-NLS-1$
            .append(truncated[0] ? Pagination.limitReachedNotice(request.limit) : "") //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Depth", "Type", "Main", "Other", "Ancestor", "State")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        sb.append(rows);
        sb.append('\n');
    }

    private static String sectionType(BslModuleSectionComparisonNode section)
    {
        return section.getSectionType() == null ? "" : section.getSectionType().getName(); //$NON-NLS-1$
    }

    // ==================== Children ====================

    private static void appendChildren(StringBuilder sb, Request request, ComparisonNode node,
        boolean finished)
    {
        if (node instanceof BslModuleComparisonNode)
        {
            // Its children ARE the sections, already rendered with their own columns.
            return;
        }
        sb.append("## Children\n\n"); //$NON-NLS-1$
        List<ComparisonNode> flat = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        boolean[] truncated = new boolean[1];
        for (ComparisonNode child : childrenOf(node))
        {
            flatten(child, 1, request.depth, request.limit, flat, depths, truncated);
        }
        if (flat.isEmpty())
        {
            sb.append('_').append(finished ? NO_DIFFERENCES : NOT_DETERMINED)
                .append(" in the child nodes._\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append("**Children shown:** ").append(flat.size()) //$NON-NLS-1$
            .append(truncated[0] ? Pagination.limitReachedNotice(request.limit) : "") //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Depth", "Node id", "Kind", "Main", "Other", "Ancestor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "State")); //$NON-NLS-1$
        for (int i = 0; i < flat.size(); i++)
        {
            ComparisonNode child = flat.get(i);
            sb.append(MarkdownUtils.tableRow(depths.get(i).toString(), Long.toString(nodeId(child)),
                kindOf(child), dashIfEmpty(nameOf(child, ComparisonSide.MAIN)),
                dashIfEmpty(nameOf(child, ComparisonSide.OTHER)),
                dashIfEmpty(nameOf(child, ComparisonSide.COMMON_ANCESTOR)),
                stateOf(child, finished)));
        }
        sb.append('\n');
    }

    /**
     * Depth-first flattening of the child tree, bounded by BOTH the requested depth and the row
     * limit. Bounded on entry so a pathological subtree cannot be materialised before the cap runs.
     * <p>
     * {@code truncated[0]} is raised only when a node was actually DECLINED. Draining the budget is
     * not truncation: a subtree with exactly {@code limit} nodes renders every one of them, and
     * inferring truncation from an exhausted budget would tell the caller to re-run with a higher
     * limit for a page that is already complete. Same shape, and the same reason, as
     * {@code FormStructureReader.renderItems}.
     */
    private static void flatten(ComparisonNode node, int depth, int maxDepth, int limit,
        List<ComparisonNode> flat, List<Integer> depths, boolean[] truncated)
    {
        if (node == null)
        {
            return;
        }
        if (flat.size() >= limit)
        {
            truncated[0] = true;
            return;
        }
        flat.add(node);
        depths.add(Integer.valueOf(depth));
        if (depth >= maxDepth)
        {
            return;
        }
        for (ComparisonNode child : childrenOf(node))
        {
            flatten(child, depth + 1, maxDepth, limit, flat, depths, truncated);
        }
    }

    // ==================== Potential problems ====================

    private static void appendPotentialProblems(StringBuilder sb, Request request,
        ComparisonNode node, NodeAccess access, boolean finished)
    {
        sb.append(POTENTIAL_HEADING);
        if (access == null)
        {
            sb.append("_(potential problems were not read for this call)_\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append("> POTENTIAL only: the engine reports these BEFORE anything is applied. A ") //$NON-NLS-1$
            .append("definitive blocking / non-blocking verdict is produced only by a merge run, ") //$NON-NLS-1$
            .append("which this read-only toolset never performs.\n\n"); //$NON-NLS-1$

        // flatten() pairs its two output lists positionally and stops on flat.size() >= limit, so it
        // gets its OWN list: seeding it with the addressed node would offset the pairing by one and
        // spend one row of the cap before the first child is even visited. The addressed node is
        // prepended afterwards, because problems recorded on it belong to the report too.
        List<ComparisonNode> descendants = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        boolean[] descendantsTruncated = new boolean[1];
        for (ComparisonNode child : childrenOf(node))
        {
            flatten(child, 1, request.depth, request.limit, descendants, depths, descendantsTruncated);
        }
        List<ComparisonNode> scope = new ArrayList<>(descendants.size() + 1);
        scope.add(node);
        scope.addAll(descendants);

        StringBuilder rows = new StringBuilder();
        int count = 0;
        // Truncation is a row that was DECLINED, never a budget that merely ran out: exactly `limit`
        // problems is a complete page. The scan continues past a full budget only until the first
        // real row has to be refused, and `scope` is itself bounded by the same limit, so this
        // cannot walk more than one capped page further.
        boolean truncated = false;
        outer: for (ComparisonNode candidate : scope)
        {
            List<PotentialMergeProblemDescription> problems =
                access.potentialProblems(nodeId(candidate));
            if (problems == null)
            {
                continue;
            }
            for (PotentialMergeProblemDescription problem : problems)
            {
                if (problem == null)
                {
                    continue;
                }
                if (count >= request.limit)
                {
                    truncated = true;
                    break outer;
                }
                rows.append(MarkdownUtils.tableRow(Long.toString(nodeId(candidate)),
                    problem.getShortDescription(), problem.getFullDescription()));
                count++;
            }
        }
        if (count == 0)
        {
            // "None reported" is a claim about what was LOOKED AT, and two separate things narrow
            // that. One is the lazy tree, already handled. The other is this section's own row
            // limit: `flatten` caps the DESCENDANT LIST before a single problem is read off it, so
            // with depth=1 and limit=2 a problem on the third child is never asked for. Announcing
            // an absence over nodes nobody visited is the same lie as "no differences" over an
            // uncompared subtree, and it is announced here instead.
            sb.append("_(none reported") //$NON-NLS-1$
                .append(incompleteScanNote(finished, descendantsTruncated[0], request.limit))
                .append(")_\n\n"); //$NON-NLS-1$
            return;
        }
        // The count is a rendered-row count, not a subtree total: collection stops at the cap, so
        // the cap being reached must be SAID, exactly as the child outline says it. A bare capped
        // number read as a total is the same class of lie as "no differences" over an unfinished
        // subtree.
        sb.append("**Potential problems:** ").append(count) //$NON-NLS-1$
            .append(truncated ? Pagination.limitReachedNotice(request.limit) : "") //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        if (descendantsTruncated[0])
        {
            // A capped ROW list and a capped SCOPE are different caps, and only the first one is
            // announced by the notice above. This one says the count covers the nodes that were
            // visited rather than the subtree.
            sb.append("> The scan was partial: only the first ").append(request.limit) //$NON-NLS-1$
                .append(" descendant nodes were examined, so this count is for those nodes and ") //$NON-NLS-1$
                .append("not for the whole subtree. Raise limit to widen it.\n\n"); //$NON-NLS-1$
        }
        // Attached to the TABLE, not to the section: with no rows there is no platform-authored text
        // to disclaim, and a disclaimer printed on every node render regardless would be noise that
        // stops being read exactly when it starts mattering.
        sb.append("> ").append(PLATFORM_TEXT_NOTICE).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(MarkdownUtils.tableHeader("Node id", "Problem", "Details")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append(rows).append('\n');
    }

    /**
     * What narrows a "nothing found" answer in the problem section, spelled out so the reader is
     * not left to infer it from a count.
     *
     * @param finished whether the addressed node's own status is {@code Finished}
     * @param descendantsTruncated whether the descendant list was cut short before its problems
     *            were read
     * @param limit the row limit that cut it
     * @return the clause to append inside the "(none reported...)" parentheses, possibly empty
     */
    private static String incompleteScanNote(boolean finished, boolean descendantsTruncated,
        int limit)
    {
        StringBuilder note = new StringBuilder();
        if (!finished)
        {
            note.append(", and this subtree is not finished, so the list is incomplete"); //$NON-NLS-1$
        }
        if (descendantsTruncated)
        {
            note.append(", and only the first ").append(limit) //$NON-NLS-1$
                .append(" descendant nodes were examined - the rest were never asked, so this is ") //$NON-NLS-1$
                .append("NOT a statement that the subtree has no problems; raise limit to widen it"); //$NON-NLS-1$
        }
        return note.toString();
    }

    // ==================== Shared node accessors ====================

    /** Direct children of a node, tolerating a null node and a null child list. */
    private static List<ComparisonNode> childrenOf(ComparisonNode node)
    {
        if (node == null)
        {
            return Collections.emptyList();
        }
        List<ComparisonNode> result = new ArrayList<>();
        List<ComparisonNode> children = node.<ComparisonNode> getChildren();
        if (children == null)
        {
            return result;
        }
        for (ComparisonNode child : children)
        {
            if (child != null)
            {
                result.add(child);
            }
        }
        return result;
    }

    private static long nodeId(ComparisonNode node)
    {
        return node == null ? 0L : node.bmGetId();
    }

    /**
     * The per-side symlink of a node that has one. A node without a symlink (a feature / collection
     * node) legitimately has none, and gets an empty string rather than an invented name.
     */
    private static String symlinkOf(ComparisonNode node, ComparisonSide side)
    {
        if (node instanceof SymlinkComparisonNode)
        {
            String symlink = ((SymlinkComparisonNode)node).getSymlink(side);
            return symlink == null ? "" : symlink; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    /** The last segment of a node's per-side symlink - a deterministic, locale-free short name. */
    private static String nameOf(ComparisonNode node, ComparisonSide side)
    {
        String symlink = symlinkOf(node, side);
        int dot = symlink.lastIndexOf('.');
        return dot >= 0 && dot < symlink.length() - 1 ? symlink.substring(dot + 1) : symlink;
    }

    /**
     * The node's structural kind, derived from its EClass name with the {@code ComparisonNode}
     * suffix removed - e.g. {@code ChildMdObjectComparisonNode} renders as "Child md object".
     */
    private static String kindOf(ComparisonNode node)
    {
        if (node == null)
        {
            return ""; //$NON-NLS-1$
        }
        String name = node.eClass() == null ? node.getClass().getSimpleName() : node.eClass().getName();
        if (name == null || name.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        if (name.endsWith(NODE_SUFFIX) && name.length() > NODE_SUFFIX.length())
        {
            name = name.substring(0, name.length() - NODE_SUFFIX.length());
        }
        return label(name);
    }

    /**
     * Decodes the node's difference state from {@code ComparisonFlags} and the one-sided markers.
     * While the node is unfinished this NEVER reports {@link #NO_DIFFERENCES}.
     */
    private static String stateOf(ComparisonNode node, boolean finished)
    {
        if (node == null)
        {
            return ""; //$NON-NLS-1$
        }
        ComparisonFlags flags = node.getComparisonFlags();
        if (flags != null && flags.hasDoubleChanges())
        {
            return "CONFLICT (changed on both sides)"; //$NON-NLS-1$
        }
        if (node.isOneSideNode())
        {
            return "ONE SIDE (" + sideLabel(node.getNodeSide()) + " only)"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (flags == null)
        {
            // No flags object is the ABSENCE of a verdict, not a verdict of "equal". Reporting it
            // as "no differences" would invent a comparison result the engine never produced.
            return NO_VERDICT;
        }
        if (flags.hasChangedMainOther())
        {
            return "CHANGED (main vs other)"; //$NON-NLS-1$
        }
        if (flags.hasDiffsMainOther())
        {
            return "CHANGED IN SUBTREE"; //$NON-NLS-1$
        }
        return finished ? NO_DIFFERENCES : NOT_DETERMINED;
    }

    private static EObject asEObject(IComparedObjects<?> objects, ComparisonSide side)
    {
        if (objects == null)
        {
            return null;
        }
        Object value = objects.getComparedObject(side);
        return value instanceof EObject ? (EObject)value : null;
    }

    /** The English side names used by every table and by the {@code side} parameter. */
    private static String sideLabel(ComparisonSide side)
    {
        if (side == ComparisonSide.OTHER)
        {
            return "Other"; //$NON-NLS-1$
        }
        if (side == ComparisonSide.COMMON_ANCESTOR)
        {
            return "Ancestor"; //$NON-NLS-1$
        }
        return "Main"; //$NON-NLS-1$
    }

    private static String statusText(ComparisonNodeStatus status)
    {
        return status == null ? "unknown" : status.getName(); //$NON-NLS-1$
    }

    /**
     * The language-neutral rendering of a programmatic name, the same fallback EDT itself uses.
     * Deliberately not the platform node labeller, which branches on the IDE locale.
     */
    private static String label(String name)
    {
        if (name == null || name.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            String text = StringUtils.nameToText(name);
            if (text != null && !text.isEmpty())
            {
                return text;
            }
        }
        catch (RuntimeException e) // NOSONAR a label must never fail the read; the raw name is a fine fallback
        {
            // fall through to the raw name
        }
        return name;
    }

    private static String dashIfEmpty(String value)
    {
        return value == null || value.isEmpty() ? "-" : value; //$NON-NLS-1$
    }
}

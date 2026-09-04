/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <li><b>A SCOPED run says so, and says it about the run.</b> A scoped comparison turns on
 * {@code mergeObjectsContent}, and {@code MdCompareUtils.isExcludeObjectsContentFeature} then
 * drops the own features of every object whose qualified name is not at or under an entry of the
 * effective scope - per feature, sparing the containment-many collections of {@code MdObject}s.
 * Such a node is matched, it was compared without the features that were dropped, and its flags
 * read exactly like those of a node that WAS compared on all of them and found equal. So
 * {@link Request#coverage} carries the fact into the document: it opens with
 * {@link #SCOPED_RUN_NOTICE}, the engine-filled tables qualify their emptiness with
 * {@link #CONTENT_MAY_BE_EXCLUDED} instead of saying {@link #NO_DIFFERENCES}, and an
 * {@code identical} {@code State} cell is qualified. What it deliberately does NOT do is decide
 * which side of the scope line THIS node fell on - see {@link ContentCoverage} for why no reading
 * of the tree answers that. The property table is not covered either: it is this class's own read
 * of the matched objects, so it still answers, and the notice says so.</li>
 * <li><b>No phrase of equality contradicts a number in the same document.</b> The {@code State}
 * cell comes from the platform's flags and the property table from this class's own reading, and
 * they can disagree: a node whose flags say {@code identical} while the table beside it counts a
 * differing property was measured live. The cell is then qualified with
 * {@link #STATE_DISPUTED_BY_PROPERTIES} and both facts are stated, because the two answer
 * different questions and picking one would delete an observation.</li>
 * <li><b>An unfinished subtree is reported as unfinished.</b> The comparison tree is LAZY
 * ({@code Unfinished} / {@code HasUnfinishedChildren} / {@code Finished}), so a node whose status is
 * not {@code Finished} has an empty or partial child list for a reason that has nothing to do with
 * the objects being equal. Every place that would otherwise say {@link #NO_DIFFERENCES} says
 * {@link #NOT_DETERMINED} instead while the node is unfinished, every {@code State} cell reads
 * {@link ComparisonNodeState#NOT_COMPARED}, and the document opens with
 * {@link #NOT_FINISHED_NOTICE}. Rendering "no differences" over an uncompared subtree is the exact
 * lie this design exists to prevent.</li>
 * <li><b>The state of a node is decoded ONCE, for both views.</b> Every {@code State} cell here is
 * {@link ComparisonNodeState#label()}, the same text {@link ComparisonTreeReport} puts in its
 * {@code Change} column for the same node. This class decided it separately until that separation
 * made the two documents contradict each other on a node both sides had changed the same way; see
 * {@link ComparisonNodeState} for what the two answers were.</li>
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
    /**
     * Rendered for a SECTION of this document that is finished and holds nothing differing - the
     * property table, the module-section list, the child outline. NEVER rendered for an unfinished
     * node.
     * <p>
     * It is not the vocabulary of the {@code State} column: what a NODE is doing across the three
     * sides is named by {@link ComparisonNodeState}, so that this document and the report it was
     * reached from cannot word the same node differently.
     */
    public static final String NO_DIFFERENCES = "No differences"; //$NON-NLS-1$

    /** Rendered in place of {@link #NO_DIFFERENCES} while the node's own status is not {@code Finished}. */
    public static final String NOT_DETERMINED = "Not determined yet (subtree not finished)"; //$NON-NLS-1$

    /**
     * Rendered in place of {@link #NO_DIFFERENCES} in every document of a SCOPED run.
     * <p>
     * A scope does not narrow the tree, it narrows what is compared inside it: with
     * {@code mergeObjectsContent} on - which is what a scoped run sets -
     * {@code MdCompareUtils.isExcludeObjectsContentFeature} EXCLUDES a feature of an object whose
     * qualified name is not at or under an entry of the effective scope. It is applied per
     * FEATURE, and not to every one of them: a containment-many collection of {@code MdObject}s
     * is spared, so child object nodes can still be built under an object whose other features
     * were dropped.
     * <p>
     * So an empty table in such a run does not carry {@link #NO_DIFFERENCES}'s claim - and the
     * phrase put in its place says that and stops, without going on to state what WAS looked at.
     * Which objects of the run the exclusion reached is not readable from a node
     * ({@link ContentCoverage}), so the phrase states the run's limit and leaves the reader to
     * place the node in it rather than placing it wrongly.
     */
    public static final String CONTENT_MAY_BE_EXCLUDED = "this run was SCOPED: outside the scope " //$NON-NLS-1$
        + "EDT excluded an object's own features feature by feature, sparing its containment-many " //$NON-NLS-1$
        + "collections of metadata objects, so an empty table here is not by itself \"the sides " //$NON-NLS-1$
        + "agree\""; //$NON-NLS-1$

    /** Opening notice for a document produced by a SCOPED run. */
    public static final String SCOPED_RUN_NOTICE = "Scoped comparison"; //$NON-NLS-1$

    /**
     * What the {@code State} cell adds when the platform calls a node identical while THIS
     * document's own property table counts a difference in it.
     * <p>
     * The two answer different questions - the flags are the engine's verdict for the node, the
     * table is this server reading every assignable property off each side - and a report may not
     * pick one of its own numbers and contradict it a paragraph later. Both are named.
     */
    public static final String STATE_DISPUTED_BY_PROPERTIES = " (EDT's node flags) - contradicted " //$NON-NLS-1$
        + "here: "; //$NON-NLS-1$

    /**
     * What the {@code State} cell adds in every document of a SCOPED run.
     * <p>
     * It qualifies the label without replacing it, and it stops at what the run did: it does not
     * go on to say which of this node's features the flags then speak for, because the exclusion
     * is per feature and spares the containment-many collections (see
     * {@link #CONTENT_MAY_BE_EXCLUDED}), so no such list is derivable here.
     */
    public static final String STATE_SCOPED_RUN =
        " - SCOPED run: outside the scope EDT excluded an object's own features feature by " //$NON-NLS-1$
            + "feature, sparing its containment-many collections of metadata objects, so if this " //$NON-NLS-1$
            + "object is one of those it was compared without the features that were excluded"; //$NON-NLS-1$

    /**
     * The cell for a property whose value could not be READ on that side.
     * <p>
     * It is not an empty cell, and that is the whole point: an empty cell is this document's way of
     * saying "no value there", so rendering a failed read as one turns a gap in what this server
     * could see into a statement about the configuration - the exact substitution the unfinished
     * guard and the one-side guard exist to prevent, one level further down.
     * <p>
     * It is a RENDERING and nothing else. Whether a side was read is carried beside the cell in
     * {@link PropertyRow#readFailed}, never recovered by comparing the cell against this string: a
     * metadata property whose real value happens to be this text would otherwise be classified as
     * unread, and a difference it carries would be downgraded to {@link RowState#UNDETERMINED} and
     * dropped from the differing count while the document announced that the property cannot be
     * read.
     */
    public static final String UNREADABLE = "_(could not be read)_"; //$NON-NLS-1$

    /**
     * The cell for a side whose object does not HAVE this property at all.
     * <p>
     * Two matched sides need not be instances of the same concrete class - form elements are the
     * ordinary case - so a property assignable on one of them can be a property the other does not
     * carry. Rendering that as an empty cell said "this side has no value there", which is a
     * statement about the object's contents; the truth is that the object has no such slot. The
     * two then compared equal whenever the side that HAS the property left it empty, so the
     * differing count could be zero and the document could announce no property differences over
     * sides that do not even agree on which properties exist.
     * <p>
     * Like {@link #UNREADABLE} this is a RENDERING and nothing else, and for the same reason: any
     * text a cell can carry is text a metadata property can legitimately hold. Whether the side
     * carries the property is held beside the cell in {@link PropertyRow#present}, and it is that
     * flag - never the rendered text - that {@link #compare} reads back.
     * <p>
     * It is not used for a side that carries no compared OBJECT: there the whole row is empty
     * because the object is absent, which the summary above the table already states.
     */
    public static final String NOT_ON_THIS_SIDE = "_(not a property of this side)_"; //$NON-NLS-1$

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

    /**
     * How many nodes the module-section walk may VISIT before it stops descending - the bound the
     * row limit stopped providing.
     *
     * <h2>Why it has to exist</h2>
     * The walk collects only sections but descends through children of ANY type, because
     * {@code BslModuleSectionComparisonNode.getChildren()} is a BRIDGE into the generic
     * {@code TopComparisonNode} implementation: it returns {@code topChildren} plus
     * {@code containmentChildren} with no filtering by type at all, so the narrow element type in
     * the interface is a generics declaration and nothing more. A node that costs no row costs no
     * row budget either, so the number of nodes visited is no longer capped by {@code limit}, and
     * depth alone bounds it at {@code branching} raised to the requested depth - which is not a
     * bound.
     *
     * <h2>Why this number</h2>
     * 10 000 is twenty times the largest page this table can render (the tool's {@code limit}
     * caps at 500), so no module whose sections would have fitted in a page can reach it. It
     * exists for a pathological subtree, and reaching it is REPORTED rather than swallowed - see
     * {@link #SECTION_WALK_CUT_SHORT}.
     */
    static final int MAX_SECTION_WALK_NODES = 10_000;

    /**
     * What the section table appends when its walk ran into {@link #MAX_SECTION_WALK_NODES}.
     * <p>
     * Its own sentence rather than the row-limit notice, because raising {@code limit} would not
     * widen it: no row was declined, the nodes carrying them were never looked at. Telling a
     * caller to pass a higher limit for that would be a wrong number in a third direction.
     */
    private static final String SECTION_WALK_CUT_SHORT =
        " (the section walk stopped after " + MAX_SECTION_WALK_NODES + " nodes, so anything " //$NON-NLS-1$ //$NON-NLS-2$
            + "below that point was never looked at - this is NOT a statement that there are no " //$NON-NLS-1$
            + "more sections; lower depth, or expand the module's parts one at a time)"; //$NON-NLS-1$

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

    /**
     * Whether the comparison compared content everywhere, or ran under a SCOPE that excluded the
     * own features of everything outside it.
     * <p>
     * The document has to carry it because the two look identical from inside a node: a node
     * whose own features the engine excluded reports exactly the flags of one it compared on all
     * of them and found equal.
     *
     * <h2>Why this is a fact about the RUN and not about the node</h2>
     * It used to claim the node, decided by {@code IComparisonSession.isInScope(node)}. Read off
     * the bytecode of {@code com._1c.g5.v8.dt.compare} 29.0.0, that predicate answers
     * {@code false} for every node that is not a {@code SymlinkComparisonNode}, and for one that
     * IS it calls {@code ComparisonUtils.isSubsymlinkOf} BOTH WAYS - so a scope entry's ANCESTORS
     * count as in scope too. The exclusion the document is describing is decided by a different
     * predicate: {@code MdCompareUtils.isObjectAndContentInScope} tests the compared object's
     * QUALIFIED NAME against {@code handle.getScope(side)} in ONE direction only (at or UNDER an
     * entry), and it is applied per FEATURE, sparing the containment-many collections of
     * {@code MdObject}s. The two disagree in both directions - with a scope of
     * {@code Catalog.Products.Form.X} the session calls the parent {@code Catalog.Products} in
     * scope while EDT excluded its own features, and it calls a non-symlink member of a genuinely
     * compared object out of scope - and nothing else readable from a node reproduces the real
     * predicate either, because the qualified name it tests comes from an
     * {@code IQualifiedNameProvider} over the compared objects and the carve-out is per feature.
     * <p>
     * So the coverage says what the run did and leaves the node's place in it to the reader. That
     * is weaker than a per-node verdict and it is the strongest thing that cannot be wrong - and
     * it is the same statement {@link ComparisonTreeReport} already prints once for the whole
     * comparison, so the two documents now agree instead of one of them claiming more.
     */
    public enum ContentCoverage
    {
        /**
         * A whole-configuration run: {@code mergeObjectsContent} is off, nothing was excluded
         * anywhere, so this node's own features WERE compared.
         */
        COMPARED,

        /**
         * A SCOPED run: outside the effective scope EDT excluded an object's own features from
         * the comparison, per feature and sparing the containment-many collections of
         * {@code MdObject}s. Whether THIS node was one of the objects that reached is not
         * knowable from the tree, so the document states the run's limit.
         */
        SCOPED_RUN;

        /**
         * The coverage every node of a run of this shape carries.
         *
         * @param wholeConfigurationRun the session's OWN saved answer
         *     ({@code IComparisonSession.isGlobalScope}), which is what decided
         *     {@code mergeObjectsContent} at launch; recomputing it from the scope object would
         *     answer about the scope as the engine extended it rather than about the setting the
         *     run started with
         * @return {@link #COMPARED} for a whole-configuration run, {@link #SCOPED_RUN} otherwise
         */
        public static ContentCoverage ofRun(boolean wholeConfigurationRun)
        {
            return wholeConfigurationRun ? COMPARED : SCOPED_RUN;
        }
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
        /** Whether the RUN compared content everywhere, or ran under a scope that excluded some. */
        public final ContentCoverage coverage;

        /**
         * @param comparisonId the comparison id
         * @param address how the caller addressed the node
         * @param side the addressed side
         * @param status the node's own comparison status (may be {@code null} when unknown)
         * @param depth child levels to descend
         * @param limit maximum rows per table
         * @param language language code for the form snapshot (may be {@code null})
         * @param coverage whether the RUN compared content everywhere; {@code null} is read as
         *     {@link ContentCoverage#COMPARED}, which is what a whole-configuration run always is
         */
        public Request(String comparisonId, String address, ComparisonSide side,
            ComparisonNodeStatus status, int depth, int limit, String language,
            ContentCoverage coverage)
        {
            this.comparisonId = comparisonId == null ? "" : comparisonId; //$NON-NLS-1$
            this.address = address == null ? "" : address; //$NON-NLS-1$
            this.side = side == null ? ComparisonSide.MAIN : side;
            this.status = status;
            this.depth = Math.max(1, depth);
            this.limit = Math.max(1, limit);
            this.language = language;
            this.coverage = coverage == null ? ContentCoverage.COMPARED : coverage;
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
        // Read BEFORE the summary, because the summary's State cell has to be able to name a
        // disagreement between the platform's verdict and this document's own property count.
        // Counting the properties twice - once for the cell, once for the table - would let the
        // two halves of one document disagree about the very number the cell exists to reconcile.
        PropertyDigest properties = digestProperties(node, access);
        StringBuilder sb = new StringBuilder();
        sb.append("# Comparison node: ").append(request.address).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!finished)
        {
            appendNotFinishedNotice(sb, request.status);
        }
        // Independent of finishedness, and printed alongside it rather than instead of it: a
        // document can be both unfinished and produced by a scoped run, and each notice governs
        // different words further down.
        if (request.coverage == ContentCoverage.SCOPED_RUN)
        {
            appendScopedRunNotice(sb);
        }
        appendSummary(sb, request, node, finished, properties);
        appendProperties(sb, request, properties, finished);
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

    /**
     * Says what a SCOPED run did not compare, and says exactly which parts of the document that
     * governs.
     * <p>
     * It states the RUN, not this node. Which objects EDT excluded is decided by a predicate no
     * reading of the tree reproduces (see {@link ContentCoverage}), so the notice hands the reader
     * the rule and the scope list - which {@code compare_configurations} prints - instead of a
     * verdict that would be wrong in both directions.
     * <p>
     * Named part by part on purpose. The exclusion is applied by the ENGINE, to an object's own
     * features and one feature at a time, so it governs the {@code State} cell, the child
     * outline, the module sections and the potential problems. It does NOT govern the property
     * table, which this server builds by reading the matched objects itself: that table is why a
     * node reported {@code identical} here can still show a property differing across the sides.
     * Blanketing the whole document with one caveat would hide the one section that still carries
     * an answer.
     * <p>
     * And it describes the exclusion no more widely than the predicate applies it. The carve-out
     * for a containment-many collection of {@code MdObject}s means an excluded object can still
     * have child object nodes built under it, so the notice never says that nothing below such a
     * node was looked at: it says an empty table is not by itself agreement, which is all that
     * follows.
     *
     * @param sb the document being assembled
     */
    private static void appendScopedRunNotice(StringBuilder sb)
    {
        sb.append("> **").append(SCOPED_RUN_NOTICE).append("** - this comparison ran with a ") //$NON-NLS-1$
            .append("`scope`. Outside the scope EDT EXCLUDED an object's own features from the ") //$NON-NLS-1$
            .append("comparison, feature by feature and sparing its containment-many collections ") //$NON-NLS-1$
            .append("of metadata objects; such an object is still MATCHED, so it is still ") //$NON-NLS-1$
            .append("reported as added or deleted. **Whether THIS object is one of them is not ") //$NON-NLS-1$
            .append("stated here** - the comparison tree does not answer it; the ") //$NON-NLS-1$
            .append("compare_configurations report lists the effective scope, and an object is ") //$NON-NLS-1$
            .append("inside it when its qualified name IS an entry or sits under one. So an ") //$NON-NLS-1$
            .append("empty child or section table below is not by itself a statement that ") //$NON-NLS-1$
            .append("\"the sides agree\". The property table is this server's own read of the ") //$NON-NLS-1$
            .append("matched objects and is NOT affected. Re-run compare_configurations with ") //$NON-NLS-1$
            .append("this object in `scope`, or with no `scope` at all, to have its content ") //$NON-NLS-1$
            .append("compared for certain.\n\n"); //$NON-NLS-1$
    }

    private static void appendSummary(StringBuilder sb, Request request, ComparisonNode node,
        boolean finished, PropertyDigest properties)
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
        fields.put("State", summaryState(request, node, finished, properties)); //$NON-NLS-1$
        sb.append(MarkdownUtils.keyValueTable("Field", "Value", fields)).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (disputedByProperties(node, finished, properties))
        {
            sb.append("> **The State cell and the property table below disagree, and both are ") //$NON-NLS-1$
                .append("reported.** EDT's own comparison flags call this node identical, while ") //$NON-NLS-1$
                .append(properties.differing.size())
                .append(properties.differing.size() == 1 ? " property read off the matched " //$NON-NLS-1$
                    : " properties read off the matched ") //$NON-NLS-1$
                .append("objects differ across the sides. The two answer different questions - ") //$NON-NLS-1$
                .append("the flags are the engine's verdict for the node, which is what a merge ") //$NON-NLS-1$
                .append("acts on, and the table is this server reading every assignable property ") //$NON-NLS-1$
                .append("off each side - so neither is dropped here. A scope that excluded this ") //$NON-NLS-1$
                .append("node's content is one way to get this state; see the notice above when ") //$NON-NLS-1$
                .append("there is one.\n\n"); //$NON-NLS-1$
        }
    }

    /**
     * Whether the platform called this node identical while this document's own property table
     * counts a difference in it.
     *
     * @param node the node (may be {@code null})
     * @param finished whether its subtree has been compared
     * @param properties this document's own reading of the matched objects
     * @return {@code true} when the two disagree
     */
    private static boolean disputedByProperties(ComparisonNode node, boolean finished,
        PropertyDigest properties)
    {
        return node != null && !properties.differing.isEmpty()
            && ComparisonNodeState.decode(node, finished) == ComparisonNodeState.IDENTICAL;
    }

    /**
     * The {@code State} cell of the summary, carrying every qualification the rest of this
     * document forces on it.
     * <p>
     * Only {@link ComparisonNodeState#IDENTICAL} is ever qualified, and that is the point: it is
     * the one label that asserts SAMENESS, so it is the only one a differing property count or an
     * excluded subtree can contradict. Every other label already says something happened.
     *
     * @param request the call description
     * @param node the node (may be {@code null})
     * @param finished whether its subtree has been compared
     * @param properties this document's own reading of the matched objects
     * @return the cell text
     */
    private static String summaryState(Request request, ComparisonNode node, boolean finished,
        PropertyDigest properties)
    {
        if (node == null)
        {
            return ""; //$NON-NLS-1$
        }
        ComparisonNodeState state = ComparisonNodeState.decode(node, finished);
        if (state != ComparisonNodeState.IDENTICAL)
        {
            return state.label();
        }
        StringBuilder cell = new StringBuilder(state.label());
        if (!properties.differing.isEmpty())
        {
            cell.append(STATE_DISPUTED_BY_PROPERTIES).append(properties.differing.size())
                .append(properties.differing.size() == 1 ? " property below differs" //$NON-NLS-1$
                    : " properties below differ"); //$NON-NLS-1$
        }
        if (request.coverage == ContentCoverage.SCOPED_RUN)
        {
            cell.append(STATE_SCOPED_RUN);
        }
        return cell.toString();
    }

    // ==================== Properties ====================

    /**
     * This document's own reading of the matched objects: the three-column rows and how they
     * sorted into differing, unreadable and equal.
     * <p>
     * It is computed ONCE per document and handed to both the summary and the property section,
     * because the {@code State} cell has to be able to name the count the table prints. Two
     * independent readings would let one number contradict the other inside one answer, which is
     * exactly the defect the {@code State} qualification exists to report.
     */
    private static final class PropertyDigest
    {
        /** No compared objects were read at all - the caller passed no read port. */
        private static final PropertyDigest NOT_READ = new PropertyDigest(false, 0,
            Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList());

        /** The objects were read, and this node carries none on any side. */
        private static final PropertyDigest NO_OBJECTS = new PropertyDigest(true, 0,
            Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList());

        private final boolean read;
        private final int presentSides;
        private final Map<String, PropertyRow> rows;
        private final List<String> differing;
        private final List<String> undetermined;
        private final List<String> ordered;

        private PropertyDigest(boolean read, int presentSides, Map<String, PropertyRow> rows,
            List<String> differing, List<String> undetermined, List<String> equal)
        {
            this.read = read;
            this.presentSides = presentSides;
            this.rows = rows;
            this.differing = differing;
            this.undetermined = undetermined;
            // Differing rows first: with a limit in play, the rows that carry the answer must
            // survive truncation. Undetermined rows come next, ahead of the equal ones, because a
            // row nobody could read is the second thing a reader needs and the one thing a silent
            // truncation would turn into agreement. Within each group the model's own feature
            // order is preserved.
            List<String> all = new ArrayList<>(differing);
            all.addAll(undetermined);
            all.addAll(equal);
            this.ordered = all;
        }
    }

    /**
     * One property across the three sides: what each cell RENDERS, and - separately - whether the
     * read behind it failed.
     * <p>
     * The two are separate fields because they answer different questions and only one of them is
     * derivable from the other's absence. The rendered cell is text chosen for a human reader, and
     * any text a cell can carry is text a metadata property can legitimately hold, {@link #UNREADABLE}
     * included. Recovering "this side was not read" by comparing the cell against that string
     * therefore misclassifies a property whose real value is that text: the row is called
     * unreadable, a difference it carries is downgraded to {@link RowState#UNDETERMINED}, the
     * differing count loses it, and the summary tells the caller the property could not be read
     * when it was read perfectly well. The flag is set by the ONE place that knows - the
     * introspector's own {@code readFailed} - and nothing downstream has to guess.
     * <p>
     * {@link #present} is that SAME shape asked of a different question, out of band for the same
     * reason. A side that does not carry the property at all rendered as an empty cell, which is
     * also how a side that carries it empty renders, so the two compared equal - see
     * {@link ComparisonNodeRenderer#NOT_ON_THIS_SIDE}. Presence is therefore recorded where it is
     * known, at the moment the introspector lists a side's features, and never recovered from the
     * cell.
     */
    private static final class PropertyRow
    {
        /** What each side's cell renders; empty string where the side carries no value. */
        private final String[] values = new String[SIDES.length];

        /**
         * What each side's value IS, which for a metadata reference is not what its cell renders.
         * <p>
         * The cell shows a reference target by its bare {@code Name}, because that is what reads
         * well in a table. Two sides of a broad reference - a subsystem's {@code content} holds
         * objects of ANY type - can then hold {@code Catalog.Foo} and {@code Document.Foo} and
         * render the same word, so comparing the cells answered SAME for two different objects and
         * the document went on to state there were no property differences. The introspector
         * supplies the qualified form beside the rendered one for exactly this; see
         * {@code PropertyInfo.valueIdentity}.
         * <p>
         * An EMPTY identity here means one thing only: the side HAS the property and set nothing in
         * it. It is not a fallback for "there is a value but it prints as nothing" - a reference
         * pointing at an unnamed object renders a blank CELL and still arrives with the target's
         * type as its identity, so it is not compared equal to a reference pointing at nothing.
         */
        private final String[] identities = new String[SIDES.length];

        /** Whether the read of that side's value FAILED, independent of what the cell renders. */
        private final boolean[] readFailed = new boolean[SIDES.length];

        /**
         * Whether that side's object carries this property AT ALL - independent of its value, and
         * independent of whether the value could be read. Stays false for a side whose object is
         * absent, which is why {@link ComparisonNodeRenderer#compare} asks it only of the sides
         * that have one.
         */
        private final boolean[] present = new boolean[SIDES.length];

        PropertyRow()
        {
            Arrays.fill(values, ""); //$NON-NLS-1$
            Arrays.fill(identities, ""); //$NON-NLS-1$
        }
    }

    /**
     * Reads the matched objects and sorts their properties, without writing a word.
     *
     * @param node the node to read
     * @param access the read port; {@code null} means the objects were not read for this call
     * @return the digest, never {@code null}
     */
    private static PropertyDigest digestProperties(ComparisonNode node, NodeAccess access)
    {
        if (access == null)
        {
            return PropertyDigest.NOT_READ;
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
            return PropertyDigest.NO_OBJECTS;
        }

        Map<String, PropertyRow> rows = new LinkedHashMap<>();
        for (int i = 0; i < sides.length; i++)
        {
            collectProperties(rows, sides[i], i);
        }
        markAbsentProperties(rows, sides);

        List<String> differing = new ArrayList<>();
        List<String> undetermined = new ArrayList<>();
        List<String> equal = new ArrayList<>();
        for (Map.Entry<String, PropertyRow> entry : rows.entrySet())
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
        return new PropertyDigest(true, presentSides, rows, differing, undetermined, equal);
    }

    private static void appendProperties(StringBuilder sb, Request request,
        PropertyDigest properties, boolean finished)
    {
        sb.append("## Properties\n\n"); //$NON-NLS-1$
        if (!properties.read)
        {
            sb.append("_(compared objects were not read for this call)_\n\n"); //$NON-NLS-1$
            return;
        }
        if (properties.presentSides == 0)
        {
            sb.append("_(this node carries no compared model objects)_\n\n"); //$NON-NLS-1$
            return;
        }

        int total = properties.ordered.size();
        int shown = Math.min(total, request.limit);
        sb.append("**Properties:** ").append(total).append(" (").append(properties.differing.size()) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" differing"); //$NON-NLS-1$
        if (!properties.undetermined.isEmpty())
        {
            sb.append(", ").append(properties.undetermined.size()).append(" not readable"); //$NON-NLS-1$ //$NON-NLS-2$
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
            String name = properties.ordered.get(i);
            String[] values = properties.rows.get(name).values;
            sb.append(MarkdownUtils.tableRow(label(name), values[0], values[1], values[2]));
        }
        sb.append('\n');
        if (properties.differing.isEmpty())
        {
            // "Nothing differs" is a claim about a COMPARISON, and one object is not a comparison:
            // with a single side present the other columns are empty because the object is absent,
            // not because the sides agree. Saying "no differences" there is the same lie the
            // unfinished guard exists to prevent, one level down.
            if (properties.presentSides < 2)
            {
                sb.append("_Only one side carries this object, so its properties have nothing to " //$NON-NLS-1$
                    + "be compared against._\n\n"); //$NON-NLS-1$
            }
            else if (!properties.undetermined.isEmpty())
            {
                // The same lie again, one step subtler: none of the rows that COULD be read
                // differ, but some could not be read at all, and "no differences" would cover
                // both with one word.
                sb.append("_No differences among the properties that could be read; ") //$NON-NLS-1$
                    .append(properties.undetermined.size())
                    .append(" could not be read on at least one side and are not claimed either " //$NON-NLS-1$
                        + "way._\n\n"); //$NON-NLS-1$
            }
            else
            {
                // NOT guarded by the scope caveat, and that is deliberate: these rows are this
                // server's own read of the matched objects through EMF, not the engine's
                // comparison, so the exclusion the engine applies to its own features never
                // reached them. The scope notice at the top of the document says so explicitly
                // rather than leaving the reader to assume this table is affected too.
                sb.append('_').append(finished ? NO_DIFFERENCES : NOT_DETERMINED)
                    .append(" in the compared properties._\n\n"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Adds {@code source}'s assignable properties into {@code rows} under column {@code index}, and
     * records that this side HAS each of them.
     * <p>
     * A feature the side does not carry is left to {@link #markAbsentProperties}, which is the only
     * place that can tell it apart from a side with no object at all. It used to be left to the
     * initial empty string, so it rendered - and compared - exactly like a property the side has
     * and leaves empty.
     * <p>
     * A property the introspector could not READ is the one case that does NOT render as an empty
     * cell: it gets {@link #UNREADABLE}. Both arrive from the introspector as a {@code null}
     * current value - the read is guarded so that one dangling proxy cannot abort the whole object
     * - and folding them together published a failure as an absence, which on a three-column
     * comparison also made two unreadable sides look like agreement.
     * <p>
     * The failure is recorded in {@link PropertyRow#readFailed} as well as rendered, and it is that
     * flag - never the rendered text - that {@link #compare} reads back. See {@link PropertyRow}.
     */
    private static void collectProperties(Map<String, PropertyRow> rows, EObject source, int index)
    {
        if (source == null)
        {
            return;
        }
        for (PropertyInfo info : MetadataPropertyIntrospector.introspect(source))
        {
            PropertyRow row = rows.get(info.name);
            if (row == null)
            {
                row = new PropertyRow();
                rows.put(info.name, row);
            }
            row.present[index] = true;
            if (info.readFailed)
            {
                row.readFailed[index] = true;
                row.values[index] = UNREADABLE;
            }
            else
            {
                row.values[index] = info.currentValue == null ? "" : info.currentValue; //$NON-NLS-1$
                row.identities[index] = info.valueIdentity == null ? "" : info.valueIdentity; //$NON-NLS-1$
            }
        }
    }

    /**
     * Marks every cell where the side HAS an object but that object has no such property.
     * <p>
     * Done here, in the one place that holds both the rows and the three objects, rather than in
     * {@link #collectProperties} - which is called per side and cannot tell "this side lacks the
     * property" from "this side was never collected because it carries no object". The two must
     * not render alike: the second is an absent object, which the summary above the table already
     * states, and the first is a property mismatch between two objects that are both there.
     *
     * @param rows the collected rows, modified in place
     * @param sides the three compared objects, {@code null} where the side has none
     */
    private static void markAbsentProperties(Map<String, PropertyRow> rows, EObject[] sides)
    {
        for (PropertyRow row : rows.values())
        {
            for (int i = 0; i < sides.length; i++)
            {
                if (sides[i] != null && !row.present[i])
                {
                    row.values[i] = NOT_ON_THIS_SIDE;
                }
            }
        }
    }

    /** What one property row establishes about the sides that carry an object. */
    private enum RowState
    {
        /**
         * The sides disagree: either two of them that were both READ carry different values, or
         * one of them carries the property and another does not have it at all.
         */
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
     * <p>
     * <b>PRESENCE is asked before any value.</b> Two matched sides need not be instances of the
     * same concrete class, so one of them can simply not have the property. That is a difference,
     * and it is established without reading anything - which is why it is answered first. Read as
     * values, the commonest shape of it came back SAME: the side that has the property left it
     * empty, the side that lacks it rendered empty too, so a node whose two sides do not even
     * agree on which properties exist could be reported with zero differing properties. Presence
     * is taken from {@link PropertyRow#present}, never from the cell, for the reason the paragraph
     * below states about {@link PropertyRow#readFailed}.
     * <p>
     * Which sides were unreadable is taken from {@link PropertyRow#readFailed} and NOT from the
     * rendered cells. Reading it back out of the text made the answer depend on what the
     * configuration happens to contain: a property whose value really is {@link #UNREADABLE} was
     * classified as unread, so a genuine difference it carried came back
     * {@link RowState#UNDETERMINED} instead of {@link RowState#DIFFERENT}, vanished from the
     * differing count, and was announced to the caller as a property this server cannot read.
     * <p>
     * <b>The values themselves are compared as {@link PropertyRow#identities}, not as the rendered
     * cells</b>, for a reason of the same shape one step further out: a cell is written to be READ,
     * so it is allowed to be shorter than the value. A metadata reference renders as the target's
     * bare {@code Name}, which makes {@code Catalog.Foo} and {@code Document.Foo} the same cell -
     * two genuinely different targets answering SAME, a row missing from the differing list, and a
     * document telling the caller that nothing differs. The table still prints the short name; only
     * the comparison uses the qualified one.
     *
     * @param row the three rendered cells and, beside them, what each stands for and which
     *            reads failed
     * @param sides the three compared objects, {@code null} where the side has none
     * @return what the row establishes
     */
    private static RowState compare(PropertyRow row, EObject[] sides)
    {
        Set<String> readable = new LinkedHashSet<>();
        boolean anyUnreadable = false;
        boolean anyPresent = false;
        boolean anyAbsent = false;
        for (int i = 0; i < sides.length; i++)
        {
            if (sides[i] == null)
            {
                continue;
            }
            if (!row.present[i])
            {
                anyAbsent = true;
                continue;
            }
            anyPresent = true;
            if (row.readFailed[i])
            {
                anyUnreadable = true;
            }
            else
            {
                readable.add(row.identities[i] == null ? "" : row.identities[i]); //$NON-NLS-1$
            }
        }
        if (anyPresent && anyAbsent)
        {
            // Established without reading a single value, and therefore ahead of everything below:
            // one object has this property and another does not, which is a difference between the
            // sides whatever the values are and whatever failed. Asking the values first sent the
            // commonest shape of it - the side that HAS the property leaves it empty - through the
            // equal branch, because the absent side's cell was the same empty string.
            return RowState.DIFFERENT;
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

    /**
     * Renders one {@code ## Form structure} section per side the comparison resolved a form for.
     *
     * <h2>Each section is headed by ITS OWN side's name</h2>
     * A node is reached from one side - {@link Request#address} is the FQN or node id the caller
     * typed, and {@link Request#side} says which side it addressed - and all three sections used to
     * be rendered under it. For a form RENAMED between the sides that is simply wrong: the other
     * and ancestor sections showed one form's structure under the other form's FQN, and a reader
     * has no way to tell, because the heading is the only name in the section. The per-side symlink
     * is the tree's own answer to "what is this node called on that side", which is the same value
     * the summary table prints, so each section is headed by it.
     * <p>
     * A side whose symlink carries no address is the one case where there is nothing to head it
     * with, and it borrows the request's address OUT LOUD - a heading that cannot be established
     * must not read as one that was. BLANK and absent are the same case here: a heading of
     * whitespace names nothing, so a name that is all whitespace is no name.
     *
     * @param sb the document being assembled
     * @param request the call description, for the language, the row limit and the fallback address
     * @param node the form node
     * @param access the read port; {@code null} means the compared objects were never read, and
     *     there is no structure to render
     */
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
            // THIS side's own name, not the request's. request.address is how the caller reached
            // the node - an FQN or a node id, on ONE side - and it was handed to all three renders,
            // so a form renamed between the sides had its other and ancestor structures printed
            // under a heading naming the MAIN side's form. The document then attributed structure
            // to an FQN that does not hold it.
            String address = symlinkOf(node, side);
            if (address.isBlank())
            {
                // A side with no symlink is not a side to borrow another's name for. Said out
                // loud rather than papered over: the heading below is still the only address this
                // document has, and a reader who is not told it came from elsewhere would read it
                // as this side's own.
                //
                // BLANK, not empty. A symlink of spaces or tabs is not empty, so it used to take
                // this branch's place while carrying nothing: the notice was suppressed and the
                // section was headed 'Form Structure:' followed by whitespace, which reads as a
                // name read off this side and is not one. What decides the fallback is whether
                // the value is an ADDRESS, and whitespace is not.
                address = request.address;
                sb.append("> This side carries no name of its own in the comparison tree, so the ") //$NON-NLS-1$
                    .append("heading below repeats the address the node was reached by (") //$NON-NLS-1$
                    .append(sideLabel(request.side))
                    .append("). It is NOT a name read off this side, and the structure under it ") //$NON-NLS-1$
                    .append("is.\n\n"); //$NON-NLS-1$
            }
            // The SHARED form reader, on the per-side model object the comparison already resolved.
            // Its FQN-based entry point is deliberately not used: it addresses our workspace project,
            // not the comparison's virtual one, so it would render the wrong side's form.
            //
            // The caller's row limit is HANDED DOWN. This document promises "maximum rows per
            // table", and these are its tables too - up to three of them per node, one per side.
            // Without the limit the reader applied only its own MAX_NODES guard, so limit=1 still
            // produced every attribute, command, parameter and event handler the form has and an
            // item outline of up to 5000 lines, in a section the caller had asked to keep small.
            sb.append(FormStructureReader.render(address, form, request.language, request.limit));
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
        // Collected as SECTIONS, which is what this table renders. The generic flattening walks
        // whatever a node's children are and spends the caller's row budget on every one of them,
        // so at depth > 1 a descendant that is not a section - and this table never renders one -
        // took a row away from a section that would have been rendered: the table came back
        // shorter than the limit, and a section that fitted was announced as cut. Everything
        // collected below IS a row, so the budget counts rendered rows and the cap is raised
        // exactly when a row was declined - which is how the child outline and the problem table
        // beside it already work.
        SectionWalk walk = new SectionWalk();
        // Read through childrenOf rather than the module's own narrowly typed accessor, for the
        // same reason the walk below takes a ComparisonNode: that accessor is a bridge into the
        // generic implementation and its list is not filtered by type at runtime.
        for (ComparisonNode child : childrenOf(node))
        {
            if (walk.bounds.exhausted())
            {
                break;
            }
            flattenSections(child, 1, request.depth, request.limit, walk);
        }
        // The phrase is allowed by ONE predicate, and every bound clears it in one place - see
        // WalkBounds. It used to be guarded by a list of the bounds instead, and the list was one
        // short: a module whose sections sit under a node that is not one had them hidden by the
        // DEPTH limit, nothing was collected, no other bound had bitten, and the document answered
        // "no differences in the module sections" over sections it had never looked at.
        if (walk.sections.isEmpty() && walk.bounds.complete())
        {
            appendEmptyFinding(sb, request, finished, "the module sections"); //$NON-NLS-1$
            return;
        }
        // `flattenSections` raises the flag when a section was DECLINED, and until this line
        // nothing read it: the table was cut and looked whole, while the child outline and the
        // problem table beside it both announce the very same cap. A module whose sections were
        // cut is exactly the case where a reader concludes "that procedure is not in the module".
        sb.append("**Sections shown:** ").append(walk.sections.size()) //$NON-NLS-1$
            .append(walk.bounds.notices(request.limit, request.depth))
            .append("\n\n"); //$NON-NLS-1$
        if (walk.sections.isEmpty())
        {
            // Nothing to tabulate, and the line above already says what stopped the walk. A table
            // header over no rows would read as an empty answer rather than as an unfinished one.
            return;
        }
        sb.append(MarkdownUtils.tableHeader("Depth", "Type", "Main", "Other", "Ancestor", "State")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        for (int i = 0; i < walk.sections.size(); i++)
        {
            BslModuleSectionComparisonNode section = walk.sections.get(i);
            sb.append(MarkdownUtils.tableRow(walk.depths.get(i).toString(),
                sectionType(section), dashIfEmpty(section.getName(ComparisonSide.MAIN)),
                dashIfEmpty(section.getName(ComparisonSide.OTHER)),
                dashIfEmpty(section.getName(ComparisonSide.COMMON_ANCESTOR)),
                stateOf(section, finished)));
        }
        sb.append('\n');
    }

    /**
     * Depth-first walk of the module's subtree, collecting the SECTIONS in it - bounded by the
     * requested depth, by the row limit, and by {@link #MAX_SECTION_WALK_NODES}.
     *
     * <h2>Walked past, not stopped at</h2>
     * The difference from {@link #flatten} is what the budget is spent ON. That one collects every
     * child of every kind, because the table it feeds renders every child. This table renders
     * sections, so a descendant that is not one costs no row and cannot raise the cap - but it is
     * still DESCENDED THROUGH, and the parameter is a {@code ComparisonNode} for exactly that
     * reason. Refusing to descend through it dropped every section beneath it out of the table
     * while the walk reported itself COMPLETE, because those sections were never visited: rows
     * vanished, and the document said the page was whole. That is a worse
     * answer than the wrong counter this walk was written to fix.
     * <p>
     * It is reachable rather than theoretical: {@code BslModuleSectionComparisonNodeImpl
     * .getChildren()} is a bridge method delegating straight into
     * {@code TopComparisonNodeImpl.getChildren()}, which answers {@code topChildren} plus
     * {@code containmentChildren} with no filtering by type. The narrow {@code EList} element type
     * on the interface is a generics declaration, and at runtime the list may carry any
     * {@code ComparisonNode}.
     *
     * <h2>The three ways it can stop short, and they say different things</h2>
     * {@link WalkBound#ROW_LIMIT} is raised only when a SECTION was actually DECLINED for want of
     * a row. Draining the row budget exactly is a complete page, and telling a caller to raise the
     * limit for a page that is already whole is a wrong number in the other direction.
     * {@link WalkBound#NODE_BUDGET} is the second: the walk stopped LOOKING, so nothing is known
     * about what lay beyond, and raising the limit would not reveal it.
     * {@link WalkBound#DEPTH_LIMIT} is the third, and it is the one that can bite while nothing at
     * all has been collected - see {@link WalkBounds}.
     *
     * <h2>The first refusal ends the walk</h2>
     * A declined row and an exhausted node budget are both GLOBAL: no later node can be collected
     * either, so everything after them is work whose only possible product is a second warning.
     * That warning was measurably wrong - a module with more direct sections than the limit
     * declined a row and then went on to spend the whole node budget, printing "the section walk
     * stopped after 10 000 nodes" beside it, which tells the caller to lower a depth that had
     * nothing to do with it. {@link WalkBounds#exhausted()} is checked on entry AND in the sibling
     * loops, so the DFS unwinds instead of running to the end of the graph.
     *
     * @param node the node to walk; collected only when it is a section
     * @param depth its depth below the module, counted from 1 - counted per LEVEL, so a node the
     *            table does not render still occupies the level it sits at
     * @param maxDepth the deepest level the caller asked for
     * @param limit the largest number of rows the table may hold
     * @param walk the collected rows and the bounds the walk ran into
     */
    private static void flattenSections(ComparisonNode node, int depth, int maxDepth, int limit,
        SectionWalk walk)
    {
        if (node == null || walk.bounds.exhausted())
        {
            return;
        }
        if (walk.visited >= MAX_SECTION_WALK_NODES)
        {
            walk.bounds.stoppedAt(WalkBound.NODE_BUDGET);
            return;
        }
        walk.visited++;
        if (node instanceof BslModuleSectionComparisonNode)
        {
            if (walk.sections.size() >= limit)
            {
                walk.bounds.stoppedAt(WalkBound.ROW_LIMIT);
                return;
            }
            walk.sections.add((BslModuleSectionComparisonNode)node);
            walk.depths.add(Integer.valueOf(depth));
        }
        if (depth >= maxDepth)
        {
            if (hasWalkableChild(node))
            {
                walk.bounds.stoppedAt(WalkBound.DEPTH_LIMIT);
            }
            return;
        }
        for (ComparisonNode child : childrenOf(node))
        {
            if (walk.bounds.exhausted())
            {
                return;
            }
            flattenSections(child, depth + 1, maxDepth, limit, walk);
        }
    }

    /**
     * A boundary that can stop a bounded walk before it has seen the whole subtree.
     * <p>
     * An enum rather than a flag apiece so that {@link WalkBounds#complete()} is a question about
     * the SET and not a conjunction that has to be extended at every site that reads it. The
     * defect that produced it was exactly that conjunction being one term short.
     */
    private enum WalkBound
    {
        /** A row this table renders was DECLINED because the row limit was already full. */
        ROW_LIMIT,
        /**
         * The walk stopped descending because {@link ComparisonNodeRenderer#MAX_SECTION_WALK_NODES}
         * ran out.
         */
        NODE_BUDGET,
        /** The walk turned back at the caller's {@code depth} with children still below. */
        DEPTH_LIMIT
    }

    /**
     * How much of a subtree a bounded walk actually covered: the ONE predicate any "there is
     * nothing here" sentence is allowed to depend on.
     *
     * <h2>The defect family this ends</h2>
     * Every bound narrows what was LOOKED AT, and a document that reports a narrowed answer as a
     * whole one is the same lie in three costumes - "no differences in the module sections", "no
     * differences in the child nodes", "none reported". Each of them used to name the bounds it
     * knew about, and each list was written at the site that printed the sentence: the section one
     * listed the node budget and not the depth limit, so a module whose only sections sat one
     * level below a node that is not a section reported "no differences" over sections nothing had
     * visited. Adding the missing term at that site would have produced the same shape again on
     * the next bound.
     * <p>
     * So completeness is not a conjunction any more. A bound is recorded through
     * {@link #stoppedAt(WalkBound)} - the ONE place completeness is lost - and every sentence that
     * claims an absence asks {@link #complete()}. A future bound joins {@link WalkBound} and every
     * such sentence is guarded by it without being touched.
     */
    private static final class WalkBounds
    {
        private final Set<WalkBound> stops = new LinkedHashSet<>();

        /**
         * Records that a boundary stopped the walk short. The one place completeness is lost.
         *
         * @param bound what stopped it
         */
        void stoppedAt(WalkBound bound)
        {
            stops.add(bound);
        }

        /**
         * @return {@code true} when the walk covered the whole subtree it was pointed at, so a
         *             statement about what is NOT there is a reading rather than an assumption
         */
        boolean complete()
        {
            return stops.isEmpty();
        }

        /**
         * Whether the walk must UNWIND rather than carry on with the next sibling.
         * <p>
         * Only the two GLOBAL bounds count here: after them nothing more can be collected or even
         * looked at, so continuing produces no row and can only raise a second, wrong warning. The
         * depth limit is LOCAL - it turned back on one branch and the siblings beside it are still
         * within the caller's depth.
         *
         * @return {@code true} when nothing further can be collected
         */
        boolean exhausted()
        {
            return stops.contains(WalkBound.ROW_LIMIT)
                || stops.contains(WalkBound.NODE_BUDGET);
        }

        /**
         * Whether ONE bound stopped the walk - private on purpose.
         * <p>
         * Asking about a single bound is how every defect in this family started: a sentence
         * guarded by the one bound its author had in mind, silent about the others. The only
         * legitimate readers are the two renderings below, which ask about every constant in
         * turn; a caller outside asks {@link #complete()}.
         *
         * @param bound the bound to ask about
         * @return whether that bound stopped the walk
         */
        private boolean hit(WalkBound bound)
        {
            return stops.contains(bound);
        }

        /**
         * What a COUNT LINE must say beside its number, one clause per bound that bit - the
         * wording for a table that renders the very nodes the walk collected.
         * <p>
         * Enumerated over {@link WalkBound#values()} and worded by a switch EXPRESSION, so this
         * list cannot go one term short the way the hand-written lists it replaced did: a constant
         * added to the enum and not answered below fails the COMPILE. The order is the enum's own
         * and therefore does not move with the order the bounds happened to be recorded in.
         *
         * @param limit the row limit the caller passed
         * @param depth the depth the caller passed
         * @return the clauses, empty when the walk was complete
         */
        String notices(int limit, int depth)
        {
            StringBuilder text = new StringBuilder();
            for (WalkBound bound : WalkBound.values())
            {
                if (hit(bound))
                {
                    text.append(countNotice(bound, limit, depth));
                }
            }
            return text.toString();
        }

        /**
         * The same bounds worded for a SCAN whose product is not the walked list itself - the
         * potential-problem section, which only reads its rows OFF the nodes the walk collected.
         * <p>
         * A second wording, not a second list, and the difference is a fact rather than a style:
         * for a table that renders the walked nodes a drained row budget is "showing the first N",
         * while for a scan that merely VISITED them nothing is being shown from that list at all -
         * and in the empty case nothing is being shown, full stop. Both renderings iterate the
         * same {@link WalkBound#values()} through the same exhaustive switch, so neither of them
         * can lose a bound while the other keeps it.
         *
         * @param limit the row limit the caller passed
         * @param depth the depth the caller passed
         * @return the clauses, each opening with ", and"; empty when the walk was complete
         */
        String scopeNotices(int limit, int depth)
        {
            StringBuilder text = new StringBuilder();
            for (WalkBound bound : WalkBound.values())
            {
                if (hit(bound))
                {
                    text.append(scopeNotice(bound, limit, depth));
                }
            }
            return text.toString();
        }

        private static String countNotice(WalkBound bound, int limit, int depth)
        {
            // A switch EXPRESSION over the enum, with no default: the compiler requires every
            // constant to be answered, which is what makes "every bound is announced" a property
            // of the build rather than of somebody remembering to extend a list.
            return switch (bound)
            {
                case ROW_LIMIT -> Pagination.limitReachedNotice(limit);
                case NODE_BUDGET -> SECTION_WALK_CUT_SHORT;
                // Its own sentence for the same reason the other two have theirs: this bound is
                // the caller's own `depth`, so that parameter - and nothing else - is the way to
                // widen it. It is also the only one of the three that can bite while NOTHING has
                // been collected, which is exactly when a bare count reads as "there is nothing".
                case DEPTH_LIMIT -> " (the walk turned back at depth " + depth //$NON-NLS-1$
                    + ", the deepest level requested, so anything below it was never looked " //$NON-NLS-1$
                    + "at - this is NOT a statement that there is nothing there; raise " //$NON-NLS-1$
                    + "depth to widen it)"; //$NON-NLS-1$
            };
        }

        private static String scopeNotice(WalkBound bound, int limit, int depth)
        {
            return switch (bound)
            {
                case ROW_LIMIT -> ", and only the first " + limit //$NON-NLS-1$
                    + " descendant nodes were examined - the rest were never asked, so this is " //$NON-NLS-1$
                    + "NOT a statement that the subtree has no problems; raise limit to widen it"; //$NON-NLS-1$
                // Unreachable from `flatten` today, which has no node budget - and written anyway,
                // because "today's caps" is what the two lists this replaces were built on.
                case NODE_BUDGET -> ", and the walk stopped looking after " //$NON-NLS-1$
                    + MAX_SECTION_WALK_NODES + " nodes, so anything past that point was never " //$NON-NLS-1$
                    + "asked - this is NOT a statement that the subtree has no problems"; //$NON-NLS-1$
                case DEPTH_LIMIT -> ", and the scan turned back at depth " + depth //$NON-NLS-1$
                    + " - nothing deeper was asked, so this is NOT a statement that the subtree " //$NON-NLS-1$
                    + "has no problems; raise depth to widen it"; //$NON-NLS-1$
            };
        }
    }

    /**
     * What the section walk collected, and the bounds it ran into.
     * <p>
     * One value rather than three out-parameters because the bounds are NOT interchangeable and
     * were becoming easy to conflate: one says a row was refused, one says a region was never
     * examined, one says the walk stopped at the level it was told to - and the table prints a
     * different sentence for each.
     */
    private static final class SectionWalk
    {
        /** The sections to render, in render order. */
        final List<BslModuleSectionComparisonNode> sections = new ArrayList<>();

        /** Their depths, paired positionally with {@link #sections}. */
        final List<Integer> depths = new ArrayList<>();

        /** What stopped the walk short, if anything. */
        final WalkBounds bounds = new WalkBounds();

        /** Nodes visited so far - sections and walked-past nodes alike. */
        int visited;
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
        WalkBounds bounds = new WalkBounds();
        for (ComparisonNode child : childrenOf(node))
        {
            if (bounds.exhausted())
            {
                break;
            }
            flatten(child, 1, request.depth, request.limit, flat, depths, bounds);
        }
        // Guarded by the same ONE predicate as the module-section table. Here the phrase happens to
        // be unreachable while a bound is up - the first child is collected before any bound can
        // bite - but that is a property of today's caps, not a promise, and stating the rule once
        // is what keeps the next cap from re-opening the hole it opened there.
        if (flat.isEmpty() && bounds.complete())
        {
            appendEmptyFinding(sb, request, finished, "the child nodes"); //$NON-NLS-1$
            return;
        }
        sb.append("**Children shown:** ").append(flat.size()) //$NON-NLS-1$
            .append(bounds.notices(request.limit, request.depth))
            .append("\n\n"); //$NON-NLS-1$
        if (flat.isEmpty())
        {
            return;
        }
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
     * {@link WalkBound#ROW_LIMIT} is raised only when a node was actually DECLINED. Draining the
     * budget is not truncation: a subtree with exactly {@code limit} nodes renders every one of
     * them, and inferring truncation from an exhausted budget would tell the caller to re-run with
     * a higher limit for a page that is already complete. Same shape, and the same reason, as
     * {@code FormStructureReader.renderItems}.
     * <p>
     * It shares {@link WalkBounds} with the section walk, and for the reason stated there: what
     * narrowed the walk is what every "nothing found" sentence downstream has to be guarded by,
     * and the two walks answer that question the same way or they answer it differently.
     *
     * @param node the node to collect and descend from
     * @param depth its depth below the addressed node, counted from 1
     * @param maxDepth the deepest level the caller asked for
     * @param limit the largest number of rows the caller allowed
     * @param flat the collected nodes, in render order
     * @param depths their depths, paired positionally with {@code flat}
     * @param bounds what stopped the walk short, if anything
     */
    private static void flatten(ComparisonNode node, int depth, int maxDepth, int limit,
        List<ComparisonNode> flat, List<Integer> depths, WalkBounds bounds)
    {
        if (node == null || bounds.exhausted())
        {
            return;
        }
        if (flat.size() >= limit)
        {
            bounds.stoppedAt(WalkBound.ROW_LIMIT);
            return;
        }
        flat.add(node);
        depths.add(Integer.valueOf(depth));
        if (depth >= maxDepth)
        {
            if (hasWalkableChild(node))
            {
                bounds.stoppedAt(WalkBound.DEPTH_LIMIT);
            }
            return;
        }
        for (ComparisonNode child : childrenOf(node))
        {
            if (bounds.exhausted())
            {
                return;
            }
            flatten(child, depth + 1, maxDepth, limit, flat, depths, bounds);
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
        WalkBounds scopeBounds = new WalkBounds();
        for (ComparisonNode child : childrenOf(node))
        {
            if (scopeBounds.exhausted())
            {
                break;
            }
            flatten(child, 1, request.depth, request.limit, descendants, depths, scopeBounds);
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
            // "None reported" is a claim about what was LOOKED AT, so it is allowed by the ONE
            // predicate every such claim in this class is allowed by, and by nothing else. This
            // site used to print the phrase unconditionally and merely APPEND the bounds it knew
            // about, which is the same sentence with a footnote: a reader who takes "none
            // reported" at its word has already been told the wrong thing, and the list of
            // footnotes was itself one term short of the bounds that exist.
            if (scopeBounds.complete())
            {
                sb.append("_(none reported").append(notFinishedNote(finished)) //$NON-NLS-1$
                    .append(")_\n\n"); //$NON-NLS-1$
            }
            else
            {
                // Not an absence: a statement about the part that was covered, plus the bound
                // that cut the rest, from the one enumeration.
                sb.append("_(no problem in the part of the subtree this scan covered") //$NON-NLS-1$
                    .append(notFinishedNote(finished))
                    .append(scopeBounds.scopeNotices(request.limit, request.depth))
                    .append(")_\n\n"); //$NON-NLS-1$
            }
            return;
        }
        // The count is a rendered-row count, not a subtree total: collection stops at the cap, so
        // the cap being reached must be SAID, exactly as the child outline says it. A bare capped
        // number read as a total is the same class of lie as "no differences" over an unfinished
        // subtree.
        sb.append("**Potential problems:** ").append(count) //$NON-NLS-1$
            .append(truncated ? Pagination.limitReachedNotice(request.limit) : "") //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        // A capped ROW list and a narrowed SCOPE are different caps, and only the first is
        // announced by the notice above. This branch used to ask about the row limit ALONE, so a
        // problem hidden below the requested depth left "Potential problems: 1" reading as a
        // total, and a scan cut by BOTH bounds announced only the second. A non-empty answer is
        // qualified by the same predicate the empty one is, and names every bound through the same
        // enumeration.
        if (!scopeBounds.complete())
        {
            sb.append("> The scan was partial, so this count is for the nodes that were visited " //$NON-NLS-1$
                + "and not for the whole subtree") //$NON-NLS-1$
                .append(scopeBounds.scopeNotices(request.limit, request.depth))
                .append(".\n\n"); //$NON-NLS-1$
        }
        // Attached to the TABLE, not to the section: with no rows there is no platform-authored text
        // to disclaim, and a disclaimer printed on every node render regardless would be noise that
        // stops being read exactly when it starts mattering.
        sb.append("> ").append(PLATFORM_TEXT_NOTICE).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(MarkdownUtils.tableHeader("Node id", "Problem", "Details")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append(rows).append('\n');
    }

    /**
     * The clause a "nothing found" answer in the problem section carries when the addressed node's
     * own subtree has not been compared yet.
     * <p>
     * The lazy tree is a SECOND narrowing, beside the bounded walk, and it lives here rather than
     * in {@link WalkBounds} because it is not a bound: no walk stopped at it, {@code stoppedAt}
     * was never called, and {@link WalkBounds#complete()} has nothing to say about it. What DID
     * live here - a hand-written list of the bounds - is gone: that list is
     * {@link WalkBounds#scopeNotices(int, int)}, in the one place the bounds are enumerated.
     *
     * @param finished whether the addressed node's own status is {@code Finished}
     * @return the clause, empty when the node is finished
     */
    private static String notFinishedNote(boolean finished)
    {
        return finished ? "" //$NON-NLS-1$
            : ", and this subtree is not finished, so the list is incomplete"; //$NON-NLS-1$
    }

    // ==================== Shared node accessors ====================

    /**
     * Direct children of a node, tolerating a null node and a null child list.
     * <p>
     * <b>The platform's own list, not a copy of it.</b> Copying charged the FULL width of every
     * level to a walk that is bounded and, past its first refusal, unwinds without reading another
     * child - a module with 10 001 direct sections and {@code limit=500} paid for all 10 001 twice
     * over before the walk had collected 500. Every caller is a {@code flatten*} that tolerates a
     * null element on entry, which is what the copy was buying.
     *
     * @param node the node to read
     * @return the live child list, or an empty one; elements may be {@code null}
     */
    private static List<ComparisonNode> childrenOf(ComparisonNode node)
    {
        if (node == null)
        {
            return Collections.emptyList();
        }
        List<ComparisonNode> children = node.<ComparisonNode> getChildren();
        return children == null ? Collections.emptyList() : children;
    }

    /**
     * Whether a node has a child a walk could actually descend INTO - asked lazily, so a wide
     * level costs one element rather than a copy of the list.
     * <p>
     * "The list is not empty" was the wrong question, and it became wrong the moment
     * {@link #childrenOf(ComparisonNode)} stopped copying: what comes back is the PLATFORM's own
     * list, and its elements may be {@code null}. Every walk here tolerates that by returning on
     * entry, so a node whose only child is {@code null} has nothing below it - yet an emptiness
     * test called the level occupied and recorded {@link WalkBound#DEPTH_LIMIT}. One such flag
     * suppresses the honest {@link #NO_DIFFERENCES} phrase, adds a "raise depth" clause to a walk
     * that had covered everything, and does the same again in the problem section beside it. A
     * bound is the statement that something was NOT looked at, so it may only be raised for a
     * child that is there to look at.
     *
     * @param node the node to read
     * @return {@code true} when at least one child is non-{@code null}
     */
    private static boolean hasWalkableChild(ComparisonNode node)
    {
        for (ComparisonNode child : childrenOf(node))
        {
            if (child != null)
            {
                return true;
            }
        }
        return false;
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
     * The sentence a table of ENGINE-BUILT rows prints when it found none, guarded by every reason
     * the emptiness might not mean agreement.
     * <p>
     * Two guards, and they are separate facts about separate mechanisms. An UNFINISHED subtree has
     * no rows because the lazy engine has not built them yet. In a SCOPED run a subtree can have
     * no rows because the engine excluded the object's own features one at a time and built no
     * child for the ones it dropped. The first is a fact about this node; the second is a fact
     * about the RUN, and it is worded as one, because which objects were excluded is not readable
     * from the tree (see {@link ContentCoverage}). Both make {@link #NO_DIFFERENCES} a claim
     * nothing observed - which is the whole of what either guard says, neither going on to state
     * what was looked at instead - and only the tables the ENGINE fills are guarded this way: the
     * property table is this server's own read and is left to say what it actually found.
     *
     * @param sb the document being assembled
     * @param request the call description, carrying the run's coverage
     * @param finished whether the subtree has been compared
     * @param where the table's own subject, as it reads inside the sentence
     */
    private static void appendEmptyFinding(StringBuilder sb, Request request, boolean finished,
        String where)
    {
        if (!finished)
        {
            sb.append('_').append(NOT_DETERMINED).append(" in ").append(where).append("._\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (request.coverage == ContentCoverage.SCOPED_RUN)
        {
            sb.append("_No finding in ").append(where).append(" - ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(CONTENT_MAY_BE_EXCLUDED).append("._\n\n"); //$NON-NLS-1$
            return;
        }
        sb.append('_').append(NO_DIFFERENCES).append(" in ").append(where).append("._\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The node's difference state, decoded by {@link ComparisonNodeState} and by nothing here.
     * <p>
     * This method used to decide it, and it decided it from MAIN-vs-OTHER alone: a node both sides
     * had edited the SAME way away from the common ancestor answered {@code hasChangedMainOther()}
     * with false and fell through to {@link #NO_DIFFERENCES}, while the report the caller reached
     * this document FROM called the very same node "changed on both sides". One decision, rendered
     * by both views, is what stops that from coming back - see {@link ComparisonNodeState}.
     *
     * @param node the node, may be {@code null}
     * @param finished whether the addressed node's subtree has been compared
     * @return the state's wire text, or {@code ""} for a {@code null} node
     */
    private static String stateOf(ComparisonNode node, boolean finished)
    {
        if (node == null)
        {
            return ""; //$NON-NLS-1$
        }
        return ComparisonNodeState.decode(node, finished).label();
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

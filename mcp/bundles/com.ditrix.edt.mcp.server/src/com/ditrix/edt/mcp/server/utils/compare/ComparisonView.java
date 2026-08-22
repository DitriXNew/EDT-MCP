/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.Collection;
import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.compare.core.ComparisonContext;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonUtils;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.core.PotentialMergeProblemDescription;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.MergeSettings;
import com._1c.g5.v8.dt.compare.model.RootComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

/**
 * A READ-ONLY window onto one live comparison.
 *
 * <h2>Why it exists</h2>
 * EDT's {@code IComparisonSession} is a single interface that both reads the comparison tree and
 * REWRITES it — it can set merge rules, re-parent nodes, break correspondences and adopt external
 * properties. Handing that interface to a tool would make "this feature only reads" a promise kept
 * by review rather than by the type system. This view exposes the reading half and nothing else, so
 * a tool that never receives the session cannot mutate what it is describing. The ONE mutating
 * operation this half legitimately needs — priming a lazy subtree so that reading it tells the
 * truth — lives on {@link ComparisonEngine}, where it is visible in one place. Recording a merge
 * decision is NOT such an operation: decisions are written to EDT's merge-rules FILE by
 * {@code merge_rules}, never onto the live comparison, and {@link #availableMergeRules} is what
 * that file's rules are checked against before it is written.
 *
 * <h2>The transaction boundary (CLAUDE.md don't #1)</h2>
 * The nodes below are {@code IBmObject}s of the COMPARISON's own BM store, not of the workspace
 * project's. {@code BmTransactions.read(project, …)} therefore opens the WRONG store and is not a
 * valid boundary for any of these calls. Every method here must be invoked from inside
 * {@link ComparisonEngine#read(ComparisonView, String, com.ditrix.edt.mcp.server.utils.BmTransactions.BmOperation)}
 * (or its {@code IBmTask} sibling), which routes to
 * {@code IComparisonSession.runComparisonTreeReadonlyTask}. This class deliberately does NOT open a
 * boundary of its own: a helper that silently opened one per call would turn one consistent read of
 * a tree into a sequence of unrelated ones.
 *
 * <h2>Laziness</h2>
 * The tree is built on demand. A node whose {@link #topNodeStatus(long)} is
 * {@link ComparisonNodeStatus#UNFINISHED} or {@link ComparisonNodeStatus#HAS_UNFINISHED_CHILDREN}
 * has not been compared yet, and reading its children then yields an EMPTY list — which renders as
 * "no differences" and is a lie. Prime it with {@link ComparisonEngine#prioritize} and wait on the
 * NODE's own status before reading it.
 *
 * <h2>Labels</h2>
 * There is deliberately no label accessor. {@code ComparisonUtils.getLabel} delegates to a function
 * that branches on {@code Locale.getDefault()}, so its output depends on the machine the server
 * happens to run on — the same defect this repository already banned in
 * {@code MetadataReferenceService.getFeatureLabel}. Callers name nodes from the comparison
 * symlink/FQN instead, which is stable.
 */
public final class ComparisonView
{
    private final ComparisonProcessHandle handle;
    private final IComparisonSession session;

    /**
     * @param handle the process handle this view belongs to
     * @param session the live session (never escapes this class)
     */
    ComparisonView(ComparisonProcessHandle handle, IComparisonSession session)
    {
        this.handle = handle;
        this.session = session;
    }

    /**
     * The session, for {@link ComparisonEngine} only. Package-scoped on purpose: it is the one
     * reference this whole design exists to keep out of tool code.
     *
     * @return the live comparison session
     */
    IComparisonSession session()
    {
        return session;
    }

    /**
     * @return the handle identifying this comparison inside EDT
     */
    public ComparisonProcessHandle handle()
    {
        return handle;
    }

    /**
     * @return the process status as the session itself reports it
     */
    public ComparisonProcessStatus status()
    {
        return session.getStatus();
    }

    /**
     * @return {@code true} when a common ancestor participates (a three-way comparison)
     */
    public boolean isThreeWay()
    {
        return session.isThreeWay();
    }

    /**
     * @return {@code true} when the comparison covers the whole configuration rather than a scope
     */
    public boolean isGlobalScope()
    {
        return session.isGlobalScope();
    }

    /**
     * @param side the side to name
     * @return the project name behind that side
     */
    public String projectName(ComparisonSide side)
    {
        return session.getProjectName(side);
    }

    /**
     * @return the root of the comparison tree
     */
    public RootComparisonNode rootNode()
    {
        return session.getRootNode();
    }

    /**
     * @param symlink the EDT qualified name of a top object, English tokens (e.g.
     *     {@code Catalog.Products}) — the engine does not translate, so pass a canonicalised name
     * @param side the side the symlink belongs to
     * @return the top node, or {@code null} when the symlink is not part of this comparison
     */
    public TopComparisonNode topNode(String symlink, ComparisonSide side)
    {
        return session.getTopNode(symlink, side);
    }

    /**
     * @param nodeId a node id
     * @return the top node that owns it
     */
    public TopComparisonNode topNodeOf(long nodeId)
    {
        return session.getTopNodeOf(nodeId);
    }

    /**
     * @param node a node
     * @return the top node that owns it
     */
    public TopComparisonNode topNodeOf(ComparisonNode node)
    {
        return session.getTopNodeOf(node);
    }

    /**
     * @param nodeId a node id
     * @return the node, or {@code null} when the id is unknown
     */
    public ComparisonNode node(long nodeId)
    {
        return session.getNode(nodeId);
    }

    /**
     * @param context the comparison context of the current read
     * @param nodeId a node id
     * @return the node, or {@code null} when the id is unknown
     */
    public ComparisonNode node(ComparisonContext context, long nodeId)
    {
        return session.getNode(context, nodeId);
    }

    /**
     * How far the comparison of a top node has progressed. Anything other than
     * {@link ComparisonNodeStatus#FINISHED} means the subtree below it is not yet trustworthy.
     *
     * @param topNodeId the top node's id
     * @return the node's own status
     */
    public ComparisonNodeStatus topNodeStatus(long topNodeId)
    {
        return session.getTopNodeStatus(topNodeId);
    }

    /**
     * @param node the node whose two/three compared objects are wanted
     * @param context the comparison context of the current read
     * @return the compared objects, or {@code null} when the node carries none
     */
    public IComparedObjects<?> comparedObjects(ComparisonNode node, ComparisonContext context)
    {
        return session.getComparedObjects(node, context);
    }

    /**
     * @param node a node
     * @return the EMF feature the node compares, or {@code null}
     */
    public EStructuralFeature relatedFeature(ComparisonNode node)
    {
        return session.getRelatedFeature(node);
    }

    /**
     * @param node a node
     * @return the EMF feature of the collection the node sits in, or {@code null}
     */
    public EStructuralFeature parentCollectionFeature(ComparisonNode node)
    {
        return session.getParentCollectionFeature(node);
    }

    /**
     * @param node a node
     * @return the EClass of the objects the node matched, or {@code null}
     */
    public EClass matchedObjectsEClass(ComparisonNode node)
    {
        return session.getMatchedObjectsEClass(node);
    }

    /**
     * Whether a node is inside the scope the engine ACTUALLY compared. This is the extended scope:
     * it can be wider than what the caller asked for, because the engine pulls in what it needs.
     *
     * @param node the node to test
     * @return {@code true} when the node is in the effective scope
     */
    public boolean inScope(ComparisonNode node)
    {
        return session.isInScope(node);
    }

    /**
     * Whether a node is inside the scope the CALLER asked for. Reporting {@link #inScope} as if it
     * were this one would tell the caller it requested objects it never named.
     *
     * @param node the node to test
     * @return {@code true} when the node is in the requested scope
     */
    public boolean inInputScope(ComparisonNode node)
    {
        return session.isInInputScope(node);
    }

    /**
     * @param nodeId a node id
     * @return {@code true} when the engine flagged potential problems under that node
     */
    public boolean hasPotentialProblems(long nodeId)
    {
        return session.hasPotentialMergeProblems(nodeId);
    }

    /**
     * @return the ids of the nodes that are the SOURCE of a potential problem
     */
    public Collection<Long> potentialProblemSourceNodes()
    {
        return session.getPotentialMergeProblemsSourceNodes();
    }

    /**
     * The engine's own descriptions of what could go wrong at a node. They are POTENTIAL: they are
     * produced by inspecting the comparison, not by attempting anything, and this feature never
     * proceeds past a comparison — so they must be reported as possibilities, never as results.
     *
     * @param nodeId the node to describe
     * @param context the comparison context of the current read
     * @return the descriptions (possibly empty)
     */
    public List<PotentialMergeProblemDescription> potentialProblems(long nodeId, ComparisonContext context)
    {
        return session.getPotentialMergeProblemsDescriptions(nodeId, context);
    }

    /**
     * @return the ids of nodes whose merge settings differ from what the engine proposed
     */
    public Collection<Long> nodesWithChangedMergeSettings()
    {
        return session.getNodesWithChangedMergeSettings();
    }

    /**
     * @param node a node
     * @return the node's merge settings, or {@code null} when it carries none
     */
    public MergeSettings mergeSettings(ComparisonNode node)
    {
        return node == null ? null : node.getMergeSettings();
    }

    /**
     * The rules EDT itself considers legal at this node. This is the ONLY authority on legality —
     * a rule absent from this list is refused by the platform silently, so it must be refused by us
     * loudly, naming the node and this set.
     *
     * @param node the node to ask about
     * @return the legal rules, or an empty list when the node carries no merge settings
     */
    public List<MergeRule> availableMergeRules(ComparisonNode node)
    {
        MergeSettings settings = mergeSettings(node);
        return settings == null ? List.of() : List.copyOf(settings.getAvailableMergeRules());
    }

    /**
     * The context a READ of this comparison runs against.
     *
     * <h2>Why the one-argument factory, and why the other two are wrong here</h2>
     * The three platform factories set DIFFERENT HALVES of the context, and the half each one
     * sets is the whole story. Read from the bytecode of
     * {@code ComparisonUtils}/{@code ComparisonDataSourceTransactionalContext}, not from their
     * names:
     * <ul>
     *   <li>{@code createComparisonContext(session)} delegates with a {@code null} transaction, and
     *       the data-source context then calls {@code dataSource.beginTransaction()} for EACH SIDE
     *       - every side is read in its own namespace, which is the only arrangement under which a
     *       three-way read can work at all;</li>
     *   <li>its transaction-taking overload puts the caller's transaction into the MAIN SIDE's
     *       slot ({@code new BmComparisonDataSourceTransaction(tx)}) and sets
     *       <b>{@code mergeMode = true}</b>. It is the MERGE entry: the transaction it wants is the
     *       main project's, to write into. {@code NoMergeStarterRatchetTest} fails the build if
     *       this bundle calls it, which is why it is described here and not written out;</li>
     *   <li>its boolean overload opens a brand-new transaction on the comparison TREE's own engine
     *       - inside an existing read that is a second, unrelated view of the same tree, and
     *       {@link ComparisonContext#close()} COMMITS it.</li>
     * </ul>
     *
     * <h2>The defect this replaces</h2>
     * This method used to take the tree transaction the read boundary hands out and pass it to the
     * transaction-taking overload. That put a transaction bound to the comparison-tree namespace into the MAIN
     * SIDE's slot, so reading a main-side object - which for a project-backed main side lives in
     * the PROJECT's namespace - failed with
     * {@code BmAssertionException: The object belongs to namespace 'X' whereas the transaction is
     * bound to namespace 'ComparisonTreeModel-N'}, and it silently put this read-only feature into
     * the platform's merge mode on the way. Every expansion of a node with compared objects failed.
     *
     * <p>The comparison transaction is deliberately left UNSET: the caller is already inside
     * {@code runComparisonTreeReadonlyTask}, so the tree reads have their transaction, and
     * {@link ComparisonContext#close()} commits whatever sits in that field - a transaction this
     * object does not own.</p>
     *
     * @return a context for reading, which the caller must {@code close()}
     */
    public ComparisonContext readContext()
    {
        return ComparisonUtils.createComparisonContext(session);
    }
}

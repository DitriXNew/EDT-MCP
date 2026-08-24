/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com._1c.g5.v8.dt.compare.core.ComparisonContext;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.PotentialMergeProblemDescription;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.SymlinkComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonEngine;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonFailures;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonNodeRenderer;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonScopeBuilder;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonView;
import com.ditrix.edt.mcp.server.utils.compare.PlatformAnswer;

/**
 * Expands ONE node of a running or finished configuration comparison: the three-way property table,
 * the per-side form structure, the module section list, the vendor-support state, the child outline
 * and the POTENTIAL problems the engine recorded. Read-only - it never merges anything.
 *
 * <p>Two behaviours are the reason this tool is not a thin wrapper around a getter.</p>
 *
 * <ol>
 * <li><b>The comparison tree is LAZY, in two ways.</b> A node whose
 * {@code ComparisonNodeStatus} is {@code Unfinished} / {@code HasUnfinishedChildren} has an empty
 * or partial child list because the engine has not reached it, NOT because the sides agree - so the
 * tool asks the engine to {@code prioritize} the node and then waits, bounded by
 * {@code waitSeconds}, on THAT NODE's own status. And a node the engine has not built yet is not
 * merely unfinished: it is ABSENT, so the address resolves to nothing at all. Both waits share one
 * budget, and neither absence is reported as a verdict: an expired wait says the subtree is
 * unfinished, and an address that never resolved says whether the tree was still building or the
 * comparison genuinely has no such node. It never renders "no differences" over an uncompared
 * subtree, and never calls a valid object nonexistent because nobody had compared it yet.</li>
 * <li><b>Nodes live in the comparison's OWN BM store.</b> Every node read therefore happens inside
 * {@code ComparisonEngine.read(...)} (which wraps
 * {@code IComparisonSession.runComparisonTreeReadonlyTask}), never inside a project transaction -
 * CLAUDE.md don't #1 applies to the wrong store just as much as to no store at all. No comparison
 * node object is allowed to escape that boundary: the first read returns node IDs, the second read
 * renders.</li>
 * </ol>
 */
public class GetComparisonNodeTool implements IMcpTool
{
    public static final String NAME = "get_comparison_node"; //$NON-NLS-1$

    /** Per-call wait applied to the lazy-node status, in seconds. */
    static final int DEFAULT_WAIT_SECONDS = 10;

    /** Transport-safe ceiling for the per-call wait, in seconds. */
    static final int MAX_WAIT_SECONDS = 25;

    /** Child levels descended when the caller does not ask for more. */
    static final int DEFAULT_DEPTH = 1;

    /** Deepest child descent a caller may request. */
    static final int MAX_DEPTH = 5;

    /** Rows per table when the caller does not ask for more. */
    static final int DEFAULT_LIMIT = 100;

    /** Largest number of rows per table a caller may request. */
    static final int MAX_LIMIT = 500;

    /** Gap between two status polls while waiting for a lazy node, in milliseconds. */
    static final long POLL_INTERVAL_MILLIS = 200L;

    private static final String KEY_COMPARISON_ID = "comparisonId"; //$NON-NLS-1$
    private static final String KEY_OBJECT_FQN = "objectFqn"; //$NON-NLS-1$
    private static final String KEY_NODE_ID = "nodeId"; //$NON-NLS-1$
    private static final String KEY_SIDE = "side"; //$NON-NLS-1$
    private static final String KEY_DEPTH = "depth"; //$NON-NLS-1$
    private static final String KEY_LIMIT = "limit"; //$NON-NLS-1$
    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$

    private static final String SIDE_MAIN = "main"; //$NON-NLS-1$
    private static final String SIDE_OTHER = "other"; //$NON-NLS-1$
    private static final String SIDE_ANCESTOR = "ancestor"; //$NON-NLS-1$

    private final NodeSource source;

    private final Ticker ticker;

    /** Production constructor: resolves the engine lazily, so construction touches no EDT service. */
    public GetComparisonNodeTool()
    {
        this(new EngineNodeSource());
    }

    GetComparisonNodeTool(NodeSource source)
    {
        this(source, System::nanoTime);
    }

    /**
     * @param source the read port
     * @param ticker the elapsed-time source this call's budget is spent against
     */
    GetComparisonNodeTool(NodeSource source, Ticker ticker)
    {
        this.source = source;
        this.ticker = ticker;
    }

    // ==================== The call's own budget ====================

    /**
     * The elapsed-time source a call budget is measured against - {@code System::nanoTime} in
     * production, a scripted one in a test.
     *
     * <h2>Why not the system clock</h2>
     * {@code waitSeconds} is an upper bound on how long this MCP call may block, and a bound is
     * only a bound if the thing measuring it cannot be moved. The system's wall clock can be:
     * NTP corrects it, an operator sets it, and a virtual machine resumed from a snapshot hands
     * the JVM a reading from before the call started. Every one of those STEPS IT BACK, and a
     * deadline computed as "the reading at the start plus the budget" then sits that much further
     * into the future - a 25-second promise becomes minutes or hours of a blocked call, with
     * nothing in the log to say why. That is what this file used to do, and the name of the wall
     * clock is deliberately not written down anywhere in it any more, so that grepping for it
     * keeps returning nothing - {@code GetComparisonNodeToolTest} fails the build if it comes
     * back.
     *
     * <h2>What it is used for, and what it is not</h2>
     * Elapsed time inside one call, and nothing else. It is not a timestamp, it cannot be
     * rendered, and it must not be compared against a reading taken from any other source - the
     * origin of {@code nanoTime} is arbitrary and may be negative, which is exactly why
     * {@link Budget} accumulates DIFFERENCES rather than comparing against an absolute instant.
     */
    @FunctionalInterface
    interface Ticker
    {
        /**
         * @return a monotonically advancing reading in nanoseconds, whose origin is arbitrary
         */
        long nanoTime();
    }

    /**
     * How much of one call's {@code waitSeconds} is left, spent against a {@link Ticker}.
     *
     * <h2>Differences, not an absolute deadline</h2>
     * The budget is spent by the FORWARD progress of the time source: each reading charges
     * {@code now - last}, and the sum is compared against the budget. Two things follow, and both
     * are the reason this is not the one-liner {@code start + budget}:
     * <ul>
     *   <li>the origin of {@code System.nanoTime()} is arbitrary and may sit anywhere in the
     *       {@code long} range, so {@code start + budget} can overflow and {@code now < deadline}
     *       is then simply the wrong comparison - the platform's own javadoc says to subtract;</li>
     *   <li>a reading that goes BACKWARDS spends nothing. It cannot refund what was already spent,
     *       so no step of the source can push the end of the wait further away than the budget the
     *       caller asked for. That is the property that has to hold for {@code waitSeconds} to be
     *       a bound at all, and it is a property of this class rather than of the machine.</li>
     * </ul>
     *
     * <h2>One budget, several waits</h2>
     * One instance is shared by every wait in a call - the address resolution and the node status
     * alike - so the sum of them is bounded, not each one separately. Time spent between two
     * {@link #expired()} calls is charged by the next one, because it is charged from the previous
     * READING and not from the previous loop.
     */
    static final class Budget
    {
        private final Ticker ticker;

        private final long budgetNanos;

        private long lastReading;

        private long spentNanos;

        /**
         * @param ticker the elapsed-time source
         * @param budgetNanos how many nanoseconds this call may spend waiting; negative is read as
         *            zero
         */
        Budget(Ticker ticker, long budgetNanos)
        {
            this.ticker = ticker;
            this.budgetNanos = Math.max(0L, budgetNanos);
            this.lastReading = ticker.nanoTime();
        }

        /**
         * Charges the time since the previous reading and says whether the budget is gone.
         *
         * @return {@code true} when nothing is left to wait with
         */
        boolean expired()
        {
            long now = ticker.nanoTime();
            long step = now - lastReading;
            lastReading = now;
            if (step > 0L)
            {
                // Saturating, so that one enormous step cannot wrap the total back to "plenty
                // left" - the failure this whole class exists to make unreachable.
                spentNanos = spentNanos > Long.MAX_VALUE - step ? Long.MAX_VALUE : spentNanos + step;
            }
            return spentNanos >= budgetNanos;
        }
    }

    // ==================== The read port ====================

    /**
     * Everything this tool needs from the comparison engine, and nothing more. It exists so the
     * tool's own logic - address resolution, the lazy-node wait, the honest unfinished report - is
     * provable by a unit test with no EDT present, and so the facade contract lives in exactly one
     * adapter ({@link EngineNodeSource}).
     */
    public interface NodeSource
    {
        /**
         * @param comparisonId the caller's comparison id
         * @return {@code true} when a live comparison session is registered under that id
         */
        boolean isKnown(String comparisonId);

        /** @return the ids of every live comparison THIS SERVER started, for a "did you mean" error */
        List<String> knownComparisonIds();

        /**
         * Whether EDT itself reports a comparison occupying its single slot.
         * <p>
         * Asked in addition to {@link #knownComparisonIds()} because that list answers a narrower
         * question than a refusal used to claim. It holds the comparisons this server started, so
         * an empty one is not "nothing is running": a comparison launched from the workbench takes
         * the slot under no id of ours and never appears in it.
         *
         * @return EDT's answer, or {@link PlatformAnswer#unavailable()} when the comparison
         *     service could not be asked - which is a third case again, and not a "no"
         */
        PlatformAnswer<Boolean> edtHasActiveComparison();

        /**
         * Asks the engine to compare these nodes next. A hint, not a guarantee - the caller still
         * has to wait on the node status.
         *
         * @param comparisonId the comparison id
         * @param nodeIds the node ids to raise
         */
        void prioritize(String comparisonId, List<Long> nodeIds);

        /**
         * Runs {@code task} inside the comparison's read boundary.
         *
         * @param <T> the task result
         * @param comparisonId the comparison id
         * @param task the work to run
         * @return the task's result
         * @throws IllegalStateException when the session is gone or the engine is unavailable
         */
        <T> T read(String comparisonId, ReadTask<T> task);
    }

    /** Node lookups that are only legal inside the comparison read boundary. */
    public interface TreeAccess
        extends ComparisonNodeRenderer.NodeAccess
    {
        /**
         * @param symlink an all-English EDT qualified name
         * @param side the side the symlink addresses
         * @return the top node, or {@code null} when the comparison has none under that name
         */
        ComparisonNode topNode(String symlink, ComparisonSide side);

        /**
         * @param nodeId the node id
         * @return the node, or {@code null} when this comparison has no such node
         */
        ComparisonNode node(long nodeId);

        /**
         * The status of a TOP node. It sits on this interface, and not on the port, because it is a
         * model read like every other one here: the platform resolves the id through the comparison
         * engine's {@code getObjectById} and then reads the status feature off the resulting
         * {@code IBmObject}. Declaring it here is what makes "the status is read inside the
         * boundary" a property of the type rather than a habit.
         *
         * @param topNodeId the top node id whose status governs the subtree
         * @return the node's own status, or {@code null} when it cannot be read
         */
        ComparisonNodeStatus topNodeStatus(long topNodeId);

        /**
         * Whether this comparison covers the WHOLE configuration rather than a scope.
         * <p>
         * It exists because the scope's exclusion is INVISIBLE from a node - the flags of an
         * object whose features were never compared read exactly like the flags of one that was
         * compared and found equal - so a document that did not carry this fact reported the
         * second when it had observed the first. What is carried is the RUN's own answer and
         * nothing narrower: no reading of the tree reproduces the platform's per-object exclusion
         * predicate, see {@link ComparisonNodeRenderer.ContentCoverage}. The classification into
         * the rendered coverage is done by the TOOL, from this boolean, so that it is exercised by
         * the tool's own tests rather than supplied ready-made by a fake.
         * <p>
         * It sits on this interface for the same reason {@link #topNodeStatus(long)} does: it is
         * asked of the session inside the boundary that renders, so the document describes one
         * comparison at one moment.
         *
         * @return {@code true} when the run compared the whole configuration
         */
        boolean wholeConfigurationRun();

        /**
         * The status of the WHOLE tree, read off its root node.
         * <p>
         * It exists for exactly one question, and it is a question about evidence: when an address
         * resolves to no node, is that because the comparison has no such object, or because the
         * engine has not built that part of the tree yet? Only the first is a fact about the
         * caller's address. Without this the tool answered "no such object" for both.
         *
         * @return the root's own status, or {@code null} when there is no root or it cannot be read
         */
        ComparisonNodeStatus treeStatus();
    }

    /**
     * A unit of work that runs inside the comparison read boundary.
     *
     * @param <T> the result type
     */
    public interface ReadTask<T>
    {
        /**
         * @param access the in-boundary node lookups
         * @return the result
         */
        T run(TreeAccess access);
    }

    // ==================== Tool surface ====================

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Expand one node of a comparison started by compare_configurations: three-way " //$NON-NLS-1$
            + "property table, form structure, module sections, support state and potential " //$NON-NLS-1$
            + "problems. Address the node by objectFqn (Russian or English type tokens both work) " //$NON-NLS-1$
            + "or by the nodeId from the comparison report. The tree is built lazily, so an " //$NON-NLS-1$
            + "unfinished subtree is reported as unfinished, never as 'no differences'. Read-only: " //$NON-NLS-1$
            + "it never merges. Parameters and examples: get_tool_guide('get_comparison_node')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_COMPARISON_ID,
                "Comparison id reported by compare_configurations.", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the object to expand, e.g. 'Catalog.Products' or " //$NON-NLS-1$
                    + "'Справочник.Товары'. " //$NON-NLS-1$
                    + "Supply this or nodeId, not both.") //$NON-NLS-1$
            .integerProperty(KEY_NODE_ID,
                "Node id from the comparison report. Supply this or objectFqn, not both.") //$NON-NLS-1$
            .enumProperty(KEY_SIDE,
                "Side the objectFqn addresses; defaults to 'main'.", //$NON-NLS-1$
                SIDE_MAIN, SIDE_OTHER, SIDE_ANCESTOR)
            .integerProperty(KEY_DEPTH,
                "Child levels to descend, 1 to " + MAX_DEPTH + " (default " + DEFAULT_DEPTH + ").") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_LIMIT,
                "Maximum rows per table, 1 to " + MAX_LIMIT + " (default " + DEFAULT_LIMIT + ").") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_WAIT_SECONDS,
                "Maximum time this call may wait for the node to finish comparing, in seconds; " //$NON-NLS-1$
                    + "defaults to " + DEFAULT_WAIT_SECONDS + " and accepts 0 to " //$NON-NLS-1$ //$NON-NLS-2$
                    + MAX_WAIT_SECONDS + ".") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        return "comparison-node.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArgument(params, KEY_COMPARISON_ID,
            ". Pass the comparisonId reported by compare_configurations."); //$NON-NLS-1$
        if (required != null)
        {
            return required;
        }
        String comparisonId = trimToNull(JsonUtils.extractStringArgument(params, KEY_COMPARISON_ID));
        if (comparisonId == null)
        {
            return ToolResult.error("comparisonId must contain a non-empty comparison id. Pass the " //$NON-NLS-1$
                + "comparisonId reported by compare_configurations.").toJson(); //$NON-NLS-1$
        }

        String objectFqn = trimToNull(JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN));
        String rawNodeId = trimToNull(JsonUtils.extractStringArgument(params, KEY_NODE_ID));
        if (objectFqn == null && rawNodeId == null)
        {
            return ToolResult.error("Address the node: pass objectFqn (e.g. 'Catalog.Products') or " //$NON-NLS-1$
                + "nodeId (from the compare_configurations report). Neither was supplied.").toJson(); //$NON-NLS-1$
        }
        if (objectFqn != null && rawNodeId != null)
        {
            return ToolResult.error("Pass objectFqn or nodeId, not both: objectFqn '" + objectFqn //$NON-NLS-1$
                + "' and nodeId '" + rawNodeId + "' address different nodes and the tool will not " //$NON-NLS-1$ //$NON-NLS-2$
                + "guess which one you meant.").toJson(); //$NON-NLS-1$
        }
        Long explicitNodeId = null;
        if (rawNodeId != null)
        {
            explicitNodeId = parseNodeId(rawNodeId);
            if (explicitNodeId == null)
            {
                return ToolResult.error("nodeId must be a whole number, but was '" + rawNodeId //$NON-NLS-1$
                    + "'. Copy the Node id column from the compare_configurations report.").toJson(); //$NON-NLS-1$
            }
        }

        String rawSide = trimToNull(JsonUtils.extractStringArgument(params, KEY_SIDE));
        ComparisonSide side = parseSide(rawSide);
        if (side == null)
        {
            return ToolResult.error("side must be one of 'main', 'other', 'ancestor', but was '" //$NON-NLS-1$
                + rawSide + "'.").toJson(); //$NON-NLS-1$
        }

        int depth = Pagination.clampLimit(JsonUtils.extractIntArgument(params, KEY_DEPTH,
            DEFAULT_DEPTH), MAX_DEPTH);
        int limit = Pagination.clampLimit(JsonUtils.extractIntArgument(params, KEY_LIMIT,
            DEFAULT_LIMIT), MAX_LIMIT);

        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params.get(KEY_WAIT_SECONDS), DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        }

        try
        {
            return expand(comparisonId, objectFqn, explicitNodeId, side, depth, limit,
                waitSeconds.intValue());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for node '" //$NON-NLS-1$
                + (objectFqn != null ? objectFqn : String.valueOf(explicitNodeId))
                + "' to finish comparing.").toJson(); //$NON-NLS-1$
        }
        catch (ComparisonUnreadableException e)
        {
            // Already worded, and returned as it is: this is the platform's own answer about the
            // comparison, not a failure of this tool, so it is neither logged as one nor given
            // the generic branch's advice.
            return e.errorJson();
        }
        catch (RuntimeException e)
        {
            Activator.logError("get_comparison_node failed", e); //$NON-NLS-1$
            // ComparisonFailures.describe, not getMessage(): EMF/BM routinely throw with a null
            // message, which would render the literal "Could not expand the comparison node:
            // null.", and the raw message can carry an implementation object's identity.
            return ToolResult.error("Could not expand the comparison node: " //$NON-NLS-1$
                + ComparisonFailures.describe(e)
                + ". Check the comparison is still alive with get_job_status, or start a new one " //$NON-NLS-1$
                + "with compare_configurations.").toJson(); //$NON-NLS-1$
        }
    }

    // ==================== Expansion ====================

    private String expand(String comparisonId, String objectFqn, Long explicitNodeId,
        ComparisonSide side, int depth, int limit, int waitSeconds) throws InterruptedException
    {
        if (!source.isKnown(comparisonId))
        {
            return unknownComparisonError(comparisonId);
        }

        // The symlink the engine matches against is an ALL-ENGLISH qualified name, and it has no
        // bilingual branch: a Russian nested FQN whose deeper structural segments were left in
        // Russian resolves to nothing at all rather than to an error, so canonicalise every segment
        // before the lookup.
        String symlink = objectFqn == null ? null : canonicalize(objectFqn);

        // ONE budget for the whole call, and it is spent in two places: first on the address
        // resolving at all, then on the node it named finishing. Both are the same lazy tree.
        // Spent against a MONOTONIC source: a wall-clock deadline stops bounding the call the
        // moment the system clock steps back. See Budget.
        Budget budget = new Budget(ticker, TimeUnit.SECONDS.toNanos(waitSeconds));

        // First read: resolve the address to plain IDs. Nothing from the comparison's BM store is
        // allowed out of the boundary, so the node itself stays inside. Retried until the budget
        // runs out, because an address that resolves to nothing RIGHT AFTER a launch usually means
        // the engine has not built that node yet - and answering "no such object" to that is a
        // verdict about the caller's address that nothing observed supports.
        Attempt attempt = locateWithin(comparisonId, symlink, explicitNodeId, side, budget);
        if (attempt.located == null)
        {
            // The refusal is built from the SNAPSHOT, never from a fresh look: see notLocatedError.
            return notLocatedError(attempt.treeStatus, comparisonId, objectFqn, symlink,
                explicitNodeId);
        }
        Located located = attempt.located;

        // Waited on, and the result deliberately NOT carried into the render: it is a reading taken
        // in a boundary that has since closed. See below.
        awaitNode(comparisonId, located.statusNodeId, budget);

        String address = located.address != null ? located.address
            : (objectFqn != null ? objectFqn : "nodeId " + located.nodeId); //$NON-NLS-1$

        // Second read: the status is re-read HERE, inside the same boundary that renders, and the
        // document is built from THAT reading. Rendering from the wait's snapshot let a comparison
        // EDT had begun re-running be described as FINISHED, and "No differences" was then printed
        // over a tree being rebuilt. One boundary, one reading, one document.
        String markdown = source.read(comparisonId, access -> {
            ComparisonNode node = access.node(located.nodeId);
            if (node == null)
            {
                return null;
            }
            // Read in the SAME boundary as the node and its status, so the three cannot describe
            // different moments of the same comparison.
            ComparisonNodeRenderer.Request request = new ComparisonNodeRenderer.Request(comparisonId,
                address, side, access.topNodeStatus(located.statusNodeId), depth, limit, null,
                ComparisonNodeRenderer.ContentCoverage.ofRun(access.wholeConfigurationRun()));
            return ComparisonNodeRenderer.render(request, node, access);
        });
        if (markdown == null)
        {
            return unknownNodeError(comparisonId, located.nodeId);
        }
        return markdown;
    }

    /**
     * Resolves the caller's address, retrying while retrying can still change the answer.
     *
     * <h2>Why it does not simply spend the budget</h2>
     * Retrying is for ONE reason: the tree is built lazily, so an address that answers nothing
     * right after a launch is usually a node the engine has not reached yet. Once the tree reports
     * itself FINISHED there is no later node - a wrong FQN, a node id from another comparison, a
     * name that exists only on the other side, all of which are ordinary ways to call this tool -
     * and the loop
     * was still sleeping out the whole of {@code waitSeconds} to produce the answer it already
     * had. A refusal is not worth more for being slow.
     *
     * <h2>The two states this must not confuse</h2>
     * "The tree is finished" and "this node is not built yet" are different readings, and only the
     * first one is final: a node still being built inside an UNFINISHED tree is exactly what the
     * wait exists for, and that case is untouched. Both readings are taken in ONE boundary, so the
     * absence and the tree status are the same observation - reading them in two boundaries would
     * let a node appear between them and turn a tree that had just finished building it into a
     * final "no such node".
     *
     * @param comparisonId the comparison id
     * @param symlink the canonical symlink, or {@code null} when addressing by node id
     * @param explicitNodeId the node id, or {@code null} when addressing by FQN
     * @param side the addressed side
     * @param budget the call's own elapsed-time budget, shared with the node wait
     * @return the LAST look, whole: the resolved ids, or their absence together with the tree
     *     status read beside it in the same boundary
     * @throws InterruptedException when the wait is interrupted
     */
    private Attempt locateWithin(String comparisonId, String symlink, Long explicitNodeId,
        ComparisonSide side, Budget budget) throws InterruptedException
    {
        Attempt attempt = attemptLocate(comparisonId, symlink, explicitNodeId, side);
        while (attempt.located == null && attempt.treeStatus != ComparisonNodeStatus.FINISHED
            && !budget.expired())
        {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            attempt = attemptLocate(comparisonId, symlink, explicitNodeId, side);
        }
        // The WHOLE attempt leaves this method. Handing back only the Located threw the other half
        // of the pair away, and the refusal below then re-read the tree status in a NEW boundary -
        // so the pin "both readings are one observation" held right up to the point where the
        // verdict was actually produced, and no further.
        return attempt;
    }

    /**
     * One look for the address, together with the tree status that decides whether looking again
     * could answer differently - both read inside the SAME boundary.
     *
     * @param comparisonId the comparison id
     * @param symlink the canonical symlink, or {@code null} when addressing by node id
     * @param explicitNodeId the node id, or {@code null} when addressing by FQN
     * @param side the addressed side
     * @return what this look saw; never {@code null}
     */
    private Attempt attemptLocate(String comparisonId, String symlink, Long explicitNodeId,
        ComparisonSide side)
    {
        return source.read(comparisonId, access -> {
            Located found = locate(access, symlink, explicitNodeId, side);
            if (found != null)
            {
                return Attempt.found(found);
            }
            return Attempt.missing(access.treeStatus());
        });
    }

    /**
     * Says WHY an address resolved to nothing, from the LAST look's own reading of the tree.
     *
     * <h2>Why the status is a parameter and not a read</h2>
     * This is where the verdict is produced, so this is where the atomicity has to hold. Opening a
     * second boundary here to ask the tree status again made the refusal an assembly of two
     * instants, and the losing interleaving is ordinary rather than exotic: the last look sees
     * "no node, tree UNFINISHED"; the engine then builds that very node and finishes the tree; the
     * second read sees FINISHED, and the caller is told the object does not exist - about a node
     * that by then does. Reading the pair once and carrying it is the only shape in which the two
     * halves of the judgement cannot disagree.
     *
     * @param treeStatus the tree status read in the SAME boundary that found nothing
     * @param comparisonId the comparison id
     * @param objectFqn the caller's FQN, or {@code null}
     * @param symlink the canonical symlink that was looked up, or {@code null}
     * @param explicitNodeId the caller's node id, or {@code null}
     * @return the refusal
     */
    private static String notLocatedError(ComparisonNodeStatus treeStatus, String comparisonId,
        String objectFqn, String symlink, Long explicitNodeId)
    {
        if (treeStatus != ComparisonNodeStatus.FINISHED)
        {
            return unbuiltTreeError(comparisonId, objectFqn != null
                ? "objectFqn '" + objectFqn + "'" : "nodeId " + explicitNodeId, treeStatus); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return objectFqn != null ? unknownObjectError(comparisonId, objectFqn, symlink)
            : unknownNodeError(comparisonId, explicitNodeId.longValue());
    }

    /**
     * Waits - bounded by the budget the whole call shares - for the node's own status to reach
     * {@code Finished}, asking the engine to prioritize it first.
     *
     * <p>The status it returns is the last one OBSERVED HERE, and the render deliberately does not
     * use it: by the time the render boundary opens, that reading belongs to a boundary that has
     * closed. The value is returned for the caller's own decisions and for tests.</p>
     *
     * @param comparisonId the comparison id
     * @param statusNodeId the top node id whose status governs the subtree
     * @param budget the call's own elapsed-time budget, shared with the address resolution
     * @return the last status observed
     * @throws InterruptedException when the wait is interrupted
     */
    private ComparisonNodeStatus awaitNode(String comparisonId, long statusNodeId, Budget budget)
        throws InterruptedException
    {
        ComparisonNodeStatus status = statusOf(comparisonId, statusNodeId);
        if (status == ComparisonNodeStatus.FINISHED)
        {
            return status;
        }
        source.prioritize(comparisonId, Collections.singletonList(Long.valueOf(statusNodeId)));
        while (status != ComparisonNodeStatus.FINISHED && !budget.expired())
        {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            status = statusOf(comparisonId, statusNodeId);
        }
        return status;
    }

    /**
     * The node's own status, read INSIDE the comparison's read boundary.
     *
     * <p>The platform's {@code getTopNodeStatus} is not a cached counter: it resolves the id
     * through the comparison engine's {@code getObjectById} and then reads the status feature off
     * the resulting {@code IBmObject}, so it is a model read of the comparison's private BM store
     * and CLAUDE.md don't #1 applies to it exactly as it does to every other node read here.</p>
     *
     * <p>Each poll opens its own boundary rather than one boundary spanning the wait: a single
     * read transaction held across the sleeps would be one frozen view of a tree the engine is
     * still building, and the wait would never observe the node finishing. Prioritising, by
     * contrast, needs no boundary at all - it only reorders the engine's own work queue.</p>
     *
     * @param comparisonId the comparison id
     * @param statusNodeId the top node id whose status governs the subtree
     * @return the node's own status, or {@code null} when it cannot be read
     */
    private ComparisonNodeStatus statusOf(String comparisonId, long statusNodeId)
    {
        return source.read(comparisonId, access -> access.topNodeStatus(statusNodeId));
    }

    /** Resolves the caller's address inside the read boundary; {@code null} when nothing matches. */
    private static Located locate(TreeAccess access, String symlink, Long explicitNodeId,
        ComparisonSide side)
    {
        ComparisonNode node = explicitNodeId != null ? access.node(explicitNodeId.longValue())
            : access.topNode(symlink, side);
        if (node == null)
        {
            return null;
        }
        Located located = new Located();
        located.nodeId = node.bmGetId();
        located.statusNodeId = topNodeIdOf(node);
        located.address = addressOf(node, side, symlink);
        return located;
    }

    /**
     * The id of the TOP node whose status governs {@code node}: the node itself when it is one,
     * otherwise its nearest top ancestor. Only a top node carries a comparison status, so asking for
     * a containment node's status would read nothing and look like "unfinished forever".
     */
    private static long topNodeIdOf(ComparisonNode node)
    {
        ComparisonNode current = node;
        while (current != null)
        {
            if (current instanceof TopComparisonNode)
            {
                return current.bmGetId();
            }
            current = current.getParent();
        }
        return node.bmGetId();
    }

    /** The heading text: the node's own symlink when it has one, else the caller's own address. */
    private static String addressOf(ComparisonNode node, ComparisonSide side, String symlink)
    {
        if (node instanceof SymlinkComparisonNode)
        {
            SymlinkComparisonNode symlinkNode = (SymlinkComparisonNode)node;
            String own = symlinkNode.getSymlink(side);
            if (own == null || own.isEmpty())
            {
                own = symlinkNode.getMainSymlink();
            }
            if (own != null && !own.isEmpty())
            {
                return own;
            }
        }
        return symlink;
    }

    // ==================== Parameter parsing ====================

    /**
     * The largest magnitude a {@code double} carries with no rounding. A JSON number bigger than
     * this reached us through a {@code double} that could not hold it, so the digits we were handed
     * may name a NEIGHBOURING node rather than the one the caller meant - and expanding the wrong
     * node silently is worse than refusing.
     */
    private static final long EXACT_IN_DOUBLE = 1L << 53;

    /**
     * A node id, or {@code null} when the text does not name one.
     *
     * <h2>Two spellings, because the wire has two</h2>
     * The id is a BM object id copied out of the {@code compare_configurations} report, and the
     * schema asks for an INTEGER - but this tool is handed a string, and how the number got into
     * that string is not the caller's doing. Gson renders every JSON number through
     * {@code Double.toString()} (the same fact {@code JsonUtils.extractIntArgument} documents and
     * handles), so a client that correctly sends {@code "nodeId": 4294967296} delivers
     * {@code "4.294967296E9"} here, and {@code "nodeId": 1} delivers {@code "1.0"}.
     * <p>
     * This used to accept only {@code Long.parseLong} plus a stripped trailing {@code ".0"}, and so
     * refused the tool's OWN ids: every node id at or above 10^7 - which the report hands out
     * routinely - came back as "nodeId must be a whole number". The refusal named the mangled text,
     * which no caller could act on, because the caller had typed the right number.
     *
     * <h2>What is still refused, and why</h2>
     * A fractional value, a non-number, anything outside {@code long}, and - the one that matters -
     * an integral value beyond {@link #EXACT_IN_DOUBLE}. Past that point the double that carried the
     * number could not hold it exactly, so the digits here may be a neighbouring id that is itself
     * perfectly plausible. Refusing is the only honest answer: the true id is already lost, and
     * expanding its neighbour would answer a question nobody asked.
     * <p>
     * Scientific notation is no longer treated as suspect on its own. {@code 1e3} is a legitimate
     * JSON spelling of 1000 - JSON has no separate integer type - so reading it as node 1000 is
     * correct rather than a guess, and it is indistinguishable from Gson's own {@code 1.0E3}.
     *
     * @param raw the caller's text
     * @return the id, or {@code null} when the text does not name one
     */
    private static Long parseNodeId(String raw)
    {
        String text = raw.trim();
        try
        {
            // The trustworthy spelling: exact digits, any magnitude a long can hold.
            return Long.valueOf(Long.parseLong(text));
        }
        catch (NumberFormatException notPlainDigits)
        {
            // Not an integer literal. It may still be the double-shaped rendering of one.
        }
        BigDecimal decimal;
        try
        {
            decimal = new BigDecimal(text);
        }
        catch (NumberFormatException notANumber)
        {
            return null;
        }
        long value;
        try
        {
            // Refuses a fractional part and anything outside long, rather than converting it.
            value = decimal.longValueExact();
        }
        catch (ArithmeticException notAWholeId)
        {
            return null;
        }
        return Math.abs(value) > EXACT_IN_DOUBLE ? null : Long.valueOf(value);
    }

    private static ComparisonSide parseSide(String raw)
    {
        if (raw == null || SIDE_MAIN.equalsIgnoreCase(raw))
        {
            return ComparisonSide.MAIN;
        }
        if (SIDE_OTHER.equalsIgnoreCase(raw))
        {
            return ComparisonSide.OTHER;
        }
        if (SIDE_ANCESTOR.equalsIgnoreCase(raw))
        {
            return ComparisonSide.COMMON_ANCESTOR;
        }
        return null;
    }

    /**
     * Lifts the caller's (possibly Russian, possibly nested) FQN to the all-English, case-preserving
     * form the comparison engine addresses nodes by. A canonicaliser that cannot make sense of the
     * text hands back nothing; the caller's own spelling is then used so the failure is reported as
     * "no such node", naming both spellings, rather than as a silent empty result.
     *
     * <p>It goes through {@link ComparisonScopeBuilder#canonicalSymlink(String)} - the same entry
     * point {@code compare_configurations} scopes with - so the two tools share ONE address
     * vocabulary. The configuration root is why that matters: it is not a metadata type, so the
     * shared metadata canonicaliser copies it through verbatim, and a comparison scoped with the
     * Russian root token would otherwise be unexpandable by the very spelling that scoped it.</p>
     */
    private static String canonicalize(String objectFqn)
    {
        String canonical = ComparisonScopeBuilder.canonicalSymlink(objectFqn);
        return canonical == null || canonical.isEmpty() ? objectFqn : canonical;
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== Errors ====================

    /**
     * The refusal for an id nothing answers to, saying only what was actually established.
     * <p>
     * The list it offers holds the comparisons THIS SERVER started, and an empty one used to be
     * rendered as "none is running right now" - a claim about EDT that this list cannot support.
     * Two situations make it false: a comparison launched from the workbench holds the single slot
     * under no id of ours, and the registry answers an empty list just as readily when the bundle
     * is not started at all. So the empty case states the narrow fact and then adds EDT's own
     * answer, with "could not be asked" kept apart from "no".
     *
     * @param comparisonId the id the caller quoted
     * @return the refusal
     */
    private String unknownComparisonError(String comparisonId)
    {
        List<String> known = source.knownComparisonIds();
        String alive = known == null || known.isEmpty() ? noKnownComparisonsText()
            : "live comparisons: " + String.join(", ", known); //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("Unknown comparison '" + comparisonId + "' (" + alive //$NON-NLS-1$ //$NON-NLS-2$
            + "). Start one with compare_configurations, or poll the one you started with " //$NON-NLS-1$
            + "get_job_status.").toJson(); //$NON-NLS-1$
    }

    /**
     * @return what to say when this server holds no comparison of its own, qualified by whatever
     *     EDT answered about its single slot
     */
    private String noKnownComparisonsText()
    {
        // Worded in ONE place, shared with compare_configurations' own refusal: the same claim
        // lived in both tools and only one of them was corrected, which is how the second one
        // went on telling callers that nothing was running.
        return ComparisonFailures.noKnownComparisonsText(source.edtHasActiveComparison());
    }

    /**
     * The refusal for an address the comparison MATCHED nothing under.
     *
     * <h2>Why the scope is not offered as the reason</h2>
     * It used to lead with "the object may be outside the comparison scope", and that contradicts
     * what this tool now promises: a scope does not narrow the TREE, so an object outside it is
     * still matched and still answered - with the run's coverage notice on top. Absence therefore
     * means the comparison has no MATCHED node under this name on this side, and the reasons that
     * really produce it are the addressed side and the spelling. The one scope-shaped case left is
     * a node the engine builds only under an object whose content was compared, and it is named as
     * that - a node BELOW an object - rather than generalised to any object outside the scope.
     *
     * <h2>What the report it sends the caller to actually lists</h2>
     * TOP-level nodes, and only as many of them as its own parameters let through:
     * {@code ComparisonTreeReport} accepts a {@code TopComparisonNode} alone, {@code changedOnly}
     * drops the identical ones and {@code limit} cuts the rest. So the sentence names that, and
     * does not offer it as a full index of everything the run compared - an address absent from a
     * filtered page is not an address the comparison lacks, and a refusal that implied otherwise
     * would turn its own advice into a second false verdict.
     *
     * @param comparisonId the comparison id
     * @param objectFqn the address as the caller spelled it
     * @param symlink the canonicalised address, when it differs
     * @return the refusal
     */
    private static String unknownObjectError(String comparisonId, String objectFqn, String symlink)
    {
        String canonicalNote = symlink != null && !symlink.equals(objectFqn)
            ? " (canonicalised to '" + symlink + "')" : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ToolResult.error("Comparison '" + comparisonId //$NON-NLS-1$
            + "' has no matched node under objectFqn '" + objectFqn + "'" + canonicalNote //$NON-NLS-1$ //$NON-NLS-2$
            + " on the addressed side. The object may not exist on that side - try the other " //$NON-NLS-1$
            + "`side`, since a renamed object is reachable under its own side's name - or the FQN " //$NON-NLS-1$
            + "may be misspelled. Being outside a `scope` is NOT a reason: a scoped run still " //$NON-NLS-1$
            + "matches every object and this tool still answers for it, saying that the run " //$NON-NLS-1$
            + "excluded content outside the scope. What a scope does remove is the nodes BELOW " //$NON-NLS-1$
            + "such an object that the engine builds only from compared content. The " //$NON-NLS-1$
            + "compare_configurations report lists the TOP-level nodes with their nodeId, as " //$NON-NLS-1$
            + "far as its own `changedOnly` and `limit` let through - it is not a full index of " //$NON-NLS-1$
            + "what the run compared.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The address resolved to nothing while the tree was still being built. That is "not compared
     * yet", and it is not the same fact as "the comparison has no such node" - only the second one
     * says the caller's address is wrong, so only the second one is allowed to say so.
     *
     * @param comparisonId the comparison id
     * @param address how the caller addressed the node, already quoted
     * @param treeStatus the tree's own status, or {@code null} when it could not be read
     * @return the refusal
     */
    private static String unbuiltTreeError(String comparisonId, String address,
        ComparisonNodeStatus treeStatus)
    {
        return ToolResult.error("Comparison '" + comparisonId + "' has no node for " + address //$NON-NLS-1$ //$NON-NLS-2$
            + " YET: its tree is still being built (tree status: " //$NON-NLS-1$
            + (treeStatus == null ? "not reported" : treeStatus.getLiteral()) //$NON-NLS-1$
            + "), so this is 'not compared yet' and NOT 'no such object in the comparison'. The " //$NON-NLS-1$
            + "waitSeconds budget expired before the node appeared. Call again with a larger " //$NON-NLS-1$
            + "waitSeconds (up to " + MAX_WAIT_SECONDS + "), or wait for the comparison job to " //$NON-NLS-1$ //$NON-NLS-2$
            + "finish with get_job_status and then expand the node.").toJson(); //$NON-NLS-1$
    }

    private static String unknownNodeError(String comparisonId, long nodeId)
    {
        return ToolResult.error("Comparison '" + comparisonId + "' has no node with id " + nodeId //$NON-NLS-1$ //$NON-NLS-2$
            + ". Node ids belong to one comparison only - take a fresh id from the " //$NON-NLS-1$
            + "compare_configurations report for THIS comparison.").toJson(); //$NON-NLS-1$
    }

    /** The IDs and heading the first read hands back; no comparison-tree object escapes with it. */
    private static final class Located
    {
        private long nodeId;
        private long statusNodeId;
        private String address;
    }

    /**
     * What one address lookup saw: the node, or its absence together with whether the tree can
     * still produce it.
     * <p>
     * The two travel together because they are one reading. Split into two boundaries they are two
     * readings of a tree that changes between them, and the pair "absent, and finished" - the pair
     * that ends the wait AND the pair the refusal is decided by - would then be assembled out of
     * two instants.
     * <p>
     * That is why the whole value leaves {@code locateWithin} rather than its {@link #located}
     * half alone: the WAIT was already atomic, but the verdict was produced further down, out of a
     * second reading, and the verdict is the only place the atomicity actually had to hold.
     */
    private static final class Attempt
    {
        private final Located located;

        /**
         * The tree's own status at the instant the node was not there, or {@code null} when the
         * node WAS there and the status was therefore never asked for.
         * <p>
         * The status itself rather than a boolean, because the refusal QUOTES it: naming the state
         * the tree is in is the difference between "wait for it" and "it does not exist", and a
         * boolean threw that wording away one step before the only place it was needed.
         */
        private final ComparisonNodeStatus treeStatus;

        private Attempt(Located located, ComparisonNodeStatus treeStatus)
        {
            this.located = located;
            this.treeStatus = treeStatus;
        }

        /**
         * @param located the resolved ids
         * @return the answer; the tree status is not asked for, because the wait is over either way
         */
        static Attempt found(Located located)
        {
            return new Attempt(located, null);
        }

        /**
         * @param treeStatus the tree status as reported in the SAME boundary, never {@code null}
         * @return the answer
         */
        static Attempt missing(ComparisonNodeStatus treeStatus)
        {
            return new Attempt(null, treeStatus);
        }
    }

    // ==================== The facade adapter ====================

    /**
     * Runs one in-boundary task and then releases the per-read comparison context, whether the task
     * returned or threw.
     *
     * <p>Releasing is correct here, and the reasoning is byte code rather than habit: the
     * one-argument context factory builds a plain context and sets only its data-source context -
     * it never sets a comparison transaction. So closing that context closes the per-side
     * data-source readers and SKIPS its commit branch entirely, and cannot touch the transaction
     * the read boundary owns. The {@code (session, boolean)} factory is the different one: it
     * opens a transaction of its own AND {@code close()} commits it. Carrying the
     * try-with-resources reasoning over to the wrong factory is what left every expand call
     * stranding its data-source readers on a feature that already pins a virtual project.</p>
     *
     * @param <T> the task result
     * @param access the in-boundary lookups the task reads through
     * @param task the work to run
     * @param release releases the context of this read
     * @return whatever the task returns
     */
    static <T> T runThenRelease(TreeAccess access, ReadTask<T> task, Runnable release)
    {
        try
        {
            return task.run(access);
        }
        finally
        {
            release.run();
        }
    }

    /**
     * The one place in this file that touches the comparison facade.
     *
     * <p>It never receives an {@code IComparisonManager} or an {@code IComparisonSession} - only
     * {@link ComparisonEngine} and the {@link ComparisonView} it hands out. That is one of the
     * three independent layers that make a merge unreachable from a tool.</p>
     */
    static final class EngineNodeSource
        implements NodeSource
    {
        @Override
        public boolean isKnown(String comparisonId)
        {
            return ComparisonSessionRegistry.shared().handle(comparisonId) != null;
        }

        @Override
        public List<String> knownComparisonIds()
        {
            return ComparisonSessionRegistry.shared().ids();
        }

        @Override
        public PlatformAnswer<Boolean> edtHasActiveComparison()
        {
            ComparisonEngine engine = ComparisonEngine.get().orElse(null);
            // No facade means the bundle is not started or the service is not registered, which
            // is precisely "could not be asked" - not "no comparison is running".
            return engine == null ? PlatformAnswer.unavailable() : engine.hasActiveComparison();
        }

        @Override
        public void prioritize(String comparisonId, List<Long> nodeIds)
        {
            ComparisonEngine engine = ComparisonEngine.get().orElse(null);
            try (ComparisonSessionRegistry.Lease lease =
                ComparisonSessionRegistry.shared().lease(comparisonId))
            {
                // A prioritisation is a HINT and its absence costs the caller nothing but a
                // slower expansion, so the two answers are collapsed here on purpose: neither
                // one is reported and no verdict is drawn from the difference. The read below
                // is where the difference matters and where it is kept.
                ComparisonView view = viewOf(engine, lease).orElse(null);
                if (engine != null && view != null)
                {
                    engine.prioritize(view, nodeIds);
                }
            }
        }

        /**
         * {@inheritDoc}
         * <p>
         * <b>The ATTACHED facade, not {@link ComparisonEngine#get()}, and that is the fix for a
         * refusal that named the wrong fact.</b> {@code get()} answers empty while EDT's
         * comparison service is momentarily unregistered - the same gap the facade's own javadoc
         * describes - and this method used to turn that empty into a bare
         * {@code IllegalStateException}. It escaped BEFORE {@link #viewOf} could answer
         * {@link PlatformAnswer#unavailable()}, so the caller was told to check its id or start a
         * new comparison while the id was alive, the lease was open and its nodeIds still
         * resolved. Going through the attached facade lets the READ answer for itself: the
         * platform's three answers reach {@code ComparisonFailures.unreadableTree}, and a
         * momentary gap comes back as the retryable "could not be asked just now".
         */
        @Override
        public <T> T read(String comparisonId, ReadTask<T> task)
        {
            ComparisonEngine engine = ComparisonEngine.attached().orElse(null);
            // LEASED for the whole read. The registry's idle sweep measures idleness from the last
            // LOOKUP, and a node expansion is one lookup followed by an arbitrarily long BM read;
            // without the lease a comparison whose read outlasts the idle TTL would be ended
            // underneath the transaction walking it. The lease also carries the handle, so
            // liveness is asked once instead of twice with two answers that can disagree.
            try (ComparisonSessionRegistry.Lease lease =
                ComparisonSessionRegistry.shared().lease(comparisonId))
            {
                if (!lease.held())
                {
                    // THIS server no longer holds the comparison - released, or reclaimed by the
                    // idle sweep between isKnown() and this lease. Said as itself, because it is
                    // not a statement about EDT at all: nothing was asked of the platform, so
                    // neither "the service could not be asked" nor "EDT ended it outside this
                    // server" is a fact this branch is entitled to.
                    throw new ComparisonUnreadableException(ToolResult.error("Comparison '" //$NON-NLS-1$
                        + comparisonId + "' is no longer registered here - it was released, or " //$NON-NLS-1$
                        + "reclaimed after sitting idle. Start a new one with " //$NON-NLS-1$
                        + "compare_configurations."));  //$NON-NLS-1$
                }
                PlatformAnswer<ComparisonView> answer = viewOf(engine, lease);
                // Three answers and one decision, shared with the other tool that reads a tree:
                // "could not ask" is a fact about this server's reach and is RETRYABLE with the
                // lease still held and the nodeIds still resolving, while "EDT no longer knows
                // this handle" is a fact about the comparison. This site used to report the
                // second when the first happened.
                ToolResult refusal = ComparisonFailures.unreadableTree(answer, comparisonId);
                if (refusal != null)
                {
                    throw new ComparisonUnreadableException(refusal);
                }
                // Not null, and not by luck: unreadableTree above refuses every answer that does
                // not carry a view, and an absent facade is one of them.
                ComparisonView view = answer.orElse(null);
                // The comparison's OWN read boundary - the tree is in its private BM store.
                return engine.read(view, "Read comparison node", (transaction, monitor) -> { //$NON-NLS-1$
                    // The boundary's own transaction is NOT handed to the context: the factory
                    // that takes one puts it into the MAIN SIDE's slot and turns on the platform's
                    // merge mode. See ComparisonView.readContext().
                    ComparisonContext context = view.readContext();
                    return runThenRelease(new ViewTreeAccess(view, context), task, context::close);
                });
            }
        }

        /**
         * The read view for a leased comparison, as the platform's own answer.
         *
         * <p>The handle comes from the LEASE rather than from a second lookup: the registry is
         * reached through its own {@code shared()} entry point - {@code ComparisonEngine.get()}
         * also reports "unavailable" while EDT's service is momentarily unregistered, and
         * answering "no such comparison" during such a gap would name the wrong fact - and asking
         * the liveness question twice can produce two answers that disagree.</p>
         *
         * <p>It answers a {@link PlatformAnswer} and not a nullable view because the caller has
         * to tell the platform's two answers apart. An {@code orElse(null)} here folded "EDT's
         * comparison service could not be asked just now" into "EDT no longer knows this
         * comparison", and the caller then told somebody their comparison had been ended outside
         * this server while the lease on it was still open, its nodeIds still resolved and it
         * still held EDT's single slot.</p>
         *
         * <p>The two absences it can meet are kept apart for the same reason. NO HANDLE is an
         * answer THIS server gives - the lease holds nothing, so EDT was never asked - and it
         * says the comparison is gone. NO FACADE is not an answer about the comparison at all:
         * the bundle is starting or stopping, nothing was asked of anybody, and reporting it as a
         * comparison EDT has forgotten would send the caller to start a new one over a live
         * session. It is {@code unavailable()}, which is retryable.</p>
         */
        private static PlatformAnswer<ComparisonView> viewOf(ComparisonEngine engine,
            ComparisonSessionRegistry.Lease lease)
        {
            if (engine == null)
            {
                // Nothing was asked, and not because the comparison is gone: there is no facade to
                // ask with. A fact about this server's reach, exactly like a service that did not
                // answer.
                return PlatformAnswer.unavailable();
            }
            ComparisonProcessHandle handle = lease.handle();
            if (handle == null)
            {
                // An ANSWERED absence, and answered by THIS server rather than by EDT: the lease
                // holds nothing, so there is no handle to ask EDT about and no question was put
                // to it. Not "unavailable" - that would claim the platform was out of reach.
                return PlatformAnswer.of(null);
            }
            return engine.view(handle);
        }
    }

    /**
     * A refusal about the comparison itself, worded where the platform answered and published
     * verbatim by {@link #execute(Map)}.
     *
     * <h2>Why the port throws a worded refusal rather than a bare failure</h2>
     * "EDT could not be asked for this comparison" and "EDT no longer knows this comparison" are
     * two facts with opposite remedies - wait and read the same comparison again, or start a new
     * one - and the port is the only place that can still tell them apart, because only there is
     * the platform's answer still an answer rather than a {@code null}. Carrying the refusal out
     * ready-made is what stops the difference being lost on the way to the caller, and it also
     * keeps these two out of the generic failure branch, whose advice ("start a new one") is
     * wrong for the retryable one.
     */
    static final class ComparisonUnreadableException
        extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        private final String errorJson;

        /**
         * @param refusal the shared refusal for what the platform said
         */
        ComparisonUnreadableException(ToolResult refusal)
        {
            super(refusal.toJson());
            this.errorJson = getMessage();
        }

        /**
         * @return the refusal as the error JSON the caller receives
         */
        String errorJson()
        {
            return errorJson;
        }
    }

    /**
     * In-boundary lookups, delegating to the facade's read view.
     *
     * <p>The {@code ComparisonContext} is built from the transaction the read boundary handed us,
     * and it IS released when that read ends - see {@link GetComparisonNodeTool#runThenRelease},
     * which records why closing this particular context cannot reach the boundary's own
     * transaction.</p>
     *
     * <p>Package-visible so a test can build one over a scripted {@link ComparisonView}. It was
     * private, and a mutation measured what that cost: replacing
     * {@link #wholeConfigurationRun()}'s body with {@code return true} - which makes every scoped
     * comparison report that content was compared everywhere - left the whole suite green, because
     * nothing could reach this class to exercise it. The classification it feeds is covered
     * elsewhere; this is the link that carries the platform's answer INTO it.</p>
     */
    static final class ViewTreeAccess
        implements TreeAccess
    {
        private final ComparisonView view;
        private final ComparisonContext context;

        ViewTreeAccess(ComparisonView view, ComparisonContext context)
        {
            this.view = view;
            this.context = context;
        }

        @Override
        public ComparisonNode topNode(String symlink, ComparisonSide side)
        {
            return symlink == null ? null : view.topNode(symlink, side);
        }

        @Override
        public ComparisonNode node(long nodeId)
        {
            return view.node(context, nodeId);
        }

        @Override
        public ComparisonNodeStatus topNodeStatus(long topNodeId)
        {
            return view.topNodeStatus(topNodeId);
        }

        @Override
        public boolean wholeConfigurationRun()
        {
            // The session's OWN saved answer, computed once in its constructor - the same value
            // that decided mergeObjectsContent at launch. It used to ask view.inScope(node) as
            // well, to narrow the claim to this node; that predicate answers a DIFFERENT question
            // from the one the exclusion is decided by and was wrong in both directions, so the
            // node is no longer consulted at all. The reasoning is in
            // ComparisonNodeRenderer.ContentCoverage.
            return view.isGlobalScope();
        }

        @Override
        public ComparisonNodeStatus treeStatus()
        {
            ComparisonNode root = view.rootNode();
            return root == null ? null : view.topNodeStatus(root.bmGetId());
        }

        @Override
        public IComparedObjects<?> comparedObjects(ComparisonNode node)
        {
            return node == null ? null : view.comparedObjects(node, context);
        }

        @Override
        public List<PotentialMergeProblemDescription> potentialProblems(long nodeId)
        {
            if (!view.hasPotentialProblems(nodeId))
            {
                return Collections.emptyList();
            }
            List<PotentialMergeProblemDescription> problems =
                view.potentialProblems(nodeId, context);
            return problems == null ? Collections.emptyList() : problems;
        }
    }
}

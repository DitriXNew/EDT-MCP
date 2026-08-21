/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    /** Production constructor: resolves the engine lazily, so construction touches no EDT service. */
    public GetComparisonNodeTool()
    {
        this(new EngineNodeSource());
    }

    GetComparisonNodeTool(NodeSource source)
    {
        this.source = source;
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

        /** @return the ids of every live comparison, for a "did you mean" error */
        List<String> knownComparisonIds();

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
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;

        // First read: resolve the address to plain IDs. Nothing from the comparison's BM store is
        // allowed out of the boundary, so the node itself stays inside. Retried until the budget
        // runs out, because an address that resolves to nothing RIGHT AFTER a launch usually means
        // the engine has not built that node yet - and answering "no such object" to that is a
        // verdict about the caller's address that nothing observed supports.
        Located located = locateWithin(comparisonId, symlink, explicitNodeId, side, deadline);
        if (located == null)
        {
            return notLocatedError(comparisonId, objectFqn, symlink, explicitNodeId);
        }

        // Waited on, and the result deliberately NOT carried into the render: it is a reading taken
        // in a boundary that has since closed. See below.
        awaitNode(comparisonId, located.statusNodeId, deadline);

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
            ComparisonNodeRenderer.Request request = new ComparisonNodeRenderer.Request(comparisonId,
                address, side, access.topNodeStatus(located.statusNodeId), depth, limit, null);
            return ComparisonNodeRenderer.render(request, node, access);
        });
        if (markdown == null)
        {
            return unknownNodeError(comparisonId, located.nodeId);
        }
        return markdown;
    }

    /**
     * Resolves the caller's address, retrying until it resolves or the budget runs out.
     *
     * @param comparisonId the comparison id
     * @param symlink the canonical symlink, or {@code null} when addressing by node id
     * @param explicitNodeId the node id, or {@code null} when addressing by FQN
     * @param side the addressed side
     * @param deadline the wall-clock millisecond deadline shared with the node wait
     * @return the resolved ids, or {@code null} when nothing answered to the address in time
     * @throws InterruptedException when the wait is interrupted
     */
    private Located locateWithin(String comparisonId, String symlink, Long explicitNodeId,
        ComparisonSide side, long deadline) throws InterruptedException
    {
        Located located = source.read(comparisonId,
            access -> locate(access, symlink, explicitNodeId, side));
        while (located == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            located = source.read(comparisonId,
                access -> locate(access, symlink, explicitNodeId, side));
        }
        return located;
    }

    /**
     * Says WHY an address resolved to nothing, having first established which of the two reasons
     * it was.
     *
     * @param comparisonId the comparison id
     * @param objectFqn the caller's FQN, or {@code null}
     * @param symlink the canonical symlink that was looked up, or {@code null}
     * @param explicitNodeId the caller's node id, or {@code null}
     * @return the refusal
     */
    private String notLocatedError(String comparisonId, String objectFqn, String symlink,
        Long explicitNodeId)
    {
        ComparisonNodeStatus treeStatus = source.read(comparisonId, TreeAccess::treeStatus);
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
     * @param deadline the wall-clock millisecond deadline shared with the address resolution
     * @return the last status observed
     * @throws InterruptedException when the wait is interrupted
     */
    private ComparisonNodeStatus awaitNode(String comparisonId, long statusNodeId, long deadline)
        throws InterruptedException
    {
        ComparisonNodeStatus status = statusOf(comparisonId, statusNodeId);
        if (status == ComparisonNodeStatus.FINISHED)
        {
            return status;
        }
        source.prioritize(comparisonId, Collections.singletonList(Long.valueOf(statusNodeId)));
        while (status != ComparisonNodeStatus.FINISHED && System.currentTimeMillis() < deadline)
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

    private static Long parseNodeId(String raw)
    {
        // A node id is a BM object id copied verbatim out of the compare_configurations report, so
        // the only correct parse is an integral one. Going through double would accept float syntax
        // ("1e3" becomes 1000) and, past 2^53, round a real id to a NEIGHBOURING id that is itself
        // plausible - the tool would then expand a different node than the caller named instead of
        // refusing. BigDecimal.longValueExact() refuses both: anything with a fractional part or
        // beyond long range must be refused, not converted. Long.parseLong does exactly that:
        // it rejects "1e3", "0x1p3" and "12.5", and overflows past Long.MAX_VALUE into an exception
        // instead of a neighbouring value. BigDecimal would NOT do - it accepts scientific notation,
        // so "1e3" would still resolve to node 1000. The single trailing ".0" is stripped first,
        // because a client that renders a JSON number that way means the whole id it printed.
        String text = raw.trim();
        if (text.endsWith(".0")) //$NON-NLS-1$
        {
            text = text.substring(0, text.length() - 2);
        }
        try
        {
            return Long.valueOf(Long.parseLong(text));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
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

    private String unknownComparisonError(String comparisonId)
    {
        List<String> known = source.knownComparisonIds();
        String alive = known == null || known.isEmpty() ? "none is running right now" //$NON-NLS-1$
            : "live comparisons: " + String.join(", ", known); //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("Unknown comparison '" + comparisonId + "' (" + alive //$NON-NLS-1$ //$NON-NLS-2$
            + "). Start one with compare_configurations, or poll the one you started with " //$NON-NLS-1$
            + "get_job_status.").toJson(); //$NON-NLS-1$
    }

    private static String unknownObjectError(String comparisonId, String objectFqn, String symlink)
    {
        String canonicalNote = symlink != null && !symlink.equals(objectFqn)
            ? " (canonicalised to '" + symlink + "')" : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ToolResult.error("Comparison '" + comparisonId + "' has no node for objectFqn '" //$NON-NLS-1$ //$NON-NLS-2$
            + objectFqn + "'" + canonicalNote + ". The object may be outside the comparison scope, " //$NON-NLS-1$ //$NON-NLS-2$
            + "may not exist on that side, or the FQN may be misspelled. The compare_configurations " //$NON-NLS-1$
            + "report lists every node it compared, with its nodeId.").toJson(); //$NON-NLS-1$
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

    // ==================== The facade adapter ====================

    /**
     * Runs one in-boundary task and then releases the per-read comparison context, whether the task
     * returned or threw.
     *
     * <p>Releasing is correct here, and the reasoning is byte code rather than habit: the
     * {@code (session, transaction)} context factory builds a plain context and sets only its
     * data-source context - it never sets a comparison transaction. So closing that context closes
     * the per-side data-source readers and SKIPS its commit branch entirely, and cannot touch the
     * transaction the read boundary owns. The {@code (session, boolean)} factory is the different
     * one: it opens a transaction of its own, which is why the facade's write path wraps THAT form
     * in try-with-resources. Carrying the try-with-resources reasoning over to the wrong factory is
     * what left every expand call stranding its data-source readers on a feature that already pins
     * a virtual project.</p>
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
        public void prioritize(String comparisonId, List<Long> nodeIds)
        {
            ComparisonEngine engine = ComparisonEngine.get().orElse(null);
            ComparisonView view = viewOf(engine, comparisonId);
            if (engine != null && view != null)
            {
                engine.prioritize(view, nodeIds);
            }
        }

        @Override
        public <T> T read(String comparisonId, ReadTask<T> task)
        {
            ComparisonEngine engine = ComparisonEngine.get()
                .orElseThrow(() -> new IllegalStateException(
                    "EDT's comparison service is not available in this workbench")); //$NON-NLS-1$
            ComparisonView view = viewOf(engine, comparisonId);
            if (view == null)
            {
                throw new IllegalStateException(
                    "comparison '" + comparisonId + "' is no longer registered"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // The comparison's OWN read boundary - the tree is in its private BM store.
            return engine.read(view, "Read comparison node", (transaction, monitor) -> { //$NON-NLS-1$
                ComparisonContext context = view.contextFor(transaction);
                return runThenRelease(new ViewTreeAccess(view, context), task, context::close);
            });
        }

        /**
         * The read view for a live comparison, or {@code null}.
         *
         * <p>The registry is reached through its own {@code shared()} entry point rather than
         * through the engine: {@code ComparisonEngine.get()} also reports "unavailable" while
         * EDT's service is momentarily unregistered, and answering "no such comparison" during
         * such a gap would name the wrong fact - the session is alive, the service blinked.</p>
         */
        private static ComparisonView viewOf(ComparisonEngine engine, String comparisonId)
        {
            if (engine == null)
            {
                return null;
            }
            ComparisonProcessHandle handle = ComparisonSessionRegistry.shared().handle(comparisonId);
            return handle == null ? null : engine.view(handle);
        }
    }

    /**
     * In-boundary lookups, delegating to the facade's read view.
     *
     * <p>The {@code ComparisonContext} is built from the transaction the read boundary handed us,
     * and it IS released when that read ends - see {@link GetComparisonNodeTool#runThenRelease},
     * which records why closing this particular context cannot reach the boundary's own
     * transaction.</p>
     */
    private static final class ViewTreeAccess
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

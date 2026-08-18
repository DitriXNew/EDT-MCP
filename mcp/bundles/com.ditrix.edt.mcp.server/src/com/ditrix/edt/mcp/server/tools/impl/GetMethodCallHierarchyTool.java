/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CallGraphTraversal;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to find method call hierarchy - who calls this method (callers)
 * or what this method calls (callees).
 * <p>
 * BSL method calls are not stored as cross-references in the index, so callers are found the
 * way EDT itself does: text-prefilter the modules that mention the method name, then parse only
 * those and match each invocation to this exact method via its resolved AST feature entries
 * (with a call-qualifier fallback when the resolver has not populated them). Callees are
 * collected by walking the target method's own AST.
 * <p>
 * The aggregated {@code direction='outgoing'} mode is a clean-room implementation inspired by the
 * idea behind edt-bridge's {@code edt_outgoing_calls} tool (Apache-2.0); no source was copied.
 */
public class GetMethodCallHierarchyTool implements IMcpTool
{
    public static final String NAME = "get_method_call_hierarchy"; //$NON-NLS-1$

    /** Input param: name of the procedure/function to analyze. */
    private static final String KEY_METHOD_NAME = "methodName"; //$NON-NLS-1$

    /** Input param: hierarchy direction ('callers', 'callees' or 'outgoing'). */
    private static final String KEY_DIRECTION = "direction"; //$NON-NLS-1$

    /** Direction value: callers (who calls this method). */
    private static final String KEY_CALLERS = "callers"; //$NON-NLS-1$

    /** Direction value: aggregated outgoing calls (distinct call targets). */
    private static final String KEY_OUTGOING = "outgoing"; //$NON-NLS-1$

    /** Input param: literal call-qualifier prefix that flags a call as an external service API. */
    private static final String KEY_EXT_API_PREFIX = "extApiPrefix"; //$NON-NLS-1$

    /** Input param: how many call-chain levels to walk (1 = the single hop this tool always did). */
    static final String KEY_DEPTH = "depth"; //$NON-NLS-1$

    /**
     * Default {@link #KEY_DEPTH}: one hop. The recursive renderer is selected only when the
     * EFFECTIVE depth exceeds this, so a caller that omits {@code depth} - or passes a value that
     * clamps back to it - gets byte-for-byte the output this tool has always produced.
     */
    static final int DEFAULT_DEPTH = 1;

    /**
     * Hard ceiling on {@link #KEY_DEPTH}.
     *
     * <p>Five is what the impact question actually needs ("what breaks 3-5 levels up") and what the
     * cost model can pay for: one level is one pass over the project's sources, so the ceiling is
     * also the multiplier on the most expensive part of the call. Past it the caller set of a real
     * configuration approaches "everything that transitively touches this module", which the node
     * budget would truncate anyway - so a higher ceiling would advertise a reach that neither the
     * budget nor the transport can deliver.
     */
    static final int MAX_DEPTH = 5;

    /**
     * Wall-clock ceiling (ms) on a TRANSITIVE walk, applied only when the effective depth is above
     * {@link #DEFAULT_DEPTH} so the single-hop path keeps its existing (unbounded) behaviour.
     *
     * <p>Same reasoning, and the same number, as {@code RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS}: an
     * MCP client cuts a call at its own transport timeout - around 60 seconds for the clients this
     * server is driven by - so a walk allowed to run past that does not return a longer answer, it
     * returns nothing the caller ever sees. Stopping at 45 s yields a labelled partial answer, which
     * is strictly more information than a transport error.
     */
    static final long TRANSITIVE_TIME_BUDGET_MS = 45_000L;

    /**
     * How many candidate modules are parsed and walked inside ONE {@link Display#syncExec}.
     *
     * <p>The model work has to happen on the UI thread, but it does not have to happen in a single
     * runnable: holding that thread for the whole traversal would freeze the IDE for up to the
     * entire time budget. Chunking hands it back between batches. The value trades UI latency
     * against thread-hop overhead; parsing one module dwarfs one hop, so a small batch is safe.
     */
    private static final int UI_CHUNK_SIZE = 25;

    /**
     * Default {@link #KEY_EXT_API_PREFIX} value: the Cyrillic 1C region name that conventionally
     * marks a module's service programming interface ("ProgrammnyyInterfeysServisa"). Encoded via
     * {@code \\uXXXX} escapes per project rule #7 (never a raw UTF-8 literal in source).
     * Package-visible so the headless unit tests can assert the default without a UTF-8 literal.
     */
    static final String DEFAULT_EXT_API_PREFIX =
        "\u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u043d\u044b\u0439" //$NON-NLS-1$
        + "\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441" //$NON-NLS-1$
        + "\u0421\u0435\u0440\u0432\u0438\u0441\u0430"; //$NON-NLS-1$

    /** Qualifier token for an unqualified local call (methodAccess is a StaticFeatureAccess). */
    private static final String QUALIFIER_LOCAL = "(local)"; //$NON-NLS-1$

    /** Qualifier token for a chained/expression call whose source is not a StaticFeatureAccess. */
    private static final String QUALIFIER_EXPR = "(expr)"; //$NON-NLS-1$

    /**
     * Explanatory suffix appended to the module-load-failure error. Shared by every direction so
     * the message stays identical (deduplicated from three inline literals).
     */
    private static final String MODULE_LOAD_FAILURE_SUFFIX =
        ". Call hierarchy requires BSL AST (EMF). Check EDT Error Log for details."; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Trace which BSL methods call a method or are called by it; optional depth walks " //$NON-NLS-1$
            + "the chain transitively for impact analysis (callers only, max " + MAX_DEPTH //$NON-NLS-1$
            + "). Finds STATIC invocations only, so a complete result does not prove nothing else " //$NON-NLS-1$
            + "calls the method. Parameters and examples: get_tool_guide('get_method_call_hierarchy')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH,
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_METHOD_NAME,
                "Name of the procedure/function (case-insensitive). " //$NON-NLS-1$
                + "Required for direction 'callers'/'callees'; optional for 'outgoing' " //$NON-NLS-1$
                + "(omit to aggregate the whole module).", false) //$NON-NLS-1$
            .enumProperty(KEY_DIRECTION,
                "'callers' = who calls this method, 'callees' = what it calls, 'outgoing' = " //$NON-NLS-1$
                    + "aggregated distinct call targets (module-wide when methodName is omitted).", //$NON-NLS-1$
                KEY_CALLERS, "callees", KEY_OUTGOING) //$NON-NLS-1$
            .stringProperty(KEY_EXT_API_PREFIX,
                "For direction 'outgoing': literal call-qualifier prefix (case-insensitive) that " //$NON-NLS-1$
                + "flags a target as an external service API. Default: the 1C region name " //$NON-NLS-1$
                + "'\u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u043d\u044b\u0439" //$NON-NLS-1$
                + "\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441" //$NON-NLS-1$
                + "\u0421\u0435\u0440\u0432\u0438\u0441\u0430'.") //$NON-NLS-1$
            .integerProperty(KEY_DEPTH,
                "Call-chain levels to walk, for direction 'callers' ONLY. Default 1 (single hop, " //$NON-NLS-1$
                + "unchanged output); max " + MAX_DEPTH + " (a larger value is clamped, a smaller " //$NON-NLS-1$ //$NON-NLS-2$
                + "than 1 becomes 1). Above 1 the result changes shape: one row per UNIQUE caller " //$NON-NLS-1$
                + "with its Level and the row it was reached Via, and 'limit' then caps the callers " //$NON-NLS-1$
                + "accepted and stops the walk there, so it cannot report a true total.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Max results. Default: 100, max: 500. With depth>1 this is the cap on unique " //$NON-NLS-1$
                + "callers accepted, i.e. an execution budget, not just an output limit.") //$NON-NLS-1$
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
        // Normalize the same way execute() does so a padded value like " outgoing " yields the
        // outgoing file name (not the generic fallback) and no whitespace leaks into the name.
        String methodName = normalizeArg(JsonUtils.extractStringArgument(params, KEY_METHOD_NAME));
        String direction = normalizeArg(JsonUtils.extractStringArgument(params, KEY_DIRECTION));
        if (methodName != null && !methodName.isEmpty())
        {
            return "call-hierarchy-" + methodName.toLowerCase() + //$NON-NLS-1$
                   "-" + (direction != null ? direction : KEY_CALLERS) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Module-wide outgoing scope has no method name; keep a distinct, descriptive file name.
        if (direction != null && KEY_OUTGOING.equalsIgnoreCase(direction))
        {
            return "call-hierarchy-outgoing.md"; //$NON-NLS-1$
        }
        return "call-hierarchy.md"; //$NON-NLS-1$
    }

    /**
     * Trims a raw input argument and folds a blank result to {@code null} so a whitespace-only value
     * is treated as absent. Shared by {@link #execute(Map)} and {@link #getResultFileName(Map)} so
     * both see the same normalized {@code direction}/{@code methodName}. No-op for already-clean,
     * non-null values.
     *
     * @param s the raw argument (may be {@code null})
     * @return the trimmed value, or {@code null} when {@code s} is {@code null} or blank
     */
    private static String normalizeArg(String s)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Builds the identical "could not load the BSL AST for this module" error JSON shared by every
     * direction. Names the failing module path and points at the EDT Error Log.
     *
     * @param modulePath the source-relative module path that failed to load
     * @return the {@link ToolResult#error} JSON string
     */
    private static String moduleLoadFailure(String modulePath)
    {
        return ToolResult.error("Could not load EMF model for " + modulePath //$NON-NLS-1$
            + MODULE_LOAD_FAILURE_SUFFIX).toJson();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        // Trim these three so a whitespace-only value is treated as absent (defaults to callers /
        // whole-module scope / default prefix) and a padded value like " outgoing " still routes.
        String methodName = normalizeArg(JsonUtils.extractStringArgument(params, KEY_METHOD_NAME));
        String direction = normalizeArg(JsonUtils.extractStringArgument(params, KEY_DIRECTION));
        String extApiPrefix = normalizeArg(JsonUtils.extractStringArgument(params, KEY_EXT_API_PREFIX));
        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, 100);
        int depth = JsonUtils.extractIntArgument(params, KEY_DEPTH, DEFAULT_DEPTH);

        // methodName is optional (only required for callers/callees); require it manually below
        // after we have parsed and validated direction, so the guard can honour 'outgoing'.
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.MODULE_PATH);
        if (err != null)
        {
            return err;
        }

        // Normalize + validate direction/methodName/extApiPrefix/depth; a non-null result is an error JSON.
        RequestArgs request = new RequestArgs(direction, methodName, extApiPrefix, limit, depth);
        String validationError = validateRequest(request);
        if (validationError != null)
        {
            return validationError;
        }

        // A transitive walk must NOT run as one giant syncExec: it drives its own UI-thread slices
        // so the IDE stays responsive (see runTransitiveCallers). The single-hop path is untouched.
        if (request.depth > DEFAULT_DEPTH)
        {
            return runTransitiveCallers(projectName, modulePath, request);
        }

        return runOnDisplay(projectName, modulePath, request);
    }

    /**
     * Normalizes {@code direction} (blank → callers, lower-cased) and validates it, applies the
     * callers/callees methodName guard, defaults a blank {@code extApiPrefix} and clamps the limit.
     * On success the normalized values are written back into {@code request}; on failure the ready
     * error JSON is returned and {@code request} is left partially normalized (unused by the caller).
     *
     * @param request the mutable request holder to normalize in place
     * @return {@code null} when the arguments are valid, otherwise the {@link ToolResult#error} JSON
     */
    private String validateRequest(RequestArgs request)
    {
        String direction = request.direction;
        if (direction == null || direction.isEmpty())
        {
            direction = KEY_CALLERS;
        }
        direction = direction.toLowerCase();
        request.direction = direction;

        if (!KEY_CALLERS.equals(direction) && !"callees".equals(direction) //$NON-NLS-1$
            && !KEY_OUTGOING.equals(direction))
        {
            return ToolResult.error("direction must be 'callers', 'callees' or 'outgoing'").toJson(); //$NON-NLS-1$
        }

        if ((request.methodName == null || request.methodName.trim().isEmpty())
            && !KEY_OUTGOING.equals(direction))
        {
            return ToolResult.error("methodName is required for callers/callees" //$NON-NLS-1$
                + ". Use get_module_structure to list the module's procedures and functions.").toJson(); //$NON-NLS-1$
        }

        if (request.extApiPrefix == null || request.extApiPrefix.isEmpty())
        {
            request.extApiPrefix = DEFAULT_EXT_API_PREFIX;
        }

        request.limit = Pagination.clampLimit(request.limit, 500);

        // Clamp first, then judge: a depth that clamps back to 1 asks for the single hop, which every
        // direction supports. Only an EFFECTIVE depth above 1 is a transitive request, and that is
        // callers-only (see the guide: the callees path does not resolve a call to its defining
        // module at all, so recursing it would invent a dependency graph rather than report one).
        request.depth = Math.min(Math.max(DEFAULT_DEPTH, request.depth), MAX_DEPTH);
        if (request.depth > DEFAULT_DEPTH && !KEY_CALLERS.equals(direction))
        {
            return ToolResult.error(KEY_DEPTH + " above " + DEFAULT_DEPTH //$NON-NLS-1$
                + " is supported for direction '" + KEY_CALLERS + "' only, not '" + direction //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Reason: '" + direction + "' reports the raw invocation names it finds and does" //$NON-NLS-1$ //$NON-NLS-2$
                + " not resolve them to the modules that define them, so there is nothing sound to" //$NON-NLS-1$
                + " recurse into. Use direction '" + KEY_CALLERS + "' for transitive impact analysis," //$NON-NLS-1$ //$NON-NLS-2$
                + " or drop " + KEY_DEPTH + " for a single hop.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Runs the direction dispatch on the UI thread and returns its rendered result. All BSL model
     * access happens inside {@link Display#syncExec} because it touches the shared EMF model.
     *
     * @param projectName the EDT project name (already validated as present)
     * @param modulePath the source-relative module path (already validated as present)
     * @param request the normalized/validated request arguments
     * @return the rendered Markdown, or a {@link ToolResult#error} JSON string on failure
     */
    private String runOnDisplay(String projectName, String modulePath, RequestArgs request)
    {
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(dispatch(projectName, modulePath, request));
            }
            catch (Exception e)
            {
                Activator.logError("Error finding call hierarchy", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        return resultRef.get();
    }

    /**
     * Routes a normalized request to the matching finder (outgoing / callers / callees). Must be
     * called on the UI thread (see {@link #runOnDisplay}).
     *
     * @param projectName the EDT project name
     * @param modulePath the source-relative module path
     * @param request the normalized/validated request arguments
     * @return the rendered result of the selected finder
     */
    private String dispatch(String projectName, String modulePath, RequestArgs request)
    {
        String dir = request.direction;
        if (KEY_OUTGOING.equals(dir))
        {
            return findOutgoing(projectName, modulePath, request.methodName,
                request.extApiPrefix, request.limit);
        }
        if (KEY_CALLERS.equals(dir))
        {
            return findCallers(projectName, modulePath, request.methodName, request.limit);
        }
        return findCallees(projectName, modulePath, request.methodName, request.limit);
    }

    /**
     * Mutable holder for the normalized/validated request arguments, threaded from
     * {@link #execute(Map)} through {@link #validateRequest} and {@link #dispatch}. Bundling them
     * keeps the individual method signatures small without changing any value.
     */
    private static final class RequestArgs
    {
        String direction;
        final String methodName;
        String extApiPrefix;
        int limit;

        /** The effective depth after clamping into {@code [DEFAULT_DEPTH, MAX_DEPTH]}. */
        int depth;

        /** The depth exactly as asked for, kept so the header can admit that it was clamped. */
        final int requestedDepth;

        RequestArgs(String direction, String methodName, String extApiPrefix, int limit, int depth)
        {
            this.direction = direction;
            this.methodName = methodName;
            this.extApiPrefix = extApiPrefix;
            this.limit = limit;
            this.depth = depth;
            this.requestedDepth = depth;
        }
    }

    /**
     * Finds all callers of the specified method.
     * <p>
     * BSL method invocations are linked by name through scoping and are not stored as ordinary
     * cross-references in the index, so the generic Xtext reference finder cannot see them. We
     * mirror EDT's own strategy: text-prefilter the .bsl modules whose source mentions the method
     * name, parse only those, and match each invocation to this exact method by its resolved
     * feature entry (falling back to the call qualifier when the resolver left entries empty).
     */
    private String findCallers(String projectName, String modulePath, String methodName, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return moduleLoadFailure(modulePath);
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        final URI methodUri = EcoreUtil.getURI(method);
        final ResourceSet resourceSet = method.eResource().getResourceSet();
        if (resourceSet == null)
        {
            return ToolResult.error("BSL resource set not available").toJson(); //$NON-NLS-1$
        }
        final String targetModuleName = extractModuleName(modulePath);

        // Cheap text prefilter: collect .bsl files whose source mentions the method name.
        List<IFile> candidates = collectCandidateModules(project, methodName);

        List<CallerInfo> callers = new ArrayList<>();
        int totalReferences = 0;

        // Loop-invariant identity of the target method (same across every candidate).
        CallerSearch search = new CallerSearch(methodUri, methodName, targetModuleName, limit);

        for (IFile candidate : candidates) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            String relToSrc = candidate.getProjectRelativePath().removeFirstSegments(1).toString();
            Module candidateModule;
            try
            {
                URI candidateUri =
                    URI.createPlatformResourceURI(projectName + "/src/" + relToSrc, true); //$NON-NLS-1$
                Resource res = resourceSet.getResource(candidateUri, true);
                if (res == null || res.getContents().isEmpty() || !(res.getContents().get(0) instanceof Module))
                {
                    continue;
                }
                candidateModule = (Module)res.getContents().get(0);
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to load candidate module " + relToSrc //$NON-NLS-1$
                    + ": " + e.getMessage()); //$NON-NLS-1$
                continue;
            }

            boolean candidateIsTarget = relToSrc.equalsIgnoreCase(modulePath);
            totalReferences += scanCandidateInvocations(candidateModule, search,
                candidateIsTarget, relToSrc, callers);
        }

        return formatCallersOutput(modulePath, methodName, callers, totalReferences);
    }

    /**
     * Scans a single candidate module for invocations that resolve to the target method, appending a
     * {@link CallerInfo} for each match (up to {@code limit} total across all candidates) into the
     * shared {@code callers} list. Extracted verbatim from
     * {@link #findCallers(String, String, String, int)} to keep that method's complexity in check; the
     * loop-local {@code continue} statements stay confined to this scan.
     *
     * @param candidateModule the module to scan
     * @param search the loop-invariant target-method identity (URI, name, declaring module, limit)
     * @param candidateIsTarget {@code true} when this candidate is the module declaring the method
     * @param relToSrc the candidate's source-relative path, used when building a {@link CallerInfo}
     * @param callers the shared accumulator of matched callers (appended to, never reassigned)
     * @return the number of invocations in this candidate that target the method
     */
    private int scanCandidateInvocations(Module candidateModule, CallerSearch search,
        boolean candidateIsTarget, String relToSrc, List<CallerInfo> callers)
    {
        int matched = 0;
        for (Iterator<EObject> iter = candidateModule.eAllContents(); iter.hasNext();) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            EObject obj = iter.next();
            if (!(obj instanceof Invocation))
            {
                continue;
            }
            Invocation inv = (Invocation)obj;
            if (!invocationTargetsMethod(inv, search.methodUri, search.methodName,
                search.targetModuleName, candidateIsTarget))
            {
                continue;
            }
            matched++;
            if (callers.size() < search.limit)
            {
                callers.add(buildCallerInfo(inv, relToSrc, search.methodName));
            }
        }
        return matched;
    }

    /**
     * Immutable holder for the loop-invariant identity of the target method shared by every
     * candidate scan in {@link #findCallers}: the method URI, its name, the simple name of the
     * declaring module and the caller {@code limit}. Bundles the parameters without changing any
     * value.
     */
    private static final class CallerSearch
    {
        final URI methodUri;
        final String methodName;
        final String targetModuleName;
        final int limit;

        CallerSearch(URI methodUri, String methodName, String targetModuleName, int limit)
        {
            this.methodUri = methodUri;
            this.methodName = methodName;
            this.targetModuleName = targetModuleName;
            this.limit = limit;
        }
    }

    /**
     * Collects .bsl files under {@code <project>/src} whose source text contains the method name
     * (case-insensitive). This is the lightweight prefilter that keeps the AST pass small.
     */
    private List<IFile> collectCandidateModules(IProject project, String methodName)
    {
        List<IFile> candidates = new ArrayList<>();
        IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        if (!srcFolder.exists())
        {
            return candidates;
        }
        final String lowerName = methodName.toLowerCase();
        try
        {
            srcFolder.accept(res -> {
                if (res.getType() == IResource.FILE
                    && "bsl".equalsIgnoreCase(((IFile)res).getFileExtension())) //$NON-NLS-1$
                {
                    IFile file = (IFile)res;
                    String text = readCandidateText(file);
                    if (text != null && text.toLowerCase().contains(lowerName))
                    {
                        candidates.add(file);
                    }
                }
                return true;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning project for caller candidates", e); //$NON-NLS-1$
        }
        return candidates;
    }

    /**
     * Fast read of a BSL file's text for the prefilter (filesystem first, workspace API fallback).
     */
    private String readCandidateText(IFile file)
    {
        try
        {
            if (file.getLocation() != null)
            {
                java.io.File osFile = file.getLocation().toFile();
                if (osFile.isFile())
                {
                    return new String(java.nio.file.Files.readAllBytes(osFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return BslModuleUtils.readFileText(file);
        }
        catch (Exception e)
        {
            // An unreadable file silently leaves the candidate set, which is indistinguishable from
            // "this file mentions nothing". Log it so the failure is at least recoverable from the
            // Error Log; the transitive path additionally COUNTS it and refuses to call its result
            // complete (the single-hop path keeps its existing output byte-for-byte).
            Activator.logWarning("Could not read BSL candidate " //$NON-NLS-1$
                + file.getFullPath() + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * True when this invocation calls the target method. Prefers the semantically resolved feature
     * entry (exact match by URI); when the resolver left entries empty, falls back to matching the
     * call qualifier (Module.Method) or an unqualified call inside the target module itself.
     */
    private boolean invocationTargetsMethod(Invocation inv, URI methodUri, String methodName,
        String targetModuleName, boolean candidateIsTarget)
    {
        EObject methodAccess = inv.getMethodAccess();
        String callName;
        EList<FeatureEntry> entries = null;
        if (methodAccess instanceof StaticFeatureAccess)
        {
            callName = ((StaticFeatureAccess)methodAccess).getName();
            entries = ((StaticFeatureAccess)methodAccess).getFeatureEntries();
        }
        else if (methodAccess instanceof DynamicFeatureAccess)
        {
            DynamicFeatureAccess dfa = (DynamicFeatureAccess)methodAccess;
            callName = dfa.getName();
            if (dfa.isSetFeatureEntries())
            {
                entries = dfa.getFeatureEntries();
            }
        }
        else
        {
            return false;
        }

        if (callName == null || !callName.equalsIgnoreCase(methodName))
        {
            return false;
        }

        // Preferred: the resolver linked this access to one or more concrete features.
        if (entries != null && !entries.isEmpty())
        {
            return matchesResolvedFeature(entries, methodUri);
        }

        // Fallback: feature entries were not populated — match by call shape.
        if (methodAccess instanceof DynamicFeatureAccess)
        {
            Expression source = ((DynamicFeatureAccess)methodAccess).getSource();
            return targetModuleName != null && source instanceof StaticFeatureAccess
                && targetModuleName.equalsIgnoreCase(((StaticFeatureAccess)source).getName());
        }
        // Unqualified call: only counts as a caller inside the target module itself.
        return candidateIsTarget;
    }

    /**
     * True when any resolved feature entry points at the target method (exact match by URI).
     *
     * @param entries the non-empty list of resolved feature entries
     * @param methodUri the URI of the target method
     * @return true if at least one entry resolves to the target method
     */
    private boolean matchesResolvedFeature(EList<FeatureEntry> entries, URI methodUri)
    {
        for (FeatureEntry entry : entries)
        {
            EObject feature = entry.getFeature();
            if (feature != null && methodUri.equals(EcoreUtil.getURI(feature)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a {@link CallerInfo} from a matched invocation (module path, containing method, line,
     * and a compacted call snippet).
     */
    private CallerInfo buildCallerInfo(Invocation inv, String modulePath, String methodName)
    {
        CallerInfo caller = new CallerInfo();
        caller.modulePath = modulePath;
        caller.line = BslModuleUtils.getStartLine(inv);

        EObject container = inv.eContainer();
        while (container != null && !(container instanceof Method))
        {
            container = container.eContainer();
        }
        if (container instanceof Method)
        {
            caller.callerMethodName = ((Method)container).getName();
        }

        INode node = NodeModelUtils.findActualNodeFor(inv);
        if (node != null)
        {
            String text = node.getText();
            if (text != null)
            {
                text = stripCommentLines(text);
                if (text.length() > 100)
                {
                    text = smartTruncateCall(text, methodName);
                }
                caller.callCode = text;
            }
        }
        return caller;
    }

    /**
     * Extracts the metadata object name that qualifies calls to a module, e.g.
     * {@code "CommonModules/AccountingClientServer/Module.bsl"} → {@code "AccountingClientServer"}.
     */
    static String extractModuleName(String modulePath)
    {
        if (modulePath == null)
        {
            return null;
        }
        String[] parts = modulePath.split("/"); //$NON-NLS-1$
        return parts.length >= 2 ? parts[parts.length - 2] : null;
    }

    /**
     * Classifies an invocation's method access into the qualifier token used to aggregate outgoing
     * calls. Pinned semantics (guarded against NPE / ClassCastException):
     * <ul>
     * <li>an unqualified local call ({@code methodAccess} is a {@link StaticFeatureAccess}) →
     * {@code "(local)"};</li>
     * <li>a {@link DynamicFeatureAccess} whose {@code getSource()} is a {@link StaticFeatureAccess}
     * → that source's name (the qualifying object, e.g. {@code CommonModule});</li>
     * <li>a {@link DynamicFeatureAccess} whose source is a chained/other expression → {@code "(expr)"}.
     * </li>
     * </ul>
     * A {@code null} or unrecognized access yields {@code "(expr)"}.
     *
     * @param methodAccess the invocation's method access node (may be {@code null})
     * @return the qualifier token, never {@code null}
     */
    static String qualifierKey(EObject methodAccess)
    {
        if (methodAccess instanceof StaticFeatureAccess)
        {
            return QUALIFIER_LOCAL;
        }
        if (methodAccess instanceof DynamicFeatureAccess)
        {
            Expression source = ((DynamicFeatureAccess)methodAccess).getSource();
            if (source instanceof StaticFeatureAccess)
            {
                String name = ((StaticFeatureAccess)source).getName();
                return name != null ? name : QUALIFIER_EXPR;
            }
            return QUALIFIER_EXPR;
        }
        return QUALIFIER_EXPR;
    }

    /**
     * True when a call qualifier flags an external service API, i.e. the resolved qualifier token
     * starts (case-insensitively) with {@code prefix}. This is a literal text match on the call
     * qualifier itself, not a resolved-module lookup. The synthetic tokens {@code "(local)"} and
     * {@code "(expr)"} never match (they cannot start with a real region name).
     *
     * @param qualifier the resolved qualifier token (from {@link #qualifierKey(EObject)})
     * @param prefix the external-API prefix to test against
     * @return true when the qualifier begins with the prefix (case-insensitive)
     */
    static boolean isExtApi(String qualifier, String prefix)
    {
        if (qualifier == null || prefix == null || prefix.isEmpty())
        {
            return false;
        }
        if (QUALIFIER_LOCAL.equals(qualifier) || QUALIFIER_EXPR.equals(qualifier))
        {
            return false;
        }
        // Allocation-free, locale-independent case-insensitive prefix test. regionMatches returns
        // false when qualifier is shorter than prefix, matching the old startsWith semantics.
        return qualifier.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * The case-insensitive aggregation key for an outgoing target. BSL identifiers are
     * case-insensitive, so {@code Module.Method} and {@code module.method} fold to the same target
     * (the first-seen spelling is kept for display). Package-visible for headless unit tests.
     *
     * @param qualifier the qualifier token
     * @param method the called method name
     * @return the lower-cased {@code qualifier.method} key (Locale.ROOT)
     */
    static String aggregationKey(String qualifier, String method)
    {
        return (qualifier + "." + method).toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
    }

    /**
     * Finds all callees from the specified method by traversing its AST.
     */
    private String findCallees(String projectName, String modulePath, String methodName, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return moduleLoadFailure(modulePath);
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        // Traverse AST of this method to find invocations
        List<CalleeInfo> callees = new ArrayList<>();
        int totalInvocations = 0;

        Iterator<EObject> iter = method.eAllContents();
        while (iter.hasNext())
        {
            EObject obj = iter.next();

            String calledName = resolveInvocationName(obj);

            if (calledName != null && !calledName.isEmpty())
            {
                totalInvocations++;

                if (callees.size() < limit)
                {
                    callees.add(buildCalleeInfo(obj, calledName));
                }
            }
        }

        return formatCalleesOutput(modulePath, methodName, callees, totalInvocations);
    }

    /**
     * Returns the called method name for an AST node when it is an {@link Invocation} whose
     * method access is a static or dynamic feature access; otherwise {@code null}. Extracted from
     * {@link #findCallees(String, String, String, int)} to keep that loop's complexity in check.
     *
     * @param obj the AST node to inspect
     * @return the invoked method name, or {@code null} when the node is not a recognized invocation
     */
    private String resolveInvocationName(EObject obj)
    {
        if (!(obj instanceof Invocation))
        {
            return null;
        }
        EObject methodAccess = ((Invocation) obj).getMethodAccess();
        if (methodAccess instanceof StaticFeatureAccess)
        {
            return ((StaticFeatureAccess) methodAccess).getName();
        }
        if (methodAccess instanceof DynamicFeatureAccess)
        {
            return ((DynamicFeatureAccess) methodAccess).getName();
        }
        return null;
    }

    /**
     * Builds a {@link CalleeInfo} for a matched invocation node: records the called method name and
     * line, then attaches a compacted call snippet from the node's source text. Extracted from
     * {@link #findCallees(String, String, String, int)}.
     *
     * @param obj the invocation AST node
     * @param calledName the resolved called method name
     * @return the populated callee info
     */
    private CalleeInfo buildCalleeInfo(EObject obj, String calledName)
    {
        CalleeInfo callee = new CalleeInfo();
        callee.calledMethodName = calledName;
        callee.line = BslModuleUtils.getStartLine(obj);

        // Get source text around the invocation
        INode node = NodeModelUtils.findActualNodeFor(obj);
        if (node != null)
        {
            String text = node.getText();
            if (text != null)
            {
                text = stripCommentLines(text);
                if (text.length() > 100)
                {
                    text = smartTruncateCall(text, calledName);
                }
                callee.callCode = text;
            }
        }
        return callee;
    }

    // ========== Transitive callers (depth > 1) ==========

    /**
     * Walks the caller chain transitively and renders it.
     * <p>
     * Unlike every single-hop direction, this does NOT run inside one {@link Display#syncExec}. The
     * expensive half of a caller search is reading the project's sources, which touches no model at
     * all, so it runs on the calling thread; only the parse-and-match half is handed to the UI
     * thread, and even that in chunks. Holding the UI thread for a whole multi-level walk would
     * freeze the IDE for up to the entire time budget, and a clock check cannot preempt a runnable
     * that has already started.
     *
     * @param projectName the EDT project name
     * @param modulePath the source-relative path of the module DECLARING the method
     * @param request the normalized request (depth already clamped and known to be above 1)
     * @return the rendered Markdown, or a {@link ToolResult#error} JSON string on failure
     */
    private String runTransitiveCallers(String projectName, String modulePath, RequestArgs request)
    {
        try
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }

            Display display = PlatformUI.getWorkbench().getDisplay();
            RootResolution root = new RootResolution();
            display.syncExec(() -> resolveRoot(ctx.project(), modulePath, request.methodName, root));
            if (root.error != null)
            {
                return root.error;
            }

            TransitiveContext context =
                new TransitiveContext(ctx.project(), projectName, root.resourceSet, display);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TRANSITIVE_TIME_BUDGET_MS);
            BooleanSupplier expired = () -> System.nanoTime() - deadline >= 0;

            CallerNode rootPayload = new CallerNode(modulePath, root.methodName, root.methodUri, 0);
            CallGraphTraversal.Node rootNode =
                new CallGraphTraversal.Node(root.methodUri.toString(), rootPayload, true);

            // The remaining budget is deliberately not used to stop a level early: a level is one
            // pass over the sources, and cutting it mid-pass would make WHICH callers survive depend
            // on file traversal order. The level is collected whole and the engine trims it in a
            // deterministic order instead.
            CallGraphTraversal.Result result = CallGraphTraversal.traverse(rootNode, request.depth,
                request.limit, expired,
                (frontier, remainingBudget, stillRunning) -> expandCallers(context, frontier, stillRunning));

            return formatTransitiveCallersOutput(modulePath, root.methodName, request, result);
        }
        catch (Exception e)
        {
            Activator.logError("Error finding transitive call hierarchy", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Resolves the method being asked about, on the UI thread. Writes either an error JSON or the
     * root identity into {@code out}.
     *
     * @param project the EDT project
     * @param modulePath the source-relative module path
     * @param methodName the requested method name (case-insensitive)
     * @param out the holder to populate
     */
    private void resolveRoot(IProject project, String modulePath, String methodName, RootResolution out)
    {
        try
        {
            Module module = BslModuleUtils.loadModule(project, modulePath);
            if (module == null)
            {
                out.error = moduleLoadFailure(modulePath);
                return;
            }
            Method method = BslModuleUtils.findMethod(module, methodName);
            if (method == null)
            {
                out.error = BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
                return;
            }
            if (method.eResource() == null || method.eResource().getResourceSet() == null)
            {
                out.error = ToolResult.error("BSL resource set not available").toJson(); //$NON-NLS-1$
                return;
            }
            out.methodUri = EcoreUtil.getURI(method);
            // The DECLARED spelling, not the caller's: BSL names are case-insensitive, and the
            // heading should show the method as its module writes it.
            out.methodName = method.getName() != null ? method.getName() : methodName;
            out.resourceSet = method.eResource().getResourceSet();
        }
        catch (Exception e)
        {
            Activator.logError("Error resolving call-hierarchy root", e); //$NON-NLS-1$
            out.error = ToolResult.error(e.getMessage()).toJson();
        }
    }

    /** Holder for the UI-thread resolution of the method the walk starts from. */
    private static final class RootResolution
    {
        String error;
        URI methodUri;
        String methodName;
        ResourceSet resourceSet;
    }

    /** Everything a level expansion needs that does not change between levels. */
    private static final class TransitiveContext
    {
        final IProject project;
        final String projectName;
        final ResourceSet resourceSet;
        final Display display;

        /**
         * Paths already counted in each diagnostic category. The walk re-reads and re-scans the
         * same files at every level, so counting per ATTEMPT would report "3 unreadable files" for
         * one unreadable file in a three-level walk - a number about our own loop rather than about
         * the project. Each category counts distinct paths.
         */
        final Set<String> unreadable = new HashSet<>();

        /** Paths already counted as unloadable; see {@link #unreadable}. */
        final Set<String> unloadable = new HashSet<>();

        /** Paths already counted as unverified; see {@link #unreadable}. */
        final Set<String> unverified = new HashSet<>();

        TransitiveContext(IProject project, String projectName, ResourceSet resourceSet, Display display)
        {
            this.project = project;
            this.projectName = projectName;
            this.resourceSet = resourceSet;
            this.display = display;
        }
    }

    /**
     * One node of the transitive result: a method (or a module's top-level code) that calls into the
     * level below it.
     */
    private static final class CallerNode
    {
        final String modulePath;

        /** {@code null} when the call sits in module-level code, which no method name addresses. */
        final String methodName;

        /** {@code null} for module-level code; otherwise the exact identity used when matching. */
        final URI methodUri;

        final int line;

        CallerNode(String modulePath, String methodName, URI methodUri, int line)
        {
            this.modulePath = modulePath;
            this.methodName = methodName;
            this.methodUri = methodUri;
            this.line = line;
        }
    }

    /**
     * The full identity of one method being searched for in a level. Batching must carry ALL of it,
     * not just the URI: {@link #invocationTargetsMethod} decides with the declaring module's simple
     * name and with whether the module being scanned is the declaring one, and both are per-target.
     * Sharing either across a same-name batch is exactly how a batched search stops being equivalent
     * to the single-hop searches it replaces.
     */
    private static final class Target
    {
        final URI methodUri;
        final String methodName;
        final String modulePath;
        final String moduleSimpleName;
        final CallGraphTraversal.Node node;

        Target(CallerNode payload, CallGraphTraversal.Node node)
        {
            this.methodUri = payload.methodUri;
            this.methodName = payload.methodName;
            this.modulePath = payload.modulePath;
            this.moduleSimpleName = extractModuleName(payload.modulePath);
            this.node = node;
        }
    }

    /** A caller found during one level, before it becomes a traversal edge. */
    private static final class Discovered
    {
        final CallGraphTraversal.Node parent;
        final String key;
        final String modulePath;
        final String methodName;
        final URI methodUri;
        int line;

        Discovered(CallGraphTraversal.Node parent, String key, String modulePath, String methodName,
            URI methodUri, int line)
        {
            this.parent = parent;
            this.key = key;
            this.modulePath = modulePath;
            this.methodName = methodName;
            this.methodUri = methodUri;
            this.line = line;
        }
    }

    /**
     * Expands one BFS level: finds every caller - a method, or a module body - that calls any
     * method in {@code frontier}.
     * <p>
     * One level is ONE pass over the project's sources testing every frontier name at once - which
     * is the whole point of doing this server-side. A per-node walk would re-read the project once
     * per discovered method; this re-reads it once per LEVEL.
     *
     * @param ctx the per-request context
     * @param frontier the methods whose callers are wanted
     * @param expired the cooperative time signal
     * @return the level's edges and everything that could not be searched
     */
    private CallGraphTraversal.Expansion expandCallers(TransitiveContext ctx,
        List<CallGraphTraversal.Node> frontier, BooleanSupplier expired)
    {
        CallGraphTraversal.Diagnostics diag = new CallGraphTraversal.Diagnostics();

        Map<String, List<Target>> byName = new HashMap<>();
        for (CallGraphTraversal.Node node : frontier)
        {
            CallerNode payload = (CallerNode)node.getPayload();
            if (payload == null || payload.methodName == null || payload.methodUri == null)
            {
                continue;
            }
            byName.computeIfAbsent(foldCase(payload.methodName), k -> new ArrayList<>())
                .add(new Target(payload, node));
        }
        if (byName.isEmpty())
        {
            return new CallGraphTraversal.Expansion(Collections.emptyList(), diag, false);
        }

        CandidateScan scan =
            collectCandidatesMentioningAny(ctx.project, byName.keySet(), diag, expired, ctx.unreadable);
        List<IFile> candidates = scan.files;

        // Keyed by the (caller, target) PAIR in first-seen order: repeated call sites from one
        // caller to one target collapse into a single edge (keeping the earliest line) instead of
        // looking like graph re-convergence, while a caller that calls two different frontier
        // methods still contributes both relations.
        Map<String, Discovered> found = new LinkedHashMap<>();
        // A prefilter that ran out of time left an unknown number of modules unexamined. That is the
        // same kind of silent hole as an unreadable file and must travel with the result.
        boolean cutShort = scan.cutShort;
        for (int from = 0; from < candidates.size(); from += UI_CHUNK_SIZE)
        {
            if (expired.getAsBoolean())
            {
                cutShort = true;
                break;
            }
            List<IFile> chunk = candidates.subList(from, Math.min(from + UI_CHUNK_SIZE, candidates.size()));
            boolean[] chunkCut = { false };
            ctx.display.syncExec(() -> chunkCut[0] = matchChunk(ctx, chunk, byName, found, diag, expired));
            if (chunkCut[0])
            {
                cutShort = true;
                break;
            }
        }

        List<Discovered> ordered = new ArrayList<>(found.values());
        // A stable order makes the whole result reproducible: the engine emits edges as given, and
        // the FIRST edge to a method is the one whose parent becomes its witness.
        ordered.sort(Comparator.comparing((Discovered d) -> foldCase(d.modulePath))
            .thenComparing(d -> d.methodName == null ? "" : foldCase(d.methodName)) //$NON-NLS-1$
            .thenComparing(d -> d.key)
            // Two edges can now share a caller (it calls two frontier methods); the parent's own
            // position is what still makes the order total, and with it the chosen witness.
            .thenComparing(d -> d.parent.getIndex()));

        List<CallGraphTraversal.Edge> edges = new ArrayList<>(ordered.size());
        for (Discovered d : ordered)
        {
            edges.add(new CallGraphTraversal.Edge(d.parent, d.key,
                new CallerNode(d.modulePath, d.methodName, d.methodUri, d.line), d.methodName != null));
        }
        return new CallGraphTraversal.Expansion(edges, diag, cutShort);
    }

    /**
     * Collects the {@code .bsl} files that mention ANY of the given method names as a whole
     * identifier. Runs OFF the UI thread: it touches only the workspace and the filesystem.
     * <p>
     * Matching whole identifier tokens rather than substrings is what keeps a level to one pass: a
     * substring test would have to be repeated per name (project text x frontier size), while
     * scanning identifiers once and looking each up costs one pass regardless of how many names are
     * being searched for. It cannot lose a caller either - a static call writes the method's name as
     * a complete identifier - and it rejects candidates a substring test would have parsed for
     * nothing (a file whose only "Add" is inside {@code AddItem}).
     *
     * @param project the EDT project
     * @param foldedNames the frontier method names, already run through {@link #foldCase}
     * @param diag the accumulator for files that could not be read or enumerated
     * @param expired the cooperative time signal
     * @param alreadyCounted paths already reported as unreadable, so one bad file is one bad file
     *            however many levels re-read it
     * @return the candidate files and whether the scan was cut short, never {@code null}
     */
    private CandidateScan collectCandidatesMentioningAny(IProject project, Set<String> foldedNames,
        CallGraphTraversal.Diagnostics diag, BooleanSupplier expired, Set<String> alreadyCounted)
    {
        CandidateScan scan = new CandidateScan();
        IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        if (!srcFolder.exists())
        {
            return scan;
        }
        try
        {
            srcFolder.accept(res -> {
                if (expired.getAsBoolean())
                {
                    // Returning false only prunes this subtree, so the visitor keeps being called;
                    // the flag is what makes the shortfall visible to the caller.
                    scan.cutShort = true;
                    return false;
                }
                if (res.getType() != IResource.FILE
                    || !"bsl".equalsIgnoreCase(((IFile)res).getFileExtension())) //$NON-NLS-1$
                {
                    return true;
                }
                IFile file = (IFile)res;
                String text = readCandidateText(file);
                if (text == null)
                {
                    if (alreadyCounted.add(file.getProjectRelativePath().toString()))
                    {
                        diag.addUnreadableFile();
                    }
                    return true;
                }
                if (mentionsAnyIdentifier(text, foldedNames))
                {
                    scan.files.add(file);
                }
                return true;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning project for caller candidates", e); //$NON-NLS-1$
            diag.markEnumerationFailed();
        }
        return scan;
    }

    /**
     * The outcome of one prefilter pass: the modules worth parsing, and whether the pass was
     * abandoned before it had looked at all of them.
     */
    private static final class CandidateScan
    {
        final List<IFile> files = new ArrayList<>();
        boolean cutShort;
    }

    /**
     * True when {@code text} contains any of {@code lowerNames} as a WHOLE identifier (letters,
     * digits and underscore, case-insensitive). Scans the text once and stops at the first hit.
     * Package-visible so the headless tests can pin the whole-identifier rule directly.
     *
     * @param text the source text to scan (may be {@code null})
     * @param foldedNames the identifiers to look for, already run through {@link #foldCase}
     *            (may be empty)
     * @return {@code true} when at least one name occurs as a complete identifier
     */
    static boolean mentionsAnyIdentifier(String text, Set<String> foldedNames)
    {
        if (text == null || foldedNames == null || foldedNames.isEmpty())
        {
            return false;
        }
        int length = text.length();
        int index = 0;
        while (index < length)
        {
            if (!isIdentifierChar(text.charAt(index)))
            {
                index++;
                continue;
            }
            int start = index;
            while (index < length && isIdentifierChar(text.charAt(index)))
            {
                index++;
            }
            if (foldedNames.contains(foldCase(text.substring(start, index))))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Folds an identifier to the ONE case-insensitivity relation this tool uses.
     * <p>
     * It has to agree with {@link String#equalsIgnoreCase}, which is what
     * {@link #invocationTargetsMethod} compares names with: a hash lookup keyed by a different
     * folding would simply never offer the target to that predicate, and the caller would go
     * missing with nothing to show it had been skipped. {@code equalsIgnoreCase} maps each CODE
     * POINT through {@code toLowerCase(toUpperCase(cp))}, so folding the same way makes the map and
     * the predicate agree by construction rather than by coincidence over Latin and Cyrillic.
     * <p>
     * No BSL identifier reaches outside the BMP, so this is defensive rather than load-bearing: it
     * costs nothing and removes the need to argue about where the two relations part company.
     *
     * @param name the identifier to fold (must not be {@code null})
     * @return the folded form, usable as a map key
     */
    static String foldCase(String name)
    {
        StringBuilder sb = new StringBuilder(name.length());
        // By CODE POINT, not by char: folding the halves of a surrogate pair separately leaves a
        // supplementary letter unfolded, and the map lookup would then miss a target that
        // equalsIgnoreCase considers equal - losing a caller with nothing to show it was skipped.
        for (int i = 0; i < name.length();)
        {
            int cp = name.codePointAt(i);
            sb.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp)));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /**
     * Whether a character may appear inside a BSL identifier: a BMP letter, a digit, or the
     * underscore. Anything else terminates the identifier.
     * <p>
     * Deliberately char-based, so a surrogate half - which is not a letter - SPLITS the token.
     * A supplementary letter is not a BSL identifier character in the first place, and the two
     * possible errors here are not symmetric: splitting too eagerly only costs one extra module
     * parse, while joining too eagerly swallows the real identifier next to it and loses a caller
     * with nothing in the report to show it was skipped. The prefilter always errs toward
     * selecting a candidate.
     *
     * @param c the character to classify
     * @return {@code true} when {@code c} continues an identifier
     */
    private static boolean isIdentifierChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Parses and searches one chunk of candidate modules. Runs ON the UI thread (the shared BSL
     * resource set is not thread-safe), which is why it is a chunk and not the whole level.
     *
     * @param ctx the per-request context
     * @param chunk the candidate files to search
     * @param byName lower-cased method name to the targets carrying that name
     * @param found the level's accumulator, keyed by discovered method
     * @param diag the accumulator for modules that could not be loaded or parsed
     * @param expired the cooperative time signal, consulted between modules
     * @return {@code true} when the time budget stopped this chunk before it was finished
     */
    private boolean matchChunk(TransitiveContext ctx, List<IFile> chunk, Map<String, List<Target>> byName,
        Map<String, Discovered> found, CallGraphTraversal.Diagnostics diag, BooleanSupplier expired)
    {
        for (IFile file : chunk)
        {
            // Between MODULES, not just between chunks: the deadline cannot interrupt a parse that
            // has already started, so the finest granularity available is one module. A single
            // pathological module can still overrun it - that is a real limit, not a hidden one.
            if (expired.getAsBoolean())
            {
                return true;
            }
            String relToSrc = file.getProjectRelativePath().removeFirstSegments(1).toString();
            Module candidateModule = loadCandidate(ctx, relToSrc, diag);
            if (candidateModule == null)
            {
                continue;
            }
            scanForTargets(candidateModule, relToSrc, byName, found);
        }
        return false;
    }

    /**
     * Loads one candidate module through the request's resource set, counting every way it can fail
     * to load instead of treating the failure as "this module calls nothing".
     *
     * @param ctx the per-request context
     * @param relToSrc the candidate's source-relative path
     * @param diag the accumulator for modules that could not be loaded or parsed
     * @return the parsed module, or {@code null} when it could not be loaded
     */
    private Module loadCandidate(TransitiveContext ctx, String relToSrc,
        CallGraphTraversal.Diagnostics diag)
    {
        try
        {
            URI uri = URI.createPlatformResourceURI(ctx.projectName + "/src/" + relToSrc, true); //$NON-NLS-1$
            Resource res = ctx.resourceSet.getResource(uri, true);
            if (res == null || res.getContents().isEmpty()
                || !(res.getContents().get(0) instanceof Module))
            {
                if (ctx.unloadable.add(relToSrc))
                {
                    diag.addUnloadableModule();
                }
                return null;
            }
            if (isParseUnverified(res) && ctx.unverified.add(relToSrc))
            {
                // Xtext recovers from a syntax error and still hands back a Module - a PARTIAL one.
                // An invocation swallowed by that recovery is invisible to any AST walk, so this
                // module cannot be called fully searched. It is still scanned: whatever did parse
                // is real, and reporting nothing would lose more than it protects.
                //
                // Counted once per MODULE, not once per scan: the same module is re-scanned at
                // every level, and reporting "5 modules parsed with errors" for one broken module
                // would be a number about our own loop rather than about the project.
                diag.addUnverifiedModule();
            }
            return (Module)res.getContents().get(0);
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to load candidate module " + relToSrc //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            if (ctx.unloadable.add(relToSrc))
            {
                diag.addUnloadableModule();
            }
            return null;
        }
    }

    /**
     * Whether this resource's syntax tree can NOT be vouched for as a faithful reading of its
     * source - either because the parser recovered from a syntax error, or because there is no
     * parse result to ask.
     * <p>
     * Deliberately narrower than {@code Resource.getErrors()}: that list also carries linking and
     * validation diagnostics, and a project with ordinary semantic errors would then report every
     * single walk as incomplete - a flag that fires always is a flag nobody reads. The parse result
     * answers the one question an AST search depends on: did some source text fail to become syntax
     * tree.
     * <p>
     * Absence of evidence is NOT evidence of a clean parse. A resource that is not an
     * {@link XtextResource}, or that has no parse result, is counted too: the walk still searches
     * whatever tree is there, but it cannot claim that tree represents the file.
     *
     * @param resource the loaded resource
     * @return {@code true} when the tree may not represent the source
     */
    private static boolean isParseUnverified(Resource resource)
    {
        if (!(resource instanceof XtextResource))
        {
            return true;
        }
        org.eclipse.xtext.parser.IParseResult parseResult = ((XtextResource)resource).getParseResult();
        return parseResult == null || parseResult.hasSyntaxErrors();
    }

    /**
     * Walks one parsed module and records every invocation that resolves to one of the level's
     * targets. Each target is judged by the SAME predicate the single-hop search uses, with its own
     * declaring-module name and its own "is this the declaring module" answer, and a caller that
     * matches several targets yields one edge PER TARGET - so one batched pass yields the same
     * distinct caller-to-target RELATIONS the separate single-hop searches would have.
     * <p>
     * Not the same ROWS: a single-hop search prints one row per call site, while a level collapses
     * repeated calls from one caller to one target into a single edge (keeping the earliest line),
     * because the transitive view answers "which callers", not "which call sites".
     *
     * @param candidateModule the parsed module to search
     * @param relToSrc the module's source-relative path
     * @param byName lower-cased method name to the targets carrying that name
     * @param found the level's accumulator, keyed by discovered method
     */
    private void scanForTargets(Module candidateModule, String relToSrc,
        Map<String, List<Target>> byName, Map<String, Discovered> found)
    {
        for (Iterator<EObject> iter = candidateModule.eAllContents(); iter.hasNext();)
        {
            EObject obj = iter.next();
            if (!(obj instanceof Invocation))
            {
                continue;
            }
            Invocation inv = (Invocation)obj;
            String callName = resolveInvocationName(inv);
            if (callName == null || callName.isEmpty())
            {
                continue;
            }
            List<Target> targets = byName.get(foldCase(callName));
            if (targets == null)
            {
                continue;
            }
            for (Target target : targets)
            {
                boolean candidateIsTarget = relToSrc.equalsIgnoreCase(target.modulePath);
                if (invocationTargetsMethod(inv, target.methodUri, target.methodName,
                    target.moduleSimpleName, candidateIsTarget))
                {
                    recordDiscovered(inv, relToSrc, target, found);
                }
            }
        }
    }

    /**
     * Records the method (or module-level code) that contains a matched invocation.
     * <p>
     * The accumulator is keyed by the (caller, TARGET) pair, not by the caller alone. One caller
     * that calls two different frontier methods is two edges - which is what running the two
     * single-hop searches separately would report - and collapsing them here would quietly change
     * both the witness parent and the re-convergence count. Repeated call sites from the same
     * caller to the SAME target still collapse into one edge, keeping the earliest line.
     *
     * @param inv the matched invocation
     * @param relToSrc the containing module's source-relative path
     * @param target the target this invocation resolved to
     * @param found the level's accumulator, keyed by discovered method
     */
    private void recordDiscovered(Invocation inv, String relToSrc, Target target,
        Map<String, Discovered> found)
    {
        EObject container = inv.eContainer();
        while (container != null && !(container instanceof Method))
        {
            container = container.eContainer();
        }

        int line = BslModuleUtils.getStartLine(inv);
        String key;
        String methodName = null;
        URI methodUri = null;
        if (container instanceof Method)
        {
            methodUri = EcoreUtil.getURI(container);
            methodName = ((Method)container).getName();
            key = methodUri.toString();
        }
        else
        {
            // Module-level code: there is no method to ask for the callers of, so this is a leaf.
            // Keyed distinctly from any resource URI so it can never collide with a real method.
            // NOT case-folded: relToSrc comes from the same workspace API every time, so folding
            // buys nothing and would merge two genuinely different modules on a case-sensitive
            // filesystem.
            key = "module-level:" + relToSrc; //$NON-NLS-1$
        }
        // A method whose NAME did not survive parsing cannot be searched for at the next level;
        // treat it as module-level code rather than emitting a target nothing can match.
        if (methodName == null || methodName.isEmpty())
        {
            methodName = null;
            methodUri = null;
        }

        // A target always carries a URI (expandCallers refuses to build one without), so the pair
        // key needs no fallback. NUL cannot occur in a workspace path or an encoded EMF URI, so it
        // is a separator the two halves can never forge between them.
        String edgeKey = key + '\u0000' + target.methodUri.toString();
        Discovered existing = found.get(edgeKey);
        if (existing != null)
        {
            if (line > 0 && (existing.line <= 0 || line < existing.line))
            {
                existing.line = line;
            }
            return;
        }
        found.put(edgeKey, new Discovered(target.node, key, relToSrc, methodName, methodUri, line));
    }

    /**
     * Renders a transitive caller walk: one row per unique CALLER - a method, or a module body
     * when the call sits outside any method - with the level it was reached at and the row that led
     * there.
     * <p>
     * The header is the honest part. It separates two different facts that a single "truncated"
     * would blur: a walk that finished knows how many callers there are, while a walk cut by the
     * node or time budget does not know and cannot - the callers it never expanded may have any
     * number of callers behind them.
     *
     * @param modulePath the module declaring the analyzed method
     * @param methodName the analyzed method, as its module declares it
     * @param request the normalized request (for the effective and requested depth, and the budget)
     * @param result the finished traversal
     * @return the rendered Markdown
     */
    private String formatTransitiveCallersOutput(String modulePath, String methodName,
        RequestArgs request, CallGraphTraversal.Result result)
    {
        List<CallGraphTraversal.Node> nodes = result.getNodes();

        StringBuilder sb = new StringBuilder();
        sb.append("## Call Hierarchy (transitive): ").append(modulePath) //$NON-NLS-1$
            .append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Direction:** Callers (who calls this method), transitive\n"); //$NON-NLS-1$
        sb.append("**Depth:** ").append(request.depth); //$NON-NLS-1$
        if (request.requestedDepth > MAX_DEPTH)
        {
            sb.append(" (requested ").append(request.requestedDepth) //$NON-NLS-1$
                .append(", clamped to max ").append(MAX_DEPTH).append(')'); //$NON-NLS-1$
        }
        sb.append('\n');
        // "Callers", not "methods": a module body is a real caller and gets a row, so calling
        // these methods would misname what the number counts.
        sb.append("**Unique callers:** ").append(nodes.size()).append('\n'); //$NON-NLS-1$

        String incomplete = describeIncompleteness(request, result);
        sb.append("**Complete through depth ").append(request.depth).append(":** ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(incomplete == null ? "yes" : "no - " + incomplete).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$

        int atDepthLimit = countFlagged(nodes, CallGraphTraversal.NodeFlag.DEPTH_LIMIT);
        if (atDepthLimit > 0)
        {
            sb.append("**Left unexpanded at the depth limit:** ").append(atDepthLimit) //$NON-NLS-1$
                .append(" (raise depth to look further up)\n"); //$NON-NLS-1$
        }
        if (result.getRepeatEdges() > 0)
        {
            sb.append("**Repeat edges collapsed:** ").append(result.getRepeatEdges()) //$NON-NLS-1$
                .append(" (the graph re-converges; nothing was lost)\n"); //$NON-NLS-1$
        }
        sb.append("**Not covered:** static invocations only - a call made dynamically " //$NON-NLS-1$
            + "(Execute/Eval, a handler named by string, platform dispatch) is invisible here, " //$NON-NLS-1$
            + "so even a complete result does not prove nothing else calls this method.\n"); //$NON-NLS-1$
        sb.append('\n');

        if (nodes.isEmpty())
        {
            sb.append("No callers found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Level | Module | Method | Line | Via # | Flags |\n"); //$NON-NLS-1$
        sb.append("|---|-------|--------|--------|------|-------|-------|\n"); //$NON-NLS-1$
        for (CallGraphTraversal.Node node : nodes)
        {
            CallerNode payload = (CallerNode)node.getPayload();
            sb.append("| ").append(node.getIndex() + 1); //$NON-NLS-1$
            sb.append(" | ").append(node.getLevel()); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                payload.modulePath != null ? payload.modulePath : "-")); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                payload.methodName != null ? payload.methodName : "(module level)")); //$NON-NLS-1$
            sb.append(" | ").append(payload.line > 0 ? String.valueOf(payload.line) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | ").append(node.getParentIndex() >= 0 //$NON-NLS-1$
                ? String.valueOf(node.getParentIndex() + 1) : "-"); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(flagLabel(node.getFlag()))); //$NON-NLS-1$
            sb.append(" |\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Describes, in one phrase, why a walk may be missing real callers - or {@code null} when it is
     * not. Reaching the requested depth is deliberately NOT a reason: the caller asked for that
     * depth, the boundary rows say so themselves, and calling that "incomplete" would train an agent
     * to ignore the word.
     *
     * @param request the normalized request (for the budget value to name)
     * @param result the finished traversal
     * @return the reason phrase, or {@code null} when the walk was complete through its depth
     */
    private String describeIncompleteness(RequestArgs request, CallGraphTraversal.Result result)
    {
        if (result.isComplete())
        {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        if (result.isBudgetExhausted())
        {
            reasons.add("the node budget (limit=" + request.limit //$NON-NLS-1$
                + ") cut the WALK itself, so the true number of callers is unknown, not merely unshown"); //$NON-NLS-1$
        }
        if (result.isTimedOut() || result.isSearchCutShort())
        {
            reasons.add("the " + (TRANSITIVE_TIME_BUDGET_MS / 1000) //$NON-NLS-1$
                + "s time budget cut the WALK itself, so the true number of callers is unknown"); //$NON-NLS-1$
        }
        CallGraphTraversal.Diagnostics diag = result.getDiagnostics();
        if (diag.isEnumerationFailed())
        {
            reasons.add("listing the project's sources failed, so an unknown part of it was never searched"); //$NON-NLS-1$
        }
        if (diag.getUnreadableFiles() > 0)
        {
            reasons.add(diag.getUnreadableFiles() + " source file(s) could not be read"); //$NON-NLS-1$
        }
        if (diag.getUnloadableModules() > 0)
        {
            reasons.add(diag.getUnloadableModules() + " module(s) could not be parsed"); //$NON-NLS-1$
        }
        if (diag.getUnverifiedModules() > 0)
        {
            reasons.add(diag.getUnverifiedModules()
                + " module(s) could not be confirmed to have parsed cleanly, so a call inside them" //$NON-NLS-1$
                + " may never have reached the syntax tree this search walks"); //$NON-NLS-1$
        }
        return String.join("; ", reasons); //$NON-NLS-1$
    }

    /**
     * Counts the nodes carrying a given flag.
     *
     * @param nodes the result nodes
     * @param flag the flag to count
     * @return the number of matching nodes
     */
    private static int countFlagged(List<CallGraphTraversal.Node> nodes, CallGraphTraversal.NodeFlag flag)
    {
        int count = 0;
        for (CallGraphTraversal.Node node : nodes)
        {
            if (node.getFlag() == flag)
            {
                count++;
            }
        }
        return count;
    }

    /**
     * The wire label for a node flag.
     *
     * @param flag the flag, or {@code null} when the node was expanded normally
     * @return the label, never {@code null}
     */
    private static String flagLabel(CallGraphTraversal.NodeFlag flag)
    {
        if (flag == null)
        {
            return "-"; //$NON-NLS-1$
        }
        switch (flag)
        {
        case DEPTH_LIMIT:
            return "depth-limit"; //$NON-NLS-1$
        case NODE_BUDGET:
            return "budget"; //$NON-NLS-1$
        case TIME_LIMIT:
            return "time-limit"; //$NON-NLS-1$
        case NOT_EXPANDABLE:
            return "no-method"; //$NON-NLS-1$
        case RECURSIVE:
            return "recursive"; //$NON-NLS-1$
        default:
            return "-"; //$NON-NLS-1$
        }
    }

    // ========== Helper methods ==========

    /**
     * Removes single-line comment lines (// ...) from multi-line node text.
     * Prevents comments from merging with code when displayed in table cells.
     */
    private String stripCommentLines(String text)
    {
        if (text == null || text.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }

        String[] lines = text.split("\\r?\\n"); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (String line : lines)
        {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//")) //$NON-NLS-1$
            {
                if (sb.length() > 0)
                {
                    sb.append(' ');
                }
                sb.append(trimmed);
            }
        }
        return sb.length() > 0 ? sb.toString() : text.trim();
    }

    /**
     * Smart truncation for long call expressions.
     * Short calls shown as-is: "Foo(arg1, arg2)".
     * Long calls: "MethodName(...)".
     */
    private String smartTruncateCall(String text, String methodName)
    {
        if (methodName != null && !methodName.isEmpty())
        {
            int nameIdx = text.indexOf(methodName);
            if (nameIdx >= 0)
            {
                return text.substring(0, nameIdx + methodName.length()) + "(...)"; //$NON-NLS-1$
            }
        }
        return text.substring(0, Math.min(text.length(), 100)) + "..."; //$NON-NLS-1$
    }

    private String formatCallersOutput(String modulePath, String methodName,
                                        List<CallerInfo> callers, int totalReferences)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Call Hierarchy: ").append(modulePath).append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Direction:** Callers (who calls this method)\n"); //$NON-NLS-1$
        sb.append("**Total references found:** ").append(totalReferences); //$NON-NLS-1$
        sb.append(Pagination.truncationNotice(callers.size(), totalReferences));
        sb.append("\n\n"); //$NON-NLS-1$

        if (callers.isEmpty())
        {
            sb.append("No callers found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Module | Method | Line | Call Code |\n"); //$NON-NLS-1$
        sb.append("|---|--------|--------|------|-----------|\n"); //$NON-NLS-1$

        int idx = 1;
        for (CallerInfo caller : callers)
        {
            sb.append("| ").append(idx++); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                caller.modulePath != null ? caller.modulePath : "-")); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                caller.callerMethodName != null ? caller.callerMethodName : "-")); //$NON-NLS-1$
            sb.append(" | ").append(caller.line > 0 ? String.valueOf(caller.line) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | `").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                caller.callCode != null ? caller.callCode : "-")).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    private String formatCalleesOutput(String modulePath, String methodName,
                                        List<CalleeInfo> callees, int totalInvocations)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Call Hierarchy: ").append(modulePath).append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Direction:** Callees (what this method calls)\n"); //$NON-NLS-1$
        sb.append("**Total calls found:** ").append(totalInvocations); //$NON-NLS-1$
        sb.append(Pagination.truncationNotice(callees.size(), totalInvocations));
        sb.append("\n\n"); //$NON-NLS-1$

        if (callees.isEmpty())
        {
            sb.append("No calls found in this method.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Called Method | Line | Call Code |\n"); //$NON-NLS-1$
        sb.append("|---|--------------|------|-----------|\n"); //$NON-NLS-1$

        int idx = 1;
        for (CalleeInfo callee : callees)
        {
            sb.append("| ").append(idx++); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(callee.calledMethodName)); //$NON-NLS-1$
            sb.append(" | ").append(callee.line > 0 ? String.valueOf(callee.line) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | `").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                callee.callCode != null ? callee.callCode : "-")).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    // ========== Outgoing (aggregated targets) ==========

    /**
     * Aggregates the distinct outgoing call targets of a scope. When {@code methodName} is given the
     * scope is that method's AST (mirroring {@link #findCallees}); when it is omitted the scope is
     * the whole module's AST (mirroring the invocation walk of {@link #findCallers} /
     * {@link #scanCandidateInvocations}). Each {@link Invocation} is classified via
     * {@link #qualifierKey(EObject)} and aggregated by {@code qualifier + "." + method} into a
     * first-seen-ordered map: {@code count} is the number of call sites and {@code firstLine} is the
     * smallest start line across those sites.
     *
     * @param projectName the EDT project
     * @param modulePath the source-relative module path
     * @param methodName the scoping method name, or {@code null}/blank for the whole module
     * @param extApiPrefix the literal external-API qualifier prefix (case-insensitive)
     * @param limit the maximum number of distinct rows to render
     * @return the rendered Markdown, or a {@link ToolResult#error} JSON string on failure
     */
    private String findOutgoing(String projectName, String modulePath, String methodName,
        String extApiPrefix, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return moduleLoadFailure(modulePath);
        }

        boolean scoped = methodName != null && !methodName.trim().isEmpty();
        EObject scope;
        if (scoped)
        {
            Method method = BslModuleUtils.findMethod(module, methodName);
            if (method == null)
            {
                return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
            }
            scope = method;
        }
        else
        {
            scope = module;
        }

        Map<String, OutgoingTarget> targets = aggregateOutgoing(scope, extApiPrefix, modulePath);

        return formatOutgoingOutput(modulePath, scoped ? methodName : null,
            new ArrayList<>(targets.values()), limit);
    }

    /**
     * Walks a scope's AST and aggregates every {@link Invocation} into distinct outgoing targets,
     * keyed by {@code qualifier + "." + method} in first-seen order. A per-file parse failure never
     * aborts the aggregation: it is logged and the targets collected so far are returned (mirroring
     * the candidate-scan resilience of {@link #findCallers}). Extracted from {@link #findOutgoing}
     * to keep that method's complexity in check.
     *
     * @param scope the AST root to walk (a single method or the whole module)
     * @param extApiPrefix the literal external-API qualifier prefix (case-insensitive)
     * @param modulePath the module path, used only for the failure log message
     * @return the first-seen-ordered map of aggregated targets (possibly empty, never {@code null})
     */
    private Map<String, OutgoingTarget> aggregateOutgoing(EObject scope, String extApiPrefix,
        String modulePath)
    {
        // First-seen-ordered aggregation keyed by qualifier + "." + method.
        Map<String, OutgoingTarget> targets = new LinkedHashMap<>();
        try
        {
            for (Iterator<EObject> iter = scope.eAllContents(); iter.hasNext();)
            {
                EObject obj = iter.next();
                if (obj instanceof Invocation)
                {
                    accumulateOutgoing((Invocation)obj, extApiPrefix, targets);
                }
            }
        }
        catch (Exception e)
        {
            // Per-file parse failure must never abort the aggregation; log and continue with what
            // was collected so far (mirrors the candidate-scan resilience of findCallers).
            Activator.logWarning("Failed to walk module for outgoing calls " + modulePath //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return targets;
    }

    /**
     * Aggregates a single {@link Invocation} into the shared {@code targets} map: resolves the called
     * method name (skipping non-invocations / unnamed accesses), classifies the qualifier, then bumps
     * the matching {@link OutgoingTarget}'s count and shrinks its {@code firstLine} to the smallest
     * positive start line seen. Extracted from {@link #findOutgoing} to flatten its aggregation loop.
     *
     * @param inv the invocation to fold in
     * @param extApiPrefix the literal external-API qualifier prefix (case-insensitive)
     * @param targets the first-seen-ordered accumulator (appended to / updated, never reassigned)
     */
    private void accumulateOutgoing(Invocation inv, String extApiPrefix,
        Map<String, OutgoingTarget> targets)
    {
        // Reuse the frozen resolveInvocationName (it re-derives the method access from the
        // Invocation) rather than a duplicate name-resolver; classify the qualifier separately.
        String method = resolveInvocationName(inv);
        if (method == null || method.isEmpty())
        {
            return;
        }
        String qualifier = qualifierKey(inv.getMethodAccess());
        int line = BslModuleUtils.getStartLine(inv);

        String key = aggregationKey(qualifier, method);
        OutgoingTarget target = targets.get(key);
        if (target == null)
        {
            target = new OutgoingTarget();
            target.qualifier = qualifier;
            target.method = method;
            target.count = 0;
            target.firstLine = line;
            target.extApi = isExtApi(qualifier, extApiPrefix);
            targets.put(key, target);
        }
        target.count++;
        // firstLine = smallest POSITIVE start line across the call sites. getStartLine() returns
        // 0 when a node has no line info; 0 must not win the min (it renders as '-', like
        // callers/callees), so only positive lines lower firstLine.
        if (line > 0 && (target.firstLine <= 0 || line < target.firstLine))
        {
            target.firstLine = line;
        }
    }

    /**
     * Renders the aggregated outgoing-call targets as Markdown. The heading names the module and,
     * only when the scope is a single method, appends {@code " :: <method>"}. Distinct rows are
     * clamped to {@code limit}; the total-distinct count and truncation notice are computed against
     * the full set before clamping.
     *
     * @param modulePath the analyzed module path
     * @param methodName the scoping method name, or {@code null} for a module-wide scope
     * @param targets the aggregated distinct targets in first-seen order
     * @param limit the maximum number of rows to render
     * @return the rendered Markdown
     */
    private String formatOutgoingOutput(String modulePath, String methodName,
        List<OutgoingTarget> targets, int limit)
    {
        int totalDistinct = targets.size();
        int shown = Math.min(totalDistinct, limit);

        StringBuilder sb = new StringBuilder();
        sb.append("## Outgoing Calls: ").append(modulePath); //$NON-NLS-1$
        if (methodName != null && !methodName.isEmpty())
        {
            sb.append(" :: ").append(methodName); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$
        sb.append("**Direction:** Outgoing calls (aggregated targets)\n"); //$NON-NLS-1$
        sb.append("**Total distinct targets:** ").append(totalDistinct); //$NON-NLS-1$
        sb.append(Pagination.truncationNotice(shown, totalDistinct));
        sb.append("\n\n"); //$NON-NLS-1$

        if (targets.isEmpty())
        {
            sb.append("No outgoing calls found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| Qualifier | Method | Count | First line | ExtAPI |\n"); //$NON-NLS-1$
        sb.append("|-----------|--------|-------|------------|--------|\n"); //$NON-NLS-1$

        int rendered = 0;
        for (OutgoingTarget target : targets)
        {
            if (rendered >= shown)
            {
                break;
            }
            rendered++;
            sb.append("| ").append(MarkdownUtils.escapeForTable(target.qualifier)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(target.method)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(String.valueOf(target.count))); //$NON-NLS-1$
            sb.append(" | ").append(target.firstLine > 0 ? String.valueOf(target.firstLine) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(target.extApi ? "yes" : "-")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append(" |\n"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    // ========== Data structures ==========

    private static class CallerInfo
    {
        String modulePath;
        String callerMethodName;
        int line;
        String callCode;
    }

    private static class CalleeInfo
    {
        String calledMethodName;
        int line;
        String callCode;
    }

    /**
     * One aggregated outgoing-call target: a distinct {@code qualifier.method} pair with the number
     * of call sites and the smallest start line across them.
     */
    private static class OutgoingTarget
    {
        String qualifier;
        String method;
        int count;
        int firstLine;
        boolean extApi;
    }
}

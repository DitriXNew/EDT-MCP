/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Find a BSL method's call hierarchy: who calls it (callers, default) " + //$NON-NLS-1$
               "or what it calls (callees), via semantic AST analysis that resolves " + //$NON-NLS-1$
               "ru/en spellings (unlike literal search_in_code). " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_method_call_hierarchy')."; //$NON-NLS-1$
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
                "'callers' (default) = who calls this method; 'callees' = what this method calls; " //$NON-NLS-1$
                + "'outgoing' = aggregated distinct call targets (module-wide when methodName omitted)", //$NON-NLS-1$
                KEY_CALLERS, "callees", KEY_OUTGOING) //$NON-NLS-1$
            .stringProperty(KEY_EXT_API_PREFIX,
                "For direction 'outgoing': literal call-qualifier prefix (case-insensitive) that " //$NON-NLS-1$
                + "flags a target as an external service API. Default: the 1C region name " //$NON-NLS-1$
                + "'\u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u043d\u044b\u0439" //$NON-NLS-1$
                + "\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441" //$NON-NLS-1$
                + "\u0421\u0435\u0440\u0432\u0438\u0441\u0430'.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Max results. Default: 100, max: 500") //$NON-NLS-1$
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

        // methodName is optional (only required for callers/callees); require it manually below
        // after we have parsed and validated direction, so the guard can honour 'outgoing'.
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, McpKeys.MODULE_PATH);
        if (err != null)
        {
            return err;
        }

        if (direction == null || direction.isEmpty())
        {
            direction = KEY_CALLERS;
        }
        direction = direction.toLowerCase();

        if (!KEY_CALLERS.equals(direction) && !"callees".equals(direction) //$NON-NLS-1$
            && !KEY_OUTGOING.equals(direction))
        {
            return ToolResult.error("direction must be 'callers', 'callees' or 'outgoing'").toJson(); //$NON-NLS-1$
        }

        if ((methodName == null || methodName.trim().isEmpty()) && !KEY_OUTGOING.equals(direction))
        {
            return ToolResult.error("methodName is required for callers/callees" //$NON-NLS-1$
                + ". Use get_module_structure to list the module's procedures and functions.").toJson(); //$NON-NLS-1$
        }

        if (extApiPrefix == null || extApiPrefix.isEmpty())
        {
            extApiPrefix = DEFAULT_EXT_API_PREFIX;
        }

        limit = Pagination.clampLimit(limit, 500);

        AtomicReference<String> resultRef = new AtomicReference<>();
        final String dir = direction;
        final int maxResults = limit;
        final String prefix = extApiPrefix;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result;
                if (KEY_OUTGOING.equals(dir))
                {
                    result = findOutgoing(projectName, modulePath, methodName, prefix, maxResults);
                }
                else if (KEY_CALLERS.equals(dir))
                {
                    result = findCallers(projectName, modulePath, methodName, maxResults);
                }
                else
                {
                    result = findCallees(projectName, modulePath, methodName, maxResults);
                }
                resultRef.set(result);
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
            return ToolResult.error("Could not load EMF model for " + modulePath + //$NON-NLS-1$
                   ". Call hierarchy requires BSL AST (EMF). Check EDT Error Log for details.").toJson(); //$NON-NLS-1$
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
            return ToolResult.error("Could not load EMF model for " + modulePath + //$NON-NLS-1$
                   ". Call hierarchy requires BSL AST (EMF). Check EDT Error Log for details.").toJson(); //$NON-NLS-1$
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
            return ToolResult.error("Could not load EMF model for " + modulePath + //$NON-NLS-1$
                   ". Call hierarchy requires BSL AST (EMF). Check EDT Error Log for details.").toJson(); //$NON-NLS-1$
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

        // First-seen-ordered aggregation keyed by qualifier + "." + method.
        Map<String, OutgoingTarget> targets = new LinkedHashMap<>();

        try
        {
            for (Iterator<EObject> iter = scope.eAllContents(); iter.hasNext();)
            {
                EObject obj = iter.next();
                if (!(obj instanceof Invocation))
                {
                    continue;
                }
                Invocation inv = (Invocation)obj;
                // Reuse the frozen resolveInvocationName (it re-derives the method access from the
                // Invocation) rather than a duplicate name-resolver; classify the qualifier separately.
                String method = resolveInvocationName(inv);
                if (method == null || method.isEmpty())
                {
                    continue;
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
        }
        catch (Exception e)
        {
            // Per-file parse failure must never abort the aggregation; log and continue with what
            // was collected so far (mirrors the candidate-scan resilience of findCallers).
            Activator.logWarning("Failed to walk module for outgoing calls " + modulePath //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }

        return formatOutgoingOutput(modulePath, scoped ? methodName : null,
            new ArrayList<>(targets.values()), limit);
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

/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Iterator;
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
 */
public class GetMethodCallHierarchyTool implements IMcpTool
{
    public static final String NAME = "get_method_call_hierarchy"; //$NON-NLS-1$

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
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Name of the procedure/function (case-insensitive, required)", true) //$NON-NLS-1$
            .enumProperty("direction", //$NON-NLS-1$
                "'callers' (default) or 'callees'", //$NON-NLS-1$
                "callers", "callees") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", //$NON-NLS-1$
                "Max results. Default: 100, max: 500") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Resolves a BSL method's call graph in one direction at a time using the " //$NON-NLS-1$
            + "semantic AST (resolved feature references), not plain text. Because matching " //$NON-NLS-1$
            + "is by the resolved method (not by literal spelling), it finds calls written " //$NON-NLS-1$
            + "in either the ru or en BSL dialect - unlike literal `search_in_code`.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- `callers`: find every place that invokes a given procedure/function before " //$NON-NLS-1$
            + "renaming, changing its signature, or assessing impact.\n" //$NON-NLS-1$
            + "- `callees`: list what a method itself calls, to understand its dependencies.\n" //$NON-NLS-1$
            + "- Prefer this over `search_in_code` for identifier lookup: text search is " //$NON-NLS-1$
            + "literal and not dialect-aware, so it misses the other-language spelling.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `modulePath` (required) - path from the project's `src/` folder to the " //$NON-NLS-1$
            + "module that DEFINES the method, e.g. " //$NON-NLS-1$
            + "`CommonModules/MyModule/Module.bsl` or " //$NON-NLS-1$
            + "`Documents/SalesOrder/ManagerModule.bsl`.\n" //$NON-NLS-1$
            + "- `methodName` (required) - the procedure/function name; case-insensitive, " //$NON-NLS-1$
            + "matched by programmatic Name (not by synonym).\n" //$NON-NLS-1$
            + "- `direction` - `callers` (default) = who calls this method; `callees` = " //$NON-NLS-1$
            + "what this method calls. An unknown value returns an error.\n" //$NON-NLS-1$
            + "- `limit` - max rows returned; default 100, max 500 (clamped). The reported " //$NON-NLS-1$
            + "total count is exact even when rows are truncated.\n\n" //$NON-NLS-1$

            + "## How callers are found\n" //$NON-NLS-1$
            + "BSL invocations are linked by name through scoping and are NOT stored as " //$NON-NLS-1$
            + "ordinary cross-references in the index, so the generic Xtext reference finder " //$NON-NLS-1$
            + "cannot see them. This tool mirrors EDT's own strategy: text-prefilter the " //$NON-NLS-1$
            + "`.bsl` modules whose source mentions the method name, parse only those, and " //$NON-NLS-1$
            + "match each invocation to this exact method by its resolved feature entry. When " //$NON-NLS-1$
            + "the resolver left entries empty it falls back to the call qualifier " //$NON-NLS-1$
            + "(`Module.Method`) or an unqualified call inside the defining module itself.\n\n" //$NON-NLS-1$

            + "## Output\n" //$NON-NLS-1$
            + "Markdown table. Callers: # / Module / Method / Line / Call Code. Callees: " //$NON-NLS-1$
            + "# / Called Method / Line / Call Code. Long or multi-line call expressions are " //$NON-NLS-1$
            + "compacted (comment lines stripped, body collapsed to `Name(...)`).\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Callers (default): `{projectName, modulePath: " //$NON-NLS-1$
            + "\"CommonModules/MyModule/Module.bsl\", methodName: \"DoWork\"}`.\n" //$NON-NLS-1$
            + "- Callees: `{projectName, modulePath: " //$NON-NLS-1$
            + "\"CommonModules/MyModule/Module.bsl\", methodName: \"DoWork\", direction: " //$NON-NLS-1$
            + "\"callees\"}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- `modulePath` must point at the module that DEFINES the method; if the method " //$NON-NLS-1$
            + "is not found there the tool returns a not-found response listing the module's " //$NON-NLS-1$
            + "methods.\n" //$NON-NLS-1$
            + "- `callees` lists raw invocation names from the target method's body and does " //$NON-NLS-1$
            + "not resolve each callee to its defining module.\n" //$NON-NLS-1$
            + "- Requires a loadable BSL AST (EMF); a module that fails to parse returns an " //$NON-NLS-1$
            + "error pointing at the EDT Error Log.\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        if (methodName != null && !methodName.isEmpty())
        {
            return "call-hierarchy-" + methodName.toLowerCase() + //$NON-NLS-1$
                   "-" + (direction != null ? direction : "callers") + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return "call-hierarchy.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", 100); //$NON-NLS-1$

        String err = JsonUtils.requireArguments(params, "projectName", "modulePath", "methodName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (err != null)
        {
            return err;
        }

        if (direction == null || direction.isEmpty())
        {
            direction = "callers"; //$NON-NLS-1$
        }
        direction = direction.toLowerCase();

        if (!"callers".equals(direction) && !"callees".equals(direction)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("direction must be 'callers' or 'callees'").toJson(); //$NON-NLS-1$
        }

        limit = Pagination.clampLimit(limit, 500);

        AtomicReference<String> resultRef = new AtomicReference<>();
        final String dir = direction;
        final int maxResults = limit;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result;
                if ("callers".equals(dir)) //$NON-NLS-1$
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
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
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

        for (IFile candidate : candidates)
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
            for (Iterator<EObject> iter = candidateModule.eAllContents(); iter.hasNext();)
            {
                EObject obj = iter.next();
                if (!(obj instanceof Invocation))
                {
                    continue;
                }
                Invocation inv = (Invocation)obj;
                if (!invocationTargetsMethod(inv, methodUri, methodName, targetModuleName, candidateIsTarget))
                {
                    continue;
                }
                totalReferences++;
                if (callers.size() < limit)
                {
                    callers.add(buildCallerInfo(inv, relToSrc, methodName));
                }
            }
        }

        return formatCallersOutput(modulePath, methodName, callers, limit, totalReferences);
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
     * Finds all callees from the specified method by traversing its AST.
     */
    private String findCallees(String projectName, String modulePath, String methodName, int limit)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
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

            String calledName = null;
            int line = 0;

            if (obj instanceof Invocation)
            {
                Invocation inv = (Invocation) obj;
                EObject methodAccess = inv.getMethodAccess();
                if (methodAccess instanceof StaticFeatureAccess)
                {
                    calledName = ((StaticFeatureAccess) methodAccess).getName();
                }
                else if (methodAccess instanceof DynamicFeatureAccess)
                {
                    calledName = ((DynamicFeatureAccess) methodAccess).getName();
                }
                line = BslModuleUtils.getStartLine(inv);
            }

            if (calledName != null && !calledName.isEmpty())
            {
                totalInvocations++;

                if (callees.size() < limit)
                {
                    CalleeInfo callee = new CalleeInfo();
                    callee.calledMethodName = calledName;
                    callee.line = line;

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

                    callees.add(callee);
                }
            }
        }

        return formatCalleesOutput(modulePath, methodName, callees, limit, totalInvocations);
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
                                        List<CallerInfo> callers, int limit, int totalReferences)
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
                                        List<CalleeInfo> callees, int limit, int totalInvocations)
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
}

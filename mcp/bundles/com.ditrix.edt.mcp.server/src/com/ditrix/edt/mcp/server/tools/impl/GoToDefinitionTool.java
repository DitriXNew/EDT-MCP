/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to navigate to the definition of a symbol (method, metadata object).
 * Resolves references like "CommonModuleName.MethodName" to the actual definition location
 * with source code, signature, and module path.
 *
 * This is the semantic "Go to Definition" — the inverse of find_references.
 * Instead of finding all usages, it finds where a symbol is defined.
 */
public class GoToDefinitionTool implements IMcpTool
{
    public static final String NAME = "go_to_definition"; //$NON-NLS-1$

    /** Input param: the symbol to resolve (qualified method, bare method, or metadata FQN). */
    private static final String KEY_SYMBOL = "symbol"; //$NON-NLS-1$

    /** Opening fence for a BSL code block. */
    private static final String BSL_CODE_FENCE_OPEN = "```bsl\n"; //$NON-NLS-1$

    /** Closing fence for a code block. */
    private static final String CODE_FENCE_CLOSE = "```\n"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Locate the source definition of a BSL symbol or metadata object. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('go_to_definition')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name", true) //$NON-NLS-1$
            .stringProperty(KEY_SYMBOL,
                "'ModuleName.MethodName', bare 'MethodName' (needs modulePath), or metadata FQN " + //$NON-NLS-1$
                "'Catalog.Products'", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Module path from src/, e.g. 'CommonModules/My/Module.bsl'; required for a bare method name") //$NON-NLS-1$
            .booleanProperty("includeSource", //$NON-NLS-1$
                "Include the source body (default true)") //$NON-NLS-1$
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
        String symbol = JsonUtils.extractStringArgument(params, KEY_SYMBOL);
        if (symbol != null && !symbol.isEmpty())
        {
            String safeName = symbol.replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "definition-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "definition.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_SYMBOL);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String symbol = JsonUtils.extractStringArgument(params, KEY_SYMBOL);
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String includeSourceStr = JsonUtils.extractStringArgument(params, "includeSource"); //$NON-NLS-1$

        boolean includeSource = !"false".equalsIgnoreCase(includeSourceStr); //$NON-NLS-1$

        // An unqualified method name (no dot) cannot be located without a module.
        if (symbol != null && !symbol.contains(".") //$NON-NLS-1$
            && (modulePath == null || modulePath.isEmpty()))
        {
            return ToolResult.error("modulePath is required for an unqualified method name like '" //$NON-NLS-1$
                + symbol + "'. Or pass a qualified 'ModuleName.MethodName' or a metadata FQN like 'Catalog.Products'.").toJson(); //$NON-NLS-1$
        }

        // Execute on UI thread (required for EDT API access)
        AtomicReference<String> resultRef = new AtomicReference<>();

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = resolveDefinition(projectName, symbol, modulePath, includeSource);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error resolving definition", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    // ========== Main resolution logic ==========

    /**
     * Main entry point for definition resolution.
     * Dispatches to appropriate resolver based on symbol format.
     */
    private String resolveDefinition(String projectName, String symbol, String modulePath, boolean includeSource)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        // Normalize Russian metadata type names: "Документ.Встреча" -> "Document.Встреча"
        symbol = MetadataTypeUtils.normalizeFqn(symbol);

        // Parse symbol: split by "."
        String[] parts = symbol.split("\\.", 2); //$NON-NLS-1$

        if (parts.length == 2)
        {
            // Two-part symbol: "ModuleName.MethodName" or "Catalog.Products"
            return resolveTwoPartSymbol(project, projectName, parts[0], parts[1], includeSource);
        }
        else
        {
            // Single-part symbol: "MethodName" — needs modulePath context
            return resolveSinglePartSymbol(project, projectName, symbol, modulePath, includeSource);
        }
    }

    /**
     * Resolves a two-part symbol like "CommonModuleName.MethodName" or "Catalog.Products".
     *
     * Resolution order:
     * 1. Try as CommonModule method (most common case for AI)
     * 2. Try as metadata object FQN (Catalog.Products, Document.SalesOrder, etc.)
     */
    private String resolveTwoPartSymbol(IProject project, String projectName, String firstPart,
                                         String secondPart, boolean includeSource)
    {
        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        // The ROOT to resolve against, not the configuration: this tool ADVERTISES every type in
        // the shared catalogue (see buildNotFoundResponse's "Supported Metadata Types"), which
        // now includes ExternalDataProcessor / ExternalReport - so it has to be able to resolve
        // them where they live, or it promises what it cannot do (issue #309 review round 2).
        MetadataScope scope = MetadataScope.of(project, config);
        if (scope.externalRootUnavailable())
        {
            return ToolResult.error(ProjectContext.unreadableExternalRootMessage(projectName)).toJson();
        }
        if (config == null && !scope.isExternalObjects())
        {
            return ToolResult.error("Could not get configuration for project").toJson(); //$NON-NLS-1$
        }

        // 1. Try as CommonModule method: firstPart = module name, secondPart = method name.
        // NOT for an external-objects project, linked or not. It holds no common modules of its
        // own, and there `config` is the BASE configuration: searching it would find the base's
        // module and then look for that module's .bsl inside THIS project - answering "Module
        // not found: src/CommonModules/X/Module.bsl" about a path the caller never named, or, if
        // such a path happened to exist here, serving an unrelated local file under the base
        // module's identity. No parent object takes part in this project's answer (issue #309).
        CommonModule commonModule = scope.isExternalObjects() || config == null
            ? null : findCommonModuleByName(config, firstPart);
        if (commonModule != null)
        {
            String cmModulePath = "CommonModules/" + commonModule.getName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            return resolveMethodInModule(project, projectName, cmModulePath, secondPart,
                includeSource, commonModule.getName());
        }

        // 2. Try as metadata object FQN: firstPart = type, secondPart = name
        MdObject mdObject = scope.findObject(firstPart, secondPart);
        if (mdObject != null)
        {
            return formatMetadataDefinition(project, projectName, mdObject, firstPart);
        }

        // 3. Nothing found — provide suggestions
        return buildNotFoundResponse(scope, firstPart, secondPart);
    }

    /**
     * Resolves a single-part symbol (method name) within the context module.
     */
    private String resolveSinglePartSymbol(IProject project, String projectName, String methodName,
                                            String modulePath, boolean includeSource)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return ToolResult.error("modulePath is required when symbol is an unqualified method name. " + //$NON-NLS-1$
                   "Provide the context module path (e.g. 'CommonModules/MyModule/Module.bsl') " + //$NON-NLS-1$
                   "or use qualified name 'ModuleName.MethodName'.").toJson(); //$NON-NLS-1$
        }

        return resolveMethodInModule(project, projectName, modulePath, methodName, includeSource, null);
    }

    // ========== Method resolution ==========

    /**
     * Resolves a method definition within a specific BSL module.
     * Returns full definition info including source code.
     */
    private String resolveMethodInModule(IProject project, String projectName, String modulePath,
                                          String methodName, boolean includeSource, String qualifiedPrefix)
    {
        // Load module via EMF
        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            // EMF not available — try text-based fallback
            return resolveMethodViaText(project, projectName, modulePath, methodName,
                includeSource, qualifiedPrefix);
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        return formatMethodDefinition(project, projectName, modulePath, method, includeSource, qualifiedPrefix);
    }

    /**
     * Formats method definition result with optional source code.
     */
    private String formatMethodDefinition(IProject project, String projectName, String modulePath,
                                           Method method, boolean includeSource, String qualifiedPrefix)
    {
        int startLine = BslModuleUtils.getStartLine(method);
        int endLine = BslModuleUtils.getEndLine(method);
        String typeStr = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$

        // Read source from file
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        List<String> allLines = null;
        try
        {
            allLines = BslModuleUtils.readFileLines(file);
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to read file for source: " + e.getMessage()); //$NON-NLS-1$
        }

        // Include doc-comment block above the method
        int from = startLine;
        int to = endLine;
        if (allLines != null)
        {
            int docStart = findDocCommentStart(allLines, startLine);
            from = Math.max(1, docStart);
            to = Math.min(allLines.size(), endLine);
        }

        // Find containing region
        String region = allLines != null ? BslModuleUtils.findRegionForLine(allLines, startLine) : null;

        // Build frontmatter
        FrontMatter fm = FrontMatter.create()
            .put(McpKeys.PROJECT_NAME, projectName)
            .put("module", modulePath) //$NON-NLS-1$
            .put("method", method.getName()) //$NON-NLS-1$
            .put("type", typeStr) //$NON-NLS-1$
            .put("export", method.isExport()) //$NON-NLS-1$
            .put("startLine", from) //$NON-NLS-1$
            .put("endLine", to); //$NON-NLS-1$

        if (allLines != null)
        {
            fm.put("totalLines", allLines.size()); //$NON-NLS-1$
        }

        if (region != null)
        {
            fm.put("region", region); //$NON-NLS-1$
        }

        if (qualifiedPrefix != null)
        {
            fm.put("qualifiedName", qualifiedPrefix + "." + method.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return fm.wrapContent(buildMethodSource(includeSource, allLines, from, to, method));
    }

    /**
     * Builds the fenced BSL source block for {@link #formatMethodDefinition}. When {@code includeSource}
     * is false the result is empty. The preferred source is the {@code [from, to]} slice of the file
     * lines; if the file could not be read ({@code allLines == null}) it falls back to the method's EMF
     * {@code getText()}. Pure string assembly, no side effects.
     */
    private static String buildMethodSource(boolean includeSource, List<String> allLines, int from, int to,
        Method method)
    {
        StringBuilder sb = new StringBuilder();
        if (includeSource)
        {
            if (allLines != null)
            {
                sb.append(BSL_CODE_FENCE_OPEN);
                for (int i = from - 1; i < to; i++)
                {
                    sb.append(allLines.get(i)).append('\n');
                }
                sb.append(CODE_FENCE_CLOSE);
            }
            else
            {
                // Fallback: use EMF getText()
                String sourceText = BslModuleUtils.getSourceText(method);
                if (sourceText != null)
                {
                    sb.append(BSL_CODE_FENCE_OPEN);
                    sb.append(sourceText);
                    if (!sourceText.endsWith("\n")) //$NON-NLS-1$
                    {
                        sb.append('\n');
                    }
                    sb.append(CODE_FENCE_CLOSE);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Fallback: resolves method definition via text-based parsing.
     * Used when EMF model is not available.
     */
    private String resolveMethodViaText(IProject project, String projectName, String modulePath,
                                         String methodName, boolean includeSource, String qualifiedPrefix)
    {
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (!file.exists())
        {
            return ToolResult.error("Module not found: src/" + modulePath).toJson(); //$NON-NLS-1$
        }

        try
        {
            List<String> allLines = BslModuleUtils.readFileLines(file);

            // Locate the method via the shared text-scan fallback.
            BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(allLines, methodName);
            if (!tm.found)
            {
                return BslModuleUtils.buildTextMethodNotFoundResponse(methodName, modulePath, tm.allMethodNames);
            }

            int methodStart = tm.startLine;
            int methodEnd = tm.endLine;
            String matchedName = tm.matchedName;
            String typeStr = tm.isFunction ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$

            // Find containing region
            String region = BslModuleUtils.findRegionForLine(allLines, methodStart + 1);

            FrontMatter fm = FrontMatter.create()
                .put(McpKeys.PROJECT_NAME, projectName)
                .put("module", modulePath) //$NON-NLS-1$
                .put("method", matchedName) //$NON-NLS-1$
                .put("type", typeStr) //$NON-NLS-1$
                .put("startLine", methodStart + 1) //$NON-NLS-1$
                .put("endLine", methodEnd + 1) //$NON-NLS-1$
                .put("totalLines", allLines.size()); //$NON-NLS-1$

            if (region != null)
            {
                fm.put("region", region); //$NON-NLS-1$
            }

            if (qualifiedPrefix != null)
            {
                fm.put("qualifiedName", qualifiedPrefix + "." + matchedName); //$NON-NLS-1$ //$NON-NLS-2$
            }

            StringBuilder sb = new StringBuilder();
            if (includeSource)
            {
                sb.append(BSL_CODE_FENCE_OPEN);
                for (int i = methodStart; i <= methodEnd; i++)
                {
                    sb.append(allLines.get(i)).append('\n');
                }
                sb.append(CODE_FENCE_CLOSE);
            }

            return fm.wrapContent(sb.toString());
        }
        catch (Exception e)
        {
            return ToolResult.error("Error reading file: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // ========== Metadata resolution ==========

    /**
     * Finds a CommonModule by name (case-insensitive).
     */
    private CommonModule findCommonModuleByName(Configuration config, String name)
    {
        if (name == null || config == null)
        {
            return null;
        }

        for (CommonModule cm : config.getCommonModules())
        {
            if (name.equalsIgnoreCase(cm.getName()))
            {
                return cm;
            }
        }

        return null;
    }

    /**
     * Formats a metadata object definition result.
     * Includes the object type, available modules, and module paths.
     */
    private String formatMetadataDefinition(IProject project, String projectName,
                                             MdObject mdObject, String typeName)
    {
        String collectionFolder = getCollectionFolder(typeName);

        FrontMatter fm = FrontMatter.create()
            .put(McpKeys.PROJECT_NAME, projectName)
            .put("kind", "MetadataObject") //$NON-NLS-1$ //$NON-NLS-2$
            .put("type", typeName) //$NON-NLS-1$
            .put("name", mdObject.getName()); //$NON-NLS-1$

        StringBuilder sb = new StringBuilder();

        // List available BSL modules for this object
        if (collectionFolder != null)
        {
            String basePath = collectionFolder + "/" + mdObject.getName(); //$NON-NLS-1$
            List<String> modules = collectAvailableBslModules(project, basePath);

            if (!modules.isEmpty())
            {
                sb.append("### Available Modules\n\n"); //$NON-NLS-1$
                for (String modPath : modules)
                {
                    sb.append("- ").append(modPath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                sb.append("No BSL modules found for this object.\n"); //$NON-NLS-1$
            }
        }

        sb.append("\n*Use `get_metadata_details` for full object properties, " + //$NON-NLS-1$
                  "or `read_module_source`/`read_method_source` to read specific modules.*\n"); //$NON-NLS-1$

        return fm.wrapContent(sb.toString());
    }

    /**
     * Collects all .bsl modules under a metadata object directory.
     */
    private List<String> collectAvailableBslModules(IProject project, String basePath)
    {
        List<String> modules = new ArrayList<>();
        org.eclipse.core.resources.IContainer folder = project.getFolder(new Path("src").append(basePath)); //$NON-NLS-1$
        if (!folder.exists())
        {
            return modules;
        }

        try
        {
            collectBslFilesRecursive(folder, modules, basePath);
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting BSL modules: " + basePath, e); //$NON-NLS-1$
        }
        return modules;
    }

    private void collectBslFilesRecursive(org.eclipse.core.resources.IContainer container,
                                           List<String> modules, String basePath)
        throws Exception
    {
        for (org.eclipse.core.resources.IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                IFile file = (IFile) member;
                if (file.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    String fullPath = file.getProjectRelativePath().toString();
                    String modulePath = fullPath.startsWith("src/") //$NON-NLS-1$
                        ? fullPath.substring(4) : fullPath;
                    modules.add(modulePath);
                }
            }
            else if (member instanceof org.eclipse.core.resources.IContainer)
            {
                collectBslFilesRecursive((org.eclipse.core.resources.IContainer) member, modules, basePath);
            }
        }
    }

    /**
     * Maps a metadata type name to the collection folder in src/.
     * Delegates to {@link MetadataTypeUtils}.
     */
    private String getCollectionFolder(String typeName)
    {
        return MetadataTypeUtils.getDirectoryName(typeName);
    }

    // ========== Not-found helper ==========

    /**
     * Names the project that could hold {@code name} as a common module, when the caller asked an
     * EXTERNAL-OBJECTS project - which never has common modules of its own. Without it the answer
     * is a truthful dead end: no suggestions can come from this root, and the module the caller
     * means is almost always in the base configuration. Appends nothing for any other project kind.
     */
    private void appendExternalProjectHint(StringBuilder sb, MetadataScope scope, String name)
    {
        if (!scope.isExternalObjects())
        {
            return;
        }
        sb.append("This is an external-objects project: it holds no common modules. "); //$NON-NLS-1$
        String baseProject = scope.baseProjectName();
        if (baseProject != null)
        {
            sb.append("`").append(name).append("` may be a common module of the ") //$NON-NLS-1$ //$NON-NLS-2$
              .append("base configuration project '").append(baseProject) //$NON-NLS-1$
              .append("' - ask go_to_definition there.\n\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("It is not linked to a base configuration either, so there is no ") //$NON-NLS-1$
              .append("project here that could hold `").append(name).append("`.\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Builds a helpful not-found response with suggestions.
     * If firstPart is a recognized metadata type, shows similar objects of that type.
     * Otherwise, shows similar common modules.
     */
    private String buildNotFoundResponse(MetadataScope scope,
                                          String firstPart, String secondPart)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Symbol not found: ").append(firstPart).append(".").append(secondPart).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (MetadataTypeUtils.isMetadataTypeName(firstPart))
        {
            // firstPart is a recognized metadata type — show similar objects of that type
            String englishType = MetadataTypeUtils.toEnglishSingular(firstPart);
            sb.append("The type '").append(firstPart).append("' was recognized as '") //$NON-NLS-1$ //$NON-NLS-2$
              .append(englishType).append("', but object '").append(secondPart) //$NON-NLS-1$
              .append("' was not found.\n\n"); //$NON-NLS-1$

            List<String> similar = scope.findSimilarObjects(englishType, secondPart, 10);
            if (!similar.isEmpty())
            {
                sb.append("### Did you mean?\n\n"); //$NON-NLS-1$
                for (String name : similar)
                {
                    sb.append("- ").append(englishType).append(".").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            // A common module may be NAMED like a metadata type (a base module called Catalog),
            // and the type branch wins the dispatch - so without this the caller of
            // `Catalog.SomeMethod` hears only "no such Catalog object" and is never told that the
            // module of that name lives in the base project. Not for a standalone type: an
            // ExternalDataProcessor miss is about this project, and common modules are noise there.
            MetadataTypeUtils.MetadataTypeInfo info = MetadataTypeUtils.resolve(firstPart);
            if (info == null || !info.isStandalone())
            {
                appendExternalProjectHint(sb, scope, firstPart);
            }
            // Both directions of a type/root mismatch, in one call: the catalogue is global, so
            // this tool advertises ExternalDataProcessor to a CONFIGURATION project too - and a
            // bare "object was not found" there implies the object could exist here if only the
            // name were right. Empty string when the type does fit this root.
            String mismatch = scope.addressingHint(firstPart);
            if (!mismatch.isEmpty())
            {
                sb.append(mismatch.trim()).append("\n\n"); //$NON-NLS-1$
            }
        }
        else
        {
            // firstPart is not a metadata type — suggest similar common modules
            List<String> similarModules = scope.findSimilarObjects(
                "CommonModule", firstPart, 10); //$NON-NLS-1$
            if (!similarModules.isEmpty())
            {
                sb.append("### Similar Common Modules\n\n"); //$NON-NLS-1$
                for (String name : similarModules)
                {
                    sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            appendExternalProjectHint(sb, scope, firstPart);
        }

        // Suggest metadata types
        sb.append("### Supported Metadata Types\n\n"); //$NON-NLS-1$
        sb.append(String.join(", ", MetadataTypeUtils.getAllEnglishSingularNames())); //$NON-NLS-1$
        sb.append("\n\n"); //$NON-NLS-1$
        sb.append("Russian metadata type names are also supported "); //$NON-NLS-1$
        sb.append("(\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A, \u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442, \u0420\u0435\u0433\u0438\u0441\u0442\u0440\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439, etc.).\n\n"); //$NON-NLS-1$ // Справочник, Документ, РегистрСведений

        sb.append("**Tip:** For methods in non-common modules, use the full module path:\n"); //$NON-NLS-1$
        sb.append("`go_to_definition(symbol='MethodName', modulePath='Documents/SalesOrder/ObjectModule.bsl')`\n"); //$NON-NLS-1$

        return sb.toString();
    }

    // ========== Utility ==========

    /**
     * Scans backwards from the method keyword line to find the start of a doc-comment block.
     * BSL doc-comments are consecutive lines starting with //.
     *
     * @param allLines all file lines (0-indexed list)
     * @param methodKeywordLine 1-based line number of the method keyword
     * @return 1-based line number where the doc-comment starts
     */
    private int findDocCommentStart(List<String> allLines, int methodKeywordLine)
    {
        // Delegate to the shared ADJACENCY-policy helper (stop at first blank or
        // non-comment line) so all read-tools share one boundary rule.
        return BslModuleUtils.findDocCommentStartLine(allLines, methodKeywordLine);
    }
}

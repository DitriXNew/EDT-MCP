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
import org.eclipse.core.resources.ResourcesPlugin;
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
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Navigate to the definition of a symbol. " + //$NON-NLS-1$
               "Resolves method calls like 'CommonModuleName.MethodName' to the actual definition " + //$NON-NLS-1$
               "with source code, signature, and location. " + //$NON-NLS-1$
               "Also resolves metadata object FQNs like 'Catalog.Products'. " + //$NON-NLS-1$
               "Supports both English and Russian metadata type names " + //$NON-NLS-1$
               "(e.g., '\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041F\u0440\u0438\u0445\u043E\u0434\u043D\u0430\u044F\u041D\u0430\u043A\u043B\u0430\u0434\u043D\u0430\u044F', " + //$NON-NLS-1$ // Документ.ПриходнаяНакладная
               "'\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430'). " + //$NON-NLS-1$ // Справочник.Номенклатура
               "This is the inverse of find_references: instead of finding usages, it finds where a symbol is defined."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("symbol", //$NON-NLS-1$
                "Symbol to find definition for (required). Formats:\n" + //$NON-NLS-1$
                "- 'ModuleName.MethodName' \u2014 method in a common module (e.g. 'Common.SubjectString')\n" + //$NON-NLS-1$
                "- 'MethodName' \u2014 method in the context module (requires modulePath)\n" + //$NON-NLS-1$
                "- 'Catalog.Products' \u2014 metadata object FQN\n" + //$NON-NLS-1$
                "- '\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041F\u0440\u0438\u0445\u043E\u0434\u043D\u0430\u044F\u041D\u0430\u043A\u043B\u0430\u0434\u043D\u0430\u044F' \u2014 Russian metadata type names are also supported", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Context module path from src/ folder (e.g. 'Documents/SalesOrder/ObjectModule.bsl'). " + //$NON-NLS-1$
                "Required when symbol is an unqualified method name.") //$NON-NLS-1$
            .booleanProperty("includeSource", //$NON-NLS-1$
                "Include method source code in the response (default: true)") //$NON-NLS-1$
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
        String symbol = JsonUtils.extractStringArgument(params, "symbol"); //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String symbol = JsonUtils.extractStringArgument(params, "symbol"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String includeSourceStr = JsonUtils.extractStringArgument(params, "includeSource"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (symbol == null || symbol.isEmpty())
        {
            return "Error: symbol is required"; //$NON-NLS-1$
        }

        boolean includeSource = !"false".equalsIgnoreCase(includeSourceStr); //$NON-NLS-1$

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
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
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
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        // Normalize Russian metadata type names: "Документ.Встреча" -> "Document.Встреча"
        symbol = MetadataTypeUtils.normalizeFqn(symbol);

        // Parse symbol: split by "."
        String[] parts = symbol.split("\\.", 2); //$NON-NLS-1$

        if (parts.length == 2)
        {
            // Two-part symbol: "ModuleName.MethodName" or "Catalog.Products"
            return resolveTwoPartSymbol(project, parts[0], parts[1], includeSource);
        }
        else
        {
            // Single-part symbol: "MethodName" — needs modulePath context
            return resolveSinglePartSymbol(project, symbol, modulePath, includeSource);
        }
    }

    /**
     * Resolves a two-part symbol like "CommonModuleName.MethodName" or "Catalog.Products".
     *
     * Resolution order:
     * 1. Try as CommonModule method (most common case for AI)
     * 2. Try as metadata object FQN (Catalog.Products, Document.SalesOrder, etc.)
     */
    private String resolveTwoPartSymbol(IProject project, String firstPart, String secondPart,
                                         boolean includeSource)
    {
        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider not available"; //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Could not get configuration for project"; //$NON-NLS-1$
        }

        // 1. Try as CommonModule method: firstPart = module name, secondPart = method name
        CommonModule commonModule = findCommonModuleByName(config, firstPart);
        if (commonModule != null)
        {
            String cmModulePath = "CommonModules/" + commonModule.getName() + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            return resolveMethodInModule(project, cmModulePath, secondPart, includeSource, commonModule.getName());
        }

        // 2. Try as metadata object FQN: firstPart = type, secondPart = name
        MdObject mdObject = findMdObjectByFqn(config, firstPart, secondPart);
        if (mdObject != null)
        {
            return formatMetadataDefinition(project, config, mdObject, firstPart, secondPart);
        }

        // 3. Nothing found — provide suggestions
        return buildNotFoundResponse(project, config, firstPart, secondPart);
    }

    /**
     * Resolves a single-part symbol (method name) within the context module.
     */
    private String resolveSinglePartSymbol(IProject project, String methodName, String modulePath,
                                            boolean includeSource)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required when symbol is an unqualified method name. " + //$NON-NLS-1$
                   "Provide the context module path (e.g. 'CommonModules/MyModule/Module.bsl') " + //$NON-NLS-1$
                   "or use qualified name 'ModuleName.MethodName'."; //$NON-NLS-1$
        }

        return resolveMethodInModule(project, modulePath, methodName, includeSource, null);
    }

    // ========== Method resolution ==========

    /**
     * Resolves a method definition within a specific BSL module.
     * Returns full definition info including source code.
     */
    private String resolveMethodInModule(IProject project, String modulePath, String methodName,
                                          boolean includeSource, String qualifiedPrefix)
    {
        // Load module via EMF
        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            // EMF not available — try text-based fallback
            return resolveMethodViaText(project, modulePath, methodName, includeSource, qualifiedPrefix);
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        return formatMethodDefinition(project, modulePath, method, includeSource, qualifiedPrefix);
    }

    /**
     * Formats method definition result with optional source code.
     */
    private String formatMethodDefinition(IProject project, String modulePath, Method method,
                                           boolean includeSource, String qualifiedPrefix)
    {
        int startLine = BslModuleUtils.getStartLine(method);
        int endLine = BslModuleUtils.getEndLine(method);
        String typeStr = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$
        String signature = BslModuleUtils.buildSignature(method);

        // Build display name
        String displayName = qualifiedPrefix != null
            ? qualifiedPrefix + "." + method.getName() //$NON-NLS-1$
            : method.getName();

        StringBuilder sb = new StringBuilder();
        sb.append("## Definition: ").append(displayName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Type:** ").append(typeStr); //$NON-NLS-1$
        if (method.isExport())
        {
            sb.append(" (Export)"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
        sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Lines:** ").append(startLine).append(" - ").append(endLine).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Signature:** `").append(signature).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (includeSource)
        {
            // Read source from file (includes doc-comments)
            IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
            List<String> allLines = null;
            try
            {
                allLines = BslModuleUtils.readFileLines(file);
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to read file for source: " + e.getMessage()); //$NON-NLS-1$
            }

            if (allLines != null)
            {
                // Include doc-comment block above the method
                int docStart = findDocCommentStart(allLines, startLine);
                int from = Math.max(1, docStart);
                int to = Math.min(allLines.size(), endLine);

                sb.append("\n### Source\n\n"); //$NON-NLS-1$
                sb.append("```bsl\n"); //$NON-NLS-1$
                for (int i = from - 1; i < to; i++)
                {
                    sb.append(String.format("%d: %s\n", i + 1, allLines.get(i))); //$NON-NLS-1$
                }
                sb.append("```\n"); //$NON-NLS-1$
            }
            else
            {
                // Fallback: use EMF getText()
                String sourceText = BslModuleUtils.getSourceText(method);
                if (sourceText != null)
                {
                    sb.append("\n### Source\n\n"); //$NON-NLS-1$
                    sb.append("```bsl\n"); //$NON-NLS-1$
                    String[] lines = sourceText.split("\n", -1); //$NON-NLS-1$
                    for (int i = 0; i < lines.length; i++)
                    {
                        sb.append(String.format("%d: %s\n", startLine + i, lines[i])); //$NON-NLS-1$
                    }
                    sb.append("```\n"); //$NON-NLS-1$
                }
            }
        }

        return sb.toString();
    }

    /**
     * Fallback: resolves method definition via text-based parsing.
     * Used when EMF model is not available.
     */
    private String resolveMethodViaText(IProject project, String modulePath, String methodName,
                                         boolean includeSource, String qualifiedPrefix)
    {
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (!file.exists())
        {
            return "Error: Module not found: src/" + modulePath; //$NON-NLS-1$
        }

        try
        {
            List<String> allLines = BslModuleUtils.readFileLines(file);

            int methodStart = -1;
            int methodEnd = -1;
            String matchedName = null;
            boolean isFunction = false;
            List<String> allMethodNames = new ArrayList<>();

            for (int i = 0; i < allLines.size(); i++)
            {
                java.util.regex.Matcher startMatcher = BslModuleUtils.METHOD_START_PATTERN.matcher(allLines.get(i));
                if (startMatcher.find())
                {
                    String foundName = startMatcher.group(1);
                    allMethodNames.add(foundName);

                    if (foundName.equalsIgnoreCase(methodName))
                    {
                        methodStart = i;
                        matchedName = foundName;
                        isFunction = BslModuleUtils.FUNC_KEYWORD_PATTERN.matcher(allLines.get(i)).find();
                    }
                }

                if (methodStart >= 0 && methodEnd < 0)
                {
                    java.util.regex.Matcher endMatcher = BslModuleUtils.METHOD_END_PATTERN.matcher(allLines.get(i));
                    if (endMatcher.find())
                    {
                        methodEnd = i;
                        break;
                    }
                }
            }

            if (methodStart < 0)
            {
                StringBuilder sb = new StringBuilder();
                sb.append("Error: Method '").append(methodName).append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                sb.append("**Available methods** (").append(allMethodNames.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String name : allMethodNames)
                {
                    sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return sb.toString();
            }

            if (methodEnd < 0)
            {
                methodEnd = allLines.size() - 1;
            }

            // Include doc-comment
            int docStart = findDocCommentStart(allLines, methodStart + 1) - 1;
            methodStart = docStart;

            String displayName = qualifiedPrefix != null
                ? qualifiedPrefix + "." + matchedName //$NON-NLS-1$
                : matchedName;
            String typeStr = isFunction ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$

            StringBuilder sb = new StringBuilder();
            sb.append("## Definition: ").append(displayName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Type:** ").append(typeStr).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Lines:** ").append(methodStart + 1).append(" - ").append(methodEnd + 1).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("*Note: resolved via text fallback (EMF model was not available)*\n"); //$NON-NLS-1$

            if (includeSource)
            {
                sb.append("\n### Source\n\n"); //$NON-NLS-1$
                sb.append("```bsl\n"); //$NON-NLS-1$
                for (int i = methodStart; i <= methodEnd; i++)
                {
                    sb.append(String.format("%d: %s\n", i + 1, allLines.get(i))); //$NON-NLS-1$
                }
                sb.append("```\n"); //$NON-NLS-1$
            }

            return sb.toString();
        }
        catch (Exception e)
        {
            return "Error reading file: " + e.getMessage(); //$NON-NLS-1$
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
     * Finds a metadata object by type name and object name.
     * Delegates to {@link MetadataTypeUtils} which supports English, Russian,
     * singular and plural forms.
     */
    private MdObject findMdObjectByFqn(Configuration config, String typeName, String objectName)
    {
        return MetadataTypeUtils.findObject(config, typeName, objectName);
    }

    /**
     * Formats a metadata object definition result.
     * Includes the object type, available modules, and module paths.
     */
    private String formatMetadataDefinition(IProject project, Configuration config,
                                             MdObject mdObject, String typeName, String objectName)
    {
        String fqn = typeName + "." + objectName; //$NON-NLS-1$
        String collectionFolder = getCollectionFolder(typeName);

        StringBuilder sb = new StringBuilder();
        sb.append("## Definition: ").append(fqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Type:** Metadata Object (").append(typeName).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Name:** ").append(mdObject.getName()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // List available BSL modules for this object
        if (collectionFolder != null)
        {
            String basePath = collectionFolder + "/" + mdObject.getName(); //$NON-NLS-1$
            List<String> modules = collectAvailableBslModules(project, basePath);

            if (!modules.isEmpty())
            {
                sb.append("\n### Available Modules\n\n"); //$NON-NLS-1$
                for (String modPath : modules)
                {
                    sb.append("- ").append(modPath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                sb.append("\nNo BSL modules found for this object.\n"); //$NON-NLS-1$
            }
        }

        sb.append("\n*Use `get_metadata_details` for full object properties, " + //$NON-NLS-1$
                  "or `read_module_source`/`read_method_source` to read specific modules.*\n"); //$NON-NLS-1$

        return sb.toString();
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
     * Builds a helpful not-found response with suggestions.
     * If firstPart is a recognized metadata type, shows similar objects of that type.
     * Otherwise, shows similar common modules.
     */
    private String buildNotFoundResponse(IProject project, Configuration config,
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

            List<String> similar = MetadataTypeUtils.findSimilarObjects(config, englishType, secondPart, 10);
            if (!similar.isEmpty())
            {
                sb.append("### Did you mean?\n\n"); //$NON-NLS-1$
                for (String name : similar)
                {
                    sb.append("- ").append(englishType).append(".").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
        }
        else
        {
            // firstPart is not a metadata type — suggest similar common modules
            List<String> similarModules = MetadataTypeUtils.findSimilarObjects(
                config, "CommonModule", firstPart, 10); //$NON-NLS-1$
            if (!similarModules.isEmpty())
            {
                sb.append("### Similar Common Modules\n\n"); //$NON-NLS-1$
                for (String name : similarModules)
                {
                    sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
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
        if (methodKeywordLine <= 1)
        {
            return methodKeywordLine;
        }

        int idx = methodKeywordLine - 2; // 0-indexed, line before the keyword
        while (idx >= 0 && allLines.get(idx).trim().startsWith("//")) //$NON-NLS-1$
        {
            idx--;
        }

        int docStart = idx + 2; // convert back to 1-based
        return docStart < methodKeywordLine ? docStart : methodKeywordLine;
    }
}

/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResourceFactory;

import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for BSL module operations: file resolution, source reading,
 * AST loading, method extraction, and project scanning.
 */
@SuppressWarnings("restriction")
public final class BslModuleUtils
{
    private BslModuleUtils()
    {
        // utility class
    }

    /** URI for BSL resource service provider lookup */
    private static final URI BSL_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$

    // ========== Module type mapping ==========

    /** Maps metadata type singular (lowercase) to filesystem folder name */
    private static final Map<String, String> TYPE_TO_FOLDER = new LinkedHashMap<>();

    /** Reverse map: folder name (lowercase) to metadata type singular */
    private static final Map<String, String> FOLDER_TO_TYPE = new LinkedHashMap<>();

    static
    {
        TYPE_TO_FOLDER.put("commonmodule", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("document", "Documents"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("catalog", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("informationregister", "InformationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("accumulationregister", "AccumulationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("accountingregister", "AccountingRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("calculationregister", "CalculationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("report", "Reports"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("dataprocessor", "DataProcessors"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("exchangeplan", "ExchangePlans"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("businessprocess", "BusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("task", "Tasks"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("constant", "Constants"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("enum", "Enums"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("webservice", "WebServices"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("httpservice", "HTTPServices"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("chartofcharacteristictypes", "ChartsOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("chartofaccounts", "ChartsOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("chartofcalculationtypes", "ChartsOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("commandgroup", "CommandGroups"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("commoncommand", "CommonCommands"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_FOLDER.put("commonform", "CommonForms"); //$NON-NLS-1$ //$NON-NLS-2$

        TYPE_TO_FOLDER.forEach((type, folder) -> FOLDER_TO_TYPE.put(folder.toLowerCase(), type));
    }

    /**
     * Module type with its relative file path within the metadata object folder.
     */
    public enum ModuleType
    {
        /** CommonModule's only module (CommonModules/Name/Module.bsl) */
        MODULE("Module.bsl", "Module"), //$NON-NLS-1$ //$NON-NLS-2$
        /** Object module (Documents/Name/Ext/ObjectModule.bsl) */
        OBJECT_MODULE("Ext/ObjectModule.bsl", "ObjectModule"), //$NON-NLS-1$ //$NON-NLS-2$
        /** Manager module (Documents/Name/Ext/ManagerModule.bsl) */
        MANAGER_MODULE("Ext/ManagerModule.bsl", "ManagerModule"), //$NON-NLS-1$ //$NON-NLS-2$
        /** RecordSet module (InformationRegisters/Name/Ext/RecordSetModule.bsl) */
        RECORDSET_MODULE("Ext/RecordSetModule.bsl", "RecordSetModule"), //$NON-NLS-1$ //$NON-NLS-2$
        /** ValueManager module for constants (Constants/Name/Ext/ValueManagerModule.bsl) */
        VALUE_MANAGER_MODULE("Ext/ValueManagerModule.bsl", "ValueManagerModule"), //$NON-NLS-1$ //$NON-NLS-2$
        /** Command module (CommonCommands/Name/Ext/CommandModule.bsl) */
        COMMAND_MODULE("Ext/CommandModule.bsl", "CommandModule"), //$NON-NLS-1$ //$NON-NLS-2$
        /** Form module - path is dynamic: Forms/FormName/Ext/Form/Module.bsl */
        FORM_MODULE(null, "FormModule"); //$NON-NLS-1$

        /** Relative path from metadata object folder, null for dynamic paths */
        public final String relativePath;
        /** Display name */
        public final String displayName;

        ModuleType(String relativePath, String displayName)
        {
            this.relativePath = relativePath;
            this.displayName = displayName;
        }
    }

    // ========== Project & file resolution ==========

    /**
     * Resolves an IProject by name from the workspace.
     *
     * @param projectName the project name
     * @return IProject or null if not found/closed
     */
    public static IProject resolveProject(String projectName)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen())
        {
            return null;
        }
        return project;
    }

    /**
     * Resolves a BSL module IFile from a path relative to src/.
     *
     * @param project the project
     * @param modulePath path relative to src/ (e.g. "CommonModules/MyModule/Module.bsl")
     * @return IFile or null if not found
     */
    public static IFile resolveModuleFile(IProject project, String modulePath)
    {
        IPath relativePath = new Path("src").append(modulePath); //$NON-NLS-1$
        IFile file = project.getFile(relativePath);
        return (file != null && file.exists()) ? file : null;
    }

    /**
     * Gets the module path relative to src/ from an IFile.
     *
     * @param project the project
     * @param file the BSL file
     * @return path relative to src/
     */
    public static String getRelativeModulePath(IProject project, IFile file)
    {
        IPath srcPath = project.getFolder("src").getFullPath(); //$NON-NLS-1$
        IPath filePath = file.getFullPath();
        if (srcPath.isPrefixOf(filePath))
        {
            return filePath.makeRelativeTo(srcPath).toString();
        }
        return filePath.toString();
    }

    // ========== FQN <-> path mapping ==========

    /**
     * Gets the filesystem folder name for a metadata type.
     *
     * @param metadataType singular metadata type (e.g. "Document", "Catalog")
     * @return folder name (e.g. "Documents", "Catalogs") or null if unknown
     */
    public static String getFolder(String metadataType)
    {
        if (metadataType == null)
        {
            return null;
        }
        return TYPE_TO_FOLDER.get(metadataType.toLowerCase());
    }

    /**
     * Gets the metadata type from a filesystem folder name.
     *
     * @param folderName folder name (e.g. "Documents")
     * @return singular metadata type (e.g. "document") or null if unknown
     */
    public static String getTypeFromFolder(String folderName)
    {
        if (folderName == null)
        {
            return null;
        }
        return FOLDER_TO_TYPE.get(folderName.toLowerCase());
    }

    /**
     * Parses a module file path relative to src/ into metadata FQN and module type.
     *
     * @param modulePath e.g. "Documents/SalesOrder/Ext/ObjectModule.bsl"
     * @return parsed info or null if path cannot be parsed
     */
    public static ModulePathInfo parseModulePath(String modulePath)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return null;
        }

        String[] parts = modulePath.replace("\\", "/").split("/"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (parts.length < 2)
        {
            return null;
        }

        String folderName = parts[0];
        String objectName = parts[1];
        String metadataType = FOLDER_TO_TYPE.get(folderName.toLowerCase());

        if (metadataType == null)
        {
            return null;
        }

        // Capitalize type for FQN (e.g. "document" -> "Document")
        String capitalizedType = metadataType.substring(0, 1).toUpperCase() + metadataType.substring(1);
        // Handle multi-word types properly
        String fqnType = getFqnType(capitalizedType);
        String ownerFqn = fqnType + "." + objectName; //$NON-NLS-1$

        // Determine module type from path
        ModuleType moduleType = determineModuleType(parts);
        String formName = null;

        if (moduleType == ModuleType.FORM_MODULE && parts.length >= 3)
        {
            formName = parts[2]; // Forms/<FormName>/...
        }

        return new ModulePathInfo(ownerFqn, moduleType, formName);
    }

    /**
     * Converts internal type name to proper FQN format.
     */
    private static String getFqnType(String type)
    {
        // Map internal lowercase types to proper casing
        switch (type.toLowerCase())
        {
            case "commonmodule":
                return "CommonModule"; //$NON-NLS-1$
            case "informationregister":
                return "InformationRegister"; //$NON-NLS-1$
            case "accumulationregister":
                return "AccumulationRegister"; //$NON-NLS-1$
            case "accountingregister":
                return "AccountingRegister"; //$NON-NLS-1$
            case "calculationregister":
                return "CalculationRegister"; //$NON-NLS-1$
            case "dataprocessor":
                return "DataProcessor"; //$NON-NLS-1$
            case "exchangeplan":
                return "ExchangePlan"; //$NON-NLS-1$
            case "businessprocess":
                return "BusinessProcess"; //$NON-NLS-1$
            case "chartofcharacteristictypes":
                return "ChartOfCharacteristicTypes"; //$NON-NLS-1$
            case "chartofaccounts":
                return "ChartOfAccounts"; //$NON-NLS-1$
            case "chartofcalculationtypes":
                return "ChartOfCalculationTypes"; //$NON-NLS-1$
            case "commoncommand":
                return "CommonCommand"; //$NON-NLS-1$
            case "commonform":
                return "CommonForm"; //$NON-NLS-1$
            case "commandgroup":
                return "CommandGroup"; //$NON-NLS-1$
            case "httpservice":
                return "HTTPService"; //$NON-NLS-1$
            case "webservice":
                return "WebService"; //$NON-NLS-1$
            case "valuemanagermodule":
                return "ValueManagerModule"; //$NON-NLS-1$
            default:
                // For simple types, capitalize first letter
                return type.substring(0, 1).toUpperCase() + type.substring(1);
        }
    }

    /**
     * Determines module type from path segments.
     */
    private static ModuleType determineModuleType(String[] parts)
    {
        // CommonModules/Name/Module.bsl
        if (parts.length == 3 && "Module.bsl".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
        {
            return ModuleType.MODULE;
        }

        // Check for Forms/FormName/Ext/Form/Module.bsl
        if (parts.length >= 3 && "Forms".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
        {
            return ModuleType.FORM_MODULE;
        }

        // Type/Name/Ext/XXXModule.bsl
        if (parts.length >= 4 && "Ext".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
        {
            String fileName = parts[3].toLowerCase();
            if (fileName.equals("objectmodule.bsl")) return ModuleType.OBJECT_MODULE; //$NON-NLS-1$
            if (fileName.equals("managermodule.bsl")) return ModuleType.MANAGER_MODULE; //$NON-NLS-1$
            if (fileName.equals("recordsetmodule.bsl")) return ModuleType.RECORDSET_MODULE; //$NON-NLS-1$
            if (fileName.equals("valuemanagermodule.bsl")) return ModuleType.VALUE_MANAGER_MODULE; //$NON-NLS-1$
            if (fileName.equals("commandmodule.bsl")) return ModuleType.COMMAND_MODULE; //$NON-NLS-1$
        }

        // Nested Forms: Type/Name/Forms/FormName/Ext/Form/Module.bsl
        if (parts.length >= 5 && "Forms".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
        {
            return ModuleType.FORM_MODULE;
        }

        return ModuleType.MODULE; // fallback
    }

    // ========== File content reading ==========

    /**
     * Reads content of an IFile, optionally restricted to a line range.
     * Returns text with line numbers prepended.
     *
     * @param file the IFile to read
     * @param startLine 1-based start line (0 or negative = from beginning)
     * @param endLine 1-based end line (0 or negative = to end)
     * @return formatted text with line numbers, or null on error
     */
    public static String readFileContent(IFile file, int startLine, int endLine)
    {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), file.getCharset())))
        {
            StringBuilder sb = new StringBuilder();
            String line;
            int lineNum = 0;

            while ((line = reader.readLine()) != null)
            {
                lineNum++;
                if (startLine > 0 && lineNum < startLine)
                {
                    continue;
                }
                if (endLine > 0 && lineNum > endLine)
                {
                    break;
                }
                sb.append(String.format("%5d | %s%n", lineNum, line)); //$NON-NLS-1$
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Error reading file content", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Counts lines in an IFile.
     *
     * @param file the file
     * @return line count, or -1 on error
     */
    public static int countLines(IFile file)
    {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), file.getCharset())))
        {
            int count = 0;
            while (reader.readLine() != null)
            {
                count++;
            }
            return count;
        }
        catch (Exception e)
        {
            Activator.logError("Error counting lines", e); //$NON-NLS-1$
            return -1;
        }
    }

    // ========== BSL Xtext model loading ==========

    /**
     * Loads a BSL module's Xtext resource and returns the Module AST root.
     * Uses the same resource loading pattern as FindReferencesTool.
     *
     * @param file the .bsl IFile
     * @return the Module AST or null on failure
     */
    public static Module loadBslModule(IFile file)
    {
        try
        {
            URI fileUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);

            ResourceSet resourceSet = new ResourceSetImpl();
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_URI);
            if (rsp != null)
            {
                XtextResourceFactory factory = rsp.get(XtextResourceFactory.class);
                if (factory != null)
                {
                    resourceSet.getResourceFactoryRegistry()
                        .getExtensionToFactoryMap().put("bsl", factory); //$NON-NLS-1$
                }
            }

            Resource resource = resourceSet.getResource(fileUri, true);
            if (resource != null && !resource.getContents().isEmpty())
            {
                EObject root = resource.getContents().get(0);
                if (root instanceof Module)
                {
                    return (Module) root;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error loading BSL module: " + file.getFullPath(), e); //$NON-NLS-1$
        }
        return null;
    }

    // ========== Method extraction from AST ==========

    /**
     * Extracts all methods from a loaded BSL Module.
     *
     * @param module the BSL Module AST root
     * @return list of MethodInfo, ordered by start line
     */
    public static List<MethodInfo> extractMethods(Module module)
    {
        List<MethodInfo> methods = new ArrayList<>();

        EList<Method> allMethods = module.allMethods();
        if (allMethods == null)
        {
            return methods;
        }

        for (Method method : allMethods)
        {
            String name = method.getName();
            boolean isFunction = method instanceof Function;
            boolean isExport = method.isExport();

            // Get line numbers from Xtext node model
            INode node = NodeModelUtils.findActualNodeFor(method);
            int startLine = node != null ? node.getStartLine() : 0;
            int endLine = node != null ? node.getEndLine() : 0;

            // Build signature
            String signature = buildMethodSignature(method, isFunction);

            // Extract pragmas
            String pragmas = extractPragmas(method);

            // Find enclosing region
            String regionName = findEnclosingRegion(method);

            methods.add(new MethodInfo(name, isFunction, isExport,
                startLine, endLine, signature, regionName, pragmas));
        }

        methods.sort(Comparator.comparingInt(m -> m.startLine));
        return methods;
    }

    /**
     * Builds the textual signature of a Method.
     */
    private static String buildMethodSignature(Method method, boolean isFunction)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(isFunction ? "Function " : "Procedure "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(method.getName());
        sb.append("("); //$NON-NLS-1$

        EList<FormalParam> params = method.getFormalParams();
        if (params != null)
        {
            for (int i = 0; i < params.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                FormalParam param = params.get(i);
                if (param.isByValue())
                {
                    sb.append("Val "); //$NON-NLS-1$
                }
                sb.append(param.getName());
                if (param.getDefaultValue() != null)
                {
                    sb.append(" = ..."); //$NON-NLS-1$
                }
            }
        }

        sb.append(")"); //$NON-NLS-1$
        if (method.isExport())
        {
            sb.append(" Export"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Extracts pragma annotations from a method.
     */
    private static String extractPragmas(Method method)
    {
        EList<Pragma> pragmas = method.getPragmas();
        if (pragmas == null || pragmas.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pragmas.size(); i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            Pragma pragma = pragmas.get(i);
            String symbol = pragma.getSymbol();
            if (symbol != null && !symbol.isEmpty())
            {
                sb.append("&").append(symbol); //$NON-NLS-1$
            }
        }
        return sb.toString();
    }

    /**
     * Finds the enclosing #Region name for a method by walking up the AST.
     */
    private static String findEnclosingRegion(Method method)
    {
        EObject container = method.eContainer();
        while (container != null)
        {
            if (container instanceof RegionPreprocessor)
            {
                return ((RegionPreprocessor) container).getName();
            }
            if (container instanceof Module)
            {
                break; // reached module root, no region
            }
            container = container.eContainer();
        }
        return null;
    }

    // ========== BSL file scanning ==========

    /**
     * Finds all .bsl files in a project's src/ directory.
     *
     * @param project the project
     * @return list of IFile objects
     */
    public static List<IFile> findAllBslFiles(IProject project)
    {
        List<IFile> bslFiles = new ArrayList<>();
        IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        if (!srcFolder.exists())
        {
            return bslFiles;
        }

        try
        {
            srcFolder.accept(resource -> {
                if (resource instanceof IFile
                    && "bsl".equalsIgnoreCase(((IFile) resource).getFileExtension())) //$NON-NLS-1$
                {
                    bslFiles.add((IFile) resource);
                }
                return true;
            });
        }
        catch (CoreException e)
        {
            Activator.logError("Error scanning BSL files", e); //$NON-NLS-1$
        }
        return bslFiles;
    }

    /**
     * Finds all .bsl files filtered by metadata type folder.
     *
     * @param project the project
     * @param metadataType metadata type filter (e.g. "Documents", "CommonModules")
     * @return list of IFile objects
     */
    public static List<IFile> findBslFilesByType(IProject project, String metadataType)
    {
        List<IFile> bslFiles = new ArrayList<>();

        // Try direct folder name first
        IFolder typeFolder = project.getFolder("src/" + metadataType); //$NON-NLS-1$

        // If not found, try mapping from type name
        if (!typeFolder.exists())
        {
            String folderName = getFolder(metadataType);
            if (folderName != null)
            {
                typeFolder = project.getFolder("src/" + folderName); //$NON-NLS-1$
            }
        }

        if (!typeFolder.exists())
        {
            return bslFiles;
        }

        try
        {
            typeFolder.accept(resource -> {
                if (resource instanceof IFile
                    && "bsl".equalsIgnoreCase(((IFile) resource).getFileExtension())) //$NON-NLS-1$
                {
                    bslFiles.add((IFile) resource);
                }
                return true;
            });
        }
        catch (CoreException e)
        {
            Activator.logError("Error scanning BSL files by type", e); //$NON-NLS-1$
        }
        return bslFiles;
    }

    /**
     * Finds all .bsl module files for a specific metadata object.
     *
     * @param project the project
     * @param objectFqn FQN like "Document.SalesOrder"
     * @return list of ModuleInfo objects
     */
    public static List<ModuleInfo> findObjectModules(IProject project, String objectFqn)
    {
        List<ModuleInfo> modules = new ArrayList<>();

        if (objectFqn == null || objectFqn.isEmpty())
        {
            return modules;
        }

        String[] parts = objectFqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return modules;
        }

        String mdType = parts[0];
        String mdName = parts[1];

        String folderName = getFolder(mdType);
        if (folderName == null)
        {
            return modules;
        }

        String basePath = folderName + "/" + mdName; //$NON-NLS-1$
        IFolder objectFolder = project.getFolder("src/" + basePath); //$NON-NLS-1$
        if (!objectFolder.exists())
        {
            return modules;
        }

        // Check standard module files
        checkAndAddModule(project, modules, basePath, ModuleType.MODULE, objectFqn);
        checkAndAddModule(project, modules, basePath, ModuleType.OBJECT_MODULE, objectFqn);
        checkAndAddModule(project, modules, basePath, ModuleType.MANAGER_MODULE, objectFqn);
        checkAndAddModule(project, modules, basePath, ModuleType.RECORDSET_MODULE, objectFqn);
        checkAndAddModule(project, modules, basePath, ModuleType.VALUE_MANAGER_MODULE, objectFqn);
        checkAndAddModule(project, modules, basePath, ModuleType.COMMAND_MODULE, objectFqn);

        // Scan for form modules: Forms/*/Ext/Form/Module.bsl
        IFolder formsFolder = project.getFolder("src/" + basePath + "/Forms"); //$NON-NLS-1$ //$NON-NLS-2$
        if (formsFolder.exists())
        {
            try
            {
                IResource[] formFolders = formsFolder.members();
                for (IResource formResource : formFolders)
                {
                    if (formResource instanceof IFolder)
                    {
                        String formName = formResource.getName();
                        String formModulePath = basePath + "/Forms/" + formName + "/Ext/Form/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                        IFile formFile = resolveModuleFile(project, formModulePath);
                        if (formFile != null)
                        {
                            modules.add(new ModuleInfo(ModuleType.FORM_MODULE, formModulePath,
                                objectFqn, formName));
                        }
                    }
                }
            }
            catch (CoreException e)
            {
                Activator.logError("Error scanning form modules", e); //$NON-NLS-1$
            }
        }

        return modules;
    }

    /**
     * Checks if a standard module file exists and adds it to the list.
     */
    private static void checkAndAddModule(IProject project, List<ModuleInfo> modules,
        String basePath, ModuleType type, String ownerFqn)
    {
        if (type.relativePath == null)
        {
            return; // dynamic path (form modules handled separately)
        }

        String modulePath = basePath + "/" + type.relativePath; //$NON-NLS-1$
        IFile file = resolveModuleFile(project, modulePath);
        if (file != null)
        {
            modules.add(new ModuleInfo(type, modulePath, ownerFqn, null));
        }
    }

    // ========== Data classes ==========

    /**
     * Holds metadata about a BSL module file.
     */
    public static class ModuleInfo
    {
        public final ModuleType type;
        public final String relativePath;
        public final String ownerFqn;
        public final String formName;

        public ModuleInfo(ModuleType type, String relativePath, String ownerFqn, String formName)
        {
            this.type = type;
            this.relativePath = relativePath;
            this.ownerFqn = ownerFqn;
            this.formName = formName;
        }
    }

    /**
     * Result of parsing a module file path.
     */
    public static class ModulePathInfo
    {
        public final String ownerFqn;
        public final ModuleType moduleType;
        public final String formName;

        public ModulePathInfo(String ownerFqn, ModuleType moduleType, String formName)
        {
            this.ownerFqn = ownerFqn;
            this.moduleType = moduleType;
            this.formName = formName;
        }
    }

    /**
     * Holds method metadata extracted from BSL AST.
     */
    public static class MethodInfo
    {
        public final String name;
        public final boolean isFunction;
        public final boolean isExport;
        public final int startLine;
        public final int endLine;
        public final String signature;
        public final String regionName;
        public final String pragmas;

        public MethodInfo(String name, boolean isFunction, boolean isExport,
            int startLine, int endLine, String signature,
            String regionName, String pragmas)
        {
            this.name = name;
            this.isFunction = isFunction;
            this.isExport = isExport;
            this.startLine = startLine;
            this.endLine = endLine;
            this.signature = signature;
            this.regionName = regionName;
            this.pragmas = pragmas;
        }
    }
}

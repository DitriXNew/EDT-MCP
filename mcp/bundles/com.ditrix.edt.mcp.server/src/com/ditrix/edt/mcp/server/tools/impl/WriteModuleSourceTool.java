/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to write BSL source code to 1C metadata object modules.
 * Supports modes: replace, append, insertBefore, insertAfter, replaceLines.
 * Optionally validates BSL syntax (balanced block keywords) before writing.
 * Can resolve module path from objectName + moduleType.
 */
public class WriteModuleSourceTool implements IMcpTool
{
    public static final String NAME = "write_module_source"; //$NON-NLS-1$

    private static final String MODE_REPLACE = "replace"; //$NON-NLS-1$
    private static final String MODE_APPEND = "append"; //$NON-NLS-1$
    private static final String MODE_INSERT_BEFORE = "insertBefore"; //$NON-NLS-1$
    private static final String MODE_INSERT_AFTER = "insertAfter"; //$NON-NLS-1$
    private static final String MODE_REPLACE_LINES = "replaceLines"; //$NON-NLS-1$

    /** Maximum source length to prevent accidental huge writes */
    private static final int MAX_SOURCE_LENGTH = 500_000;

    /** UTF-8 BOM bytes */
    private static final byte[] UTF8_BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Write BSL source code to 1C metadata object modules. " + //$NON-NLS-1$
            "Modes: replace (replace all, default), append (add to end), " + //$NON-NLS-1$
            "insertBefore/insertAfter (insert before/after line), " + //$NON-NLS-1$
            "replaceLines (replace line range from lineFrom to lineTo). " + //$NON-NLS-1$
            "Specify modulePath or objectName + moduleType. " + //$NON-NLS-1$
            "Automatically checks BSL syntax (balanced Procedure/EndProcedure, " + //$NON-NLS-1$
            "Function/EndFunction, If/EndIf, etc.) before writing — " + //$NON-NLS-1$
            "blocks write on errors. Pass skipSyntaxCheck=true to force."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to module from src/ folder, e.g. " + //$NON-NLS-1$
                "'Documents/MyDoc/ObjectModule.bsl' or " + //$NON-NLS-1$
                "'CommonModules/MyModule/Module.bsl'. " + //$NON-NLS-1$
                "Alternative: use objectName + moduleType.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Full object name, e.g. 'Document.MyDoc', " + //$NON-NLS-1$
                "'DataProcessor.MyProcessor'. " + //$NON-NLS-1$
                "Supports Russian names (e.g. 'Документ.МойДок'). " + //$NON-NLS-1$
                "Alternative to modulePath.") //$NON-NLS-1$
            .stringProperty("moduleType", //$NON-NLS-1$
                "Module type (used with objectName): ObjectModule (default), " + //$NON-NLS-1$
                "ManagerModule, FormModule, CommandModule, RecordSetModule.") //$NON-NLS-1$
            .stringProperty("source", //$NON-NLS-1$
                "BSL source code to write (required). " + //$NON-NLS-1$
                "For replace: complete module content. " + //$NON-NLS-1$
                "For append/insert: code to add.", true) //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "Write mode: 'replace' (replace entire file, default), " + //$NON-NLS-1$
                "'append' (add to end), 'insertBefore' (insert before line), " + //$NON-NLS-1$
                "'insertAfter' (insert after line), " + //$NON-NLS-1$
                "'replaceLines' (replace lines from lineFrom to lineTo).") //$NON-NLS-1$
            .integerProperty("line", //$NON-NLS-1$
                "Line number (1-based) for insertBefore/insertAfter modes.") //$NON-NLS-1$
            .integerProperty("lineFrom", //$NON-NLS-1$
                "Start line (1-based, inclusive) for replaceLines mode.") //$NON-NLS-1$
            .integerProperty("lineTo", //$NON-NLS-1$
                "End line (1-based, inclusive) for replaceLines mode.") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "Form name, required when moduleType=FormModule " + //$NON-NLS-1$
                "(e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Command name, required when moduleType=CommandModule " + //$NON-NLS-1$
                "(e.g. 'FillByTemplate').") //$NON-NLS-1$
            .booleanProperty("skipSyntaxCheck", //$NON-NLS-1$
                "Skip BSL syntax validation (default: false). " + //$NON-NLS-1$
                "By default, checks balanced Procedure/EndProcedure, " + //$NON-NLS-1$
                "Function/EndFunction, If/EndIf, While/EndDo, " + //$NON-NLS-1$
                "For/EndDo, Try/EndTry. Set true to force write.") //$NON-NLS-1$
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
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "write-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "write-module-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // 1. Extract parameters
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
        String source = JsonUtils.extractStringArgument(params, "source"); //$NON-NLS-1$
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        int line = JsonUtils.extractIntArgument(params, "line", -1); //$NON-NLS-1$
        int lineFrom = JsonUtils.extractIntArgument(params, "lineFrom", -1); //$NON-NLS-1$
        int lineTo = JsonUtils.extractIntArgument(params, "lineTo", -1); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        boolean skipSyntaxCheck = JsonUtils.extractBooleanArgument(params, "skipSyntaxCheck", false); //$NON-NLS-1$

        // 2. Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (source == null)
        {
            return "Error: source is required"; //$NON-NLS-1$
        }
        if (source.length() > MAX_SOURCE_LENGTH)
        {
            return "Error: source exceeds maximum allowed length (" + MAX_SOURCE_LENGTH + " characters)"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Default mode
        if (mode == null || mode.isEmpty())
        {
            mode = MODE_REPLACE;
        }

        // Validate mode
        if (!MODE_REPLACE.equals(mode) && !MODE_APPEND.equals(mode)
            && !MODE_INSERT_BEFORE.equals(mode) && !MODE_INSERT_AFTER.equals(mode)
            && !MODE_REPLACE_LINES.equals(mode))
        {
            return "Error: invalid mode '" + mode + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Allowed: replace, append, insertBefore, insertAfter, replaceLines"; //$NON-NLS-1$
        }

        // 3. Resolve modulePath
        if (modulePath == null || modulePath.isEmpty())
        {
            if (objectName == null || objectName.isEmpty())
            {
                return "Error: either modulePath or objectName is required"; //$NON-NLS-1$
            }
            String resolved = resolveModulePath(objectName, moduleType, formName, commandName);
            if (resolved.startsWith("Error:")) //$NON-NLS-1$
            {
                return resolved;
            }
            modulePath = resolved;
        }

        // Validate modulePath: prevent path traversal
        if (modulePath.contains("..")) //$NON-NLS-1$
        {
            return "Error: modulePath must not contain '..'"; //$NON-NLS-1$
        }

        // Validate modulePath: only .bsl files allowed
        if (!modulePath.endsWith(".bsl")) //$NON-NLS-1$
        {
            return "Error: only .bsl module files can be written"; //$NON-NLS-1$
        }

        // 4. Validate project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        // 5. Get file
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        boolean fileExists = file.exists();

        // For non-replace modes, file must exist
        if (!fileExists && !MODE_REPLACE.equals(mode))
        {
            return "Error: File not found: src/" + modulePath + //$NON-NLS-1$
                ". Only 'replace' mode can create new files."; //$NON-NLS-1$
        }

        try
        {
            // Normalize source: \r\n -> \n
            source = source.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // 6. Read current content (if file exists)
            List<String> originalLines;
            boolean hasBom;
            if (fileExists)
            {
                originalLines = BslModuleUtils.readFileLines(file);
                hasBom = detectBom(file);
            }
            else
            {
                originalLines = new ArrayList<>();
                // New BSL files should have BOM
                hasBom = modulePath.endsWith(".bsl"); //$NON-NLS-1$
            }

            // 7. Split source into lines
            List<String> sourceLines = splitSourceLines(source);

            // 8. Compute new content based on mode
            List<String> newLines;
            int totalOriginal = originalLines.size();

            switch (mode)
            {
                case MODE_REPLACE:
                    newLines = new ArrayList<>(sourceLines);
                    break;

                case MODE_APPEND:
                    newLines = new ArrayList<>(originalLines);
                    newLines.addAll(sourceLines);
                    break;

                case MODE_INSERT_BEFORE:
                    if (line < 1 || line > totalOriginal + 1)
                    {
                        return "Error: line must be between 1 and " + (totalOriginal + 1) //$NON-NLS-1$
                            + " for insertBefore (file has " + totalOriginal + " lines)"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    newLines = new ArrayList<>(originalLines.subList(0, line - 1));
                    newLines.addAll(sourceLines);
                    newLines.addAll(originalLines.subList(line - 1, totalOriginal));
                    break;

                case MODE_INSERT_AFTER:
                    if (line < 1 || line > totalOriginal)
                    {
                        return "Error: line must be between 1 and " + totalOriginal //$NON-NLS-1$
                            + " for insertAfter (file has " + totalOriginal + " lines)"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    newLines = new ArrayList<>(originalLines.subList(0, line));
                    newLines.addAll(sourceLines);
                    newLines.addAll(originalLines.subList(line, totalOriginal));
                    break;

                case MODE_REPLACE_LINES:
                    if (lineFrom < 1)
                    {
                        return "Error: lineFrom is required for replaceLines mode (1-based)"; //$NON-NLS-1$
                    }
                    if (lineTo < lineFrom)
                    {
                        return "Error: lineTo must be >= lineFrom"; //$NON-NLS-1$
                    }
                    if (lineTo > totalOriginal)
                    {
                        return "Error: lineTo (" + lineTo + ") exceeds file length (" //$NON-NLS-1$ //$NON-NLS-2$
                            + totalOriginal + " lines)"; //$NON-NLS-1$
                    }
                    newLines = new ArrayList<>(originalLines.subList(0, lineFrom - 1));
                    newLines.addAll(sourceLines);
                    newLines.addAll(originalLines.subList(lineTo, totalOriginal));
                    break;

                default:
                    return "Error: unsupported mode: " + mode; //$NON-NLS-1$
            }

            // 9. BSL syntax check
            if (!skipSyntaxCheck)
            {
                BslSyntaxChecker.CheckResult checkResult = BslSyntaxChecker.check(newLines);
                if (!checkResult.isValid())
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Error: BSL syntax check failed. Write blocked.\n\n"); //$NON-NLS-1$
                    sb.append("**Errors:**\n"); //$NON-NLS-1$
                    for (String error : checkResult.getErrors())
                    {
                        sb.append("- ").append(error).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    sb.append("\nPass skipSyntaxCheck=true to force write."); //$NON-NLS-1$
                    return sb.toString();
                }
            }

            // 10. Write file
            writeFile(file, newLines, hasBom, fileExists);

            // 11. Return success
            StringBuilder sb = new StringBuilder();
            sb.append("## Written: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Mode:** ").append(mode); //$NON-NLS-1$

            switch (mode)
            {
                case MODE_INSERT_BEFORE:
                    sb.append(" (before line ").append(line).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                case MODE_INSERT_AFTER:
                    sb.append(" (after line ").append(line).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                case MODE_REPLACE_LINES:
                    sb.append(" (lines ").append(lineFrom).append("-").append(lineTo).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    break;
                default:
                    break;
            }
            sb.append("\n"); //$NON-NLS-1$

            if (fileExists)
            {
                sb.append("**Lines before:** ").append(totalOriginal); //$NON-NLS-1$
                sb.append(" | **Lines after:** ").append(newLines.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                sb.append("**New file created** | **Lines:** ").append(newLines.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            sb.append("**Syntax check:** ").append(skipSyntaxCheck ? "skipped" : "passed").append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("**Status:** File written successfully\n"); //$NON-NLS-1$

            return sb.toString();
        }
        catch (Exception e)
        {
            return "Error writing file: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves objectName + moduleType to a module file path relative to src/.
     */
    private String resolveModulePath(String objectName, String moduleType,
        String formName, String commandName)
    {
        // Parse objectName: "Document.MyDoc" -> typePart="Document", namePart="MyDoc"
        int dotIndex = objectName.indexOf('.'); //$NON-NLS-1$
        if (dotIndex <= 0 || dotIndex >= objectName.length() - 1)
        {
            return "Error: objectName must be in format 'Type.Name' " + //$NON-NLS-1$
                "(e.g. 'Document.MyDoc', 'CommonModule.MyModule')"; //$NON-NLS-1$
        }

        String typePart = objectName.substring(0, dotIndex);
        String namePart = objectName.substring(dotIndex + 1);

        // Resolve type to English singular
        String englishType = MetadataTypeUtils.toEnglishSingular(typePart);
        if (englishType == null)
        {
            return "Error: unknown metadata type: " + typePart; //$NON-NLS-1$
        }

        // Get directory name
        String dirName = MetadataTypeUtils.getDirectoryName(typePart);
        if (dirName == null)
        {
            return "Error: metadata type '" + typePart + "' has no source directory"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Determine default moduleType based on metadata type
        if (moduleType == null || moduleType.isEmpty())
        {
            if ("CommonModule".equals(englishType) //$NON-NLS-1$
                || "CommonForm".equals(englishType) //$NON-NLS-1$
                || "WebService".equals(englishType) //$NON-NLS-1$
                || "HTTPService".equals(englishType)) //$NON-NLS-1$
            {
                moduleType = "Module"; //$NON-NLS-1$
            }
            else if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
            {
                moduleType = "CommandModule"; //$NON-NLS-1$
            }
            else
            {
                moduleType = "ObjectModule"; //$NON-NLS-1$
            }
        }

        // Build path based on moduleType
        switch (moduleType)
        {
            case "Module": //$NON-NLS-1$
                return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ObjectModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ObjectModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ManagerModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ManagerModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "RecordSetModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/RecordSetModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "FormModule": //$NON-NLS-1$
                // CommonForms don't need formName — path is always Module.bsl
                if ("CommonForm".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (formName == null || formName.isEmpty())
                {
                    return "Error: formName is required when moduleType=FormModule"; //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Forms/" + formName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            case "CommandModule": //$NON-NLS-1$
                // CommonCommands don't need commandName — path is always CommandModule.bsl
                if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (commandName == null || commandName.isEmpty())
                {
                    return "Error: commandName is required when moduleType=CommandModule"; //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Commands/" + commandName + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            default:
                return "Error: unknown moduleType: " + moduleType + //$NON-NLS-1$
                    ". Allowed: ObjectModule, ManagerModule, FormModule, " + //$NON-NLS-1$
                    "CommandModule, RecordSetModule, Module"; //$NON-NLS-1$
        }
    }

    /**
     * Splits source code into lines, handling trailing newline artifact.
     */
    private List<String> splitSourceLines(String source)
    {
        if (source.isEmpty())
        {
            return new ArrayList<>();
        }

        String[] parts = source.split("\n", -1); //$NON-NLS-1$
        List<String> lines = new ArrayList<>(Arrays.asList(parts));

        // If source ends with \n, split produces a trailing empty element.
        // Remove it to avoid adding an extra blank line.
        if (source.endsWith("\n") && lines.size() > 1 //$NON-NLS-1$
            && lines.get(lines.size() - 1).isEmpty())
        {
            lines.remove(lines.size() - 1);
        }

        return lines;
    }

    /**
     * Detects if the file starts with UTF-8 BOM.
     */
    private boolean detectBom(IFile file)
    {
        try (InputStream is = file.getContents();
             BufferedInputStream bis = new BufferedInputStream(is))
        {
            byte[] bom = new byte[3];
            int read = bis.read(bom);
            return read == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
        }
        catch (Exception e)
        {
            // Default: assume BOM for BSL files
            return true;
        }
    }

    /**
     * Writes lines to the file, preserving BOM if needed.
     */
    private void writeFile(IFile file, List<String> lines, boolean withBom,
        boolean fileExists) throws Exception
    {
        String content = String.join("\n", lines); //$NON-NLS-1$

        // Ensure file ends with newline
        if (!content.endsWith("\n")) //$NON-NLS-1$
        {
            content += "\n"; //$NON-NLS-1$
        }

        byte[] contentBytes = content.getBytes("UTF-8"); //$NON-NLS-1$

        byte[] output;
        if (withBom)
        {
            output = new byte[UTF8_BOM.length + contentBytes.length];
            System.arraycopy(UTF8_BOM, 0, output, 0, UTF8_BOM.length);
            System.arraycopy(contentBytes, 0, output, UTF8_BOM.length, contentBytes.length);
        }
        else
        {
            output = contentBytes;
        }

        InputStream stream = new ByteArrayInputStream(output);

        if (fileExists)
        {
            file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
        }
        else
        {
            // Create parent directories if needed
            createParentFolders(file);
            file.create(stream, true, null);
        }
    }

    /**
     * Recursively creates parent folders for the given file.
     */
    private void createParentFolders(IFile file) throws Exception
    {
        IFolder parent = (IFolder)file.getParent();
        if (parent != null && !parent.exists())
        {
            createFolder(parent);
        }
    }

    /**
     * Recursively creates a folder and its parents.
     */
    private void createFolder(IFolder folder) throws Exception
    {
        if (folder.exists())
        {
            return;
        }
        if (folder.getParent() instanceof IFolder)
        {
            IFolder parentFolder = (IFolder)folder.getParent();
            if (!parentFolder.exists())
            {
                createFolder(parentFolder);
            }
        }
        folder.create(true, true, null);
    }
}

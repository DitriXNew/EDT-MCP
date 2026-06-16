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

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslSyntaxChecker;

/**
 * Tool to write BSL source code to 1C metadata object modules.
 * Supports modes: searchReplace (content-based, default), replace (full file), append.
 * Optionally validates BSL syntax (balanced block keywords) before writing.
 * Can resolve module path from objectName + moduleType.
 */
public class WriteModuleSourceTool implements IMcpTool
{
    public static final String NAME = "write_module_source"; //$NON-NLS-1$

    private static final String MODE_REPLACE = "replace"; //$NON-NLS-1$
    private static final String MODE_APPEND = "append"; //$NON-NLS-1$
    private static final String MODE_SEARCH_REPLACE = "searchReplace"; //$NON-NLS-1$

    private static final String PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String MODULE_PATH = "modulePath"; //$NON-NLS-1$

    private static final String MODULE_TYPE_MODULE = "Module"; //$NON-NLS-1$
    private static final String MODULE_TYPE_OBJECT_MODULE = "ObjectModule"; //$NON-NLS-1$
    private static final String MODULE_TYPE_COMMAND_MODULE = "CommandModule"; //$NON-NLS-1$

    private static final String MODULE_FILE_SUFFIX = "/Module.bsl"; //$NON-NLS-1$

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
        return "Write BSL source code to a 1C metadata object module. " + //$NON-NLS-1$
            "Use to edit a module: searchReplace a fragment (default, needs oldSource), " + //$NON-NLS-1$
            "replace the whole file, or append. " + //$NON-NLS-1$
            "Target the module by EITHER modulePath OR objectName (mutually exclusive — pass exactly one). " + //$NON-NLS-1$
            "Runs a BSL syntax check before writing (skipSyntaxCheck=true to force). " + //$NON-NLS-1$
            "Full parameters and examples: call get_tool_guide('write_module_source')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(MODULE_PATH,
                "src/-relative module path, e.g. 'CommonModules/MyModule/Module.bsl'. " + //$NON-NLS-1$
                "Alternative to objectName (pass exactly one).") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Object name 'Type.Name', e.g. 'Document.MyDoc'. " + //$NON-NLS-1$
                "Resolved with moduleType. Alternative to modulePath.") //$NON-NLS-1$
            .enumProperty("moduleType", //$NON-NLS-1$
                "Module type for objectName resolution (default ObjectModule).", //$NON-NLS-1$
                MODULE_TYPE_OBJECT_MODULE, "ManagerModule", "FormModule", MODULE_TYPE_COMMAND_MODULE, "RecordSetModule", MODULE_TYPE_MODULE) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("source", //$NON-NLS-1$
                "BSL source to write (required): full file for replace, new fragment for " + //$NON-NLS-1$
                "searchReplace, text to add for append.", true) //$NON-NLS-1$
            .stringProperty("oldSource", //$NON-NLS-1$
                "Fragment to find and replace; required for searchReplace, must match exactly once.") //$NON-NLS-1$
            .enumProperty("mode", //$NON-NLS-1$
                "Write mode (default searchReplace).", //$NON-NLS-1$
                MODE_SEARCH_REPLACE, MODE_REPLACE, MODE_APPEND)
            .stringProperty("formName", //$NON-NLS-1$
                "Form name; required when moduleType=FormModule (e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Command name; required when moduleType=CommandModule (e.g. 'FillByTemplate').") //$NON-NLS-1$
            .booleanProperty("skipSyntaxCheck", //$NON-NLS-1$
                "Skip the BSL syntax check (default false).") //$NON-NLS-1$
            .stringProperty("expectedSource", //$NON-NLS-1$
                "Lost-update guard for mode=replace: the module content you last read; mismatch rejects.") //$NON-NLS-1$
            .booleanProperty("overwrite", //$NON-NLS-1$
                "Force mode=replace over an existing module without an expectedSource check (default false).") //$NON-NLS-1$
            .stringProperty("expectedHash", //$NON-NLS-1$
                "Lost-update guard for any mode: the contentHash from your last read; mismatch rejects.") //$NON-NLS-1$
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
        String modulePath = JsonUtils.extractStringArgument(params, MODULE_PATH);
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "write-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "write-module-source.md"; //$NON-NLS-1$
    }

    @Override
    @SuppressWarnings("java:S125")
    public String execute(Map<String, String> params)
    {
        // 1. Extract parameters
        String projectName = JsonUtils.extractStringArgument(params, PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, MODULE_PATH);
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
        String source = JsonUtils.extractStringArgument(params, "source"); //$NON-NLS-1$
        String oldSource = JsonUtils.extractStringArgument(params, "oldSource"); //$NON-NLS-1$
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        boolean skipSyntaxCheck = JsonUtils.extractBooleanArgument(params, "skipSyntaxCheck", false); //$NON-NLS-1$
        String expectedSource = JsonUtils.extractStringArgument(params, "expectedSource"); //$NON-NLS-1$
        boolean overwrite = JsonUtils.extractBooleanArgument(params, "overwrite", false); //$NON-NLS-1$
        String expectedHash = JsonUtils.extractStringArgument(params, "expectedHash"); //$NON-NLS-1$

        // 2. Validate required parameters
        if (mode == null || mode.isEmpty())
        {
            mode = MODE_SEARCH_REPLACE;
        }
        String argError = validateWriteArguments(params, source, mode, oldSource);
        if (argError != null)
        {
            return argError;
        }

        // 3. Resolve the module target.
        ModuleTargetResult target = resolveModuleTarget(modulePath, objectName, moduleType,
            formName, commandName);
        if (target.error != null)
        {
            return target.error;
        }
        modulePath = target.modulePath;

        // 4. Validate project
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        // 5. Get file
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        boolean fileExists = file.exists();

        // For non-replace modes, file must exist
        if (!fileExists && !MODE_REPLACE.equals(mode))
        {
            return ToolResult.error("File not found: src/" + modulePath + //$NON-NLS-1$
                ". Only 'replace' mode can create new files.").toJson(); //$NON-NLS-1$
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
                hasBom = true; // New BSL files should have BOM
            }

            // 6b. Optimistic-lock guard (any mode): when the caller carried an
            // expectedHash from its last read, reject if the module changed since —
            // a cheap lost-update check that complements searchReplace's oldSource
            // match and replace's expectedSource. Read the canonical text the same way
            // those guards do (readFileText, \n-normalized) so the hashes always agree;
            // skipped entirely when no expectedHash is given.
            String currentTextForHash = (expectedHash != null && !expectedHash.isEmpty() && fileExists)
                ? BslModuleUtils.readFileText(file).replace("\r\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                : null;
            String hashError = evaluateExpectedHash(currentTextForHash, expectedHash, fileExists);
            if (hashError != null)
            {
                return hashError;
            }

            // 7. Compute new content based on mode
            int totalOriginal = originalLines.size();
            NewLinesResult computed = computeNewLines(mode, file, fileExists, originalLines,
                source, oldSource, expectedHash, expectedSource, overwrite);
            if (computed.error != null)
            {
                return computed.error;
            }
            List<String> newLines = computed.newLines;

            // 8. BSL syntax check
            if (!skipSyntaxCheck)
            {
                String syntaxError = runSyntaxCheck(newLines);
                if (syntaxError != null)
                {
                    return syntaxError;
                }
            }

            // 9. Write file
            writeFile(file, newLines, hasBom, fileExists);

            // 10. Return success
            return buildSuccessResponse(projectName, modulePath, mode, skipSyntaxCheck,
                newLines, fileExists, totalOriginal);
        }
        catch (Exception e)
        {
            return ToolResult.error("Failed to write file: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Validates the required and scalar write arguments in the SAME order the inline
     * flow did: projectName presence, source presence + length, mode validity, and the
     * oldSource requirement for searchReplace. {@code mode} is expected to have already
     * been defaulted by the caller.
     *
     * @return a ready {@link ToolResult#error} JSON payload (or the {@code requireArgument}
     *         error) to return verbatim, or {@code null} when all arguments are valid
     */
    private static String validateWriteArguments(Map<String, String> params, String source,
        String mode, String oldSource)
    {
        String err = JsonUtils.requireArgument(params, PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        if (source == null)
        {
            return ToolResult.error("source is required").toJson(); //$NON-NLS-1$
        }
        if (source.length() > MAX_SOURCE_LENGTH)
        {
            return ToolResult.error("source exceeds maximum allowed length (" + MAX_SOURCE_LENGTH + " characters)").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Validate mode
        if (!MODE_REPLACE.equals(mode) && !MODE_APPEND.equals(mode)
            && !MODE_SEARCH_REPLACE.equals(mode))
        {
            return ToolResult.error("invalid mode '" + mode + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Allowed: searchReplace, replace, append").toJson(); //$NON-NLS-1$
        }

        // Validate oldSource for searchReplace mode
        if (MODE_SEARCH_REPLACE.equals(mode) && (oldSource == null || oldSource.isEmpty()))
        {
            return ToolResult.error("oldSource is required for searchReplace mode").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolved module target produced by {@link #resolveModuleTarget}: either a ready
     * {@link ToolResult#error} JSON payload in {@link #error} (the caller returns it
     * verbatim) or the validated {@code src/}-relative {@link #modulePath}.
     */
    private static final class ModuleTargetResult
    {
        final String error;
        final String modulePath;

        private ModuleTargetResult(String error, String modulePath)
        {
            this.error = error;
            this.modulePath = modulePath;
        }
    }

    /**
     * Resolves the module target (read-only — no file is written). modulePath and
     * objectName form an exclusive OR (a flat Map schema cannot express XOR, so it is
     * enforced here): exactly one must be given. moduleType is a decorator on the
     * objectName path only — it is meaningless with an explicit modulePath, so it is
     * flagged instead of being silently ignored. formName/commandName are validated
     * deeper in {@link #resolveModulePath} (they depend on the resolved moduleType).
     * The resolved path is then validated against path traversal and a {@code .bsl}
     * extension, mirroring the inline checks exactly.
     *
     * @return a {@link ModuleTargetResult} whose {@link ModuleTargetResult#error} is
     *         non-{@code null} when the caller must return that JSON payload, otherwise
     *         the validated module path
     */
    private ModuleTargetResult resolveModuleTarget(String modulePath, String objectName,
        String moduleType, String formName, String commandName)
    {
        boolean hasModulePath = modulePath != null && !modulePath.isEmpty();
        boolean hasObjectName = objectName != null && !objectName.isEmpty();
        boolean hasModuleType = moduleType != null && !moduleType.isEmpty();

        if (hasModulePath && hasObjectName)
        {
            return new ModuleTargetResult(ToolResult.error("modulePath and objectName are mutually exclusive; " + //$NON-NLS-1$
                "pass only one (modulePath for a direct path, or objectName to resolve it)").toJson(), null); //$NON-NLS-1$
        }
        if (!hasModulePath && !hasObjectName)
        {
            return new ModuleTargetResult(
                ToolResult.error("either modulePath or objectName is required").toJson(), null); //$NON-NLS-1$
        }
        if (hasModulePath && hasModuleType)
        {
            return new ModuleTargetResult(ToolResult.error("moduleType applies only with objectName; " + //$NON-NLS-1$
                "it is meaningless with an explicit modulePath, so drop one of them").toJson(), null); //$NON-NLS-1$
        }

        if (!hasModulePath)
        {
            String resolved = resolveModulePath(objectName, moduleType, formName, commandName);
            if (isErrorJson(resolved))
            {
                // resolveModulePath signals failure by returning a ready
                // ToolResult.error(...).toJson() payload; surface it as-is.
                return new ModuleTargetResult(resolved, null);
            }
            modulePath = resolved;
        }

        // Validate modulePath: prevent path traversal
        if (modulePath.contains("..")) //$NON-NLS-1$
        {
            return new ModuleTargetResult(ToolResult.error("modulePath must not contain '..'").toJson(), null); //$NON-NLS-1$
        }

        // Validate modulePath: only .bsl files allowed
        if (!modulePath.endsWith(".bsl")) //$NON-NLS-1$
        {
            return new ModuleTargetResult(
                ToolResult.error("only .bsl module files can be written").toJson(), null); //$NON-NLS-1$
        }

        return new ModuleTargetResult(null, modulePath);
    }

    /**
     * Computed new module content produced by {@link #computeNewLines}: either a ready
     * {@link ToolResult#error} JSON payload in {@link #error} (the caller returns it
     * verbatim) or the {@link #newLines} to write.
     */
    private static final class NewLinesResult
    {
        final String error;
        final List<String> newLines;

        private NewLinesResult(String error, List<String> newLines)
        {
            this.error = error;
            this.newLines = newLines;
        }
    }

    /**
     * Computes the new module content for the given mode (read-only — no file is
     * written; only the already-read {@code file}/{@code originalLines} content is
     * consulted). Mirrors the inline {@code switch} exactly, including the replace
     * lost-update precondition and the searchReplace not-found / multiple-match errors.
     * <p>
     * Declares {@code throws Exception} because {@link #checkReplacePrecondition} does,
     * so the same exceptions reach the SAME {@code catch (Exception)} in {@link #execute}
     * as before.
     *
     * @return a {@link NewLinesResult} whose {@link NewLinesResult#error} is non-{@code null}
     *         when the caller must return that JSON payload, otherwise the lines to write
     */
    private NewLinesResult computeNewLines(String mode, IFile file, boolean fileExists,
        List<String> originalLines, String source, String oldSource, String expectedHash,
        String expectedSource, boolean overwrite) throws Exception
    {
        switch (mode)
        {
            case MODE_REPLACE:
            {
                // Lost-update guard: overwriting an EXISTING module blindly
                // clobbers any edit made since the agent last read it. Creating a
                // NEW module is unconditional (nothing to lose).
                // A matching expectedHash is itself a valid lost-update precondition
                // (the whole-file token proves the agent saw the current state) and
                // was ALREADY validated at step 6b — so when one was supplied, the
                // expectedSource/overwrite precondition is satisfied and skipped.
                boolean hashGuardSatisfied = expectedHash != null && !expectedHash.isEmpty();
                if (fileExists && !hashGuardSatisfied)
                {
                    String preconditionError =
                        checkReplacePrecondition(file, expectedSource, overwrite);
                    if (preconditionError != null)
                    {
                        return new NewLinesResult(preconditionError, null);
                    }
                }
                return new NewLinesResult(null, splitSourceLines(source));
            }

            case MODE_APPEND:
            {
                List<String> appended = new ArrayList<>(originalLines);
                appended.addAll(splitSourceLines(source));
                return new NewLinesResult(null, appended);
            }

            case MODE_SEARCH_REPLACE:
            {
                // Normalize oldSource
                oldSource = oldSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

                // Read the raw file content (preserves the trailing newline that
                // writeFile always adds). Reconstructing it from originalLines via
                // String.join dropped that final newline, so an oldSource fragment
                // that ended at EOF (including the final newline) was reported
                // "not found".
                String currentContent = BslModuleUtils.readFileText(file).replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

                SearchReplaceResult sr = applySearchReplace(currentContent, oldSource, source);
                if (sr.occurrences == 0)
                {
                    return new NewLinesResult(ToolResult.error("oldSource not found in current file content. " + //$NON-NLS-1$
                        "The file may have changed since last read, or the oldSource text " + //$NON-NLS-1$
                        "does not match exactly. Please read the file again with read_module_source.").toJson(), null); //$NON-NLS-1$
                }
                if (sr.occurrences > 1)
                {
                    return new NewLinesResult(ToolResult.error("oldSource found multiple times in the file (" + //$NON-NLS-1$
                        sr.occurrences +
                        " occurrences). Provide a larger, more specific oldSource fragment " + //$NON-NLS-1$
                        "that matches exactly one location.").toJson(), null); //$NON-NLS-1$
                }

                return new NewLinesResult(null, splitSourceLines(sr.newContent));
            }

            default:
                return new NewLinesResult(ToolResult.error("unsupported mode: " + mode).toJson(), null); //$NON-NLS-1$
        }
    }

    /**
     * Runs the BSL syntax check (read-only) over the computed {@code newLines} and,
     * on failure, formats the blocking error message exactly as the inline check did.
     *
     * @return a ready {@link ToolResult#error} JSON payload to return verbatim, or
     *         {@code null} when the syntax check passes
     */
    private static String runSyntaxCheck(List<String> newLines)
    {
        BslSyntaxChecker.CheckResult checkResult = BslSyntaxChecker.check(newLines);
        if (checkResult.isValid())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("BSL syntax check failed. Write blocked.\n\n"); //$NON-NLS-1$
        sb.append("Errors:\n"); //$NON-NLS-1$
        for (String error : checkResult.getErrors())
        {
            sb.append("- ").append(error).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\nPass skipSyntaxCheck=true to force write."); //$NON-NLS-1$
        return ToolResult.error(sb.toString()).toJson();
    }

    /**
     * Builds the success frontmatter + content returned after the module is written
     * (pure string building — no side effects), preserving the exact keys and the
     * {@code linesBefore}/{@code newFile} branch the inline flow used.
     *
     * @return the wrapped success response
     */
    private static String buildSuccessResponse(String projectName, String modulePath, String mode,
        boolean skipSyntaxCheck, List<String> newLines, boolean fileExists, int totalOriginal)
    {
        FrontMatter fm = FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put(PROJECT_NAME, projectName)
            .put(MODULE_PATH, modulePath)
            .put("mode", mode) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("linesAfter", newLines.size()) //$NON-NLS-1$
            .put("syntaxCheck", skipSyntaxCheck ? "skipped" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (fileExists)
        {
            fm.put("linesBefore", totalOriginal); //$NON-NLS-1$
        }
        else
        {
            fm.put("newFile", true); //$NON-NLS-1$
        }

        return fm.wrapContent("File written successfully"); //$NON-NLS-1$
    }

    /**
     * Counts the number of occurrences of a substring in a string.
     */
    private static int countOccurrences(String text, String search)
    {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) >= 0)
        {
            count++;
            idx++;
        }
        return count;
    }

    /**
     * Result of a pure content-based search/replace.
     * {@code occurrences}: 0 = oldSource absent, 1 = replaced ({@code newContent}
     * set), &gt;1 = ambiguous ({@code newContent} null).
     */
    static final class SearchReplaceResult
    {
        final int occurrences;
        final String newContent;

        SearchReplaceResult(int occurrences, String newContent)
        {
            this.occurrences = occurrences;
            this.newContent = newContent;
        }
    }

    /**
     * Pure content-based search/replace used by MODE_SEARCH_REPLACE. Operating on
     * the raw file content (which keeps the trailing newline) means an oldSource
     * fragment that ends at EOF, including the final newline, is found. Returns
     * occurrences=0 when absent and &gt;1 when ambiguous (newContent null in both),
     * or 1 with the replaced content.
     */
    static SearchReplaceResult applySearchReplace(String currentContent, String oldSource, String newSource)
    {
        int idx = currentContent.indexOf(oldSource);
        if (idx < 0)
        {
            return new SearchReplaceResult(0, null);
        }
        int secondIdx = currentContent.indexOf(oldSource, idx + 1);
        if (secondIdx >= 0)
        {
            return new SearchReplaceResult(countOccurrences(currentContent, oldSource), null);
        }
        String newContent = currentContent.substring(0, idx) + newSource
            + currentContent.substring(idx + oldSource.length());
        return new SearchReplaceResult(1, newContent);
    }

    /**
     * Lost-update precondition for {@code mode=replace} over an EXISTING module.
     * mode=searchReplace is already protected by its {@code oldSource} content match;
     * a full replace would otherwise blindly clobber whatever the module contains now,
     * losing a concurrent edit (or one made between the agent's read and write).
     * <p>
     * Resolution:
     * <ul>
     * <li>{@code expectedSource} provided — compare it to the current module content;
     *     on mismatch reject with a "read it again then retry" steer (mirrors the
     *     searchReplace not-found steer), on match proceed.</li>
     * <li>else {@code overwrite == true} — proceed (explicit force).</li>
     * <li>else — reject and point at expectedSource / overwrite / searchReplace.</li>
     * </ul>
     * Content is compared on {@code \n}-normalized text (same normalization
     * {@code source} and searchReplace use), so a CRLF/LF difference alone is not a
     * spurious mismatch.
     *
     * @return {@code null} to proceed, or a ready {@link ToolResult#error} JSON
     *     payload to return verbatim from {@link #execute}.
     */
    private static String checkReplacePrecondition(IFile file, String expectedSource,
        boolean overwrite) throws Exception
    {
        // Read the raw module content (same normalization searchReplace uses) ONLY
        // when an expectedSource has to be compared; otherwise the decision is purely
        // overwrite vs reject and no file access is needed.
        String currentContent = expectedSource == null
            ? null
            : BslModuleUtils.readFileText(file).replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
        return evaluateReplacePrecondition(currentContent, expectedSource, overwrite);
    }

    /**
     * Pure decision for the {@code mode=replace}-over-existing lost-update guard,
     * split out so it is unit-testable without an Eclipse workspace (mirrors
     * {@link #applySearchReplace}). {@code currentContent} is the {@code \n}-normalized
     * current module content and may be {@code null} when {@code expectedSource} is
     * {@code null} (no content comparison is performed in that branch).
     *
     * @return {@code null} to proceed, or a ready {@link ToolResult#error} JSON
     *     payload to return verbatim from {@link #execute}.
     */
    static String evaluateReplacePrecondition(String currentContent, String expectedSource,
        boolean overwrite)
    {
        if (expectedSource != null)
        {
            String expectedNormalized = expectedSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (currentContent == null || !currentContent.equals(expectedNormalized))
            {
                return ToolResult.error("expectedSource does not match the current module content. " + //$NON-NLS-1$
                    "The module changed since you read it (a concurrent edit), so a full replace " + //$NON-NLS-1$
                    "would lose that change. Please read the file again with read_module_source, " + //$NON-NLS-1$
                    "then retry with the up-to-date expectedSource.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        if (overwrite)
        {
            return null;
        }
        return ToolResult.error("module already exists; a full replace would overwrite it and " + //$NON-NLS-1$
            "could lose a concurrent edit. Pass expectedSource (the content you last read with " + //$NON-NLS-1$
            "read_module_source) to guard against a lost update, or overwrite=true to force, " + //$NON-NLS-1$
            "or use mode=searchReplace to change only a fragment.").toJson(); //$NON-NLS-1$
    }

    /**
     * Pure decision for the {@code expectedHash} optimistic-lock guard, split out so
     * it is unit-testable without an Eclipse workspace (mirrors
     * {@link #evaluateReplacePrecondition}). Applies to ANY write mode and is the cheap
     * counterpart to {@code expectedSource}: the caller round-trips the opaque
     * {@code contentHash} from a read tool, and a change since then is rejected with the
     * same "read it again then retry" steer.
     *
     * @param currentContent the {@code \n}-normalized current module content, or
     *     {@code null} when {@code expectedHash} is blank or the file does not exist
     *     (no content comparison is performed in those branches)
     * @param expectedHash the opaque token the caller carried over from its read; a
     *     blank value disables the guard (backward compatible)
     * @param fileExists whether the target module already exists on disk
     * @return {@code null} to proceed, or a ready {@link ToolResult#error} JSON payload
     *     to return verbatim from {@link #execute}
     */
    static String evaluateExpectedHash(String currentContent, String expectedHash, boolean fileExists)
    {
        if (expectedHash == null || expectedHash.isEmpty())
        {
            return null;
        }
        if (!fileExists)
        {
            return ToolResult.error("expectedHash was provided but this module does not exist yet, " + //$NON-NLS-1$
                "so there is nothing to match. To CREATE a new module omit expectedHash; otherwise the " + //$NON-NLS-1$
                "path is wrong or the module was deleted — re-check with read_module_source.").toJson(); //$NON-NLS-1$
        }
        if (!ContentHash.matches(currentContent, expectedHash))
        {
            return ToolResult.error("expectedHash does not match the current module content. " + //$NON-NLS-1$
                "The module changed since you read it (a concurrent edit), so writing now could lose " + //$NON-NLS-1$
                "that change. Please read the file again with read_module_source and retry with the " + //$NON-NLS-1$
                "up-to-date contentHash.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns {@code true} when the given resolver result is a structured error
     * payload (a {@link ToolResult#error} JSON object) rather than a resolved
     * module path. A resolved path is a plain {@code src/}-relative string, so it
     * can never begin with the JSON object opener.
     */
    private static boolean isErrorJson(String resolverResult)
    {
        return resolverResult.startsWith("{"); //$NON-NLS-1$
    }

    /**
     * Resolves objectName + moduleType to a module file path relative to src/.
     * On success returns the plain path; on failure returns a ready
     * {@link ToolResult#error} JSON payload (detected by {@link #isErrorJson})
     * so the error reaches the client through the structured error contract
     * rather than a bare "Error:" string.
     */
    private String resolveModulePath(String objectName, String moduleType,
        String formName, String commandName)
    {
        // Parse objectName: "Document.MyDoc" -> typePart="Document", namePart="MyDoc"
        int dotIndex = objectName.indexOf('.'); //$NON-NLS-1$
        if (dotIndex <= 0 || dotIndex >= objectName.length() - 1)
        {
            return ToolResult.error("objectName must be in format 'Type.Name' " + //$NON-NLS-1$
                "(e.g. 'Document.MyDoc', 'CommonModule.MyModule')").toJson(); //$NON-NLS-1$
        }

        String typePart = objectName.substring(0, dotIndex);
        String namePart = objectName.substring(dotIndex + 1);

        // Resolve type to English singular
        String englishType = MetadataTypeUtils.toEnglishSingular(typePart);
        if (englishType == null)
        {
            return ToolResult.error("unknown metadata type: " + typePart).toJson(); //$NON-NLS-1$
        }

        // Get directory name
        String dirName = MetadataTypeUtils.getDirectoryName(typePart);
        if (dirName == null)
        {
            return ToolResult.error("metadata type '" + typePart + "' has no source directory").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Determine default moduleType based on metadata type
        moduleType = resolveDefaultModuleType(moduleType, englishType);

        // Build path based on moduleType
        switch (moduleType)
        {
            case MODULE_TYPE_MODULE:
                return dirName + "/" + namePart + MODULE_FILE_SUFFIX; //$NON-NLS-1$

            case MODULE_TYPE_OBJECT_MODULE:
                return dirName + "/" + namePart + "/ObjectModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ManagerModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ManagerModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "RecordSetModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/RecordSetModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "FormModule": //$NON-NLS-1$
                // CommonForms don't need formName — path is always Module.bsl
                if ("CommonForm".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + MODULE_FILE_SUFFIX; //$NON-NLS-1$
                }
                if (formName == null || formName.isEmpty())
                {
                    return ToolResult.error("formName is required when moduleType=FormModule").toJson(); //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Forms/" + formName + MODULE_FILE_SUFFIX; //$NON-NLS-1$ //$NON-NLS-2$

            case MODULE_TYPE_COMMAND_MODULE:
                // CommonCommands don't need commandName — path is always CommandModule.bsl
                if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (commandName == null || commandName.isEmpty())
                {
                    return ToolResult.error("commandName is required when moduleType=CommandModule").toJson(); //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Commands/" + commandName + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            default:
                return ToolResult.error("unknown moduleType: " + moduleType + //$NON-NLS-1$
                    ". Allowed: ObjectModule, ManagerModule, FormModule, " + //$NON-NLS-1$
                    "CommandModule, RecordSetModule, Module").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Returns the supplied {@code moduleType} unchanged when it is non-empty,
     * otherwise derives the default module type from the English metadata type:
     * {@code Module} for module-bearing singletons (CommonModule, CommonForm,
     * WebService, HTTPService), {@code CommandModule} for CommonCommand, and
     * {@code ObjectModule} for everything else.
     *
     * @param moduleType the requested module type (may be {@code null} or empty)
     * @param englishType the resolved English singular metadata type
     * @return the explicit module type, or the derived default when none was given
     */
    private static String resolveDefaultModuleType(String moduleType, String englishType)
    {
        if (moduleType != null && !moduleType.isEmpty())
        {
            return moduleType;
        }
        if ("CommonModule".equals(englishType) //$NON-NLS-1$
            || "CommonForm".equals(englishType) //$NON-NLS-1$
            || "WebService".equals(englishType) //$NON-NLS-1$
            || "HTTPService".equals(englishType)) //$NON-NLS-1$
        {
            return MODULE_TYPE_MODULE;
        }
        if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
        {
            return MODULE_TYPE_COMMAND_MODULE;
        }
        return MODULE_TYPE_OBJECT_MODULE;
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

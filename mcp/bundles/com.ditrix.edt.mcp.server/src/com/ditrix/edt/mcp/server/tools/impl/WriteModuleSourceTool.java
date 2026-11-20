/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Locale;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.AdoptedModuleState;
import com.ditrix.edt.mcp.server.utils.BslDuplicateMethods;
import com.ditrix.edt.mcp.server.utils.BslMethodTransfer;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslSyntaxChecker;
import com.ditrix.edt.mcp.server.utils.BslWholeWordReplace;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.InvalidFileCharacters;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * Tool to write BSL source code to 1C metadata object modules.
 * Supports content, whole-file, append, and method-targeted write modes.
 * Optionally validates BSL syntax (balanced block keywords) before writing.
 * Can resolve module path from objectName + moduleType.
 */
public class WriteModuleSourceTool implements IMcpTool
{
    public static final String NAME = "write_module_source"; //$NON-NLS-1$

    private static final String MODE_REPLACE = "replace"; //$NON-NLS-1$
    private static final String MODE_APPEND = "append"; //$NON-NLS-1$
    private static final String MODE_SEARCH_REPLACE = "searchReplace"; //$NON-NLS-1$
    private static final String MODE_REPLACE_METHOD = "replaceMethod"; //$NON-NLS-1$
    private static final String MODE_INSERT_BEFORE = "insertBefore"; //$NON-NLS-1$
    private static final String MODE_INSERT_AFTER = "insertAfter"; //$NON-NLS-1$
    /** Deletes the methods named in {@code methodNames} (#978). */
    private static final String MODE_DELETE_METHODS = "deleteMethods"; //$NON-NLS-1$
    /** Replaces every whole-word occurrence of {@code oldSource} with {@code source} (#996). */
    private static final String MODE_REPLACE_ALL = "replaceAll"; //$NON-NLS-1$

    private static final String PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String MODULE_PATH = "modulePath"; //$NON-NLS-1$
    /** The module of the same project the methods named in {@code methodNames} are copied from (#978). */
    private static final String FROM_MODULE_PATH = "fromModulePath"; //$NON-NLS-1$
    private static final String METHOD_NAMES = "methodNames"; //$NON-NLS-1$
    /** The revision token read_module_source reports; a write answers it for the text it wrote (#978). */
    private static final String CONTENT_HASH = "contentHash"; //$NON-NLS-1$

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
        return "Create or edit BSL source in a metadata module. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('write_module_source')."; //$NON-NLS-1$
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
                "BSL source to write: full file for replace, new fragment for " + //$NON-NLS-1$
                "searchReplace, text to add for append, or exactly one complete method for " + //$NON-NLS-1$
                "a method-targeted mode. Required unless fromModulePath or mode=deleteMethods.") //$NON-NLS-1$
            .stringProperty(FROM_MODULE_PATH,
                "Instead of source: copy the methodNames methods from this module of the same project " //$NON-NLS-1$
                + "(modes append (default), insertBefore, insertAfter, replace of a new module).") //$NON-NLS-1$
            .stringArrayProperty(METHOD_NAMES,
                "Methods to copy (with fromModulePath) or delete (mode=deleteMethods), up to " //$NON-NLS-1$
                + BslMethodTransfer.MAX_METHOD_NAMES + ".") //$NON-NLS-1$
            .stringProperty("oldSource", //$NON-NLS-1$
                "Fragment to find and replace; required for searchReplace (must match exactly once) and " //$NON-NLS-1$
                + "replaceAll (every whole-word occurrence).") //$NON-NLS-1$
            .enumProperty("mode", //$NON-NLS-1$
                "Write mode (default searchReplace).", //$NON-NLS-1$
                MODE_SEARCH_REPLACE, MODE_REPLACE, MODE_APPEND,
                MODE_REPLACE_METHOD, MODE_INSERT_BEFORE, MODE_INSERT_AFTER, MODE_DELETE_METHODS, MODE_REPLACE_ALL)
            .stringProperty("methodName", //$NON-NLS-1$
                "Existing anchor method; required for replaceMethod, insertBefore, and insertAfter.") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "Form name; required when moduleType=FormModule (e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Command name; required when moduleType=CommandModule (e.g. 'FillByTemplate').") //$NON-NLS-1$
            .booleanProperty("skipSyntaxCheck", //$NON-NLS-1$
                "Skip the BSL syntax check (default false).") //$NON-NLS-1$
            .booleanProperty("normalizeInvalidCharacters", //$NON-NLS-1$
                "Replace the characters the 1C standard InvalidCharacterInFile forbids in the " //$NON-NLS-1$
                + "SOURCE you supply - en/em/figure dash, horizontal bar and typographic minus " //$NON-NLS-1$
                + "become '-', a no-break space becomes a space, a soft hyphen is dropped " //$NON-NLS-1$
                + "(default true). They are invisible in a diff and read exactly like their ASCII " //$NON-NLS-1$
                + "twins, so they otherwise land in the module as a marker. Set false to write the " //$NON-NLS-1$
                + "source byte-for-byte.") //$NON-NLS-1$
            .stringProperty("expectedSource", //$NON-NLS-1$
                "Lost-update guard for mode=replace: the module content you last read; mismatch rejects.") //$NON-NLS-1$
            .booleanProperty("overwrite", //$NON-NLS-1$
                "Force mode=replace over an existing module without an expectedSource check (default false).") //$NON-NLS-1$
            .stringProperty("expectedHash", //$NON-NLS-1$
                "Lost-update guard: the hash from the read that produced your edit. The write is " //$NON-NLS-1$
                + "rejected if the module changed since; required for every method-targeted mode " //$NON-NLS-1$
                + "and deleteMethods.") //$NON-NLS-1$
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
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "write-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "write-module-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // 1. Extract and default the request parameters.
        WriteRequest req = WriteRequest.from(params);

        // 2. Validate required parameters
        String argError = validateWriteArguments(params, req);
        if (argError != null)
        {
            return argError;
        }

        // 3. Resolve the module target.
        ModuleTargetResult target = resolveModuleTarget(req.modulePath, req.objectName,
            req.moduleType, req.formName, req.commandName);
        if (target.error != null)
        {
            return target.error;
        }
        req.modulePath = target.modulePath;

        // 4. Validate project
        ProjectContext ctx = ProjectContext.of(req.projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(req.projectName)).toJson();
        }
        IProject project = ctx.project();
        // A project this server just switched still holds the previous branch's model; the write of an adopted
        // module exports its owner's .mdo from that model over the checked-out file (#922).
        String reload = ProjectStateChecker.checkoutReloadErrorOrNull(project);
        if (reload != null)
        {
            return ToolResult.error(reload).toJson();
        }

        AtomicBoolean mutationEntered = new AtomicBoolean();
        AtomicBoolean mutationCommitted = new AtomicBoolean();
        // The contentHash of the text this call wrote, set together with mutationCommitted (#978).
        AtomicReference<String> writtenHash = new AtomicReference<>();
        try
        {
            // 5. Get file
            IFile file = BslModuleUtils.resolveModuleFile(project, req.modulePath);
            String outside = outsideProjectError(project, file, req.modulePath);
            if (outside != null)
            {
                return outside;
            }
            req.modulePath = BslModuleUtils.sourceRelativePath(file);
            boolean fileExists = file.exists();

            // For non-replace modes, file must exist
            if (!fileExists && !MODE_REPLACE.equals(req.mode))
            {
                return ToolResult.error("File not found: src/" + req.modulePath + //$NON-NLS-1$
                    ". Only 'replace' mode can create new files.").toJson(); //$NON-NLS-1$
            }

            // 6. The metadata root, resolved ONCE and here - where the vendor guard always resolved it, so
            // the order of the refusals is unchanged - for the guard and for the adopted-module step after
            // the write (#711). Then vendor support: the module and the object that owns it must be
            // editable (#642).
            ProjectContext.ConfigurationResult root = ctx.resolveMetadataRoot();
            if (!root.ok())
            {
                return root.errorJson();
            }
            String locked = vendorSupportRefusal(ctx, root, file);
            if (locked != null)
            {
                return locked;
            }
            // 7. A copy (#978): the methods' text is cut from the source module here, server-side; the source
            // is only read, so it needs no vendor guard and no adopted-module step of its own.
            if (req.isCopy())
            {
                String copyError = prepareCopy(req, project, file, fileExists);
                if (copyError != null)
                {
                    return copyError;
                }
            }

            return writeModule(req, file, fileExists, root.scope(), mutationEntered, mutationCommitted,
                writtenHash);
        }
        catch (Exception e)
        {
            String refusal = Refusals.messageOf(e);
            String message = refusal != null && !mutationEntered.get() ? refusal
                : (mutationEntered.get() ? "Failed to write file: " : "Failed to prepare module write: ") //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage();
            ToolResult error = mutationCommitted.get()
                ? withWrittenHash(ToolResult.errorAfterMutation(message), writtenHash.get())
                : mutationEntered.get()
                    ? ToolResult.errorWithUnknownMutationOutcome(message)
                    : ToolResult.error(message);
            return error.toJson();
        }
    }

    /**
     * Refuses a module path that resolves to no file, or to a file of another project: an
     * absolute path resolves across the whole workspace, so without this check the write -
     * and its vendor-support guard - would judge the named project while changing another.
     *
     * @param project the named project
     * @param file the resolved module file, may be {@code null}
     * @param modulePath the requested path, for the message
     * @return the error JSON, or {@code null} when the file belongs to {@code project}
     */
    static String outsideProjectError(IProject project, IFile file, String modulePath)
    {
        return outsideProjectError(project, file, modulePath, MODULE_PATH);
    }

    /**
     * {@link #outsideProjectError(IProject, IFile, String)} for the module named by {@code parameter} - the
     * target's {@code modulePath} or a copy's {@code fromModulePath} (#978), which must belong to the same
     * project too.
     */
    private static String outsideProjectError(IProject project, IFile file, String modulePath, String parameter)
    {
        if (file == null)
        {
            return ToolResult.error("File not found: " + modulePath //$NON-NLS-1$
                + ". Pass a src/-relative module path of project '" + project.getName() //$NON-NLS-1$
                + "', e.g. 'CommonModules/MyModule/Module.bsl'.").toJson(); //$NON-NLS-1$
        }
        IProject owner = file.getProject();
        if ((owner == null || !owner.equals(project)) && FROM_MODULE_PATH.equals(parameter))
        {
            return ToolResult.error("fromModulePath '" + modulePath + "' is a file of project '" //$NON-NLS-1$ //$NON-NLS-2$
                + (owner == null ? "?" : owner.getName()) + "', not of '" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Nothing was written: methods are copied only between modules of one project; pass " //$NON-NLS-1$
                + "a src/-relative fromModulePath of '" + project.getName() + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (owner == null || !owner.equals(project))
        {
            return ToolResult.error(parameter + " '" + modulePath + "' is a file of project '" //$NON-NLS-1$ //$NON-NLS-2$
                + (owner == null ? "?" : owner.getName()) + "', not of '" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Nothing was changed. Pass projectName='" //$NON-NLS-1$
                + (owner == null ? "?" : owner.getName()) //$NON-NLS-1$
                + "' and a src/-relative modulePath.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The vendor-support refusal for writing {@code file}, as ready JSON: the module (when it
     * exists) and the object that owns it must be editable. The caller has already refused a
     * project whose metadata root cannot be read (fail closed).
     *
     * @param ctx the resolved project
     * @param root the project's resolved metadata root
     * @param file the module file
     * @return the error JSON, or {@code null} when the write may proceed
     */
    private static String vendorSupportRefusal(ProjectContext ctx, ProjectContext.ConfigurationResult root,
        IFile file)
    {
        String refusal = VendorSupportGuard.refusalForModuleFile(ctx.project(), root.scope(), file);
        return refusal == null ? null : ToolResult.error(refusal).toJson();
    }

    /**
     * Resolves and reads the source module of a copy and cuts the named methods out of it (#978), setting
     * {@link WriteRequest#copy}. The source path follows the target's rules ({@code ..} and non-{@code .bsl}
     * refused, a file of the same project); it must exist and differ from the target. The text is decoded
     * strictly - a malformed byte would otherwise be copied as a replacement character - and the token answered
     * as {@code fromContentHash} is taken over that very text.
     *
     * @return the error JSON, or {@code null} when the copy is ready
     */
    private static String prepareCopy(WriteRequest req, IProject project, IFile target, boolean targetExists)
        throws Exception
    {
        String from = req.fromModulePath;
        if (from.contains("..")) //$NON-NLS-1$
        {
            return ToolResult.error("fromModulePath must not contain '..'").toJson(); //$NON-NLS-1$
        }
        if (!from.endsWith(".bsl")) //$NON-NLS-1$
        {
            return ToolResult.error("fromModulePath must name a .bsl module file").toJson(); //$NON-NLS-1$
        }
        if (MODE_REPLACE.equals(req.mode) && targetExists)
        {
            return ToolResult.error("fromModulePath with mode=replace creates a new module, but '" + req.modulePath //$NON-NLS-1$
                + "' already exists. Nothing was written. Copy into it with mode=append, insertBefore or " //$NON-NLS-1$
                + "insertAfter.").toJson(); //$NON-NLS-1$
        }
        IFile source = BslModuleUtils.resolveModuleFile(project, from);
        String outside = outsideProjectError(project, source, from, FROM_MODULE_PATH);
        if (outside != null)
        {
            return outside;
        }
        if (!source.exists())
        {
            return ToolResult.error("fromModulePath not found: src/" + from + ". Nothing was written.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String sourcePath = BslModuleUtils.sourceRelativePath(source);
        if (source.equals(target) || source.getFullPath().equals(target.getFullPath()))
        {
            return ToolResult.error("fromModulePath '" + sourcePath + "' is the target module itself. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Copy between two different modules.").toJson(); //$NON-NLS-1$
        }
        String text;
        try
        {
            text = BslModuleUtils.readFileText(source, true);
        }
        catch (java.nio.charset.CharacterCodingException e)
        {
            return ToolResult.error("fromModulePath '" + sourcePath + "' is not valid text in its encoding, so " //$NON-NLS-1$ //$NON-NLS-2$
                + "its methods cannot be copied byte for byte. Nothing was written.").toJson(); //$NON-NLS-1$
        }
        BslMethodTransfer.Result extracted =
            BslMethodTransfer.extract(splitSourceLines(text), req.methodNames, sourcePath);
        if (extracted.failed())
        {
            return ToolResult.error(extracted.error).toJson();
        }
        req.copy = new Copy(extracted.lines, extracted.methods, sourcePath, ContentHash.of(text));
        return null;
    }

    /**
     * Parsed and defaulted {@code write_module_source} request inputs, bundled into one
     * holder so the write helpers take a single seam instead of a long parameter list
     * (avoids the &gt;7-parameter smell on {@link #computeNewLines}). {@code mode} is
     * defaulted to {@link #MODE_SEARCH_REPLACE} at construction exactly as the inline flow
     * did; {@code modulePath} is mutable because {@link #resolveModuleTarget} may resolve it
     * from {@code objectName}.
     */
    private static final class WriteRequest
    {
        final String projectName;
        String modulePath;
        final String objectName;
        final String moduleType;
        String source;
        final String oldSource;
        final String mode;
        final String methodName;
        final String formName;
        final String commandName;
        final boolean skipSyntaxCheck;
        final boolean normalizeInvalidCharacters;
        final String expectedSource;
        final boolean overwrite;
        final String expectedHash;
        /** The module the methods are copied from, or {@code null} (#978). */
        final String fromModulePath;
        /** The names sent in {@code methodNames}, trimmed (blank entries kept, to be refused), or {@code null}. */
        final List<String> methodNames;
        /** The copy (fromModulePath) of a validated request: the methods' lines, declared names and source token. */
        Copy copy;

        private WriteRequest(Map<String, String> params)
        {
            this.projectName = JsonUtils.extractStringArgument(params, PROJECT_NAME);
            this.modulePath = JsonUtils.extractStringArgument(params, MODULE_PATH);
            String from = JsonUtils.extractStringArgument(params, FROM_MODULE_PATH);
            this.fromModulePath = from == null || from.isEmpty() ? null : from;
            this.methodNames = trimmed(JsonUtils.extractArrayArgument(params, METHOD_NAMES));
            this.objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
            this.moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
            // Kept RAW here (NOT \r\n-normalized) so validateWriteArguments measures the
            // exact same length the inline flow did; the \r\n->\n normalization happens in
            // writeModule, just like the original try block did, before any content compute.
            this.source = JsonUtils.extractStringArgument(params, "source"); //$NON-NLS-1$
            this.oldSource = JsonUtils.extractStringArgument(params, "oldSource"); //$NON-NLS-1$
            String rawMode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
            // A copy appends by default: its methods are not a fragment to search for.
            String defaultMode = this.fromModulePath != null ? MODE_APPEND : MODE_SEARCH_REPLACE;
            this.mode = (rawMode == null || rawMode.isEmpty()) ? defaultMode : rawMode;
            this.methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
            this.formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
            this.commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
            this.skipSyntaxCheck = JsonUtils.extractBooleanArgument(params, "skipSyntaxCheck", false); //$NON-NLS-1$
            this.normalizeInvalidCharacters =
                JsonUtils.extractBooleanArgument(params, "normalizeInvalidCharacters", true); //$NON-NLS-1$
            this.expectedSource = JsonUtils.extractStringArgument(params, "expectedSource"); //$NON-NLS-1$
            this.overwrite = JsonUtils.extractBooleanArgument(params, "overwrite", false); //$NON-NLS-1$
            this.expectedHash = JsonUtils.extractStringArgument(params, "expectedHash"); //$NON-NLS-1$
        }

        static WriteRequest from(Map<String, String> params)
        {
            return new WriteRequest(params);
        }

        private static List<String> trimmed(List<String> raw)
        {
            if (raw == null)
            {
                return null; // NOSONAR absent, not empty: methodNames was not sent
            }
            List<String> names = new ArrayList<>(raw.size());
            for (String name : raw)
            {
                names.add(name == null ? "" : name.trim()); //$NON-NLS-1$
            }
            return names;
        }

        boolean isCopy()
        {
            return fromModulePath != null;
        }

        boolean isDelete()
        {
            return MODE_DELETE_METHODS.equals(mode);
        }

        boolean isReplaceAll()
        {
            return MODE_REPLACE_ALL.equals(mode);
        }
    }

    /**
     * What a copy (fromModulePath) takes from its source module (#978): the lines of the named methods, their
     * names as declared, the source's resolved path and the {@code contentHash} of the very text the lines were
     * cut from - the token a later {@code mode=deleteMethods} on the source carries as {@code expectedHash}, so
     * it deletes exactly what was copied.
     */
    static final class Copy
    {
        final List<String> lines;
        final List<String> methods;
        final String fromModulePath;
        final String fromContentHash;

        Copy(List<String> lines, List<String> methods, String fromModulePath, String fromContentHash)
        {
            this.lines = lines;
            this.methods = methods;
            this.fromModulePath = fromModulePath;
            this.fromContentHash = fromContentHash;
        }
    }

    /**
     * The body of {@link #execute} that runs once the project/file is resolved: reads the
     * current content, applies the optimistic-lock + syntax guards, then WRITES the module
     * and builds the success response. Kept under {@link #execute}'s {@code try} so any
     * {@link Exception} reaches the SAME {@code catch} as before; declares
     * {@code throws Exception} because {@link #computeNewLines}/{@link #writeFile} do.
     *
     * @param req the parsed request (its {@code modulePath} is already resolved)
     * @param file the resolved module file
     * @param fileExists whether {@code file} already exists on disk
     * @param scope the project's metadata root, for the adopted-module step after the write
     * @return the success response, or a ready {@link ToolResult#error} JSON payload from a
     *         guard (returned verbatim) — the same value in the same case as the inline flow; once
     *         the text is written, a failed adopted-module step answers instead with a
     *         {@link ToolResult#errorAfterMutation} payload ({@code mutationCommitted:true}), see
     *         {@link #finishWrite}
     */
    private String writeModule(WriteRequest req, IFile file, boolean fileExists, MetadataScope scope,
        AtomicBoolean mutationEntered, AtomicBoolean mutationCommitted, AtomicReference<String> writtenHash)
        throws Exception
    {
        // Normalize source: \r\n -> \n (same point and order as the inline try block,
        // i.e. AFTER validateWriteArguments measured the raw length).
        // A copy or a deletion (#978) carries no source: copied methods are existing code moved as it is, never
        // normalized, so the deletion that finishes a move removes exactly the text that was copied.
        if (req.source == null)
        {
            req.source = ""; //$NON-NLS-1$
        }
        req.source = req.source.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Normalize the forbidden look-alike characters in the INCOMING source only (#161).
        // Deliberately not applied to oldSource: that fragment is matched against what is
        // already in the file, and normalizing it would stop it matching. It is also not
        // applied to the untouched rest of the file - a write must change the lines the caller
        // asked to change, not silently rewrite the ones it did not.
        InvalidFileCharacters.Result charFix = req.normalizeInvalidCharacters
            ? InvalidFileCharacters.normalize(req.source)
            : InvalidFileCharacters.unchanged(req.source);
        req.source = charFix.text();

        // Read the module, check the guards and compute the new lines. For a guarded write this is done once
        // more under the file's modify rule below; this first pass answers every refusal before any lock is taken.
        WritePlan plan = planWrite(req, file, fileExists);
        if (plan.error != null)
        {
            return plan.error;
        }

        // Method-targeted writes always carry a hash and build a whole replacement module in
        // memory, so a concurrent change must not be overwritten. Re-reading the hash and then
        // writing as two operations leaves a window between them; the two run inside ONE
        // workspace operation holding the file's modify rule, which is what actually excludes
        // another workspace writer (an editor save, another tool) for the whole compare-and-write.
        // A process writing the file outside Eclipse is not covered - IFile.setContents(FORCE)
        // has no compare-and-swap - but it IS seen by the guard's own read.
        // A deletion (expectedHash required) and a copy into an existing module splice whole methods the same
        // way, so they take the same compare-and-write (#978). Under the rule the module is READ AGAIN and the
        // plan recomputed from that text: a copy without expectedHash then splices into what the file holds
        // now - an edit saved after the first read is kept, and a name that appeared meanwhile refuses the copy -
        // and for a hashed write it is the same compare the late hash check always made.
        // The text modes (searchReplace, append, replace) take the same path for an existing module (#987): their
        // plan - the replaced fragment, the appended text, the repeated-method-name verdict - is judged against the
        // text the file holds under the rule, so an edit saved after the first read is neither overwritten nor
        // left out of the duplicate check.
        if (fileExists)
        {
            WritePlan[] locked = new WritePlan[1];
            Exception[] failure = new Exception[1];
            ICoreRunnable operation = monitor -> {
                try
                {
                    WritePlan current = planWrite(req, file, fileExists);
                    locked[0] = current;
                    if (current.error != null)
                    {
                        return;
                    }
                    // A replaceAll edits the text its plan read: checked once more right before the write, so a save
                    // from outside Eclipse (the modify rule does not exclude it) landing after that read is refused,
                    // not overwritten (local review of #999). The window between this read and the write remains:
                    // IFile has no compare-and-swap.
                    if (current.snapshot != null && !current.snapshot.equals(BslModuleUtils.readFileText(file)))
                    {
                        locked[0] = WritePlan.refused(ToolResult.error("The module changed while replaceAll was " //$NON-NLS-1$
                            + "planning its edit (saved from outside EDT). Nothing was written. Read it again with " //$NON-NLS-1$
                            + "read_module_source and repeat the call.").toJson()); //$NON-NLS-1$
                        return;
                    }
                    mutationEntered.set(true);
                    writtenHash.set(writeFile(file, current.newLines, current.hasBom, fileExists, current.lineDelimiter));
                    mutationCommitted.set(true);
                }
                catch (Exception e)
                {
                    failure[0] = e;
                }
            };
            // The workspace comes from the resource itself - tools/impl must not add another
            // ResourcesPlugin.getWorkspace() copy (rule #4, enforced by ProjectContextAdoptionRatchetTest).
            IWorkspace workspace = file.getWorkspace();
            workspace.run(operation, workspace.getRuleFactory().modifyRule(file),
                IWorkspace.AVOID_UPDATE, null);
            if (failure[0] != null)
            {
                throw failure[0];
            }
            if (locked[0].error != null)
            {
                return locked[0].error;
            }
            plan = locked[0];
        }
        else
        {
            // Write file (the mutating step — kept inline under the passed guards)
            mutationEntered.set(true);
            writtenHash.set(writeFile(file, plan.newLines, plan.hasBom, fileExists, plan.lineDelimiter));
            // IFile.setContents/create has returned: any later exception is response work after the
            // workspace mutation, not a refusal that left the module untouched.
            mutationCommitted.set(true);
        }
        List<String> newLines = plan.newLines;
        int totalOriginal = plan.totalOriginal;
        List<String> stillCalled = plan.stillCalled;
        List<String> deletedMethods = plan.deletedMethods;
        String duplicateWarning = plan.duplicateWarning;
        // The facts of the plan the write ran on - the one computed under the modify rule for an existing module.
        BslWholeWordReplace.Result replaceAll = plan.replaceAll;

        // The module of an object ADOPTED into an extension gets the state EDT's UI records in the owner's
        // .mdo when code is added to an adopted module - Extended - recorded at once (#711): EDT's own
        // listener sets it only in the model and never saves the .mdo. Every other module is untouched and
        // answers as before. A dry run (#465) must skip this step - it changes the model and exports the
        // owner's .mdo.
        String contentHash = writtenHash.get();
        return finishWrite(contentHash,
            () -> AdoptedModuleState.ensureExtended(file.getProject(), scope, file.getProjectRelativePath().toString()),
            moduleState -> buildSuccessResponse(req.projectName, req.modulePath, req.mode, req.skipSyntaxCheck,
                newLines, fileExists, totalOriginal, contentHash, charFix.summary(), moduleState,
                methodFacts(req, deletedMethods, stillCalled, duplicateWarning, replaceAll)));
    }

    /** What {@link #planWrite} found: a ready refusal, or the lines to write and how to write them. */
    private static final class WritePlan
    {
        final String error;
        final List<String> newLines;
        final boolean hasBom;
        final String lineDelimiter;
        final int totalOriginal;
        final List<String> stillCalled;
        final List<String> deletedMethods;
        /** What the write does to repeated method names that does not block it (#987), or {@code null}. */
        private String duplicateWarning;
        /** mode=replaceAll: the count, lines and other-case spellings of the text this plan rewrites (#996). */
        private BslWholeWordReplace.Result replaceAll;
        /** mode=replaceAll: the exact text this plan read and edited, checked again before the write; else null. */
        private String snapshot;

        WritePlan(String error, List<String> newLines, boolean hasBom, String lineDelimiter, int totalOriginal,
            List<String> stillCalled, List<String> deletedMethods)
        {
            this.error = error;
            this.newLines = newLines;
            this.hasBom = hasBom;
            this.lineDelimiter = lineDelimiter;
            this.totalOriginal = totalOriginal;
            this.stillCalled = stillCalled;
            this.deletedMethods = deletedMethods;
        }

        /** Attaches the repeated-method-name warning of the write (#987) and returns this plan. */
        WritePlan withDuplicateWarning(String warning)
        {
            this.duplicateWarning = warning;
            return this;
        }

        /** Attaches what a replaceAll found in the text it rewrites (#996) and returns this plan. */
        WritePlan withReplaceAll(BslWholeWordReplace.Result replaced)
        {
            this.replaceAll = replaced;
            return this;
        }

        /** Attaches the text a replaceAll edited, to be checked again before the write, and returns this plan. */
        WritePlan withSnapshot(String text)
        {
            this.snapshot = text;
            return this;
        }

        static WritePlan refused(String error)
        {
            return new WritePlan(error, null, false, null, 0, null, null);
        }
    }

    /**
     * Reads the module as it is now, applies the {@code expectedHash} guard, computes the new lines for the
     * request's mode and runs the BSL syntax check - everything a write decides before it writes, with no write.
     * Called once before the locked write and once more under the file's modify rule (see {@link #writeModule}).
     */
    private WritePlan planWrite(WriteRequest req, IFile file, boolean fileExists) throws Exception
    {
        // Read current content (if file exists)
        List<String> originalLines;
        boolean hasBom;
        String lineDelimiter;
        String currentText = null;
        if (fileExists)
        {
            originalLines = BslModuleUtils.readFileLines(file);
            hasBom = detectBom(file);
            // Preserve the module's OWN line separator (issue #305): EDT writes BSL modules with CRLF,
            // and the raw \n write flipped every line to LF, marking the whole module changed. Detect
            // from the raw text (readFileText keeps the original separators; readFileLines strips them).
            currentText = BslModuleUtils.readFileText(file);
            lineDelimiter = resolveLineDelimiter(file.getProject(), currentText);
        }
        else
        {
            originalLines = new ArrayList<>();
            hasBom = true; // New BSL files should have BOM
            lineDelimiter = resolveLineDelimiter(file.getProject(), null);
        }

        // A replaceAll edits the text this plan read, so its guard vouches for that very text; every other mode reads
        // the module again for the guard, as before (local review of #999).
        String hashError = checkExpectedHashGuard(req, file, fileExists, req.isReplaceAll() ? currentText : null);
        if (hashError != null)
        {
            return WritePlan.refused(hashError);
        }

        // Compute new content based on mode
        int totalOriginal = originalLines.size();
        NewLinesResult computed = computeNewLines(req, file, fileExists, originalLines, currentText);
        if (computed.error != null)
        {
            return WritePlan.refused(computed.error);
        }
        List<String> newLines = computed.newLines;
        String tooLarge = replaceAllSizeRefusal(computed.replaceAll, newLines, lineDelimiter);
        if (tooLarge != null)
        {
            return WritePlan.refused(tooLarge);
        }
        String unchanged = replaceAllNoChangeRefusal(computed.replaceAll, newLines, lineDelimiter, currentText);
        if (unchanged != null)
        {
            return WritePlan.refused(unchanged);
        }
        List<String> stillCalled = computed.stillCalled;
        List<String> deletedMethods = computed.deletedMethods;

        // BSL syntax check
        if (!req.skipSyntaxCheck)
        {
            String syntaxError = runSyntaxCheck(newLines);
            if (syntaxError != null)
            {
                return WritePlan.refused(syntaxError);
            }
        }
        // Method names (#987): the block-balance check cannot see a second procedure or function of one name,
        // and the platform then refuses to load the whole module. Judged on the text this write produces
        // against the text it replaces, in every mode - and again under the modify rule, with the rest of the plan.
        DuplicateCheck duplicates = checkDuplicateMethods(originalLines, newLines, req.skipSyntaxCheck);
        if (duplicates.error != null)
        {
            return WritePlan.refused(duplicates.error);
        }
        return new WritePlan(null, newLines, hasBom, lineDelimiter, totalOriginal, stillCalled, deletedMethods)
            .withDuplicateWarning(duplicates.warning).withReplaceAll(computed.replaceAll)
            .withSnapshot(computed.replaceAll == null ? null : currentText);
    }

    /**
     * The bound of a replaceAll (#996) on the text it WRITES (#999 review): the rule measured the {@code \n}-normalized
     * text, and a module written with {@code \r\n} is one character longer per line.
     *
     * @return the refusal JSON, or {@code null} when the request is not a replaceAll or the text fits
     */
    private static String replaceAllSizeRefusal(BslWholeWordReplace.Result replaceAll, List<String> newLines,
        String lineDelimiter)
    {
        if (replaceAll == null)
        {
            return null;
        }
        long written = fileContentLength(newLines, lineDelimiter);
        if (written <= BslWholeWordReplace.MAX_RESULT_LENGTH)
        {
            return null;
        }
        return ToolResult.error("replaceAll would write a module of " + written //$NON-NLS-1$
            + " characters (its line delimiters included), more than the " + BslWholeWordReplace.MAX_RESULT_LENGTH //$NON-NLS-1$
            + " allowed. Nothing was written.").toJson(); //$NON-NLS-1$
    }

    /**
     * A replaceAll whose write would put back exactly the text the module holds (#999 review): the write re-applies
     * the module's own line delimiter and final line break and drops a blank last line, so replacements that only
     * touch those are erased by the write itself. Judged on the text the write produces against the text the module
     * holds - the bytes that change - not on the request's strings or the line lists - and without building it.
     *
     * @return the refusal JSON, or {@code null} when the request is not a replaceAll or the write changes the module
     */
    private static String replaceAllNoChangeRefusal(BslWholeWordReplace.Result replaceAll, List<String> newLines,
        String lineDelimiter, String currentText)
    {
        if (replaceAll == null || currentText == null || !writesExactly(newLines, lineDelimiter, currentText))
        {
            return null;
        }
        return ToolResult.error("replaceAll would write the module exactly as it is: the " //$NON-NLS-1$
            + replaceAll.replacements() + " replacement(s) only touch line breaks the write puts back as they were " //$NON-NLS-1$
            + "(the module's own line delimiter, its final line break). Nothing was written. Every write keeps them: " //$NON-NLS-1$
            + "to change a line break, change the lines around it (join or split them).").toJson(); //$NON-NLS-1$
    }

    /** The outcome of {@link #checkDuplicateMethods}: a ready refusal, or a warning for the response, or neither. */
    static final class DuplicateCheck
    {
        final String error;
        final String warning;

        DuplicateCheck(String error, String warning)
        {
            this.error = error;
            this.warning = warning;
        }
    }

    /**
     * The repeated-method-name rule of a write (#987): a write that CREATES a certain duplicate - a name declared
     * twice in one preprocessor context, or once more than before - is refused, nothing written; with the syntax
     * check skipped it is written and the response warns instead. A possible duplicate (separate {@code #If}
     * blocks, different compilation directives) and a duplicate the module already had are reported as a warning
     * and never block, so a legacy module stays editable. Pure: no I/O. Package-visible for tests.
     *
     * @param originalLines the module before the write (empty for a new module)
     * @param newLines the lines the write would produce
     * @param skipSyntaxCheck whether the caller skipped the syntax check
     * @return the refusal JSON or the warning text
     */
    static DuplicateCheck checkDuplicateMethods(List<String> originalLines, List<String> newLines,
        boolean skipSyntaxCheck)
    {
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(originalLines, newLines);
        String refusal = verdict.refusal();
        if (refusal != null && !skipSyntaxCheck)
        {
            return new DuplicateCheck(ToolResult.error("BSL duplicate method check failed. " + refusal).toJson(), //$NON-NLS-1$
                null);
        }
        return new DuplicateCheck(null, verdict.warning(skipSyntaxCheck));
    }

    /**
     * The facts a copy or a deletion (#978) and a repeated method name (#987) add to the success response, or
     * {@code null} when there are none.
     */
    private static MethodFacts methodFacts(WriteRequest req, List<String> deletedMethods, List<String> stillCalled,
        String duplicateWarning, BslWholeWordReplace.Result replaceAll)
    {
        MethodFacts facts = null;
        if (req.copy != null)
        {
            facts = MethodFacts.copied(req.copy.methods, req.copy.fromModulePath, req.copy.fromContentHash);
        }
        else if (req.isDelete())
        {
            facts = MethodFacts.deleted(deletedMethods, stillCalled);
        }
        else if (replaceAll != null)
        {
            facts = MethodFacts.replacedAll(replaceAll);
        }
        if (duplicateWarning == null)
        {
            return facts;
        }
        return (facts == null ? MethodFacts.none() : facts).withDuplicateWarning(duplicateWarning);
    }

    /**
     * What a copy ({@code copiedMethods}, {@code fromModulePath}, {@code fromContentHash}) or a deletion
     * ({@code deletedMethods} and the calls left behind) reports in the success frontmatter (#978), and the
     * repeated-method-name warning of the write (#987).
     */
    static final class MethodFacts
    {
        final List<String> copiedMethods;
        final String fromModulePath;
        final String fromContentHash;
        final List<String> deletedMethods;
        final List<String> stillCalled;
        /** What the write does to repeated method names that does not block it (#987), or {@code null}. */
        final String duplicateWarning;
        /** mode=replaceAll: the occurrences replaced and the other-case spellings left (#996), or {@code null}. */
        final BslWholeWordReplace.Result replaceAll;

        private MethodFacts(List<String> copiedMethods, String fromModulePath, String fromContentHash,
            List<String> deletedMethods, List<String> stillCalled, String duplicateWarning,
            BslWholeWordReplace.Result replaceAll)
        {
            this.copiedMethods = copiedMethods;
            this.fromModulePath = fromModulePath;
            this.fromContentHash = fromContentHash;
            this.deletedMethods = deletedMethods;
            this.stillCalled = stillCalled;
            this.duplicateWarning = duplicateWarning;
            this.replaceAll = replaceAll;
        }

        static MethodFacts copied(List<String> methods, String fromModulePath, String fromContentHash)
        {
            return new MethodFacts(methods, fromModulePath, fromContentHash, null, List.of(), null, null);
        }

        static MethodFacts deleted(List<String> methods, List<String> stillCalled)
        {
            return new MethodFacts(null, null, null, methods, stillCalled == null ? List.of() : stillCalled, null,
                null);
        }

        /** A replaceAll (#996): the count and lines of the replaced occurrences, the other-case spellings left. */
        static MethodFacts replacedAll(BslWholeWordReplace.Result replaced)
        {
            return new MethodFacts(null, null, null, null, List.of(), null, replaced);
        }

        /** No copy and no deletion: only the warning a later {@link #withDuplicateWarning} attaches. */
        static MethodFacts none()
        {
            return new MethodFacts(null, null, null, null, List.of(), null, null);
        }

        /** These facts plus the repeated-method-name warning of the write (#987). */
        MethodFacts withDuplicateWarning(String warning)
        {
            return new MethodFacts(copiedMethods, fromModulePath, fromContentHash, deletedMethods, stillCalled,
                warning, replaceAll);
        }
    }

    /**
     * The end of a write whose text has landed: runs the adopted-module step, then answers with the
     * success response - or, when the step failed, with an error that says the text IS written
     * ({@code mutationCommitted:true}), so neither the caller nor a harness takes it for a refusal and
     * re-sends the edit. The step is run HERE, not handed in as a result, so a write cannot run it and
     * answer past its failure: leaving this call out leaves the step out with it. Package-visible for tests.
     *
     * @param contentHash the {@code contentHash} of the text the write produced; the error carries it too, since
     *     the text IS on disk (#978); {@code null} leaves it out
     * @param step the adopted-module step; run exactly once
     * @param successResponse builds the success response from the step's result; not called when it failed
     * @return the response
     */
    static String finishWrite(String contentHash, Supplier<AdoptedModuleState.Result> step,
        Function<AdoptedModuleState.Result, String> successResponse)
    {
        AdoptedModuleState.Result moduleState = step.get();
        if (moduleState.failed())
        {
            return withWrittenHash(ToolResult.errorAfterMutation(moduleState.failureMessage()), contentHash).toJson();
        }
        return successResponse.apply(moduleState);
    }

    /**
     * Adds the {@code contentHash} of the written text to an error that follows a committed write (#978): the
     * module holds exactly that text, so the token is as true as after a success, and a caller can chain its
     * next guarded edit on it.
     *
     * @param error the {@code errorAfterMutation} result
     * @param contentHash the token of the written text, or {@code null} when it is not known
     * @return {@code error}
     */
    private static ToolResult withWrittenHash(ToolResult error, String contentHash)
    {
        return contentHash == null ? error : error.put(CONTENT_HASH, contentHash);
    }

    /**
     * Optimistic-lock guard (any mode): when the caller carried an {@code expectedHash} from
     * its last read, reject if the module changed since — a cheap lost-update check that
     * complements searchReplace's {@code oldSource} match and replace's {@code expectedSource}.
     * Reads the canonical text the same way those guards do (readFileText, {@code \n}-normalized)
     * so the hashes always agree; skipped entirely when no {@code expectedHash} is given.
     *
     * @return a ready {@link ToolResult#error} JSON payload to return verbatim, or {@code null}
     *         when the guard passes (or is disabled)
     */
    private static String checkExpectedHashGuard(WriteRequest req, IFile file, boolean fileExists, String currentText)
        throws Exception
    {
        // The text the plan already read, when given (replaceAll, local review of #999): the guard then vouches for the
        // very text that is edited, not for a later read another writer could have changed in between.
        String currentTextForHash =
            (req.expectedHash != null && !req.expectedHash.isEmpty() && fileExists)
                ? (currentText != null ? currentText : BslModuleUtils.readFileText(file)).replace("\r\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                : null;
        return evaluateExpectedHash(currentTextForHash, req.expectedHash, fileExists);
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
    private static String validateWriteArguments(Map<String, String> params, WriteRequest req)
    {
        String err = JsonUtils.requireArgument(params, PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        String methodsError = validateMethodsArguments(req);
        if (methodsError != null)
        {
            return ToolResult.error(methodsError).toJson();
        }
        if (req.source == null && !req.isCopy() && !req.isDelete())
        {
            return ToolResult.error("source is required (or fromModulePath + methodNames to copy methods, " //$NON-NLS-1$
                + "or mode=deleteMethods + methodNames to delete them)").toJson(); //$NON-NLS-1$
        }
        if (req.source != null && req.source.length() > MAX_SOURCE_LENGTH)
        {
            return ToolResult.error("source exceeds maximum allowed length (" + MAX_SOURCE_LENGTH + " characters)").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Validate mode
        if (!MODE_REPLACE.equals(req.mode) && !MODE_APPEND.equals(req.mode)
            && !MODE_SEARCH_REPLACE.equals(req.mode) && !isMethodTargetedMode(req.mode) && !req.isDelete()
            && !req.isReplaceAll())
        {
            return ToolResult.error("invalid mode '" + req.mode + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Allowed: searchReplace, replace, append, replaceMethod, insertBefore, " + //$NON-NLS-1$
                "insertAfter, deleteMethods, replaceAll").toJson(); //$NON-NLS-1$
        }
        if (req.isDelete() && (req.expectedHash == null || req.expectedHash.trim().isEmpty()))
        {
            return ToolResult.error("expectedHash is required for mode=deleteMethods. Read the module with " //$NON-NLS-1$
                + "read_module_source and pass its contentHash - or, to finish a move, the fromContentHash " //$NON-NLS-1$
                + "the copy answered.").toJson(); //$NON-NLS-1$
        }

        // Validate oldSource for searchReplace mode
        if (MODE_SEARCH_REPLACE.equals(req.mode)
            && (req.oldSource == null || req.oldSource.isEmpty()))
        {
            return ToolResult.error("oldSource is required for searchReplace mode").toJson(); //$NON-NLS-1$
        }
        if (req.isReplaceAll())
        {
            String replaceAllError = validateReplaceAllArguments(req);
            if (replaceAllError != null)
            {
                return ToolResult.error(replaceAllError).toJson();
            }
        }
        if (isMethodTargetedMode(req.mode))
        {
            if (req.methodName == null || req.methodName.trim().isEmpty())
            {
                return ToolResult.error("methodName is required for mode=" + req.mode).toJson(); //$NON-NLS-1$
            }
            if (req.expectedHash == null || req.expectedHash.trim().isEmpty())
            {
                return ToolResult.error("expectedHash is required for mode=" + req.mode + //$NON-NLS-1$
                    ". Read the module with read_module_source or read_method_source and pass " + //$NON-NLS-1$
                    "its contentHash.").toJson(); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * The argument rules of a copy ({@code fromModulePath}) and of {@code mode=deleteMethods} (#978): each takes
     * {@code methodNames} and no {@code source}; a copy runs only in a mode that adds methods; and
     * {@code methodNames} means nothing to any other call, so it is refused there rather than ignored.
     *
     * @param req the parsed request
     * @return the refusal message, or {@code null}
     */
    private static String validateMethodsArguments(WriteRequest req)
    {
        if (req.isCopy() && req.source != null)
        {
            return "source and fromModulePath are alternatives: pass exactly one way to supply the methods."; //$NON-NLS-1$
        }
        if (req.isDelete() && req.source != null)
        {
            return "mode=deleteMethods takes no source: it deletes the methods named in methodNames."; //$NON-NLS-1$
        }
        if (req.isCopy() && !MODE_APPEND.equals(req.mode) && !MODE_INSERT_BEFORE.equals(req.mode)
            && !MODE_INSERT_AFTER.equals(req.mode) && !MODE_REPLACE.equals(req.mode))
        {
            return "fromModulePath supports mode append (the default), insertBefore, insertAfter, or replace of " //$NON-NLS-1$
                + "a module that does not exist yet; got '" + req.mode + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!req.isCopy() && !req.isDelete())
        {
            return req.methodNames == null ? null
                : "methodNames applies only with fromModulePath (to copy methods) or mode=deleteMethods; mode=" //$NON-NLS-1$
                    + req.mode + " takes source."; //$NON-NLS-1$
        }
        return BslMethodTransfer.validateNames(req.methodNames);
    }

    /**
     * The argument rules of {@code mode=replaceAll} (#996): it takes {@code oldSource} and {@code source} (an empty
     * {@code source} deletes the occurrences) and none of the arguments that address a method or guard a whole-file
     * replace - they would be silently ignored otherwise. {@code methodNames} and {@code fromModulePath} are refused
     * by {@link #validateMethodsArguments} already.
     *
     * @param req the parsed request
     * @return the refusal message, or {@code null}
     */
    private static String validateReplaceAllArguments(WriteRequest req)
    {
        if (req.oldSource == null || req.oldSource.isEmpty())
        {
            return "oldSource is required for replaceAll mode: the fragment whose every whole-word occurrence is " //$NON-NLS-1$
                + "replaced with source."; //$NON-NLS-1$
        }
        if (req.methodName != null && !req.methodName.isEmpty())
        {
            return "methodName does not apply to mode=replaceAll: it replaces every whole-word occurrence of " //$NON-NLS-1$
                + "oldSource in the whole module. Drop methodName, or use mode=replaceMethod for one method."; //$NON-NLS-1$
        }
        if (req.expectedSource != null || req.overwrite)
        {
            return "expectedSource and overwrite apply only to mode=replace; mode=replaceAll is guarded by " //$NON-NLS-1$
                + "expectedHash (the contentHash of your last read)."; //$NON-NLS-1$
        }
        if (req.oldSource.replace("\r\n", "\n").equals(req.source.replace("\r\n", "\n"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            return "oldSource and source are the same text: nothing to replace. Nothing was written."; //$NON-NLS-1$
        }
        return null;
    }

    private static boolean isMethodTargetedMode(String mode)
    {
        return MODE_REPLACE_METHOD.equals(mode) || MODE_INSERT_BEFORE.equals(mode)
            || MODE_INSERT_AFTER.equals(mode);
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
        /** mode=deleteMethods: the deleted methods as declared, and those still called (#978). */
        final List<String> deletedMethods;
        final List<String> stillCalled;
        /** mode=replaceAll: how many occurrences were replaced, where, and what was left (#996). */
        final BslWholeWordReplace.Result replaceAll;

        private NewLinesResult(String error, List<String> newLines)
        {
            this(error, newLines, null, null, null);
        }

        private NewLinesResult(String error, List<String> newLines, List<String> deletedMethods,
            List<String> stillCalled)
        {
            this(error, newLines, deletedMethods, stillCalled, null);
        }

        private NewLinesResult(String error, List<String> newLines, List<String> deletedMethods,
            List<String> stillCalled, BslWholeWordReplace.Result replaceAll)
        {
            this.error = error;
            this.newLines = newLines;
            this.deletedMethods = deletedMethods;
            this.stillCalled = stillCalled;
            this.replaceAll = replaceAll;
        }

        static NewLinesResult replacedAll(BslWholeWordReplace.Result replaced)
        {
            return new NewLinesResult(null, splitSourceLines(replaced.newContent()), null, null, replaced);
        }
    }

    /**
     * Computes the new module content for the request's mode (read-only — no file is
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
    private NewLinesResult computeNewLines(WriteRequest req, IFile file, boolean fileExists,
        List<String> originalLines, String currentText) throws Exception
    {
        if (req.copy != null)
        {
            MethodEditResult copied = applyCopy(originalLines, req.mode, req.methodName, req.copy.lines,
                req.copy.methods);
            return new NewLinesResult(copied.error, copied.newLines);
        }
        if (req.isDelete())
        {
            BslMethodTransfer.Result removed = BslMethodTransfer.remove(originalLines, req.methodNames);
            return removed.failed() ? new NewLinesResult(ToolResult.error(removed.error).toJson(), null)
                : new NewLinesResult(null, removed.lines, removed.methods, removed.stillCalled);
        }
        String source = req.source;
        switch (req.mode)
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
                boolean hashGuardSatisfied = req.expectedHash != null && !req.expectedHash.isEmpty();
                if (fileExists && !hashGuardSatisfied)
                {
                    String preconditionError =
                        checkReplacePrecondition(file, req.expectedSource, req.overwrite);
                    if (preconditionError != null)
                    {
                        return new NewLinesResult(preconditionError, null);
                    }
                }
                return new NewLinesResult(null, splitSourceLines(source));
            }

            case MODE_APPEND:
            {
                return new NewLinesResult(null, appendLines(originalLines, source));
            }

            case MODE_SEARCH_REPLACE:
            {
                // Normalize oldSource
                String oldSource = req.oldSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

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

            case MODE_REPLACE_ALL:
            {
                // The text and normalization searchReplace matches against (#996), so the spelling search_in_code
                // finds is the spelling replaced. Under the modify rule this runs on the text the file holds then,
                // and the count it reports is the count of that text.
                String oldText = req.oldSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
                // source is normalized by now (writeModule): an en dash sent for a hyphen is a hyphen again, and
                // replacing a text with itself would report replacements and rewrite nothing (#999 review).
                if (oldText.equals(source))
                {
                    return new NewLinesResult(ToolResult.error("source equals oldSource once " //$NON-NLS-1$
                        + "normalizeInvalidCharacters replaced the forbidden characters in source (the dashes, the " //$NON-NLS-1$
                        + "no-break space and the soft hyphen): nothing to replace. Nothing was written. Pass " //$NON-NLS-1$
                        + "normalizeInvalidCharacters=false to write the characters as sent.").toJson(), null); //$NON-NLS-1$
                }
                // The text planWrite read (local review of #999): the match and the "nothing would change" verdict
                // are judged on ONE read, never on two reads another writer could save between.
                String currentContent = currentText.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
                BslWholeWordReplace.Result replaced = BslWholeWordReplace.replace(currentContent, oldText, source);
                if (!replaced.replaced())
                {
                    return new NewLinesResult(ToolResult.error(replaced.failureMessage()).toJson(), null);
                }
                return NewLinesResult.replacedAll(replaced);
            }

            case MODE_REPLACE_METHOD:
            case MODE_INSERT_BEFORE:
            case MODE_INSERT_AFTER:
            {
                // No BSL model here on purpose. Loading it touches EMF/Xtext, which the sibling
                // read tools reach only through the UI thread, and this runs on the MCP dispatch
                // thread; marshalling it would add another unbounded wait on a thread a modal
                // dialog can hold. Nothing is lost: the span the model produced was accepted only
                // when it matched the text span line for line, so it could not change an outcome.
                MethodEditResult edit = applyMethodTargetedEdit(originalLines, req.mode,
                    req.methodName, source);
                return new NewLinesResult(edit.error, edit.newLines);
            }

            default:
                return new NewLinesResult(ToolResult.error("unsupported mode: " + req.mode).toJson(), null); //$NON-NLS-1$
        }
    }

    /** Result of a pure method-targeted validation and splice. */
    static final class MethodEditResult
    {
        final String error;
        final List<String> newLines;

        MethodEditResult(String error, List<String> newLines)
        {
            this.error = error;
            this.newLines = newLines;
        }
    }

    /**
     * Validates one complete incoming method, resolves one unambiguous anchor in
     * the current module, and performs the requested line splice without I/O.
     * The raw current lines are authoritative: the text span is what the splice uses, and no
     * BSL model is consulted (see the call site for why).
     */
    static MethodEditResult applyMethodTargetedEdit(List<String> originalLines, String mode,
        String methodName, String source)
    {
        Anchor anchor = findAnchor(originalLines, methodName);
        if (anchor.error != null)
        {
            return new MethodEditResult(anchor.error, null);
        }
        return applyToAnchor(originalLines, mode, methodName, source, anchor.moduleSpans, anchor.target);
    }

    /**
     * The splice of a copy (#978): the copied methods' lines go into the target as one block - at the end
     * ({@code append}), next to an anchor ({@code insertBefore}/{@code insertAfter}, the anchor found by the
     * rules of the method-targeted modes) or as the whole of a new module ({@code replace}) - with one blank
     * line at each occupied junction. Refused when the target already declares a copied name.
     *
     * @param originalLines the target's lines (empty for a new module)
     * @param mode the copy's mode
     * @param methodName the anchor, for the insert modes
     * @param methodLines the copied methods' lines
     * @param methods the copied methods' declared names
     * @return the new lines or the refusal
     */
    static MethodEditResult applyCopy(List<String> originalLines, String mode, String methodName,
        List<String> methodLines, List<String> methods)
    {
        int insertionLine = originalLines.size();
        if (MODE_INSERT_BEFORE.equals(mode) || MODE_INSERT_AFTER.equals(mode))
        {
            Anchor anchor = findAnchor(originalLines, methodName);
            if (anchor.error != null)
            {
                return new MethodEditResult(anchor.error, null);
            }
            insertionLine = MODE_INSERT_BEFORE.equals(mode) ? anchor.target.startLine : anchor.target.endLine + 1;
        }
        String conflict = BslMethodTransfer.targetConflict(originalLines, methods);
        if (conflict != null)
        {
            return methodEditError(conflict);
        }
        if (MODE_REPLACE.equals(mode))
        {
            return new MethodEditResult(null, new ArrayList<>(methodLines));
        }
        return new MethodEditResult(null, BslModuleUtils.insertMethodLines(originalLines, insertionLine, methodLines));
    }

    /** The anchor of a method-targeted write: the module's spans and the one span named by methodName. */
    private static final class Anchor
    {
        final String error;
        final List<BslModuleUtils.MethodSpan> moduleSpans;
        final BslModuleUtils.MethodSpan target;

        Anchor(String error, List<BslModuleUtils.MethodSpan> moduleSpans, BslModuleUtils.MethodSpan target)
        {
            this.error = error;
            this.moduleSpans = moduleSpans;
            this.target = target;
        }
    }

    private static Anchor anchorError(String message)
    {
        return new Anchor(ToolResult.error(message).toJson(), null, null);
    }

    /**
     * Finds the one complete declaration named {@code methodName} in an addressable module, or the refusal.
     */
    private static Anchor findAnchor(List<String> originalLines, String methodName)
    {
        // A declaration this line scanner cannot ADDRESS - the keyword alone on its line, or the
        // keyword and a name with the parenthesis on the next - makes every answer below unsafe
        // rather than merely incomplete: the method is invisible, so a span can run straight
        // through it and the duplicate-name check cannot see the name it declares. Naming the
        // line and refusing is the only honest outcome for a whole-line scanner.
        BslModuleUtils.Unaddressable hidden = BslModuleUtils.unaddressable(originalLines);
        if (hidden != null)
        {
            return anchorError("the module has " + hidden.what + " at line " //$NON-NLS-1$ //$NON-NLS-2$
                + (hidden.line + 1) + " ('" + originalLines.get(hidden.line).trim() //$NON-NLS-1$ //$NON-NLS-2$
                + "'), which method-targeted modes cannot address: " + hidden.fix //$NON-NLS-1$
                + ", or edit with mode 'searchReplace'."); //$NON-NLS-1$
        }
        // The mirror of the same blindness: a SECOND declaration written after the opener on
        // one line is invisible to every anchored rule here, so the scan counts one method
        // where two are declared and a span runs straight through the hidden one.
        int crowded = BslModuleUtils.secondDeclarationOnLine(originalLines);
        if (crowded >= 0)
        {
            return anchorError("the module declares two methods on line " + (crowded + 1) //$NON-NLS-1$
                + " ('" + originalLines.get(crowded).trim() //$NON-NLS-1$
                + "'), which method-targeted modes cannot address: one declaration per line. " //$NON-NLS-1$
                + "Split them, or edit with mode 'searchReplace'."); //$NON-NLS-1$
        }
        List<BslModuleUtils.MethodSpan> moduleSpans =
            BslModuleUtils.findMethodSpansViaText(originalLines);
        List<BslModuleUtils.MethodSpan> targets = new ArrayList<>();
        for (BslModuleUtils.MethodSpan span : moduleSpans)
        {
            if (span.name.equalsIgnoreCase(methodName))
            {
                targets.add(span);
            }
        }
        if (targets.isEmpty())
        {
            return anchorError("methodName '" + methodName + //$NON-NLS-1$
                "' was not found in the current module. Read it again with " + //$NON-NLS-1$
                "read_module_source or read_method_source and retry."); //$NON-NLS-1$
        }
        if (targets.size() > 1)
        {
            return anchorError("methodName '" + methodName + "' is ambiguous: " + //$NON-NLS-1$ //$NON-NLS-2$
                targets.size() + " declarations exist in the current module. " + //$NON-NLS-1$
                "Method-targeted writes do not choose between preprocessor branches."); //$NON-NLS-1$
        }
        if (!targets.get(0).complete)
        {
            return anchorError("methodName '" + methodName + //$NON-NLS-1$
                "' has no matching method terminator in the current module. " + //$NON-NLS-1$
                "The target span is incomplete, so no write was performed."); //$NON-NLS-1$
        }
        return new Anchor(null, moduleSpans, targets.get(0));
    }

    /**
     * The method-targeted splice of {@code source} at an anchor already found by {@link #findAnchor}.
     */
    private static MethodEditResult applyToAnchor(List<String> originalLines, String mode, String methodName,
        String source, List<BslModuleUtils.MethodSpan> moduleSpans, BslModuleUtils.MethodSpan anchorSpan)
    {

        List<String> sourceLines = splitSourceLines(source);
        // The same guard on the PAYLOAD: a declaration the scanner cannot address hides inside an
        // otherwise one-method source, and the outer method then borrows the hidden one's
        // terminator - the exactly-one-method contract would be satisfied by text that is not.
        BslModuleUtils.Unaddressable hiddenInSource = BslModuleUtils.unaddressable(sourceLines);
        if (hiddenInSource != null)
        {
            return methodEditError("source has " + hiddenInSource.what + " at line " //$NON-NLS-1$ //$NON-NLS-2$
                + (hiddenInSource.line + 1) + " ('" //$NON-NLS-1$
                + sourceLines.get(hiddenInSource.line).trim()
                + "'), which this mode cannot address: " + hiddenInSource.fix + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // And on the payload: "Procedure A() Function B()" reads as ONE method to the scan and
        // as balanced pairs to the syntax check, so without this it would be written as a
        // method nested inside a method - invalid BSL reported as a success.
        int crowdedInSource = BslModuleUtils.secondDeclarationOnLine(sourceLines);
        if (crowdedInSource >= 0)
        {
            return methodEditError("source declares two methods on line " //$NON-NLS-1$
                + (crowdedInSource + 1) + " ('" + sourceLines.get(crowdedInSource).trim() //$NON-NLS-1$ //$NON-NLS-2$
                + "'): this mode takes exactly one method, and a declaration after the opener " //$NON-NLS-1$
                + "on the same line is not one. Put one declaration per line."); //$NON-NLS-1$
        }
        List<BslModuleUtils.MethodSpan> sourceSpans =
            BslModuleUtils.findMethodSpansViaText(sourceLines);
        if (sourceSpans.size() != 1)
        {
            return methodEditError("source must contain exactly one complete Procedure/Function " + //$NON-NLS-1$
                "for mode=" + mode + "; found " + sourceSpans.size() + " declarations."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BslModuleUtils.MethodSpan incoming = sourceSpans.get(0);
        if (!incoming.complete)
        {
            return methodEditError("source must contain exactly one complete Procedure/Function; " + //$NON-NLS-1$
                "declaration '" + incoming.name + "' has no matching method terminator."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (hasNonBlankLinesOutside(sourceLines, incoming.startLine, incoming.endLine))
        {
            return methodEditError("source must consist of exactly one complete Procedure/Function " + //$NON-NLS-1$
                "plus its contiguous documentation and annotations; module-level code or " + //$NON-NLS-1$
                "detached text outside that method is not allowed."); //$NON-NLS-1$
        }

        if (MODE_REPLACE_METHOD.equals(mode)
            && !incoming.name.equalsIgnoreCase(methodName))
        {
            return methodEditError("source declares method '" + incoming.name + //$NON-NLS-1$
                "', but replaceMethod targets '" + methodName + "'. Rename is not supported; " + //$NON-NLS-1$ //$NON-NLS-2$
                "declare the targeted method in source."); //$NON-NLS-1$
        }
        if (!MODE_REPLACE_METHOD.equals(mode))
        {
            for (BslModuleUtils.MethodSpan existing : moduleSpans)
            {
                if (existing.name.equalsIgnoreCase(incoming.name))
                {
                    return methodEditError("cannot insert method '" + incoming.name + //$NON-NLS-1$
                        "': a declaration with that name already exists in the module. " + //$NON-NLS-1$
                        "Read the module again and choose a unique method name."); //$NON-NLS-1$
                }
            }
        }

        BslModuleUtils.MethodSpan target = anchorSpan;
        if (MODE_REPLACE_METHOD.equals(mode) && incoming.isFunction != target.isFunction)
        {
            // Refused for the same reason a rename is: matching the NAME is not the whole
            // contract. Turning a Function into a Procedure takes the return value away from
            // every expression that consumes Target(), and no check below can see it -
            // Function/EndFunction and Procedure/EndProcedure are each balanced on their own.
            return methodEditError("source declares " //$NON-NLS-1$
                + (incoming.isFunction ? "a Function" : "a Procedure") //$NON-NLS-1$ //$NON-NLS-2$
                + ", but '" + methodName + "' is " //$NON-NLS-1$ //$NON-NLS-2$
                + (target.isFunction ? "a Function" : "a Procedure") //$NON-NLS-1$ //$NON-NLS-2$
                + " in the module. replaceMethod does not change the kind of a method, because " //$NON-NLS-1$
                + "callers of a Function consume its return value; declare the same kind in " //$NON-NLS-1$
                + "source, or change the kind deliberately: delete the method with mode=deleteMethods, then add " //$NON-NLS-1$
                + "the new one with insertBefore, insertAfter or append."); //$NON-NLS-1$
        }
        List<String> result;
        if (MODE_REPLACE_METHOD.equals(mode))
        {
            result = new ArrayList<>(originalLines);
            result.subList(target.startLine, target.endLine + 1).clear();
            result.addAll(target.startLine, sourceLines);
        }
        else
        {
            int insertionLine = MODE_INSERT_BEFORE.equals(mode) ? target.startLine : target.endLine + 1;
            result = BslModuleUtils.insertMethodLines(originalLines, insertionLine, sourceLines);
        }
        return new MethodEditResult(null, result);
    }

    private static MethodEditResult methodEditError(String message)
    {
        return new MethodEditResult(ToolResult.error(message).toJson(), null);
    }

    private static boolean hasNonBlankLinesOutside(List<String> lines, int startLine, int endLine)
    {
        for (int i = 0; i < lines.size(); i++)
        {
            if ((i < startLine || i > endLine) && !lines.get(i).trim().isEmpty())
            {
                return true;
            }
        }
        return false;
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
     * {@code linesBefore}/{@code newFile} branch the inline flow used. Package-visible for tests.
     *
     * @param contentHash the {@code contentHash} of the text the write produced, see {@link #writeFile}
     * @param moduleState the adopted-module step's result: a key and, when it has one, a paragraph
     *     for the module of an adopted object; nothing for any other module
     * @return the wrapped success response
     */
    static String buildSuccessResponse(String projectName, String modulePath, String mode, // NOSONAR the response's facts; a parameter object would only rename them
        boolean skipSyntaxCheck, List<String> newLines, boolean fileExists, int totalOriginal,
        String contentHash, String normalizedCharacters, AdoptedModuleState.Result moduleState)
    {
        return buildSuccessResponse(projectName, modulePath, mode, skipSyntaxCheck, newLines, fileExists,
            totalOriginal, contentHash, normalizedCharacters, moduleState, null);
    }

    /**
     * {@link #buildSuccessResponse(String, String, String, boolean, List, boolean, int, String, String,
     * AdoptedModuleState.Result)} with what a copy or a deletion of methods reports (#978): a copy names the
     * methods, its source and the source's token ({@code fromContentHash}), and says how to finish a move; a
     * deletion names the methods and warns about the calls left behind; a repeated method name that does not
     * block the write joins the frontmatter's {@code warning} (#987). Package-visible for tests.
     *
     * @param facts the copy's, deletion's or repeated-name facts, or {@code null} when there are none
     */
    static String buildSuccessResponse(String projectName, String modulePath, String mode, // NOSONAR the response's facts; a parameter object would only rename them
        boolean skipSyntaxCheck, List<String> newLines, boolean fileExists, int totalOriginal,
        String contentHash, String normalizedCharacters, AdoptedModuleState.Result moduleState, MethodFacts facts)
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
        // The token of the text just written - the one read_module_source reports for it - so the next guarded
        // edit carries it as expectedHash without reading the module again (#978).
        if (contentHash != null)
        {
            fm.put(CONTENT_HASH, contentHash);
        }
        if (facts != null && facts.copiedMethods != null)
        {
            fm.putList("copiedMethods", facts.copiedMethods) //$NON-NLS-1$
                .put(FROM_MODULE_PATH, facts.fromModulePath)
                .put("fromContentHash", facts.fromContentHash); //$NON-NLS-1$
        }
        if (facts != null && facts.deletedMethods != null)
        {
            fm.putList("deletedMethods", facts.deletedMethods); //$NON-NLS-1$
        }
        // A replaceAll (#996) names how many occurrences it replaced and on which lines of the text before it.
        if (facts != null && facts.replaceAll != null)
        {
            fm.put("replacements", facts.replaceAll.replacements()) //$NON-NLS-1$
                .put("replacedLines", facts.replaceAll.replacedLinesText()); //$NON-NLS-1$
        }
        List<String> warnings = new ArrayList<>();
        if (facts != null && !facts.stillCalled.isEmpty())
        {
            warnings.add("Deleted methods are still called in this module (a text check of unqualified calls " //$NON-NLS-1$
                + "outside comments and strings): " + String.join(", ", facts.stillCalled) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Qualify the calls with the new module's name or move the callers too."); //$NON-NLS-1$
        }
        if (facts != null && facts.duplicateWarning != null)
        {
            warnings.add(facts.duplicateWarning);
        }
        String caseVariants = facts == null || facts.replaceAll == null ? null : facts.replaceAll.caseVariantWarning();
        if (caseVariants != null)
        {
            warnings.add(caseVariants);
        }

        // Only when something WAS replaced: a key that is always there would be noise on every
        // write, while a silent replacement would leave the caller believing it wrote what it
        // sent. Absent means the source went in untouched.
        if (normalizedCharacters != null)
        {
            fm.put("normalizedCharacters", normalizedCharacters); //$NON-NLS-1$
        }
        // Written as asked (the platform accepts it), but EDT's model will miss what follows.
        String overlap = BslSyntaxChecker.directiveOverlap(newLines);
        if (overlap != null)
        {
            warnings.add(0, overlap + ". The 1C platform accepts overlapping #Region and #If blocks, " //$NON-NLS-1$
                + "but EDT cannot parse them: EDT-based tools will not see the methods after this point. " //$NON-NLS-1$
                + "Nest the blocks inside each other."); //$NON-NLS-1$
        }
        if (!warnings.isEmpty())
        {
            fm.put("warning", String.join(" ", warnings)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // The same rule: only for the module of an object adopted into an extension (#711) - every
        // other write answers byte for byte as before. The paragraph comes only with a switch, a state
        // left as it is, a missing extension block or a warning; on every write into an already-Extended
        // module the key alone says it.
        String extensionState = moduleState.frontMatterValue();
        if (extensionState != null)
        {
            fm.put(AdoptedModuleState.FRONT_MATTER_KEY, extensionState);
        }
        String note = moduleState.bodySentence();
        String body = note == null ? "File written successfully" //$NON-NLS-1$
            : "File written successfully\n\n" + note; //$NON-NLS-1$
        if (facts != null && facts.copiedMethods != null)
        {
            body += "\n\nTo finish a move, call mode=deleteMethods on fromModulePath with the same methodNames and " //$NON-NLS-1$
                + "expectedHash=" + facts.fromContentHash + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return fm.wrapContent(body);
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

        EClass ownerClass = (EClass)MdClassPackage.eINSTANCE.getEClassifier(englishType);
        boolean supported = switch (moduleType)
        {
            case "FormModule" -> "CommonForm".equals(englishType) //$NON-NLS-1$ //$NON-NLS-2$
                || ownerClass.getEStructuralFeature("forms") instanceof EReference; //$NON-NLS-1$
            case MODULE_TYPE_COMMAND_MODULE -> "CommonCommand".equals(englishType) //$NON-NLS-1$
                || ownerClass.getEStructuralFeature("commands") instanceof EReference; //$NON-NLS-1$
            case MODULE_TYPE_MODULE -> "CommonForm".equals(englishType) //$NON-NLS-1$
                || AdoptedModuleState.moduleFeature(ownerClass, moduleType) != null;
            case MODULE_TYPE_OBJECT_MODULE, "ManagerModule", "RecordSetModule" -> //$NON-NLS-1$ //$NON-NLS-2$
                AdoptedModuleState.moduleFeature(ownerClass, moduleType) != null;
            default -> true;
        };
        if (!supported)
        {
            return ToolResult.error("metadata type '" + typePart + "' has no " + moduleType //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use list_modules and pass an existing modulePath.").toJson(); //$NON-NLS-1$
        }

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
     * WebService, HTTPService, Bot, IntegrationService, WebSocketClient), {@code CommandModule} for CommonCommand, and
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
            || "HTTPService".equals(englishType) //$NON-NLS-1$
            || "Bot".equals(englishType) //$NON-NLS-1$
            || "IntegrationService".equals(englishType) //$NON-NLS-1$
            || "WebSocketClient".equals(englishType)) //$NON-NLS-1$
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
     * Whether the text ends with a line separator in any of the three spellings.
     *
     * @param source the payload
     * @return whether it ends with a separator
     */
    private static boolean endsWithSeparator(String source)
    {
        return source.endsWith("\n") || source.endsWith("\r"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The module's lines after {@code mode=append}: the current lines, then the source's. An EMPTY
     * source appends nothing, so the write rewrites the module's current text - the no-op an adopted
     * module's step is re-run with (#711). Package-visible for tests.
     *
     * @param originalLines the module's current lines
     * @param source the appended text
     * @return the new lines
     */
    static List<String> appendLines(List<String> originalLines, String source)
    {
        List<String> appended = new ArrayList<>(originalLines);
        appended.addAll(splitSourceLines(source));
        return appended;
    }

    /**
     * Splits source code into lines, handling trailing newline artifact. An empty source is no line
     * at all, not one empty line.
     */
    static List<String> splitSourceLines(String source)
    {
        if (source.isEmpty())
        {
            return new ArrayList<>();
        }

        // All three separators, not just the newline: a payload written with bare carriage
        // returns would otherwise arrive as ONE element holding several physical lines, and
        // every rule here reasons about whole lines.
        String[] parts = source.split("\\r\\n|\\r|\\n", -1); //$NON-NLS-1$
        List<String> lines = new ArrayList<>(Arrays.asList(parts));

        // A trailing separator produces a trailing empty element; remove it to avoid adding an
        // extra blank line. ANY separator, not just the newline: the split above cuts on all
        // three, so a payload ending in a bare carriage return produces that element too - and
        // testing only for the newline left it in, splicing a blank line into the module.
        if (endsWithSeparator(source) && lines.size() > 1
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
     * The line delimiter to write the module with. An EXISTING module keeps its OWN dominant
     * delimiter (so an edit does not flip every line to LF and mark the whole module changed —
     * issue #305); a brand-new module (or an existing single-line one with no delimiter) uses the
     * project's configured line separator, falling back to the platform default.
     *
     * @param project the module's project (for the line-separator preference scope)
     * @param rawCurrentText the file's raw content WITH its original separators, or {@code null}
     *     for a new file
     * @return {@code "\r\n"} or {@code "\n"}
     */
    private static String resolveLineDelimiter(IProject project, String rawCurrentText)
    {
        String detected = detectDominantDelimiter(rawCurrentText);
        if (detected != null)
        {
            return detected;
        }
        String pref = Platform.getPreferencesService().getString(Platform.PI_RUNTIME,
            Platform.PREF_LINE_SEPARATOR, null,
            new IScopeContext[] { new ProjectScope(project), InstanceScope.INSTANCE });
        return (pref != null && !pref.isEmpty()) ? pref : System.lineSeparator();
    }

    /**
     * The dominant line delimiter of {@code text} ({@code "\r\n"} vs {@code "\n"}), or {@code null}
     * when the text is null/empty or has no line break at all (a single line, where the caller falls
     * back to the project default). A tie counts as CRLF (the EDT/1C convention for BSL modules).
     */
    static String detectDominantDelimiter(String text)
    {
        if (text == null || text.isEmpty())
        {
            return null;
        }
        int crlf = countOccurrences(text, "\r\n"); //$NON-NLS-1$
        int lf = countOccurrences(text, "\n") - crlf; // bare LF not part of a CRLF //$NON-NLS-1$
        if (crlf == 0 && lf == 0)
        {
            return null;
        }
        return crlf >= lf ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The text {@link #writeFile} writes for these lines: joined with the file's own delimiter and ending with it.
     * A module with no lines stays empty (#978: deleting every method): a lone delimiter would be one empty line to
     * every reader, against linesAfter=0. Package-visible for tests.
     */
    static String fileContent(List<String> lines, String lineDelimiter)
    {
        String content = String.join(lineDelimiter, lines);
        if (!lines.isEmpty() && !content.endsWith(lineDelimiter))
        {
            content += lineDelimiter;
        }
        return content;
    }

    /**
     * The length of {@link #fileContent} for these lines, computed from the line lengths without building the text
     * (#999 review): the size bound of a replaceAll runs in both planning passes. Package-visible for tests.
     */
    static long fileContentLength(List<String> lines, String lineDelimiter)
    {
        if (lines.isEmpty())
        {
            return 0;
        }
        long length = (long) lineDelimiter.length() * (lines.size() - 1);
        for (String line : lines)
        {
            length += line.length();
        }
        // The joined text ends with the delimiter exactly when its last delimiter.length() characters spell it; they
        // lie in the last line, or in the delimiter before it and the last line.
        String last = lines.get(lines.size() - 1);
        String tail = lines.size() > 1 && last.length() < lineDelimiter.length() ? lineDelimiter + last : last;
        return tail.endsWith(lineDelimiter) ? length : length + lineDelimiter.length();
    }

    /**
     * Whether {@link #fileContent} of these lines equals {@code text}, decided without building it (local review of
     * #999): the lengths first, then the lines and delimiters in place, stopping at the first difference.
     * Package-visible for tests.
     */
    static boolean writesExactly(List<String> lines, String lineDelimiter, String text)
    {
        if (fileContentLength(lines, lineDelimiter) != text.length())
        {
            return false;
        }
        int offset = 0;
        for (int index = 0; index < lines.size(); index++)
        {
            String line = lines.get(index);
            if (!text.startsWith(line, offset))
            {
                return false;
            }
            offset += line.length();
            if (index < lines.size() - 1)
            {
                if (!text.startsWith(lineDelimiter, offset))
                {
                    return false;
                }
                offset += lineDelimiter.length();
            }
        }
        // The lengths agree, so what is left is the final delimiter the writer adds, or nothing.
        return offset == text.length() || text.startsWith(lineDelimiter, offset);
    }

    /**
     * Writes lines to the file, preserving BOM if needed. {@code lineDelimiter} is the module's own
     * separator (see {@link #resolveLineDelimiter}); {@code lines} are LF-normalized (no {@code \r}),
     * so joining with it re-applies the file's convention verbatim.
     *
     * @return the {@code contentHash} of the text written (#978): {@link ContentHash#of} over the written
     *     bytes decoded as read_module_source decodes the file ({@link BslModuleUtils#decodeWrittenText}). It
     *     is computed from the bytes written, not by reading the file again: a re-read could vouch for a later
     *     write by someone else, which the next guarded edit must refuse.
     */
    private String writeFile(IFile file, List<String> lines, boolean withBom,
        boolean fileExists, String lineDelimiter) throws Exception
    {
        String content = fileContent(lines, lineDelimiter);

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
        // The written bytes decoded the way read_module_source decodes the file (a leading EF BB BF is a BOM even
        // when the text itself began with U+FEFF; without a BOM the file's declared charset applies), so the token
        // is the one a read reports, whatever the charset - and still not taken from a second read of the file.
        return ContentHash.of(BslModuleUtils.decodeWrittenText(file, output));
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

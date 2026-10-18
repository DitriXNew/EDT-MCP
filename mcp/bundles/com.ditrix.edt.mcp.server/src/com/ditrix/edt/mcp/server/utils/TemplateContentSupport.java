/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.textdocument.model.FileAwareTextDocument;
import com._1c.g5.v8.dt.textdocument.model.TextDocumentFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * EDT's template creation and content rules. Call model operations inside a BM boundary.
 * Text content belongs to an Eclipse file; its FileAwareTextDocument is only a URI proxy.
 */
public final class TemplateContentSupport
{
    private static final String TEXT = "text"; //$NON-NLS-1$
    private static final String EXPECTED_HASH = "expectedHash"; //$NON-NLS-1$
    private static final String SUPPORTED = "TextDocument, SpreadsheetDocument"; //$NON-NLS-1$

    private TemplateContentSupport()
    {
    }

    /** Resolve enum names/literals first, then EDT's own Russian labels (spaces optional). */
    public static TemplateType resolveType(String value)
    {
        EEnumLiteral literal = MetadataPropertyIntrospector.resolveEnumLiteral(
            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE_TYPE, value);
        if (literal != null)
        {
            return (TemplateType)literal.getInstance();
        }
        if (value == null)
        {
            return null;
        }
        var bundle = Platform.getBundle("com._1c.g5.v8.dt.md.ui"); //$NON-NLS-1$
        var resource = bundle == null ? null : bundle.getEntry("localization/FeatureNames_ru.properties"); //$NON-NLS-1$
        if (resource == null)
        {
            return null;
        }
        Properties labels = new Properties();
        try (InputStream input = resource.openStream())
        {
            labels.load(input);
        }
        catch (IOException error)
        {
            throw new IllegalStateException("Cannot read EDT template type labels; retry after EDT initializes.", //$NON-NLS-1$
                error);
        }
        for (TemplateType type : TemplateType.VALUES)
        {
            String label = labels.getProperty("TemplateType|" + type.getLiteral()); //$NON-NLS-1$
            if (label != null && label.replace(" ", "").equalsIgnoreCase(value.replace(" ", ""))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                return type;
            }
        }
        return null;
    }

    /** An actionable refusal for a new unsupported content type, or null. */
    public static String unsupportedTypeError(TemplateType type, String supplied)
    {
        if (type == TemplateType.TEXT_DOCUMENT || type == TemplateType.SPREADSHEET_DOCUMENT)
        {
            return null;
        }
        return ToolResult.error("Unsupported templateType '" + supplied + "'. Supported: " + SUPPORTED //$NON-NLS-1$ //$NON-NLS-2$
            + ". Create a template with one of these types using create_metadata.").toJson(); //$NON-NLS-1$
    }

    /**
     * An actionable refusal for a retype target, or null. A DataCompositionSchema is accepted here (but
     * not at creation): the retype leaves the content empty and the dcs tool materializes the schema on
     * its first write, as it does for any template declared as a DCS.
     */
    public static String unsupportedRetypeError(TemplateType type, String supplied)
    {
        if (type == TemplateType.DATA_COMPOSITION_SCHEMA || type == TemplateType.TEXT_DOCUMENT
            || type == TemplateType.SPREADSHEET_DOCUMENT)
        {
            return null;
        }
        return ToolResult.error("Unsupported templateType '" + supplied + "'. Supported: " + SUPPORTED //$NON-NLS-1$ //$NON-NLS-2$
            + ", DataCompositionSchema (an empty template only; author it with the dcs tool).").toJson(); //$NON-NLS-1$
    }

    /** Reproduce the type's new-wizard content; the supplied file is EDT's external-property path. */
    public static String initialize(BasicTemplate template, TemplateType type, IBmTransaction tx,
        IDtProject project, ITopObjectFqnGenerator names, IFile file)
    {
        String refusal = unsupportedTypeError(type, String.valueOf(type));
        if (refusal != null)
        {
            throw Refusals.argument(refusal);
        }
        template.setTemplateType(type);
        if (type == TemplateType.TEXT_DOCUMENT)
        {
            if (file == null)
            {
                throw new IllegalStateException("Cannot resolve Template.txt; retry after EDT initializes."); //$NON-NLS-1$
            }
            try
            {
                FileUtil.createEmptyFileIfNotExist(file);
            }
            catch (CoreException error)
            {
                throw new IllegalStateException("Cannot create Template.txt: " + error.getMessage(), error); //$NON-NLS-1$
            }
            EObject proxy = TextDocumentFactory.eINSTANCE.createFileAwareTextDocument();
            ((InternalEObject)proxy).eSetProxyURI(URI.createPlatformResourceURI(
                file.getFullPath().toString(), true).appendFragment("/")); //$NON-NLS-1$
            template.setTemplate(proxy);
            return null;
        }
        SpreadsheetDocument document = project == null ? SheetFactory.createSpreadsheetDocument()
            : SheetFactory.createInteractiveSpreadsheetDocument(project);
        document.setTemplateMode(true);
        String fqn = names == null ? null : names.generateExternalPropertyFqn(template,
            MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        if (fqn == null || fqn.isEmpty())
        {
            throw new IllegalStateException("Cannot generate template content FQN; retry after EDT initializes."); //$NON-NLS-1$
        }
        tx.attachTopObject((IBmObject)document, fqn);
        template.setTemplate(document);
        return fqn;
    }

    /** No-op for the same type; replacing content is permitted only when it is empty. */
    public static String retype(BasicTemplate template, TemplateType type, IBmTransaction tx,
        IDtProject project, ITopObjectFqnGenerator names, IFile textFile)
    {
        if (template.getTemplateType() == type)
        {
            return null;
        }
        String refusal = unsupportedRetypeError(type, String.valueOf(type));
        if (refusal != null)
        {
            throw Refusals.argument(refusal);
        }
        requireEmpty(template, textFile, project);
        detachContent(template, tx);
        if (type == TemplateType.DATA_COMPOSITION_SCHEMA)
        {
            // Left empty on purpose: the dcs tool creates and attaches the schema on its first write.
            template.setTemplateType(type);
            return null;
        }
        return initialize(template, type, tx, project, names, textFile);
    }

    private static void requireEmpty(BasicTemplate template, IFile file, IDtProject project)
    {
        if (project == null && template.getTemplate() instanceof SpreadsheetDocument)
        {
            throw Refusals.state("Project services are not available yet; retry after the project is ready."); //$NON-NLS-1$
        }
        if (hasContent(template, file, project))
        {
            throw contentRefusal(template);
        }
    }

    private static IllegalArgumentException contentRefusal(BasicTemplate template)
    {
        return Refusals.argument("Cannot change templateType: template '" + template.getName() //$NON-NLS-1$
            + "' already has content. Keep its type, or use create_metadata to create a separate " //$NON-NLS-1$
            + "TextDocument or SpreadsheetDocument template."); //$NON-NLS-1$
    }

    /** A legacy empty external-property reference, never a file-backed text document. */
    public static boolean isPlaceholder(EObject content)
    {
        return content == null || !(content instanceof FileAwareTextDocument)
            && (content.eIsProxy() || content.eClass() == EcorePackage.Literals.EOBJECT);
    }

    /** Conservative for other content models; spreadsheet creation defaults are not authored content. */
    public static boolean hasContent(BasicTemplate template, IFile textFile)
    {
        return hasContent(template, textFile, null);
    }

    private static boolean hasContent(BasicTemplate template, IFile textFile, IDtProject project)
    {
        if (template.getTemplateType() == TemplateType.TEXT_DOCUMENT && !readText(textFile).isEmpty())
        {
            return true;
        }
        EObject content = template.getTemplate();
        if (content == null)
        {
            return false;
        }
        if (content instanceof FileAwareTextDocument)
        {
            // A mislabeled file-backed reference cannot be proven empty from another type's path.
            return template.getTemplateType() != TemplateType.TEXT_DOCUMENT || !readText(textFile).isEmpty();
        }
        if (content instanceof SpreadsheetDocument)
        {
            // Compare the whole model, including print/view settings and format tables. Without a
            // project only the simpler creation baseline can be proven empty; any extra state refuses.
            SpreadsheetDocument baseline = project == null ? SheetFactory.createSpreadsheetDocument()
                : SheetFactory.createInteractiveSpreadsheetDocument(project);
            baseline.setTemplateMode(true);
            return !EcoreUtil.equals(content, baseline);
        }
        if (content.eIsProxy())
        {
            // An unresolved real resource cannot be proven empty.
            return true;
        }
        for (EStructuralFeature feature : content.eClass().getEAllStructuralFeatures())
        {
            if (!feature.isDerived() && content.eIsSet(feature))
            {
                return true;
            }
        }
        return false;
    }

    private static void detachContent(BasicTemplate template, IBmTransaction tx)
    {
        EObject content = template.getTemplate();
        template.setTemplate(null);
        // Only an ATTACHED top object (a spreadsheet's own content resource) is detached. A text
        // template's content is a FileAwareTextDocument proxy onto Template.txt with no BM
        // namespace, and the platform's detachTopObject throws on such an object.
        if (content instanceof IBmObject && ((IBmObject)content).bmIsTop()
            && ((IBmObject)content).bmGetNamespace() != null)
        {
            tx.detachTopObject((IBmObject)content);
        }
    }

    /** Validate the mutually exclusive text and spreadsheet payloads before any file mutation. */
    public static String payloadError(BasicTemplate template, JsonObject spec, String fqn)
    {
        if (spec.has(TEXT) && template.getTemplateType() != TemplateType.TEXT_DOCUMENT)
        {
            return ToolResult.error("Template '" + fqn + "' is not a TextDocument template. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use template.cells for a SpreadsheetDocument, or create_metadata with " //$NON-NLS-1$
                + "templateType=TextDocument before writing template.text.").toJson(); //$NON-NLS-1$
        }
        if (template.getTemplateType() == TemplateType.TEXT_DOCUMENT)
        {
            for (String key : spec.keySet())
            {
                if (!TEXT.equals(key) && !EXPECTED_HASH.equals(key))
                {
                    return ToolResult.error("TextDocument template '" + fqn + "' cannot accept '" + key //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Use template:{text:\"...\", expectedHash?:\"...\"}; cells, areas and other " //$NON-NLS-1$
                        + "spreadsheet keys require a SpreadsheetDocument template.").toJson(); //$NON-NLS-1$
                }
            }
            if (!isString(spec.get(TEXT)) || spec.has(EXPECTED_HASH) && !isString(spec.get(EXPECTED_HASH)))
            {
                return ToolResult.error("TextDocument template requires a string 'text' and an optional string " //$NON-NLS-1$
                    + "'expectedHash'. Read the text and contentHash with get_metadata_details.").toJson(); //$NON-NLS-1$
            }
        }
        else if (spec.has(EXPECTED_HASH))
        {
            return ToolResult.error("template.expectedHash is supported only with template.text on a " //$NON-NLS-1$
                + "TextDocument template; use template.cells for a SpreadsheetDocument.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    private static boolean isString(JsonElement value)
    {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    /** File-backed write, inside the caller's BM write boundary; null means it was written. */
    public static String writeText(BasicTemplate template, IFile file, JsonObject spec)
    {
        return writeText(template, file, spec, null);
    }

    /** Enlist file changes only AFTER validation, so a refusal never rolls back somebody else's edit. */
    public static String writeText(BasicTemplate template, IFile file, JsonObject spec, Context context)
    {
        String refusal = payloadError(template, spec, template.getName());
        if (refusal != null)
        {
            return refusal;
        }
        String expected = spec.has(EXPECTED_HASH) ? spec.get(EXPECTED_HASH).getAsString() : null;
        refusal = expectedHashError(file, expected);
        if (refusal != null)
        {
            return refusal;
        }
        String[] lateError = new String[1];
        try
        {
            IWorkspace workspace = file.getWorkspace();
            // Already inside the BM write lambda: setContents acquires this same file rule there,
            // so holding it across the final check and write adds no new lock class.
            ICoreRunnable operation = monitor -> {
                lateError[0] = expectedHashError(file, expected);
                if (lateError[0] == null)
                {
                    lateError[0] = writeTextContents(file, spec.get(TEXT).getAsString(), context);
                }
            };
            workspace.run(operation, workspace.getRuleFactory().modifyRule(file), IWorkspace.AVOID_UPDATE, null);
            return lateError[0];
        }
        catch (CoreException error)
        {
            throw textWriteFailure(error);
        }
    }

    private static String expectedHashError(IFile file, String expected)
    {
        if (expected != null && !expected.isEmpty())
        {
            String current = readText(file);
            if (!ContentHash.matches(current, expected))
            {
                return ToolResult.error("expectedHash does not match the current template text; nothing was " //$NON-NLS-1$
                    + "written. Current contentHash: " + ContentHash.of(current) //$NON-NLS-1$
                    + ". Read get_metadata_details again and retry with that contentHash.").toJson(); //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String writeTextContents(IFile file, String text, Context context)
    {
        try
        {
            boolean exists = file.exists();
            byte[] old = exists ? bytes(file) : new byte[0];
            boolean bom = old.length >= 3 && (old[0] & 0xFF) == 0xEF && (old[1] & 0xFF) == 0xBB
                && (old[2] & 0xFF) == 0xBF;
            Charset charset = bom ? StandardCharsets.UTF_8
                : Charset.forName(file.getCharset());
            final byte[] encoded;
            try
            {
                ByteBuffer buffer = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(bom ? "\uFEFF" + text : text)); //$NON-NLS-1$
                encoded = new byte[buffer.remaining()];
                buffer.get(encoded);
            }
            catch (CharacterCodingException error)
            {
                return ToolResult.error("Cannot write Template.txt with file charset '" + charset.name() //$NON-NLS-1$
                    + "': the text contains characters it cannot represent. Use text representable in this " //$NON-NLS-1$
                    + "charset, or change the file encoding in EDT and retry.").toJson(); //$NON-NLS-1$
            }
            if (context != null)
            {
                context.beforeWrite(file);
            }
            FileUtil.createEmptyFileIfNotExist(file);
            file.setContents(new ByteArrayInputStream(encoded), IResource.KEEP_HISTORY, null);
            return null;
        }
        catch (CoreException | IOException error)
        {
            throw textWriteFailure(error);
        }
    }

    private static IllegalStateException textWriteFailure(Exception error)
    {
        return new IllegalStateException("Cannot write Template.txt; refresh the project and retry: " + error.getMessage(), error); //$NON-NLS-1$
    }

    /** Preserve actual line separators and the final newline, reusing the module file decoder. */
    public static String readText(IFile file)
    {
        if (file == null || !file.exists())
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            return BslModuleUtils.readFileText(file);
        }
        catch (Exception error)
        {
            throw new IllegalStateException("Cannot read Template.txt; refresh the project and retry: " //$NON-NLS-1$
                + error.getMessage(), error);
        }
    }

    /** Hash precedes the bounded body and always covers the full text. */
    public static String renderText(String fqn, String text)
    {
        int budget = OutputSizeGuard.MAX_CONTENT_CHARS / 4;
        String shown = text.length() > budget ? text.substring(0, budget) : text;
        // Choose a CommonMark fence longer than any run in the displayed data.
        int longest = 2;
        int run = 0;
        for (int i = 0; i < shown.length(); i++)
        {
            run = shown.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1); //$NON-NLS-1$
        String heading = "## Text template: " + MarkdownUtils.inlineCode(fqn) + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + FrontMatter.create().put("contentHash", ContentHash.of(text)).build() + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String notice = text.length() > shown.length()
            ? OutputSizeGuard.buildTruncationNotice(text.length(), budget) : ""; //$NON-NLS-1$
        return heading + fence + "text\n" + shown + "\n" + fence + "\n" + notice; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static byte[] bytes(IFile file) throws CoreException, IOException
    {
        try (InputStream input = file.getContents())
        {
            return input.readAllBytes();
        }
    }

    /** Resolve the same project filesystem service the creation wizards use. */
    public static IFile contentFile(IProject project, BasicTemplate template)
    {
        var plugin = MdPlugin.getDefault();
        if (plugin == null || plugin.getInjector() == null)
        {
            throw new IllegalStateException("EDT metadata services are unavailable; retry after initialization."); //$NON-NLS-1$
        }
        IProjectFileSystemSupportProvider provider =
            plugin.getInjector().getInstance(IProjectFileSystemSupportProvider.class);
        IProjectFileSystemSupport filesystem = provider.getProjectFileSystemSupport(project);
        IFile file = filesystem == null ? null
            : filesystem.getFile(template, MdClassPackage.Literals.BASIC_TEMPLATE__TEMPLATE);
        if (file == null)
        {
            throw new IllegalStateException("Cannot resolve template content file; refresh the project and retry."); //$NON-NLS-1$
        }
        return file;
    }

    /**
     * File changes accompany a BM transaction. Restore original bytes if that transaction fails,
     * since Eclipse file operations are not rolled back by BM itself.
     */
    public static final class Context implements AutoCloseable
    {
        private final IProject project;
        private final Map<IFile, byte[]> originals = new LinkedHashMap<>();
        private final Set<IFile> changed = new LinkedHashSet<>();
        private boolean committed;

        public Context(IProject project)
        {
            this.project = project;
        }

        private void remember(IFile file)
        {
            if (!originals.containsKey(file))
            {
                try
                {
                    originals.put(file, file.exists() ? bytes(file) : null);
                }
                catch (CoreException | IOException error)
                {
                    throw new IllegalStateException("Cannot snapshot template content before writing; refresh the project and retry.", error); //$NON-NLS-1$
                }
            }
        }

        private void beforeWrite(IFile file)
        {
            remember(file);
            changed.add(file);
        }

        public String initialize(BasicTemplate template, TemplateType type, IBmTransaction tx)
        {
            template.setTemplateType(type);
            IFile file = contentFile(project, template);
            if (type == TemplateType.TEXT_DOCUMENT && !file.exists())
            {
                beforeWrite(file);
            }
            var activator = Activator.getDefault();
            var manager = activator.getDtProjectManager();
            IDtProject dtProject = manager == null ? null : manager.getDtProject(project);
            return TemplateContentSupport.initialize(template, type, tx, dtProject,
                activator.getTopObjectFqnGenerator(), file);
        }

        public String retype(BasicTemplate template, TemplateType type, IBmTransaction tx)
        {
            if (template.getTemplateType() == type)
            {
                return null;
            }
            String refusal = unsupportedRetypeError(type, String.valueOf(type));
            if (refusal != null)
            {
                throw Refusals.argument(refusal);
            }
            TemplateType oldType = template.getTemplateType();
            IFile oldFile = contentFile(project, template);
            final IFile newFile;
            // Ask EDT for the new type's path, then restore the type before the shared guard.
            // This is inside the write boundary and performs no file mutation.
            try
            {
                template.setTemplateType(type);
                newFile = contentFile(project, template);
            }
            finally
            {
                template.setTemplateType(oldType);
            }
            var activator = Activator.getDefault();
            var manager = activator.getDtProjectManager();
            IDtProject dtProject = manager == null ? null : manager.getDtProject(project);
            // The shared retype guard runs before any file change is enlisted.
            requireEmpty(template, oldFile, dtProject);
            if (type == TemplateType.TEXT_DOCUMENT && !newFile.exists())
            {
                beforeWrite(newFile);
            }
            // The model is retyped first: deleting an attached spreadsheet's Template.mxlx before the
            // content is detached turns it into an unresolved proxy, which the guard reads as content.
            String fqn = TemplateContentSupport.retype(template, type, tx, dtProject,
                activator.getTopObjectFqnGenerator(), oldType == TemplateType.TEXT_DOCUMENT ? oldFile : newFile);
            try
            {
                if (oldFile.exists() && !oldFile.equals(newFile))
                {
                    IWorkspace workspace = oldFile.getWorkspace();
                    var rules = workspace.getRuleFactory();
                    ICoreRunnable operation = monitor -> {
                        // An editor may have saved text since the early guard. The final check and the
                        // delete hold both file rules; a refusal here escapes the BM write lambda, so the
                        // transaction rolls the retype back and nothing is deleted.
                        if (oldType == TemplateType.TEXT_DOCUMENT && !readText(oldFile).isEmpty())
                        {
                            throw contentRefusal(template);
                        }
                        beforeWrite(oldFile);
                        oldFile.delete(IResource.KEEP_HISTORY, null);
                    };
                    workspace.run(operation, MultiRule.combine(rules.modifyRule(oldFile), rules.deleteRule(oldFile)),
                        IWorkspace.AVOID_UPDATE, null);
                }
            }
            catch (CoreException error)
            {
                throw new IllegalStateException("Cannot remove the old empty template content file.", error);
            }
            return fqn;
        }

        public void commit()
        {
            committed = true;
        }

        @Override
        public void close()
        {
            if (committed)
            {
                return;
            }
            for (Map.Entry<IFile, byte[]> original : originals.entrySet())
            {
                IFile file = original.getKey();
                if (!changed.contains(file))
                {
                    continue;
                }
                try
                {
                    if (original.getValue() == null)
                    {
                        if (file.exists())
                        {
                            file.delete(IResource.KEEP_HISTORY, null);
                        }
                    }
                    else if (file.exists())
                    {
                        file.setContents(new ByteArrayInputStream(original.getValue()), IResource.KEEP_HISTORY, null);
                    }
                    else
                    {
                        file.create(new ByteArrayInputStream(original.getValue()), true, null);
                    }
                }
                catch (CoreException error)
                {
                    throw new IllegalStateException("Cannot restore template content after a failed transaction.", //$NON-NLS-1$
                        error);
                }
            }
        }
    }
}

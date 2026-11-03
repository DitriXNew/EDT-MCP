/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/** Shared resolution and EDT-native editor opening. All model reads stay in a BM read task. */
public final class EditorNavigation
{
    public static final long UI_TIMEOUT_MILLIS = 5000;

    public record Request(String projectName, String fqn, String modulePath, Integer line, String methodName)
    {
        public Request
        {
            if ((fqn != null && fqn.isBlank()) || (modulePath != null && modulePath.isBlank()))
            {
                throw new IllegalArgumentException("fqn/modulePath cannot be blank. Pass exactly one valid target.");
            }
            if ((fqn == null || fqn.isBlank()) == (modulePath == null || modulePath.isBlank()))
            {
                throw new IllegalArgumentException("Pass exactly one of fqn or modulePath.");
            }
            if (fqn != null && (line != null || methodName != null))
            {
                throw new IllegalArgumentException("line and methodName require modulePath; omit fqn.");
            }
            if (line != null && methodName != null)
            {
                throw new IllegalArgumentException("line and methodName are mutually exclusive; pass only one.");
            }
            if (line != null && line < 1)
            {
                throw new IllegalArgumentException("Invalid line '" + line + "'. Pass a positive 1-based line.");
            }
            if (methodName != null && methodName.isBlank())
            {
                throw new IllegalArgumentException("methodName is blank. Use get_module_structure to find a method.");
            }
        }
    }

    interface Editors
    {
        boolean openMetadata(MdObject object);
    }

    interface ModuleEditors
    {
        IEditorPart open(IFile file) throws Exception;
    }

    private EditorNavigation()
    {
    }

    public static boolean isEnabled()
    {
        Activator plugin = Activator.getDefault();
        return plugin != null && plugin.getPreferenceStore()
            .getBoolean(PreferenceConstants.PREF_ALLOW_UI_NAVIGATION);
    }

    public static void open(Request request) throws Exception
    {
        BoundedUiCall.call(active -> {
            openOnUi(request, active);
            return null;
        }, UI_TIMEOUT_MILLIS);
    }

    private static void openOnUi(Request request, BooleanSupplier active) throws Exception
    {
        requireActive(active);
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page == null)
        {
            throw new IllegalStateException("No active EDT workbench page. Open an EDT window and retry.");
        }
        ProjectContext context = ProjectContext.of(request.projectName());
        if (!context.exists())
        {
            throw new IllegalArgumentException(ProjectContext.notFoundMessage(request.projectName()));
        }
        if (!context.isOpen())
        {
            throw new IllegalArgumentException("Project '" + request.projectName() + "' is closed. Open it in EDT.");
        }
        if (request.modulePath() != null)
        {
            IFile file = BslModuleUtils.resolveModuleFile(context.project(), request.modulePath());
            if (file == null || !file.exists() || !context.project().equals(file.getProject())
                || !"bsl".equalsIgnoreCase(file.getFileExtension()))
            {
                throw new IllegalArgumentException("BSL module '" + request.modulePath() + "' not found in project '"
                    + request.projectName() + "'. Use list_modules to select a modulePath in this project.");
            }
            openModule(request, file, module -> {
                requireActive(active);
                return IDE.openEditor(page, module, true);
            });
            return;
        }
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        IBmModel model = manager == null ? null : manager.getModel(context.project());
        if (model == null)
        {
            throw new IllegalStateException("BM model unavailable for '" + request.projectName()
                + "'. Wait for the project to start; check list_projects.");
        }
        BmTransactions.read(model, "OpenEditor", (tx, monitor) -> {
            ProjectContext.ConfigurationResult root = context.resolveMetadataRoot();
            if (!root.ok())
            {
                throw new IllegalStateException(root.errorJson());
            }
            openMetadata(root.scope(), request.fqn(), object -> {
                requireActive(active);
                return new OpenHelper(page).openEditor(object) != null;
            });
            return null;
        });
    }

    static void openModule(Request request, IFile file, ModuleEditors editors) throws Exception
    {
        IEditorPart editor = editors.open(file);
        if (!(editor instanceof ITextEditor))
        {
            throw new IllegalStateException("EDT did not provide a text editor for '" + request.modulePath()
                + "'. Check its BSL editor association in EDT.");
        }
        ITextEditor textEditor = (ITextEditor)editor;
        IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (document == null)
        {
            throw new IllegalStateException("EDT did not provide a document for '" + request.modulePath()
                + "'. Reopen its BSL editor and retry open_editor.");
        }
        int line = resolveLine(List.of(document.get().split("\\r\\n|\\n|\\r", -1)),
            request.line(), request.methodName());
        textEditor.selectAndReveal(document.getLineOffset(line - 1), 0);
    }

    private static void requireActive(BooleanSupplier active)
    {
        if (!active.getAsBoolean() || !isEnabled())
        {
            throw new IllegalStateException("UI navigation was disabled or the open_editor request expired. "
                + "Check 'Allow UI navigation' in MCP Server preferences and retry.");
        }
    }

    static void openMetadata(MetadataScope scope, String fqn, Editors editors)
    {
        MdObject object = MetadataNodeResolver.resolveAddress(scope, MetadataTypeUtils.normalizeFqn(fqn), false);
        if (object == null)
        {
            throw new IllegalArgumentException("Metadata '" + fqn
                + "' not found. Use get_metadata_objects/get_metadata_details to find a valid FQN.");
        }
        if (object instanceof BasicForm && ((BasicForm)object).getFormType() != FormType.MANAGED)
        {
            throw new IllegalArgumentException("Form '" + fqn
                + "' is not managed. Select a managed form; EDT has no supported editor for this form type.");
        }
        if (!editors.openMetadata(object))
        {
            throw new IllegalStateException("EDT could not open an editor for '" + fqn
                + "'. Check that this object has an editor and the project is ready in EDT.");
        }
    }

    static int resolveLine(List<String> lines, Integer line, String methodName)
    {
        if (methodName != null)
        {
            for (BslModuleUtils.MethodSpan method : BslModuleUtils.findMethodSpansViaText(lines))
            {
                if (method.name.equalsIgnoreCase(methodName))
                {
                    return method.declarationLine + 1;
                }
            }
            throw new IllegalArgumentException("Method '" + methodName
                + "' not found. Use get_module_structure to select a procedure/function name.");
        }
        int target = line == null ? 1 : line;
        if (target < 1 || target > Math.max(1, lines.size()))
        {
            throw new IllegalArgumentException("Invalid line '" + target
                + "'. Use read_module_source to check the module's line range.");
        }
        return target;
    }
}

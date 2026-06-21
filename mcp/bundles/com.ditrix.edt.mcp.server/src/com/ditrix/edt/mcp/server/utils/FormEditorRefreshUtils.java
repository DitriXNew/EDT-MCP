/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Best-effort, unattended-safe refresh of an ALREADY-OPEN EDT form editor after an MCP form write.
 * <p>
 * After {@link FormElementWriter#writeEditableForm} mutates the form in the BM model and force-exports
 * the {@code .form} file to disk, a form editor that was already open keeps rendering its own stale
 * (grey / read-only) WYSIWYG until a {@code clean_project}. Recomputing derived data does NOT refresh
 * the open editor, so this helper goes straight at the editor's WYSIWYG representation: it locates the
 * open form editor for the written form and reflectively triggers the same WYSIWYG rebuild the
 * screenshot path uses ({@code rebuild(true)} on the {@code FormWysiwygRepresentation} plus
 * {@code refresh()} on the viewer), forcing the editor to re-render the freshly written model.
 * <p>
 * Everything here is best-effort and never throws: with no running workbench (headless / detached MCP
 * run), no open editor for that form, or any reflective step failing, the helper logs and returns
 * cleanly. The refresh runs on the SWT UI thread via {@link Display#asyncExec(Runnable)} so the calling
 * (MCP / BM) thread is never blocked.
 * <p>
 * There is deliberately NO compile-time dependency on the EDT form package
 * ({@code com._1c.g5.v8.dt.form.*}); the editor, its WYSIWYG page, viewer and representation are reached
 * reflectively (per the plugin code rules), reusing the public reflective accessors of
 * {@link EditorScreenshotHelper}.
 */
public final class FormEditorRefreshUtils
{
    /** Fully qualified name of the EDT form editor part (resolved reflectively, never imported). */
    private static final String FORM_EDITOR_CLASS = "com._1c.g5.v8.dt.form.ui.editor.FormEditor"; //$NON-NLS-1$

    /** Page id of the form editor's WYSIWYG (main) page. */
    private static final String FORM_MAIN_PAGE_ID = "editors.form.pages.main"; //$NON-NLS-1$

    /** {@code FormEditor.findPage(String)} - resolves a page by id. */
    private static final String FIND_PAGE_METHOD = "findPage"; //$NON-NLS-1$

    /** Field on the WYSIWYG page holding the viewer (same field {@code EditorScreenshotHelper} reads). */
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$

    private FormEditorRefreshUtils()
    {
        // Utility class
    }

    /**
     * Asynchronously refreshes the WYSIWYG of an already-open form editor for {@code formPath}, if one is
     * open. Schedules the work on the SWT UI thread and returns immediately; the caller thread is never
     * blocked. A no-op when the workbench is not running, the form path cannot be resolved, the
     * {@code .form} file does not exist, or no editor is open for that form. Never throws.
     *
     * @param project the workspace project owning the form (e.g. {@code FormEditContext.project})
     * @param formPath the normalized form path (e.g. {@code "Document.Order.forms.<Name>"} or
     *            {@code "CommonForm.<Name>"}), e.g. {@code FormEditContext.formPath}; ignored if blank
     */
    public static void refreshOpenFormEditorAsync(IProject project, String formPath)
    {
        try
        {
            if (project == null || formPath == null || formPath.isEmpty() || !PlatformUI.isWorkbenchRunning())
            {
                return;
            }
            String relativePath = MetadataPathResolver.resolveFormFilePath(formPath);
            if (relativePath == null)
            {
                return;
            }
            IFile formFile = project.getFile(new Path(relativePath));
            if (!formFile.exists())
            {
                return;
            }
            Display display = workbenchDisplayOrNull();
            if (display != null)
            {
                display.asyncExec(() -> refreshOnUiThread(formFile, formPath));
            }
        }
        catch (Exception e) // NOSONAR best-effort refresh must never propagate
        {
            Activator.logWarning("Could not schedule form editor refresh for " + formPath //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** UI-thread body: find the open editor for {@code formFile} and rebuild its WYSIWYG. */
    private static void refreshOnUiThread(IFile formFile, String formPath)
    {
        try
        {
            IEditorPart editorPart = findOpenFormEditor(formFile);
            if (editorPart != null)
            {
                refreshFormEditorWysiwyg(editorPart);
            }
        }
        catch (Exception e) // NOSONAR best-effort refresh must never propagate
        {
            Activator.logWarning("Could not refresh open form editor for " + formPath //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Finds the editor open for {@code formFile} across every workbench window and page (so an editor on a
     * non-active window/page is still found), or {@code null} when none is open. UI thread only.
     */
    private static IEditorPart findOpenFormEditor(IFile formFile)
    {
        FileEditorInput input = new FileEditorInput(formFile);
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
            {
                continue;
            }
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                {
                    continue;
                }
                IEditorPart editor = page.findEditor(input);
                if (editor != null)
                {
                    return editor;
                }
            }
        }
        return null;
    }

    /**
     * Forces a form editor's WYSIWYG to rebuild from the just-written model, exactly as the screenshot
     * path does: editor -&gt; WYSIWYG main page -&gt; viewer -&gt; representation, then {@code rebuild(true)}
     * on the representation and {@code refresh()} on the viewer. Tolerant of a not-yet-built page (skips).
     * UI thread only.
     */
    private static void refreshFormEditorWysiwyg(IEditorPart editorPart) throws Exception // NOSONAR reflective boundary; caller swallows
    {
        Object wysiwygViewer = findWysiwygViewer(editorPart);
        if (wysiwygViewer == null)
        {
            return;
        }
        Object representation = EditorScreenshotHelper.getRepresentation(wysiwygViewer);
        if (representation != null)
        {
            EditorScreenshotHelper.rebuildRepresentation(representation);
        }
        EditorScreenshotHelper.refreshViewer(wysiwygViewer);
    }

    /**
     * Reflectively resolves the WYSIWYG viewer of a form editor part: confirms the part is a
     * {@code FormEditor}, finds its main page via {@code findPage("editors.form.pages.main")} and reads
     * the page's {@code wysiwygViewer} field. {@code null} when not a form editor or the viewer is not
     * available yet. UI thread only.
     */
    private static Object findWysiwygViewer(IEditorPart editorPart) throws Exception // NOSONAR reflective boundary; caller swallows
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        if (!editorClass.isInstance(editorPart))
        {
            return null;
        }
        Method findPageMethod = ReflectionUtils.findMethod(editorPart.getClass(), FIND_PAGE_METHOD, String.class);
        if (findPageMethod == null)
        {
            return null;
        }
        findPageMethod.setAccessible(true); // NOSONAR reflective access into EDT internals (no Require-Bundle)
        Object page = findPageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
        if (page == null)
        {
            return null;
        }
        return ReflectionUtils.getFieldValue(page, WYSIWYG_VIEWER_FIELD);
    }

    /** The workbench display when running, or {@code null} on a shutdown race / headless run. */
    private static Display workbenchDisplayOrNull()
    {
        try
        {
            return PlatformUI.getWorkbench().getDisplay();
        }
        catch (IllegalStateException e)
        {
            return null;
        }
    }
}

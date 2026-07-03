/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures.handlers;

import java.lang.reflect.Method;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesGalleryRenderer;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesGalleryRenderer.RenderedPage;
import com.ditrix.edt.mcp.server.pictures.editor.StringHtmlEditorInput;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Handler for the common-pictures preview command (Russian menu label "Просмотр общих картинок
 * (превью)..."), contributed to the context menu of the
 * "Common Pictures" (CommonPictures) folder node in the 1C metadata Navigator. It renders the
 * selected project's {@link CommonPicture}s — one PAGE at a time, with server-side name/synonym
 * search — with per-variant base64 PNG thumbnails, into a single
 * {@code CommonPicturesGalleryEditor} so a developer can visually spot mismatched icons across one
 * {@code Picture.zip}.
 * <p>
 * <b>No new MCP tool.</b> This is a pure EDT-UI feature (a command + handler + editor), mirroring the
 * groups feature ({@code groups/handlers/NewGroupHandler}); it reuses the shared
 * {@code CommonPictureContentReader} the {@code list_common_pictures} / {@code export_common_picture}
 * tools use, via the shared {@link CommonPicturesGalleryRenderer} seam.
 * <p>
 * <b>Reflective navigator detection (no md.ui/navigator compile import).</b> The CommonPictures folder
 * is a {@code com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase<Configuration>}; this
 * handler must NOT compile against {@code com._1c.g5.v8.dt.md.ui} / the navigator adapter types. Exactly
 * like {@link com.ditrix.edt.mcp.server.groups.handlers.NewGroupHandler#isCollectionAdapter}, it walks
 * the selection's superclass chain for that base class and narrows via the reflective
 * {@code getModelObjectName()} equal to {@code "CommonPicture"}.
 * <p>
 * <b>Unattended / no-freeze.</b> {@link #execute(ExecutionEvent)} resolves the project on the UI thread,
 * then delegates to {@link CommonPicturesGalleryRenderer#scheduleRender}: the heavy enumerate + filter +
 * decompress + base64 work runs OFF the UI thread inside a {@code BmTransactions.read} boundary,
 * collecting fully detached POJOs (no live {@code EObject} escapes the transaction). Only building the
 * HTML and opening the editor returns to the UI thread, so the workbench never freezes. The initial
 * open renders query="" / page=0; the editor itself handles subsequent Prev/Next/search navigations.
 */
public class ShowCommonPicturesHandler extends AbstractHandler
{
    /**
     * The collection navigator adapter base class (resolved reflectively). The CommonPictures folder
     * node is a subclass of this. Matched by walking the selection's superclass chain, exactly like
     * {@code NewGroupHandler}.
     */
    private static final String COLLECTION_ADAPTER_CLASS_NAME =
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase"; //$NON-NLS-1$

    /** The model-object name a CommonPictures folder adapter reports via {@code getModelObjectName()}. */
    private static final String COMMON_PICTURE_MODEL_NAME = "CommonPicture"; //$NON-NLS-1$

    /** Reflective accessor name on the collection navigator adapter. */
    private static final String GET_MODEL_OBJECT_NAME_METHOD = "getModelObjectName"; //$NON-NLS-1$

    /** The editor id registered in {@code plugin.xml} against {@code CommonPicturesGalleryEditor}. */
    private static final String GALLERY_EDITOR_ID = "com.ditrix.edt.mcp.server.pictures.galleryEditor"; //$NON-NLS-1$

    /**
     * Editor tab title / part-name prefix; the configuration name is appended. Russian surface so the
     * gallery tab reads naturally in a Russian EDT (this is UI chrome shown to the developer, not an
     * English-only MCP tool surface).
     */
    private static final String EDITOR_TITLE_PREFIX = "Общие картинки: "; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        Object selection = HandlerUtil.getVariable(evaluationContext, "selection"); //$NON-NLS-1$
        setBaseEnabled(isCommonPicturesFolder(firstElement(selection)));
    }

    @Override
    public Object execute(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        Object selected = firstElement(selection);
        if (!isCommonPicturesFolder(selected))
        {
            return null;
        }

        IProject project = getProjectFromAdapter(selected);
        if (project == null)
        {
            Activator.logWarning("Show Common Pictures Overview: could not resolve the project from the " //$NON-NLS-1$
                + "selected Common Pictures folder."); //$NON-NLS-1$
            return null;
        }

        // Resolve the configuration on the UI thread only to resolve the synonym language once (cheap);
        // the heavy work is scheduled below and re-resolves the project/configuration fresh.
        ProjectContext.ConfigurationResult resolved = ProjectContext.of(project.getName()).resolveConfiguration();
        if (!resolved.ok())
        {
            Activator.logWarning("Show Common Pictures Overview: could not resolve the configuration for " //$NON-NLS-1$
                + project.getName());
            return null;
        }
        Configuration config = resolved.configuration();
        String language = MetadataLanguageUtils.resolveLanguageCode(config, null);
        String projectName = project.getName();

        // Initial open: query="" / page=0. The editor drives subsequent Prev/Next/search navigations.
        CommonPicturesGalleryRenderer.scheduleRender(projectName, EDITOR_TITLE_PREFIX, language, "", 0, //$NON-NLS-1$
            rendered -> openEditorOnUiThread(rendered, projectName, language));
        return null;
    }

    /**
     * Builds the editor input (carrying the project name + language for the editor's search/pagination
     * round-trip) and opens the gallery editor on the UI thread. Only this step touches the workbench;
     * all model/reader work already completed off the UI thread on the renderer's Job. Uses
     * {@link EditorScreenshotHelper#getWorkbenchPage()} for the same active-page resolution the
     * screenshot tools use.
     *
     * @param rendered the rendered HTML page + title
     * @param projectName the owning project name (carried into the input for re-render on nav)
     * @param language the synonym language code (carried into the input)
     */
    private static void openEditorOnUiThread(RenderedPage rendered, String projectName, String language)
    {
        Display.getDefault().asyncExec(() -> {
            try
            {
                IWorkbenchPage page = EditorScreenshotHelper.getWorkbenchPage();
                if (page == null)
                {
                    Activator.logWarning("Show Common Pictures Overview: no active workbench page to open the " //$NON-NLS-1$
                        + "gallery editor."); //$NON-NLS-1$
                    return;
                }
                page.openEditor(
                    new StringHtmlEditorInput(rendered.html(), rendered.title(), projectName, language),
                    GALLERY_EDITOR_ID);
            }
            catch (Exception e) // NOSONAR opening the editor can fail (part init); log and do not crash the UI thread
            {
                Activator.logError("Could not open the Common Pictures gallery editor", e); //$NON-NLS-1$
            }
        });
    }

    // ---------------------------------------------------------------------
    // Reflective navigator detection (NO md.ui / navigator compile import)
    // ---------------------------------------------------------------------

    /**
     * Reports whether the selected element is the CommonPictures folder adapter: a subclass of
     * {@code CollectionNavigatorAdapterBase} whose reflective {@code getModelObjectName()} is
     * {@code "CommonPicture"}. Mirrors {@code NewGroupHandler.isCollectionAdapter}; entirely reflective,
     * so this bundle never compiles against the md.ui / navigator adapter types.
     *
     * @param element the first selected element (may be {@code null})
     * @return {@code true} when it is the CommonPictures folder node
     */
    private static boolean isCommonPicturesFolder(Object element)
    {
        return isCollectionAdapter(element)
            && COMMON_PICTURE_MODEL_NAME.equals(invokeGetModelObjectName(element));
    }

    /**
     * Walks the element's superclass chain for {@link #COLLECTION_ADAPTER_CLASS_NAME}. Reflective by
     * design (no navigator compile import), exactly like {@code NewGroupHandler.isCollectionAdapter}.
     *
     * @param element the selected element (may be {@code null})
     * @return {@code true} when it is (a subclass of) the collection navigator adapter base
     */
    private static boolean isCollectionAdapter(Object element)
    {
        if (element == null)
        {
            return false;
        }
        Class<?> clazz = element.getClass();
        while (clazz != null)
        {
            if (COLLECTION_ADAPTER_CLASS_NAME.equals(clazz.getName()))
            {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * Reflectively invokes {@code getModelObjectName()} on the adapter, returning the model object name
     * (e.g. {@code "CommonPicture"}) or {@code null} when the method is absent or does not return a
     * String.
     *
     * @param adapter the navigator adapter
     * @return the model object name, or {@code null}
     */
    private static String invokeGetModelObjectName(Object adapter)
    {
        try
        {
            Method method = adapter.getClass().getMethod(GET_MODEL_OBJECT_NAME_METHOD);
            Object result = method.invoke(adapter);
            return result instanceof String ? (String)result : null;
        }
        catch (NoSuchMethodException e)
        {
            // Not this adapter kind (no getModelObjectName) — treated as "not a CommonPictures folder".
            return null;
        }
        catch (Exception e) // NOSONAR any reflective failure means "not the CommonPictures folder"
        {
            return null;
        }
    }

    /**
     * Resolves the workspace project behind a navigator adapter via {@code getAdapter(IProject.class)}.
     * The collection adapter is {@link IAdaptable}; this mirrors {@code NewGroupHandler}.
     *
     * @param adapter the navigator adapter
     * @return the project, or {@code null}
     */
    private static IProject getProjectFromAdapter(Object adapter)
    {
        if (adapter instanceof IAdaptable adaptable)
        {
            return adaptable.getAdapter(IProject.class);
        }
        return null;
    }

    /**
     * Returns the first element of a structured selection, or {@code null}.
     *
     * @param selection the selection object (from the event or the evaluation context)
     * @return the first selected element, or {@code null}
     */
    private static Object firstElement(Object selection)
    {
        if (selection instanceof IStructuredSelection structured)
        {
            return structured.getFirstElement();
        }
        return null;
    }
}

/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures.handlers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.PicturePageEntry;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.Variant;
import com.ditrix.edt.mcp.server.pictures.editor.StringHtmlEditorInput;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader.PictureVariantInfo;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader.PngResult;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Handler for the common-pictures preview command (Russian menu label "Просмотр общих картинок
 * (превью)..."), contributed to the context menu of the
 * "Common Pictures" (CommonPictures) folder node in the 1C metadata Navigator. It renders every
 * {@link CommonPicture} of the selected project's configuration, with per-variant base64 PNG
 * thumbnails, into a single {@link CommonPicturesGalleryEditor} so a developer can visually spot
 * mismatched icons across one {@code Picture.zip}.
 * <p>
 * <b>No new MCP tool.</b> This is a pure EDT-UI feature (a command + handler + editor), mirroring the
 * groups feature ({@code groups/handlers/NewGroupHandler}); it reuses the shared
 * {@link CommonPictureContentReader} the {@code list_common_pictures} / {@code export_common_picture}
 * tools use.
 * <p>
 * <b>Reflective navigator detection (no md.ui/navigator compile import).</b> The CommonPictures folder
 * is a {@code com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase<Configuration>}; this
 * handler must NOT compile against {@code com._1c.g5.v8.dt.md.ui} / the navigator adapter types. Exactly
 * like {@link com.ditrix.edt.mcp.server.groups.handlers.NewGroupHandler#isCollectionAdapter}, it walks
 * the selection's superclass chain for that base class and narrows via the reflective
 * {@code getModelObjectName()} equal to {@code "CommonPicture"}.
 * <p>
 * <b>Unattended / no-freeze.</b> {@link #execute(ExecutionEvent)} resolves the project + configuration
 * on the UI thread, then schedules an {@link org.eclipse.core.runtime.jobs.Job}: the heavy
 * enumerate + decompress + base64 work runs OFF the UI thread inside a {@code BmTransactions.read}
 * boundary, collecting fully detached {@link PicturePageEntry} POJOs (no live {@code EObject} escapes
 * the transaction). Only building the HTML and opening the editor returns to the UI thread via
 * {@link Display#asyncExec(Runnable)}, so the workbench never freezes on a large configuration.
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

    /**
     * Upper bound on pictures whose {@code Picture.zip} is decompressed for thumbnails. Pictures beyond
     * this cap are counted (so the page can show "showing X of Y") but are NOT decompressed, keeping a
     * huge configuration bounded. Mirrors the {@code list_common_pictures} default page size.
     */
    private static final int PAGINATION_CAP = 100;

    /** The best-thumbnail selector understood by {@link CommonPictureContentReader#exportPng}. */
    private static final String VARIANT_BEST = "best"; //$NON-NLS-1$

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

        // Resolve the live configuration on the UI thread (cheap); the heavy work is scheduled below.
        ProjectContext.ConfigurationResult resolved = ProjectContext.of(project.getName()).resolveConfiguration();
        if (!resolved.ok())
        {
            Activator.logWarning("Show Common Pictures Overview: could not resolve the configuration for " //$NON-NLS-1$
                + project.getName());
            return null;
        }
        Configuration config = resolved.configuration();
        String configName = config.getName() != null ? config.getName() : project.getName();

        scheduleGalleryJob(project, config, configName);
        return null;
    }

    /**
     * Schedules the background {@link Job} that enumerates the pictures, decompresses their thumbnails
     * inside a BM read boundary, then hands the detached data to the UI thread to build the HTML and
     * open the editor. Keeping every model read + rasterization off the UI thread is what makes this
     * unattended-safe on a large configuration.
     *
     * @param project the workspace project owning the configuration
     * @param config the resolved configuration whose CommonPictures are rendered
     * @param configName the configuration name for the editor title / page heading
     */
    private void scheduleGalleryJob(IProject project, Configuration config, String configName)
    {
        Job job = new Job("Building Common Pictures overview: " + configName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    GalleryData data = collectGalleryData(project, config, configName, monitor);
                    if (data == null)
                    {
                        return Status.OK_STATUS;
                    }
                    String html = CommonPicturesHtmlGenerator.render(data.configName, data.entries, data.total);
                    openEditorOnUiThread(html, EDITOR_TITLE_PREFIX + data.configName);
                    return Status.OK_STATUS;
                }
                catch (Exception e) // NOSONAR any model/reader/UI failure must be logged, never crash the Job
                {
                    Activator.logError("Failed to build the Common Pictures overview for " + configName, e); //$NON-NLS-1$
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "Could not build the Common Pictures overview: " + e.getMessage(), e); //$NON-NLS-1$
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /**
     * Collects the detached per-picture data inside a {@code BmTransactions.read} boundary: enumerates
     * the configuration's CommonPictures, and for each one up to {@link #PAGINATION_CAP} reads its best
     * thumbnail plus every variant thumbnail through the shared {@link CommonPictureContentReader}. One
     * broken picture is captured as a per-entry error (never aborts the page); a variant-less single-image
     * picture yields one synthetic {@code Picture.png} variant, so the "No variants" note only shows for a
     * picture with no readable image content at all. Nothing here holds a
     * live {@code EObject}, so the result is safe to format after the transaction closes.
     *
     * @param project the workspace project owning the configuration
     * @param config the resolved configuration
     * @param configName the configuration name for the page
     * @param monitor the progress monitor (may be {@code null})
     * @return the detached gallery data, or {@code null} when the BM model is unavailable
     */
    private GalleryData collectGalleryData(IProject project, Configuration config, String configName,
        IProgressMonitor monitor)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            Activator.logWarning("Show Common Pictures Overview: IBmModelManager is not available."); //$NON-NLS-1$
            return null;
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            Activator.logWarning("Show Common Pictures Overview: BM model is not available for " //$NON-NLS-1$
                + project.getName());
            return null;
        }

        // The synonym map is keyed by the language CODE (never the Language object's name); resolve the
        // configuration default code once (may be null on a language-less config).
        String language = MetadataLanguageUtils.resolveLanguageCode(config, null);

        // Model reads happen ONLY inside the BM read boundary (CLAUDE.md hard rule). The reader touches
        // the picture-content API through the current transaction; the resolved model object is handed
        // to it already inside this transaction.
        return BmTransactions.read(bmModel, "ShowCommonPictures", (tx, mon) -> //$NON-NLS-1$
            collectEntries(config, configName, language, monitor));
    }

    /**
     * Enumerates the configuration's CommonPictures inside the open read transaction, decompressing
     * thumbnails only for the first {@link #PAGINATION_CAP} pictures. Pictures beyond the cap count
     * toward the total (so the page can state "showing X of Y") but are never decompressed.
     *
     * @param config the resolved configuration
     * @param configName the configuration name for the page
     * @param language the resolved synonym language code (may be {@code null})
     * @param monitor the progress monitor (may be {@code null})
     * @return the detached gallery data
     */
    private static GalleryData collectEntries(Configuration config, String configName, String language,
        IProgressMonitor monitor)
    {
        CommonPictureContentReader reader = createReaderQuietly();

        List<CommonPicture> pictures = new ArrayList<>(config.getCommonPictures());
        int total = pictures.size();
        int shown = Math.min(total, PAGINATION_CAP);

        beginTask(monitor, "Rendering common pictures", shown); //$NON-NLS-1$

        List<PicturePageEntry> entries = new ArrayList<>();
        int index = 0;
        for (CommonPicture picture : pictures)
        {
            if (index >= PAGINATION_CAP)
            {
                // Beyond the cap: counted toward `total`, never decompressed.
                break;
            }
            if (monitor != null && monitor.isCanceled())
            {
                break;
            }
            entries.add(buildEntry(reader, picture, language));
            worked(monitor);
            index++;
        }
        done(monitor);
        return new GalleryData(configName, entries, total);
    }

    /**
     * Builds one detached {@link PicturePageEntry} from a common picture: its programmatic name and the
     * chosen-language synonym, its best inline thumbnail, and one thumbnail per variant. A picture whose
     * content cannot be read is captured as a per-entry error and never aborts the page; a variant-less
     * single-image picture yields one synthetic {@code Picture.png} variant (so it renders its image, not
     * the "No variants" note).
     * <p>
     * The frozen Q4 decision (one 'best' inline thumbnail per picture) is honoured via the dedicated
     * {@link PicturePageEntry#best} field, which the generator renders in its own prominent {@code .best}
     * block above the per-variant strip. A single-image picture's synthetic {@code Picture.png} is its
     * 'best' thumbnail; only a picture with no readable content at all leaves {@code best} {@code null}.
     *
     * @param reader the shared content reader, or {@code null} when it could not be constructed
     * @param picture the common picture (read inside the open transaction)
     * @param language the resolved synonym language code (may be {@code null})
     * @return the detached entry
     */
    private static PicturePageEntry buildEntry(CommonPictureContentReader reader, CommonPicture picture,
        String language)
    {
        PicturePageEntry entry = new PicturePageEntry();
        entry.name = picture.getName();
        entry.synonym = synonymForLanguage(picture, language);
        entry.variants = new ArrayList<>();

        if (reader == null)
        {
            entry.error = "The common-picture content reader is not available (MD plugin / injector missing)."; //$NON-NLS-1$
            return entry;
        }

        try
        {
            // 'best' inline thumbnail (densest raster; the single synthetic Picture.png for a variant-less
            // single-image picture; falls back to the first entry for an SVG-only picture). Rendered as the
            // dedicated prominent .best block above the variant strip per the frozen Q4 decision; a null
            // result (a picture with no readable content) renders nothing.
            entry.best = toThumb(VARIANT_BEST, reader.exportPng(picture, VARIANT_BEST));

            // One thumbnail per variant so mismatched icons within one Picture.zip are visible.
            for (PictureVariantInfo variant : reader.listVariants(picture))
            {
                entry.variants.add(toVariantThumb(reader, picture, variant));
            }
        }
        catch (Exception e) // NOSONAR one unreadable/corrupt Picture.zip is surfaced per-picture, never aborts the page
        {
            Activator.logError("Could not read variants of common picture " //$NON-NLS-1$
                + (entry.name != null ? entry.name : "<unnamed>"), e); //$NON-NLS-1$
            entry.error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return entry;
    }

    /**
     * Decodes one variant of a picture to a {@link Variant}. A variant that fails to decode is still
     * listed with a {@code null} base64 image and the failure captured on its {@link Variant#error} (the
     * generator then renders that error under the variant label in a {@code .variant-error} block), so a
     * single bad variant does not hide the picture's other variants and its failure stays visible.
     *
     * @param reader the shared content reader
     * @param picture the common picture (read inside the open transaction)
     * @param variant the variant metadata already read from the reader
     * @return the variant thumbnail (never {@code null}; may carry a {@code null} image + an error)
     */
    private static Variant toVariantThumb(CommonPictureContentReader reader, CommonPicture picture,
        PictureVariantInfo variant)
    {
        try
        {
            Variant decoded = toThumb(variant.name, reader.exportPng(picture, variant.name));
            // A resolvable-but-imageless variant (null PNG result) still appears as a captioned tile.
            return decoded != null ? decoded : new Variant(variant.name, null);
        }
        catch (Exception e) // NOSONAR one undecodable variant is logged and surfaced per-variant, never aborts the picture
        {
            Activator.logError("Could not decode variant " //$NON-NLS-1$
                + (variant.name != null ? variant.name : "<unnamed>"), e); //$NON-NLS-1$
            Variant thumb = new Variant();
            thumb.label = variant.name;
            thumb.base64Png = null;
            thumb.error = e.getMessage() != null ? e.getMessage() : e.toString();
            return thumb;
        }
    }

    /**
     * Builds a {@link Variant} from a decoded PNG result. A {@code null} result (the picture has no
     * multi-variant content, or the selector did not resolve) yields {@code null}.
     *
     * @param label the variant label / entry name
     * @param png the decoded PNG result, or {@code null}
     * @return the thumbnail, or {@code null} when {@code png} is {@code null}
     */
    private static Variant toThumb(String label, PngResult png)
    {
        if (png == null)
        {
            return null;
        }
        Variant thumb = new Variant();
        thumb.label = label != null ? label : png.name;
        thumb.base64Png = png.base64;
        return thumb;
    }

    /**
     * Reads a picture's synonym for the given language CODE out of its code-keyed synonym map,
     * delegating to the shared resolver (first the requested code, then any non-empty value).
     *
     * @param picture the common picture
     * @param language the preferred language code (may be {@code null})
     * @return the resolved synonym, or {@code ""} when none is available
     */
    private static String synonymForLanguage(CommonPicture picture, String language)
    {
        return MetadataLanguageUtils.getSynonymForLanguage(
            picture.getSynonym() == null ? null : picture.getSynonym().map(), language);
    }

    /**
     * Constructs the shared {@link CommonPictureContentReader}, degrading to {@code null} (and logging)
     * when the MD plugin / injector is unavailable — every picture then renders as a per-entry error
     * rather than the whole page aborting.
     *
     * @return the reader, or {@code null} when it could not be built
     */
    private static CommonPictureContentReader createReaderQuietly()
    {
        try
        {
            return new CommonPictureContentReader();
        }
        catch (Exception e) // NOSONAR a missing injector/plugin degrades every picture to an error, never aborts
        {
            Activator.logError("Could not create the common-picture content reader", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Builds the editor input and opens the gallery editor on the UI thread. Only this step touches the
     * workbench; all model/reader work already completed off the UI thread. Uses
     * {@link EditorScreenshotHelper#getWorkbenchPage()} for the same active-page resolution the
     * screenshot tools use.
     *
     * @param html the rendered HTML document
     * @param title the editor tab title / part name
     */
    private static void openEditorOnUiThread(String html, String title)
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
                page.openEditor(new StringHtmlEditorInput(html, title), GALLERY_EDITOR_ID);
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

    // ---------------------------------------------------------------------
    // Progress-monitor helpers (null-safe)
    // ---------------------------------------------------------------------

    private static void beginTask(IProgressMonitor monitor, String name, int totalWork)
    {
        if (monitor != null)
        {
            monitor.beginTask(name, totalWork);
        }
    }

    private static void worked(IProgressMonitor monitor)
    {
        if (monitor != null)
        {
            monitor.worked(1);
        }
    }

    private static void done(IProgressMonitor monitor)
    {
        if (monitor != null)
        {
            monitor.done();
        }
    }

    /**
     * Detached data carried out of the BM read boundary: the configuration name, the rendered entries
     * (up to the pagination cap) and the total picture count. Nothing here holds a live model handle.
     */
    private static final class GalleryData
    {
        final String configName;
        final List<PicturePageEntry> entries;
        final int total;

        GalleryData(String configName, List<PicturePageEntry> entries, int total)
        {
            this.configName = configName;
            this.entries = entries;
            this.total = total;
        }
    }
}

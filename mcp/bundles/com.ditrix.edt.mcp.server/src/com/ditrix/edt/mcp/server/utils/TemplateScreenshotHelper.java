/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetRect;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.moxel.sheet.UnitsConverter;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelControl;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditor;
import com._1c.g5.v8.dt.moxel.ui.editor.PositionHolder;
import com._1c.g5.v8.dt.moxel.ui.editor.ViewPort;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;

/**
 * Captures a PNG screenshot of a 1C <b>template</b> (макет) - specifically a
 * {@code SpreadsheetDocument} (табличный документ / print form) template - by opening its EDT
 * editor and rasterizing the spreadsheet off-screen.
 * <p>
 * In EDT a SpreadsheetDocument is the "moxel" model; its WHOLE used cell range is painted as one
 * continuous off-screen image - the editor-canvas look, not split into print pages - via
 * {@code MoxelControl.paintViewPort} into a self-made {@code new Image(Display.getCurrent(), w, h)} on
 * the UI thread. Unlike the managed-form render path there is no asynchronous render hop and no JVM
 * flag dependency, so a straight synchronous call on the UI thread produces the image.
 * <p>
 * All methods here must run on the SWT UI thread (via {@code Display.syncExec}); the render needs a
 * non-null {@link Display#getCurrent()}.
 */
public final class TemplateScreenshotHelper
{
    /** Page id of the SpreadsheetDocument page inside the template editor. */
    private static final String SPREADSHEET_PAGE_ID = "editors.commontemplate.pages.spreadsheet"; //$NON-NLS-1$
    private static final String SET_ACTIVE_PAGE_METHOD = "setActivePage"; //$NON-NLS-1$
    private static final String GET_EMBEDDED_EDITOR_METHOD = "getEmbeddedEditor"; //$NON-NLS-1$

    /** Upper bound on the rendered image area (pixels) - guards against an SWT image too large to allocate. */
    private static final long MAX_IMAGE_PIXELS = 120_000_000L;
    private static final int MODEL_RESOLVE_RETRIES = 20;
    private static final int MODEL_RESOLVE_INTERVAL_MS = 250;
    private static final int CONTROL_WAIT_RETRIES = 40;
    private static final int CONTROL_WAIT_INTERVAL_MS = 200;

    private TemplateScreenshotHelper()
    {
        // Utility class
    }

    /**
     * Opens the template editor, reaches its embedded SpreadsheetDocument (moxel) editor and renders
     * it to a PNG. Runs on the UI thread.
     *
     * @param projectName EDT project name
     * @param templatePath template FQN - a common template ({@code CommonTemplate.<Name>}) or an
     *            object-owned template ({@code <Type>.<Owner>.Template.<Name>})
     * @return a {@link CaptureResult} carrying the base64 PNG on success, or an error JSON
     */
    public static CaptureResult capture(String projectName, String templatePath)
    {
        try
        {
            OpenResult open = openTemplateEditor(projectName, templatePath);
            if (open.error != null)
            {
                return CaptureResult.error(open.error);
            }

            // Reach the embedded moxel editor: activating the SpreadsheetDocument page forces its
            // controls to be created (the page builds lazily). A null result means this template has
            // no spreadsheet page - i.e. it is not a SpreadsheetDocument template.
            MoxelResolveResult resolve = resolveSpreadsheetMoxelEditor(open.editorPart);
            MoxelEditor moxelEditor = resolve.editor;
            if (moxelEditor == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "Template '" + templatePath + "' is not a SpreadsheetDocument template (no " //$NON-NLS-1$ //$NON-NLS-2$
                    + "spreadsheet editor page found). Only SpreadsheetDocument (print form) " //$NON-NLS-1$
                    + "templates can be rendered to an image. [diag: " + resolve.diag + "]").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
            }

            MoxelControl control = waitForMoxelControl(moxelEditor);
            if (control == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "The template editor for '" + templatePath + "' opened but its spreadsheet control " //$NON-NLS-1$ //$NON-NLS-2$
                    + "did not finish initializing in time; the project may still be loading - try " //$NON-NLS-1$
                    + "again.").toJson()); //$NON-NLS-1$
            }

            SheetAccessor sheet = control.getSheet();
            SpreadsheetDocument document = moxelEditor.getDocument();
            if (sheet == null || document == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "Template '" + templatePath + "' editor is not ready (no spreadsheet model); try " //$NON-NLS-1$ //$NON-NLS-2$
                    + "again.").toJson()); //$NON-NLS-1$
            }

            IBmModel bmModel = resolveBmModel(projectName);
            if (bmModel == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "Could not resolve the BM model for project '" + projectName + "'.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
            }

            // The empty-content extent check and the render are the only reads of the BM-managed
            // SpreadsheetDocument's features; both run inside the executeAndRollback boundary opened by
            // renderTemplate, so no model feature is touched outside a transaction (hard-don't #1).
            RenderOutcome outcome = renderTemplate(control, bmModel);
            if (outcome.emptyContent)
            {
                return CaptureResult.error(ToolResult.error(
                    "Template '" + templatePath + "' has no content to render (the SpreadsheetDocument " //$NON-NLS-1$ //$NON-NLS-2$
                    + "is empty).").toJson()); //$NON-NLS-1$
            }
            ImageData imageData = outcome.imageData;
            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
                return CaptureResult.error(ToolResult.error(
                    "Template image is not available: '" + templatePath + "' produced no image.") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson());
            }

            return CaptureResult.success(EditorScreenshotHelper.encodePng(imageData));
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture template screenshot", e); //$NON-NLS-1$
            return CaptureResult.error(
                ToolResult.error("Failed to capture template screenshot: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the template object by FQN and opens it in the EDT template editor, returning a holder
     * with the opened editor part. Works for BOTH a common template ({@code CommonTemplate.<Name>}) and
     * an owned object template ({@code <Type>.<Owner>.Template.<Name>}): the FQN is resolved to the
     * {@code BasicTemplate} model object via {@link MetadataNodeResolver}, then opened via EDT's own
     * navigator-open path ({@link OpenHelper#openEditor(EObject)}), which builds the correct editor
     * input and opens the single TemplateEditor registered for both template eclasses. Runs on the UI
     * thread.
     */
    private static OpenResult openTemplateEditor(String projectName, String templatePath)
    {
        ProjectContext.ConfigurationResult cfg = ProjectContext.resolveConfiguration(projectName);
        if (!cfg.ok())
        {
            return OpenResult.error(cfg.errorJson());
        }

        EObject templateObject = resolveTemplateObject(cfg.configuration(), templatePath);
        if (templateObject == null)
        {
            return OpenResult.error(ToolResult.error(
                "Cannot resolve template '" + templatePath + "'. Expected a template FQN: a common " //$NON-NLS-1$ //$NON-NLS-2$
                + "template 'CommonTemplate.<Name>' or an owned object template " //$NON-NLS-1$
                + "'<Type>.<Owner>.Template.<Name>' (e.g. 'DataProcessor.Invoices.Template.Printout'). " //$NON-NLS-1$
                + "Verify the name with get_metadata_objects / get_metadata_details.").toJson()); //$NON-NLS-1$
        }
        if (!(templateObject instanceof BasicTemplate))
        {
            return OpenResult.error(ToolResult.error(
                "'" + templatePath + "' is not a template (it resolves to a " //$NON-NLS-1$ //$NON-NLS-2$
                + templateObject.eClass().getName() + "). Pass a template FQN: 'CommonTemplate.<Name>' " //$NON-NLS-1$
                + "or '<Type>.<Owner>.Template.<Name>'.").toJson()); //$NON-NLS-1$
        }

        IWorkbenchPage page = EditorScreenshotHelper.getWorkbenchPage();
        if (page == null)
        {
            return OpenResult.error(ToolResult.error("No active workbench page").toJson()); //$NON-NLS-1$
        }

        try
        {
            // EDT's own navigator-open path: it builds the correct TemplateEditorInput and opens the
            // TemplateEditor (the SAME editor is registered for the CommonTemplate and Template
            // eclasses), returning the opened part - so the downstream moxel resolution is identical
            // for common and owned templates.
            IEditorPart editorPart = new OpenHelper(page).openEditor(templateObject);
            if (editorPart == null)
            {
                return OpenResult.error(
                    ToolResult.error("Could not open template editor for: " + templatePath).toJson()); //$NON-NLS-1$
            }
            page.activate(editorPart);
            return OpenResult.page(editorPart);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open template editor for: " + templatePath, e); //$NON-NLS-1$
            return OpenResult.error(
                ToolResult.error("Failed to open template editor: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves a template FQN to its {@code BasicTemplate} model object via
     * {@link MetadataNodeResolver#resolveExisting} (handles a 2-part common template and a 4-part owned
     * object template, bilingually), retrying while the project's model is still loading. Returns the
     * resolved {@code EObject}, or {@code null} when it cannot be resolved. Runs on the UI thread.
     */
    private static EObject resolveTemplateObject(Configuration configuration, String templatePath)
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < MODEL_RESOLVE_RETRIES; i++)
        {
            try
            {
                MetadataNodeResolver.MetadataNode node =
                    MetadataNodeResolver.resolveExisting(configuration, templatePath);
                if (node != null && node.object != null)
                {
                    return node.object;
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("Template not resolvable yet: " + e.getMessage()); //$NON-NLS-1$
            }
            EditorScreenshotHelper.processEvents(display);
            sleep(MODEL_RESOLVE_INTERVAL_MS);
            EditorScreenshotHelper.processEvents(display);
        }
        return null;
    }

    /**
     * Reaches the embedded SpreadsheetDocument (moxel) editor of the opened template editor. It
     * first tries the known SpreadsheetDocument page id; if that does not resolve, it enumerates the
     * editor's pages ({@code FormEditor.pages}) and activates the one that carries an embedded
     * {@link MoxelEditor} - robust against a different page id across EDT versions. Activating a page
     * forces its (lazy) control creation, so the embedded editor and its model become available.
     * Retries while the editor finishes building. Returns a holder with the moxel editor, or a
     * diagnostic describing the pages found. Runs on the UI thread.
     */
    private static MoxelResolveResult resolveSpreadsheetMoxelEditor(IEditorPart editorPart)
    {
        Display display = Display.getCurrent();
        String diag = ""; //$NON-NLS-1$
        for (int attempt = 0; attempt < CONTROL_WAIT_RETRIES; attempt++)
        {
            // (a) Fast path: activate the known SpreadsheetDocument page id and read its embedded editor.
            MoxelEditor direct = embeddedMoxelOf(invokeSetActivePage(editorPart, SPREADSHEET_PAGE_ID));
            if (direct != null)
            {
                return MoxelResolveResult.found(direct);
            }

            // (b) Robust path: enumerate the editor's pages, activate any that exposes an embedded
            // editor, and accept the first whose embedded editor is a MoxelEditor.
            List<Object> pages = editorPages(editorPart);
            StringBuilder sb = new StringBuilder();
            sb.append(editorPart.getClass().getSimpleName()).append(" pages=").append(pages.size()); //$NON-NLS-1$
            for (Object page : pages)
            {
                if (page == null)
                {
                    continue;
                }
                boolean hasEmbedded =
                    ReflectionUtils.findMethod(page.getClass(), GET_EMBEDDED_EDITOR_METHOD) != null;
                sb.append(" | ").append(page.getClass().getSimpleName()) //$NON-NLS-1$
                    .append('#').append(pageId(page)).append(hasEmbedded ? "(emb)" : ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (hasEmbedded)
                {
                    invokeSetActivePage(editorPart, pageId(page));
                    MoxelEditor moxel = embeddedMoxelOf(page);
                    if (moxel != null)
                    {
                        return MoxelResolveResult.found(moxel);
                    }
                }
            }
            diag = sb.toString();
            EditorScreenshotHelper.processEvents(display);
            sleep(CONTROL_WAIT_INTERVAL_MS);
            EditorScreenshotHelper.processEvents(display);
        }
        return MoxelResolveResult.diag(diag);
    }

    /**
     * Activates a page of the template editor by id via the Eclipse Forms
     * {@code FormEditor.setActivePage(String)} API (reflectively; {@code TemplateEditor} is internal)
     * and returns the page instance, or {@code null}. Runs on the UI thread.
     */
    private static Object invokeSetActivePage(IEditorPart editorPart, String pageId)
    {
        if (pageId == null)
        {
            return null;
        }
        try
        {
            Method setActivePage =
                ReflectionUtils.findMethod(editorPart.getClass(), SET_ACTIVE_PAGE_METHOD, String.class);
            if (setActivePage == null)
            {
                return null;
            }
            setActivePage.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            return setActivePage.invoke(editorPart, pageId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Reads a page's embedded editor via its public {@code getEmbeddedEditor()} accessor (reflectively
     * by name; the declaring base class is internal) and returns it when it is a {@link MoxelEditor},
     * else {@code null}. The embedded editor is non-null only after the page's controls are created.
     */
    private static MoxelEditor embeddedMoxelOf(Object page)
    {
        if (page == null)
        {
            return null;
        }
        try
        {
            Object embedded = ReflectionUtils.invokeMethod(page, GET_EMBEDDED_EDITOR_METHOD);
            return embedded instanceof MoxelEditor ? (MoxelEditor)embedded : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Returns the multipage editor's page instances via the protected {@code FormEditor.pages} field,
     * or an empty list. Runs on the UI thread.
     */
    private static List<Object> editorPages(IEditorPart editorPart)
    {
        try
        {
            Object pages = ReflectionUtils.getFieldValue(editorPart, "pages"); //$NON-NLS-1$
            if (pages instanceof List)
            {
                return new ArrayList<>((List<?>)pages);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not enumerate template editor pages: " + e.getMessage()); //$NON-NLS-1$
        }
        return new ArrayList<>();
    }

    /**
     * Reads a page's {@code getId()} (the Eclipse {@code IFormPage} id), or {@code null}.
     */
    private static String pageId(Object page)
    {
        try
        {
            Object id = ReflectionUtils.invokeMethod(page, "getId"); //$NON-NLS-1$
            return id != null ? id.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Waits (bounded) until the moxel editor's {@link MoxelControl} is created - it is built only in
     * the embedded editor's {@code createPartControl}, which runs after the page is activated and the
     * SWT event loop is pumped. Mirrors the form tool's "wait for the WYSIWYG viewer" readiness gate.
     * Runs on the UI thread.
     */
    private static MoxelControl waitForMoxelControl(MoxelEditor moxelEditor)
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < CONTROL_WAIT_RETRIES; i++)
        {
            MoxelControl control = moxelEditor.getMoxelControl();
            if (control != null && !control.isDisposed() && control.getSheet() != null)
            {
                return control;
            }
            EditorScreenshotHelper.processEvents(display);
            sleep(CONTROL_WAIT_INTERVAL_MS);
            EditorScreenshotHelper.processEvents(display);
        }
        MoxelControl control = moxelEditor.getMoxelControl();
        return control != null && !control.isDisposed() ? control : null;
    }

    /**
     * Resolves the BM model for the project via the model manager, or {@code null} when unavailable.
     */
    private static IBmModel resolveBmModel(String projectName)
    {
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        if (manager == null)
        {
            return null;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return null;
        }
        return manager.getModel(project);
    }

    /**
     * Renders the SpreadsheetDocument to {@link ImageData} inside a BM <b>auto-rolled-back</b>
     * transaction. The document is BM-managed and painting it lazily initializes derived features
     * while reading the model - those are model writes, so a plain read boundary rejects them and the
     * model must not be persistently dirtied either. {@link BmTransactions#executeAndRollback} runs the
     * render in a write-capable transaction and discards every change afterwards - the same sandbox
     * EDT's form render uses. The returned image survives the rollback; the model edits do not. The
     * empty-content extent check runs inside the same boundary, so every model-feature read stays in
     * one transaction. Must run on the UI thread.
     *
     * @param control the realized moxel control
     * @param bmModel the BM model owning the document, for the rollback transaction
     * @return a {@link RenderOutcome}: the rendered image, or the empty-content marker
     */
    private static RenderOutcome renderTemplate(MoxelControl control, IBmModel bmModel)
    {
        return BmTransactions.executeAndRollback(bmModel, "renderTemplateScreenshot", //$NON-NLS-1$
            (tx, monitor) -> {
                SheetAccessor sheet = control.getSheet();
                if (sheet == null || sheet.getVerticalSize() <= 0 || sheet.getHorizontalSize() <= 0)
                {
                    return RenderOutcome.empty();
                }
                return RenderOutcome.image(renderSpreadsheet(control));
            });
    }

    /**
     * Renders the WHOLE used range of the SpreadsheetDocument to a single continuous {@link ImageData},
     * the way the EDT editor canvas shows it - <b>not</b> split into print pages. It paints the full
     * cell range into one off-screen {@link Image} via {@code MoxelControl.paintViewPort} (the same
     * primitive the editor and the print preview draw cells with), sized to the used range's pixel
     * extent. This is why a wide template's right-hand columns stay beside the header instead of
     * spilling onto a separate print page (the old print-pipeline render paginated by paper width and
     * stacked the pages, which mis-laid-out wide forms). Row/column header strips and print-page chrome
     * are not drawn (paintViewPort draws cell content only). Must run on the UI thread inside the
     * boundary opened by {@link #renderTemplate}.
     *
     * @param control the realized moxel control (source of sheet + display metrics)
     * @return the rendered image data, or {@code null} if nothing could be produced
     */
    private static ImageData renderSpreadsheet(MoxelControl control)
    {
        Display display = control.getDisplay();
        SheetAccessor sheet = control.getSheet();
        PositionHolder positionHolder = control.getDisplayPositionHolder();
        UnitsConverter unitsConverter = positionHolder.getUnitsConverter();

        int columnCount = sheet.getHorizontalSize(); // used column count (last col index + 1)
        int rowCount = sheet.getVerticalSize();       // used row count (last row index + 1)
        if (columnCount <= 0 || rowCount <= 0)
        {
            return null;
        }

        // Pixel extent of the whole used range. The extent rect ends ONE PAST the last used cell, so
        // getPositionForRectUnit returns {0,0,totalWidthUnits,totalHeightUnits} (the cumulative left/top
        // edge of the column/row just past the last used one); convert units to device pixels.
        SpreadsheetRect extent = SheetFactory.createSpreadsheetRect();
        extent.getBegin().getCell().setX(0);
        extent.getBegin().getCell().setY(0);
        extent.getEnd().getCell().setX(columnCount);
        extent.getEnd().getCell().setY(rowCount);
        Rectangle unitRect = positionHolder.getPositionForRectUnit(extent);
        int widthPx = unitsConverter.XUnitToPixel(unitRect.width);
        int heightPx = unitsConverter.YUnitToPixel(unitRect.height);
        if (widthPx <= 0 || heightPx <= 0 || (long)widthPx * heightPx > MAX_IMAGE_PIXELS)
        {
            // Nothing to draw, or pathologically large for a single SWT image (avoid a native failure).
            return null;
        }

        // ViewPort over the whole INCLUSIVE used range (begin (0,0) .. end (lastCol, lastRow)).
        SpreadsheetRect range = SheetFactory.createSpreadsheetRect();
        range.getBegin().getCell().setX(0);
        range.getBegin().getCell().setY(0);
        range.getEnd().getCell().setX(columnCount - 1);
        range.getEnd().getCell().setY(rowCount - 1);
        ViewPort viewPort = new ViewPort(sheet, positionHolder);
        viewPort.setSheetPosition(range);
        viewPort.setDevicePosition(new Rectangle(0, 0, widthPx, heightPx));

        Image image = new Image(display, widthPx, heightPx);
        try
        {
            GC gc = new GC(image);
            try
            {
                gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, widthPx, heightPx);
                // paintViewPort(viewPort, gc, clip, drawSelection=false): paint the whole sheet content
                // into the off-screen image, using the editor's current view settings (grid, etc.).
                control.paintViewPort(viewPort, gc, new Rectangle(0, 0, widthPx, heightPx), false);
            }
            finally
            {
                gc.dispose();
            }
            return image.getImageData();
        }
        finally
        {
            image.dispose();
        }
    }

    private static void sleep(int millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Outcome of the in-transaction render: either the rendered {@link ImageData}, or the
     * empty-content marker (the SpreadsheetDocument has no rows to render). Lets the empty check run
     * inside the rollback boundary while still surfacing a distinct "no content" message to the caller.
     */
    private static final class RenderOutcome
    {
        final ImageData imageData;
        final boolean emptyContent;

        private RenderOutcome(ImageData imageData, boolean emptyContent)
        {
            this.imageData = imageData;
            this.emptyContent = emptyContent;
        }

        static RenderOutcome image(ImageData imageData)
        {
            return new RenderOutcome(imageData, false);
        }

        static RenderOutcome empty()
        {
            return new RenderOutcome(null, true);
        }
    }

    /**
     * Holder for the embedded-moxel-editor resolution: either the resolved {@link MoxelEditor}, or a
     * short diagnostic of the editor pages found (when no SpreadsheetDocument page was located).
     */
    private static final class MoxelResolveResult
    {
        final MoxelEditor editor;
        final String diag;

        private MoxelResolveResult(MoxelEditor editor, String diag)
        {
            this.editor = editor;
            this.diag = diag;
        }

        static MoxelResolveResult found(MoxelEditor editor)
        {
            return new MoxelResolveResult(editor, null);
        }

        static MoxelResolveResult diag(String diag)
        {
            return new MoxelResolveResult(null, diag);
        }
    }

    /**
     * Holder threading the opened template editor part or an early-return error JSON out of
     * {@link #openTemplateEditor(String, String)}. Exactly one of {@code editorPart} / {@code error}
     * is set.
     */
    private static final class OpenResult
    {
        final IEditorPart editorPart;
        final String error;

        private OpenResult(IEditorPart editorPart, String error)
        {
            this.editorPart = editorPart;
            this.error = error;
        }

        static OpenResult page(IEditorPart editorPart)
        {
            return new OpenResult(editorPart, null);
        }

        static OpenResult error(String errorJson)
        {
            return new OpenResult(null, errorJson);
        }
    }
}

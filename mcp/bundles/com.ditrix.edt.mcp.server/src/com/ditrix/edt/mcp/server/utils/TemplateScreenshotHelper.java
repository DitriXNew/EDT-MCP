/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.ui.presentation.IPresentationService;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetRect;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.moxel.sheet.UnitsConverter;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelControl;
import com._1c.g5.v8.dt.moxel.ui.editor.PositionHolder;
import com._1c.g5.v8.dt.moxel.ui.editor.ViewPort;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;

/**
 * Captures a PNG screenshot of a 1C <b>template</b> (макет) - a {@code SpreadsheetDocument} print form -
 * as EDT renders it, <b>without opening an editor</b>.
 * <p>
 * The FQN is resolved to its {@code BasicTemplate} model object (a common template or an object-owned
 * template); its content {@code SpreadsheetDocument} ("moxel" model) is read from the BM model and
 * painted as one continuous image - the editor-canvas look, not print pages - into an off-screen
 * {@code Image} via a standalone, never-shown {@code MoxelControl.paintViewPort}. The render is
 * editor-free on purpose: the EDT template editor's page structure varies across builds / headless
 * runs (the embedded spreadsheet page is not always created), so reaching the moxel editor through the
 * editor is unreliable; building the moxel control directly from the document is not.
 * <p>
 * All methods here must run on the SWT UI thread (via {@code Display.syncExec}); the render needs a
 * non-null {@link Display#getCurrent()}.
 */
public final class TemplateScreenshotHelper
{
    /**
     * SWT style for the off-screen render control - the same style EDT's print preview uses for its own
     * off-screen {@code MoxelControl} (it only ever paints into a self-made GC, never shown).
     */
    private static final int MOXEL_CONTROL_STYLE = 262914;
    /** Upper bound on the rendered image area (pixels) - guards against an SWT image too large to allocate. */
    private static final long MAX_IMAGE_PIXELS = 120_000_000L;
    private static final int MODEL_RESOLVE_RETRIES = 20;
    private static final int MODEL_RESOLVE_INTERVAL_MS = 250;

    private TemplateScreenshotHelper()
    {
        // Utility class
    }

    /**
     * Resolves the template by FQN and renders its SpreadsheetDocument content to a PNG. Runs on the
     * UI thread.
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
            ProjectContext.ConfigurationResult cfg = ProjectContext.resolveConfiguration(projectName);
            if (!cfg.ok())
            {
                return CaptureResult.error(cfg.errorJson());
            }

            EObject templateObject = resolveTemplateObject(cfg.configuration(), templatePath);
            if (templateObject == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "Cannot resolve template '" + templatePath + "'. Expected a template FQN: a common " //$NON-NLS-1$ //$NON-NLS-2$
                    + "template 'CommonTemplate.<Name>' or an object-owned template " //$NON-NLS-1$
                    + "'<Type>.<Owner>.Template.<Name>' (e.g. 'DataProcessor.Invoices.Template.Printout'). " //$NON-NLS-1$
                    + "Verify the name with get_metadata_objects / get_metadata_details.").toJson()); //$NON-NLS-1$
            }
            if (!(templateObject instanceof BasicTemplate) || !(templateObject instanceof IBmObject))
            {
                return CaptureResult.error(ToolResult.error(
                    "'" + templatePath + "' is not a template (it resolves to a " //$NON-NLS-1$ //$NON-NLS-2$
                    + templateObject.eClass().getName() + "). Pass a template FQN: 'CommonTemplate.<Name>' " //$NON-NLS-1$
                    + "or '<Type>.<Owner>.Template.<Name>'.").toJson()); //$NON-NLS-1$
            }

            IBmModel bmModel = resolveBmModel(projectName);
            if (bmModel == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "Could not resolve the BM model for project '" + projectName + "'.").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            IPresentationService presentation = Activator.getDefault().getPresentationService();
            if (presentation == null)
            {
                return CaptureResult.error(ToolResult.error(
                    "The moxel presentation service is not available; cannot render the template.") //$NON-NLS-1$
                    .toJson());
            }

            long bmId = ((IBmObject)templateObject).bmGetId();
            RenderOutcome outcome = renderTemplate(bmModel, bmId, presentation);

            if (outcome.notSpreadsheet)
            {
                return CaptureResult.error(ToolResult.error(
                    "Template '" + templatePath + "' is not a SpreadsheetDocument template (its content " //$NON-NLS-1$ //$NON-NLS-2$
                    + "is " + outcome.detail + "). Only SpreadsheetDocument (print form) templates can be " //$NON-NLS-1$ //$NON-NLS-2$
                    + "rendered to an image.").toJson()); //$NON-NLS-1$
            }
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
     * Reads the template's content inside a BM <b>auto-rolled-back</b> transaction and renders it. The
     * document is BM-managed and painting it lazily initializes derived features (those are model
     * writes), so the work runs in {@link BmTransactions#executeAndRollback} (write-capable, every edit
     * discarded) - the same sandbox EDT's form render uses. The template object is re-fetched by its BM
     * id inside the transaction so {@code getTemplate()} materializes the content from {@code Template.mxlx}.
     * Must run on the UI thread.
     *
     * @param bmModel the BM model owning the template
     * @param bmId the template object's BM id
     * @param presentation the moxel presentation service for the render control
     * @return the render outcome (image, empty marker, or not-a-SpreadsheetDocument marker)
     */
    private static RenderOutcome renderTemplate(IBmModel bmModel, long bmId, IPresentationService presentation)
    {
        return BmTransactions.executeAndRollback(bmModel, "renderTemplateScreenshot", //$NON-NLS-1$
            (tx, monitor) -> {
                EObject txTemplate = tx.getObjectById(bmId);
                EObject content =
                    txTemplate instanceof BasicTemplate ? ((BasicTemplate)txTemplate).getTemplate() : null;
                if (content == null)
                {
                    return RenderOutcome.empty();
                }
                if (!(content instanceof SpreadsheetDocument))
                {
                    return RenderOutcome.nonSpreadsheet(content.eClass().getName());
                }
                return renderSpreadsheet((SpreadsheetDocument)content, presentation);
            });
    }

    /**
     * Renders the WHOLE used range of a SpreadsheetDocument to a single continuous {@link ImageData} via
     * a standalone, never-shown {@link MoxelControl} (no editor). It builds the control from the document
     * + presentation service, then paints the full inclusive cell range into one off-screen
     * {@link Image} via {@code paintViewPort}, sized to the used range's pixel extent. Must run on the UI
     * thread inside the boundary opened by {@link #renderTemplate}.
     *
     * @param document the spreadsheet document content
     * @param presentation the moxel presentation service
     * @return a {@link RenderOutcome}: the rendered image, the empty-content marker, or {@code image(null)}
     *         when nothing could be produced
     */
    private static RenderOutcome renderSpreadsheet(SpreadsheetDocument document, IPresentationService presentation)
    {
        Display display = Display.getCurrent();
        Shell shell = new Shell(display);
        try
        {
            MoxelControl control = new MoxelControl(shell, MOXEL_CONTROL_STYLE, presentation);
            control.setDocument(document);

            SheetAccessor sheet = control.getSheet();
            if (sheet == null)
            {
                return RenderOutcome.image(null);
            }
            int columnCount = sheet.getHorizontalSize(); // used column count (last col index + 1)
            int rowCount = sheet.getVerticalSize();       // used row count (last row index + 1)
            if (columnCount <= 0 || rowCount <= 0)
            {
                return RenderOutcome.empty();
            }

            PositionHolder positionHolder = control.getDisplayPositionHolder();
            UnitsConverter unitsConverter = positionHolder.getUnitsConverter();

            // Pixel extent of the whole used range. The extent rect ends ONE PAST the last used cell, so
            // getPositionForRectUnit returns {0,0,totalWidthUnits,totalHeightUnits} (the cumulative
            // left/top edge of the column/row just past the last used one); convert units to pixels.
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
                // Nothing to draw, or pathologically large for a single SWT image.
                return RenderOutcome.image(null);
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

            return RenderOutcome.image(paintToImage(control, viewPort, display, widthPx, heightPx));
        }
        finally
        {
            shell.dispose();
        }
    }

    /**
     * Paints the viewport into a fresh off-screen {@link Image} on a white background and returns its
     * {@link ImageData}. The image and GC are disposed on every path (the returned ImageData is a
     * detached copy). Runs on the UI thread.
     */
    private static ImageData paintToImage(MoxelControl control, ViewPort viewPort, Display display,
        int widthPx, int heightPx)
    {
        Image image = new Image(display, widthPx, heightPx);
        try
        {
            GC gc = new GC(image);
            try
            {
                gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, widthPx, heightPx);
                // paintViewPort(viewPort, gc, clip, drawSelection=false): paint the whole sheet content
                // into the off-screen image (no row/column header strips, no print-page chrome).
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
     * Outcome of the in-transaction render: the rendered {@link ImageData}, the empty-content marker, or
     * the not-a-SpreadsheetDocument marker (with the actual content type for the error message).
     */
    private static final class RenderOutcome
    {
        final ImageData imageData;
        final boolean emptyContent;
        final boolean notSpreadsheet;
        final String detail;

        private RenderOutcome(ImageData imageData, boolean emptyContent, boolean notSpreadsheet, String detail)
        {
            this.imageData = imageData;
            this.emptyContent = emptyContent;
            this.notSpreadsheet = notSpreadsheet;
            this.detail = detail;
        }

        static RenderOutcome image(ImageData imageData)
        {
            return new RenderOutcome(imageData, false, false, null);
        }

        static RenderOutcome empty()
        {
            return new RenderOutcome(null, true, false, null);
        }

        static RenderOutcome nonSpreadsheet(String detail)
        {
            return new RenderOutcome(null, false, true, detail);
        }
    }
}

/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/**
 * Tool to capture a screenshot of a form WYSIWYG editor as PNG.
 * Can automatically open and activate a form by its metadata FQN path.
 */
public class GetFormScreenshotTool implements IMcpTool
{
    public static final String NAME = "get_form_screenshot"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Capture a PNG screenshot of a form's WYSIWYG editor; pass formPath to open the form " + //$NON-NLS-1$
            "automatically or omit it to shoot the active editor. Requires EDT launched with " + //$NON-NLS-1$
            "-DnativeFormBufferedLayoutRender=true, else the image is blank (missing flag, not a bad " + //$NON-NLS-1$
            "call). Full parameters and examples: call get_tool_guide('get_form_screenshot')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required when formPath is specified.") //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Form FQN (e.g. 'Catalog.Products.Forms.ItemForm' or 'CommonForm.MyForm'); " + //$NON-NLS-1$
                "if omitted, captures the active form editor.") //$NON-NLS-1$
            .booleanProperty("refresh", //$NON-NLS-1$
                "Force a real WYSIWYG re-render before capture; fails with an explicit error instead " + //$NON-NLS-1$
                "of returning a stale image when the re-render cannot be completed (default: false)") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.IMAGE;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String formPath = params.get("formPath"); //$NON-NLS-1$
        if (formPath != null && !formPath.isEmpty())
        {
            String[] parts = formPath.split("\\."); //$NON-NLS-1$
            if (parts.length > 0)
            {
                return parts[parts.length - 1] + ".png"; //$NON-NLS-1$
            }
        }
        return "form.png"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        boolean refresh = "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "refresh")); //$NON-NLS-1$ //$NON-NLS-2$

        if (formPath != null && !formPath.isEmpty()
            && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error("projectName is required when formPath is specified").toJson(); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("Display is not available").toJson(); //$NON-NLS-1$
        }

        AtomicReference<CaptureResult> resultRef = new AtomicReference<>();
        display.syncExec(() -> resultRef.set(captureScreenshot(projectName, formPath, refresh)));

        CaptureResult result = resultRef.get();
        if (!result.isSuccess())
        {
            return result.getError();
        }

        return result.getBase64Data();
    }

    /**
     * Main capture logic. Runs on the UI thread.
     */
    private CaptureResult captureScreenshot(String projectName, String formPath, boolean refresh)
    {
        try
        {
            Object editorPage;
            boolean formRequested = formPath != null && !formPath.isEmpty();

            if (formRequested)
            {
                EditorScreenshotHelper.ensureBufferedNativeRenderMode();

                // Open the requested form and keep a direct handle on the editor that was opened
                // for it. The image MUST come from this editor's own WYSIWYG representation, not
                // from the globally active form editor: previously this tool resolved the page via
                // FormEditor.getActiveFormEditorPage(), which returns whatever form editor currently
                // has workbench focus, so a previously rendered/active form (e.g. DataProcessor.X)
                // was captured instead of the requested one.
                EditorScreenshotHelper.OpenFormResult openResult =
                    EditorScreenshotHelper.openForm(projectName, formPath);
                if (!openResult.isSuccess())
                {
                    return CaptureResult.error(openResult.getError());
                }

                IEditorPart editorPart = openResult.getEditorPart();

                // Let UI settle after activation
                Display display = Display.getCurrent();
                for (int i = 0; i < 5; i++)
                {
                    EditorScreenshotHelper.processEvents(display);
                    Thread.sleep(100);
                }

                // Resolve the WYSIWYG page from THIS editor part (findPage), not the global active
                // page, so the page is guaranteed to belong to the requested form.
                editorPage = EditorScreenshotHelper.waitForFormEditorPageOf(editorPart);
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading.").toJson()); //$NON-NLS-1$
                }

                // Identity guard (a): confirm the opened editor actually corresponds to the requested
                // form before reading its image. If it does not match, fail explicitly rather than
                // return another form's PNG (the silent wrong-form defect).
                String actualFqn = EditorScreenshotHelper.getFormEditorFqn(editorPart);
                if (actualFqn != null && !EditorScreenshotHelper.fqnMatchesFormPath(actualFqn, formPath))
                {
                    return CaptureResult.error(ToolResult.error(
                        "Captured form editor does not match the requested form. Requested '" //$NON-NLS-1$
                        + formPath + "' but the active editor is '" + actualFqn //$NON-NLS-1$
                        + "'. No screenshot was taken to avoid returning the wrong form's image; " //$NON-NLS-1$
                        + "try again once the requested form's editor is fully open.").toJson()); //$NON-NLS-1$
                }
            }
            else
            {
                editorPage = EditorScreenshotHelper.getActiveFormEditorPage();
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "No active form editor page found. " + //$NON-NLS-1$
                        "Specify formPath parameter to open a form automatically.").toJson()); //$NON-NLS-1$
                }
            }

            Object wysiwygViewer = ReflectionUtils.getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (wysiwygViewer == null)
            {
                return CaptureResult.error(ToolResult.error("WYSIWYG viewer is not available").toJson()); //$NON-NLS-1$
            }

            Object representation = EditorScreenshotHelper.getRepresentation(wysiwygViewer);
            if (representation == null)
            {
                return CaptureResult.error(
                    ToolResult.error("WYSIWYG representation is not available").toJson()); //$NON-NLS-1$
            }

            // Identity guard (b): image-level check (the stale-image defect). The form image
            // is read from the representation's own form model: in the rebuild task the image and the
            // layout are produced together from a single createHippoSession(tx, this.form, ...) call, so
            // the pixels belong to whatever form THIS representation renders. Confirm that model is the
            // requested form before trusting the image. The shared HippoLayoutService singleton paints
            // every form into ONE memory-mapped offscreen buffer, so an editor-input FQN match alone
            // does not prove the buffer (and thus the image) was repainted for the requested form.
            if (formRequested && !EditorScreenshotHelper.representationFormMatches(representation, formPath))
            {
                return CaptureResult.error(ToolResult.error(
                    "The WYSIWYG editor for '" + formPath + "' does not render the requested form model. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "No screenshot was taken to avoid returning another form's image; " //$NON-NLS-1$
                    + "try again once the requested form's editor is fully open.").toJson()); //$NON-NLS-1$
            }

            if (refresh)
            {
                EditorScreenshotHelper.refreshViewer(wysiwygViewer);
            }

            // Identity guard (c): ensure THIS representation's form is rendered into its formImageData
            // and wait (bounded) until that image is non-empty before reading it. Correctness comes from
            // the identity guard above (representationFormMatches): the image and the layout are produced
            // together from a single createHippoSession(tx, this.form, ...) call, so a non-empty image on
            // a representation whose own form IS the requested form is the requested form's image. We
            // deliberately do NOT require a brand-new ImageData instance: in this detached/MCP-driven EDT
            // the native render reuses the existing instance, so the old "must be a NEW instance" gate was
            // never satisfied and suppressed screenshots for every form (including renderable ones).
            // ensureRenderedFormImage best-effort triggers a synchronous render to populate the buffer,
            // but falls through to the already-present (identity-verified) image when it exists. Only fail
            // if no image is produced.
            //
            // refresh=true changes that contract (the stale-screenshot fix): the caller explicitly asked
            // for a re-render (e.g. the form was just edited), so the pre-existing buffer must NOT be
            // accepted — refreshViewer above only fires the ASYNC rebuild, which is dropped in this
            // detached/MCP-driven EDT, and falling through to the old buffer returned the PRE-edit PNG as
            // "refreshed". In force mode ensureRenderedFormImage drives a real re-render (the synchronous
            // render path, with the async rebuild as fallback) and succeeds only on evidence that a
            // render ran during this call; otherwise we fail explicitly below — consistent with the
            // identity-guard philosophy: never a stale/wrong image silently.
            boolean rendered = EditorScreenshotHelper.ensureRenderedFormImage(representation, refresh);
            if (refresh && !rendered)
            {
                return CaptureResult.error(ToolResult.error(
                    "refresh=true was requested but the form could not be re-rendered in time, so no " //$NON-NLS-1$
                    + "screenshot was taken: returning the previously rendered image would silently " //$NON-NLS-1$
                    + "show stale (pre-edit) content. Ensure EDT runs with buffered native render " //$NON-NLS-1$
                    + "(VM option -DnativeFormBufferedLayoutRender=true) and try again, or call with " //$NON-NLS-1$
                    + "refresh=false to accept the last rendered image.").toJson()); //$NON-NLS-1$
            }
            if (formRequested && !rendered)
            {
                // Keep the documented render-unavailable sentinel "Form image data is not available"
                // CONTIGUOUS — callers (and the upstream e2e suite) match it as a substring; the
                // wait-budget context is carried around it, not inside it.
                return CaptureResult.error(ToolResult.error(
                    "Could not render the requested form '" + formPath //$NON-NLS-1$
                    + "' in time, so no screenshot was taken. Form image data is not available: its " //$NON-NLS-1$
                    + "WYSIWYG representation produced no image within the wait budget. " //$NON-NLS-1$
                    + "Ensure EDT runs with buffered native render " //$NON-NLS-1$
                    + "(VM option -DnativeFormBufferedLayoutRender=true) and try again.").toJson()); //$NON-NLS-1$
            }

            // Read the (identity-verified) rendered image from this representation. For a requested form
            // this is the requested form's image: representationFormMatches proved the representation's
            // own form IS the requested form, and ensureRenderedFormImage confirmed formImageData is
            // non-empty.
            ImageData imageData = EditorScreenshotHelper.readFormImageData(representation);

            // Fallback: capture control via print (only used for the active-editor case with no
            // explicit formPath; for a requested form the image above is already the correct one).
            if (imageData == null && !formRequested)
            {
                imageData = EditorScreenshotHelper.captureControlImageData(wysiwygViewer);
            }

            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
                if (!rendered)
                {
                    // Same contiguous-sentinel rule as above: lead with the documented
                    // "Form image data is not available" phrase, then the wait-budget context.
                    return CaptureResult.error(ToolResult.error(
                        "Form image data is not available: the form did not finish rendering " + //$NON-NLS-1$
                        "in time, so no image could be captured. " + //$NON-NLS-1$
                        "Ensure EDT runs with buffered native render " + //$NON-NLS-1$
                        "(VM option -DnativeFormBufferedLayoutRender=true) and try again.").toJson()); //$NON-NLS-1$
                }
                return CaptureResult.error(ToolResult.error("Form image data is not available").toJson()); //$NON-NLS-1$
            }

            String base64 = EditorScreenshotHelper.encodePng(imageData);
            return CaptureResult.success(base64);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture form screenshot", e); //$NON-NLS-1$
            return CaptureResult.error(
                ToolResult.error("Failed to capture form screenshot: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }
}

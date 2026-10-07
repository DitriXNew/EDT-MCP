/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.FormPageSwitch;

/**
 * Tool to capture a screenshot of a form WYSIWYG editor as PNG.
 * Can automatically open and activate a form by its metadata FQN path.
 */
public class GetFormScreenshotTool implements IMcpTool
{
    public static final String NAME = "get_form_screenshot"; //$NON-NLS-1$

    /** What this tool returns, named in the wrong-form errors. */
    private static final String PRODUCT = "screenshot"; //$NON-NLS-1$

    /** Input param: form FQN to open and capture. */
    private static final String KEY_FORM_PATH = "formPath"; //$NON-NLS-1$

    /** Input param: form element whose page must be shown before the capture. */
    private static final String KEY_SHOW_ELEMENT = "showElement"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Visually inspect an EDT form as rendered by the designer. Requires EDT launched " //$NON-NLS-1$
            + "with -DnativeFormBufferedLayoutRender=true: without the flag the image comes back " //$NON-NLS-1$
            + "BLANK instead of failing, so an empty screenshot means the flag is missing, not " //$NON-NLS-1$
            + "that the call was wrong. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('get_form_screenshot')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required when formPath is specified.") //$NON-NLS-1$
            .stringProperty(KEY_FORM_PATH,
                "Form FQN (e.g. 'Catalog.Products.Forms.ItemForm' or 'CommonForm.MyForm'); " + //$NON-NLS-1$
                "if omitted, captures the active form editor.") //$NON-NLS-1$
            .stringProperty(KEY_SHOW_ELEMENT,
                "Name of a form element (e.g. a page) to bring into view before capture: every " + //$NON-NLS-1$
                "enclosing Pages group switches to the page holding it.") //$NON-NLS-1$
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
        String formPath = params.get(KEY_FORM_PATH);
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
        String formPath = JsonUtils.extractStringArgument(params, KEY_FORM_PATH);
        String showElement = JsonUtils.extractStringArgument(params, KEY_SHOW_ELEMENT);
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
        display.syncExec(() -> resultRef.set(captureScreenshot(projectName, formPath, showElement, refresh)));

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
    private CaptureResult captureScreenshot(String projectName, String formPath, String showElement,
        boolean refresh)
    {
        try
        {
            boolean formRequested = formPath != null && !formPath.isEmpty();

            if (formRequested)
            {
                EditorScreenshotHelper.ensureBufferedNativeRenderMode();
            }
            // Opens the requested form (or takes the active editor) and runs both identity guards: the
            // image is rendered from the representation's own form model into an offscreen buffer every
            // form shares, so only that model proves whose pixels these are.
            EditorScreenshotHelper.FormEditorTarget editor =
                EditorScreenshotHelper.resolveFormEditor(projectName, formPath, PRODUCT);
            if (editor.getError() != null)
            {
                return CaptureResult.error(ToolResult.error(editor.getError()).toJson());
            }
            Object wysiwygViewer = editor.getViewer();
            Object representation = editor.getRepresentation();

            // Resolve showElement before the render gate: an unknown name must fail fast with the
            // not-found error, not wait for the render or turn into the render-unavailable error.
            EditorScreenshotHelper.ShowElementTarget showTarget = null;
            if (showElement != null && !showElement.isEmpty())
            {
                showTarget = EditorScreenshotHelper.resolveShowElementForScreenshot(representation, showElement);
                if (showTarget.getError() != null)
                {
                    return CaptureResult.error(ToolResult.error(showTarget.getError()).toJson());
                }
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
            CaptureResult renderGate = checkRenderGate(rendered, refresh, formRequested, formPath);
            if (renderGate != null)
            {
                return renderGate;
            }

            if (showTarget == null)
            {
                return captureImage(representation, wysiwygViewer, rendered, formRequested);
            }
            return captureShowingElement(representation, wysiwygViewer, rendered, formPath, showTarget);
        }
        catch (Exception e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            Activator.logError("Failed to capture form screenshot", e); //$NON-NLS-1$
            return CaptureResult.error(
                ToolResult.error("Failed to capture form screenshot: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /** Reads, validates and encodes the representation's current image. */
    private static CaptureResult captureImage(Object representation, Object wysiwygViewer, boolean rendered,
        boolean formRequested)
        throws Exception
    {
        ImageDataResult imageResult = readValidImageData(representation, wysiwygViewer, rendered, formRequested);
        if (imageResult.error != null)
        {
            return CaptureResult.error(ToolResult.error(imageResult.error).toJson());
        }
        return CaptureResult.success(EditorScreenshotHelper.encodePng(imageResult.imageData));
    }

    /**
     * Captures with the page holding {@code target} shown. Runs after the (possibly forced) render,
     * since a full render brings back the default pages. The switch, the identity guard and the image
     * read follow each other with no event pumping, inside the {@code try} whose {@code finally}
     * restores the editor. A failed restore fails the call and is appended to any other failure: the
     * editor and later captures would keep the switched page.
     */
    static CaptureResult captureShowingElement(Object representation, Object wysiwygViewer,
        boolean rendered, String formPath, EditorScreenshotHelper.ShowElementTarget target)
    {
        boolean formRequested = formPath != null && !formPath.isEmpty();
        FormPageSwitch pageSwitch = FormPageSwitch.prepare(representation, target);
        String failure = null;
        String png = null;
        String restoreError;
        try
        {
            failure = pageSwitch.show();
            if (failure == null)
            {
                failure = EditorScreenshotHelper.representationGuardError(representation, formPath, PRODUCT);
            }
            if (failure == null)
            {
                ImageDataResult image = readValidImageData(representation, wysiwygViewer, rendered, formRequested);
                failure = image.error;
                png = image.error == null ? EditorScreenshotHelper.encodePng(image.imageData) : null;
            }
        }
        catch (Exception e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            Activator.logError("Failed to capture form screenshot", e); //$NON-NLS-1$
            failure = "Failed to capture form screenshot: " + e.getMessage(); //$NON-NLS-1$
        }
        finally
        {
            restoreError = pageSwitch.restore();
        }
        if (restoreError != null)
        {
            failure = failure == null ? restoreError : failure + " " + restoreError; //$NON-NLS-1$
        }
        return failure == null ? CaptureResult.success(png)
            : CaptureResult.error(ToolResult.error(failure).toJson());
    }

    /**
     * Applies the render gate after {@code ensureRenderedFormImage}: when the render could not be
     * completed it returns the same explicit error the inline code did (refresh first, then the
     * requested-form case), or {@code null} when capture may proceed. Behaviour is unchanged.
     */
    private static CaptureResult checkRenderGate(boolean rendered, boolean refresh, boolean formRequested,
        String formPath)
    {
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
        return null;
    }

    /**
     * Reads the rendered image from the representation, applies the active-editor print fallback,
     * and validates the image dimensions. Returns a holder carrying either a valid {@link ImageData}
     * or the error message (the contiguous "Form image data is not available" sentinel rules are
     * preserved).
     */
    private static ImageDataResult readValidImageData(Object representation, Object wysiwygViewer,
        boolean rendered, boolean formRequested)
        throws Exception
    {
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
                return ImageDataResult.failed(
                    "Form image data is not available: the form did not finish rendering " + //$NON-NLS-1$
                    "in time, so no image could be captured. " + //$NON-NLS-1$
                    "Ensure EDT runs with buffered native render " + //$NON-NLS-1$
                    "(VM option -DnativeFormBufferedLayoutRender=true) and try again."); //$NON-NLS-1$
            }
            return ImageDataResult.failed("Form image data is not available"); //$NON-NLS-1$
        }

        return ImageDataResult.image(imageData);
    }

    /**
     * Holder threading the validated image data or an early-return error out of
     * {@link #readValidImageData}. Exactly one of {@code imageData} / {@code error} is set.
     */
    private static final class ImageDataResult
    {
        final ImageData imageData;
        /** The error message, or {@code null} when {@link #imageData} is set. */
        final String error;

        private ImageDataResult(ImageData imageData, String error)
        {
            this.imageData = imageData;
            this.error = error;
        }

        static ImageDataResult image(ImageData imageData)
        {
            return new ImageDataResult(imageData, null);
        }

        static ImageDataResult failed(String error)
        {
            return new ImageDataResult(null, error);
        }
    }
}

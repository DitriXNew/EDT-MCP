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
            .booleanProperty("refresh", "Force WYSIWYG refresh before capture (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Captures a **PNG screenshot** of a form's WYSIWYG editor as it actually renders " //$NON-NLS-1$
            + "(the same visual EDT shows in the form designer). The response type is IMAGE - the " //$NON-NLS-1$
            + "tool returns the PNG, not text.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- See what a form looks like rendered, verify a layout/visibility change visually, " //$NON-NLS-1$
            + "or attach a before/after image.\n" //$NON-NLS-1$
            + "- If you need element positions/sizes as DATA (bounds, types, properties) rather " //$NON-NLS-1$
            + "than a picture, use `get_form_layout_snapshot` instead.\n" //$NON-NLS-1$
            + "- To inspect the declarative form definition, read the `.form` model; this tool is " //$NON-NLS-1$
            + "only the rendered bitmap.\n\n" //$NON-NLS-1$

            + "## Required JVM flag (read this first)\n" //$NON-NLS-1$
            + "EDT must be launched with `-DnativeFormBufferedLayoutRender=true` in the `1cedt.ini` " //$NON-NLS-1$
            + "`-vmargs` section. Without it the offscreen layout handler is never constructed and " //$NON-NLS-1$
            + "the screenshot comes back **blank/empty**. A blank image almost always means the " //$NON-NLS-1$
            + "flag is missing - it is NOT a bad call or a code bug, so do not retry or reshape the " //$NON-NLS-1$
            + "arguments to \"fix\" it; add the flag and relaunch EDT.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `projectName` - EDT project name. **Required when `formPath` is specified**; " //$NON-NLS-1$
            + "omitting it then returns the error \"projectName is required when formPath is " //$NON-NLS-1$
            + "specified\". Ignored when targeting the active editor.\n" //$NON-NLS-1$
            + "- `formPath` - metadata FQN of the form. If given, the tool opens and activates " //$NON-NLS-1$
            + "that form automatically, waits for the WYSIWYG page, then captures it. If omitted, " //$NON-NLS-1$
            + "the currently active form editor is captured.\n" //$NON-NLS-1$
            + "- `refresh` - force a WYSIWYG refresh before capturing; default `false`. Set `true` " //$NON-NLS-1$
            + "if the form was just edited and the rendered image may be stale.\n\n" //$NON-NLS-1$

            + "### formPath format\n" //$NON-NLS-1$
            + "`MetadataType.ObjectName.Forms.FormName`, or `CommonForm.FormName` for a common " //$NON-NLS-1$
            + "form. Examples:\n" //$NON-NLS-1$
            + "- `Catalog.Products.Forms.ItemForm`\n" //$NON-NLS-1$
            + "- `Document.SalesOrder.Forms.DocumentForm`\n" //$NON-NLS-1$
            + "- `CommonForm.MyForm`\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Active editor, default: `{}`.\n" //$NON-NLS-1$
            + "- Specific form: `{projectName: \"MyProj\", formPath: " //$NON-NLS-1$
            + "\"Catalog.Products.Forms.ItemForm\"}`.\n" //$NON-NLS-1$
            + "- Force refresh first: `{projectName: \"MyProj\", formPath: \"CommonForm.MyForm\", " //$NON-NLS-1$
            + "refresh: true}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- Blank image => the `-DnativeFormBufferedLayoutRender=true` flag is missing (see " //$NON-NLS-1$
            + "above), not a failure of this call.\n" //$NON-NLS-1$
            + "- `formPath` without `projectName` is rejected before any rendering.\n" //$NON-NLS-1$
            + "- After opening a form the tool lets the UI settle briefly; if the page is still " //$NON-NLS-1$
            + "loading you may get \"Form editor opened but WYSIWYG page is not available\" - retry.\n" //$NON-NLS-1$
            + "- Needs a live workbench Display and runs on the UI thread; not available headless.\n" //$NON-NLS-1$
            + "- The saved file is named after the last FQN segment (e.g. `ItemForm.png`), or " //$NON-NLS-1$
            + "`form.png` when capturing the active editor.\n"; //$NON-NLS-1$
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

            if (formPath != null && !formPath.isEmpty())
            {
                EditorScreenshotHelper.ensureBufferedNativeRenderMode();

                String openError = EditorScreenshotHelper.openAndActivateForm(projectName, formPath);
                if (openError != null)
                {
                    return CaptureResult.error(openError);
                }

                // Let UI settle after activation
                Display display = Display.getCurrent();
                for (int i = 0; i < 5; i++)
                {
                    EditorScreenshotHelper.processEvents(display);
                    Thread.sleep(100);
                }

                editorPage = EditorScreenshotHelper.waitForFormEditorPage();
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading.").toJson()); //$NON-NLS-1$
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

            if (refresh)
            {
                EditorScreenshotHelper.refreshViewer(wysiwygViewer);
            }

            // Primary method: extract image from representation
            ImageData imageData = EditorScreenshotHelper.extractFormImageData(wysiwygViewer);

            // Fallback: capture control via print
            if (imageData == null)
            {
                imageData = EditorScreenshotHelper.captureControlImageData(wysiwygViewer);
            }

            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
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

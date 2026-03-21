/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Reusable helper for capturing screenshots from EDT visual editors (forms, print forms, etc.).
 * <p>
 * All UI-modifying methods must be called from the SWT UI thread (via {@code Display.syncExec}).
 */
public final class EditorScreenshotHelper
{
    private static final String FORM_EDITOR_CLASS = "com._1c.g5.v8.dt.form.ui.editor.FormEditor"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$
    private static final String FORM_MAIN_PAGE_ID = "editors.form.pages.main"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String FORM_IMAGE_METHOD = "getFormImageData"; //$NON-NLS-1$
    private static final String GET_CONTROL_METHOD = "getControl"; //$NON-NLS-1$
    private static final String REFRESH_METHOD = "refresh"; //$NON-NLS-1$
    private static final int WYSIWYG_WAIT_RETRIES = 15;
    private static final int WYSIWYG_WAIT_INTERVAL_MS = 500;

    private EditorScreenshotHelper()
    {
        // Utility class
    }

    // ==================== Result container ====================

    /**
     * Result of a screenshot capture — either base64 PNG data or an error JSON string.
     */
    public static class CaptureResult
    {
        private final String base64Data;
        private final String error;

        private CaptureResult(String base64Data, String error)
        {
            this.base64Data = base64Data;
            this.error = error;
        }

        public static CaptureResult success(String base64)
        {
            return new CaptureResult(base64, null);
        }

        public static CaptureResult error(String errorJson)
        {
            return new CaptureResult(null, errorJson);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public String getBase64Data()
        {
            return base64Data;
        }

        public String getError()
        {
            return error;
        }
    }

    // ==================== Native render mode ====================

    /**
     * Ensures that the native buffered render mode is enabled so that
     * {@code getFormImageData()} returns valid image data.
     * Should be called before opening a form editor.
     */
    /**
     * Previously tried to switch the native renderer to buffered mode at runtime.
     * This caused WYSIWYG flickering and is no longer needed — screenshots now use
     * {@code captureControlImageData()} which falls back to Robot screen capture.
     * Kept as a no-op for API compatibility with {@link com.ditrix.edt.mcp.server.tools.impl.GetFormScreenshotTool}.
     */
    public static void ensureBufferedNativeRenderMode()
    {
        // No-op: switching render mode at runtime causes WYSIWYG flickering.
        // extractFormImageData() now checks isBufferedRenderActive() and returns null
        // when in screen mode, so we fall through to captureControlImageData() / Robot.
    }

    /**
     * Returns true when the 1C native form renderer is in buffered (off-screen) mode,
     * meaning {@code getFormImageData()} will return valid image data.
     */
    private static boolean isBufferedRenderActive()
    {
        try
        {
            Class<?> serviceClass = Class.forName("com._1c.g5.v8.dt.form.layout.service.NativeRenderService"); //$NON-NLS-1$
            Method isBufferedRender = serviceClass.getMethod("isBufferedRender"); //$NON-NLS-1$
            return (Boolean)isBufferedRender.invoke(null);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    // ==================== Editor opening ====================

    /**
     * Opens a form file in the editor and activates the WYSIWYG (main) page.
     * Must be called on the UI thread.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return {@code null} on success, error JSON string on failure
     */
    public static String openAndActivateForm(String projectName, String formPath)
    {
        String relativePath = MetadataPathResolver.resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "or 'CommonForm.FormName'.").toJson(); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return ToolResult.error(
                "Form file not found: " + relativePath + " in project " + projectName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IWorkbenchPage page = getWorkbenchPage();
        if (page == null)
        {
            return ToolResult.error("No active workbench page").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Reuse existing editor if already open — avoid flickering from close/reopen
            IEditorPart editorPart;
            IEditorPart existingEditor = page.findEditor(new FileEditorInput(formFile));
            if (existingEditor != null)
            {
                page.activate(existingEditor);
                editorPart = existingEditor;
            }
            else
            {
                editorPart = IDE.openEditor(page, formFile, FORM_EDITOR_ID, true);
            }
            if (editorPart == null)
            {
                return ToolResult.error("Could not open form editor for: " + formPath).toJson(); //$NON-NLS-1$
            }

            activateFormMainPage(editorPart);
            return null; // success
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return ToolResult.error("Failed to open form editor: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Gets the active workbench page, trying all available windows.
     */
    public static IWorkbenchPage getWorkbenchPage()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            if (windows.length > 0)
            {
                window = windows[0];
            }
        }
        if (window == null)
        {
            return null;
        }
        return window.getActivePage();
    }

    // ==================== WYSIWYG page detection ====================

    /**
     * Waits for the form editor WYSIWYG page to become available.
     * Processes UI events while waiting to allow the editor to initialize.
     * Must be called on the UI thread.
     *
     * @return the FormEditorPage, or {@code null} if not available after timeout
     */
    public static Object waitForFormEditorPage()
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < WYSIWYG_WAIT_RETRIES; i++)
        {
            processEvents(display);

            try
            {
                Object page = getActiveFormEditorPage();
                if (page != null)
                {
                    Object viewer = ReflectionUtils.getFieldValue(page, WYSIWYG_VIEWER_FIELD);
                    if (viewer != null)
                    {
                        return page;
                    }
                }
            }
            catch (Exception e)
            {
                // Editor still initializing, keep waiting
            }

            sleep(WYSIWYG_WAIT_INTERVAL_MS);
            processEvents(display);
        }

        // Final attempt
        try
        {
            return getActiveFormEditorPage();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get form editor page after waiting", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Gets the active form editor page via the static FormEditor API.
     */
    public static Object getActiveFormEditorPage() throws Exception
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        Method method = editorClass.getMethod("getActiveFormEditorPage"); //$NON-NLS-1$
        return method.invoke(null);
    }

    // ==================== Image capture ====================

    /**
     * Extracts the form image data from the WYSIWYG representation.
     * This is the primary (preferred) capture method using {@code getFormImageData()}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if not available
     */
    public static ImageData extractFormImageData(Object wysiwygViewer) throws Exception
    {
        // getFormImageData() only returns valid data in buffered render mode.
        // In screen mode it returns a stale/empty image — skip it entirely.
        if (!isBufferedRenderActive())
        {
            return null;
        }

        Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        if (representation == null)
        {
            return null;
        }

        // Trigger rebuild to get up-to-date image
        try
        {
            Method rebuildMethod = representation.getClass().getDeclaredMethod("rebuild", boolean.class); //$NON-NLS-1$
            rebuildMethod.setAccessible(true);
            rebuildMethod.invoke(representation, true);

            Display display = Display.getCurrent();
            if (display != null)
            {
                for (int i = 0; i < 5; i++)
                {
                    processEvents(display);
                    sleep(200);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not call rebuild: " + e.getMessage()); //$NON-NLS-1$
        }

        // Get the image data
        try
        {
            Method method = representation.getClass().getDeclaredMethod(FORM_IMAGE_METHOD);
            method.setAccessible(true);
            ImageData data = (ImageData)method.invoke(representation);
            if (data != null && data.width > 0 && data.height > 0 && !isImageDataUniform(data))
            {
                return data;
            }
            if (data != null)
            {
                Activator.logWarning("getFormImageData returned uniform image — buffered render not ready, falling through"); //$NON-NLS-1$
            }
        }
        catch (NoSuchMethodException e)
        {
            Activator.logWarning("Method " + FORM_IMAGE_METHOD + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return null;
    }

    /**
     * Fallback capture method. Tries several strategies in order:
     * 1. {@code PrintWindow(PW_RENDERFULLCONTENT)} — Direct3D/DWM, works minimized/occluded
     * 2. {@code PrintWindow(PW_CLIENTONLY)} — GDI client area
     * 3. {@code java.awt.Robot.createScreenCapture()} — screen grab (EDT must be visible)
     * 4. {@code Control.print()} — SWT GDI fallback (usually black for 1C native forms)
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if the control is not available or has invalid bounds
     */
    public static ImageData captureControlImageData(Object wysiwygViewer) throws Exception
    {
        Control control = (Control)ReflectionUtils.invokeMethod(wysiwygViewer, GET_CONTROL_METHOD);
        if (control == null || control.isDisposed())
        {
            return null;
        }

        Rectangle bounds = control.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
        {
            return null;
        }

        // Narrow bounds to the actual form content area (excludes the dark editor background
        // that fills the WYSIWYG control beyond the form's own dimensions).
        Rectangle contentBounds = getFormContentBounds(wysiwygViewer);
        if (contentBounds != null)
        {
            bounds = new Rectangle(bounds.x, bounds.y,
                Math.min(bounds.width, contentBounds.width),
                Math.min(bounds.height, contentBounds.height));
        }

        // 1. PrintWindow(PW_RENDERFULLCONTENT) — DWM path, best for Direct3D
        ImageData printWindowData = captureViaPrintWindow(control, bounds);
        if (printWindowData != null)
        {
            return printWindowData;
        }

        // 2. java.awt.Robot screen capture — EDT must be visible, brings it to front first
        ImageData robotData = captureViaRobot(control, bounds);
        if (robotData != null)
        {
            return robotData;
        }

        // 3. SWT Control.print() via GDI (last resort — black for D3D native forms)
        control.update();
        Image image = new Image(control.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(image);
        try
        {
            gc.setBackground(control.getDisplay().getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, bounds.width, bounds.height);
            control.print(gc);
            return image.getImageData();
        }
        finally
        {
            gc.dispose();
            image.dispose();
        }
    }

    /**
     * Returns true when an SWT ImageData has no visible non-white content.
     * Counts opaque non-white pixels (alpha > 10, any channel < 230).
     * Handles RGBA images where transparent pixels have black RGB (alpha=0, rgb=0x000000).
     */
    private static boolean isImageDataUniform(ImageData data)
    {
        if (data == null || data.width <= 0 || data.height <= 0) return true;
        int total = data.width * data.height;
        int step = Math.max(1, total / 1000);
        int nonBlank = 0;
        for (int i = 0; i < total; i += step)
        {
            int x = i % data.width;
            int y = i / data.width;

            // Skip transparent pixels — they appear white when composited
            if (data.alphaData != null)
            {
                int alphaIdx = y * data.width + x;
                if (alphaIdx < data.alphaData.length && (data.alphaData[alphaIdx] & 0xFF) < 10)
                {
                    continue;
                }
            }

            // Extract RGB using palette (works for both direct and indexed palettes)
            int pixel = data.getPixel(x, y);
            int r, g, b;
            if (data.palette.isDirect)
            {
                r = (data.palette.redMask & pixel) >>> (-data.palette.redShift);
                g = (data.palette.greenMask & pixel) >>> (-data.palette.greenShift);
                b = (data.palette.blueMask & pixel) >>> (-data.palette.blueShift);
            }
            else
            {
                org.eclipse.swt.graphics.RGB rgb = data.palette.getRGB(pixel);
                r = rgb.red; g = rgb.green; b = rgb.blue;
            }

            // Visible non-white pixel = actual form content
            if (r < 230 || g < 230 || b < 230)
            {
                nonBlank++;
                if (nonBlank > 10) // need more than 10 non-white pixels to be "real content"
                {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns true when a raw BGRA pixel array is visually uniform (all one color).
     * Used to detect that the 1C native renderer hasn't drawn yet.
     */
    private static boolean isPixelArrayUniform(byte[] pixels, int w, int h)
    {
        if (pixels == null || pixels.length < 4) return true;
        int b0 = pixels[0] & 0xFF;
        int g0 = pixels[1] & 0xFF;
        int r0 = pixels[2] & 0xFF;
        int total = w * h;
        int step = Math.max(1, total / 500); // sample ~500 pixels
        for (int i = 0; i < total; i += step)
        {
            int idx = i * 4;
            if (Math.abs((pixels[idx] & 0xFF) - b0) > 8
                || Math.abs((pixels[idx + 1] & 0xFF) - g0) > 8
                || Math.abs((pixels[idx + 2] & 0xFF) - r0) > 8)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the actual pixel size of the rendered form content, or {@code null} if unavailable.
     * <p>
     * Strategy 1: {@code HippoLayForm.getResWidth()/getResHeight()} — the layout engine's computed
     *   pixel dimensions of the form. Most accurate source.
     * Strategy 2: {@code ScrolledComposite.getMinWidth()/getMinHeight()} — the minimum scrollable
     *   content size set by the WYSIWYG representation.
     * <p>
     * The WYSIWYG control area is often larger than the form itself — the area beyond the form
     * is filled with the dark editor background. Using the form's own dimensions lets us crop
     * to just the rendered content.
     */
    private static Rectangle getFormContentBounds(Object wysiwygViewer)
    {
        try
        {
            Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
            if (representation == null)
            {
                return null;
            }

            // Strategy 1: HippoLayForm.getResWidth() / getResHeight()
            // These are the layout engine's resolved pixel dimensions — most accurate.
            try
            {
                Method getHippoLayForm = representation.getClass().getMethod("getHippoLayForm"); //$NON-NLS-1$
                Object hippoLayForm = getHippoLayForm.invoke(representation);
                if (hippoLayForm != null)
                {
                    Method getResWidth = hippoLayForm.getClass().getMethod("getResWidth"); //$NON-NLS-1$
                    Method getResHeight = hippoLayForm.getClass().getMethod("getResHeight"); //$NON-NLS-1$
                    int resW = (Integer)getResWidth.invoke(hippoLayForm);
                    int resH = (Integer)getResHeight.invoke(hippoLayForm);
                    if (resW > 0 && resH > 0)
                    {
                        Activator.logWarning("Form content size from HippoLayForm.getResWidth/Height: " + resW + "x" + resH); //$NON-NLS-1$
                        return new Rectangle(0, 0, resW, resH);
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("HippoLayForm.getResWidth/Height failed: " + e.getMessage()); //$NON-NLS-1$
            }

            // Strategy 2: ScrolledComposite.getMinWidth() / getMinHeight()
            // These are set by the representation to match the form's rendered size.
            Object scObj = ReflectionUtils.getFieldValue(representation, "scrolledComposite"); //$NON-NLS-1$
            if (scObj instanceof ScrolledComposite)
            {
                ScrolledComposite sc = (ScrolledComposite)scObj;
                if (!sc.isDisposed())
                {
                    int minW = sc.getMinWidth();
                    int minH = sc.getMinHeight();
                    if (minW > 0 && minH > 0)
                    {
                        Activator.logWarning("Form content size from ScrolledComposite.getMinWidth/Height: " + minW + "x" + minH); //$NON-NLS-1$
                        return new Rectangle(0, 0, minW, minH);
                    }
                    // Fallback: content size
                    Control content = sc.getContent();
                    if (content != null && !content.isDisposed())
                    {
                        org.eclipse.swt.graphics.Point size = content.getSize();
                        if (size.x > 0 && size.y > 0)
                        {
                            Activator.logWarning("Form content size from ScrolledComposite.getContent.getSize: " + size.x + "x" + size.y); //$NON-NLS-1$
                            return new Rectangle(0, 0, size.x, size.y);
                        }
                    }
                }
            }

            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("getFormContentBounds failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Captures the control area using {@code java.awt.Robot.createScreenCapture()}.
     * Brings EDT to front before capture. Only works when EDT is not minimized.
     * Must be called on the SWT UI thread.
     *
     * @param control the SWT control to capture
     * @param bounds  the control bounds
     * @return image data, or {@code null} on failure
     */
    private static ImageData captureViaRobot(Control control, Rectangle bounds)
    {
        try
        {
            org.eclipse.swt.widgets.Shell shell = control.getShell();
            Display display = control.getDisplay();

            // Minimize then restore to guarantee EDT is the foreground window.
            // SetForegroundWindow() is restricted on modern Windows for background processes
            // and may only flash the taskbar. ShowWindow(SW_RESTORE) reliably brings the window
            // to front regardless of focus-stealing rules.
            if (!shell.getMinimized())
            {
                shell.setMinimized(true);
                pumpEvents(display, 300);
            }
            shell.setMinimized(false);
            shell.forceActive();
            // Pump events continuously so EDT can process WM_ACTIVATE / WM_PAINT
            pumpEvents(display, 2000);

            // Control's absolute screen position and logical bounds
            org.eclipse.swt.graphics.Point controlOrigin = control.toDisplay(0, 0);
            int controlX = controlOrigin.x;
            int controlY = controlOrigin.y;
            int controlW = bounds.width;
            int controlH = bounds.height;

            // Shell's absolute screen position (EDT main window)
            Rectangle shellBounds = shell.getBounds();
            int shellX = shellBounds.x;
            int shellY = shellBounds.y;
            int shellW = shellBounds.width;
            int shellH = shellBounds.height;

            // Intersect: only capture the portion of the control that is visible inside the shell.
            // After minimize+restore EDT may have a smaller window than the control's logical bounds,
            // so capturing the full control area would include desktop/other windows (appearing white).
            int captureX = Math.max(controlX, shellX);
            int captureY = Math.max(controlY, shellY);
            int captureX2 = Math.min(controlX + controlW, shellX + shellW);
            int captureY2 = Math.min(controlY + controlH, shellY + shellH);
            int x = captureX;
            int y = captureY;
            int w = captureX2 - captureX;
            int h = captureY2 - captureY;

            Activator.logWarning("Robot capture: control=(" + controlX + "," + controlY + "," + controlW + "x" + controlH //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ") shell=(" + shellX + "," + shellY + "," + shellW + "x" + shellH //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ") capture=(" + x + "," + y + "," + w + "x" + h + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

            if (w <= 0 || h <= 0)
            {
                Activator.logWarning("Robot: control not visible within shell bounds — skipping"); //$NON-NLS-1$
                return null;
            }

            java.awt.Robot robot = new java.awt.Robot();
            java.awt.image.BufferedImage img = robot.createScreenCapture(
                new java.awt.Rectangle(x, y, w, h));

            // Convert BufferedImage → SWT ImageData
            PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
            ImageData data = new ImageData(w, h, 24, palette);
            for (int row = 0; row < h; row++)
            {
                for (int col = 0; col < w; col++)
                {
                    int rgb = img.getRGB(col, row);
                    data.setPixel(col, row, rgb & 0x00FFFFFF);
                }
            }

            // Sanity check — if Robot captured all-white (EDT still not on top), fall through
            if (isImageDataUniform(data))
            {
                Activator.logWarning("Robot captured uniform image — EDT not on top after restore"); //$NON-NLS-1$
                return null;
            }

            return data;
        }
        catch (Exception e)
        {
            Activator.logWarning("Robot capture failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Pumps SWT events for the given duration (ms), sleeping 50 ms between iterations.
     * Used instead of a flat {@code sleep()} so EDT can process window messages
     * (WM_ACTIVATE, WM_PAINT, etc.) while waiting.
     */
    private static void pumpEvents(Display display, int totalMillis)
    {
        long deadline = System.currentTimeMillis() + totalMillis;
        while (System.currentTimeMillis() < deadline)
        {
            processEvents(display);
            sleep(50);
        }
    }

    /**
     * Captures a control using Win32 {@code PrintWindow(PW_RENDERFULLCONTENT)} via reflection.
     * {@code PW_RENDERFULLCONTENT} only works on top-level windows, so we capture the EDT shell
     * and crop to the control's screen bounds.
     * Works for Direct3D surfaces and minimized/occluded windows (Windows 8.1+).
     * Uses reflection so the plugin compiles on all platforms; returns null on non-Windows.
     *
     * @param control the SWT control to capture
     * @param bounds  the control bounds (width/height)
     * @return image data, or {@code null} on failure or non-Windows platform
     */
    private static ImageData captureViaPrintWindow(Control control, Rectangle bounds)
    {
        final int PW_RENDERFULLCONTENT = 2;

        try
        {
            // Load SWT Win32 OS class — only available on Windows
            Class<?> osClass = Class.forName("org.eclipse.swt.internal.win32.OS"); //$NON-NLS-1$

            java.lang.reflect.Method mGetDC = osClass.getMethod("GetDC", long.class); //$NON-NLS-1$
            java.lang.reflect.Method mCreateCompatibleDC = osClass.getMethod("CreateCompatibleDC", long.class); //$NON-NLS-1$
            java.lang.reflect.Method mCreateCompatibleBitmap = osClass.getMethod("CreateCompatibleBitmap", long.class, int.class, int.class); //$NON-NLS-1$
            java.lang.reflect.Method mSelectObject = osClass.getMethod("SelectObject", long.class, long.class); //$NON-NLS-1$
            java.lang.reflect.Method mPrintWindow = osClass.getMethod("PrintWindow", long.class, long.class, int.class); //$NON-NLS-1$
            java.lang.reflect.Method mGetDIBits = osClass.getMethod("GetDIBits", long.class, long.class, int.class, int.class, byte[].class, byte[].class, int.class); //$NON-NLS-1$
            java.lang.reflect.Method mReleaseDC = osClass.getMethod("ReleaseDC", long.class, long.class); //$NON-NLS-1$
            java.lang.reflect.Method mDeleteDC = osClass.getMethod("DeleteDC", long.class); //$NON-NLS-1$
            java.lang.reflect.Method mDeleteObject = osClass.getMethod("DeleteObject", long.class); //$NON-NLS-1$

            // PW_RENDERFULLCONTENT only works on top-level windows.
            // Get EDT shell HWND and capture it, then crop to control area.
            org.eclipse.swt.widgets.Shell shell = control.getShell();
            java.lang.reflect.Field handleField = shell.getClass().getField("handle"); //$NON-NLS-1$
            long shellHwnd = handleField.getLong(shell);

            // Shell screen bounds
            Rectangle shellBounds = shell.getBounds();
            int shellW = shellBounds.width;
            int shellH = shellBounds.height;

            long screenDC = (long)mGetDC.invoke(null, 0L);
            long memDC = (long)mCreateCompatibleDC.invoke(null, screenDC);
            long hBitmap = (long)mCreateCompatibleBitmap.invoke(null, screenDC, shellW, shellH);
            long oldBitmap = (long)mSelectObject.invoke(null, memDC, hBitmap);

            try
            {
                boolean ok = (boolean)mPrintWindow.invoke(null, shellHwnd, memDC, PW_RENDERFULLCONTENT);
                if (!ok)
                {
                    Activator.logWarning("PrintWindow returned false for shell HWND " + shellHwnd); //$NON-NLS-1$
                    return null;
                }

                // Read all shell pixels
                byte[] bmi = Win32BitmapUtils.buildBitmapInfoHeader(shellW, -shellH, (short)32);
                byte[] pixels = new byte[shellW * shellH * 4];
                int scanLines = (int)mGetDIBits.invoke(null, memDC, hBitmap, 0, shellH, pixels, bmi, 0);
                if (scanLines <= 0)
                {
                    Activator.logWarning("GetDIBits returned 0 scan lines"); //$NON-NLS-1$
                    return null;
                }

                // If captured image is uniform (all white or all black), the native renderer
                // hasn't drawn yet — return null so Robot fallback can try after a longer wait.
                if (isPixelArrayUniform(pixels, shellW, shellH))
                {
                    Activator.logWarning("PrintWindow captured uniform image — renderer not ready yet, falling through"); //$NON-NLS-1$
                    return null;
                }

                // Compute control position relative to shell (screen coords)
                org.eclipse.swt.graphics.Point controlOrigin = control.toDisplay(0, 0);
                int offsetX = controlOrigin.x - shellBounds.x;
                int offsetY = controlOrigin.y - shellBounds.y;
                int cropW = bounds.width;
                int cropH = bounds.height;

                // Crop to control area
                PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
                ImageData imageData = new ImageData(cropW, cropH, 24, palette);
                imageData.alphaData = new byte[cropW * cropH];

                for (int y = 0; y < cropH; y++)
                {
                    int srcY = offsetY + y;
                    if (srcY < 0 || srcY >= shellH) continue;
                    for (int x = 0; x < cropW; x++)
                    {
                        int srcX = offsetX + x;
                        if (srcX < 0 || srcX >= shellW) continue;
                        int idx = (srcY * shellW + srcX) * 4;
                        int b = pixels[idx] & 0xFF;
                        int g = pixels[idx + 1] & 0xFF;
                        int r = pixels[idx + 2] & 0xFF;
                        int a = pixels[idx + 3] & 0xFF;
                        imageData.setPixel(x, y, (r << 16) | (g << 8) | b);
                        imageData.alphaData[y * cropW + x] = (byte)a;
                    }
                }

                return imageData;
            }
            finally
            {
                mSelectObject.invoke(null, memDC, oldBitmap);
                mDeleteObject.invoke(null, hBitmap);
                mDeleteDC.invoke(null, memDC);
                mReleaseDC.invoke(null, 0L, screenDC);
            }
        }
        catch (ClassNotFoundException e)
        {
            // Not on Windows — expected
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("captureViaPrintWindow failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }


    /**
     * Refreshes the WYSIWYG viewer and waits for it to complete.
     * Must be called on the UI thread.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     */
    public static void refreshViewer(Object wysiwygViewer)
    {
        try
        {
            ReflectionUtils.invokeMethod(wysiwygViewer, REFRESH_METHOD);
            Display display = Display.getCurrent();
            if (display != null)
            {
                for (int i = 0; i < 3; i++)
                {
                    processEvents(display);
                    sleep(100);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to refresh WYSIWYG viewer: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ==================== Encoding ====================

    /**
     * Encodes {@link ImageData} as a base64 PNG string.
     *
     * @param imageData the image data to encode
     * @return base64-encoded PNG string
     */
    public static String encodePng(ImageData imageData)
    {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { imageData };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        loader.save(output, SWT.IMAGE_PNG);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    // ==================== Internal helpers ====================

    /**
     * Activates the main (WYSIWYG) page of the form editor via reflection.
     */
    private static void activateFormMainPage(IEditorPart editorPart)
    {
        try
        {
            Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
            if (!editorClass.isInstance(editorPart))
            {
                return;
            }

            Method setActivePageMethod =
                ReflectionUtils.findMethod(editorPart.getClass(), "setActivePage", String.class); //$NON-NLS-1$
            if (setActivePageMethod != null)
            {
                setActivePageMethod.setAccessible(true);
                setActivePageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not activate form main page: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Processes all pending SWT events.
     */
    public static void processEvents(Display display)
    {
        if (display != null)
        {
            while (display.readAndDispatch())
            {
                // drain event queue
            }
        }
    }

    /**
     * Sleeps with interrupt handling.
     */
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
}

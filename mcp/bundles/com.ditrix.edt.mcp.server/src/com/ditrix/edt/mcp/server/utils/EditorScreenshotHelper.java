/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

import com._1c.g5.v8.dt.ui.util.ContentUtil;

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
    private static final String FIND_PAGE_METHOD = "findPage"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String FORM_CONTROLS_CREATED_FIELD = "formControlsCreated"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String FORM_IMAGE_METHOD = "getFormImageData"; //$NON-NLS-1$
    private static final String FORM_IMAGE_DATA_FIELD = "formImageData"; //$NON-NLS-1$
    private static final String REPRESENTATION_FORM_FIELD = "form"; //$NON-NLS-1$
    private static final String REPRESENTATION_CONTROLLER_FIELD = "controller"; //$NON-NLS-1$
    private static final String REBUILD_INTERNAL_METHOD = "rebuildInternal"; //$NON-NLS-1$
    private static final String GET_MAPPING_ROOT_METHOD = "getMappingRoot"; //$NON-NLS-1$
    private static final String BUILD_UPDATE_EVENT_METHOD = "buildUpdateEvent"; //$NON-NLS-1$
    private static final String GET_CONTROL_METHOD = "getControl"; //$NON-NLS-1$
    private static final String REFRESH_METHOD = "refresh"; //$NON-NLS-1$
    private static final String REBUILD_METHOD = "rebuild"; //$NON-NLS-1$
    private static final int WYSIWYG_WAIT_RETRIES = 15;
    private static final int WYSIWYG_WAIT_INTERVAL_MS = 500;
    private static final int RENDER_WAIT_TIMEOUT_MS = 8000;
    private static final int RENDER_WAIT_POLL_INTERVAL_MS = 200;
    private static final int FRESH_RENDER_WAIT_TIMEOUT_MS = 10000;
    private static final int EDITOR_INPUT_RESOLVE_RETRIES = 20;
    private static final int EDITOR_INPUT_RESOLVE_INTERVAL_MS = 250;

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
    public static void ensureBufferedNativeRenderMode()
    {
        final String nativeRenderServiceClass = "com._1c.g5.v8.dt.form.layout.service.NativeRenderService"; //$NON-NLS-1$
        final String bufferedFlagField = "NATIVE_FORM_BUFFERED_LAYOUT_RENDER"; //$NON-NLS-1$
        final String propertyName = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

        try
        {
            System.setProperty(propertyName, "true"); //$NON-NLS-1$

            Class<?> serviceClass = Class.forName(nativeRenderServiceClass);
            Method isNativeRenderMethod = serviceClass.getMethod("isNativeRender"); //$NON-NLS-1$
            Method isBufferedRenderMethod = serviceClass.getMethod("isBufferedRender"); //$NON-NLS-1$

            boolean nativeRender = (Boolean)isNativeRenderMethod.invoke(null);
            boolean bufferedBefore = (Boolean)isBufferedRenderMethod.invoke(null);

            if (nativeRender && !bufferedBefore)
            {
                forceBufferedRenderFlag(serviceClass, bufferedFlagField);
            }

            boolean bufferedAfter = (Boolean)isBufferedRenderMethod.invoke(null);
            if (!bufferedAfter)
            {
                Activator.logWarning("Buffered native render is still disabled. " + //$NON-NLS-1$
                    "Restart EDT with VM option: -DnativeFormBufferedLayoutRender=true"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to ensure buffered native render mode: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Forces the static buffered-render flag field to {@code true}, first via a plain reflective
     * field set and, if that fails, via {@link ReflectionUtils#forceStaticFinalBoolean}.
     *
     * @param serviceClass the native render service class declaring the flag
     * @param bufferedFlagField the name of the static boolean flag field
     */
    private static void forceBufferedRenderFlag(Class<?> serviceClass, String bufferedFlagField)
    {
        try
        {
            Field bufferedField = serviceClass.getDeclaredField(bufferedFlagField);
            bufferedField.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            bufferedField.setBoolean(null, true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
        }
        catch (Exception e)
        {
            ReflectionUtils.forceStaticFinalBoolean(serviceClass, bufferedFlagField, true);
        }
    }

    // ==================== Editor opening ====================

    /**
     * Result of opening a form editor: either the opened {@code FormEditor} part plus the
     * resolved form {@code IFile}, or an error JSON string.
     */
    public static final class OpenFormResult
    {
        private final IEditorPart editorPart;
        private final IFile formFile;
        private final String error;

        private OpenFormResult(IEditorPart editorPart, IFile formFile, String error)
        {
            this.editorPart = editorPart;
            this.formFile = formFile;
            this.error = error;
        }

        static OpenFormResult success(IEditorPart editorPart, IFile formFile)
        {
            return new OpenFormResult(editorPart, formFile, null);
        }

        static OpenFormResult error(String errorJson)
        {
            return new OpenFormResult(null, null, errorJson);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public IEditorPart getEditorPart()
        {
            return editorPart;
        }

        public IFile getFormFile()
        {
            return formFile;
        }

        public String getError()
        {
            return error;
        }
    }

    /**
     * Opens a form file in the editor, activates its WYSIWYG (main) page and returns the opened
     * editor part. Callers get a direct
     * handle on the editor that was opened for the requested {@code formPath}, so they can read
     * the WYSIWYG page from <i>that</i> specific editor instead of the globally active one. This
     * is the fix for the wrong-form-screenshot defect, where {@code get_form_screenshot} captured the previously
     * active form because it resolved the page via the global "active form editor page" lookup
     * ({@code FormEditor.getActiveFormEditorPage()}), which returns whatever editor currently has
     * workbench focus rather than the one just opened. Must be called on the UI thread.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return an {@link OpenFormResult} with the opened editor part on success, or an error
     */
    public static OpenFormResult openForm(String projectName, String formPath)
    {
        String relativePath = MetadataPathResolver.resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return OpenFormResult.error(ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "or 'CommonForm.FormName'.").toJson()); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return OpenFormResult.error(ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return OpenFormResult.error(ToolResult.error(
                "Form file not found: " + relativePath + " in project " + projectName).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IWorkbenchPage page = getWorkbenchPage();
        if (page == null)
        {
            return OpenFormResult.error(ToolResult.error("No active workbench page").toJson()); //$NON-NLS-1$
        }

        // Resolve the typed DT editor input (model + feature) BEFORE opening. Opening the form
        // editor with a raw FileEditorInput goes through DtGranularEditor's bridge init, which
        // calls ContentUtil.createGranularEditorInput(file); if the form model is not yet
        // resolvable from the BM model that bridge throws a PartInitException during async part
        // rendering and the editor is left half-initialized (no WYSIWYG viewer). Resolving the
        // granular input here lets us (a) wait until the model is available and (b) open with the
        // already-resolved input, and (c) report a precise error instead of a misleading one.
        IEditorInput editorInput = resolveGranularEditorInput(formFile);
        if (editorInput == null)
        {
            return OpenFormResult.error(ToolResult.error(
                "Could not resolve the form model for: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "The project may still be loading or building its model; " + //$NON-NLS-1$
                "wait for the project to finish loading and try again.").toJson()); //$NON-NLS-1$
        }

        try
        {
            // Close existing editor so we apply current render mode. The form editor matching
            // strategy recognizes a FileEditorInput for an already-open granular editor.
            IEditorPart existingEditor = page.findEditor(new FileEditorInput(formFile));
            if (existingEditor != null)
            {
                page.closeEditor(existingEditor, false);
            }

            IEditorPart editorPart = IDE.openEditor(page, editorInput, FORM_EDITOR_ID, true);
            if (editorPart == null)
            {
                return OpenFormResult.error(
                    ToolResult.error("Could not open form editor for: " + formPath).toJson()); //$NON-NLS-1$
            }

            // Bring the editor to the top and give it focus so its WYSIWYG page builds and the
            // native render targets it; without this the just-opened editor can stay behind the
            // previously active one and the capture would read the wrong form.
            page.activate(editorPart);
            activateFormMainPage(editorPart);
            return OpenFormResult.success(editorPart, formFile);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return OpenFormResult.error(
                ToolResult.error("Failed to open form editor: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the EDT granular editor input ({@code IDtEditorInput}) for a form file, retrying
     * while the project's BM model is still loading.
     * <p>
     * {@link ContentUtil#createGranularEditorInput(IFile)} returns {@code null} when the form's
     * top object cannot be resolved from the BM model yet (e.g. the project is still being built
     * after EDT startup). Right after startup this is transient, so we poll a few times, pumping
     * the SWT event loop between attempts. Must be called on the UI thread.
     *
     * @param formFile the form {@code Form.form} file
     * @return the resolved editor input, or {@code null} if it never became available
     */
    private static IEditorInput resolveGranularEditorInput(IFile formFile)
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < EDITOR_INPUT_RESOLVE_RETRIES; i++)
        {
            try
            {
                IEditorInput input = ContentUtil.createGranularEditorInput(formFile);
                if (input != null)
                {
                    return input;
                }
            }
            catch (Exception e)
            {
                // Model resolution can fail transiently while the project loads; keep retrying.
                Activator.logWarning("Form model not resolvable yet: " + e.getMessage()); //$NON-NLS-1$
            }

            processEvents(display);
            sleep(EDITOR_INPUT_RESOLVE_INTERVAL_MS);
            processEvents(display);
        }
        return null;
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
     * Waits for the WYSIWYG (main) page of a <i>specific</i> form editor part to become available,
     * resolving it directly from that editor via {@code findPage("editors.form.pages.main")} rather
     * than via the global {@code FormEditor.getActiveFormEditorPage()} lookup. This guarantees the
     * returned page belongs to the editor that was opened for the requested form, even if another
     * form editor currently holds workbench focus (the wrong-form-screenshot case).
     * Must be called on the UI thread.
     *
     * @param editorPart the {@code FormEditor} part returned by {@link #openForm(String, String)}
     * @return the {@code FormEditorPage}, or {@code null} if it does not become ready in time
     */
    public static Object waitForFormEditorPageOf(IEditorPart editorPart)
    {
        if (editorPart == null)
        {
            return null;
        }
        Display display = Display.getCurrent();
        for (int i = 0; i < WYSIWYG_WAIT_RETRIES; i++)
        {
            processEvents(display);
            try
            {
                // Re-activate the main page each iteration so its createPartControl runs and the
                // viewer is created asynchronously, then accept the page only once it is ready.
                activateFormMainPage(editorPart);
                Object pageObject = findFormMainPage(editorPart);
                if (pageObject != null && isWysiwygPageReady(pageObject))
                {
                    return pageObject;
                }
            }
            catch (Exception e)
            {
                // Editor still initializing, keep waiting
            }
            sleep(WYSIWYG_WAIT_INTERVAL_MS);
            processEvents(display);
        }

        try
        {
            Object pageObject = findFormMainPage(editorPart);
            return (pageObject != null && isWysiwygPageReady(pageObject)) ? pageObject : null;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get form editor page for the opened editor", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves the WYSIWYG (main) {@code FormEditorPage} of a specific form editor part via the
     * Eclipse Forms {@code FormEditor.findPage(String)} API (reflectively, since the concrete type
     * is internal to EDT). Returns {@code null} when the part is not a form editor or the page does
     * not exist yet.
     *
     * @param editorPart the form editor part
     * @return the main {@code FormEditorPage}, or {@code null}
     */
    private static Object findFormMainPage(IEditorPart editorPart) throws Exception
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
        findPageMethod.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
        return findPageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
    }

    /**
     * Reports whether the form editor WYSIWYG page has finished building its controls, i.e. the
     * {@code wysiwygViewer} field is populated. The page sets {@code formControlsCreated} to
     * {@code true} only after the asynchronous control creation (which assigns the viewer)
     * completes, so it is used as a confirming signal when present.
     *
     * @param formEditorPage the {@code FormEditorPage} instance
     * @return {@code true} when the WYSIWYG viewer is available
     */
    private static boolean isWysiwygPageReady(Object formEditorPage)
    {
        try
        {
            Object viewer = ReflectionUtils.getFieldValue(formEditorPage, WYSIWYG_VIEWER_FIELD);
            if (viewer == null)
            {
                return false;
            }
            Object controlsCreated = ReflectionUtils.getFieldValue(formEditorPage, FORM_CONTROLS_CREATED_FIELD);
            // If the flag field is missing in this EDT version, fall back to viewer presence only.
            return !(controlsCreated instanceof Boolean) || ((Boolean)controlsCreated).booleanValue();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Gets the active form editor page via the static FormEditor API.
     */
    public static Object getActiveFormEditorPage() throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        Method method = editorClass.getMethod("getActiveFormEditorPage"); //$NON-NLS-1$
        return method.invoke(null);
    }

    // ==================== Identity guard (wrong-form screenshots) ====================

    /**
     * Returns the FQN of the metadata model behind a form editor part, e.g.
     * {@code "Catalog.Products.Form.ItemForm"} or {@code "CommonForm.MyForm"}. The model is read
     * from the editor input ({@link ContentUtil#getModel(Object)} resolves the granular DT input or
     * a plain file input to its EObject) and its FQN is read via {@code IBmObject.bmGetFqn()}.
     * Returns {@code null} when the part is not a form editor or its model FQN cannot be resolved.
     * <p>
     * Used as the identity guard before capturing a screenshot so that, if the resolved editor/page
     * does not correspond to the requested form, the caller returns an explicit error instead of a
     * wrong-form image.
     *
     * @param editorPart the form editor part
     * @return the model FQN, or {@code null}
     */
    public static String getFormEditorFqn(IEditorPart editorPart)
    {
        if (editorPart == null)
        {
            return null;
        }
        try
        {
            IEditorInput input = editorPart.getEditorInput();
            // ContentUtil.getModel resolves both EObject inputs and navigator-adaptable inputs.
            Object model = ContentUtil.getModel(input);
            String fqn = bmGetFqn(model);
            if (fqn != null)
            {
                return fqn;
            }
            // Fall back to the typed DT editor input's own getModel() (IDtEditorInput exposes the
            // form's metadata object directly) when the navigator adapter path yields nothing.
            Object typedModel = ReflectionUtils.invokeMethod(input, "getModel"); //$NON-NLS-1$
            return bmGetFqn(typedModel);
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not resolve form editor FQN: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reads {@code IBmObject.bmGetFqn()} from a model object via reflection (the BM interface is not
     * imported by name here). Returns {@code null} when unavailable.
     *
     * @param model the metadata model object
     * @return the FQN string, or {@code null}
     */
    private static String bmGetFqn(Object model)
    {
        if (model == null)
        {
            return null;
        }
        try
        {
            Method method = ReflectionUtils.findMethod(model.getClass(), "bmGetFqn"); //$NON-NLS-1$
            if (method == null)
            {
                return null;
            }
            method.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            Object fqn = method.invoke(model);
            return fqn != null ? fqn.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Tests whether an actual form-editor/representation model FQN denotes the same form as a
     * requested form FQN path, tolerating the differences that are legal across the tool surface:
     * case, English vs Russian metadata type names (via {@link MetadataTypeUtils}), the singular
     * {@code Form} vs plural {@code Forms} forms separator, and a trailing {@code .Form} content-form
     * segment.
     * <p>
     * The latter is essential for the image-level identity guard: the representation
     * renders the <i>content</i> form ({@code com._1c.g5.v8.dt.form.model.Form}), whose
     * {@code bmGetFqn()} is the external-property FQN of the MD-form's {@code form} reference and so
     * carries an extra trailing {@code .Form} segment compared to the MD-form FQN. For example the
     * representation's content-form FQN {@code "Catalog.Products.Form.ItemForm.Form"} (and the MD-form
     * FQN {@code "Catalog.Products.Form.ItemForm"}) both match the requested
     * {@code "Catalog.Products.Forms.ItemForm"}, and the common-form content FQN
     * {@code "CommonForm.MyForm.Form"} matches the requested {@code "CommonForm.MyForm"}.
     * Russian type names are tolerated too: {@code "Справочник.Товары.Форма.X"} matches
     * {@code "Catalog.Товары.Form.X"}.
     *
     * @param actualFqn the FQN read from the opened editor's model or the representation's content
     *            form (may be {@code null})
     * @param requestedFormPath the form FQN path the caller asked for
     * @return {@code true} when both denote the same form
     */
    public static boolean fqnMatchesFormPath(String actualFqn, String requestedFormPath)
    {
        String a = canonicalFormFqn(actualFqn);
        String b = canonicalFormFqn(requestedFormPath);
        return a != null && a.equals(b);
    }

    /**
     * Canonicalizes a form FQN/path to a comparison key built from the meaningful identity
     * (owner-type + owner-name + form-name, or type + name for common forms), so FQNs that denote the
     * same form but differ only in legal surface details compare equal. Specifically it:
     * <ul>
     * <li>strips a trailing {@code .Form} (or {@code .Форма}) content-form segment when present — the
     * representation's content form ({@code Form}) is attached under the external-property FQN of the
     * MD-form's {@code form} reference, which appends that segment (e.g.
     * {@code Catalog.Products.Form.ItemForm.Form} for the MD-form {@code Catalog.Products.Form.ItemForm},
     * and {@code CommonForm.MyForm.Form} for the common form {@code CommonForm.MyForm});</li>
     * <li>normalizes the leading metadata type segment to its English singular form (so Russian and
     * plural type names match);</li>
     * <li>collapses the {@code Form}/{@code Forms} (or Russian {@code Форма}/{@code Формы}) forms
     * separator of an object form to a single canonical token.</li>
     * </ul>
     * The trailing {@code .Form} segment is only treated as a content-form suffix when removing it
     * still leaves a recognized form shape (a 2-part common form or a 4-part object form). This keeps
     * a genuine form actually <i>named</i> {@code Form} (e.g. {@code Catalog.Products.Forms.Form})
     * intact: that 4-part FQN is canonicalized directly without stripping. Returns {@code null} for
     * unrecognized shapes so a non-form or malformed FQN never matches.
     *
     * @param fqn a form FQN or path (MD-form FQN, content-form FQN, or requested form path)
     * @return a canonical comparison key, or {@code null}
     */
    private static String canonicalFormFqn(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$

        // Content-form FQNs carry an extra trailing ".Form" content segment (the external-property
        // FQN of the MD-form's 'form' reference): a 3-part common-form content FQN
        // (CommonForm.Name.Form) or a 5-part object-form content FQN (Type.Owner.Form.Name.Form).
        // Strip that trailing segment and re-canonicalize the remaining MD-form FQN, so the
        // representation's content form matches the requested MD-form path. Only strip when the
        // trailing segment is literally the content separator AND the remainder is a valid form shape,
        // so a form genuinely named "Form" (a 4-part Type.Owner.Forms.Form) is not mis-stripped.
        if ((parts.length == 3 || parts.length == 5) && isContentFormSeparator(parts[parts.length - 1]))
        {
            String[] mdParts = new String[parts.length - 1];
            System.arraycopy(parts, 0, mdParts, 0, mdParts.length);
            return canonicalFormFqnFromParts(mdParts);
        }
        return canonicalFormFqnFromParts(parts);
    }

    /**
     * Canonicalizes an MD-form FQN already split into its dotted segments: a 2-part common form
     * ({@code CommonForm.Name}) or a 4-part object form ({@code Type.Owner.Form/Forms.Name}). Returns
     * {@code null} for any other shape (including a 4-part FQN whose third segment is not a forms
     * separator).
     *
     * @param parts the dotted segments of an MD-form FQN
     * @return a canonical comparison key, or {@code null}
     */
    private static String canonicalFormFqnFromParts(String[] parts)
    {
        if (parts.length == 2)
        {
            String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
            String typeKey = type != null ? type : parts[0];
            return (typeKey + "." + parts[1]).toLowerCase(); //$NON-NLS-1$
        }
        if (parts.length == 4)
        {
            if (!isFormsSeparator(parts[2]))
            {
                return null;
            }
            String type = MetadataTypeUtils.toEnglishSingular(parts[0]);
            String typeKey = type != null ? type : parts[0];
            return (typeKey + "." + parts[1] + ".form." + parts[3]).toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Reports whether a segment is the owner/forms separator of an object-form FQN: the canonical
     * singular {@code Form} or the plural {@code Forms}, English or Russian ({@code Форма}/{@code Формы}),
     * case-insensitive.
     *
     * @param segment the FQN segment between the owner name and the form name
     * @return {@code true} when it is a forms separator
     */
    private static boolean isFormsSeparator(String segment)
    {
        // Shared form-token predicate: Form/Forms and their Russian equivalents,
        // case-insensitive.
        return FormElementWriter.isFormToken(segment);
    }

    /** The Russian singular form token ("forma"), built from escapes — no raw Cyrillic in code. */
    private static final String RU_FORM_SINGULAR =
        MetadataLanguageUtils.cp(0x0444, 0x043e, 0x0440, 0x043c, 0x0430); // forma

    /**
     * Reports whether a trailing segment is the content-form separator appended by the external-
     * property FQN of the MD-form's {@code form} reference: the singular {@code Form} (English) or
     * {@code Форма} (Russian), case-insensitive. The content separator is always singular, unlike the
     * owner/forms separator which may be plural.
     *
     * @param segment the trailing FQN segment
     * @return {@code true} when it is a content-form separator
     */
    private static boolean isContentFormSeparator(String segment)
    {
        String s = segment.toLowerCase();
        return "form".equals(s) || RU_FORM_SINGULAR.equals(s); //$NON-NLS-1$
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
        Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        if (representation == null)
        {
            return null;
        }

        // Trigger rebuild so the native render produces an up-to-date image, then read it.
        rebuildRepresentation(representation);
        return readFormImageData(representation);
    }

    /**
     * Returns the {@code com._1c.g5.v8.dt.form.model.Form} model that the WYSIWYG representation is
     * actually rendering, read from its {@code form} field. This is the authoritative subject of the
     * representation's render: in the representation's rebuild task ({@code FormWysiwygRepresentation$2})
     * the form image and the {@code HippoLayForm} are produced together from a single
     * {@code HippoLayoutService.createHippoSession(tx, this.form, ...)} call, so the rendered image
     * always corresponds to <i>this</i> form model. Comparing this form's FQN to the requested form is
     * therefore a stronger image-identity guarantee than the editor input FQN alone.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return the form model object (an {@code IBmObject}/{@code EObject}), or {@code null}
     */
    public static Object getRepresentationForm(Object representation)
    {
        if (representation == null)
        {
            return null;
        }
        try
        {
            return ReflectionUtils.getFieldValue(representation, REPRESENTATION_FORM_FIELD);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Tests whether the form model the representation renders denotes the requested form. This is the
     * image-level identity guard against wrong-form screenshots: the captured image is produced from the
     * representation's own {@code form} model, so verifying that model's FQN matches the requested
     * {@code formPath} confirms the image belongs to the requested form, independent of which editor
     * currently holds workbench focus or what the shared offscreen render buffer last held.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param formPath the requested form FQN path
     * @return {@code true} when the representation's form model is the requested form
     */
    public static boolean representationFormMatches(Object representation, String formPath)
    {
        Object form = getRepresentationForm(representation);
        String fqn = bmGetFqn(form);
        return fqn != null && fqnMatchesFormPath(fqn, formPath);
    }

    /**
     * Reads the raw {@code formImageData} field of the representation <i>by reference</i>, including
     * an empty/zero-size placeholder. Unlike {@link #readFormImageData(Object)} this does not filter
     * out empty images and does not trigger a rebuild; it is used to detect when a render pass has
     * replaced the field with a freshly produced {@link ImageData} instance.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return the current {@code formImageData} reference, or {@code null}
     */
    private static ImageData getFormImageDataField(Object representation)
    {
        if (representation == null)
        {
            return null;
        }
        try
        {
            Object value = ReflectionUtils.getFieldValue(representation, FORM_IMAGE_DATA_FIELD);
            return value instanceof ImageData ? (ImageData)value : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Ensures the representation's own form is rendered and waits until its {@code formImageData}
     * field holds a <i>non-empty</i> {@link ImageData} (positive width and height), then reports
     * success. It does <b>not</b> require the image to be a brand-new instance.
     * <p>
     * <b>Correctness model.</b> Image identity is guaranteed by the caller's
     * {@link #representationFormMatches(Object, String)} guard, not by this method: that guard proves
     * the representation's own {@code form} field is the requested form, and the representation's image
     * and {@code HippoLayForm} are produced together from a single
     * {@code HippoLayoutService.createHippoSession(tx, this.form, ...)} call, so {@code formImageData}
     * read from a representation whose {@code form} is the requested form is the requested form's image.
     * Therefore the only thing this method must establish is that <i>an</i> image has actually been
     * produced for that representation (non-empty), not that it is a freshly created object.
     * <p>
     * <b>Why the "must be a NEW instance" gate was removed (the regression).</b> An earlier version
     * required {@code formImageData} to become a different object than before reading it. In this
     * detached/MCP-driven EDT the native render reuses the existing {@link ImageData} (or never swaps
     * the reference because the async swap is dropped), so that gate was never satisfied and the tool
     * returned the "does not render the requested form model" error for <i>every</i> form, including
     * genuinely renderable ones. {@code get_form_layout_snapshot}, which reads the same representation's
     * {@code hippoLayForm}, returns the requested form correctly — proving the same representation's
     * {@code formImageData} is populated for the requested form. So a non-empty image on the
     * identity-verified representation is the correct, sufficient condition.
     * <p>
     * The render is still driven <b>synchronously</b> via {@link #renderRequestedFormSynchronously(Object)}
     * as a best-effort to populate/refresh the buffer first (it runs the same
     * {@code createHippoSession(tx, this.form, ...)} render the representation's rebuild task runs, on the
     * UI thread without the dropped async scheduling). If that path is unavailable, an asynchronous
     * {@code rebuild(true)} is used. But neither is a gate: if the image is already non-empty (the
     * snapshot proves it is for the requested form), this returns immediately, and after triggering a
     * rebuild it succeeds as soon as the image is non-empty. Must be called on the UI thread.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return {@code true} if the representation's {@code formImageData} is non-empty within the timeout
     */
    public static boolean ensureRenderedFormImage(Object representation)
    {
        return ensureRenderedFormImage(representation, FRESH_RENDER_WAIT_TIMEOUT_MS, false);
    }

    /**
     * Same as {@link #ensureRenderedFormImage(Object)} but with an explicit force-refresh mode for
     * {@code refresh=true} captures (the stale-screenshot defect).
     * <p>
     * With {@code forceRefresh=false} the behavior is identical to {@link #ensureRenderedFormImage(Object)}:
     * a pre-existing non-empty image is accepted as-is. With {@code forceRefresh=true} the pre-existing
     * buffer is deliberately <b>not</b> trusted — the caller asked for a refresh because the form may have
     * just been edited, so returning the previously rendered (pre-edit) image as "refreshed" is exactly
     * the defect. Instead a real re-render is forced and this method succeeds only when there is evidence
     * that a render actually ran during this call:
     * <ul>
     * <li>the pre-existing {@code formImageData} buffer is cleared first, so any non-empty image observed
     * afterwards is provably the product of a new render — this stays sound even though the native render
     * may reuse the same {@link ImageData} instance (which is why instance identity alone was abandoned as
     * a freshness signal, see {@link #ensureRenderedFormImage(Object)});</li>
     * <li>a completed synchronous render pass ({@code RenderOutcome.RENDERED} from
     * {@link #renderRequestedFormSynchronously(Object)}) is accepted as direct evidence, since it
     * reassigns {@code formImageData} inline;</li>
     * <li>if the buffer could not be cleared, a fresh image must at least be a <i>different</i> instance
     * than the pre-existing one (weaker; a false negative here surfaces as an explicit error, never as a
     * stale image).</li>
     * </ul>
     * When no fresh render can be established within the timeout, this returns {@code false} so the caller
     * can fail with an explicit error instead of silently returning a stale image. Must be called on the
     * UI thread.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param forceRefresh {@code true} to require evidence of a re-render performed during this call,
     *            {@code false} to accept a pre-existing non-empty image
     * @return {@code true} if a (fresh, when forced) non-empty image is available within the timeout
     */
    public static boolean ensureRenderedFormImage(Object representation, boolean forceRefresh)
    {
        return ensureRenderedFormImage(representation, FRESH_RENDER_WAIT_TIMEOUT_MS, forceRefresh);
    }

    /**
     * Same as {@link #ensureRenderedFormImage(Object, boolean)} but with an explicit timeout.
     * Package-visible so unit tests can use a short budget instead of the production wait.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param timeoutMs maximum time to wait for a (fresh, when forced) non-empty image, in milliseconds
     * @param forceRefresh {@code true} to require evidence of a re-render performed during this call
     * @return {@code true} if a (fresh, when forced) non-empty image is available within the timeout
     */
    static boolean ensureRenderedFormImage(Object representation, int timeoutMs, boolean forceRefresh)
    {
        if (representation == null)
        {
            return false;
        }

        ImageData baseline = getFormImageDataField(representation);
        boolean hadPreexistingImage = isNonEmpty(baseline);

        // If an image is already present for this (identity-verified) representation, use it directly:
        // the layout snapshot proves formImageData is populated for the requested form, and a brand-new
        // instance is NOT required. No need to wait for or force another render. NOT acceptable in
        // force-refresh mode: that pre-existing image is exactly the potentially stale buffer the
        // caller asked to re-render.
        if (!forceRefresh && hadPreexistingImage)
        {
            return true;
        }

        // Force-refresh with a pre-existing image: drop the stale buffer so any non-empty image seen
        // afterwards is provably produced by a render that ran during this call, even when the native
        // render reuses the same ImageData instance. If the field cannot be cleared, fall back to
        // requiring a different instance than the baseline — that can false-negative on instance reuse,
        // which then surfaces as an explicit "could not re-render" error rather than a stale image.
        boolean requireNewInstance = false;
        if (forceRefresh && hadPreexistingImage)
        {
            requireNewInstance = !clearFormImageData(representation);
        }

        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + timeoutMs;

        // Best-effort primary path: drive the representation's own synchronous render directly so the
        // requested form is painted into the shared offscreen buffer and read back into formImageData,
        // bypassing the asynchronous rebuild scheduling that is dropped in a detached/headless EDT.
        // Retry within the budget because the mapping root can be transiently unavailable right after
        // the editor opens; pump the event loop between attempts so the model finishes loading. Outside
        // force mode this is NOT a gate: as soon as the image is non-empty we return, regardless of why
        // it became non-empty. In force mode the image must additionally be fresh (see above).
        boolean syncPathReachable = false;
        while (System.currentTimeMillis() < deadline)
        {
            RenderOutcome outcome = renderRequestedFormSynchronously(representation);
            if (outcome == RenderOutcome.RENDERED && hasNonEmptyImage(representation))
            {
                // A full synchronous render ran during this call and reassigned formImageData, so the
                // (non-empty) image is fresh by construction — sufficient in both modes.
                return true;
            }
            if (hasFreshImage(representation, baseline, requireNewInstance))
            {
                return true;
            }
            if (outcome == RenderOutcome.UNREACHABLE)
            {
                // The synchronous hooks do not exist on this EDT; stop retrying and use the fallback.
                break;
            }
            syncPathReachable = true;
            processEvents(display);
            sleep(RENDER_WAIT_POLL_INTERVAL_MS);
        }
        if (syncPathReachable)
        {
            // The synchronous hooks are present but did not produce a (fresh) image within the budget; // NOSONAR explanatory comment, not commented-out code
            // one last check (the render task may have settled while pumping events).
            return hasFreshImage(representation, baseline, requireNewInstance);
        }

        // Fallback (older/different EDT where rebuildInternal is not reachable): re-trigger the async
        // rebuild and poll until the image is non-empty (and fresh, in force mode). The async
        // mapping-root callback skips re-entrant rebuilds while the representation's rebuild lock is
        // held, so a single request can be dropped; re-requesting until the image is present makes the
        // wait robust.
        return runAsyncRebuildFallback(representation, timeoutMs, display, baseline, requireNewInstance);
    }

    /**
     * Fallback render wait used when the synchronous render hooks are not reachable on this EDT.
     * Re-triggers the asynchronous rebuild and polls until a (fresh, when forced) non-empty image is
     * available, performing one final check after the budget elapses. Extracted verbatim from
     * {@link #ensureRenderedFormImage(Object, int, boolean)} to keep that method's complexity in check.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param timeoutMs maximum time to wait, in milliseconds
     * @param display the current display used to pump the event loop (may be {@code null})
     * @param baseline the image observed before the wait, used for freshness comparison
     * @param requireNewInstance {@code true} to require a different {@code ImageData} instance than the
     *            baseline (used when the stale buffer could not be cleared)
     * @return {@code true} if a (fresh, when forced) non-empty image became available within the budget
     */
    private static boolean runAsyncRebuildFallback(Object representation, int timeoutMs, Display display,
        ImageData baseline, boolean requireNewInstance)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            rebuildRepresentation(representation);
            if (hasFreshImage(representation, baseline, requireNewInstance))
            {
                return true;
            }
            processEvents(display);
            sleep(RENDER_WAIT_POLL_INTERVAL_MS);
            if (hasFreshImage(representation, baseline, requireNewInstance))
            {
                return true;
            }
        }
        return hasFreshImage(representation, baseline, requireNewInstance);
    }

    /**
     * Clears the representation's {@code formImageData} field so that a subsequently observed non-empty
     * image is provably the product of a render performed afterwards (the force-refresh freshness
     * detector). Dropping the buffer is safe: it is only a cache of the last render, the caller has
     * explicitly requested a re-render, and every read path treats an absent image as "not rendered yet"
     * rather than an error.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return {@code true} when the field was found and cleared
     */
    private static boolean clearFormImageData(Object representation)
    {
        try
        {
            Class<?> type = representation.getClass();
            while (type != null)
            {
                try
                {
                    Field field = type.getDeclaredField(FORM_IMAGE_DATA_FIELD);
                    field.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
                    field.set(representation, null); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
                    return true;
                }
                catch (NoSuchFieldException e)
                {
                    type = type.getSuperclass();
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not clear the previous form image before a forced re-render: " //$NON-NLS-1$
                + e.getMessage());
        }
        return false;
    }

    /**
     * Runs the representation's own native render <b>synchronously</b> for its current {@code form},
     * bypassing the asynchronous scheduling that never completes in a detached/headless EDT.
     * <p>
     * <b>Why this is needed (success-path regression).</b> The public entry point
     * {@code FormWysiwygRepresentation.rebuild(boolean)} does not render directly: it calls
     * {@code MappingController.getMappingRootAsync(CommandInterfaceMapping.class, handler)}, which
     * spawns a background {@code Thread} that, once the mapping root is computed, posts the actual
     * render back to the UI thread via {@code Display.syncExec}. The real render is what that handler
     * runs synchronously inside {@code rebuildInternal(...)}: a single
     * {@code bmModel.executeAndRollback} task that calls
     * {@code HippoLayoutService.INSTANCE.createHippoSession(tx, this.form, ...)} and then assigns
     * {@code this.formImageData = session.getFormImageData()} and
     * {@code this.hippoLayForm = session.getHippoForm()}. In this MCP-driven EDT the UI thread is the
     * one calling us and is blocked, so the background thread's {@code syncExec} is dropped and the
     * async render never completes — the screenshot wait always timed out, breaking even the genuinely
     * active form.
     * <p>
     * This method removes the async hop: it obtains the mapping root <i>synchronously</i> via
     * {@code MappingController.getMappingRoot(CommandInterfaceMapping.class)} (the synchronous sibling
     * of {@code getMappingRootAsync}), then invokes the private
     * {@code rebuildInternal(Form, CommandInterfaceMapping, NativeRenderEvent, boolean)} directly on the
     * current UI thread, passing the representation's own {@code form} field. That runs exactly the same
     * {@code createHippoSession} render and reassigns {@code formImageData}/{@code hippoLayForm} for the
     * requested form, so the image read afterwards is provably the requested form's. Must be called on
     * the UI thread.
     * <p>
     * <b>Class resolution.</b> The {@code CommandInterfaceMapping} and {@code NativeRenderEvent}
     * classes are taken from {@code rebuildInternal}'s own parameter types (index 1 and 2 of the
     * located method), NOT via {@code Class.forName}: this bundle's classloader deliberately does not
     * import {@code com._1c.g5.v8.dt.form.mapping.model} / {@code com._1c.g5.v8.dt.form.model}, so a
     * {@code Class.forName} here always threw {@code ClassNotFoundException} and silently disabled the
     * whole synchronous path (the dead-sync-render defect: refresh=true cleared {@code formImageData},
     * depended on this path to repopulate it, and timed out instead). The parameter types come from the
     * form bundle's own classloader and are correct by construction.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return {@link RenderOutcome#RENDERED} if the synchronous render ran to completion,
     *         {@link RenderOutcome#NOT_READY} if its hooks exist but the mapping root is not yet
     *         available (retry), or {@link RenderOutcome#UNREACHABLE} if the hooks do not exist on this
     *         EDT (use the async fallback)
     */
    private static RenderOutcome renderRequestedFormSynchronously(Object representation)
    {
        try
        {
            // The form model the representation renders (the same field rebuildInternal would read).
            Object form = ReflectionUtils.getFieldValue(representation, REPRESENTATION_FORM_FIELD);
            Object controller = ReflectionUtils.getFieldValue(representation, REPRESENTATION_CONTROLLER_FIELD);
            Method rebuildInternal = findMethodByNameAndArity(representation.getClass(),
                REBUILD_INTERNAL_METHOD, 4);
            Method getMappingRoot = controller == null ? null
                : ReflectionUtils.findMethod(controller.getClass(), GET_MAPPING_ROOT_METHOD, Class.class);
            if (controller == null || rebuildInternal == null || getMappingRoot == null)
            {
                // This EDT does not expose the synchronous render hooks; signal the async fallback.
                return RenderOutcome.UNREACHABLE;
            }
            // The platform classes come from rebuildInternal's OWN parameter types — resolved by the
            // form bundle's classloader. rebuildInternal(Form, CommandInterfaceMapping,
            // NativeRenderEvent, boolean): [1] = CommandInterfaceMapping, [2] = NativeRenderEvent.
            // Class.forName here used OUR bundle classloader, which does not (and must not) import
            // those packages, so it always failed and the whole synchronous path was dead.
            Class<?>[] paramTypes = rebuildInternal.getParameterTypes();
            if (paramTypes[3] != boolean.class && paramTypes[3] != Boolean.class)
            {
                // Not the rebuildInternal(Form, CommandInterfaceMapping, NativeRenderEvent, boolean)
                // shape this path drives; treat the hooks as absent rather than mis-invoke.
                return RenderOutcome.UNREACHABLE;
            }
            Class<?> cmiMappingClass = paramTypes[1];
            Class<?> nativeRenderEventClass = paramTypes[2];
            if (form == null)
            {
                // Hooks exist but the form is not set yet; let the caller retry once it loads.
                return RenderOutcome.NOT_READY;
            }

            // Synchronous sibling of getMappingRootAsync: compute the CommandInterfaceMapping root on
            // THIS thread instead of the background thread whose syncExec callback gets dropped.
            getMappingRoot.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            Object cmiMapping = getMappingRoot.invoke(controller, cmiMappingClass);
            if (cmiMapping == null)
            {
                // Mapping not ready yet; retry rather than render half-built.
                return RenderOutcome.NOT_READY;
            }

            // NativeRenderEvent.buildUpdateEvent() — the same event rebuild(boolean) constructs.
            Method buildUpdateEvent = nativeRenderEventClass.getMethod(BUILD_UPDATE_EVENT_METHOD);
            Object event = buildUpdateEvent.invoke(null);

            // Invoke the private rebuildInternal(Form, CommandInterfaceMapping, NativeRenderEvent,
            // boolean) directly: this is the synchronous body the async handler would have run.
            rebuildInternal.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            // updateOnly=false → force a full layout/render pass for this form.
            rebuildInternal.invoke(representation, form, cmiMapping, event, Boolean.FALSE);

            // Drain the redraw the render task scheduled so the offscreen buffer is settled.
            processEvents(Display.getCurrent());
            return RenderOutcome.RENDERED;
        }
        catch (Exception e)
        {
            Activator.logWarning("Synchronous form render failed, falling back to async rebuild: " //$NON-NLS-1$
                + e.getMessage());
            return RenderOutcome.UNREACHABLE;
        }
    }

    /**
     * Outcome of an attempt to drive the representation's synchronous render
     * ({@link #renderRequestedFormSynchronously(Object)}).
     */
    private enum RenderOutcome
    {
        /** A full synchronous render ran and reassigned {@code formImageData} for the requested form. */
        RENDERED,
        /** The synchronous hooks exist but inputs (form/mapping root) are not ready yet; retry. */
        NOT_READY,
        /** The synchronous hooks are not present on this EDT; use the async fallback instead. */
        UNREACHABLE
    }

    /**
     * Finds the first declared method on {@code clazz} (or a superclass) with the given name and number
     * of parameters, without needing the parameter types. Used to reach an internal method whose
     * parameter classes are not referenced by name here.
     *
     * @param clazz the class to start searching from
     * @param name the method name
     * @param paramCount the expected number of parameters
     * @return the matching method, or {@code null} if none is found
     */
    private static Method findMethodByNameAndArity(Class<?> clazz, String name, int paramCount)
    {
        Class<?> type = clazz;
        while (type != null)
        {
            for (Method method : type.getDeclaredMethods())
            {
                if (method.getName().equals(name) && method.getParameterCount() == paramCount)
                {
                    return method;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    /**
     * Reports whether the representation's {@code formImageData} field currently holds a non-empty
     * image (positive width and height). This is the relaxed render-readiness check:
     * it deliberately does <b>not</b> require the image to be a new/different instance than before,
     * because in this detached/MCP-driven EDT the native render reuses the same {@link ImageData} and
     * the "must be a brand-new instance" requirement was never satisfied, breaking screenshots for
     * every form. Image identity for the requested form is guaranteed separately by
     * {@link #representationFormMatches(Object, String)} on the representation's own {@code form} model.
     *
     * @param representation the representation
     * @return {@code true} when a non-empty image is present
     */
    private static boolean hasNonEmptyImage(Object representation)
    {
        return isNonEmpty(getFormImageDataField(representation));
    }

    /**
     * Reports whether an {@link ImageData} holds an actual image (non-null with positive dimensions).
     *
     * @param imageData the image data, may be {@code null}
     * @return {@code true} when the image is non-empty
     */
    private static boolean isNonEmpty(ImageData imageData)
    {
        return imageData != null && imageData.width > 0 && imageData.height > 0;
    }

    /**
     * Reports whether the representation currently holds a non-empty image that satisfies the
     * force-refresh freshness requirement: when {@code requireNewInstance} is set (the pre-existing
     * stale buffer could not be cleared), the image must be a different {@link ImageData} instance than
     * the {@code baseline} captured before the re-render was driven; otherwise any non-empty image is
     * accepted (the buffer was cleared first — or there was no pre-existing image — so only a render
     * performed during this call can have populated it).
     *
     * @param representation the representation
     * @param baseline the {@code formImageData} reference captured on entry (may be {@code null})
     * @param requireNewInstance {@code true} to require a different instance than the baseline
     * @return {@code true} when a non-empty (and fresh, when required) image is present
     */
    private static boolean hasFreshImage(Object representation, ImageData baseline, boolean requireNewInstance)
    {
        ImageData current = getFormImageDataField(representation);
        if (!isNonEmpty(current))
        {
            return false;
        }
        return !requireNewInstance || current != baseline;
    }

    /**
     * Reads the currently rendered form image from the representation without triggering a
     * rebuild. Returns {@code null} when the native render has not produced an image yet.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @return image data, or {@code null} if not available
     */
    public static ImageData readFormImageData(Object representation)
    {
        if (representation == null)
        {
            return null;
        }
        try
        {
            Method method = representation.getClass().getDeclaredMethod(FORM_IMAGE_METHOD);
            method.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            ImageData data = (ImageData)method.invoke(representation);
            if (data != null && data.width > 0 && data.height > 0)
            {
                return data;
            }
        }
        catch (NoSuchMethodException e)
        {
            Activator.logWarning("Method " + FORM_IMAGE_METHOD + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read form image data: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Fallback capture method: captures the WYSIWYG control image via {@code Control.print()}.
     * <p>
     * {@code Control.print()} only produces a faithful image when the control is actually
     * shown on screen: for a control that is hidden, behind another editor tab, or on a
     * non-active editor it yields a blank or partial image while still reporting positive
     * dimensions, which would be reported as a successful (but empty) screenshot. To avoid
     * that false positive, this returns {@code null} unless the control is genuinely visible
     * and on top (its top-level shell is the display's active shell), so the caller falls
     * back to the explicit "form layout did not finish rendering" error instead.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if the control is unavailable, has invalid bounds,
     *         or is not visible/on top
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

        // Gate the print fallback on the control being shown and on top. isVisible() is
        // true only when the control and all its ancestors are visible (so a control on a
        // hidden/behind editor is excluded), and we additionally require its shell to be
        // the active shell so a background window does not yield a blank capture.
        if (!control.isVisible() || !isOnTop(control))
        {
            return null;
        }

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
     * Returns {@code true} when the control's top-level shell is the display's active shell,
     * i.e. the control is on the window currently on top. Used to gate the {@code print()}
     * fallback so a background/non-active editor does not produce a blank capture.
     *
     * @param control the control to test
     * @return {@code true} when the control's shell is the active shell
     */
    private static boolean isOnTop(Control control)
    {
        Display display = control.getDisplay();
        if (display == null)
        {
            return false;
        }
        return control.getShell() == display.getActiveShell();
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

    // ==================== Render readiness ====================

    /**
     * Returns the {@code FormWysiwygRepresentation} backing the given viewer, or {@code null}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return the representation object, or {@code null} if not available
     */
    public static Object getRepresentation(Object wysiwygViewer)
    {
        try
        {
            return ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Triggers a full rebuild of the WYSIWYG representation so the native layout/render
     * pipeline recomputes element bounds and the form image. Pumps a few UI cycles so the
     * asynchronous native render can deliver its result.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     */
    public static void rebuildRepresentation(Object representation)
    {
        if (representation == null)
        {
            return;
        }
        try
        {
            Method rebuildMethod = representation.getClass().getDeclaredMethod(REBUILD_METHOD, boolean.class);
            rebuildMethod.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            rebuildMethod.invoke(representation, true);

            Display display = Display.getCurrent();
            for (int i = 0; i < 5; i++)
            {
                processEvents(display);
                sleep(RENDER_WAIT_POLL_INTERVAL_MS);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not rebuild WYSIWYG representation: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Waits until the form WYSIWYG layout has actually finished rendering.
     * <p>
     * The EDT form layout is produced by an asynchronous, event-driven native render
     * ({@code NativeRenderService}); right after a form is opened or its structure changes,
     * the first attempt to read the layout or the image returns nothing because rendering has
     * not completed yet. This method triggers a rebuild and then polls the supplied readiness
     * predicate, pumping the SWT event loop between polls, until the predicate is satisfied or
     * the timeout elapses.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @param renderedCheck predicate that returns {@code true} once the rendered content
     *            (calculated bounds or form image) is available
     * @return {@code true} if the form rendered within the timeout, {@code false} otherwise
     */
    public static boolean waitUntilRendered(Object wysiwygViewer, BooleanSupplier renderedCheck)
    {
        return waitUntilRendered(wysiwygViewer, renderedCheck, RENDER_WAIT_TIMEOUT_MS);
    }

    /**
     * Same as {@link #waitUntilRendered(Object, BooleanSupplier)} but with an explicit timeout.
     * Public so callers (and tests) can choose a bounded wait, e.g. the layout-snapshot tool uses a
     * slightly longer budget than the screenshot tool while still pumping the SWT event loop.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @param renderedCheck predicate that returns {@code true} once the rendered content is available
     * @param timeoutMs maximum time to wait, in milliseconds
     * @return {@code true} if the form rendered within the timeout, {@code false} otherwise
     */
    public static boolean waitUntilRendered(Object wysiwygViewer, BooleanSupplier renderedCheck, int timeoutMs)
    {
        Object representation = getRepresentation(wysiwygViewer);
        Display display = Display.getCurrent();

        if (safeCheck(renderedCheck))
        {
            return true;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean rebuilt = false;
        while (System.currentTimeMillis() < deadline)
        {
            if (!rebuilt && representation != null)
            {
                rebuildRepresentation(representation);
                rebuilt = true;
            }

            processEvents(display);
            if (safeCheck(renderedCheck))
            {
                return true;
            }
            sleep(RENDER_WAIT_POLL_INTERVAL_MS);
            processEvents(display);
            if (safeCheck(renderedCheck))
            {
                return true;
            }
        }

        return safeCheck(renderedCheck);
    }

    private static boolean safeCheck(BooleanSupplier renderedCheck)
    {
        try
        {
            return renderedCheck.getAsBoolean();
        }
        catch (Exception e)
        {
            // A transient failure while the model is still being built is treated as "not ready".
            return false;
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
                setActivePageMethod.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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

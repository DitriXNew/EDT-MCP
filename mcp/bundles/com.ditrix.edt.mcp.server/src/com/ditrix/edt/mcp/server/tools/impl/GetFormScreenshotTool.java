/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to capture a screenshot of a form WYSIWYG editor as PNG.
 * Can automatically open and activate a form by its metadata FQN path.
 */
public class GetFormScreenshotTool implements IMcpTool
{
    public static final String NAME = "get_form_screenshot"; //$NON-NLS-1$
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

    /** Mapping from singular metadata type (FQN) to plural directory name in src/ */
    private static final Map<String, String> METADATA_TYPE_TO_DIR = new HashMap<>();
    static
    {
        METADATA_TYPE_TO_DIR.put("catalog", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("document", "Documents"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("dataprocessor", "DataProcessors"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("report", "Reports"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("informationregister", "InformationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("accumulationregister", "AccumulationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("accountingregister", "AccountingRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("calculationregister", "CalculationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("exchangeplan", "ExchangePlans"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("businessprocess", "BusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("task", "Tasks"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("chartofaccounts", "ChartsOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("chartofcharacteristictypes", "ChartsOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("chartofcalculationtypes", "ChartsOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("documentjournal", "DocumentJournals"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("settingsstorage", "SettingsStorages"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("filtercriterion", "FilterCriteria"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("externaldatasource", "ExternalDataSources"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("enum", "Enums"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("constant", "Constants"); //$NON-NLS-1$ //$NON-NLS-2$
        METADATA_TYPE_TO_DIR.put("commonform", "CommonForms"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Capture a screenshot of the active form WYSIWYG editor as PNG. " + //$NON-NLS-1$
               "Can open and activate a form automatically by metadata FQN path."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required when formPath is specified.") //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Metadata FQN path to the form. " + //$NON-NLS-1$
                "Format: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products.Forms.ItemForm', 'Document.SalesOrder.Forms.DocumentForm', " + //$NON-NLS-1$
                "'CommonForm.MyForm'. If not specified, captures the currently active form editor.") //$NON-NLS-1$
            .booleanProperty("refresh", "Force WYSIWYG refresh before capture (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        boolean refresh = "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "refresh")); //$NON-NLS-1$ //$NON-NLS-2$

        // Validate: if formPath is specified, projectName is required
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

        AtomicReference<ToolResult> resultRef = new AtomicReference<>(ToolResult.error("Capture failed")); //$NON-NLS-1$
        display.syncExec(() -> resultRef.set(captureScreenshot(projectName, formPath, refresh)));
        return resultRef.get().toJson();
    }

    /**
     * Main capture logic. Runs on UI thread.
     */
    private ToolResult captureScreenshot(String projectName, String formPath, boolean refresh)
    {
        try
        {
            Object editorPage;

            if (formPath != null && !formPath.isEmpty())
            {
                // Open/activate the specified form
                ToolResult openResult = openAndActivateForm(projectName, formPath);
                if (openResult != null)
                {
                    return openResult; // Error occurred
                }

                // Give UI time to process after activation
                Display display = Display.getCurrent();
                if (display != null)
                {
                    for (int i = 0; i < 5; i++)
                    {
                        while (display.readAndDispatch())
                        {
                            // Process all pending UI events
                        }
                        try
                        {
                            Thread.sleep(100);
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // Wait for WYSIWYG to be ready and get the editor page
                editorPage = waitForFormEditorPage();
                if (editorPage == null)
                {
                    // Try to get diagnostic info about what's available
                    String diagnostic = getDiagnosticInfo();
                    Activator.logWarning("Form editor opened but WYSIWYG page is not available. " + diagnostic); //$NON-NLS-1$
                    
                    return ToolResult.error(
                        "Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading. " + diagnostic); //$NON-NLS-1$
                }
            }
            else
            {
                // Use active editor (legacy behavior)
                editorPage = getActiveFormEditorPage();
                if (editorPage == null)
                {
                    return ToolResult.error(
                        "No active form editor page found. " + //$NON-NLS-1$
                        "Specify formPath parameter to open a form automatically."); //$NON-NLS-1$
                }
            }

            Object wysiwygViewer = getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (wysiwygViewer == null)
            {
                return ToolResult.error("WYSIWYG viewer is not available"); //$NON-NLS-1$
            }

            if (refresh)
            {
                invokeMethod(wysiwygViewer, REFRESH_METHOD);
            }

            ImageData imageData = extractFormImageData(wysiwygViewer);
            String source = "form_image_data"; //$NON-NLS-1$
            if (imageData == null)
            {
                imageData = captureControlImageData(wysiwygViewer);
                source = "control_print"; //$NON-NLS-1$
            }

            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
                return ToolResult.error("Form image data is not available"); //$NON-NLS-1$
            }

            String base64 = encodePng(imageData);
            ToolResult result = ToolResult.success()
                .put("mimeType", "image/png") //$NON-NLS-1$ //$NON-NLS-2$
                .put("width", imageData.width) //$NON-NLS-1$
                .put("height", imageData.height) //$NON-NLS-1$
                .put("source", source); //$NON-NLS-1$

            if (formPath != null && !formPath.isEmpty())
            {
                result.put("formPath", formPath); //$NON-NLS-1$
            }

            result.put("dataBase64", base64); //$NON-NLS-1$
            return result;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture form screenshot", e); //$NON-NLS-1$
            return ToolResult.error("Failed to capture form screenshot: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Opens a form file in the editor and activates the WYSIWYG (main) page.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return null on success, ToolResult with error on failure
     */
    private ToolResult openAndActivateForm(String projectName, String formPath)
    {
        // Resolve the form file
        String relativePath = resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products.Forms.ItemForm') " + //$NON-NLS-1$
                "or 'CommonForm.FormName' (e.g. 'CommonForm.MyForm')."); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName); //$NON-NLS-1$
        }

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return ToolResult.error("Form file not found: " + relativePath + " in project " + projectName); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Get workbench page
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
            return ToolResult.error("No workbench window available"); //$NON-NLS-1$
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return ToolResult.error("No active workbench page"); //$NON-NLS-1$
        }

        try
        {
            // Open the form file in the form editor
            IEditorPart editorPart = IDE.openEditor(page, formFile, FORM_EDITOR_ID, true);
            if (editorPart == null)
            {
                return ToolResult.error("Could not open form editor for: " + formPath); //$NON-NLS-1$
            }

            // Activate the WYSIWYG (main) page via reflection
            activateFormMainPage(editorPart);

            return null; // Success
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return ToolResult.error("Failed to open form editor: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Activates the main (WYSIWYG) page of the form editor via reflection.
     * Uses setActivePage("editors.form.pages.main") from the DtGranularEditor.
     */
    private void activateFormMainPage(IEditorPart editorPart)
    {
        try
        {
            Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
            if (!editorClass.isInstance(editorPart))
            {
                Activator.logWarning("Editor is not a FormEditor: " + editorPart.getClass().getName()); //$NON-NLS-1$
                return;
            }

            // Call setActivePage(String pageId) — inherited from FormEditor -> DtGranularEditor -> FormEditor (Eclipse)
            Method setActivePageMethod = findMethod(editorPart.getClass(), "setActivePage", String.class); //$NON-NLS-1$
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
     * Waits for the form editor WYSIWYG page to become available.
     * Processes UI events while waiting to allow the editor to initialize.
     *
     * @return FormEditorPage or null if not available after timeout
     */
    private Object waitForFormEditorPage()
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < WYSIWYG_WAIT_RETRIES; i++)
        {
            // Process pending UI events to allow editor initialization
            if (display != null)
            {
                while (display.readAndDispatch())
                {
                    // Process all pending events
                }
            }

            try
            {
                Object page = getActiveFormEditorPage();
                if (page != null)
                {
                    Object viewer = getFieldValue(page, WYSIWYG_VIEWER_FIELD);
                    if (viewer != null)
                    {
                        Activator.logInfo("WYSIWYG viewer found after " + (i + 1) + " attempts"); //$NON-NLS-1$ //$NON-NLS-2$
                        return page;
                    }
                    else
                    {
                        Activator.logInfo("Page found but viewer is null (attempt " + (i + 1) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                else
                {
                    Activator.logInfo("Page is null (attempt " + (i + 1) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Exception e)
            {
                Activator.logInfo("Exception while checking WYSIWYG (attempt " + (i + 1) + "): " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }

            // Brief sleep (non-blocking on UI thread — we process events)
            try
            {
                Thread.sleep(WYSIWYG_WAIT_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }

            // Process events again after sleep
            if (display != null)
            {
                while (display.readAndDispatch())
                {
                    // Process all pending events
                }
            }
        }

        // Final attempt
        try
        {
            Object page = getActiveFormEditorPage();
            if (page != null)
            {
                Activator.logWarning("Page found on final attempt but might not have viewer"); //$NON-NLS-1$
            }
            return page;
        }
        catch (Exception e)
        {
            Activator.logError("Final attempt failed", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves a form FQN path to a file path relative to project root.
     *
     * Supported formats:
     * - "Catalog.Products.Forms.ItemForm" -> "src/Catalogs/Products/Forms/ItemForm/Form.form"
     * - "Document.SalesOrder.Forms.DocumentForm" -> "src/Documents/SalesOrder/Forms/DocumentForm/Form.form"
     * - "CommonForm.MyForm" -> "src/CommonForms/MyForm/Form.form"
     *
     * @param formPath FQN path
     * @return file path relative to project root, or null if cannot resolve
     */
    private String resolveFormFilePath(String formPath)
    {
        if (formPath == null || formPath.isEmpty())
        {
            return null;
        }

        String[] parts = formPath.split("\\."); //$NON-NLS-1$

        // CommonForm.FormName (2 parts)
        if (parts.length == 2)
        {
            String type = parts[0].toLowerCase();
            if ("commonform".equals(type) || "commonforms".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "src/CommonForms/" + parts[1] + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return null;
        }

        // MetadataType.ObjectName.Forms.FormName (4 parts)
        if (parts.length == 4)
        {
            String metadataType = parts[0].toLowerCase();
            String objectName = parts[1];
            String formsKeyword = parts[2];
            String formName = parts[3];

            // Validate "Forms" keyword
            if (!"forms".equalsIgnoreCase(formsKeyword)) //$NON-NLS-1$
            {
                return null;
            }

            String dirName = METADATA_TYPE_TO_DIR.get(metadataType);
            if (dirName == null)
            {
                // Try with plural (if user specified "Catalogs" instead of "Catalog")
                // Remove trailing 's' and try again
                if (metadataType.endsWith("s")) //$NON-NLS-1$
                {
                    dirName = METADATA_TYPE_TO_DIR.get(metadataType.substring(0, metadataType.length() - 1));
                }
            }
            if (dirName == null)
            {
                return null;
            }

            return "src/" + dirName + "/" + objectName + "/Forms/" + formName + "/Form.form"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        return null;
    }

    /**
     * Collects diagnostic information about the current editor state.
     * Helps understand why WYSIWYG page might not be available.
     *
     * @return diagnostic message
     */
    private String getDiagnosticInfo()
    {
        StringBuilder sb = new StringBuilder();
        
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                sb.append("No active workbench window. ");
            }
            else
            {
                IWorkbenchPage page = window.getActivePage();
                if (page == null)
                {
                    sb.append("No active workbench page. ");
                }
                else
                {
                    IEditorPart activeEditor = page.getActiveEditor();
                    if (activeEditor == null)
                    {
                        sb.append("No active editor. ");
                    }
                    else
                    {
                        sb.append("Active editor: ").append(activeEditor.getClass().getName()).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
                        
                        // Check if it's a FormEditor
                        try
                        {
                            Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
                            if (editorClass.isInstance(activeEditor))
                            {
                                sb.append("Is FormEditor. "); //$NON-NLS-1$
                                
                                // Try to get active page ID
                                try
                                {
                                    Method getActivePageMethod = findMethod(activeEditor.getClass(), "getActivePageId"); //$NON-NLS-1$
                                    if (getActivePageMethod != null)
                                    {
                                        getActivePageMethod.setAccessible(true);
                                        Object pageId = getActivePageMethod.invoke(activeEditor);
                                        sb.append("Active page ID: ").append(pageId).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
                                    }
                                }
                                catch (Exception e)
                                {
                                    sb.append("Could not get active page ID: ").append(e.getMessage()).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
                                }
                            }
                            else
                            {
                                sb.append("Not a FormEditor. "); //$NON-NLS-1$
                            }
                        }
                        catch (Exception e)
                        {
                            sb.append("Could not check FormEditor: ").append(e.getMessage()).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
            
            // Check what getActiveFormEditorPage() returns
            try
            {
                Object formEditorPage = getActiveFormEditorPage();
                if (formEditorPage == null)
                {
                    sb.append("getActiveFormEditorPage() returned null. "); //$NON-NLS-1$
                }
                else
                {
                    sb.append("getActiveFormEditorPage() returned: ").append(formEditorPage.getClass().getName()).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Exception e)
            {
                sb.append("getActiveFormEditorPage() threw exception: ").append(e.getMessage()).append(". "); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (Exception e)
        {
            sb.append("Error collecting diagnostics: ").append(e.getMessage()); //$NON-NLS-1$
        }
        
        return sb.toString();
    }

    // ========== Existing screenshot capture methods ==========

    private Object getActiveFormEditorPage() throws Exception
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        Method method = editorClass.getMethod("getActiveFormEditorPage"); //$NON-NLS-1$
        return method.invoke(null);
    }

    private ImageData extractFormImageData(Object wysiwygViewer) throws Exception
    {
        Object representation = getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        if (representation == null)
        {
            return null;
        }

        Method method = representation.getClass().getDeclaredMethod(FORM_IMAGE_METHOD);
        method.setAccessible(true);
        return (ImageData) method.invoke(representation);
    }

    private ImageData captureControlImageData(Object wysiwygViewer) throws Exception
    {
        Control control = (Control) invokeMethod(wysiwygViewer, GET_CONTROL_METHOD);
        if (control == null || control.isDisposed())
        {
            return null;
        }

        Rectangle bounds = control.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
        {
            return null;
        }

        Image image = new Image(control.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(image);
        try
        {
            control.print(gc);
            return image.getImageData();
        }
        finally
        {
            gc.dispose();
            image.dispose();
        }
    }

    private String encodePng(ImageData imageData)
    {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { imageData };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        loader.save(output, SWT.IMAGE_PNG);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    // ========== Reflection utilities ==========

    private Object invokeMethod(Object target, String methodName) throws Exception
    {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private Object getFieldValue(Object target, String fieldName) throws Exception
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException ex)
            {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Finds a method by name and parameter types, searching up the class hierarchy.
     */
    private Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
    {
        Class<?> type = clazz;
        while (type != null)
        {
            try
            {
                return type.getDeclaredMethod(name, paramTypes);
            }
            catch (NoSuchMethodException e)
            {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}

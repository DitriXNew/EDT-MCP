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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

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
        return ResponseType.IMAGE;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String formPath = params.get("formPath"); //$NON-NLS-1$
        if (formPath != null && !formPath.isEmpty())
        {
            // Extract form name from path (e.g., "Catalog.AccessGroups.Forms.ItemForm" -> "ItemForm")
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
        Activator.logInfo("get_form_screenshot called: projectName=" + projectName + ", formPath=" + formPath //$NON-NLS-1$ //$NON-NLS-2$
            + ", refresh=" + refresh); //$NON-NLS-1$

        // Validate: if formPath is specified, projectName is required
        if (formPath != null && !formPath.isEmpty()
            && (projectName == null || projectName.isEmpty()))
        {
            Activator.logWarning("Validation failed: projectName is required when formPath is provided"); //$NON-NLS-1$
            // Return error as JSON - won't be used as IMAGE, but for error reporting
            return ToolResult.error("projectName is required when formPath is specified").toJson(); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            Activator.logWarning("Display is not available or disposed"); //$NON-NLS-1$
            // Return error as JSON - won't be used as IMAGE, but for error reporting
            return ToolResult.error("Display is not available").toJson(); //$NON-NLS-1$
        }

        AtomicReference<CaptureResult> resultRef = new AtomicReference<>();
        display.syncExec(() -> resultRef.set(captureScreenshot(projectName, formPath, refresh)));
        
        CaptureResult result = resultRef.get();
        if (result.error != null)
        {
            Activator.logWarning("get_form_screenshot finished with error JSON"); //$NON-NLS-1$
            // Return error as JSON
            return result.error;
        }
        
        // Return base64 PNG data directly
        Activator.logInfo("get_form_screenshot success, base64 length=" + result.base64Data.length()); //$NON-NLS-1$
        return result.base64Data;
    }
    
    /**
     * Result of screenshot capture - either base64 data or error JSON.
     */
    private static class CaptureResult
    {
        String base64Data;
        String error;
        
        static CaptureResult success(String base64)
        {
            CaptureResult r = new CaptureResult();
            r.base64Data = base64;
            return r;
        }
        
        static CaptureResult error(String errorJson)
        {
            CaptureResult r = new CaptureResult();
            r.error = errorJson;
            return r;
        }
    }

    /**
     * Main capture logic. Runs on UI thread.
     */
    private CaptureResult captureScreenshot(String projectName, String formPath, boolean refresh)
    {
        try
        {
            Activator.logInfo("captureScreenshot start: formPath=" + formPath + ", refresh=" + refresh); //$NON-NLS-1$ //$NON-NLS-2$
            Object editorPage;

            if (formPath != null && !formPath.isEmpty())
            {
                ensureBufferedNativeRenderMode();

                // Open/activate the specified form
                String openError = openAndActivateForm(projectName, formPath);
                if (openError != null)
                {
                    Activator.logWarning("openAndActivateForm returned error"); //$NON-NLS-1$
                    return CaptureResult.error(openError); // Error occurred
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
                    
                    return CaptureResult.error(ToolResult.error(
                        "Form editor opened but WYSIWYG page is not available. " + //$NON-NLS-1$
                        "The form may still be loading. " + diagnostic).toJson()); //$NON-NLS-1$
                }
            }
            else
            {
                // Use active editor (legacy behavior)
                editorPage = getActiveFormEditorPage();
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "No active form editor page found. " + //$NON-NLS-1$
                        "Specify formPath parameter to open a form automatically.").toJson()); //$NON-NLS-1$
                }
            }

            Activator.logInfo("Editor page class: " + editorPage.getClass().getName()); //$NON-NLS-1$

            Object wysiwygViewer = getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (wysiwygViewer == null)
            {
                return CaptureResult.error(ToolResult.error("WYSIWYG viewer is not available").toJson()); //$NON-NLS-1$
            }

            Activator.logInfo("WYSIWYG viewer class: " + wysiwygViewer.getClass().getName()); //$NON-NLS-1$

            if (refresh)
            {
                Activator.logInfo("Invoking viewer.refresh()"); //$NON-NLS-1$
                invokeMethod(wysiwygViewer, REFRESH_METHOD);
                
                // Give time for refresh to complete
                Display display = Display.getCurrent();
                if (display != null)
                {
                    for (int i = 0; i < 3; i++)
                    {
                        while (display.readAndDispatch())
                        {
                            // Process UI events
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
                Activator.logInfo("viewer.refresh() completed"); //$NON-NLS-1$
            }

            ImageData imageData = extractFormImageData(wysiwygViewer);
            String captureMethod = "form_image_data"; //$NON-NLS-1$
            
            if (imageData == null)
            {
                Activator.logInfo("extractFormImageData returned null, trying control capture"); //$NON-NLS-1$
                imageData = captureControlImageData(wysiwygViewer);
                captureMethod = "control_print"; //$NON-NLS-1$
            }

            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
                return CaptureResult.error(ToolResult.error("Form image data is not available").toJson()); //$NON-NLS-1$
            }

            Activator.logInfo("Captured screenshot: " + imageData.width + "x" + imageData.height + " via " + captureMethod); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            logImageDiagnostics(imageData, "final_image"); //$NON-NLS-1$
            
            String base64 = encodePng(imageData);
            return CaptureResult.success(base64);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to capture form screenshot", e); //$NON-NLS-1$
            return CaptureResult.error(ToolResult.error("Failed to capture form screenshot: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /**
     * Opens a form file in the editor and activates the WYSIWYG (main) page.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return null on success, error JSON on failure
     */
    private String openAndActivateForm(String projectName, String formPath)
    {
        Activator.logInfo("openAndActivateForm start: projectName=" + projectName + ", formPath=" + formPath); //$NON-NLS-1$ //$NON-NLS-2$
        // Resolve the form file
        String relativePath = resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products.Forms.ItemForm') " + //$NON-NLS-1$
                "or 'CommonForm.FormName' (e.g. 'CommonForm.MyForm').").toJson(); //$NON-NLS-1$
        }
        Activator.logInfo("Resolved form path: " + relativePath); //$NON-NLS-1$

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        Activator.logInfo("Project found: " + project.getName() + ", open=" + project.isOpen()); //$NON-NLS-1$ //$NON-NLS-2$

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return ToolResult.error("Form file not found: " + relativePath + " in project " + projectName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Activator.logInfo("Form file found: " + formFile.getFullPath()); //$NON-NLS-1$

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
            return ToolResult.error("No workbench window available").toJson(); //$NON-NLS-1$
        }
        Activator.logInfo("Workbench window acquired"); //$NON-NLS-1$

        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return ToolResult.error("No active workbench page").toJson(); //$NON-NLS-1$
        }
        Activator.logInfo("Workbench page acquired"); //$NON-NLS-1$

        try
        {
            IEditorPart existingEditor = page.findEditor(new FileEditorInput(formFile));
            if (existingEditor != null)
            {
                Activator.logInfo("Closing existing form editor to apply current render mode: " //$NON-NLS-1$
                    + existingEditor.getClass().getName());
                page.closeEditor(existingEditor, false);
            }

            // Open the form file in the form editor
            IEditorPart editorPart = IDE.openEditor(page, formFile, FORM_EDITOR_ID, true);
            if (editorPart == null)
            {
                return ToolResult.error("Could not open form editor for: " + formPath).toJson(); //$NON-NLS-1$
            }
            Activator.logInfo("Editor opened: " + editorPart.getClass().getName()); //$NON-NLS-1$

            // Activate the WYSIWYG (main) page via reflection
            activateFormMainPage(editorPart);

            return null; // Success
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return ToolResult.error("Failed to open form editor: " + e.getMessage()).toJson(); //$NON-NLS-1$
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
                Activator.logInfo("setActivePage method found, invoking with pageId=" + FORM_MAIN_PAGE_ID); //$NON-NLS-1$
                setActivePageMethod.setAccessible(true);
                setActivePageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
                Activator.logInfo("setActivePage invocation completed"); //$NON-NLS-1$
            }
            else
            {
                Activator.logWarning("setActivePage(String) method not found on editor class hierarchy"); //$NON-NLS-1$
            }

            Method getActivePageIdMethod = findMethod(editorPart.getClass(), "getActivePageId"); //$NON-NLS-1$
            if (getActivePageIdMethod != null)
            {
                getActivePageIdMethod.setAccessible(true);
                Object activePageId = getActivePageIdMethod.invoke(editorPart);
                Activator.logInfo("Editor activePageId after activation: " + activePageId); //$NON-NLS-1$
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
        Activator.logInfo("waitForFormEditorPage start: retries=" + WYSIWYG_WAIT_RETRIES + ", intervalMs=" //$NON-NLS-1$ //$NON-NLS-2$
            + WYSIWYG_WAIT_INTERVAL_MS);
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
                    Activator.logInfo("getActiveFormEditorPage class on attempt " + (i + 1) + ": " + page.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
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
                Activator.logInfo("Final page class: " + page.getClass().getName()); //$NON-NLS-1$
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
            Activator.logInfo("wysiwygRepresentation field is null"); //$NON-NLS-1$
            return null;
        }

        Activator.logInfo("wysiwygRepresentation class: " + representation.getClass().getName()); //$NON-NLS-1$
        try
        {
            Activator.logInfo("representation.getControl() class: " //$NON-NLS-1$
                + safeClassName(invokeMethod(representation, GET_CONTROL_METHOD)));
        }
        catch (Exception e)
        {
            Activator.logWarning("representation.getControl() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Activator.logInfo("representation.isRebuildAllowed(): " + invokeMethod(representation, "isRebuildAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("representation.isRebuildAllowed() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Activator.logInfo("representation.getScale(): " + invokeMethod(representation, "getScale")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("representation.getScale() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Activator.logInfo("representation.getResolution(): " + invokeMethod(representation, "getResolution")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("representation.getResolution() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Activator.logInfo("representation.getInterfaceTheme(): " + invokeMethod(representation, "getInterfaceTheme")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("representation.getInterfaceTheme() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Activator.logInfo("representation.getInterfaceMode(): " + invokeMethod(representation, "getInterfaceMode")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logWarning("representation.getInterfaceMode() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        
        // Try to trigger rebuild before capturing
        try
        {
            Method rebuildMethod = representation.getClass().getDeclaredMethod("rebuild", boolean.class); //$NON-NLS-1$
            rebuildMethod.setAccessible(true);
            rebuildMethod.invoke(representation, true);
            Activator.logInfo("Called rebuild(true) on representation"); //$NON-NLS-1$
            
            // Give time for rebuild to complete
            Display display = Display.getCurrent();
            if (display != null)
            {
                for (int i = 0; i < 5; i++)
                {
                    while (display.readAndDispatch())
                    {
                        // Process events
                    }
                    try
                    {
                        Thread.sleep(200);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            Activator.logInfo("Rebuild wait loop completed"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not call rebuild: " + e.getMessage()); //$NON-NLS-1$
        }
        
        // Try getFormImageData
        try
        {
            Method method = representation.getClass().getDeclaredMethod(FORM_IMAGE_METHOD);
            method.setAccessible(true);
            ImageData data = (ImageData) method.invoke(representation);
            
            if (data == null)
            {
                Activator.logInfo("getFormImageData() returned null"); //$NON-NLS-1$
            }
            else
            {
                Activator.logInfo("getFormImageData() returned: " + data.width + "x" + data.height); //$NON-NLS-1$ //$NON-NLS-2$
                logImageDiagnostics(data, "representation_image"); //$NON-NLS-1$
                return data;
            }
        }
        catch (NoSuchMethodException e)
        {
            Activator.logWarning("Method " + FORM_IMAGE_METHOD + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        return null;
    }

    private ImageData captureControlImageData(Object wysiwygViewer) throws Exception
    {
        Control control = (Control) invokeMethod(wysiwygViewer, GET_CONTROL_METHOD);
        if (control == null || control.isDisposed())
        {
            Activator.logWarning("Control is null or disposed"); //$NON-NLS-1$
            return null;
        }

        Activator.logInfo("Control class: " + control.getClass().getName()); //$NON-NLS-1$
        Activator.logInfo("Control visible: " + control.isVisible()); //$NON-NLS-1$
        Activator.logInfo("Control enabled: " + control.isEnabled()); //$NON-NLS-1$
        Activator.logInfo("Control focusControl: " + control.isFocusControl()); //$NON-NLS-1$
        Activator.logInfo("Control parent class: " + safeClassName(control.getParent())); //$NON-NLS-1$
        Activator.logInfo("Control shell class: " + safeClassName(control.getShell())); //$NON-NLS-1$
        Activator.logInfo("Shell visible: " + control.getShell().isVisible() + ", minimized: " //$NON-NLS-1$ //$NON-NLS-2$
            + control.getShell().getMinimized());
        
        Rectangle bounds = control.getBounds();
        Activator.logInfo("Control bounds: " + bounds); //$NON-NLS-1$
        Point displayOrigin = control.toDisplay(0, 0);
        Activator.logInfo("Control display origin: " + displayOrigin); //$NON-NLS-1$
        
        if (bounds.width <= 0 || bounds.height <= 0)
        {
            Activator.logWarning("Control bounds are invalid: " + bounds); //$NON-NLS-1$
            return null;
        }

        // Force the control to update its layout
        control.update();
        
        Image image = new Image(control.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(image);
        try
        {
            // Fill with white background first to see if anything is being drawn
            gc.setBackground(control.getDisplay().getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, bounds.width, bounds.height);
            
            boolean printed = control.print(gc);
            Activator.logInfo("Control.print() result: " + printed); //$NON-NLS-1$

            ImageData data = image.getImageData();
            logImageDiagnostics(data, "control_print_image"); //$NON-NLS-1$
            return data;
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
        String base64 = Base64.getEncoder().encodeToString(output.toByteArray());
        Activator.logInfo("PNG encoded bytes=" + output.size() + ", base64 length=" + base64.length()); //$NON-NLS-1$ //$NON-NLS-2$
        return base64;
    }

    private void ensureBufferedNativeRenderMode()
    {
        final String nativeRenderServiceClass = "com._1c.g5.v8.dt.form.layout.service.NativeRenderService"; //$NON-NLS-1$
        final String bufferedFlagField = "NATIVE_FORM_BUFFERED_LAYOUT_RENDER"; //$NON-NLS-1$
        final String propertyName = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

        try
        {
            System.setProperty(propertyName, "true"); //$NON-NLS-1$
            Activator.logInfo("Set system property " + propertyName + "=true"); //$NON-NLS-1$ //$NON-NLS-2$

            Class<?> serviceClass = Class.forName(nativeRenderServiceClass);
            Method isNativeRenderMethod = serviceClass.getMethod("isNativeRender"); //$NON-NLS-1$
            Method isBufferedRenderMethod = serviceClass.getMethod("isBufferedRender"); //$NON-NLS-1$

            boolean nativeRender = (Boolean) isNativeRenderMethod.invoke(null);
            boolean bufferedBefore = (Boolean) isBufferedRenderMethod.invoke(null);
            Activator.logInfo("NativeRenderService before: isNativeRender=" + nativeRender //$NON-NLS-1$
                + ", isBufferedRender=" + bufferedBefore); //$NON-NLS-1$

            if (nativeRender && !bufferedBefore)
            {
                try
                {
                    Field bufferedField = serviceClass.getDeclaredField(bufferedFlagField);
                    bufferedField.setAccessible(true);
                    bufferedField.setBoolean(null, true);
                    Activator.logInfo("Forced NativeRenderService." + bufferedFlagField + "=true via reflection"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Exception e)
                {
                    Activator.logWarning("Could not force buffered render flag via reflection: " + e.getMessage()); //$NON-NLS-1$
                    if (forceStaticFinalBooleanWithUnsafe(serviceClass, bufferedFlagField, true))
                    {
                        Activator.logInfo("Forced NativeRenderService." + bufferedFlagField + "=true via Unsafe"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }

            boolean bufferedAfter = (Boolean) isBufferedRenderMethod.invoke(null);
            Activator.logInfo("NativeRenderService after: isBufferedRender=" + bufferedAfter); //$NON-NLS-1$

            if (!bufferedAfter)
            {
                Activator.logWarning("Buffered native render is still disabled. " + //$NON-NLS-1$
                    "For reliable screenshots restart EDT with VM option: -DnativeFormBufferedLayoutRender=true"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to ensure buffered native render mode: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void logImageDiagnostics(ImageData data, String label)
    {
        if (data == null)
        {
            Activator.logWarning(label + ": imageData is null"); //$NON-NLS-1$
            return;
        }
        int pixel00 = samplePixel(data, 0, 0);
        int pixelCenter = samplePixel(data, data.width / 2, data.height / 2);
        int pixel1010 = samplePixel(data, 10, 10);
        Activator.logInfo(label + ": depth=" + data.depth + ", alphaData=" + (data.alphaData != null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ", alpha=" + data.alpha + ", transparentPixel=" + data.transparentPixel //$NON-NLS-1$ //$NON-NLS-2$
            + ", p00=" + pixel00 + ", p10_10=" + pixel1010 + ", pCenter=" + pixelCenter); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private int samplePixel(ImageData data, int x, int y)
    {
        if (data == null || data.width <= 0 || data.height <= 0)
        {
            return -1;
        }
        int sx = Math.max(0, Math.min(x, data.width - 1));
        int sy = Math.max(0, Math.min(y, data.height - 1));
        return data.getPixel(sx, sy);
    }

    private String safeClassName(Object value)
    {
        return value == null ? "null" : value.getClass().getName(); //$NON-NLS-1$
    }

    private boolean forceStaticFinalBooleanWithUnsafe(Class<?> targetClass, String fieldName, boolean value)
    {
        try
        {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe"); //$NON-NLS-1$
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe"); //$NON-NLS-1$
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);

            Field targetField = targetClass.getDeclaredField(fieldName);
            targetField.setAccessible(true);

            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class); //$NON-NLS-1$
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class); //$NON-NLS-1$
            Method putBooleanVolatile = unsafeClass.getMethod("putBooleanVolatile", Object.class, long.class, boolean.class); //$NON-NLS-1$

            Object fieldBase = staticFieldBase.invoke(unsafe, targetField);
            long fieldOffset = (Long) staticFieldOffset.invoke(unsafe, targetField);
            putBooleanVolatile.invoke(unsafe, fieldBase, fieldOffset, value);

            return true;
        }
        catch (Exception e)
        {
            Activator.logWarning("Unsafe patch for static final boolean failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
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

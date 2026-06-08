/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.form.FormLayoutSnapshotService;

/**
 * Tool to extract calculated WYSIWYG layout data from an EDT form editor.
 */
public class GetFormLayoutSnapshotTool implements IMcpTool
{
    public static final String NAME = "get_form_layout_snapshot"; //$NON-NLS-1$

    private final FormLayoutSnapshotService service = new FormLayoutSnapshotService();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Return a YAML snapshot of a form's calculated WYSIWYG layout (bounds, element types, " + //$NON-NLS-1$
            "display properties) as text; use it to inspect or compare what a form actually renders. " + //$NON-NLS-1$
            "Requires EDT launched with -DnativeFormBufferedLayoutRender=true, else the result is blank " + //$NON-NLS-1$
            "(missing flag, not a bad call). " + //$NON-NLS-1$
            "Full parameters and examples: call get_tool_guide('get_form_layout_snapshot')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required when formPath is specified.") //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Form FQN (e.g. 'Catalog.Products.Forms.ItemForm' or 'CommonForm.MyForm'); " + //$NON-NLS-1$
                "if omitted, uses the active form editor.") //$NON-NLS-1$
            .booleanProperty("refresh", "Force WYSIWYG refresh before snapshot (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("mode", //$NON-NLS-1$
                "Output mode: 'compact' (default, visible elements only) or 'full' (all nodes/properties)", //$NON-NLS-1$
                "compact", "full") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.TEXT;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String refreshParam = JsonUtils.extractStringArgument(params, "refresh"); //$NON-NLS-1$
        String rawMode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String mode = service.normalizeMode(rawMode);
        boolean refresh = refreshParam == null || "true".equalsIgnoreCase(refreshParam); //$NON-NLS-1$

        if (mode == null)
        {
            return service.errorYaml("Invalid mode: " + rawMode + ". Expected 'compact' or 'full'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (formPath != null && !formPath.isEmpty()
            && (projectName == null || projectName.isEmpty()))
        {
            return service.errorYaml("projectName is required when formPath is specified"); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return service.errorYaml("Display is not available"); //$NON-NLS-1$
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        display.syncExec(() -> resultRef.set(service.captureLayoutSnapshot(projectName, formPath, refresh, mode)));
        return resultRef.get();
    }
}

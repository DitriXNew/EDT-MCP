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
    public String getGuide()
    {
        return "# get_form_layout_snapshot\n\n" //$NON-NLS-1$
            + "Returns a YAML snapshot of a form's **calculated WYSIWYG layout**: per-element " //$NON-NLS-1$
            + "bounds (x/y/width/height), element types, and display-affecting properties, plus " //$NON-NLS-1$
            + "the overall form size. The response type is TEXT (the YAML itself).\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Inspect or compare what a form actually lays out (positions, sizes, visibility) " //$NON-NLS-1$
            + "rather than its declarative `.form` definition.\n" //$NON-NLS-1$
            + "- Verify a layout change took effect, or diff two states of the same form.\n" //$NON-NLS-1$
            + "- For a rendered PNG instead of layout data, use `get_form_screenshot`.\n\n" //$NON-NLS-1$

            + "## Required JVM flag (read this first)\n" //$NON-NLS-1$
            + "EDT must be launched with `-DnativeFormBufferedLayoutRender=true` in the " //$NON-NLS-1$
            + "`1cedt.ini` `-vmargs` section. Without it the layout service is constructed " //$NON-NLS-1$
            + "without an offscreen handler and the snapshot comes back **blank/empty**. A blank " //$NON-NLS-1$
            + "result almost always means the flag is missing - it is NOT a bad call or a code " //$NON-NLS-1$
            + "bug. (The default native render mode also yields only form-level metrics for some " //$NON-NLS-1$
            + "elements; per-element bounds populate when buffered layout render is active.)\n\n" //$NON-NLS-1$

            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` - EDT project name. **Required when `formPath` is specified**; " //$NON-NLS-1$
            + "omitting it then returns an error. Ignored when targeting the active editor.\n" //$NON-NLS-1$
            + "- `formPath` - metadata FQN of the form. If given, the tool opens and activates " //$NON-NLS-1$
            + "that form automatically. If omitted, the currently active form editor is used.\n" //$NON-NLS-1$
            + "- `refresh` - force a WYSIWYG refresh before capturing; default `true`. Set " //$NON-NLS-1$
            + "`false` to read the last-rendered state without re-laying-out.\n" //$NON-NLS-1$
            + "- `mode` - `compact` (default) or `full`; an unknown value returns an error.\n\n" //$NON-NLS-1$

            + "### formPath format\n" //$NON-NLS-1$
            + "`MetadataType.ObjectName.Forms.FormName`, or `CommonForm.FormName` for a common " //$NON-NLS-1$
            + "form. Examples:\n" //$NON-NLS-1$
            + "- `Catalog.Products.Forms.ItemForm`\n" //$NON-NLS-1$
            + "- `Document.SalesOrder.Forms.DocumentForm`\n" //$NON-NLS-1$
            + "- `CommonForm.MyForm`\n\n" //$NON-NLS-1$

            + "## Modes\n" //$NON-NLS-1$
            + "- `compact` (default) - only visual elements with positive bounds, and only " //$NON-NLS-1$
            + "selected display-affecting properties. Best for a readable overview.\n" //$NON-NLS-1$
            + "- `full` - every layout node (including zero-bounds/structural ones) and all " //$NON-NLS-1$
            + "non-containment properties. Verbose; use when you need the complete tree.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Active editor, default compact: `{}`.\n" //$NON-NLS-1$
            + "- Specific form: `{projectName: \"MyProj\", formPath: " //$NON-NLS-1$
            + "\"Catalog.Products.Forms.ItemForm\"}`.\n" //$NON-NLS-1$
            + "- Full tree, no refresh: `{formPath: \"CommonForm.MyForm\", projectName: " //$NON-NLS-1$
            + "\"MyProj\", mode: \"full\", refresh: false}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- Blank result => the `-DnativeFormBufferedLayoutRender=true` flag is missing " //$NON-NLS-1$
            + "(see above), not a failure of this call.\n" //$NON-NLS-1$
            + "- `formPath` without `projectName` is rejected: " //$NON-NLS-1$
            + "\"projectName is required when formPath is specified\".\n" //$NON-NLS-1$
            + "- Needs a live workbench Display; runs on the UI thread.\n" //$NON-NLS-1$
            + "- A \"No calculated element bounds were found\" warning means the form had not " //$NON-NLS-1$
            + "finished rendering yet - retry, or ensure `refresh` is `true`.\n"; //$NON-NLS-1$
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

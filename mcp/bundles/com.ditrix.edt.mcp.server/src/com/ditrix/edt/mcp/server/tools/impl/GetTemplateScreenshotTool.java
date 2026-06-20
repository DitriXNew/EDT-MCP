/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.CaptureResult;
import com.ditrix.edt.mcp.server.utils.TemplateScreenshotHelper;

/**
 * Tool to capture a PNG screenshot of a 1C template (макет) - a {@code SpreadsheetDocument}
 * (табличный документ / print form) - as EDT renders it. Opens the common-template editor by FQN,
 * reaches its embedded spreadsheet editor and rasterizes it off-screen.
 */
public class GetTemplateScreenshotTool implements IMcpTool
{
    public static final String NAME = "get_template_screenshot"; //$NON-NLS-1$

    /** Input param: template FQN to open and capture. */
    private static final String KEY_TEMPLATE_PATH = "templatePath"; //$NON-NLS-1$
    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Capture a PNG screenshot of a 1C template (a SpreadsheetDocument print form) as EDT " + //$NON-NLS-1$
            "renders it, so its layout and text are visible to an AI. Pass a common-template FQN " + //$NON-NLS-1$
            "'CommonTemplate.<Name>'. Renders off-screen (no JVM flag needed). Full parameters and " + //$NON-NLS-1$
            "examples: call get_tool_guide('get_template_screenshot')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT_NAME, "EDT project name.", true) //$NON-NLS-1$
            .stringProperty(KEY_TEMPLATE_PATH,
                "Common-template FQN 'CommonTemplate.<Name>', e.g. 'CommonTemplate.PrintForm'.", true) //$NON-NLS-1$
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
        String templatePath = params.get(KEY_TEMPLATE_PATH);
        if (templatePath != null && !templatePath.isEmpty())
        {
            String[] parts = templatePath.split("\\."); //$NON-NLS-1$
            if (parts.length > 0)
            {
                return parts[parts.length - 1] + ".png"; //$NON-NLS-1$
            }
        }
        return "template.png"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME);
        String templatePath = JsonUtils.extractStringArgument(params, KEY_TEMPLATE_PATH);

        // Pure, Display-free validation up front so a bad call fails fast (and is unit-testable).
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (templatePath == null || templatePath.isEmpty())
        {
            return ToolResult.error("templatePath is required").toJson(); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("Display is not available").toJson(); //$NON-NLS-1$
        }

        AtomicReference<CaptureResult> resultRef = new AtomicReference<>();
        display.syncExec(() -> resultRef.set(TemplateScreenshotHelper.capture(projectName, templatePath)));

        CaptureResult result = resultRef.get();
        if (result == null)
        {
            return ToolResult.error("Template screenshot capture produced no result").toJson(); //$NON-NLS-1$
        }
        if (!result.isSuccess())
        {
            return result.getError();
        }
        return result.getBase64Data();
    }
}

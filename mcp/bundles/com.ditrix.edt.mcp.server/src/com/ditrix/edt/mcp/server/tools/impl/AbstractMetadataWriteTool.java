/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Base class for metadata write tools that mutate the EDT model
 * (create / add / delete) and therefore must run on the UI thread.
 * <p>
 * Centralizes the boilerplate shared by all such tools:
 * <ul>
 * <li>JSON response type;</li>
 * <li>marshalling the call onto the SWT UI thread via {@link Display#syncExec}
 * with unified error handling (logs and returns a {@link ToolResult} error);</li>
 * <li>resolving the {@link IProject} and its {@link Configuration};</li>
 * <li>unwrapping the underlying cause message thrown from a BM write task.</li>
 * </ul>
 * Subclasses implement {@link #executeOnUiThread(Map)}, which is already invoked
 * on the UI thread.
 */
public abstract class AbstractMetadataWriteTool implements IMcpTool
{
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public final String execute(Map<String, String> params)
    {
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeOnUiThread(params));
            }
            catch (Exception e)
            {
                Activator.logError("Error in " + getName(), e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    /**
     * Performs the tool logic. Always invoked on the SWT UI thread, so model
     * mutations are safe here. Any thrown exception is logged and converted to a
     * {@link ToolResult} error by {@link #execute(Map)}.
     *
     * @param params the tool parameters
     * @return the JSON result string
     * @throws Exception on unexpected failure
     */
    protected abstract String executeOnUiThread(Map<String, String> params) throws Exception;

    /**
     * Holds the resolved project and configuration, or a ready-to-return JSON
     * error string when resolution failed.
     */
    protected static final class ProjectContext
    {
        /** Resolved project; non-null only when {@link #error} is null. */
        public IProject project;
        /** Resolved configuration; non-null only when {@link #error} is null. */
        public Configuration config;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        public String error;

        boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves the EDT project and its configuration, applying the same
     * validation and error messages used across the metadata write tools.
     *
     * @param projectName the project name from the tool parameters
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndConfig(String projectName)
    {
        ProjectContext ctx = new ProjectContext();

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            ctx.error = ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            return ctx;
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            ctx.error = ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
            return ctx;
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            ctx.error = ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
            return ctx;
        }

        ctx.project = project;
        ctx.config = config;
        return ctx;
    }

    /**
     * Returns the most specific failure message from an exception thrown by a BM
     * write task: the cause message when present, otherwise the exception's own.
     *
     * @param e the caught exception
     * @return the resolved message
     */
    protected static String unwrapCauseMessage(Exception e)
    {
        String msg = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null)
        {
            msg = e.getCause().getMessage();
        }
        return msg;
    }
}

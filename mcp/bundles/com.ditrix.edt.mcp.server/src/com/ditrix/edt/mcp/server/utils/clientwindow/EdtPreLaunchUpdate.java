// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;

import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;

/** Uses the very same update request/status handler as EDT's runtime-client launch delegate. */
public final class EdtPreLaunchUpdate
{
    @FunctionalInterface public interface UpdatePath { IStatus update() throws Exception; }
    private EdtPreLaunchUpdate() { }

    static void run(boolean requested, UpdatePath path) throws Exception
    {
        if (!requested) { return; }
        IStatus result = path.update();
        if (result == null || !result.isOK())
        {
            throw new IllegalStateException("EDT did not complete pre-launch preparation: "
                + (result == null ? "no update result" : result.getMessage())
                + ". Inspect edt_dialog and the application update state before retrying.");
        }
    }

    static IStatus update(IProject project, IApplication application, String mode, ExecutionContext context,
        IProgressMonitor monitor) throws Exception
    {
        var bundle = Platform.getBundle("com._1c.g5.v8.dt.launching.core");
        if (bundle == null)
        {
            throw new IllegalStateException("EDT's launching.core plugin is unavailable; install the EDT launching plugins and retry.");
        }
        // The exported request class is loaded from its owner; the headless target omits launching.core.
        Class<?> requestType = bundle.loadClass("com._1c.g5.v8.dt.launching.core.ApplicationUpdateRequest");
        Object request = requestType.getConstructor(IProject.class, IApplication.class, ApplicationUpdateType.class,
            ExecutionContext.class, String.class, IProgressMonitor.class)
            .newInstance(project, application, ApplicationUpdateType.INCREMENTAL, context, mode, monitor);
        IStatus status = new Status(IStatus.ERROR, "com._1c.g5.v8.dt.launching.core", 1006,
            "Preparing application for client launch", null);
        var plugin = DebugPlugin.getDefault();
        var handler = plugin == null ? null : plugin.getStatusHandler(status);
        if (handler == null)
        {
            throw new IllegalStateException("EDT's application-update status handler is unavailable; enable launching.ui and retry in the EDT workbench.");
        }
        Object result = handler.handleStatus(status, request);
        if (!(result instanceof IStatus updateStatus))
        {
            throw new IllegalStateException("EDT returned no application-update status; inspect the EDT workspace log and retry.");
        }
        return updateStatus;
    }
}

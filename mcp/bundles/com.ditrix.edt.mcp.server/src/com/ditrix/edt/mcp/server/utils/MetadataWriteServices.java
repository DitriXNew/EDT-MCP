// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Service admission for metadata writes. */
public final class MetadataWriteServices
{
    public final IBmModel model;
    public final IModelObjectFactory factory;
    public final Version version;
    public final String error;
    public final IV8Project v8Project;
    public final Failure failure;

    public enum Failure { SERVICES_UNAVAILABLE, PROJECT_UNAVAILABLE, MODEL_UNAVAILABLE }

    private MetadataWriteServices(IBmModel model, IModelObjectFactory factory, Version version, String error, IV8Project v8Project, Failure failure)
    {
        this.model = model;
        this.factory = factory;
        this.version = version;
        this.error = error;
        this.v8Project = v8Project;
        this.failure = failure;
    }

    public static MetadataWriteServices resolve(IProject project)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IModelObjectFactory factory = Activator.getDefault().getModelObjectFactory();
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (bmModelManager == null || factory == null || v8ProjectManager == null)
        {
            return new MetadataWriteServices(null, null, null, ToolResult.error("The metadata write services " //$NON-NLS-1$
                + "are not available (IBmModelManager / IModelObjectFactory / IV8ProjectManager). " //$NON-NLS-1$
                + "Ensure the EDT workbench is fully started, then retry.").toJson(), null, Failure.SERVICES_UNAVAILABLE); //$NON-NLS-1$
        }
        IV8Project v8Project = v8ProjectManager.getProject(project);
        if (v8Project == null)
        {
            return new MetadataWriteServices(null, null, null, ToolResult.error("No 1C project for: " //$NON-NLS-1$
                + project.getName()).toJson(), null, Failure.PROJECT_UNAVAILABLE);
        }
        IBmModel model = BmModelResolver.resolve(project, bmModelManager).getModel();
        if (model == null)
        {
            return new MetadataWriteServices(null, null, null, ToolResult.error("BM model not available for " //$NON-NLS-1$
                + "project: " + project.getName()).toJson(), v8Project, Failure.MODEL_UNAVAILABLE); //$NON-NLS-1$
        }
        return new MetadataWriteServices(model, factory, v8Project.getVersion(), null, v8Project, null);
    }
}

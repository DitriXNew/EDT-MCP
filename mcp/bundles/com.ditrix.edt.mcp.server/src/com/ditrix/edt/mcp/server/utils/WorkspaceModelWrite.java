// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.operations.OperationType;
import com._1c.g5.v8.derived.operations.WrappingOperation;
import com._1c.g5.v8.derived.pipeline.DerivedDataSegmentBucket;
import com.ditrix.edt.mcp.server.Activator;

/** Root-rule isolation for native BM writes; callers must drain exports after leaving SWT. */
public final class WorkspaceModelWrite
{
    private WorkspaceModelWrite() { }

    public static <T> T run(IWorkspace workspace, IBmModel model, String name, Supplier<T> write)
        throws CoreException
    {
        var provider = Activator.getDefault().getDerivedDataManagerProvider();
        IDerivedDataManager manager = provider == null ? null : provider.get(model);
        if (manager == null)
        {
            throw Refusals.state("Derived-data services are unavailable. Wait for the project to finish loading "
                + "and retry " + name + ". Nothing was changed.");
        }
        return run(workspace, manager, name, write);
    }

    static <T> T run(IWorkspace workspace, IDerivedDataManager manager, String name, Supplier<T> write)
        throws CoreException
    {
        // Own the commit's DD context without an implicit wait under the root rule or on SWT.
        // The metadata writer's export barrier drains submitted exports after both have been released.
        WrappingOperation operation = new WrappingOperation(handle -> { }, OperationType.BACKGROUND,
            name, false, manager);
        try
        {
            operation.setHandle(manager.scheduleOperation(operation, OperationType.BACKGROUND, true,
                DerivedDataSegmentBucket.AFTER_SYNC));
            AtomicReference<T> result = new AtomicReference<>();
            workspace.run(monitor -> result.set(write.get()), workspace.getRoot(), IWorkspace.AVOID_UPDATE,
                new NullProgressMonitor());
            return result.get();
        }
        finally
        {
            operation.finish().run();
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;

/** Prevents EDT's native rename planner from saving unrelated, unfinished editor changes. */
public final class RenameEditorGuard
{
    static final String UNVERIFIED =
        "Could not verify unsaved editor changes for the affected EDT models. Nothing was renamed or saved. " //$NON-NLS-1$
            + "Retry once the projects finish loading. If verification still fails, check get_server_status."; //$NON-NLS-1$

    private RenameEditorGuard()
    {
    }

    static Collection<IProject> dependents(IProject project)
    {
        var activator = Activator.getDefault();
        return activator == null || activator.getV8ProjectManager() == null ? null
            : IDependentProject.getDependent(project, activator.getV8ProjectManager().getProjects());
    }

    public static String refusal(IProject project, Collection<IProject> alreadyDiscovered)
    {
        return refusal(HostKind.current(), project, alreadyDiscovered, RenameEditorGuard::dependents, affected -> {
            var resolution = BmModelResolver.resolve(affected);
            return resolution.isAvailable() ? resolution.getModel() : null;
        });
    }

    static String refusal(HostKind host, IProject project,
        Function<IProject, Collection<IProject>> findDependents, Function<IProject, IBmModel> findModel)
    {
        return refusal(host, project, java.util.List.of(), findDependents, findModel);
    }

    static String refusal(HostKind host, IProject project, Collection<IProject> alreadyDiscovered,
        Function<IProject, Collection<IProject>> findDependents, Function<IProject, IBmModel> findModel)
    {
        if (host != HostKind.WINDOWED)
        {
            return null;
        }
        try
        {
            var dependents = findDependents.apply(project);
            if (dependents == null)
            {
                return UNVERIFIED;
            }
            var affected = new LinkedHashSet<IProject>();
            affected.add(project);
            affected.addAll(alreadyDiscovered);
            affected.addAll(dependents);
            var dirty = new ArrayList<String>();
            for (IProject item : affected)
            {
                var model = findModel.apply(item);
                if (model == null)
                {
                    return UNVERIFIED + " Unverified project: '" + item.getName() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
                }
                // The native planner calls save() on these contexts before loading contributors.
                if (model.getAllLocalContexts().stream().anyMatch(context -> context.isDirty()))
                {
                    dirty.add("'" + item.getName() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (!dirty.isEmpty())
            {
                return "Open EDT editors have unsaved changes in " + String.join(", ", dirty.stream().sorted().toList()) //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Building the rename would make EDT save them without asking, so nothing was renamed or saved. " //$NON-NLS-1$
                    + "Save or discard those edits in EDT, then retry."; //$NON-NLS-1$
            }
        }
        catch (RuntimeException e)
        {
            return UNVERIFIED;
        }
        return null;
    }
}

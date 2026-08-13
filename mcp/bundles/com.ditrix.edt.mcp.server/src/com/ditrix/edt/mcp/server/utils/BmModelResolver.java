/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Resolves a project's BM model and reports the transient state where EDT has a project but has not
 * registered its model.
 * <p>
 * A non-null {@link IBmModelManager} does not imply that {@link IBmModelManager#getModel(IProject)}
 * returns a model. This happens while a project is initializing or its model is being recreated. BM
 * callers must refuse the operation before passing that null into a transaction or an EDT service.
 */
public final class BmModelResolver
{
    private BmModelResolver()
    {
        // Utility class
    }

    /**
     * Resolves the model through the currently registered EDT service.
     *
     * @param project the project whose model is required
     * @return an available model or an unavailable result naming the project
     */
    public static Resolution resolve(IProject project)
    {
        IBmModelManager modelManager = Activator.getDefault().getBmModelManager();
        return resolve(project, modelManager);
    }

    /**
     * Resolves the model through an explicit manager. This overload keeps the null-return contract
     * independently testable without an OSGi service registry.
     *
     * @param project the project whose model is required
     * @param modelManager the manager, or {@code null} while the service is unavailable
     * @return an available model or an unavailable result naming the project
     */
    public static Resolution resolve(IProject project, IBmModelManager modelManager)
    {
        if (project == null || modelManager == null)
        {
            return Resolution.unavailable(projectName(project));
        }
        IBmModel model = modelManager.getModel(project);
        return model == null ? Resolution.unavailable(project.getName()) : Resolution.available(model);
    }

    /**
     * Verifies every model EDT's mdclass refactoring will use: the target project and each dependent
     * project. EDT's refactoring core maps dependent projects through {@code getModel(...)} without
     * filtering nulls, then immediately calls {@code model.getId()}. Checking the same dependency set
     * here turns that platform NPE into a controlled refusal.
     *
     * @param project the project containing the refactoring target
     * @return an available result, or an unavailable result naming the first project without a model
     */
    public static Resolution resolveForRefactoring(IProject project)
    {
        IBmModelManager modelManager = Activator.getDefault().getBmModelManager();
        Resolution target = resolve(project, modelManager);
        if (!target.isAvailable())
        {
            return target;
        }

        IV8ProjectManager projectManager = Activator.getDefault().getV8ProjectManager();
        if (projectManager == null)
        {
            return Resolution.unavailable(projectName(project));
        }

        try
        {
            Collection<IProject> dependentProjects =
                IDependentProject.getDependent(project, projectManager.getProjects());
            return resolveDependentModels(target, modelManager, dependentProjects);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not verify BM models required by a refactoring for project '" //$NON-NLS-1$
                + projectName(project) + "'", e); //$NON-NLS-1$
            return Resolution.unavailable(projectName(project));
        }
    }

    /** Package-visible test seam for EDT's dependent-project collection. */
    static Resolution resolveForRefactoring(IProject project, IBmModelManager modelManager,
        Collection<IProject> dependentProjects)
    {
        Resolution target = resolve(project, modelManager);
        if (!target.isAvailable())
        {
            return target;
        }
        return resolveDependentModels(target, modelManager, dependentProjects);
    }

    private static Resolution resolveDependentModels(Resolution target, IBmModelManager modelManager,
        Collection<IProject> dependentProjects)
    {
        if (dependentProjects == null)
        {
            return target;
        }
        for (IProject dependentProject : dependentProjects)
        {
            Resolution dependent = resolve(dependentProject, modelManager);
            if (!dependent.isAvailable())
            {
                return dependent;
            }
        }
        return target;
    }

    private static String projectName(IProject project)
    {
        return project == null ? "<unknown>" : project.getName(); //$NON-NLS-1$
    }

    /** Result of resolving the target model and, when requested, its refactoring dependencies. */
    public static final class Resolution
    {
        private final IBmModel model;
        private final String unavailableProjectName;

        private Resolution(IBmModel model, String unavailableProjectName)
        {
            this.model = model;
            this.unavailableProjectName = unavailableProjectName;
        }

        private static Resolution available(IBmModel model)
        {
            return new Resolution(model, null);
        }

        private static Resolution unavailable(String projectName)
        {
            return new Resolution(null, projectName);
        }

        /** @return whether a non-null model is available for every checked project */
        public boolean isAvailable()
        {
            return model != null;
        }

        /**
         * @return the target project's model; valid only when {@link #isAvailable()} is {@code true}
         */
        public IBmModel getModel()
        {
            return model;
        }

        /** @return the project whose BM model is unavailable, or {@code null} when available */
        public String getUnavailableProjectName()
        {
            return unavailableProjectName;
        }

        /**
         * Builds the actionable error for a refused operation.
         *
         * @param operationName the MCP tool the caller may retry
         * @param stateStatement what is known about the refused mutation, including punctuation
         * @return a value-naming message with a transient-state explanation and retry action
         */
        public String actionableError(String operationName, String stateStatement)
        {
            return "BM model is not available for project '" + unavailableProjectName + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                + stateStatement + " This is a transient window while EDT reopens the project's " //$NON-NLS-1$
                + "storage; list_projects does not expose BM-model registration and will still " //$NON-NLS-1$
                + "report the project as ready. Wait a few seconds, then retry " + operationName + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}

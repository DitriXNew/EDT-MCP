/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Resolves an MCP {@code projectName} argument to a workspace {@link IProject}
 * and exposes the existence/open predicates that the project tools previously
 * inlined as
 * {@code ResourcesPlugin.getWorkspace().getRoot().getProject(name)} followed by
 * {@code exists()} / {@code isOpen()} checks.
 * <p>
 * Besides resolution, this class owns the <b>standard "project not found"
 * message</b> via {@link #notFoundMessage(String)} — a single actionable wording
 * (names the value AND points at {@code list_projects}) that tools use instead of
 * inlining {@code "Project not found: " + name}, so the not-found error reads the
 * same everywhere. A tool still chooses WHICH checks to apply ({@link #exists()}
 * vs {@link #isOpen()}) and its own wording for the distinct "project is closed"
 * case; only the not-found message and the lookup-and-check boilerplate are shared.
 * <p>
 * Beyond {@link IProject} resolution this also resolves the live
 * {@link Configuration} via {@link #resolveConfiguration()} /
 * {@link #resolveConfiguration(String)} — the read tools' shared
 * {@code IConfigurationProvider.getConfiguration(project)} block, with the same
 * actionable errors. Follow-up (card {@code introduce-project-context-resolver}):
 * extend with cached {@code IV8Project} + BM model-manager resolution so tools
 * stop repeating that chain too. That part works against the live BM model and
 * must be introduced incrementally with end-to-end validation.
 *
 * @see ProjectStateChecker for the complementary readiness (building / derived
 *      data) check.
 */
public final class ProjectContext
{
    private final String projectName;
    private final IProject project;

    private ProjectContext(String projectName, IProject project)
    {
        this.projectName = projectName;
        this.project = project;
    }

    /**
     * Resolves a project handle by name. A {@code null}/empty name short-circuits
     * to an empty context (no workspace access) whose {@link #exists()} is
     * {@code false}; callers treat that the same as "not found".
     *
     * @param projectName the MCP project name argument (may be {@code null})
     * @return a context wrapping the resolved handle (never {@code null})
     */
    public static ProjectContext of(String projectName)
    {
        IProject resolved = (projectName == null || projectName.isEmpty())
            ? null
            : ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        return new ProjectContext(projectName, resolved);
    }

    /**
     * Returns a handle to every project in the workspace (open or closed). This is
     * the shared replacement for an inlined
     * {@code ResourcesPlugin.getWorkspace().getRoot().getProjects()} used by tools
     * that scan across all projects (e.g. a workspace-wide marker scan) rather than
     * one named project. The caller applies its own {@link IProject#isOpen()} /
     * existence filtering, exactly as the inlined form required.
     * <p>
     * Follow-up (card {@code introduce-project-context-resolver}): the remaining
     * {@code tools/impl} tools that still inline the workspace-root enumeration
     * (see {@code ProjectContextAdoptionRatchetTest}) can migrate onto this.
     *
     * @return all projects in the workspace (never {@code null}; possibly empty)
     */
    public static IProject[] allProjects()
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProjects();
    }

    /**
     * Whether {@code project} carries any of {@code natureIds} - answered for a CLOSED project too.
     *
     * <p>{@link IProject#hasNature} and {@link IProject#getDescription} both require an OPEN project,
     * yet the nature of a closed one is a knowable fact: its {@code .project} descriptor is on disk
     * and {@code IWorkspace.loadProjectDescription} reads it without opening anything. Callers that
     * must tell "this project could hold X" from "this project never could" need that answer
     * regardless of the project's state, so the two paths live here rather than in each caller.</p>
     *
     * @param project the project to inspect (may be {@code null})
     * @param natureIds the nature ids to look for
     * @return {@code TRUE}/{@code FALSE} when the natures could be read, and {@code null} when they
     *     could NOT be determined at all - which is never the same statement as "no"
     */
    public static Boolean hasAnyNature(IProject project, Collection<String> natureIds)
    {
        if (natureIds == null || natureIds.isEmpty())
        {
            return null;
        }
        Set<String> natures = naturesOf(project);
        if (natures == null)
        {
            return null;
        }
        for (String natureId : natureIds)
        {
            if (natures.contains(natureId))
            {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * Every nature id {@code project} carries - answered for a CLOSED project too.
     *
     * <p>One read, so a caller that has to ask about several nature FAMILIES (does this project hold
     * a configuration at all? is it an external-objects project?) classifies from a single,
     * self-consistent answer instead of re-reading the descriptor per question.</p>
     *
     * @param project the project to inspect (may be {@code null})
     * @return its nature ids, or {@code null} when they could NOT be determined at all - which is
     *     never the same statement as "it has none"
     */
    public static Set<String> naturesOf(IProject project)
    {
        if (project == null)
        {
            return null;
        }
        try
        {
            IProjectDescription description;
            if (project.isOpen())
            {
                description = project.getDescription();
            }
            else
            {
                // A CLOSED project cannot answer getDescription()/hasNature(), but its .project
                // descriptor is on disk and loadProjectDescription reads it without opening anything.
                IPath location = project.getLocation();
                if (location == null)
                {
                    return null;
                }
                description = ResourcesPlugin.getWorkspace().loadProjectDescription(
                    location.append(IProjectDescription.DESCRIPTION_FILE_NAME));
            }
            return new LinkedHashSet<>(Arrays.asList(description.getNatureIds()));
        }
        catch (CoreException | RuntimeException e)
        {
            // Removed mid-flight, descriptor unreadable, ...: unknowable, and saying "none" here
            // would turn a project we could not classify into proof that it holds nothing.
            return null;
        }
    }

    /**
     * @return the resolved project handle; may be {@code null} (empty name) or a
     *         handle to a project that does not exist in the workspace
     */
    public IProject project()
    {
        return project;
    }

    /**
     * @return the name this context was resolved from (may be {@code null})
     */
    public String name()
    {
        return projectName;
    }

    /**
     * @return {@code true} when the project exists in the workspace
     */
    public boolean exists()
    {
        return project != null && project.exists();
    }

    /**
     * @return {@code true} when the project exists and is open
     */
    public boolean isOpen()
    {
        return exists() && project.isOpen();
    }

    /**
     * The standard, actionable "project not found" error MESSAGE for an unresolved
     * {@code projectName}: it names the offending value AND points the caller at the
     * sibling discovery tool. Wrap it in {@code ToolResult.error(...)} instead of
     * inlining {@code "Project not found: " + projectName}, so every tool surfaces the
     * same actionable not-found error.
     *
     * @param projectName the unresolved project name (the value the caller passed)
     * @return the message naming the value and suggesting {@code list_projects}
     */
    public static String notFoundMessage(String projectName)
    {
        return "Project not found: " + projectName //$NON-NLS-1$
            + ". Use list_projects to see available projects."; //$NON-NLS-1$
    }

    /**
     * Resolves the live {@link Configuration} for THIS already-resolved project via
     * the EDT {@code IConfigurationProvider}. Assumes the project handle exists (call
     * after {@link #exists()}); it does not re-check existence. Use the static
     * {@link #resolveConfiguration(String)} when you still need the not-found check.
     *
     * @return a result carrying the configuration on success, or the matching error
     *         JSON ({@code "Configuration provider not available"} /
     *         {@code "Could not get configuration for project: <name>"}) — the exact
     *         wording the read tools used inline
     */
    public ConfigurationResult resolveConfiguration()
    {
        return resolveRoot(false);
    }

    /**
     * Resolves the ROOT a metadata FQN resolves against for THIS project - the configuration, or an
     * external-objects project's own root objects (issue #309).
     *
     * <p>The difference from {@link #resolveConfiguration()} is one case: an external-objects
     * project linked to NO base configuration resolves successfully here, with a null
     * {@link ConfigurationResult#configuration()} and a usable {@link ConfigurationResult#scope()}.
     * Only a caller that resolves everything through the scope may use this entry; a caller that
     * dereferences the configuration must keep {@link #resolveConfiguration()}, which still refuses
     * that case rather than handing it a null.</p>
     *
     * @return a result carrying the project, the scope and (when there is one) the configuration
     */
    public ConfigurationResult resolveMetadataRoot()
    {
        return resolveRoot(true);
    }

    private ConfigurationResult resolveRoot(boolean allowNoConfiguration)
    {
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return new ConfigurationResult(project, null, null,
                ToolResult.error("Configuration provider not available").toJson()); //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        MetadataScope scope = MetadataScope.of(project, config);
        if (config == null && !(allowNoConfiguration && scope.isExternalObjects()))
        {
            return new ConfigurationResult(project, null, null,
                ToolResult.error(noConfigurationMessage(projectName, scope.isExternalObjects()))
                    .toJson());
        }

        return new ConfigurationResult(project, config, scope, null);
    }

    /**
     * The "no configuration" refusal, told apart by WHY there is none.
     *
     * <p>An external-objects project has no configuration by construction - its roots are its own
     * external data processors / reports - so the generic wording sounds like a transient failure
     * of a project that should have had one. Naming the project kind, and the tools that DO work
     * there, is the difference between "something broke" and "ask a different question"
     * (issue #309).</p>
     *
     * @param projectName the project the caller named
     * @param externalObjects whether the project is an external-objects one
     * @return the message
     */
    public static String noConfigurationMessage(String projectName, boolean externalObjects)
    {
        if (externalObjects)
        {
            return "Project '" + projectName + "' is an EXTERNAL-OBJECTS project with no base " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration, and this operation needs one. Its OWN external data processors / " //$NON-NLS-1$
                + "reports are reachable through get_metadata_objects / get_metadata_details / " //$NON-NLS-1$
                + "create_metadata / modify_metadata / delete_metadata; link the project to a " //$NON-NLS-1$
                + "configuration project to use the rest."; //$NON-NLS-1$
        }
        return "Could not get configuration for project: " + projectName; //$NON-NLS-1$
    }

    /**
     * Resolves a project by name AND its live {@link Configuration} in one step — the
     * full block the read tools shared: {@code ProjectContext.of(name)} →
     * {@link #exists()} → not-found error → {@link #resolveConfiguration()}.
     *
     * @param projectName the MCP project name argument
     * @return a result carrying the project + configuration on success, or the first
     *         matching error JSON (not-found / provider-missing / configuration-missing)
     */
    public static ConfigurationResult resolveConfiguration(String projectName)
    {
        ProjectContext ctx = of(projectName);
        if (!ctx.exists())
        {
            return new ConfigurationResult(null, null, null,
                ToolResult.error(notFoundMessage(projectName)).toJson());
        }
        return ctx.resolveConfiguration();
    }

    /**
     * The {@link #resolveConfiguration(String)} twin that resolves the metadata ROOT instead of
     * insisting on a configuration - see {@link #resolveMetadataRoot()} for which caller may use
     * which (issue #309). The project not-found check is identical.
     *
     * @param projectName the MCP project name argument
     * @return a result carrying the project + scope on success, or the first matching error JSON
     */
    public static ConfigurationResult resolveMetadataRoot(String projectName)
    {
        ProjectContext ctx = of(projectName);
        if (!ctx.exists())
        {
            return new ConfigurationResult(null, null, null,
                ToolResult.error(notFoundMessage(projectName)).toJson());
        }
        return ctx.resolveMetadataRoot();
    }

    /**
     * Outcome of resolving a project's live {@link Configuration}: either the
     * configuration (and its project) or an actionable error JSON, mirroring the
     * inline {@code ToolResult.error(...).toJson()} the tools returned. Check
     * {@link #ok()} first; on failure return {@link #errorJson()} verbatim.
     */
    public static final class ConfigurationResult
    {
        private final IProject project;
        private final Configuration configuration;
        private final MetadataScope scope;
        private final String errorJson;

        private ConfigurationResult(IProject project, Configuration configuration,
            MetadataScope scope, String errorJson)
        {
            this.project = project;
            this.configuration = configuration;
            this.scope = scope;
            this.errorJson = errorJson;
        }

        /** @return {@code true} when the configuration resolved (no error). */
        public boolean ok()
        {
            return errorJson == null;
        }

        /** @return the resolved project handle (may be {@code null} on a not-found error). */
        public IProject project()
        {
            return project;
        }

        /**
         * @return the resolved configuration, or {@code null} on error - and also for an
         *     external-objects project that is linked to no base configuration, which is a SUCCESS
         *     (check {@link #ok()}, not this, for failure)
         */
        public Configuration configuration()
        {
            return configuration;
        }

        /**
         * @return the ROOT a metadata FQN resolves against for this project (the configuration, or
         *     an external-objects project's own root objects); {@code null} on error
         */
        public MetadataScope scope()
        {
            return scope;
        }

        /** @return the error JSON to return from {@code execute}, or {@code null} on success. */
        public String errorJson()
        {
            return errorJson;
        }
    }
}

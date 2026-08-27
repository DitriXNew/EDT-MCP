/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.IAcceptor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeParticipantsResult;

/**
 * Runs the Xtext BSL reference finder over the target project and all of its open extension projects.
 * Source URIs come from the Xtext index itself, so the scope stays aligned with every resource kind the
 * finder knows instead of guessing from a workspace file walk.
 * <p>
 * Extension sources cover two distinct cases. Searching them for the base EObject URI remains a
 * precaution: an adopted object has its own URI, so no direct cross-project BSL reference to that base
 * URI was observed in the fixture. The caller also adds adopted-copy URIs as targets, however, and the
 * fixture deliberately proves that an extension BSL usage of such a copy is found in this source scope.
 * <p>
 * The scope optimization fails CLOSED: unless extension discovery completes, every scoped project is
 * ready, and every indexed URI can be classified as either a workspace resource or a known
 * non-workspace resource, this helper calls {@link IReferenceFinder#findAllReferences} exactly as the
 * previous implementation did.
 * A successful fallback is therefore complete and must remain a successful BSL scan. This is
 * load-bearing for {@code delete_metadata}: its predefined-item safety check uses the same reference
 * scan, and silently searching a partial project set could turn a reference outside that partial set
 * into "no references" and leave it dangling. A slow complete result is strictly preferable to a fast
 * partial result, so the workspace-wide fallback must not be removed or replaced with a partial scoped
 * call.
 */
@SuppressWarnings("restriction")
public final class BslReferenceSearch
{
    private BslReferenceSearch()
    {
        // Utility class.
    }

    /**
     * Finds references using a project-complete source scope when it can be proven, or the complete
     * workspace index otherwise. Both paths are logged at INFO so a slow fallback is diagnosable.
     *
     * @param resourceServiceProvider the BSL resource service provider
     * @param finder the BSL reference finder obtained from the same provider
     * @param baseProject the project that owns the target, or {@code null} when it is unknown
     * @param targetURIs URIs of the target object and its produced types
     * @param acceptor reference-description consumer
     * @param monitor progress monitor for the Xtext scan
     */
    public static void findReferences(IResourceServiceProvider resourceServiceProvider,
        IReferenceFinder finder, IProject baseProject, Iterable<URI> targetURIs,
        IAcceptor<IReferenceDescription> acceptor, IProgressMonitor monitor)
    {
        findReferences(resourceServiceProvider, finder, baseProject, targetURIs, acceptor, monitor,
            ProjectStateChecker.determineCascadeParticipants(baseProject));
    }

    /**
     * Variant for a caller that already resolved participants and needs that same snapshot for related
     * work, such as adding adopted-extension target URIs. An undetermined result forces the complete
     * workspace fallback.
     */
    public static void findReferences(IResourceServiceProvider resourceServiceProvider, IReferenceFinder finder,
        IProject baseProject, Iterable<URI> targetURIs, IAcceptor<IReferenceDescription> acceptor,
        IProgressMonitor monitor, CascadeParticipantsResult cascadeParticipants)
    {
        ScopeResolution scope = resolveScope(resourceServiceProvider, baseProject, cascadeParticipants);
        String projectName = safeProjectName(baseProject);
        if (scope.isScoped())
        {
            Activator.logInfo("BSL reference scan: using scoped Xtext index search for project '" //$NON-NLS-1$
                + projectName + "' (" + scope.projectCount + " project(s), " //$NON-NLS-1$ //$NON-NLS-2$
                + scope.sourceResourceURIs.size() + " indexed resource(s))."); //$NON-NLS-1$
            finder.findReferences(targetURIs, scope.sourceResourceURIs, null, acceptor, monitor);
            return;
        }

        Activator.logInfo("BSL reference scan: scoped source enumeration unavailable for project '" //$NON-NLS-1$
            + projectName + "' (" + scope.failureReason //$NON-NLS-1$
            + "); using complete workspace Xtext index fallback."); //$NON-NLS-1$
        finder.findAllReferences(targetURIs, null, acceptor, monitor);
    }

    private static ScopeResolution resolveScope(IResourceServiceProvider resourceServiceProvider,
        IProject baseProject, CascadeParticipantsResult cascadeParticipants)
    {
        if (resourceServiceProvider == null)
        {
            return ScopeResolution.failure("resource service provider is unavailable"); //$NON-NLS-1$
        }
        if (baseProject == null)
        {
            return ScopeResolution.failure("target project is unknown"); //$NON-NLS-1$
        }

        try
        {
            Set<String> projectNames = scopeProjectNames(baseProject, cascadeParticipants);
            if (projectNames == null || projectNames.isEmpty())
            {
                return ScopeResolution.failure("project scope could not be determined"); //$NON-NLS-1$
            }

            // IReferenceFinder is implemented by Xtext's DelegatingReferenceFinder, whose indexData
            // field is injected as an unqualified IResourceDescriptions from this same provider's
            // injector. Resolve that public interface directly instead of depending on the concrete
            // findrefs type, whose package is restricted to Xtext friend bundles.
            IResourceDescriptions indexData = resourceServiceProvider.get(IResourceDescriptions.class);
            if (indexData == null)
            {
                return ScopeResolution.failure("Xtext index is unavailable"); //$NON-NLS-1$
            }

            Iterable<IResourceDescription> descriptions = indexData.getAllResourceDescriptions();
            if (descriptions == null)
            {
                return ScopeResolution.failure("Xtext index enumeration is unavailable"); //$NON-NLS-1$
            }

            Set<URI> sourceResourceURIs = new LinkedHashSet<>();
            for (IResourceDescription description : descriptions)
            {
                if (description == null || description.getURI() == null)
                {
                    return ScopeResolution.failure(
                        "Xtext index returned an invalid resource description"); //$NON-NLS-1$
                }
                URI resourceURI = description.getURI();
                if (isKnownNonWorkspaceResource(resourceURI))
                {
                    continue;
                }
                if (!resourceURI.isPlatformResource())
                {
                    return ScopeResolution.failure("Xtext index contains an unclassifiable URI scheme: " //$NON-NLS-1$
                        + schemeForLog(resourceURI));
                }
                String resourceProjectName = platformResourceProjectName(resourceURI);
                if (projectNames.contains(resourceProjectName))
                {
                    sourceResourceURIs.add(resourceURI);
                }
            }

            // A READY BSL project can legitimately have no modules. Participant determination has
            // already proved that every scoped project is settled, so an empty source set is complete;
            // project readiness, not an invented per-project resource-count rule, guards this case.
            return ScopeResolution.scoped(new ArrayList<>(sourceResourceURIs), projectNames.size());
        }
        catch (RuntimeException e)
        {
            // Do not return the URIs accumulated before an iterator/provider failure: that would turn
            // an undeterminable scope into a partial search. The caller deliberately widens to all.
            return ScopeResolution.failure("scope enumeration failed: " + e.getClass().getSimpleName()); //$NON-NLS-1$
        }
    }

    private static Set<String> scopeProjectNames(IProject baseProject,
        CascadeParticipantsResult cascadeParticipants)
    {
        if (cascadeParticipants == null || !cascadeParticipants.isDetermined())
        {
            return null;
        }
        Set<String> projectNames = new LinkedHashSet<>();
        if (!addProjectName(projectNames, baseProject))
        {
            return null;
        }
        List<IProject> participants = cascadeParticipants.getParticipants();
        if (participants == null)
        {
            return null;
        }
        for (IProject participant : participants)
        {
            if (!addProjectName(projectNames, participant))
            {
                return null;
            }
        }
        return projectNames;
    }

    private static boolean addProjectName(Set<String> projectNames, IProject project)
    {
        if (project == null)
        {
            return false;
        }
        String projectName = project.getName();
        if (projectName == null || projectName.isEmpty())
        {
            return false;
        }
        projectNames.add(projectName);
        return true;
    }

    private static String platformResourceProjectName(URI uri)
    {
        String platformString = uri.toPlatformString(true);
        if (platformString == null)
        {
            throw new IllegalArgumentException("Platform resource URI has no workspace path"); //$NON-NLS-1$
        }
        IPath path = Path.fromPortableString(platformString);
        if (path.segmentCount() == 0 || path.segment(0).isEmpty())
        {
            throw new IllegalArgumentException("Platform resource URI has no project segment"); //$NON-NLS-1$
        }
        return path.segment(0);
    }

    private static boolean isKnownNonWorkspaceResource(URI uri)
    {
        // Observed non-workspace entries in EDT's BSL index are platform types under v8:/... and
        // resources contributed by installed plug-ins under platform:/plugin/.... Neither can be an
        // IWorkspace resource. Every other form is unknown and therefore forces the complete fallback.
        return uri.isPlatformPlugin() || "v8".equalsIgnoreCase(uri.scheme()); //$NON-NLS-1$
    }

    private static String schemeForLog(URI uri)
    {
        String scheme = uri.scheme();
        return scheme != null && !scheme.isEmpty()
            ? "'" + scheme + "'" //$NON-NLS-1$ //$NON-NLS-2$
            : "<none>"; //$NON-NLS-1$
    }

    private static String safeProjectName(IProject project)
    {
        if (project == null)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
        try
        {
            String name = project.getName();
            return name != null ? name : "<unknown>"; //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            // Logging must not prevent the complete fallback after scope resolution failed.
            return "<unknown>"; //$NON-NLS-1$
        }
    }

    private static final class ScopeResolution
    {
        private final List<URI> sourceResourceURIs;
        private final int projectCount;
        private final String failureReason;

        private ScopeResolution(List<URI> sourceResourceURIs, int projectCount, String failureReason)
        {
            this.sourceResourceURIs = sourceResourceURIs;
            this.projectCount = projectCount;
            this.failureReason = failureReason;
        }

        private static ScopeResolution scoped(List<URI> sourceResourceURIs, int projectCount)
        {
            return new ScopeResolution(sourceResourceURIs, projectCount, null);
        }

        private static ScopeResolution failure(String reason)
        {
            return new ScopeResolution(null, 0, reason);
        }

        private boolean isScoped()
        {
            return sourceResourceURIs != null;
        }
    }
}

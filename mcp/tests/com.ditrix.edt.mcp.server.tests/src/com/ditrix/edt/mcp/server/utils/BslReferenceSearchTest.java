/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.IAcceptor;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeEnvironment;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/**
 * Headless tests for the Xtext-index source scope. The live BSL injector and real dependent-project
 * discovery need an EDT workspace. The existing test_find_references.py fixture exercises the adopted
 * extension-target mapping and pins the live results for the scoped build.
 */
@SuppressWarnings("restriction")
public class BslReferenceSearchTest
{
    @Test
    public void scopedSearchIncludesBaseExtensionsAndExternalObjectsButExcludesUnrelatedProjects()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject extension1 = project("Base.tests"); //$NON-NLS-1$
        IProject extension2 = project("Base.extra"); //$NON-NLS-1$
        IProject externalObjects = project("ExternalObjects"); //$NON-NLS-1$
        IProject unrelatedExtension = project("Other.tests"); //$NON-NLS-1$
        IProject otherBase = project("Other"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI extension1URI =
            platformURI("Base.tests", "src/CommonModules/Extension/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI extension2URI =
            platformURI("Base.extra", "src/CommonModules/Extra/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI erpURI = platformURI("ERP_XML", "src/CommonModules/Erp/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI externalObjectsURI =
            platformURI("ExternalObjects", "src/CommonModules/External/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI serversURI = platformURI("Servers", "src/CommonModules/Server/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

        IResourceDescription baseDescription = description(baseURI);
        IResourceDescription extension1Description = description(extension1URI);
        IResourceDescription extension2Description = description(extension2URI);
        IResourceDescription erpDescription = description(erpURI);
        IResourceDescription externalObjectsDescription = description(externalObjectsURI);
        IResourceDescription serversDescription = description(serversURI);

        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Arrays.asList(baseDescription,
            extension1Description, extension2Description, erpDescription, externalObjectsDescription,
            serversDescription));
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(
            Arrays.asList(base, extension1, extension2, externalObjects, unrelatedExtension));
        when(environment.getOpenDependentNatureProjects()).thenReturn(
            Arrays.asList(extension1, extension2, externalObjects, unrelatedExtension));
        when(environment.resolveBaseProject(extension1)).thenReturn(base);
        when(environment.resolveBaseProject(extension2)).thenReturn(base);
        when(environment.resolveBaseProject(externalObjects)).thenReturn(base);
        when(environment.resolveBaseProject(unrelatedExtension)).thenReturn(otherBase);
        ProjectStateResult ready = state(ProjectState.READY);
        when(environment.getProjectState(any(IProject.class))).thenReturn(ready);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<Iterable<URI>> sources = ArgumentCaptor.forClass((Class)Iterable.class);
        verify(finder).findReferences(eq(targets), sources.capture(), isNull(), eq(acceptor), eq(monitor));
        verify(finder, never()).findAllReferences(any(), any(), any(), any());
        assertEquals(new LinkedHashSet<>(Arrays.asList(baseURI, extension1URI, extension2URI,
            externalObjectsURI)), asSet(sources.getValue()));
    }

    @Test
    public void readyProjectWithNoIndexedModulesUsesScopedEmptySearch()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Collections.emptyList());
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();

        CascadeEnvironment environment = stableEnvironment(base);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<Iterable<URI>> sources = ArgumentCaptor.forClass((Class)Iterable.class);
        verify(finder).findReferences(eq(targets), sources.capture(), isNull(), eq(acceptor), eq(monitor));
        verify(finder, never()).findAllReferences(any(), any(), any(), any());
        assertEquals(Collections.emptySet(), asSet(sources.getValue()));
    }

    @Test
    public void failedEnumerationFallsBackInsteadOfSearchingAccumulatedPartialScope()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IResourceDescription firstDescription =
            description(platformURI("Base", "src/CommonModules/Base/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        Iterator<IResourceDescription> iterator = mock(Iterator.class);
        when(iterator.hasNext()).thenReturn(true, true);
        when(iterator.next()).thenReturn(firstDescription)
            .thenThrow(new IllegalStateException("index failed")); //$NON-NLS-1$

        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(() -> iterator);
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();

        CascadeEnvironment environment = stableEnvironment(base);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    @Test
    public void unregisteredOpenExternalObjectsProjectForcesCompleteWorkspaceFallback()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject unregisteredExternalObjects = project("ExternalObjects"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        IResourceDescription baseDescription = description(baseURI);
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Collections.singletonList(baseDescription));
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Collections.singletonList(base));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Collections.singletonList(unregisteredExternalObjects));

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    @Test
    public void readyExtensionWithNoIndexedModulesStillUsesScopedSearch()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject extension = project("Base.tests"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        IResourceDescription baseDescription = description(baseURI);
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Collections.singletonList(baseDescription));
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = stableEnvironment(base, extension);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<Iterable<URI>> sources = ArgumentCaptor.forClass((Class)Iterable.class);
        verify(finder).findReferences(eq(targets), sources.capture(), isNull(), eq(acceptor), eq(monitor));
        verify(finder, never()).findAllReferences(any(), any(), any(), any());
        assertEquals(Collections.singleton(baseURI), asSet(sources.getValue()));
    }

    @Test
    public void buildingExternalObjectsProjectForcesCompleteWorkspaceFallback()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject externalObjects = project("ExternalObjects"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        IResourceDescription baseDescription = description(baseURI);
        when(index.getAllResourceDescriptions()).thenReturn(Collections.singletonList(baseDescription));
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Arrays.asList(base, externalObjects));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Collections.singletonList(externalObjects));
        when(environment.resolveBaseProject(externalObjects)).thenReturn(base);
        ProjectStateResult ready = state(ProjectState.READY);
        ProjectStateResult building = state(ProjectState.BUILDING);
        when(environment.getProjectState(base)).thenReturn(ready);
        when(environment.getProjectState(externalObjects)).thenReturn(building);
        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    @Test
    public void knownNonWorkspaceResourcesAreSkippedWithoutDisablingScopedSearch()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI platformTypeURI = URI.createURI("v8:/PlatformTypes/8.3.27"); //$NON-NLS-1$
        URI pluginURI = URI.createPlatformPluginURI("com.example.bundle/types.bsl", true); //$NON-NLS-1$
        IResourceDescription platformTypeDescription = description(platformTypeURI);
        IResourceDescription pluginDescription = description(pluginURI);
        IResourceDescription baseDescription = description(baseURI);
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Arrays.asList(platformTypeDescription,
            pluginDescription, baseDescription));
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = stableEnvironment(base);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<Iterable<URI>> sources = ArgumentCaptor.forClass((Class)Iterable.class);
        verify(finder).findReferences(eq(targets), sources.capture(), isNull(), eq(acceptor), eq(monitor));
        verify(finder, never()).findAllReferences(any(), any(), any(), any());
        assertEquals(Collections.singleton(baseURI), asSet(sources.getValue()));
    }

    @Test
    public void unclassifiableUriSchemeFallsBackInsteadOfUsingAccumulatedPartialScope()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        URI unknownURI = URI.createURI("workspace:/Base/src/CommonModules/Unknown/Module.bsl"); //$NON-NLS-1$
        IResourceDescription baseDescription = description(baseURI);
        IResourceDescription unknownDescription = description(unknownURI);
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Arrays.asList(baseDescription,
            unknownDescription));
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = stableEnvironment(base);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    @Test
    public void newlyOpenedDependentDuringEnumerationForcesCompleteWorkspaceFallback()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject externalObjects = project("ExternalObjects"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        IResourceDescription baseDescription = description(baseURI);
        List<IResourceDescription> indexedDescriptions =
            Collections.singletonList(baseDescription);
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(indexedDescriptions);
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        List<IProject> projectsBefore = Collections.singletonList(base);
        List<IProject> projectsAfter = Arrays.asList(base, externalObjects);
        List<IProject> dependenciesBefore = Collections.emptyList();
        List<IProject> dependenciesAfter = Collections.singletonList(externalObjects);
        ProjectStateResult ready = state(ProjectState.READY);
        when(environment.getOpenDtProjects()).thenReturn(projectsBefore).thenReturn(projectsAfter);
        when(environment.getOpenDependentNatureProjects()).thenReturn(dependenciesBefore)
            .thenReturn(dependenciesAfter);
        when(environment.resolveBaseProject(externalObjects)).thenReturn(base);
        when(environment.getProjectState(any(IProject.class))).thenReturn(ready);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    @Test
    public void readinessChangeDuringEnumerationForcesCompleteWorkspaceFallback()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        URI baseURI = platformURI("Base", "src/CommonModules/Base/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        IResourceDescription baseDescription = description(baseURI);
        List<IResourceDescription> indexedDescriptions =
            Collections.singletonList(baseDescription);
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(indexedDescriptions);
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        List<IProject> openProjects = Collections.singletonList(base);
        List<IProject> noDependencies = Collections.emptyList();
        ProjectStateResult ready = state(ProjectState.READY);
        ProjectStateResult building = state(ProjectState.BUILDING);
        when(environment.getOpenDtProjects()).thenReturn(openProjects);
        when(environment.getOpenDependentNatureProjects()).thenReturn(noDependencies);
        when(environment.getProjectState(base)).thenReturn(ready).thenReturn(building);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, environment);

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    private static CascadeEnvironment stableEnvironment(IProject base, IProject... dependencies)
    {
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        List<IProject> openProjects = new ArrayList<>();
        openProjects.add(base);
        openProjects.addAll(Arrays.asList(dependencies));
        when(environment.getOpenDtProjects()).thenReturn(openProjects);
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Arrays.asList(dependencies));
        for (IProject dependency : dependencies)
        {
            when(environment.resolveBaseProject(dependency)).thenReturn(base);
        }
        when(environment.getProjectState(any(IProject.class))).thenReturn(state(ProjectState.READY));
        return environment;
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static ProjectStateResult state(ProjectState state)
    {
        return new ProjectStateResult(state, state.getValue());
    }

    private static URI platformURI(String projectName, String path)
    {
        return URI.createPlatformResourceURI(projectName + "/" + path, true); //$NON-NLS-1$
    }

    private static IResourceDescription description(URI uri)
    {
        IResourceDescription description = mock(IResourceDescription.class);
        when(description.getURI()).thenReturn(uri);
        return description;
    }

    private static IResourceServiceProvider provider(IResourceDescriptions index)
    {
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        when(provider.get(IResourceDescriptions.class)).thenReturn(index);
        return provider;
    }

    private static Set<URI> asSet(Iterable<URI> uris)
    {
        Set<URI> result = new LinkedHashSet<>();
        for (URI uri : uris)
        {
            result.add(uri);
        }
        return result;
    }
}

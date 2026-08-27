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

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeParticipantsResult;

/**
 * Headless tests for the Xtext-index source scope. The live BSL injector and real extension-project
 * discovery need an EDT workspace. The existing test_find_references.py fixture exercises the adopted
 * extension-target mapping and pins the live results for the scoped build.
 */
@SuppressWarnings("restriction")
public class BslReferenceSearchTest
{
    @Test
    public void scopedSearchIncludesBaseAndExtensionsButExcludesUnrelatedProjects()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject extension1 = project("Base.tests"); //$NON-NLS-1$
        IProject extension2 = project("Base.extra"); //$NON-NLS-1$
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
            Arrays.asList(base, extension1, extension2, unrelatedExtension));
        when(environment.getOpenExtensionNatureProjects()).thenReturn(
            Arrays.asList(extension1, extension2, unrelatedExtension));
        when(environment.resolveBaseProject(extension1)).thenReturn(base);
        when(environment.resolveBaseProject(extension2)).thenReturn(base);
        when(environment.resolveBaseProject(unrelatedExtension)).thenReturn(otherBase);
        CascadeParticipantsResult participants =
            ProjectStateChecker.determineCascadeParticipants(base, environment);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, participants);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        ArgumentCaptor<Iterable<URI>> sources = ArgumentCaptor.forClass((Class)Iterable.class);
        verify(finder).findReferences(eq(targets), sources.capture(), isNull(), eq(acceptor), eq(monitor));
        verify(finder, never()).findAllReferences(any(), any(), any(), any());
        assertEquals(new LinkedHashSet<>(Arrays.asList(baseURI, extension1URI, extension2URI)),
            asSet(sources.getValue()));
    }

    @Test
    public void emptyScopedEnumerationFallsBackToCompleteWorkspaceSearch()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenReturn(Collections.emptyList());
        IResourceServiceProvider resourceServiceProvider = provider(index);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        Iterable<URI> targets = Collections.singletonList(URI.createURI("bm:/target")); //$NON-NLS-1$
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };
        NullProgressMonitor monitor = new NullProgressMonitor();

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, CascadeParticipantsResult.determined(Collections.emptyList()));

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
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

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, CascadeParticipantsResult.determined(Collections.emptyList()));

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    @Test
    public void unregisteredOpenExtensionForcesCompleteWorkspaceFallback()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IProject unregisteredExtension = project("Other.tests"); //$NON-NLS-1$
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
        when(environment.getOpenExtensionNatureProjects())
            .thenReturn(Collections.singletonList(unregisteredExtension));
        CascadeParticipantsResult participants =
            ProjectStateChecker.determineCascadeParticipants(base, environment);

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, participants);

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

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, CascadeParticipantsResult.determined(Collections.emptyList()));

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

        BslReferenceSearch.findReferences(resourceServiceProvider, finder, base, targets, acceptor,
            monitor, CascadeParticipantsResult.determined(Collections.emptyList()));

        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder).findAllReferences(eq(targets), isNull(), eq(acceptor), eq(monitor));
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
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

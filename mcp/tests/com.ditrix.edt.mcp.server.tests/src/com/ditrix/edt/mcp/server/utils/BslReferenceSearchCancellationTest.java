/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.IAcceptor;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeEnvironment;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/**
 * Issue #931 review: the caller's monitor bounds the scope enumeration too (two passes over the whole
 * index come before the finder walk), and a cancellation there propagates instead of being widened into
 * the workspace-wide fallback.
 */
public class BslReferenceSearchCancellationTest
{
    private static final int INDEX_SIZE = 1000;

    /** A large index whose iterator counts the entries it hands out. */
    private static IResourceDescriptions countingIndex(AtomicInteger handedOut, RuntimeException failure)
    {
        IResourceDescription description = mock(IResourceDescription.class);
        when(description.getURI()).thenReturn(
            URI.createPlatformResourceURI("Base/src/CommonModules/M/Module.bsl", true)); //$NON-NLS-1$
        IResourceDescriptions index = mock(IResourceDescriptions.class);
        when(index.getAllResourceDescriptions()).thenAnswer(inv -> (Iterable<IResourceDescription>)() ->
            new Iterator<IResourceDescription>()
            {
                private int i;

                @Override
                public boolean hasNext()
                {
                    return i < INDEX_SIZE;
                }

                @Override
                public IResourceDescription next()
                {
                    i++;
                    handedOut.incrementAndGet();
                    if (failure != null)
                    {
                        throw failure;
                    }
                    return description;
                }
            });
        return index;
    }

    private static IResourceServiceProvider provider(IResourceDescriptions index)
    {
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        when(provider.get(IResourceDescriptions.class)).thenReturn(index);
        return provider;
    }

    private static CascadeEnvironment readyEnvironment(IProject base)
    {
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Collections.singletonList(base));
        when(environment.getOpenDependentNatureProjects()).thenReturn(Collections.emptyList());
        when(environment.getOpenExtensionNatureProjects()).thenReturn(Collections.emptyList());
        ProjectStateResult ready = new ProjectStateResult(ProjectState.READY, ProjectState.READY.getValue());
        when(environment.getProjectState(any(IProject.class))).thenReturn(ready);
        return environment;
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    @Test
    public void theMonitorStopsTheScopeEnumerationBeforeTheFinderRuns()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        AtomicInteger handedOut = new AtomicInteger();
        IResourceDescriptions index = countingIndex(handedOut, null);
        IReferenceFinder finder = mock(IReferenceFinder.class);
        AtomicInteger polls = new AtomicInteger();
        NullProgressMonitor monitor = new NullProgressMonitor()
        {
            @Override
            public boolean isCanceled()
            {
                return polls.incrementAndGet() > 5;
            }
        };
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };

        try
        {
            BslReferenceSearch.findReferences(provider(index), finder, base,
                Collections.singletonList(URI.createURI("bm:/target")), acceptor, monitor, //$NON-NLS-1$
                readyEnvironment(base));
            fail("a cancelled enumeration must not go on to a search"); //$NON-NLS-1$
        }
        catch (OperationCanceledException expected)
        {
            // the stop propagates to the caller
        }

        assertTrue("enumeration stops at the cancellation, not after 2 x " + INDEX_SIZE + ": " //$NON-NLS-1$ //$NON-NLS-2$
            + handedOut.get(), handedOut.get() <= 6);
        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
        verify(finder, never()).findAllReferences(any(), any(), any(), any());
    }

    /**
     * A cancellation raised inside the index enumeration is not an undeterminable scope: it must reach
     * the caller (which reports a foreign one as an incomplete scan) instead of being widened into the
     * workspace-wide fallback.
     */
    @Test
    public void aCancellationDuringEnumerationIsNotWidenedIntoTheWorkspaceFallback()
    {
        IProject base = project("Base"); //$NON-NLS-1$
        IResourceDescriptions index = countingIndex(new AtomicInteger(), new OperationCanceledException());
        IReferenceFinder finder = mock(IReferenceFinder.class);
        IAcceptor<IReferenceDescription> acceptor = ignored -> { };

        try
        {
            BslReferenceSearch.findReferences(provider(index), finder, base,
                Collections.singletonList(URI.createURI("bm:/target")), acceptor, new NullProgressMonitor(), //$NON-NLS-1$
                readyEnvironment(base));
            fail("the cancellation must propagate"); //$NON-NLS-1$
        }
        catch (OperationCanceledException expected)
        {
            // propagated, not swallowed into a fallback
        }

        verify(finder, never()).findAllReferences(any(), any(), any(), any());
        verify(finder, never()).findReferences(any(), any(), any(), any(), any());
    }
}

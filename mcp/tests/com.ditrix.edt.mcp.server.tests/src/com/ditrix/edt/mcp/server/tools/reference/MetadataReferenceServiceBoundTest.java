/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.eclipse.xtext.util.IAcceptor;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmCrossReference;
import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceCollector;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceInfo;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/**
 * Issue #931: {@code limit} must bound the TRAVERSAL, and a time budget must stop it with an explicit
 * partial-result marker. The cost model is proven with counting fakes: an index of 1000 candidate
 * modules, each holding one reference, of which a bounded search may visit only {@code limit + 1}; and a
 * line resolution (a module parse in production) that runs only for the references the search keeps.
 * The strict, unbounded scan that guards {@code delete_metadata} is pinned to keep walking everything.
 */
public class MetadataReferenceServiceBoundTest
{
    private static final String FQN = "Catalog.Products"; //$NON-NLS-1$
    private static final int INDEX_SIZE = 1000;

    /** A fake Xtext finder: one candidate resource per reference, cancellation polled per candidate. */
    private static final class CountingFinder implements IReferenceFinder
    {
        final AtomicInteger visited = new AtomicInteger();
        private final Runnable perCandidate;

        CountingFinder(Runnable perCandidate)
        {
            this.perCandidate = perCandidate;
        }

        @Override
        public void findReferences(Iterable<URI> targetURIs, Iterable<URI> sourceResourceURIs,
            ILocalResourceAccess localResourceAccess, IAcceptor<IReferenceDescription> acceptor,
            IProgressMonitor monitor)
        {
            walk(acceptor, monitor);
        }

        @Override
        public void findAllReferences(Iterable<URI> targetURIs, ILocalResourceAccess localResourceAccess,
            IAcceptor<IReferenceDescription> acceptor, IProgressMonitor monitor)
        {
            walk(acceptor, monitor);
        }

        private void walk(IAcceptor<IReferenceDescription> acceptor, IProgressMonitor monitor)
        {
            for (int i = 0; i < INDEX_SIZE; i++)
            {
                // Exactly where Xtext's ReferenceFinder polls: before each candidate resource.
                if (monitor.isCanceled())
                {
                    throw new OperationCanceledException();
                }
                visited.incrementAndGet();
                perCandidate.run();
                IReferenceDescription description = mock(IReferenceDescription.class);
                when(description.getSourceEObjectUri()).thenReturn(URI.createPlatformResourceURI(
                    "/Shop/src/CommonModules/M" + i + "/Module.bsl", true).appendFragment("/0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                acceptor.accept(description);
            }
        }
    }

    /** Production collector with the module parse replaced by a counter. */
    private static final class CountingCollector extends ReferenceCollector
    {
        final AtomicInteger parsed = new AtomicInteger();

        CountingCollector(IBmModel model, int limit, ReferenceSearchBound bound)
        {
            super(project(), model, target(), limit, bound);
        }

        @Override
        int resolveLine(URI sourceUri)
        {
            parsed.incrementAndGet();
            return 7;
        }
    }

    private static IProject project()
    {
        IProject source = mock(IProject.class);
        when(source.getName()).thenReturn("Shop"); //$NON-NLS-1$
        return source;
    }

    /** EcoreUtil.getURI casts a container-less object to InternalEObject to build its fragment. */
    private static IBmObject target()
    {
        IBmObject target = mock(IBmObject.class, withSettings().extraInterfaces(InternalEObject.class));
        when(target.eClass()).thenReturn(EcorePackage.Literals.EOBJECT);
        return target;
    }

    /** A determined, ready scope without extensions: no scan-gap note can appear. */
    private static SearchDependenciesResult readyScope() throws Exception
    {
        Map<String, ProjectState> readiness = new LinkedHashMap<>();
        readiness.put("Shop", ProjectState.READY); //$NON-NLS-1$
        return MetadataReferenceServiceScanGapTest.snapshot(readiness);
    }

    private static IResourceServiceProvider provider(IReferenceFinder finder)
    {
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        when(provider.get(IReferenceFinder.class)).thenReturn(finder);
        return provider;
    }

    private static IBmModel modelWithBackReferences(int count, AtomicInteger examined)
    {
        List<IBmCrossReference> refs = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            IBmObject source = mock(IBmObject.class);
            when(source.eClass()).thenReturn(EcorePackage.Literals.EOBJECT);
            when(source.bmIsTop()).thenReturn(true);
            String fqn = "Document.D" + i; //$NON-NLS-1$
            // The path of a back reference is built once per examined reference.
            when(source.bmGetFqn()).thenAnswer(inv -> {
                examined.incrementAndGet();
                return fqn;
            });
            IBmCrossReference ref = mock(IBmCrossReference.class);
            when(ref.getObject()).thenReturn(source);
            refs.add(ref);
        }
        IBmEngine engine = mock(IBmEngine.class);
        when(engine.getBackReferences(org.mockito.ArgumentMatchers.any(IBmObject.class))).thenReturn(refs);
        IBmModel model = mock(IBmModel.class);
        when(model.getEngine()).thenReturn(engine);
        return model;
    }

    private static int bslRows(List<ReferenceInfo> refs)
    {
        int n = 0;
        for (ReferenceInfo ref : refs)
        {
            if (ref.isBslReference)
            {
                n++;
            }
        }
        return n;
    }

    // ==================== the bound stops the walk ====================

    @Test
    public void aBoundedBslScanStopsTheIndexWalkOneReferencePastTheLimit() throws Exception
    {
        CountingFinder finder = new CountingFinder(() -> { });
        ReferenceSearchBound bound = ReferenceSearchBound.of(2, 60_000L, System::nanoTime);
        CountingCollector collector = new CountingCollector(mock(IBmModel.class), 2, bound);
        SearchDependenciesResult scope = readyScope();

        collector.collectBslReferences(target(), () -> provider(finder), project -> scope);

        // limit + 1 references prove that more exist; nothing beyond them is walked or parsed.
        assertEquals(3, finder.visited.get());
        assertEquals(3, collector.parsed.get());
        assertEquals(ReferenceSearchBound.StopReason.LIMIT, bound.stopReason());
        // Stopping at the bound is not a failed scan.
        assertTrue(collector.isBslScanComplete());
    }

    @Test
    public void aBoundedReportShowsTheLimitAndSaysTheSearchStoppedThere() throws Exception
    {
        CountingFinder finder = new CountingFinder(() -> { });
        ReferenceSearchBound bound = ReferenceSearchBound.of(2, 60_000L, System::nanoTime);
        CountingCollector collector = new CountingCollector(mock(IBmModel.class), 2, bound);
        SearchDependenciesResult scope = readyScope();
        collector.collectBslReferences(target(), () -> provider(finder), project -> scope);

        String report = MetadataReferenceService.formatOutput(FQN, collector);

        assertTrue(report, report.contains(
            "**References shown:** 2 (showing first 2, limit reached; pass a higher limit for more)\n\n")); //$NON-NLS-1$
        assertTrue(report, report.contains(MetadataReferenceService.limitStopNote(2)));
        assertFalse(report, report.contains("**Total references found:**")); //$NON-NLS-1$
        assertFalse(report, report.contains("**Scan incomplete:**")); //$NON-NLS-1$
        // Two module rows, the third (the proof of "more") is not printed.
        assertTrue(report, report.contains("- CommonModules/M0/Module.bsl [Line 7]\n")); //$NON-NLS-1$
        assertTrue(report, report.contains("- CommonModules/M1/Module.bsl [Line 7]\n")); //$NON-NLS-1$
        assertFalse(report, report.contains("CommonModules/M2/")); //$NON-NLS-1$
    }

    @Test
    public void theTimeBudgetStopsTheIndexWalkAndTheReportSaysSo() throws Exception
    {
        AtomicLong now = new AtomicLong();
        // Each candidate costs 10 ms of the clock; the budget is 35 ms.
        CountingFinder finder = new CountingFinder(() -> now.addAndGet(10_000_000L));
        ReferenceSearchBound bound = ReferenceSearchBound.of(100, 35L, now::get);
        CountingCollector collector = new CountingCollector(mock(IBmModel.class), 100, bound);
        SearchDependenciesResult scope = readyScope();

        collector.collectBslReferences(target(), () -> provider(finder), project -> scope);

        assertEquals(ReferenceSearchBound.StopReason.TIME_BUDGET, bound.stopReason());
        assertTrue("the walk stops near the budget, not at the index end: " + finder.visited.get(),
            finder.visited.get() <= 4);
        // The reference whose candidate ran the clock out is not parsed.
        assertTrue(collector.parsed.get() < finder.visited.get());

        String report = MetadataReferenceService.formatOutput(FQN, collector);
        int shown = collector.getAllReferences().size();
        assertTrue(report, report.contains("**References shown:** " + shown + "\n\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report, report.contains(MetadataReferenceService.timeBudgetStopNote(35L, shown)));
        assertFalse(report, report.contains("**Total references found:**")); //$NON-NLS-1$
    }

    @Test
    public void aCompleteBoundedSearchKeepsTheTotalLine() throws Exception
    {
        CountingFinder finder = new CountingFinder(() -> { });
        ReferenceSearchBound bound = ReferenceSearchBound.of(INDEX_SIZE, 60_000L, System::nanoTime);
        CountingCollector collector = new CountingCollector(mock(IBmModel.class), INDEX_SIZE, bound);
        SearchDependenciesResult scope = readyScope();

        collector.collectBslReferences(target(), () -> provider(finder), project -> scope);

        assertEquals(INDEX_SIZE, finder.visited.get());
        assertFalse(bound.isStopped());
        String report = MetadataReferenceService.formatOutput(FQN, collector);
        assertTrue(report, report.startsWith("# References to Catalog.Products\n\n**Total references found:** " //$NON-NLS-1$
            + INDEX_SIZE + "\n\n")); //$NON-NLS-1$
        assertFalse(report, report.contains("**Search stopped early:**")); //$NON-NLS-1$
    }

    @Test
    public void backReferencesStopOnePastTheLimitAndTheBslStepNeverRuns() throws Exception
    {
        AtomicInteger examined = new AtomicInteger();
        IBmModel model = modelWithBackReferences(INDEX_SIZE, examined);
        ReferenceSearchBound bound = ReferenceSearchBound.of(3, 60_000L, System::nanoTime);
        CountingCollector collector = new CountingCollector(model, 3, bound);
        AtomicInteger bslLookups = new AtomicInteger();

        collector.collect(() -> {
            bslLookups.incrementAndGet();
            return null;
        }, project -> null);

        assertEquals(4, examined.get());
        assertEquals(4, collector.getAllReferences().size());
        assertEquals(0, bslLookups.get());
        assertEquals(ReferenceSearchBound.StopReason.LIMIT, bound.stopReason());
        String report = MetadataReferenceService.formatOutput(FQN, collector);
        assertTrue(report, report.contains("**References shown:** 3 (")); //$NON-NLS-1$
        assertTrue(report, report.contains("- Document.D2\n")); //$NON-NLS-1$
        assertFalse(report, report.contains("- Document.D3\n")); //$NON-NLS-1$
    }

    // ==================== strict scans stay unbounded ====================

    /**
     * delete_metadata's fail-closed guard runs the strict scan: neither a limit nor a clock may stop its
     * index walk, so an unseen reference can never read as "no references".
     */
    @Test
    public void theStrictScanStillWalksTheWholeIndex() throws Exception
    {
        CountingFinder finder = new CountingFinder(() -> { });
        CountingCollector collector = new CountingCollector(mock(IBmModel.class), 2,
            ReferenceSearchBound.unbounded());
        SearchDependenciesResult scope = readyScope();

        collector.collectBslReferences(target(), () -> provider(finder), project -> scope);

        assertEquals(INDEX_SIZE, finder.visited.get());
        // The legacy collection cap (limit * 10) still applies to what is kept and parsed.
        assertEquals(20, bslRows(collector.getAllReferences()));
        assertEquals(20, collector.parsed.get());
        assertTrue(collector.isBslScanComplete());
    }

    @Test
    public void theStrictScanStillRunsTheBslStepAfterTheLegacyCap() throws Exception
    {
        AtomicInteger examined = new AtomicInteger();
        IBmModel model = modelWithBackReferences(50, examined);
        CountingCollector collector = new CountingCollector(model, 2, ReferenceSearchBound.unbounded());
        AtomicInteger bslLookups = new AtomicInteger();

        collector.collect(() -> {
            bslLookups.incrementAndGet();
            return null;
        }, project -> null);

        assertEquals(20, collector.getAllReferences().size());
        assertEquals(1, bslLookups.get());
        // An unavailable index stays an incomplete strict scan, exactly as before.
        assertFalse(collector.isBslScanComplete());
    }

    /** A cancellation the bound did not ask for is still a failed scan, not a quiet early stop. */
    @Test
    public void aForeignCancellationStaysAnIncompleteScan() throws Exception
    {
        IReferenceFinder finder = new CountingFinder(() -> {
            throw new OperationCanceledException();
        });
        ReferenceSearchBound bound = ReferenceSearchBound.of(5, 60_000L, System::nanoTime);
        CountingCollector collector = new CountingCollector(mock(IBmModel.class), 5, bound);
        SearchDependenciesResult scope = readyScope();

        collector.collectBslReferences(target(), () -> provider(finder), project -> scope);

        assertFalse(bound.isStopped());
        assertFalse(collector.isBslScanComplete());
    }
}

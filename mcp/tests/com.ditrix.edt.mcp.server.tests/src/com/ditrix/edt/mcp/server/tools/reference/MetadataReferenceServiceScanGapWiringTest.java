/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.junit.Test;

import org.eclipse.emf.ecore.EcorePackage;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceCollector;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ScanGap;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/**
 * Pins the SOURCE of the scan-gap note, not only its helpers: the gap the production BSL step
 * captures from its own search-scope snapshot is the one the production report renders.
 */
public class MetadataReferenceServiceScanGapWiringTest
{
    private static final String FQN = "CommonModule.Calc";
    private static final String EMPTY_REPORT_WITH_NOTE = "# References to CommonModule.Calc\n\n"
        + "**Total references found:** 0\n\n" + MetadataReferenceServiceScanGapTest.NOT_READY
        + "\n\nNo references found.\n";

    private static IBmObject target()
    {
        IBmObject target = mock(IBmObject.class);
        when(target.eClass()).thenReturn(EcorePackage.Literals.EOBJECT);
        return target;
    }

    private static ReferenceCollector collector()
    {
        IProject source = mock(IProject.class);
        when(source.getName()).thenReturn("TestConfiguration");
        return new ReferenceCollector(source, mock(IBmModel.class), target(), 100);
    }

    private static SearchDependenciesResult notReady() throws Exception
    {
        return MetadataReferenceServiceScanGapTest.snapshot(MetadataReferenceServiceScanGapTest.readiness(),
            "TestConfiguration.tests");
    }

    @Test
    public void theCapturedGapIsTheOneTheProductionReportRenders() throws Exception
    {
        ReferenceCollector collector = collector();
        ScanGap gap = collector.captureScanGap(notReady());

        assertNotNull(gap);
        assertEquals(ScanGap.Kind.NOT_READY, gap.kind);
        assertEquals(EMPTY_REPORT_WITH_NOTE, MetadataReferenceService.formatOutput(FQN, collector));
    }

    @Test
    public void aReadyCapturedScopeRendersNoNote() throws Exception
    {
        Map<String, ProjectState> readiness = MetadataReferenceServiceScanGapTest.readiness();
        readiness.put("ExternalObjects", ProjectState.READY);
        ReferenceCollector collector = collector();

        assertNull(collector.captureScanGap(
            MetadataReferenceServiceScanGapTest.snapshot(readiness, "TestConfiguration.tests")));
        assertEquals("# References to CommonModule.Calc\n\n**Total references found:** 0\n\nNo references found.\n",
            MetadataReferenceService.formatOutput(FQN, collector));
    }

    /**
     * Runs the production BSL step itself. The search beyond the capture finds nothing headless (the
     * finder is a mock, and the step's own catch keeps any failure there best-effort); what matters is
     * that the snapshot the step asked for is the one whose gap reaches the report.
     */
    @Test
    public void theBslStepCapturesTheGapFromTheSnapshotItSearchesWith() throws Exception
    {
        ReferenceCollector collector = collector();
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        when(provider.get(IReferenceFinder.class)).thenReturn(mock(IReferenceFinder.class));
        SearchDependenciesResult snapshot = notReady();
        List<IProject> asked = new ArrayList<>();

        collector.collectBslReferences(target(), () -> provider, project -> {
            asked.add(project);
            return snapshot;
        });

        assertEquals(1, asked.size());
        assertEquals("TestConfiguration", asked.get(0).getName());
        assertEquals(EMPTY_REPORT_WITH_NOTE, MetadataReferenceService.formatOutput(FQN, collector));
    }

    @Test
    public void anUnavailableIndexCapturesNoGap() throws Exception
    {
        ReferenceCollector collector = collector();
        collector.collectBslReferences(target(), () -> null, project -> {
            fail("the scope is only sampled once the index is available");
            return null;
        });

        assertFalse(collector.isBslScanComplete());
        assertFalse(MetadataReferenceService.formatOutput(FQN, collector).contains("**Scan incomplete:**"));
    }
}

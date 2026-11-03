/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceCollector;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/**
 * The scan-incomplete note follows the BSL step's actual outcome, not only the readiness snapshot:
 * a ready scope whose adopted copies could not be inspected, or an unavailable finder, still tells
 * the reader the count may be low - and a scan that provably missed nothing never carries the note.
 */
public class MetadataReferenceServiceScanOutcomeNoteTest
{
    private static final String FQN = "CommonModule.Calc";
    static final String GENERIC = "**Scan incomplete:** the BSL code scan for CommonModule.Calc could not "
        + "inspect every module (the code index or adopted copies in extensions were unavailable), so the "
        + "count above may be low. Retry once list_projects reports every project ready.";
    private static final String HEADER = "# References to CommonModule.Calc\n\n**Total references found:** 0\n\n";
    private static final String NO_REFERENCES = "No references found.\n";

    private static ReferenceCollector collector(IBmObject target)
    {
        IProject source = mock(IProject.class);
        when(source.getName()).thenReturn("TestConfiguration");
        return new ReferenceCollector(source, mock(IBmModel.class), target, 100);
    }

    private static IBmObject plainTarget()
    {
        IBmObject target = mock(IBmObject.class);
        when(target.eClass()).thenReturn(EcorePackage.Literals.EOBJECT);
        return target;
    }

    /**
     * A detached predefined item: AdoptedReferenceTargets.resolve deterministically reports the
     * augmentation incomplete (its owner cannot be determined) even on a ready scope.
     */
    private static IBmObject detachedPredefinedItem()
    {
        PredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        item.setName("Detached"); //$NON-NLS-1$
        return (IBmObject)item;
    }

    private static SearchDependenciesResult readyWithExtension() throws Exception
    {
        Map<String, ProjectState> readiness = MetadataReferenceServiceScanGapTest.readiness();
        readiness.put("ExternalObjects", ProjectState.READY);
        return MetadataReferenceServiceScanGapTest.snapshot(readiness, "TestConfiguration.tests");
    }

    private static SearchDependenciesResult notReadyWithoutExtensions() throws Exception
    {
        return MetadataReferenceServiceScanGapTest.snapshot(MetadataReferenceServiceScanGapTest.readiness());
    }

    /** The production BSL step: ready snapshot, so no gap is captured, yet the adopted lookup fails. */
    @Test
    public void aResolveFailureWithAReadySnapshotRendersTheGenericNote() throws Exception
    {
        IBmObject target = detachedPredefinedItem();
        ReferenceCollector collector = collector(target);
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        when(provider.get(IReferenceFinder.class)).thenReturn(mock(IReferenceFinder.class));
        SearchDependenciesResult snapshot = readyWithExtension();

        collector.collectBslReferences(target, () -> provider, project -> snapshot);

        assertFalse(collector.isBslScanComplete());
        assertEquals(HEADER + GENERIC + "\n\n" + NO_REFERENCES,
            MetadataReferenceService.formatOutput(FQN, collector));
    }

    /** The adopted-target outcome alone - nothing else failed - reaches the report. */
    @Test
    public void anIncompleteAugmentationOfAReadyScopeRendersTheGenericNote() throws Exception
    {
        ReferenceCollector collector = collector(plainTarget());
        SearchDependenciesResult snapshot = readyWithExtension();
        assertNull(collector.captureScanGap(snapshot));

        collector.recordAdoptedTargetsOutcome(false, snapshot);

        assertFalse(collector.isBslScanComplete());
        assertEquals(HEADER + GENERIC + "\n\n" + NO_REFERENCES,
            MetadataReferenceService.formatOutput(FQN, collector));
    }

    @Test
    public void aCompleteAugmentationRendersNoNote() throws Exception
    {
        ReferenceCollector collector = collector(plainTarget());
        SearchDependenciesResult snapshot = readyWithExtension();
        assertNull(collector.captureScanGap(snapshot));

        collector.recordAdoptedTargetsOutcome(true, snapshot);

        assertTrue(collector.isBslScanComplete());
        assertEquals(HEADER + NO_REFERENCES, MetadataReferenceService.formatOutput(FQN, collector));
    }

    /**
     * No extension project means no adopted copy can exist: the augmentation's refusal (it checks
     * readiness first, and ExternalObjects is building) stays incomplete for the strict caller but
     * costs the report nothing.
     */
    @Test
    public void aScopeWithoutExtensionsKeepsTheReportFreeOfTheNote() throws Exception
    {
        ReferenceCollector collector = collector(plainTarget());
        SearchDependenciesResult snapshot = notReadyWithoutExtensions();
        assertNull(collector.captureScanGap(snapshot));

        collector.recordAdoptedTargetsOutcome(false, snapshot);

        assertFalse("the strict result stays incomplete", collector.isBslScanComplete());
        assertEquals(HEADER + NO_REFERENCES, MetadataReferenceService.formatOutput(FQN, collector));
    }

    @Test
    public void aCapturedGapKeepsItsSpecificTextOverTheGenericOne() throws Exception
    {
        ReferenceCollector collector = collector(plainTarget());
        SearchDependenciesResult snapshot = MetadataReferenceServiceScanGapTest.snapshot(
            MetadataReferenceServiceScanGapTest.readiness(), "TestConfiguration.tests");
        assertNotNull(collector.captureScanGap(snapshot));

        collector.recordAdoptedTargetsOutcome(false, snapshot);

        assertEquals(HEADER + MetadataReferenceServiceScanGapTest.NOT_READY + "\n\n" + NO_REFERENCES,
            MetadataReferenceService.formatOutput(FQN, collector));
    }

    @Test
    public void anUnavailableFinderRendersTheGenericNote() throws Exception
    {
        ReferenceCollector collector = collector(plainTarget());
        IResourceServiceProvider provider = mock(IResourceServiceProvider.class);
        when(provider.get(IReferenceFinder.class)).thenReturn(null);

        collector.collectBslReferences(plainTarget(), () -> provider, project -> {
            fail("the scope is only sampled once the finder is available");
            return null;
        });

        assertFalse(collector.isBslScanComplete());
        assertEquals(HEADER + GENERIC + "\n\n" + NO_REFERENCES,
            MetadataReferenceService.formatOutput(FQN, collector));
    }

    @Test
    public void anUntouchedCollectorRendersNoNote()
    {
        assertEquals(HEADER + NO_REFERENCES, MetadataReferenceService.formatOutput(FQN, collector(plainTarget())));
    }
}

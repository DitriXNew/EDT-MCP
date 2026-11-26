/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.Test;

import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com.ditrix.edt.mcp.server.HostKind;

/**
 * Issue #975, review round 1. A preview's Xtext passes may hand work to EDT's UI thread, and a BM
 * read transaction holds resource read locks a UI-thread write transaction waits for - so they must
 * run with no BM read of ours open; only the rendering, which never dispatches, reads inside one. And
 * a preview already stopped must not start EDT's uncancellable native pass at all.
 */
public class MetadataRenamePreviewReadBoundaryTest
{
    @Test
    public void testTheXtextPassesRunWithNoReadOfOursOpenAndTheRenderInsideOne() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService(false, ignored -> null);
        List<String> seen = new ArrayList<>();
        service.xtextPasses = new MetadataRenameService.XtextPasses()
        {
            @Override
            public MetadataRenameService.ExactPass exactMatches(IProject project, MdObject target, String newName)
            {
                seen.add("exact@" + service.readDepth());
                return MetadataRenameService.ExactPass.NONE;
            }

            @Override
            public Change edtBslChange(IProject project, MdObject target, String newName)
            {
                seen.add("edt@" + service.readDepth());
                return new NullChange("edt change")
                {
                    @Override
                    public String getName()
                    {
                        seen.add("render@" + service.readDepth());
                        return super.getName();
                    }
                };
            }
        };
        IMdRefactoringService mdService = mdService(refactorings());

        String preview = service.createMdClassRename(null, "CommonModule.Old", "New", target(), false,
            DisableRequest.parse(null), null, 0, new RenameProgress(), mdService);

        assertTrue(preview, preview.contains("action: preview"));
        assertEquals("Both Xtext passes run with no read of ours open: " + seen,
            List.of("exact@0", "edt@0"), seen.subList(0, 2));
        assertTrue("The render reads the passes' output: " + seen, seen.size() > 2);
        assertTrue("The render runs inside one read of ours: " + seen,
            seen.subList(2, seen.size()).stream().allMatch("render@1"::equals));
        assertEquals(0, service.readDepth());
    }

    @Test
    public void testThePreviewIsTheRenderOfThePassesOutput() throws Exception
    {
        MetadataRenameService service = new MetadataRenameService(false, ignored -> null);
        service.xtextPasses = new MetadataRenameService.XtextPasses()
        {
            @Override
            public MetadataRenameService.ExactPass exactMatches(IProject project, MdObject target, String newName)
            {
                return MetadataRenameService.ExactPass.NONE;
            }

            @Override
            public Change edtBslChange(IProject project, MdObject target, String newName)
            {
                return null;
            }
        };
        List<IRefactoring> refactorings = refactorings();
        DataProcessor target = mock(DataProcessor.class);
        when(target.getName()).thenReturn("Old");
        String preview = service.createMdClassRename(null, "DataProcessor.Old", "New", target, false,
            DisableRequest.parse(null), null, 0, new RenameProgress(), mdService(refactorings));

        Method render = MetadataRenameService.class.getDeclaredMethod("renderPreview", String.class, String.class,
            String.class, Collection.class, int.class, Map.class, List.class);
        render.setAccessible(true);
        // ... followed by the fixed mdclass note on the reference updates the rows cannot list (#994).
        assertEquals("The preview must be exactly the render of what the passes produced",
            render.invoke(new MetadataRenameService(), "DataProcessor.Old", "New", "Old", refactorings, 0,
                Map.of(), List.of()) + MetadataRenameService.crossReferenceNote(), preview);
        verify(target, never()).getTemplates();
        verify(refactorings.get(0), never()).perform();
    }

    @Test
    public void testSpreadsheetGuardRefusalNeverStartsTheNativeRename() throws Exception
    {
        Method apply = MetadataRenameService.class.getDeclaredMethod("performRename", IProject.class, String.class,
            String.class, Collection.class, DisableRequest.class, String.class, RenameProgress.class, String.class,
            Supplier.class);
        apply.setAccessible(true);
        List<IRefactoring> groups = refactorings();
        RenameProgress progress = new RenameProgress();
        String reason = "Saved workbook description could not be verified. Nothing was renamed.";
        Supplier<String> refusal = () -> {
            assertEquals(RenameProgress.Phase.CHECKING_SAFETY, progress.getPhase());
            return reason;
        };
        // Keep this apply-boundary unit test independent of the SWT consent dialog. The host-specific
        // spreadsheet-evidence decisions are covered in SpreadsheetRenameGuardTest.
        String property = "eclipse.application";
        String original = System.setProperty(property, HostKind.APPLICATION_ID);
        try
        {
            String result = (String)apply.invoke(new MetadataRenameService(), null, "DataProcessor.Old", "New",
                groups, DisableRequest.parse(null), null, progress, "metadata object", refusal);
            assertTrue(result, result.contains(reason));
            assertEquals(RenameProgress.Phase.CHECKING_SAFETY, progress.getPhase());
            verify(groups.get(0), never()).perform();
        }
        finally
        {
            if (original == null) System.clearProperty(property);
            else System.setProperty(property, original);
        }
    }

    @Test
    public void editorThatBecomesDirtyBeforeApplyStopsTheRefactoring()
        throws Exception
    {
        Method apply = MetadataRenameService.class.getDeclaredMethod("performRename", IProject.class, String.class,
            String.class, Collection.class, DisableRequest.class, String.class, RenameProgress.class, String.class,
            Supplier.class);
        apply.setAccessible(true);
        List<IRefactoring> groups = refactorings();
        RenameProgress progress = new RenameProgress();
        String refusal = "An affected project has unsaved edits";
        var service = new MetadataRenameService(false, ignored -> {
            assertEquals(RenameProgress.Phase.CHECKING_SAFETY, progress.getPhase());
            return refusal;
        });
        String property = "eclipse.application";
        String original = System.setProperty(property, HostKind.APPLICATION_ID);
        try
        {
            String result = (String)apply.invoke(service, null, "Catalog.Old", "New", groups,
                DisableRequest.parse(null), null, progress, "metadata object", (Supplier<String>)() -> {
                    throw new AssertionError("Must not prepare the spreadsheet repair when an editor is dirty");
                });
            assertTrue(result, result.contains(refusal));
            verify(groups.get(0), never()).perform();
        }
        finally
        {
            if (original == null) System.clearProperty(property);
            else System.setProperty(property, original);
        }
    }

    @Test
    public void dirtyCatalogRefusesBothModesBeforeTheNativePlanner()
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("DirtyCatalogProject");
        IMdRefactoringService nativePlanner = mock(IMdRefactoringService.class);
        var service = new MetadataRenameService(false, p -> "Project '" + p.getName()
            + "' has unsaved changes; nothing was renamed or saved.");
        for (boolean confirm : List.of(false, true))
        {
            String result = service.createMdClassRename(project, "Catalog.Old", "New", mock(Catalog.class), confirm,
                DisableRequest.parse(null), null, 0, new RenameProgress(), nativePlanner);
            assertTrue(result, result.contains("DirtyCatalogProject") && result.contains("unsaved changes"));
        }
        verify(nativePlanner, never()).createMdObjectRenameRefactoring(any(), any());
    }

    @Test
    public void changedDependencyEditorsStopConfirmBeforeTheNativePlanner()
    {
        var project = mock(IProject.class);
        var planner = mock(IMdRefactoringService.class);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var service = new MetadataRenameService(false, p -> checks.getAndIncrement() == 0 ? null
            : "Project 'NewDependency' has unsaved changes; nothing was renamed or saved.");
        var owner = mock(DataProcessor.class);
        when(owner.eClass()).thenReturn(org.eclipse.emf.ecore.EcoreFactory.eINSTANCE.createEClass());
        String result = service.createMdClassRename(project, "DataProcessor.Old", "New",
            owner, true, DisableRequest.parse(null), null, 0, new RenameProgress(), planner);
        assertEquals(2, checks.get());
        assertTrue(result, result.contains("NewDependency"));
        verify(planner, never()).createMdObjectRenameRefactoring(any(), any());
    }

    @Test
    public void testAStoppedPreviewNeverStartsEdtsNativePass()
    {
        MetadataRenameService service = new MetadataRenameService(false, ignored -> null);
        service.useMonitor(cancelled());
        IMdRefactoringService mdService = mdService(refactorings());

        assertThrows(OperationCanceledException.class, () -> service.createMdClassRename(null,
            "CommonModule.Old", "New", target(), false, DisableRequest.parse(null), null, 0,
            new RenameProgress(), mdService));
        verify(mdService, never()).createMdObjectRenameRefactoring(any(), any());
    }

    @Test
    public void testAPreviewStoppedDuringEdtsNativePassIsAStopNotAnError()
    {
        MetadataRenameService service = new MetadataRenameService(false, ignored -> null);
        IProgressMonitor monitor = new NullProgressMonitor();
        service.useMonitor(monitor);
        IMdRefactoringService mdService = mock(IMdRefactoringService.class);
        // EDT's LTK createChange NPEs on fParticipants when its pass was cut short by the stop.
        when(mdService.createMdObjectRenameRefactoring(any(), any())).thenAnswer(invocation -> {
            monitor.setCanceled(true);
            throw new NullPointerException("fParticipants");
        });

        assertThrows(OperationCanceledException.class, () -> service.createMdClassRename(null,
            "CommonModule.Old", "New", target(), false, DisableRequest.parse(null), null, 0,
            new RenameProgress(), mdService));
    }

    @Test
    public void testAFailedNativePassThatWasNotStoppedIsReported()
    {
        MetadataRenameService service = new MetadataRenameService(false, ignored -> null);
        IMdRefactoringService mdService = mock(IMdRefactoringService.class);
        when(mdService.createMdObjectRenameRefactoring(any(), any()))
            .thenThrow(new NullPointerException("fParticipants"));
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        String result = service.createMdClassRename(project, "CommonModule.Old", "New", target(), false,
            DisableRequest.parse(null), null, 0, new RenameProgress(), mdService);
        assertTrue(result, result.contains("Could not prepare rename of 'CommonModule.Old'"));
    }

    @Test
    public void testAPreviewStoppedBeforeItStartsResolvesNothing()
    {
        assertThrows(OperationCanceledException.class, () -> new MetadataRenameService().rename(
            "NoSuchProject975", "Catalog.Products", "Goods", false, DisableRequest.parse(null), null, 0,
            new RenameProgress(), cancelled()));
    }

    private static IProgressMonitor cancelled()
    {
        IProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        return monitor;
    }

    private static MdObject target()
    {
        MdObject target = mock(MdObject.class);
        when(target.getName()).thenReturn("Old");
        return target;
    }

    private static List<IRefactoring> refactorings()
    {
        IRefactoring refactoring = mock(IRefactoring.class);
        when(refactoring.getTitle()).thenReturn("Rename");
        when(refactoring.getItems()).thenReturn(List.of());
        return List.of(refactoring);
    }

    private static IMdRefactoringService mdService(List<IRefactoring> refactorings)
    {
        IMdRefactoringService service = mock(IMdRefactoringService.class);
        when(service.createMdObjectRenameRefactoring(any(), any())).thenReturn(refactorings);
        return service;
    }
}

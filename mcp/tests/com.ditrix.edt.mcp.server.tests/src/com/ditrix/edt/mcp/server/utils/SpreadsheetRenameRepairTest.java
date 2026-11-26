// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.refactoring.core.RefactoringSettings;
import com._1c.g5.v8.dt.refactoring.core.RefactoringOperationDescriptor;
import com.ditrix.edt.mcp.server.HostKind;

public class SpreadsheetRenameRepairTest
{
    private static final long OWNER_ID = 1;
    private static final long WORKBOOK_ID = 2;
    private static final SpreadsheetRenameGuard.LanguageKey RU = new SpreadsheetRenameGuard.LanguageKey("ru", "Russian");

    @Test
    public void nativeEditorRenameHasNoMcpParticipant()
    {
        var contributor = new SpreadsheetRenameRepair();
        var settings = new RefactoringSettings();
        var owner = owner(OWNER_ID);
        assertTrue(contributor.createNativePreChanges(owner, "New", settings, null).isEmpty());
        assertNull(contributor.createParticipatingOperation(owner, settings, null));
    }

    @Test
    public void attachesOnlyOnceAndDoesNotCreateAnIndexedChangePoint()
    {
        var contributor = new SpreadsheetRenameRepair();
        var settings = new RefactoringSettings();
        var owner = owner(OWNER_ID);
        try (var repair = SpreadsheetRenameRepair.prepare(mock(IProject.class), owner, true, HostKind.WINDOWED,
            ignored -> List.of()))
        {
            assertTrue(contributor.createNativePreChanges(owner, "New", settings, null).isEmpty());
            assertNull(contributor.createParticipatingOperation(owner(OWNER_ID + 1), settings, null));
            var operation = contributor.createParticipatingOperation(owner, settings, null);
            assertNotNull(operation);
            assertNull(operation.getName());
            assertFalse(operation.isOptional());
            assertTrue(repair.canRestore());
            assertNull(contributor.createParticipatingOperation(owner, settings, null));
        }
        assertNull(contributor.createParticipatingOperation(owner, settings, null));
    }

    @Test
    public void secureUnchangedBytesArmModelRepair()
    {
        IFile file = synchronizedWorkbookFile();
        var candidate = candidate(file, WORKBOOK_ID);
        var owner = owner(OWNER_ID);
        try (var repair = SpreadsheetRenameRepair.prepare(mock(IProject.class), owner, true, HostKind.WINDOWED,
            ignored -> List.of()))
        {
            var descriptor = attach(owner);
            assertNull(SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Old", List.of(candidate), false,
                ignored -> evidence(), repair));
            var transaction = mock(IBmTransaction.class);
            var workbook = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
            var languages = MoxelFactory.eINSTANCE.createLanguageSettings();
            var language = MoxelFactory.eINSTANCE.createLanguageInfo();
            language.setLanguageId(RU.id());
            language.setLanguageCode(RU.code());
            languages.getLanguages().add(language);
            workbook.setLanguageSettings(languages);
            when(transaction.getObjectById(WORKBOOK_ID)).thenReturn((IBmObject)workbook);
            // Missing workbook must skip before a later invocation restores the present one.
            var operation = (SpreadsheetRenameRepair.RepairOperation)descriptor.getOperation();
            operation.setActiveTransaction(mock(IBmTransaction.class));
            operation.perform();
            operation.setActiveTransaction(transaction);
            operation.perform();
            assertEquals("Saved", language.getDescription());
            verify(transaction, never()).getObjectById(OWNER_ID);
        }
    }

    @Test
    public void refusesStaleEvidenceNoIdPreviewAndExtension()
    {
        IFile file = synchronizedWorkbookFile();
        var owner = owner(OWNER_ID);
        var project = mock(IProject.class);
        try (var repair = SpreadsheetRenameRepair.prepare(project, owner, true, HostKind.WINDOWED, ignored -> List.of()))
        {
            attach(owner);
            assertRefused(candidate(file, IBmObject.BM_NULL_ID), false, repair);
            assertRefused(candidate(file, WORKBOOK_ID), true, repair);
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            assertTrue(SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Old", List.of(candidate(file, WORKBOOK_ID)),
                false, ignored -> reads.getAndIncrement() == 0 ? evidence()
                    : ProjectSpreadsheetReader.Evidence.securelyRead(Map.of(RU, "Changed")), repair).contains("Nothing was renamed"));
        }
        try (var preview = SpreadsheetRenameRepair.prepare(project, owner, false, HostKind.WINDOWED, ignored -> List.of()))
        {
            assertRefused(candidate(file, WORKBOOK_ID), false, preview);
        }
        try (var cascade = SpreadsheetRenameRepair.prepare(project, owner, true, HostKind.WINDOWED,
            ignored -> List.of(mock(IProject.class))))
        {
            assertRefused(candidate(file, WORKBOOK_ID), false, cascade);
        }
    }

    @Test
    public void refusesWhenDependencyLookupFailsBeforeNativePlanning()
    {
        var project = mock(IProject.class);
        var owner = owner(OWNER_ID);
        var failure = new IllegalStateException("raw dependency error");
        for (boolean unavailable : new boolean[] {false, true})
        {
            try (var scope = SpreadsheetRenameRepair.prepare(project, owner, true, HostKind.WINDOWED, ignored -> {
                if (unavailable)
                {
                    return null;
                }
                throw failure;
            }))
            {
                org.junit.Assert.fail("An unverified dependency lookup must not admit native planning");
            }
            catch (SpreadsheetRenameRepair.DependencyLookupException e)
            {
                assertEquals(unavailable ? null : failure, e.getCause());
                assertRecoveryRefusal(e.getMessage());
            }
        }
    }

    @Test
    public void refusesAfterPlanningWhenRepeatLookupCannotVerifyDependencies()
    {
        var project = mock(IProject.class);
        var owner = owner(OWNER_ID);
        var candidate = candidate(synchronizedWorkbookFile(), WORKBOOK_ID);
        for (boolean unavailable : new boolean[] {false, true})
        {
            var lookups = new java.util.concurrent.atomic.AtomicInteger();
            try (var repair = SpreadsheetRenameRepair.prepare(project, owner, true, HostKind.WINDOWED, ignored -> {
                if (lookups.getAndIncrement() == 0)
                {
                    return List.of();
                }
                if (unavailable)
                {
                    return null;
                }
                throw new IllegalStateException("raw dependency error");
            }))
            {
                assertNotNull(attach(owner));
                String refusal = SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Old", List.of(candidate),
                    false, ignored -> { throw new AssertionError("must refuse before reading the workbook"); }, repair);
                assertRecoveryRefusal(refusal);
                assertEquals(2, lookups.get());
            }
        }
    }

    private static void assertRecoveryRefusal(String refusal)
    {
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Nothing was renamed or saved"));
        assertTrue(refusal, refusal.contains("Retry once the projects finish loading"));
        assertTrue(refusal, refusal.contains("get_server_status"));
        assertFalse(refusal, refusal.contains("raw dependency error"));
    }

    private static void assertRefused(SpreadsheetRenameGuard.Candidate candidate, boolean extension,
        SpreadsheetRenameRepair.Scope repair)
    {
        var refusal = SpreadsheetRenameGuard.refusal("Example", "DataProcessor.Old", List.of(candidate), extension,
            ignored -> evidence(), repair);
        assertTrue(refusal, refusal != null && refusal.contains("Nothing was renamed"));
    }

    private static RefactoringOperationDescriptor attach(DataProcessor owner)
    {
        var contributor = new SpreadsheetRenameRepair();
        var settings = new RefactoringSettings();
        contributor.createNativePreChanges(owner, "New", settings, null);
        return contributor.createParticipatingOperation(owner, settings, null);
    }

    private static DataProcessor owner(long id)
    {
        var owner = mock(DataProcessor.class, withSettings().extraInterfaces(IBmObject.class));
        when(((IBmObject)owner).bmGetId()).thenReturn(id);
        return owner;
    }

    private static IFile synchronizedWorkbookFile()
    {
        var file = mock(IFile.class);
        when(file.exists()).thenReturn(true);
        when(file.isSynchronized(IResource.DEPTH_ZERO)).thenReturn(true);
        when(file.getModificationStamp()).thenReturn(7L);
        return file;
    }

    private static SpreadsheetRenameGuard.Candidate candidate(IFile file, long id)
    {
        return new SpreadsheetRenameGuard.Candidate("Invoice", file, Set.of(RU), id);
    }

    private static ProjectSpreadsheetReader.Evidence evidence()
    {
        return ProjectSpreadsheetReader.Evidence.securelyRead(Map.of(RU, "Saved"));
    }
}

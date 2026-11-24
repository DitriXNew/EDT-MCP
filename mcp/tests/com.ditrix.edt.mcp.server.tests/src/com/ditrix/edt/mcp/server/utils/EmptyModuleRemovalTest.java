// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.junit.Test;

public class EmptyModuleRemovalTest
{
    @Test
    public void acceptsEmptyAndWhitespaceButChecksTheRevision()
    {
        EmptyModuleRemoval.requireEmptyRevision("", ContentHash.of(""));
        EmptyModuleRemoval.requireEmptyRevision(" \t\r\n", ContentHash.of(" \t\n"));
        var e = assertThrows(IllegalStateException.class,
            () -> EmptyModuleRemoval.requireEmptyRevision(" ", ContentHash.of("")));
        assertTrue(e.getMessage().contains("expectedHash does not match"));
    }

    @Test
    public void doesNotConsiderCommentsDirectivesOrCodeEmpty()
    {
        for (String text : new String[] { "// only a comment\n", "#Region Named\n#EndRegion\n",
            "Procedure Keep() Export\nEndProcedure\n" })
        {
            var e = assertThrows(IllegalArgumentException.class,
                () -> EmptyModuleRemoval.requireEmptyRevision(text, ContentHash.of(text)));
            assertTrue(e.getMessage().contains("only removes a blank module"));
        }
    }

    @Test
    public void cleanTypedFileEditorBlocksDeletionWithoutAResourceAdapter() throws Exception
    {
        IFile module = mock(IFile.class);
        IFile owner = mock(IFile.class);
        IFileEditorInput input = mock(IFileEditorInput.class);
        when(input.getFile()).thenReturn(module);
        IEditorReference editor = editor(input);
        assertBlocked(editor, module, owner);
    }

    @Test
    public void cleanOwnerEditorBlocksWhenItAdaptsOnlyToIFile() throws Exception
    {
        IFile module = mock(IFile.class);
        IFile owner = mock(IFile.class);
        IEditorInput input = mock(IEditorInput.class);
        when(input.getAdapter(IFile.class)).thenReturn(owner);
        assertBlocked(editor(input), module, owner);
    }

    @Test
    public void resourceAdapterStillIdentifiesTheOwnerAndUnrelatedFilesDoNotBlock() throws Exception
    {
        IFile module = mock(IFile.class);
        IFile owner = mock(IFile.class);
        IEditorInput input = mock(IEditorInput.class);
        when(input.getAdapter(IResource.class)).thenReturn(owner);
        assertBlocked(editor(input), module, owner);
        IFile unrelated = mock(IFile.class);
        when(input.getAdapter(IResource.class)).thenReturn(unrelated);
        EmptyModuleRemoval.Windowed.requireEditorDoesNotBlock(editor(input), module, owner);
    }

    @Test
    public void unidentifiableDirtyEditorStillBlocksDeletion() throws Exception
    {
        IEditorReference editor = editor(null);
        when(editor.isDirty()).thenReturn(true);
        assertBlocked(editor, mock(IFile.class), mock(IFile.class));
    }

    @Test
    public void resolvesTheSameOwnerUnderStandardAndNonstandardSourceRoots()
    {
        IFile module = mock(IFile.class);
        when(module.getProjectRelativePath()).thenReturn(new Path("src/Catalogs/Goods/ObjectModule.bsl"));
        assertEquals("Catalog.Goods", EmptyModuleRemoval.sourceOwnerFqn(module));
        when(module.getProjectRelativePath()).thenReturn(new Path("source/Catalogs/Goods/ObjectModule.bsl"));
        assertEquals("Catalog.Goods", EmptyModuleRemoval.sourceOwnerFqn(module));
        when(module.getProjectRelativePath()).thenReturn(new Path("source/Catalogs/\u0422\u043e\u0432\u0430\u0440/ManagerModule.bsl"));
        assertEquals("Catalog.\u0422\u043e\u0432\u0430\u0440", EmptyModuleRemoval.sourceOwnerFqn(module));
        when(module.getProjectRelativePath()).thenReturn(new Path("source/Catalogs/Goods/Forms/Item/Module.bsl"));
        assertNull(EmptyModuleRemoval.sourceOwnerFqn(module));
    }

    @Test
    public void callerInterruptionPreventsLateAdmission() throws Exception
    {
        var gate = new EmptyModuleRemoval.CommitGate();
        var job = Job.create("Test deferred deletion admission", monitor -> Status.OK_STATUS);
        job.setSystem(true);
        job.schedule(TimeUnit.MINUTES.toMillis(1));
        Thread.currentThread().interrupt();
        try
        {
            assertSame(Status.CANCEL_STATUS, EmptyModuleRemoval.Windowed.await(job, gate));
            assertTrue("the interrupted flag remains set", Thread.currentThread().isInterrupted());
            assertFalse("a late callback must not enter the mutation", gate.enter());
        }
        finally
        {
            Thread.interrupted();
            job.cancel();
            job.join();
        }
    }

    @Test
    public void suspendedJobManagerCannotLeaveADeferredMutationAfterReturning() throws Exception
    {
        var gate = new EmptyModuleRemoval.CommitGate();
        var entered = new AtomicBoolean();
        var job = Job.create("Test suspended deletion admission", monitor -> {
            entered.set(gate.enter());
            return Status.OK_STATUS;
        });
        var manager = Job.getJobManager();
        job.setSystem(true);
        manager.suspend();
        try
        {
            job.schedule();
            assertNull("the suspended manager holds a job that has not completed", job.getResult());
            assertSame(Status.CANCEL_STATUS, EmptyModuleRemoval.Windowed.await(job, gate));
        }
        finally
        {
            manager.resume();
            job.join();
        }
        assertFalse("resume cannot enter a mutation after the caller was told it was cancelled", entered.get());
        assertFalse(gate.enter());
    }

    @Test
    public void interruptedCallerWaitsForTheActualResultAfterAdmission() throws Exception
    {
        var gate = new EmptyModuleRemoval.CommitGate();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var result = new AtomicReference<IStatus>();
        var interrupted = new AtomicBoolean();
        var job = Job.create("Test admitted deletion", monitor -> {
            if (!gate.enter())
            {
                return Status.CANCEL_STATUS;
            }
            started.countDown();
            try
            {
                return release.await(5, TimeUnit.SECONDS) ? Status.OK_STATUS : Status.CANCEL_STATUS;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            }
        });
        job.setSystem(true);
        job.schedule();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(EmptyModuleRemoval.Windowed.await(job, gate));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try
        {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.start();
            // Joining the caller cannot finish while the admitted operation is still running.
            caller.join(100);
            assertTrue(caller.isAlive());
            assertNull(result.get());
            release.countDown();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertSame(Status.OK_STATUS, result.get());
            assertTrue(interrupted.get());
        }
        finally
        {
            release.countDown();
            job.join();
            caller.join(5000);
        }
    }

    private static IEditorReference editor(IEditorInput input) throws Exception
    {
        IEditorReference editor = mock(IEditorReference.class);
        when(editor.getEditorInput()).thenReturn(input);
        return editor;
    }

    private static void assertBlocked(IEditorReference editor, IFile module, IFile owner)
    {
        var error = assertThrows(IllegalStateException.class,
            () -> EmptyModuleRemoval.Windowed.requireEditorDoesNotBlock(editor, module, owner));
        assertTrue(error.getMessage().contains("Close the module and its owning object's editors")); //$NON-NLS-1$
    }
}
